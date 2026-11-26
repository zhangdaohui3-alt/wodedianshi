package com.wodedianshi.tv;

import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded read-only candidate discovery. Matching metadata is not a media
 * preflight, proof of station content, or proof of streaming authorization. */
final class SingleChannelCandidateSearch {
    private SingleChannelCandidateSearch(){}
    static final int MAX_CANDIDATES=32, MAX_PER_FEED=8, MAX_ROWS_PER_FEED=4096,
            MAX_ROWS=24000;
    static final long SEARCH_BUDGET_MS=8000L;
    static final class Target {
        final String name,group,tvgId,tvgName;
        Target(String name,String group,String tvgId,String tvgName){
            this.name=name;this.group=group;this.tvgId=tvgId;this.tvgName=tvgName;
        }
    }
    static final class Candidate {
        final String url,scope;
        Candidate(String url,String scope){this.url=url;this.scope=scope;}
    }
    static final class SearchResult {
        final ArrayList<Candidate> candidates=new ArrayList<>();
        int rowsScanned,feedsExamined,missingFeeds;
        boolean bounded;
    }
    private static final class CandidateLimit extends RuntimeException {}

    /** Pure streaming scan shared by cache search and deterministic tests.
     * The caller owns the reader and verifies the feed body version. */
    static int scan(Reader reader,Target target,String feedScope,Collection<String> excluded,
            int rowLimit,int candidateLimit,List<Candidate> result,BooleanSupplier paused)
            throws IOException {
        if(rowLimit<=0||candidateLimit<=0)return 0;
        HashSet<String> seen=new HashSet<>();
        for(String url:excluded)seen.add(SourceSelectionPolicy.endpointKey(url));
        int[] scanned={0};
        try{
            PlaylistParser.parseEach(reader,0,rowLimit,entry->{
                scanned[0]++;
                String endpoint=SourceSelectionPolicy.endpointKey(entry.url);
                if(endpoint.isEmpty()||seen.contains(endpoint))return;
                if(!SingleChannelRefreshPolicy.matchesStation(target.name,target.group,target.tvgId,target.tvgName,
                        entry.name,entry.group,entry.tvgId,entry.tvgName))return;
                if(SingleChannelRefreshPolicy.appendCandidates(
                        java.util.Collections.emptyList(),java.util.Collections.singletonList(entry.url),1).isEmpty())return;
                seen.add(endpoint);
                String scope=RegionCategoryPolicy.catalogueScope(entry.name,entry.group,entry.tvgName,
                        entry.tvgId,feedScope);
                result.add(new Candidate(entry.url,scope));
                if(result.size()>=candidateLimit)throw new CandidateLimit();
            },paused);
        }catch(CandidateLimit complete){/* enough matches for this feed */}
        SourceProbeTimePolicy.checkPaused(paused);
        return scanned[0];
    }

    /** Search the existing configured directories in their established priority
     * order. Keep partial results only from a version-checked completed scan.
     * This path performs no network requests or cache writes. */
    static SearchResult searchCached(Context context,Target target,
            Collection<String> existing,BooleanSupplier paused)throws IOException {
        SearchResult result=new SearchResult();
        long started=SystemClock.elapsedRealtime();
        BooleanSupplier bounded=()->(paused!=null&&paused.getAsBoolean())
                ||SystemClock.elapsedRealtime()-started>=SEARCH_BUDGET_MS;
        ArrayList<String> excluded=new ArrayList<>(existing);
        for(String feed:SourceDiscoveryFeedCatalog.feeds()){
            SourceProbeTimePolicy.checkPaused(paused);
            if(bounded.getAsBoolean()||result.rowsScanned>=MAX_ROWS
                    ||result.candidates.size()>=MAX_CANDIDATES){result.bounded=true;break;}
            File body=PlaylistFeedCache.findCachedForSingleChannel(context,feed);
            if(body==null){result.missingFeeds++;continue;}
            int rowLimit=Math.min(MAX_ROWS_PER_FEED,MAX_ROWS-result.rowsScanned);
            int candidateLimit=Math.min(MAX_PER_FEED,MAX_CANDIDATES-result.candidates.size());
            ArrayList<Candidate> local=new ArrayList<>();
            try{
                String version=PlaylistFeedCache.contentVersion(body);
                int scanned;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                        new FileInputStream(body),StandardCharsets.UTF_8),8192)){
                    String header=reader.readLine();
                    if(header==null||!header.replace("\uFEFF","").trim().startsWith("#EXTM3U"))
                        throw new IOException("Invalid cached playlist");
                    scanned=scan(reader,target,SourceFeedScopePolicy.scopeForFeed(feed),
                            excluded,rowLimit,candidateLimit,local,bounded);
                }
                result.rowsScanned+=scanned;
                if(scanned>=rowLimit||local.size()>=candidateLimit)result.bounded=true;
                if(!version.equals(PlaylistFeedCache.contentVersion(body))){
                    result.missingFeeds++;continue;
                }
                result.feedsExamined++;
                for(Candidate candidate:local){
                    result.candidates.add(candidate);excluded.add(candidate.url);
                }
            }catch(SourceProbeTimePolicy.PausedException stop){
                SourceProbeTimePolicy.checkPaused(paused);
                result.bounded=true;break;
            }catch(IOException unavailable){
                result.missingFeeds++;
            }
        }
        SourceProbeTimePolicy.checkPaused(paused);
        return result;
    }
}
