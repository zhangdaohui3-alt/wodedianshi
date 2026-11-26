package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/** Conservative append-only plan for an explicit single-station refresh. */
public final class SingleChannelRefreshPolicy {
    private SingleChannelRefreshPolicy(){}
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
}
