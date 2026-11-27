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

/** Bounded candidate discovery without modifying shared directory caches. Matching metadata is not a media
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
        String cursorKey;
        org.json.JSONObject nextCursors;
        org.json.JSONObject priorCursors;
        int nextRetryFeed;
        int cachedCandidates;
    }
    private static final class CandidateLimit extends RuntimeException {}

    /** Only one existing configured directory, selected by known station family.
     * Unknown/ambiguous regions do not trigger an unrelated online search. */
    static int preferredOnlineFeedIndex(Target target){
        String region=RegionCategoryPolicy.subcategory(target.name+" "+target.tvgName+" "+target.tvgId,target.group);
        if("香港".equals(region))return 10;
        if("澳门".equals(region))return 11;
        if("台湾".equals(region))return 12;
        if(!region.isEmpty())return -1;
        int category=FullDiscoveryProgressPolicy.coreCategoryMask(target.name,target.group,target.tvgName,target.tvgId);
        if(category==FullDiscoveryProgressPolicy.CORE_CCTV)return 0;
        if(category==FullDiscoveryProgressPolicy.CORE_SATELLITE)return 2;
        if(category==FullDiscoveryProgressPolicy.CORE_LOCAL)return 5;
        return -1;
    }

    static boolean onlineResponseAllowed(int status,String range,String encoding,long declared){
        return status==200&&(range==null||range.isEmpty())
            &&(encoding==null||encoding.isEmpty()||"identity".equalsIgnoreCase(encoding))
            &&declared>=-1L&&declared!=0L&&SourceProbeTimePolicy.fitsLiveDirectory(declared);
    }
    static boolean completeOnlineBody(long declared,long copied){
        return copied>0L&&SourceProbeTimePolicy.fitsLiveDirectory(copied)
            &&(declared==-1L||declared==copied);
    }

    /** On a cache miss, read one small complete directory into private memory.
     * No shared cache, feed cursor, snapshot or index is replaced. The existing
     * station matcher and media preflight still decide whether a route is saved. */
    static void tryOnlineFallback(SearchResult result,Target target,Collection<String> existing,
            boolean viewing,BooleanSupplier paused)throws IOException{
        SourceProbeTimePolicy.checkPaused(paused);
        if(!result.candidates.isEmpty())return;
        int index=preferredOnlineFeedIndex(target);
        String[] feeds=SourceDiscoveryFeedCatalog.feeds();
        if(index<0||index>=feeds.length)return;
        result.bounded=true;
        String feed=feeds[index];
        long started=SystemClock.elapsedRealtime();
        BooleanSupplier bounded=()->(paused!=null&&paused.getAsBoolean())
            ||SystemClock.elapsedRealtime()-started>=SourceProbeTimePolicy.LIVE_DIRECTORY_BUDGET_MS;
        java.net.HttpURLConnection connection=null;
        try{
            SourceProbeTimePolicy.checkPaused(bounded);
            connection=(java.net.HttpURLConnection)new java.net.URL(feed).openConnection();
            connection.setConnectTimeout(2000);connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(true);connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent","MyTV/1.19 Android TV");
            connection.setRequestProperty("Accept-Encoding","identity");
            int status=connection.getResponseCode();
            SourceProbeTimePolicy.checkPaused(bounded);
            long declared=connection.getContentLength();
            if(!onlineResponseAllowed(status,connection.getHeaderField("Content-Range"),
                    connection.getHeaderField("Content-Encoding"),declared))return;
            java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
            long copied=0L;
            long readStarted=SystemClock.elapsedRealtime();
            try(java.io.InputStream input=connection.getInputStream()){
                byte[] buffer=new byte[4096];
                while(true){
                    SourceProbeTimePolicy.checkPaused(bounded);
                    int count=input.read(buffer,0,(int)Math.min(buffer.length,
                        SourceProbeTimePolicy.LIVE_DIRECTORY_MAX_BYTES+1L-copied));
                    SourceProbeTimePolicy.checkPaused(bounded);
                    if(count<0)break;
                    copied+=count;
                    if(!SourceProbeTimePolicy.fitsLiveDirectory(copied)||(declared>=0&&copied>declared))return;
                    body.write(buffer,0,count);
                    if(viewing){
                        long delay=SourceProbeTimePolicy.liveDirectoryPacingMillis(copied,SystemClock.elapsedRealtime()-readStarted);
                        while(delay>0L){
                            SourceProbeTimePolicy.checkPaused(bounded);
                            long step=Math.min(50L,delay);SystemClock.sleep(step);delay-=step;
                        }
                    }
                }
            }
            if(!completeOnlineBody(declared,copied))return;
            ArrayList<Candidate> local=new ArrayList<>();
            try(BufferedReader reader=new BufferedReader(new java.io.StringReader(
                    new String(body.toByteArray(),StandardCharsets.UTF_8)))){
                String header=reader.readLine();
                if(header==null||!header.replace("\uFEFF","").trim().startsWith("#EXTM3U"))return;
                int scanned=scan(reader,target,SourceFeedScopePolicy.scopeForFeed(feed),existing,
                    MAX_ROWS_PER_FEED,MAX_PER_FEED,local,bounded);
                SourceProbeTimePolicy.checkPaused(bounded);
                result.rowsScanned+=scanned;result.feedsExamined++;
                result.candidates.addAll(local);
            }
        }catch(IOException unavailable){
            // The local online budget or network failure leaves the old cache
            // and routes untouched; lifecycle cancellation still propagates.
            SourceProbeTimePolicy.checkPaused(paused);
        }finally{if(connection!=null)connection.disconnect();}
    }

    /** Count only rows actually visited. A candidate limit must not skip the
     * rest of an indexed page or a streaming page. */
    private static final class ScanPage {
        final SingleChannelRefreshPolicy.StationMatcher matcher;
        final String feedScope;
        final HashSet<String> seen=new HashSet<>();
        final int candidateLimit;
        final List<Candidate> result;
        int scanned;
        ScanPage(Target target,String feedScope,Collection<String> excluded,int candidateLimit,List<Candidate> result){
            this.matcher=new SingleChannelRefreshPolicy.StationMatcher(target.name,target.group,target.tvgId,target.tvgName);
            this.feedScope=feedScope;this.candidateLimit=candidateLimit;this.result=result;
            for(String url:excluded)seen.add(SourceSelectionPolicy.endpointKey(url));
        }
        void accept(PlaylistParser.ChannelSource entry){
            scanned++;
            String endpoint=SourceSelectionPolicy.endpointKey(entry.url);
            if(endpoint.isEmpty()||seen.contains(endpoint))return;
            if(!matcher.matches(entry.name,entry.group,entry.tvgId,entry.tvgName))return;
            if(SingleChannelRefreshPolicy.appendCandidates(
                    java.util.Collections.emptyList(),java.util.Collections.singletonList(entry.url),1).isEmpty())return;
            seen.add(endpoint);
            String scope=RegionCategoryPolicy.catalogueScope(entry.name,entry.group,entry.tvgName,entry.tvgId,feedScope);
            result.add(new Candidate(entry.url,scope));
            if(result.size()>=candidateLimit)throw new CandidateLimit();
        }
    }

    static int scan(Reader reader,Target target,String feedScope,Collection<String> excluded,
            int rowLimit,int candidateLimit,List<Candidate> result,BooleanSupplier paused)throws IOException {
        return scanFrom(reader,0,target,feedScope,excluded,rowLimit,candidateLimit,result,paused);
    }
    static int scanFrom(Reader reader,int start,Target target,String feedScope,Collection<String> excluded,
            int rowLimit,int candidateLimit,List<Candidate> result,BooleanSupplier paused)throws IOException {
        if(rowLimit<=0||candidateLimit<=0)return 0;
        ScanPage page=new ScanPage(target,feedScope,excluded,candidateLimit,result);
        try{PlaylistParser.parseEach(reader,start,rowLimit,page::accept,paused);}
        catch(CandidateLimit complete){/* retain exact consumed prefix */}
        SourceProbeTimePolicy.checkPaused(paused);
        return page.scanned;
    }
    static int scanIndexed(File body,int start,Target target,String feedScope,Collection<String> excluded,
            int rowLimit,int candidateLimit,List<Candidate> result,BooleanSupplier paused)throws IOException{
        if(rowLimit<=0||candidateLimit<=0)return 0;
        ScanPage page=new ScanPage(target,feedScope,excluded,candidateLimit,result);
        try{
            while(page.scanned<rowLimit){
                int count=PlaylistFeedIndex.readExistingPage(body,start+page.scanned,
                    Math.min(64,rowLimit-page.scanned),page::accept,paused);
                if(count==0)break;
            }
        }catch(CandidateLimit complete){/* next request starts after the last consumed row */}
        SourceProbeTimePolicy.checkPaused(paused);
        return page.scanned;
    }

    // A positional cursor describes both the body and its physical row order.
    // Raw streaming and raw indexes share the same order.
    static String scanVersion(String bodyVersion,File body){
        return PlaylistFeedIndex.initialProbeVersion(bodyVersion,PlaylistFeedIndex.hasCoverageOrder(body));
    }

    static int resumeOffset(String savedVersion,String actualVersion,int savedOffset){
        return actualVersion!=null&&!actualVersion.isEmpty()&&actualVersion.equals(savedVersion)
                ?Math.max(0,savedOffset):0;
    }
    static int nextOffset(int start,int consumed,int limit,int indexedCount,boolean candidateLimitReached){
        if(indexedCount>=0&&(long)start+consumed>=indexedCount)return 0;
        if(!candidateLimitReached&&consumed<limit)return 0;
        return (int)Math.min(Integer.MAX_VALUE,(long)start+consumed);
    }
    static void persistCompletedSearch(Context context,SearchResult result,int checked,boolean conclusive,
            BooleanSupplier paused)throws IOException{
        // No untested candidate may be skipped. The worker calls this only
        // after any verified routes have been committed to Room.
        if(result.nextCursors==null)return;
        SourceProbeTimePolicy.checkPaused(paused);
        org.json.JSONObject state=result.nextCursors;
        if(!conclusive||checked<result.cachedCandidates){
            // Retain every previous row offset, but let another directory take
            // the next turn. An inconclusive route must not monopolize all feeds.
            state=result.priorCursors;
            try{state.put("nextFeed",result.nextRetryFeed);}
            catch(org.json.JSONException impossible){throw new IOException("Invalid retry cursor",impossible);}
        }
        com.wodedianshi.tv.db.AppStateStore.put(context,result.cursorKey,state.toString());
    }

    /** Search the existing configured directories in their established priority
     * order. Keep partial results only from a version-checked completed scan.
     * This path performs no network requests or cache writes. */
    static SearchResult searchCached(Context context,String channelId,Target target,
            Collection<String> existing,int probeLimit,BooleanSupplier paused)throws IOException,org.json.JSONException {
        SearchResult result=new SearchResult();
        result.cursorKey="single_channel_scan_v1_"+StableKey.channelIdentity(channelId);
        org.json.JSONObject cursors;
        try{cursors=new org.json.JSONObject(com.wodedianshi.tv.db.AppStateStore.get(context,result.cursorKey,"{}"));}
        catch(org.json.JSONException malformed){cursors=new org.json.JSONObject();}
        final String[] feeds=SourceDiscoveryFeedCatalog.feeds();
        final int startFeed=SourceSelectionPolicy.wrapIndex(cursors.optInt("nextFeed",0),feeds.length);
        result.priorCursors=new org.json.JSONObject(cursors.toString());
        result.nextRetryFeed=(startFeed+1)%feeds.length;
        final int candidateBudget=Math.max(1,Math.min(MAX_CANDIDATES,probeLimit));
        long started=SystemClock.elapsedRealtime();
        BooleanSupplier bounded=()->(paused!=null&&paused.getAsBoolean())
                ||SystemClock.elapsedRealtime()-started>=SEARCH_BUDGET_MS;
        ArrayList<String> excluded=new ArrayList<>(existing);
        for(int turn=0;turn<feeds.length;turn++){
            SourceProbeTimePolicy.checkPaused(paused);
            if(bounded.getAsBoolean()||result.rowsScanned>=MAX_ROWS
                    ||result.candidates.size()>=candidateBudget){result.bounded=true;break;}
            int feedIndex=(startFeed+turn)%feeds.length;
            String feed=feeds[feedIndex];
            String feedKey=StableKey.url(feed);
            File body=PlaylistFeedCache.findCachedForSingleChannel(context,feed);
            cursors.put("nextFeed",(feedIndex+1)%feeds.length);
            if(body==null){result.missingFeeds++;continue;}
            int rowLimit=Math.min(MAX_ROWS_PER_FEED,MAX_ROWS-result.rowsScanned);
            int candidateLimit=Math.min(MAX_PER_FEED,candidateBudget-result.candidates.size());
            ArrayList<Candidate> local=new ArrayList<>();
            try{
                String version=scanVersion(PlaylistFeedCache.contentVersion(body),body);
                org.json.JSONObject prior=cursors.optJSONObject(feedKey);
                int offset=prior==null?0:resumeOffset(prior.optString("version"),version,prior.optInt("offset"));
                int indexedCount=PlaylistFeedIndex.indexedEntryCount(body);
                if(indexedCount>=0&&offset>=indexedCount)offset=0;
                int scanned;
                if(indexedCount>=0){
                    scanned=scanIndexed(body,offset,target,SourceFeedScopePolicy.scopeForFeed(feed),
                            excluded,rowLimit,candidateLimit,local,bounded);
                }else{
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                            new FileInputStream(body),StandardCharsets.UTF_8),8192)){
                        String header=reader.readLine();
                        if(header==null||!header.replace("\uFEFF","").trim().startsWith("#EXTM3U"))
                            throw new IOException("Invalid cached playlist");
                        scanned=scanFrom(reader,offset,target,SourceFeedScopePolicy.scopeForFeed(feed),
                                excluded,rowLimit,candidateLimit,local,bounded);
                    }
                }
                result.rowsScanned+=scanned;
                if(scanned>=rowLimit||local.size()>=candidateLimit)result.bounded=true;
                if(!version.equals(scanVersion(PlaylistFeedCache.contentVersion(body),body))){
                    result.missingFeeds++;continue;
                }
                cursors.put(feedKey,new org.json.JSONObject().put("version",version)
                    .put("offset",nextOffset(offset,scanned,rowLimit,indexedCount,local.size()>=candidateLimit)));
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
        result.cachedCandidates=result.candidates.size();
        result.nextCursors=cursors;
        return result;
    }
}
