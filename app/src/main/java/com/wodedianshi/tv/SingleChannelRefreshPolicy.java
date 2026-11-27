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
        return new StationMatcher(name,group,tvgId,tvgName)
                .matches(candidateName,candidateGroup,candidateId,candidateGuide);
    }

    /** Prepare the immutable target once for a bounded search, not once per
     * candidate row. Preserve the existing conservative matching precedence. */
    static final class StationMatcher {
        private final String name,guide,region,broadcaster,province,city,identity,id;
        StationMatcher(String name,String group,String tvgId,String tvgName){
            this.name=name;this.guide=tvgName;
            String meta=metadata(name,tvgName,tvgId);
            region=RegionCategoryPolicy.subcategory(meta,group);
            broadcaster=ChannelNamePolicy.consistentBroadcaster(name,tvgName,tvgId);
            String localMeta=meta+" "+safe(group);
            province=broadcaster!=null&&broadcaster.isEmpty()
                    ?ChannelNamePolicy.localProvinceEvidence(localMeta):null;
            city=province==null?null:ChannelNamePolicy.localCityEvidence(localMeta,province);
            identity=city==null?"":localIdentity(name,tvgName,tvgId,group);
            id=ChannelIdentityPolicy.normalizeTvgId(tvgId);
        }
        boolean matches(String candidateName,String candidateGroup,String candidateId,String candidateGuide){
            String candidateMeta=metadata(candidateName,candidateGuide,candidateId);
            String otherRegion=RegionCategoryPolicy.subcategory(candidateMeta,candidateGroup);
            if(!region.equals(otherRegion)||broadcaster==null)return false;
            String second=ChannelNamePolicy.consistentBroadcaster(candidateName,candidateGuide,candidateId);
            if(second==null)return false;
            if(!broadcaster.isEmpty()||!second.isEmpty())
                return region.isEmpty()&&!broadcaster.isEmpty()&&broadcaster.equals(second);
            if(province==null||city==null)return false;
            String secondMeta=candidateMeta+" "+safe(candidateGroup);
            String otherProvince=ChannelNamePolicy.localProvinceEvidence(secondMeta);
            if(otherProvince==null)return false;
            if(!province.isEmpty()&&!otherProvince.isEmpty()&&!province.equals(otherProvince))return false;
            String otherCity=ChannelNamePolicy.localCityEvidence(secondMeta,otherProvince);
            if(otherCity==null)return false;
            if(!city.isEmpty()&&!otherCity.isEmpty()&&!city.equals(otherCity))return false;
            String b=localIdentity(candidateName,candidateGuide,candidateId,candidateGroup);
            if(!identity.isEmpty()&&!b.isEmpty())return identity.equals(b);
            String otherId=ChannelIdentityPolicy.normalizeTvgId(candidateId);
            return !id.isEmpty()&&id.equals(otherId)
                    &&(sameLabel(name,candidateName)||sameLabel(name,candidateGuide)
                        ||sameLabel(guide,candidateName)||sameLabel(guide,candidateGuide));
        }
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

    /** Shared exact URL validation for candidate scans and retention.
     * A syntactically valid URL is not evidence of media playability. */
    static boolean validCandidateUrl(String url){
        if(url==null||!(url.startsWith("http://")||url.startsWith("https://")))return false;
        try{
            java.net.URI uri=java.net.URI.create(url);
            return uri.getHost()!=null&&!uri.getHost().isEmpty();
        }catch(IllegalArgumentException malformed){return false;}
    }

    public static List<String> appendCandidates(List<String> existing,
            Collection<String> verifiedBestFirst,int maximum){
        ArrayList<String> result=new ArrayList<>();
        if(existing==null||verifiedBestFirst==null||maximum<=existing.size())return result;
        HashSet<String> seen=new HashSet<>();
        for(String url:existing)seen.add(SourceSelectionPolicy.endpointKey(url));
        for(String url:verifiedBestFirst){
            if(existing.size()+result.size()>=maximum)break;
            if(!validCandidateUrl(url))continue;
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
        // The original-route set already excludes old and duplicate endpoints.
        // Validate and consume each candidate once, without an intermediate list.
        for(String candidate:verifiedBestFirst){
            if(!validCandidateUrl(candidate))continue;
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
