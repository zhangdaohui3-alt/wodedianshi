package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/** Conservative bounded refresh plan for an explicit single-station refresh. */
public final class SingleChannelRefreshPolicy {
    private SingleChannelRefreshPolicy(){}
    /** The same request deadline applies to queue wait, search, probe and commit. */
    static boolean requestExpired(long now,long deadline){return now>=deadline;}
    static boolean shouldCancelRequest(boolean cancelled,long admittedPressure,long currentPressure,
            long now,long deadline){
        return cancelled||admittedPressure!=currentPressure||requestExpired(now,deadline);
    }
    /** Match only a strong shared station identity, never the host/name
     * fallback used by the broad discovery catalogue. Conflicting metadata
     * rejects a candidate rather than resolving it by guesswork. */
    public static boolean matchesStation(String name,String group,String tvgId,String tvgName,
            String candidateName,String candidateGroup,String candidateId,String candidateGuide) {
        String region=RegionCategoryPolicy.subcategory(metadata(name,tvgName,tvgId),group);
        String otherRegion=RegionCategoryPolicy.subcategory(
                metadata(candidateName,candidateGuide,candidateId),candidateGroup);
        if(!region.equals(otherRegion))return false;
        String first=ChannelNamePolicy.consistentBroadcaster(name,tvgName,tvgId);
        String second=ChannelNamePolicy.consistentBroadcaster(candidateName,candidateGuide,candidateId);
        if(first==null||second==null)return false;
        if(!first.isEmpty()||!second.isEmpty())
            return region.isEmpty()&&!first.isEmpty()&&first.equals(second);
        String firstMeta=metadata(name,tvgName,tvgId)+" "+safe(group);
        String secondMeta=metadata(candidateName,candidateGuide,candidateId)+" "+safe(candidateGroup);
        String province=ChannelNamePolicy.localProvinceEvidence(firstMeta);
        String otherProvince=ChannelNamePolicy.localProvinceEvidence(secondMeta);
        // A contradictory location cannot fall back to matching EPG IDs.
        if(province==null||otherProvince==null)return false;
        if(!province.isEmpty()&&!otherProvince.isEmpty()&&!province.equals(otherProvince))return false;
        String city=ChannelNamePolicy.localCityEvidence(firstMeta,province);
        String otherCity=ChannelNamePolicy.localCityEvidence(secondMeta,otherProvince);
        if(city==null||otherCity==null)return false;
        if(!city.isEmpty()&&!otherCity.isEmpty()&&!city.equals(otherCity))return false;
        // Prefer the established strong city/station identity. An exact
        // display translation may also bridge English and Chinese labels.
        String a=localIdentity(name,tvgName,tvgId,group);
        String b=localIdentity(candidateName,candidateGuide,candidateId,candidateGroup);
        if(!a.isEmpty()&&!b.isEmpty())return a.equals(b);
        String id=ChannelIdentityPolicy.normalizeTvgId(tvgId);
        String otherId=ChannelIdentityPolicy.normalizeTvgId(candidateId);
        // Require exact stable EPG IDs AND a matching specific display/guide
        // label for other stations; generic labels alone are never enough.
        return !id.isEmpty()&&id.equals(otherId)
                &&(sameLabel(name,candidateName)||sameLabel(name,candidateGuide)
                    ||sameLabel(tvgName,candidateName)||sameLabel(tvgName,candidateGuide));
    }

    private static String localIdentity(String name,String guide,String id,String group){
        String direct=ChannelIdentityPolicy.localStationIdentity(name,guide);
        if(!direct.isEmpty())return direct;
        String translated=ChannelNamePolicy.canonicalLocalDisplayName(name,guide,id,group);
        return ChannelIdentityPolicy.localStationIdentity(translated,guide);
    }

    private static String metadata(String name,String guide,String id){
        return safe(name)+" "+safe(guide)+" "+safe(id);
    }
    private static String safe(String value){return value==null?"":value;}
    private static boolean sameLabel(String first,String second){
        String a=safe(first).trim(),b=safe(second).trim();
        return a.length()>=4&&a.equalsIgnoreCase(b)
                &&!a.matches("(?i)^(?:频道|新闻综合|综合频道|新闻频道|未命名频道|local news|news|channel|unknown)$");
    }

    public static List<String> appendCandidates(List<String> existing,
            Collection<String> verifiedBestFirst,int maximum){
        ArrayList<String> result=new ArrayList<>();
        if(existing==null||verifiedBestFirst==null||maximum<=existing.size())return result;
        HashSet<String> seen=new HashSet<>();
        for(String url:existing)seen.add(SourceSelectionPolicy.endpointKey(url));
        for(String url:verifiedBestFirst){
            if(existing.size()+result.size()>=maximum)break;
            if(url==null||!(url.startsWith("http://")||url.startsWith("https://")))continue;
            try{
                java.net.URI uri=java.net.URI.create(url);
                if(uri.getHost()==null||uri.getHost().isEmpty())continue;
            }catch(IllegalArgumentException malformed){continue;}
            if(seen.add(SourceSelectionPolicy.endpointKey(url)))result.add(url);
        }
        return result;
    }
    /** Keep exact source positions for an explicit refresh. New verified
     * candidates fill free slots first; at capacity, the shared maintenance
     * rule may retire only a repeatedly failed, lower-scoring backup.
     * Protect both persisted selection and actual visible playback endpoint. */
    public static List<String> planRefresh(List<String> existing, Collection<String> verifiedBestFirst,
            int maximum,String primary,String selected,String playing,
            java.util.Set<String> repeatedlyFailed,java.util.Comparator<String> bestFirst){
        ArrayList<String> result=new ArrayList<>(existing);
        if(verifiedBestFirst==null||maximum<=0||result.size()>maximum)return result;
        HashSet<String> eligible=new HashSet<>();
        if(repeatedlyFailed!=null)eligible.addAll(repeatedlyFailed);
        eligible.remove(SourceSelectionPolicy.endpointKey(playing));
        HashSet<String> seen=new HashSet<>();
        for(String url:existing)seen.add(SourceSelectionPolicy.endpointKey(url));
        for(String candidate:appendCandidates(existing,verifiedBestFirst,Integer.MAX_VALUE)){
            String key=SourceSelectionPolicy.endpointKey(candidate);
            if(!seen.add(key))continue;
            if(result.size()<maximum){result.add(candidate);continue;}
            if(bestFirst==null)continue;
            int slot=SourceMaintenanceRetentionPolicy.retirementIndex(result,bestFirst,primary,selected,eligible);
            if(slot<0||bestFirst.compare(candidate,result.get(slot))>=0)continue;
            eligible.remove(SourceSelectionPolicy.endpointKey(result.get(slot)));
            result.set(slot,candidate);
        }
        return result;
    }

}
