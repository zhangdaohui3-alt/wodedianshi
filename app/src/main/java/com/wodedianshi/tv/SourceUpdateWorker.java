package com.wodedianshi.tv;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class SourceUpdateWorker extends Worker {
  // WorkManager unique names do not exclude one another. Keep every source
  // task in this process under one nonblocking ownership gate.
  private static final java.util.concurrent.atomic.AtomicBoolean SOURCE_WORK_ACTIVE=
      new java.util.concurrent.atomic.AtomicBoolean();
  // Transient, same-process status only; durable progress stays in Room.
  private static volatile String initialLiveStage="";
  public static String currentInitialLiveStage(){return initialLiveStage;}
  private void noteInitialStage(String stage){
    if(getInputData().getBoolean("initial_discovery_chain",false))initialLiveStage=stage;
  }
  // Single source-work owner accesses this bounded process-local cache.
  // Do not reuse old persisted quality scores as evidence of a new preflight.
  private static final LinkedHashMap<String,RecentProbe> INITIAL_PROBES=
      new LinkedHashMap<>(64,0.75f,true);
  private static final int INITIAL_PROBE_CACHE_CAP=1024;
  private static final class RecentProbe {
    final ProbeResult result; final long at;
    RecentProbe(ProbeResult result,long at){this.result=result;this.at=at;}
  }
  private static final HashMap<Integer,Long> LIVE_DIRECTORY_RETRY_AFTER=new HashMap<>();
  // Reuse only committed staging owned by the sole source worker. A borrowed
  // mutable snapshot is removed from the cache BEFORE any mutation.
  private static JSONArray committedStaging;
  private static String committedStagingRevision="";
  private static long committedStagingAt;
  private boolean slowPlayback;
  private java.util.function.BooleanSupplier pauseRequested = () -> false;
  private static final int MAX_PROBES_PER_RUN = 24;
  private static final long PROBE_STAGE_DEADLINE_MS = 90_000L;
  private static final int MAX_SOURCES_PER_CHANNEL = 8;
  // The full rebuild reads one feed page per worker run and spends its 24-probe
  // budget on that page. This prevents large China playlists from being starved
  // by small pages interleaved from every worldwide feed.
  // Per-worker memory safety bound only; it is not a lifetime catalogue cap.
  // Reaching it must leave the feed cursor untouched rather than skip entries.
  private static final int MAX_DISCOVERY_CANDIDATES_PER_RUN = 24000;
  private static final int MAX_DISCOVERY_PER_FEED = 4;
  private static final int MAX_INITIAL_DISCOVERY_PER_FEED = MAX_PROBES_PER_RUN;
  public SourceUpdateWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
  @NonNull @Override public Result doWork(){
    if(!SOURCE_WORK_ACTIVE.compareAndSet(false,true)){
      return SourceUpdateSchedulePolicy.shouldRetryWhenBusy(
          getInputData().getBoolean("manual",false),
          getInputData().getBoolean("initial_discovery_chain",false))
          ?Result.retry():Result.success();
    }
    try{
      long now=android.os.SystemClock.elapsedRealtime();
      if(!getInputData().getBoolean("initial_discovery_chain",false)
          ||now<committedStagingAt||now-committedStagingAt>60000L)discardStagingReuse();
      noteInitialStage("准备本轮目录整理");
      return runOwnedWork();
    }finally{
      if(isStopped()||Thread.currentThread().isInterrupted())discardStagingReuse();
      initialLiveStage="";SOURCE_WORK_ACTIVE.set(false);
    }
  }
  private Result runOwnedWork(){
    SharedPreferences prefs=getApplicationContext().getSharedPreferences("mytv",0);
    final boolean manual=getInputData().getBoolean("manual",false);
    final boolean initialDiscoveryChain=getInputData().getBoolean("initial_discovery_chain",false);
    final boolean manualRefresh=manual&&!initialDiscoveryChain;
    final String manualId=getInputData().getString("manual_refresh_id")==null
        ?getId().toString():getInputData().getString("manual_refresh_id");
    if(!manual&&!prefs.getBoolean("auto_source_update",true))return Result.success();
    // No full catalogue yet: the one-time chain owns discovery; a periodic
    // worker must neither compete with it nor publish a partial replacement.
    if(!initialDiscoveryChain){
      long completedAt=0L;
      try{completedAt=Long.parseLong(roomString("source_full_rebuild_completed_at","0"));}
      catch(NumberFormatException ignored){}
      if(!SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true,completedAt))
        return manual?Result.retry():Result.success();
      long lastRoutineAt=0L;
      try{lastRoutineAt=Long.parseLong(roomString("source_last_routine_maintenance_at","0"));}
      catch(NumberFormatException ignored){}
      if(!manual&&!SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(completedAt,lastRoutineAt,System.currentTimeMillis()))
        return Result.success();
    }
    final long playbackStart=SourceUpdateActivityPolicy.playbackStartGeneration();
    final long activityStart=SourceUpdateActivityPolicy.activityStartGeneration();
    final long stableGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
    slowPlayback=initialDiscoveryChain&&SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime());
    final boolean foregroundAtStart=isActivityActive(prefs);
    // Automatic discovery parses the saved catalogue and builds sizeable indexes.
    // Defer that work while the TV app is active so it cannot compete with playback,
    // channel rendering, or remote-control input. Explicit manual updates remain available.
    // First-run discovery must proceed while browsing, even with seed channels.
    // Actual playback still pauses discovery; routine automatic updates defer.
    boolean hasPublishedChannels=true;
    if(initialDiscoveryChain){
      hasPublishedChannels=com.wodedianshi.tv.db.RoomDatabaseProvider
          .get(getApplicationContext()).channelDao().countChannels()>0;
    }
    final boolean publishedAtStart=hasPublishedChannels;
    final boolean deferForeground=SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
        true,manual,initialDiscoveryChain,publishedAtStart);
    pauseRequested=SourceProbeTimePolicy.latchPause(()->isStopped()
        ||SourceUpdateActivityPolicy.playbackStartGeneration()!=playbackStart
        ||(slowPlayback&&SourceUpdateActivityPolicy.stableRevocationGeneration()!=stableGeneration)
        ||(deferForeground&&SourceUpdateActivityPolicy.activityStartGeneration()!=activityStart)
        ||shouldYieldToViewing(prefs,manual,initialDiscoveryChain,publishedAtStart));
    if(pauseRequested.getAsBoolean()){
      saveStatus(prefs,SourceUpdateSchedulePolicy.shouldRetryWhenBusy(manual,initialDiscoveryChain)
          ?"节目或频道界面正在使用，首次/手动更新已推迟"
          :"正在观看或浏览频道，维护仍待执行；下次空闲检查再继续",0);
      return deferredViewingResult(manual,initialDiscoveryChain);
    }
    int added=0,feedsOk=0,feedsWithEntries=0,discoveryProbesValid=0,chinaPreflightsValid=0,feedsFailed=0,checked=0;
    StringBuilder errors=new StringBuilder();
    // Centralized, testable feed order: China local-first, then global.
    // A feed-list fingerprint change intentionally restarts first-build cursors.
    String[] feeds=SourceDiscoveryFeedCatalog.feeds();
    try{
      if(manualRefresh){
        if(manualId.equals(roomString("source_manual_completed_id","")))return Result.success();
        String config=discoveryFeedsFingerprint(feeds);
        if(!manualId.equals(roomString("source_manual_active_id",""))
            ||!config.equals(roomString("source_manual_feed_config",""))){
          com.wodedianshi.tv.db.AppStateEditor reset=
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
              .putString("source_manual_active_id",manualId)
              .putString("source_manual_feed_config",config)
              .putInt("source_manual_next_feed",0)
              .putInt("source_manual_failed_feeds",0)
              .putInt("source_manual_feed_attempts",0)
              .remove("source_manual_feed_batch_fingerprint")
              .remove("source_manual_feed_batch_scanned");
          for(int i=0;i<feeds.length;i++)
            reset.remove(manualFeedCursorKey(i)).remove(manualFeedCursorKey(i)+"_version");
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          reset.apply();
        }
      }
      // First discovery works solely on isolated staging. Do not deserialize
      // the published catalogue every slice; read it afresh at publication.
      // Routine maintenance still needs its original optimistic snapshot.
      com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot roomSnapshot =
          initialDiscoveryChain?null:
              com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),null,pauseRequested);
      if(initialDiscoveryChain
          ?!com.wodedianshi.tv.db.RoomCatalogueReader.hasValidatedCatalogue(getApplicationContext())
          :roomSnapshot==null) {
        saveStatus(prefs,"Room频道目录尚未完成初始化，后台更新稍后重试",0);
        return Result.retry();
      }
      JSONArray saved;
      if(initialDiscoveryChain){
        String buildActive=com.wodedianshi.tv.db.AppStateStore.get(
            getApplicationContext(),"source_full_rebuild_active","false");
        long completedRebuildAt=0L;
        try{
          completedRebuildAt=Long.parseLong(com.wodedianshi.tv.db.AppStateStore.get(
              getApplicationContext(),"source_full_rebuild_completed_at","0"));
        }catch(NumberFormatException ignored){}
        // The Room publish transaction records completion and clears the active
        // flag atomically. If the process died after commit but before WorkManager
        // recorded success, its retry must not start a second full rebuild.
        if(!"true".equals(buildActive)&&completedRebuildAt>0L){
          saveStatus(prefs,"全量频道目录已完成发布，无需重复重建",0);
          return Result.success();
        }
        String configuredFeedFingerprint=discoveryFeedsFingerprint(feeds);
        String savedFeedFingerprint=roomString("source_full_rebuild_feed_fingerprint","");
        boolean rebuildProgressMustReset=!"true".equals(buildActive)
            ||!configuredFeedFingerprint.equals(savedFeedFingerprint);
        if(rebuildProgressMustReset){
          if(slowPlayback&&!FullDiscoveryProgressPolicy.canResetInitialStaging(true,
              com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext())
                  .channelDao().countCatalogueBuildRows()))
            return deferInitialDirectory("已有暂存目录，等待空闲重建，保留观看");
          // Empty staging initialization is only a small state transaction.
          // It must not block first discovery forever on a playing seed channel.
          INITIAL_PROBES.clear();
          LIVE_DIRECTORY_RETRY_AFTER.clear();
          discardStagingReuse();
          // Start from an empty isolated Room staging set. The live catalogue is
          // intentionally not read as input and remains untouched until publish.
          // Feed progress is tied to the exact ordered URL list: an app update that
          // adds, removes, or reorders feeds must not inherit stale per-index cursors.
          com.wodedianshi.tv.db.AppStateEditor resetBuildState=
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext());
          for(int feed=0;feed<feeds.length;feed++){
            resetBuildState.remove(initialFeedDoneKey(feed)).remove(feedCursorKey(feed))
                .remove(feedCursorKey(feed)+"_version")
                .remove(initialFeedProgressKey(feed))
                .remove(initialAcquiredVersionKey(feed))
                .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"fingerprint").remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"scanned")
                .remove(initialFeedUnavailableKey(feed))
                .remove(initialFeedFailureCountKey(feed))
                .remove(initialFeedFailureReasonKey(feed))
                .remove(initialFeedDisplayLabelKey(feed));
          }
          resetBuildState.remove("source_feed_batch_fingerprint")
              .remove("source_feed_batch_scanned")
              .remove("source_initial_feed_batch_fingerprint")
              .remove("source_initial_feed_batch_scanned")
              .remove("source_scan_after_key").remove("source_scan_cursor")
              .remove("source_saved_scan_after_key").remove("source_saved_scan_cursor")
              .remove("source_initial_has_validated_source")
              .remove(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY)
              .remove("source_initial_discovery_fingerprint")
              .remove("source_initial_discovery_scanned")
              .putInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0)
              .putInt("source_initial_index_attempted",0)
              .putBoolean("source_initial_index_bypassed",slowPlayback)
              .putInt("source_initial_index_ready",0)
              .putLong("source_initial_index_entries",0L)
              .putInt("source_initial_progress_percent",0)
              .putInt("source_initial_progress_done_feeds",0)
              .putInt("source_initial_progress_total_feeds",feeds.length)
              .putInt("source_initial_progress_china_done_feeds",0)
              .putInt("source_initial_progress_china_total_feeds",
                  SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT)
              .putInt("source_initial_progress_staged_channels",0)
              .putInt("source_initial_progress_timeout_urls",0)
              .putInt("source_initial_progress_checked_urls",0)
              .putInt("source_initial_progress_validated_urls",0)
              .putInt("source_initial_progress_china_validated_urls",0)
              .putInt("source_initial_progress_unavailable_feeds",0)
              .putInt("source_initial_progress_feed_index",0)
              .putInt("source_initial_progress_feed_cursor",0)
              .putInt("source_initial_progress_feed_entries",0)
              .putString("source_initial_progress_phase","正在准备首次完整抓源")
              .putInt("source_initial_next_feed_index",0)
              .putString("source_full_rebuild_feed_fingerprint",configuredFeedFingerprint)
              .putBoolean("source_full_rebuild_active",true);
          com.wodedianshi.tv.db.RoomCatalogueBuildStore.clear(
              getApplicationContext(),resetBuildState,pauseRequested);
        }
        // Acquisition phase: build disk indexes independently of media probes.
        // Keep the existing cache/index as the candidate store; Room records
        // only the acquisition cursor and counts, not duplicate unverified URLs.
        int nextIndex=Math.max(0,roomInt("source_initial_index_attempted",0));
        int mainlandCount=Math.min(feeds.length,SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
        int mainlandLimit=FullDiscoveryProgressPolicy.bootstrapIndexLimit(mainlandCount);
        long indexedEntriesSoFar=0L;
        try{indexedEntriesSoFar=Math.max(0L,Long.parseLong(roomString("source_initial_index_entries","0")));}
        catch(NumberFormatException ignored){}
        boolean bootstrapBypassed=roomBoolean("source_initial_index_bypassed",false);
        if(!bootstrapBypassed&&FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(slowPlayback,nextIndex,mainlandCount,
            roomInt("source_initial_progress_checked_urls",0),indexedEntriesSoFar,false)){
          int count=-1;
          String preparedVersion="";
          noteInitialStage("首批中国目录准备 "+(nextIndex+1)+"/"+mainlandLimit+"（不检测线路）");
          try{
            File cached=PlaylistFeedCache.obtain(getApplicationContext(),feeds[nextIndex],true,
                roomInt(feedCursorKey(nextIndex),0)>0,pauseRequested);
            // readPage builds the complete random-access index if necessary.
            // Decode one record to check the sidecar without allocating all entries.
            PlaylistFeedIndex.readPage(cached,0,1,source->{},pauseRequested);
            count=PlaylistFeedIndex.indexedEntryCount(cached);
            if(count>=0)preparedVersion=PlaylistFeedCache.contentVersion(cached);
          }catch(Exception failure){
            if(failure instanceof SourceProbeTimePolicy.PausedException||pauseRequested.getAsBoolean())
              return deferredViewingResult(manual,initialDiscoveryChain);
            android.util.Log.w("MyTVSourceDiscovery","initial_index_deferred feed="+nextIndex);
          }
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          int ready=Math.max(0,roomInt("source_initial_index_ready",0))+(count>=0?1:0);
          long entries=indexedEntriesSoFar;
          com.wodedianshi.tv.db.AppStateEditor acquisition=
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
              .putInt("source_initial_index_attempted",nextIndex+1)
              .putInt("source_initial_index_ready",ready)
              .putLong("source_initial_index_entries",Math.min(Long.MAX_VALUE-Math.max(0,count),entries)+Math.max(0,count))
              .putString("source_initial_progress_phase",count>0||nextIndex+1>=mainlandLimit
                  ?"首批目录准备已结束，开始线路初筛"
                  :"首批目录准备："+(nextIndex+1)+"/"+mainlandLimit);
          // Start with the nonempty indexed feed, rather than immediately
          // retrying an earlier failed download. Other feeds remain unfinished.
          if(count>0)acquisition.putInt("source_initial_next_feed_index",nextIndex);
          if(!preparedVersion.isEmpty())acquisition.putString(initialAcquiredVersionKey(nextIndex),preparedVersion);
          acquisition.apply();
          // Persist acquisition before queueing the next slice. An unavailable
          // directory is retried by the ordinary bounded probe phase.
          enqueueInitialContinuation();
          return Result.success();
        }
        if(slowPlayback&&!bootstrapBypassed&&FullDiscoveryProgressPolicy.isMainlandIndexPhase(nextIndex,mainlandCount,
            roomInt("source_initial_progress_checked_urls",0),indexedEntriesSoFar,false)){
          // Mark only the optional bootstrap as bypassed, not any feed done.
          // The regular bounded feed path still reads and checks every feed.
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
              .putBoolean("source_initial_index_bypassed",true)
              .putString("source_initial_progress_phase","稳定直播：开始低速准备中国目录")
              .apply();
        }
        // Last viewing slice may already have committed every feed. Avoid
        // reloading/ranking staging on each retry while only publication waits.
        if(slowPlayback&&areAllDiscoveryFeedsComplete(prefs,Collections.emptyList(),feeds.length))
          return deferInitialDirectory("线路初筛已完成，等待空闲发布完整快照");
        saved=takeCommittedStaging();
        com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),
            "source_initial_progress_phase","正在继续首次完整抓源");
      }else{
        saved=roomSnapshotToArray(roomSnapshot);
      }
      final int previousStagedSize=saved.length();
      IdentityHashMap<JSONObject,Integer> stagedPositions=new IdentityHashMap<>();
      HashSet<Integer> touchedStagedRows=new HashSet<>();
      LinkedHashMap<String,JSONObject> byIdentity=new LinkedHashMap<>();
      JSONObject measuredScores=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_probe_scores_v1","{}"));
      // A single failed probe merely reduces its score. It cannot delete an
      // existing saved backup; three independent failures are needed.
      JSONObject probeFailureStreaks=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(
          getApplicationContext(),"source_probe_failure_streaks_v1","{}"));
      JSONObject observedQoe=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_qoe_v1","{}"));
      for(int i=0;i<saved.length();i++){
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        JSONObject c=saved.getJSONObject(i);
        if(initialDiscoveryChain)stagedPositions.put(c,i);
        byIdentity.put(identity(c.optString("name"),c.optString("group"),
            c.optString("url"),c.optString("tvgId"),c.optString("tvgName")),c);
      }
      ArrayList<Entry> candidates=new ArrayList<>();
      // Include the user's already-saved backup URLs in the rotating health-check
      // pool. Previously only URLs rediscovered in public playlists were probed,
      // so a saved backup could silently die without ever losing its old score.
      ArrayList<Entry> savedPool=new ArrayList<>();
      HashSet<String> savedPoolUrls=new HashSet<>();
      // A foreground full rebuild probes the staged discovery candidates; it does not
      // import old catalogue sources. Routine background maintenance also checks saved URLs.
      // Avoid allocating up to 20,000 additional saved-source Entry objects while the
      // TV UI is open; the full rotating health-check pool is retained for background runs.
      if(!foregroundAtStart&&!initialDiscoveryChain){
        for(int i=0;i<saved.length()&&savedPool.size()<20000;i++){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          JSONObject channel=saved.optJSONObject(i);if(channel==null)continue;
          String channelName=channel.optString("name",""),channelGroup=channel.optString("group","");
          LinkedHashSet<String> channelUrls=new LinkedHashSet<>();
          JSONArray sources=channel.optJSONArray("sources");
          if(sources!=null)for(int j=0;j<sources.length();j++){
            String u=sources.optString(j,"");
            if(u.startsWith("http://")||u.startsWith("https://"))channelUrls.add(u);
          }
          String primary=channel.optString("url","");
          if(primary.startsWith("http://")||primary.startsWith("https://"))channelUrls.add(primary);
          for(String u:channelUrls){
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
            if(savedPool.size()>=20000)break;
            // A shared URL only needs one network probe, even if several channel
            // entries reference it. Keep the first channel identity for diagnostics.
            if(savedPoolUrls.add(SourceSelectionPolicy.endpointKey(u)))savedPool.add(new Entry(
                channelName,u,channelGroup,true,"",channel.optString("tvgId",""),channel.optString("tvgName","")));
          }
        }
      }
      com.wodedianshi.tv.db.AppStateEditor update=
          new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext());
      ArrayList<FeedBatch> feedBatches=new ArrayList<>();
      int initialFeedStartIndex=InitialFeedRotationPolicy.normalizeStart(
          roomInt("source_initial_next_feed_index",0),feeds.length);
      int initialNextFeedIndex=initialFeedStartIndex;
      boolean[] inspectedFeeds=new boolean[feeds.length];
      boolean[] attemptedFeedsThisRun=new boolean[feeds.length];
      if(initialDiscoveryChain){
        for(int i=0;i<feeds.length;i++)
          inspectedFeeds[i]=roomBoolean(initialFeedDoneKey(i),false);
      }
      int routineStart=InitialFeedRotationPolicy.normalizeStart(
          roomInt(manualRefresh?"source_manual_next_feed":"source_routine_next_feed_index",0),feeds.length);
      int routineEnd=manualRefresh?Math.min(feeds.length,routineStart+1):
          SourceUpdateSchedulePolicy.routineSliceEnd(routineStart,feeds.length);
      int feedLimit=initialDiscoveryChain?feeds.length:routineEnd-routineStart;
      for(int feedOffset=0;feedOffset<feedLimit;feedOffset++){
        int feedIndex=initialDiscoveryChain
            ?SourceDiscoveryPriorityPolicy.nextFeed(initialNextFeedIndex,
                inspectedFeeds,attemptedFeedsThisRun,
                SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
                SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT)
            :routineStart+feedOffset;
        if(feedIndex<0)break;
        // Full rebuild: inspect China mainland feeds first (CCTV, provincial,
        // county/local); then HK/Macau/Taiwan; only then international feeds.
        // Rotate fairly within a stage and resume each feed's saved page cursor.
        // Keep the 24-probe budget and yield to playback in every stage.
        String feed=feeds[feedIndex];
        String cursorKey=initialDiscoveryChain?feedCursorKey(feedIndex):
            manualRefresh?manualFeedCursorKey(feedIndex):routineFeedCursorKey(feedIndex);
        int feedCursor=roomInt(cursorKey,0);
        String feedVersion="";
        int[] parsedCount={0};
        int indexedEntries=-1; // Count from the validated cached playlist index.
        boolean feedReadSucceeded=false;
        boolean feedTerminalUnavailable=false;
        if(isStopped())return Result.retry();
        if(pauseRequested.getAsBoolean()){
          saveStatus(prefs,"频道播放或浏览开始，本轮抓源已让路",0);
          return deferredViewingResult(manual,initialDiscoveryChain);
        }
        ArrayList<Entry> feedCandidates=new ArrayList<>();
        boolean feedCandidatesAdded=false;
        try{
          // WorkManager may resume the same large feed over many runs. Reuse a
          // complete local snapshot so each continuation does not redownload
          // the entire playlist prefix. The cache revalidates after 24 hours so a multi-run rebuild keeps a stable feed snapshot.
          // A playback session can begin after the batch-level check above.
          // Do not start a potentially large network download in that case.
          if(isStopped())return Result.retry();
          if(pauseRequested.getAsBoolean()){
            saveStatus(prefs,"节目播放或频道操作优先，已推迟频道源下载",0);
            return deferredViewingResult(manual,initialDiscoveryChain);
          }
          if(slowPlayback&&android.os.SystemClock.elapsedRealtime()<LIVE_DIRECTORY_RETRY_AFTER.getOrDefault(feedIndex,0L))continue;
          noteInitialStage("读取目录 "+(feedIndex+1)+"/"+feeds.length+"（下载或缓存）");
          File cachedFeed=initialDiscoveryChain?PlaylistFeedCache.findIndexedInitial(getApplicationContext(),feed):
              PlaylistFeedCache.obtain(getApplicationContext(),feed,false,feedCursor>0,pauseRequested);
          if(initialDiscoveryChain&&!slowPlayback&&(cachedFeed==null||!DiscoveryBatchPolicy.isPreparedInitialFeed(
              roomString(initialAcquiredVersionKey(feedIndex),""),PlaylistFeedCache.contentVersion(cachedFeed)))){
            // The acquisition-only slice is independent of the probe cursor.
            // Never reuse a marker if eviction or a changed body invalidated it.
            noteInitialStage("独立准备目录 "+(feedIndex+1)+"/"+feeds.length);
            cachedFeed=PlaylistFeedCache.obtain(getApplicationContext(),feed,true,feedCursor>0,pauseRequested);
            PlaylistFeedIndex.readPage(cachedFeed,0,1,source->{},pauseRequested);
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
            commitInitialAcquisition(feedIndex,cachedFeed);
            enqueueInitialContinuation();
            return Result.success();
          }
          if(cachedFeed==null&&slowPlayback){
            // Acquisition is its own bounded slice: no stream probe in the same
            // worker. Never advance the verified cursor after a download alone.
            noteInitialStage("稳定直播低速下载目录 "+(feedIndex+1)+"/"+feeds.length);
            try{
              cachedFeed=PlaylistFeedCache.obtainSmallInitialDuringViewing(
                  getApplicationContext(),feed,feedCursor>0,pauseRequested);
              PlaylistFeedIndex.readPage(cachedFeed,0,1,source->{},pauseRequested);
              SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
              LIVE_DIRECTORY_RETRY_AFTER.remove(feedIndex);
              commitInitialAcquisition(feedIndex,cachedFeed);
              enqueueInitialContinuation();
              return Result.success();
            }catch(Exception failure){
              if(pauseRequested.getAsBoolean()||failure instanceof SourceProbeTimePolicy.PausedException)
                return deferredViewingResult(manual,initialDiscoveryChain);
              LIVE_DIRECTORY_RETRY_AFTER.put(feedIndex,android.os.SystemClock.elapsedRealtime()+300000L);
              // One network acquisition per viewing slice, including failures.
              // Next slice starts at another feed; the deferred page stays intact.
              SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                  .putInt("source_initial_next_feed_index",InitialFeedRotationPolicy.nextAfterAttempt(feedIndex,feeds.length,false,false))
                  .putString("source_initial_progress_phase","较大或较慢目录待空闲补查，继续其他目录")
                  .apply();
              enqueueInitialContinuation();
              return Result.success();
            }
          }
          feedVersion=PlaylistFeedCache.contentVersion(cachedFeed);
          feedCursor=DiscoveryBatchPolicy.cursorForVersion(feedCursor,
              roomString(cursorKey+"_version",""),feedVersion);
          int feedPageSize=initialDiscoveryChain
              ?MAX_INITIAL_DISCOVERY_PER_FEED:manualRefresh?MAX_PROBES_PER_RUN/2:MAX_DISCOVERY_PER_FEED;
          noteInitialStage("解析目录 "+(feedIndex+1)+"/"+feeds.length+" · 游标 "+feedCursor);
          PlaylistParser.EntryConsumer consume=source->{
            String scope=RegionCategoryPolicy.catalogueScope(
                source.name,source.group,source.tvgName,source.tvgId,scopeForFeed(feed));
            feedCandidates.add(new Entry(source.name,source.url,source.group,false,scope,
                source.tvgId,source.tvgName));
          };
          parsedCount[0]=slowPlayback?PlaylistFeedIndex.readExistingPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested):
              PlaylistFeedIndex.readPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested);
          if((initialDiscoveryChain||manualRefresh)&&feedIndex<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT){
            // Preserve every address and the exact raw index cursor. Within the
            // current 24-row page, probe mainland candidates first. Java's List
            // sort is stable, so partial-batch fingerprint/resume stays deterministic.
            feedCandidates.sort((aEntry,bEntry)->Integer.compare(
                ChinaDiscoveryCandidatePolicy.initialProbePriority(feedIndex,
                    aEntry.scope,aEntry.name,aEntry.group,aEntry.tvgName,aEntry.tvgId),
                ChinaDiscoveryCandidatePolicy.initialProbePriority(feedIndex,
                    bEntry.scope,bEntry.name,bEntry.group,bEntry.tvgName,bEntry.tvgId)));
          }
          indexedEntries=PlaylistFeedIndex.indexedEntryCount(cachedFeed);
          // Parsing a cached large playlist can overlap a newly started player.
          // Do not enqueue its candidates for probing or advance any cursor.
          if(isStopped())return Result.retry();
          if(pauseRequested.getAsBoolean()){
            saveStatus(prefs,"频道操作开始，本轮目录整理已暂停且未发布部分结果",0);
            return deferredViewingResult(manual,initialDiscoveryChain);
          }
          // Keep per-feed discovery counts for diagnosing missing regional stations.
          // Log metadata only; never expose individual stream URLs or credentials.
          int localMetadataCandidates=0;
          int provinceResolvedCandidates=0;
          for(Entry candidate:feedCandidates){
            // Count the same metadata used by catalogueScope, including EPG
            // aliases. Otherwise diagnostics can report zero local stations
            // even when tvg-name/tvg-id identifies their province.
            String metadata=(candidate.name==null?"":candidate.name)+" "
                +(candidate.group==null?"":candidate.group)+" "
                +(candidate.tvgName==null?"":candidate.tvgName)+" "
                +(candidate.tvgId==null?"":candidate.tvgId);
            String normalized=metadata.toLowerCase(Locale.ROOT);
            boolean provinceResolved=!ChannelNamePolicy.provinceForLocalChannel(metadata).isEmpty();
            if(provinceResolved)provinceResolvedCandidates++;
            if(provinceResolved||normalized.contains("地方")||normalized.contains("local")){
              localMetadataCandidates++;
            }
          }
          android.util.Log.i("MyTVSourceDiscovery","feed_index="+feedIndex
              +" cursor="+feedCursor+" parsed="+parsedCount[0]
              +" candidates="+feedCandidates.size()
              +" mainland_priority_page="
                  +((initialDiscoveryChain||manualRefresh)&&feedIndex<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT)
              +" local_metadata="+localMetadataCandidates
              +" province_resolved="+provinceResolvedCandidates
              +" feed_scope="+scopeForFeed(feed));
          if(!feedCandidates.isEmpty())feedsWithEntries++;
          int remaining=MAX_DISCOVERY_CANDIDATES_PER_RUN-candidates.size();
          if(feedCandidates.size()>remaining){
            // Never advance a feed cursor after silently truncating its parsed page.
            // The next worker run starts with a fresh per-run budget and retries it.
            feedCandidates.clear();
            throw new IOException("本轮候选容量已满，保留目录游标并在下轮继续");
          }
          candidates.addAll(feedCandidates);
          feedCandidatesAdded=true;
          feedReadSucceeded=true;
          feedsOk++;
        }catch(Exception e){
          // A viewing pause is not a source failure or an exhausted feed.
          if(e instanceof SourceProbeTimePolicy.PausedException||pauseRequested.getAsBoolean()){
            saveStatus(prefs,"频道播放优先，目录读取已暂停，保留原游标",0);
            return deferredViewingResult(manual,initialDiscoveryChain);
          }
          if(slowPlayback){
            LIVE_DIRECTORY_RETRY_AFTER.put(feedIndex,android.os.SystemClock.elapsedRealtime()+300000L);
            continue;
          }
          // Keep complete entries parsed before a transient stream read failure;
          // an incomplete feed is still reported as failed and will be retried later.
          // Failed pages never contribute partial candidates or advance a cursor.
          // A partial initial feed is retried from its unchanged cursor. Do not
          // mix its partial page with another feed's batch, or a later fingerprint
          // change could discard unprobed entries from the partial page.
          String failure=String.valueOf(e.getMessage());
          // A dead/blocked provider is an inspected, UNAVAILABLE outcome rather
          // than a successful feed. Bound transient failures to three separate
          // worker attempts; preserve its failure record for later idle/manual retry.
          if(initialDiscoveryChain){
            int attempts=InitialFeedFailurePolicy.nextAttempts(
                roomInt(initialFeedFailureCountKey(feedIndex),0));
            update.putInt(initialFeedFailureCountKey(feedIndex),attempts)
                .putString(initialFeedFailureReasonKey(feedIndex),
                    InitialFeedFailurePolicy.userVisibleReason(failure))
                .putString(initialFeedDisplayLabelKey(feedIndex),safeFeedLabel(feed));
            feedTerminalUnavailable=InitialFeedFailurePolicy.isTerminalUnavailable(
                failure,attempts);
            if(feedTerminalUnavailable)android.util.Log.w("MyTVSourceDiscovery",
                "first_build_feed_marked_unavailable index="+feedIndex+" attempts="+attempts);
          }
          if(manualRefresh){
            int attempts=InitialFeedFailurePolicy.nextAttempts(
                roomInt("source_manual_feed_attempts",0));
            update.putInt("source_manual_feed_attempts",attempts);
            feedTerminalUnavailable=InitialFeedFailurePolicy.isTerminalUnavailable(failure,attempts);
          }
          feedsFailed++;if(errors.length()<350)errors.append(feed.substring(feed.lastIndexOf('/')+1)).append("；");
        }finally{
          feedBatches.add(new FeedBatch(feedIndex,feedCursor,parsedCount[0],
              indexedEntries,feedReadSucceeded||feedTerminalUnavailable,
              feedTerminalUnavailable,feedVersion));
          if(initialDiscoveryChain)attemptedFeedsThisRun[feedIndex]=true;
        }
        if(initialDiscoveryChain){
          if(feedReadSucceeded){
            // A completed page yields to the next unfinished directory. The
            // per-feed cursor persists even if the directory has many pages.
            initialNextFeedIndex=InitialFeedRotationPolicy.nextAfterAttempt(
                feedIndex,feeds.length,true,
                parsedCount[0]>=MAX_INITIAL_DISCOVERY_PER_FEED);
            break;
          }
          // A bounded failure remains on record as unavailable, not as successful
          // download. Other feeds can still make a usable complete snapshot.
          initialNextFeedIndex=InitialFeedRotationPolicy.nextAfterAttempt(
              feedIndex,feeds.length,feedTerminalUnavailable,false);
          // One failed directory per idle slice, then commit its actual outcome.
          break;
        }
      }
      if(slowPlayback&&feedsOk==0)return deferInitialDirectory("稳定直播中：本阶段剩余目录待空闲补查，已保存断点");
      ArrayList<String> savedScanUrls=new ArrayList<>(savedPool.size());
      for(Entry entry:savedPool)savedScanUrls.add(entry.url);
      ArrayList<String> discoveryScanUrls=new ArrayList<>(candidates.size());
      for(Entry entry:candidates)discoveryScanUrls.add(entry.url);
      int savedScanStart=SourceSelectionPolicy.nextScanStart(savedScanUrls, roomString("source_saved_scan_after_key",""), roomInt("source_saved_scan_cursor",0));
      // During a full rebuild every probe slot belongs to newly discovered feed
      // candidates. Staged rows already retain their measured scores; repeatedly
      // health-checking them here would starve unseen entries and slow completion.
      int savedWindow=initialDiscoveryChain?0:Math.min(MAX_PROBES_PER_RUN/2,savedPool.size());
      int pageFeed=-1;
      if(initialDiscoveryChain)for(FeedBatch batch:feedBatches)
        if(batch.success&&!batch.unavailable){pageFeed=batch.index;break;}
      String batchPrefix=initialDiscoveryChain?DiscoveryBatchPolicy.initialBatchPrefix(pageFeed):
          manualRefresh?"source_manual_feed_batch_":"source_routine_feed_batch_";
      StringBuilder batchIdentity=new StringBuilder();
      appendFingerprintPart(batchIdentity,discoveryFingerprint(candidates));
      for(FeedBatch batch:feedBatches){
        // Only pages that actually contributed candidates determine probe
        // resume. A preceding unavailable feed must not invalidate the same page.
        if(!batch.success||batch.unavailable||batch.parsed<=0)continue;
        appendFingerprintPart(batchIdentity,Integer.toString(batch.index));
        appendFingerprintPart(batchIdentity,batch.version);
        appendFingerprintPart(batchIdentity,Integer.toString(batch.cursor));
      }
      String discoveryFingerprint=CatalogueFingerprint.sha256(batchIdentity.toString());
      String previousDiscoveryFingerprint=roomString(batchPrefix+"fingerprint","");
      int previousScanned=roomInt(batchPrefix+"scanned",0);
      // An upgrade may have one committed legacy page. Migrate only if the
      // full page fingerprint matches (including feed, version and cursor).
      if(initialDiscoveryChain&&pageFeed>=0&&previousDiscoveryFingerprint.isEmpty()
          &&discoveryFingerprint.equals(roomString("source_initial_feed_batch_fingerprint",""))){
        previousDiscoveryFingerprint=discoveryFingerprint;
        previousScanned=roomInt("source_initial_feed_batch_scanned",0);
        update.remove("source_initial_feed_batch_fingerprint").remove("source_initial_feed_batch_scanned");
      }
      int initialDiscoveryScanned=DiscoveryBatchPolicy.scannedForBatch(
          discoveryFingerprint,previousDiscoveryFingerprint,previousScanned,candidates.size());
      // Resume by exact position inside an unchanged batch. URL-based cursors are
      // unsafe because distinct channel identities can share one endpoint; choosing
      // the last duplicate URL can skip entries that were not yet scanned.
      int discoveryScanStart=DiscoveryBatchPolicy.startIndex(
          initialDiscoveryScanned,candidates.size());
      int discoveryWindow=Math.min(DiscoveryBatchPolicy.remaining(
          initialDiscoveryScanned,candidates.size()),MAX_PROBES_PER_RUN-savedWindow);
      if(slowPlayback)discoveryWindow=Math.min(1,discoveryWindow);
      ArrayList<Entry> probeCandidates=new ArrayList<>(savedWindow+discoveryWindow);
      int savedIndex=0,discoveryIndex=0;
      while(savedIndex<savedWindow||discoveryIndex<discoveryWindow){
        if(discoveryIndex<discoveryWindow){
          Entry e=candidates.get((discoveryScanStart+discoveryIndex)%candidates.size());
          probeCandidates.add(e);discoveryIndex++;
        }
        if(savedIndex<savedWindow){
          Entry e=savedPool.get((savedScanStart+savedIndex)%savedPool.size());
          probeCandidates.add(e);savedIndex++;
        }
      }
      // Use independent rotating cursors and a fixed per-run budget for discovery
      // and saved-source health checks. A moving 5,000-item window combined with one
      // cursor could skip most saved URLs forever as the window changed between runs.
      long probeDeadline=android.os.SystemClock.elapsedRealtime()+PROBE_STAGE_DEADLINE_MS;
      int savedScanned=0,discoveryScanned=0,reusedPreflights=0,initialTimeouts=0;
      HashSet<String> visitedUrls=new HashSet<>();
      HashSet<String> savedStreakUpdatedThisRun=new HashSet<>();
      HashMap<String,ProbeResult> measuredByEndpoint=new HashMap<>();
      for(int offset=0;offset<probeCandidates.size();offset++){
        if(pauseRequested.getAsBoolean()){
          saveStatus(prefs,"用户使用频道时暂停后续线路检测",0);
          return deferredViewingResult(manual,initialDiscoveryChain);
        }
        if(checked>=MAX_PROBES_PER_RUN||isStopped()||android.os.SystemClock.elapsedRealtime()>=probeDeadline)break;
        Entry e=probeCandidates.get(offset);
        if(e.savedSource)savedScanned++;else discoveryScanned++;
        if(!e.url.startsWith("http://")&&!e.url.startsWith("https://"))continue;
        String endpoint=SourceSelectionPolicy.endpointKey(e.url);
        ProbeResult measured=measuredByEndpoint.get(endpoint);
        if(measured==null){
          if(!visitedUrls.add(endpoint))continue;
          String cacheKey=initialDiscoveryChain?StableKey.url(e.url):"";
          RecentProbe cached=initialDiscoveryChain?INITIAL_PROBES.get(cacheKey):null;
          long clock=android.os.SystemClock.elapsedRealtime();
          boolean reuse=cached!=null&&SourceUpdateSchedulePolicy.canReuseInitialProbe(
              cached.result.valid,cached.at,clock);
          long addressDeadline=probeDeadline;
          if(initialDiscoveryChain&&!reuse){
            addressDeadline=SourceProbeTimePolicy.initialAddressDeadline(probeDeadline,clock);
            if(addressDeadline==0L){
              // Do not start a new probe with a shortened budget at the tail.
              discoveryScanned--;
              break;
            }
          }
          if(reuse){
            measured=cached.result;
            reusedPreflights++;
          }else{
            checked++;
            noteInitialStage((slowPlayback?"稳定直播低速检测 · ":"")+"本批检测第 "+checked+" 条（尚未提交）");
            measured=probe(e.url,addressDeadline);
          }
          if(pauseRequested.getAsBoolean()||Thread.currentThread().isInterrupted()){
            saveStatus(prefs,"频道播放优先，线路检测已暂停，不记录失败",0);
            return deferredViewingResult(manual,initialDiscoveryChain);
          }
          if(probeDeadlineReached(probeDeadline)){
            // A source interrupted by the global deadline is inconclusive, not a failed source.
            if(e.savedSource)savedScanned--;else discoveryScanned--;
            break;
          }
          if(initialDiscoveryChain&&!reuse
              &&android.os.SystemClock.elapsedRealtime()>=addressDeadline){
            // The address received its bounded first-pass opportunity. Do not
            // promote, penalize or cache an inconclusive result. The later
            // manual/routine scan can retry with the normal probe budget.
            initialTimeouts++;
            visitedUrls.remove(endpoint);
            continue;
          }
          if(initialDiscoveryChain&&!reuse){
            INITIAL_PROBES.put(cacheKey,new RecentProbe(measured,android.os.SystemClock.elapsedRealtime()));
            while(INITIAL_PROBES.size()>INITIAL_PROBE_CACHE_CAP)
              INITIAL_PROBES.remove(INITIAL_PROBES.keySet().iterator().next());
          }
          measuredByEndpoint.put(endpoint,measured);
          // A previously good source that now fails its probe must lose its old high score.
          // Keep the URL as a fallback, but rank it below sources that still pass validation.
          measuredScores.put(scoreKey(e.url),measured.valid?measured.score:-500);
          measuredScores.remove(legacyScoreKey(e.url));
        }
        if(e.savedSource&&savedStreakUpdatedThisRun.add(endpoint)){
          String streakKey=scoreKey(e.url);
          int streak=SourceMaintenanceRetentionPolicy.nextFailureStreak(
              probeFailureStreaks.optInt(streakKey,0),measured.valid);
          if(streak==0)probeFailureStreaks.remove(streakKey);
          else probeFailureStreaks.put(streakKey,streak);
        }
        if(measured.valid&&(!e.savedSource||initialDiscoveryChain)){
          discoveryProbesValid++;
          if(initialDiscoveryChain&&ChinaDiscoveryCandidatePolicy.isClearlyMainland(
              e.scope,e.name,e.group,e.tvgName,e.tvgId))chinaPreflightsValid++;
        }
        // Whether this is the first full build or routine discovery, a
        // newly discovered URL must pass the media preflight before it may
        // become a published channel or a new backup. Failed saved sources
        // remain protected by the independent Room retention policy above.
        if(!SourceDiscoveryAcceptancePolicy.mayPromoteNewCandidate(measured.valid))
          continue;
        String key=identity(e.name,e.group,e.url,e.tvgId,e.tvgName);JSONObject target=byIdentity.get(key);
        if(target!=null){
          String canonical=ChannelNamePolicy.canonicalBroadcasterName(
              e.name,e.tvgName,e.tvgId,e.group);
          if(!canonical.isEmpty())target.put("name",canonical);
          if(target.optString("tvgId","").isEmpty()&&!e.tvgId.isEmpty())target.put("tvgId",e.tvgId);
          if(target.optString("tvgName","").isEmpty()&&!e.tvgName.isEmpty())target.put("tvgName",e.tvgName);
        }
        // Merge every candidate for an existing channel through one retention policy.
        // This protects the stable primary and last selected source before any
        // capacity replacement occurs, rather than trying to restore them afterwards.
        if(target==null){
          target=new JSONObject();JSONArray sources=new JSONArray();sources.put(e.url);
          target.put("name",canonicalDisplayName(e));target.put("url",e.url);target.put("group",e.group);
          target.put("tvgId",e.tvgId);target.put("tvgName",e.tvgName);target.put("sources",sources);
          addBackupSourceScope(target,e.url,e.scope);
          if(initialDiscoveryChain)stagedPositions.put(target,saved.length());
          saved.put(target);byIdentity.put(key,target);added++;
        }else{
          JSONArray currentSources=target.optJSONArray("sources");
          ArrayList<String> currentUrls=new ArrayList<>();
          if(currentSources!=null){
            for(int i=0;i<currentSources.length();i++)currentUrls.add(currentSources.optString(i,""));
          }else{
            String primary=target.optString("url","");
            if(!primary.isEmpty())currentUrls.add(primary);
          }
          int activeIndex=Math.max(0,target.optInt("activeSource",0));
          String activeUrl=activeIndex<currentUrls.size()
              ?currentUrls.get(activeIndex):target.optString("url","");
          String primaryUrl=target.optString("url","");
          java.util.Comparator<String> bestFirst=(a,b)->
              Double.compare(combinedScore(b,measuredScores,observedQoe),
                  combinedScore(a,measuredScores,observedQoe));
          ArrayList<String> retainedUrls;
          if(initialDiscoveryChain){
            retainedUrls=new ArrayList<>(SourceChannelMergePolicy.attachSharedEndpoint(
                currentUrls,e.url,MAX_SOURCES_PER_CHANNEL,bestFirst,primaryUrl,activeUrl));
          }else{
            HashSet<String> eligibleRetirement=new HashSet<>();
            for(String oldUrl:currentUrls){
              if(probeFailureStreaks.optInt(scoreKey(oldUrl),0)
                  >=SourceMaintenanceRetentionPolicy.FAILURES_BEFORE_RETIREMENT)
                eligibleRetirement.add(SourceSelectionPolicy.endpointKey(oldUrl));
            }
            retainedUrls=new ArrayList<>(SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(
                currentUrls,e.url,MAX_SOURCES_PER_CHANNEL,bestFirst,
                primaryUrl,activeUrl,eligibleRetirement));
          }
          boolean changed=currentUrls.size()!=retainedUrls.size();
          if(!changed){
            for(int i=0;i<currentUrls.size();i++){
              if(!currentUrls.get(i).equals(retainedUrls.get(i))){changed=true;break;}
            }
          }
          if(changed){
            JSONArray updatedSources=new JSONArray();
            for(String retainedUrl:retainedUrls)updatedSources.put(retainedUrl);
            target.put("sources",updatedSources);
            added++;
          }
          String retainedEndpointUrl="";
          for(String retainedUrl:retainedUrls){
            if(endpoint.equals(SourceSelectionPolicy.endpointKey(retainedUrl))){
              retainedEndpointUrl=retainedUrl;break;
            }
          }
          if(!retainedEndpointUrl.isEmpty()){
            addBackupSourceScope(target,retainedEndpointUrl,e.scope);
          }
        }
        if(initialDiscoveryChain)touchedStagedRows.add(stagedPositions.get(target));
      }
      // Even if every discovery directory is temporarily unavailable, continue
      // rotating through already-saved source URLs. Existing channels still need
      // health checks while public catalogue services are down; retry discovery
      // separately after persisting these local probe results.
      boolean discoveryUnavailable=feedsOk==0;
      if(initialDiscoveryChain)android.util.Log.i("MyTVSourceDiscovery","network_probes="+checked+" reused_recent="+reusedPreflights+" inconclusive_budget="+initialTimeouts);
      // Keep each channel's best distinct sources together, ranked by measured
      // reachability/speed/resolution. Generic names remain host-separated; recognized
      // CCTV and satellite channels merge across source hosts.
      // Initial staging is append-only and touched rows alone need sorting.
      // A final idle pass re-ranks the complete snapshot before publication.
      boolean finalRanking=initialDiscoveryChain&&initialDiscoveryScanned+discoveryScanned>=candidates.size()
          &&areAllDiscoveryFeedsComplete(prefs,feedBatches,feeds.length)&&!slowPlayback;
      Iterable<Integer> rankingRows=initialDiscoveryChain&&!finalRanking?touchedStagedRows:
          ()->new Iterator<Integer>(){private int index;public boolean hasNext(){return index<saved.length();}public Integer next(){return index++;}};
      for(int i:rankingRows){
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        JSONObject channel=saved.optJSONObject(i);if(channel==null)continue;
        JSONArray sourceArray=channel.optJSONArray("sources");
        if(sourceArray==null||sourceArray.length()==0){String primary=channel.optString("url","");if(primary.isEmpty())continue;sourceArray=new JSONArray();sourceArray.put(primary);}
        // Preserve the user's last selected source across background re-ranking.
        // Ranking may change the preferred primary URL, but must not silently
        // replace the source that the user explicitly selected for this channel.
        int previousActiveIndex=Math.max(0,channel.optInt("activeSource",0));
        String previousActiveUrl=previousActiveIndex<sourceArray.length()?sourceArray.optString(previousActiveIndex,""):channel.optString("url","");
        ArrayList<String> ordered=new ArrayList<>();
        HashSet<String> orderedEndpoints=new HashSet<>();
        for(int j=0;j<sourceArray.length();j++){
          String u=sourceArray.optString(j,"");
          if((u.startsWith("http://")||u.startsWith("https://"))
              &&orderedEndpoints.add(SourceSelectionPolicy.endpointKey(u)))ordered.add(u);
        }
        String stablePrimaryUrl=channel.optString("url","");
        // Preserve both the stable channel identity and the user's last selected
        // source even when the ranked list is trimmed to the eight-source cap.
        ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
        ordered=new ArrayList<>(SourceRankingRetentionPolicy.retainRequiredAndBest(
            ordered,stablePrimaryUrl,previousActiveUrl,MAX_SOURCES_PER_CHANNEL));
        ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
        JSONArray ranked=new JSONArray();int restoredActiveIndex=0;
        for(int j=0;j<ordered.size();j++){String u=ordered.get(j);ranked.put(u);if(u.equals(previousActiveUrl))restoredActiveIndex=j;}
        if(ranked.length()>0){
          // Keep url as the stable channel identity. The ranked source list and
          // activeSource carry playback preference independently.
          channel.put("sources",ranked);channel.put("activeSource",restoredActiveIndex);
          retainBackupSourceScopes(channel,ranked);
        }
      }
      // Persist measured quality independently from URL labels so the player can rank
      // candidates by reachability, estimated speed and manifest resolution. This is
      // a preflight score, not a guarantee of audio/video playback.
      trimProbeScores(measuredScores,savedPool,probeCandidates);
      trimProbeScores(probeFailureStreaks,savedPool,probeCandidates);
      // The database snapshot is checked again at commit time by Room's transaction;
      // no SharedPreferences catalogue comparison participates in this update.
      // Playback can begin after the last probe-loop check but before persistence.
      // Re-check immediately before the catalogue commit so a just-started session
      // is not overlapped by the worker's final state write.
      if(pauseRequested.getAsBoolean()){
        saveStatus(prefs,"观看或频道操作已开始，放弃本轮发布，旧完整目录仍然可用",0);
        return deferredViewingResult(manual,initialDiscoveryChain);
      }
      if(isStopped())return Result.retry();
      int nextBatchScanned=Math.min(candidates.size(),initialDiscoveryScanned+discoveryScanned);
      boolean feedBatchComplete=nextBatchScanned>=candidates.size();
      if(initialDiscoveryChain&&!feedBatches.isEmpty()){
        // If a 90-second deadline paused probing inside the current feed page,
        // resume this exact feed and its fingerprint before rotating onward.
        // Otherwise its prefix would be rescanned and the tail could starve.
        int attemptedFeed=feedBatches.get(feedBatches.size()-1).index;
        initialNextFeedIndex=slowPlayback&&pageFeed>=0?(pageFeed+1)%feeds.length:
            InitialFeedRotationPolicy.nextAfterProbeBatch(
                attemptedFeed,initialNextFeedIndex,feeds.length,feedBatchComplete);
      }
      boolean initialFeedsExhausted=initialDiscoveryChain&&feedBatchComplete
          &&areAllDiscoveryFeedsComplete(prefs,feedBatches,feeds.length);
      boolean initialHasValidatedSource=initialDiscoveryChain
          &&(roomBoolean("source_initial_has_validated_source",false)||discoveryProbesValid>0);
      boolean awaitingIdleFinalization=FullDiscoveryProgressPolicy.awaitingIdleFinalization(initialFeedsExhausted,slowPlayback);
      boolean initialDiscoveryComplete=FullDiscoveryProgressPolicy.canPublishInitialSnapshot(
          initialFeedsExhausted,initialHasValidatedSource,slowPlayback);
      // Save the last probed page in staging even while viewing. Only merging
      // the live snapshot (or resetting an unsuccessful build) requires idle.
      if(initialDiscoveryComplete)roomSnapshot=readPublishedForInitialCommit();
      // Prepare the full publication and all progress before opening a write transaction.
      JSONArray publication=initialDiscoveryComplete
          ?preservePreviouslyPublishedChannels(saved,roomSnapshot):saved;
      int publishedChannelCount=publication.length();
      if(initialDiscoveryComplete&&saved.length()==0)
        throw new IllegalStateException("Full rebuild has no validated channels");
      if(pauseRequested.getAsBoolean())
        return deferredViewingResult(manual,initialDiscoveryChain);
      update.putInt("source_scan_cursor",candidates.isEmpty()?0:(discoveryScanStart+discoveryScanned)%candidates.size())
          .putInt("source_saved_scan_cursor",savedPool.isEmpty()?0:(savedScanStart+savedScanned)%savedPool.size())
          .putString("source_scan_after_key",discoveryScanned>0?SourceSelectionPolicy.lastScannedKey(discoveryScanUrls,discoveryScanStart,discoveryScanned):roomString("source_scan_after_key",""))
          .putString("source_saved_scan_after_key",savedScanned>0?SourceSelectionPolicy.lastScannedKey(savedScanUrls,savedScanStart,savedScanned):roomString("source_saved_scan_after_key",""))
          .putString("source_probe_scores_v1",measuredScores.toString())
          .putString("source_probe_failure_streaks_v1",probeFailureStreaks.toString())
          // The exact catalogue snapshot is represented by a compact fingerprint.
          .putString("source_update_time",now())
          .putString("source_update_result",(initialDiscoveryChain
              ?(initialDiscoveryComplete?"全量目录采集完成并已原子发布；":"独立全量目录构建中，保留已发布频道；")
              :"已安全更新当前目录；")+(initialDiscoveryChain?"首次全量重建按批次覆盖全部配置目录；":(foregroundAtStart||isActivityActive(prefs)?"日常更新优先读取中国及港澳台目录，全球目录留待后台运行时补充；":"本轮已检查中国及全球公开目录；"))+(discoveryUnavailable?"公开目录暂不可用，仍完成已保存线路的轮检；":(feedsWithEntries==0?"目录可访问，但没有解析出有效频道条目；":(discoveryProbesValid==0?(initialHasValidatedSource?"本轮新增候选未通过媒体预检，未纳入已验证频道；":"已解析频道条目，但尚无候选线路通过媒体预检；"):"成功读取 "+feedsOk+" 个目录，"+feedsWithEntries+" 个含频道条目，"+discoveryProbesValid+" 条候选通过媒体预检；")))+"初筛 "+checked+" 个候选地址；新增/替换 "+added+" 个备用地址；每频道最多保留 "+MAX_SOURCES_PER_CHANNEL+" 条线路；网络初筛不能保证实际画面与声音，仍需播放器验证；"+(errors.length()>0?"部分目录失败。":""));
      if(initialDiscoveryChain){
        update.putInt("source_initial_next_feed_index",initialNextFeedIndex);
      }
      // Advance a feed only after every candidate in the current multi-feed batch
      // has had its probe turn. This prevents losing the tail when the probe deadline
      // interrupts a run, and ensures later playlist pages are eventually discovered.
      if(feedBatchComplete){
        for(FeedBatch batch:feedBatches){
          if(!batch.success)continue;
          if(!initialDiscoveryChain && batch.parsed>0
              && roomBoolean(initialFeedUnavailableKey(batch.index),false)){
            // A previously unavailable directory has now been fetched and its
            // candidates have completed this run's probe window. Clear its old
            // warning; other old source warnings remain intact.
            update.remove(initialFeedUnavailableKey(batch.index))
                .remove(initialFeedFailureCountKey(batch.index))
                .remove(initialFeedFailureReasonKey(batch.index))
                .remove(initialFeedDisplayLabelKey(batch.index));
          }
          if(initialDiscoveryChain&&batch.unavailable){
            update.putBoolean(initialFeedUnavailableKey(batch.index),true);
          }else if(initialDiscoveryChain){
            update.remove(initialFeedUnavailableKey(batch.index))
                .remove(initialFeedFailureCountKey(batch.index))
                .remove(initialFeedFailureReasonKey(batch.index))
                .remove(initialFeedDisplayLabelKey(batch.index));
          }
          String cursorKey=initialDiscoveryChain?feedCursorKey(batch.index):
              manualRefresh?manualFeedCursorKey(batch.index):routineFeedCursorKey(batch.index);
          if(initialDiscoveryChain&&SourceUpdateSchedulePolicy.manualFeedFinished(batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED)){
            update.putBoolean(initialFeedDoneKey(batch.index),true)
                .remove(cursorKey).remove(cursorKey+"_version");
          }else if(batch.parsed>0){
            if(!initialDiscoveryChain&&batch.indexedEntries>=0&&batch.cursor+batch.parsed>=batch.indexedEntries)
              update.remove(cursorKey).remove(cursorKey+"_version");
            else update.putInt(cursorKey,batch.cursor+batch.parsed)
                .putString(cursorKey+"_version",batch.version);
          }else if(initialDiscoveryChain){
            update.putBoolean(initialFeedDoneKey(batch.index),true)
                .remove(cursorKey).remove(cursorKey+"_version");
          }else{
            update.remove(cursorKey).remove(cursorKey+"_version");
          }
        }
        update.remove(batchPrefix+"fingerprint").remove(batchPrefix+"scanned");
      }else if(candidates.size()>0){
        update.putString(batchPrefix+"fingerprint",discoveryFingerprint)
            .putInt(batchPrefix+"scanned",nextBatchScanned);
      }
      if(!initialDiscoveryChain&&feedBatchComplete){
        int stillUnavailable=0;
        for(int i=0;i<feeds.length;i++){
          boolean wasUnavailable=roomBoolean(initialFeedUnavailableKey(i),false);
          if(wasUnavailable){
            for(FeedBatch batch:feedBatches){
              if(batch.index==i&&batch.success&&batch.parsed>0){wasUnavailable=false;break;}
            }
          }
          if(wasUnavailable)stillUnavailable++;
        }
        update.putInt("source_initial_progress_unavailable_feeds",stillUnavailable);
      }
      int previewLevel=0, previousPreviewLevel=0;
      if(initialDiscoveryChain){
        // Only count a source feed as done after its entire page was probed and
        // end-of-feed was observed. A successful HTTP response alone is not done.
        boolean[] finished=new boolean[feeds.length];
        int completedFeeds=0;
        for(int i=0;i<feeds.length;i++)finished[i]=roomBoolean(initialFeedDoneKey(i),false);
        if(feedBatchComplete){
          for(FeedBatch batch:feedBatches){
            if(batch.success&&batch.index>=0&&batch.index<feeds.length
                &&SourceUpdateSchedulePolicy.manualFeedFinished(batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED))
              finished[batch.index]=true;
          }
        }
        for(boolean done:finished)if(done)completedFeeds++;
        boolean[] unavailableFlags=new boolean[feeds.length];
        for(int i=0;i<feeds.length;i++)
          unavailableFlags[i]=roomBoolean(initialFeedUnavailableKey(i),false);
        if(feedBatchComplete){
          for(FeedBatch batch:feedBatches)
            if(batch.index>=0&&batch.index<feeds.length&&batch.unavailable
                &&batch.parsed<MAX_INITIAL_DISCOVERY_PER_FEED)
              unavailableFlags[batch.index]=true;
        }
        int unavailableCount=0;
        for(boolean missing:unavailableFlags)if(missing)unavailableCount++;
        int activeFeed=initialNextFeedIndex;
        int activeCursor=0,activeEntries=-1;
        for(FeedBatch batch:feedBatches){
          if(batch.success&&!finished[batch.index]&&batch.indexedEntries>0){
            activeFeed=batch.index;
            activeCursor=batch.cursor+(feedBatchComplete?batch.parsed:nextBatchScanned);
            activeEntries=batch.indexedEntries;
          }
        }
        int[] partial=new int[feeds.length];
        for(int i=0;i<feeds.length;i++)
          partial[i]=roomInt(initialFeedProgressKey(i),0);
        for(FeedBatch batch:feedBatches){
          if(batch.index<0||batch.index>=feeds.length||!batch.success)continue;
          int basis=finished[batch.index]?10000:
              FullDiscoveryProgressPolicy.feedProgress(
                  batch.cursor+(feedBatchComplete?batch.parsed:nextBatchScanned),
                  batch.indexedEntries);
          partial[batch.index]=basis;
          update.putInt(initialFeedProgressKey(batch.index),basis);
        }
        int progressPercent=FullDiscoveryProgressPolicy.overallPercent(
            finished,partial,initialDiscoveryComplete);
        int chinaFeedDone=SourceDiscoveryPriorityPolicy.completedInStage(
            finished,0,SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
        int activeStage=SourceDiscoveryPriorityPolicy.activeStage(finished,
            SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
            SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT);
        String stageDescription=SourceDiscoveryPriorityPolicy.stageLabel(activeStage);
        // A changed feed version can legitimately reduce the measured work.
        // Do not preserve an inflated high-water mark after a cursor reset.
        int checkedSoFar=Math.max(0,roomInt("source_initial_progress_checked_urls",0));
        int validatedSoFar=Math.max(0,roomInt("source_initial_progress_validated_urls",0));
        int chinaValidatedSoFar=Math.max(0,
            roomInt("source_initial_progress_china_validated_urls",0));
        previousPreviewLevel=roomInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0);
        previewLevel=FullDiscoveryProgressPolicy.nextPreviewLevel(previousPreviewLevel,saved.length(),
            (int)Math.min(Integer.MAX_VALUE,(long)chinaValidatedSoFar+chinaPreflightsValid),activeStage>0);
        int timeoutTotal=(int)Math.min(Integer.MAX_VALUE,(long)Math.max(0,roomInt("source_initial_progress_timeout_urls",0))+initialTimeouts);
        update.putInt("source_initial_progress_timeout_urls",timeoutTotal)
            .putInt("source_initial_progress_percent",progressPercent)
            .putInt("source_initial_progress_done_feeds",completedFeeds)
            .putInt("source_initial_progress_total_feeds",feeds.length)
            .putInt("source_initial_progress_china_done_feeds",chinaFeedDone)
            .putInt("source_initial_progress_china_total_feeds",
                SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT)
            .putInt("source_initial_progress_staged_channels",publishedChannelCount)
            .putInt("source_initial_progress_checked_urls",
                (int)Math.min(Integer.MAX_VALUE,(long)checkedSoFar+Math.max(0,checked)))
            .putInt("source_initial_progress_validated_urls",
                (int)Math.min(Integer.MAX_VALUE,
                    (long)validatedSoFar+Math.max(0,discoveryProbesValid)))
            .putInt("source_initial_progress_china_validated_urls",
                (int)Math.min(Integer.MAX_VALUE,
                    (long)chinaValidatedSoFar+Math.max(0,chinaPreflightsValid)))
            .putInt("source_initial_progress_unavailable_feeds",unavailableCount)
            .putInt("source_initial_progress_feed_index",Math.max(0,activeFeed))
            .putInt("source_initial_progress_feed_cursor",Math.max(0,activeCursor))
            .putInt("source_initial_progress_feed_entries",Math.max(0,activeEntries))
            .putString("source_initial_progress_phase",initialDiscoveryComplete
                ?("完整频道快照已发布，另有 "+unavailableCount+" 个来源暂不可用，待空闲补查")
                :(awaitingIdleFinalization?"线路初筛已完成，等待空闲发布或确认结果":stageDescription+"；暂不可用 "+unavailableCount+" 个"))
            .putString("source_update_result",initialDiscoveryComplete
                ?("首次全量目录检查已完成并发布；成功处理 "
                    +(completedFeeds-unavailableCount)+"/"+feeds.length
                    +" 个来源，另 "+unavailableCount+" 个已记录为暂不可用；通过媒体预检 "
                    +(validatedSoFar+Math.max(0,discoveryProbesValid))
                    +" 次（含近期重复地址复用，非实机播放验证）；预算超时未确认 "+timeoutTotal+" 次，待后续补查。")
                :("首次完整抓源进度 "+progressPercent+"%；已处理 "
                    +completedFeeds+"/"+feeds.length+" 个来源，暂不可用 "
                    +unavailableCount+" 个；预算超时未确认 "+timeoutTotal+" 次；已发布频道保留，未完成线路继续检测。"));
      }
      if(initialDiscoveryChain&&initialHasValidatedSource){
        update.putBoolean("source_initial_has_validated_source",true);
      }
      if(initialDiscoveryChain&&initialDiscoveryComplete){
        FullDiscoveryProgressPolicy.Coverage fresh=new FullDiscoveryProgressPolicy.Coverage();
        for(int i=0;i<saved.length();i++){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          JSONObject row=saved.getJSONObject(i);
          fresh.add(row.optString("name"),row.optString("group"),
              row.optString("tvgName"),row.optString("tvgId"));
        }
        update.putString(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY,
            fresh.summary("本次预检入库目录（合并旧频道之前，非实播验证）"));
        if(fresh.cctvCount()==0||fresh.satelliteCount()==0||fresh.localCount()==0){
          update.putString("source_initial_progress_phase",
              "目录已发布，核心中国频道覆盖不足，待空闲或手动补查");
        }
        update.remove("source_initial_discovery_fingerprint").remove("source_initial_discovery_scanned")
            .remove("source_initial_has_validated_source")
            .remove("source_full_rebuild_active");
        if(saved.length()>0){
          long completedAt=System.currentTimeMillis();
          update.putLong("source_update_last_success_at",completedAt)
              .putLong("source_full_rebuild_completed_at",completedAt);
        }
      }else if(!initialDiscoveryChain&&SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(discoveryProbesValid)){
        update.putLong("source_update_last_success_at",System.currentTimeMillis());
      }
      // A completed, committed probe pass advances the weekly deadline;
      // skipped or failed work leaves the due date unchanged.
      boolean manualComplete=false;
      if(manualRefresh){
        FeedBatch page=feedBatches.isEmpty()?null:feedBatches.get(0);
        boolean advance=page!=null&&feedBatchComplete&&page.success
            &&(page.unavailable||SourceUpdateSchedulePolicy.manualFeedFinished(
                page.cursor,page.parsed,page.indexedEntries,MAX_PROBES_PER_RUN/2));
        int next=advance?routineStart+1:routineStart;
        int failed=Math.max(0,roomInt("source_manual_failed_feeds",0));
        if(advance&&page.unavailable)failed++;
        if(advance||page!=null&&page.success&&!page.unavailable)
          update.putInt("source_manual_feed_attempts",0);
        manualComplete=next>=feeds.length;
        update.putInt("source_manual_next_feed",next)
            .putInt("source_manual_failed_feeds",failed)
            .putString("source_update_result","手动完整目录轮检：已处理 "+next+"/"+feeds.length
                +" 个来源，暂不可用 "+failed+" 个。"
                +(manualComplete?"本轮目录处理结束；":"已保存断点，空闲时继续；")
                +"旧频道保留，媒体预检不等于实播。");
        if(manualComplete)update.putString("source_manual_completed_id",manualId)
            .remove("source_manual_active_id");
      }else if(!initialDiscoveryChain){
        int nextRoutine=SourceUpdateSchedulePolicy.routineNextFeed(routineStart,feeds.length,feedBatchComplete);
        update.putInt("source_routine_next_feed_index",nextRoutine);
        if(!manual&&feedBatchComplete&&nextRoutine==0&&publishedChannelCount>0&&checked>0)
          update.putLong("source_last_routine_maintenance_at",System.currentTimeMillis());
        update.putString("source_update_result","日常分批轮检：目录 "+(routineStart+1)+"–"+routineEnd
            +"/"+feeds.length+"；读取成功 "+feedsOk+"，失败 "+feedsFailed
            +"；媒体预检 "+checked+" 次；"+(feedBatchComplete?"本批已处理":"保留本批游标，下次继续")
            +"。旧目录保留，实际播放待验证。");
      }
      update.remove("source_update_pending_base_fingerprint")
          .remove("source_update_pending_base")
          .remove("source_update_pending_channels");
      if(initialFeedsExhausted&&!initialHasValidatedSource&&!awaitingIdleFinalization){
        // Every feed and every current candidate has had its probe turn. Do not
        // retry an exhausted, unpublishable build forever merely because the TV
        // Activity happens to remain open; discard staging and retain the live DB.
        // A failed full scan must not preserve per-feed "done" flags or cursors.
        // Otherwise the next rebuild may start with every feed already exhausted,
        // see no candidates, and never retry discovery after network recovery.
        com.wodedianshi.tv.db.AppStateEditor failedBuildReset=update;
        for(int feed=0;feed<feeds.length;feed++){
          // Reset the publish/scan cursors for the next attempt, but leave the
          // last failure diagnostics visible in Settings until that next attempt
          // starts and explicitly initializes a fresh build.
          failedBuildReset.remove(initialFeedDoneKey(feed)).remove(feedCursorKey(feed))
              .remove(feedCursorKey(feed)+"_version")
              .remove(initialFeedProgressKey(feed))
              .remove(initialAcquiredVersionKey(feed))
              .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"fingerprint").remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"scanned");
        }
        failedBuildReset.remove("source_full_rebuild_active")
            .remove("source_full_rebuild_feed_fingerprint")
            .remove("source_initial_has_validated_source")
            .remove("source_initial_discovery_fingerprint")
            .remove("source_initial_discovery_scanned")
            .remove("source_feed_batch_fingerprint")
            .remove("source_feed_batch_scanned")
            .remove("source_initial_feed_batch_fingerprint")
            .remove("source_initial_feed_batch_scanned")
            .putInt("source_initial_next_feed_index",0)
            .putInt("source_initial_progress_percent",0)
            .putInt("source_initial_progress_done_feeds",0)
            .putInt("source_initial_progress_china_done_feeds",0)
            .putInt("source_initial_progress_staged_channels",0)
            .putInt("source_initial_progress_checked_urls",0)
            .putInt("source_initial_progress_validated_urls",0)
            .putInt("source_initial_progress_china_validated_urls",0)
            .putString("source_initial_progress_phase","本轮没有通过预检的有效频道，未发布快照；暂不可用目录和失败原因已保留，恢复网络后可手动重试");
        discardStagingReuse();
        com.wodedianshi.tv.db.RoomCatalogueBuildStore.clear(
            getApplicationContext(),failedBuildReset,pauseRequested);
        saveStatus(prefs,saved.length()==0
            ?"全量目录采集已结束，但没有可发布的有效频道；旧目录继续保留，等待下次重新采集"
            :"全量候选线路均未通过媒体预检；未替换旧目录，后续可重新采集",0);
        return Result.failure();
      }
      // Commit one unit: either unpublished staging + progress or published
      // rows + checkpoint + progress. Cancellation rolls back the whole unit.
      noteInitialStage(initialDiscoveryComplete?"正在发布完整快照":"正在保存本批已完成进度");
      if(initialDiscoveryChain&&!initialDiscoveryComplete){
        if(finalRanking)com.wodedianshi.tv.db.RoomCatalogueBuildStore.replace(
            getApplicationContext(),saved,pauseRequested,update);
        else {
          String revision=java.util.UUID.randomUUID().toString();
          update.putString(com.wodedianshi.tv.db.RoomCatalogueBuildStore.STAGING_REVISION_KEY,revision);
          com.wodedianshi.tv.db.RoomCatalogueBuildStore.updateTouched(
              getApplicationContext(),saved,previousStagedSize,touchedStagedRows,pauseRequested,update);
          // Remember only after the transaction has returned successfully.
          if(FullDiscoveryProgressPolicy.canRetainStaging(saved.length(),slowPlayback)){
            committedStaging=saved;committedStagingRevision=revision;
            committedStagingAt=android.os.SystemClock.elapsedRealtime();
          }
        }
        if(!slowPlayback&&previewLevel>previousPreviewLevel){
          // First commit staging and cursors. A failed preview never loses that work.
          noteInitialStage("正在发布阶段性已预检频道");
          roomSnapshot=readPublishedForInitialCommit();
          JSONArray preview=preservePreviouslyPublishedChannels(saved,roomSnapshot);
          com.wodedianshi.tv.db.AppStateEditor previewState=
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                  .putInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,previewLevel);
          int count=com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshotWithProgress(
              getApplicationContext(),preview,roomSnapshot.catalogueVersion,false,pauseRequested,previewState);
          if(count!=preview.length())throw new IllegalStateException("Preview directory count mismatch");
        }
      }else{
        int written=com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshotWithProgress(
            getApplicationContext(),publication,roomSnapshot.catalogueVersion,
            initialDiscoveryComplete,pauseRequested,update);
        if(written!=publishedChannelCount)
          throw new IllegalStateException("Committed directory count mismatch");
      }
      if(awaitingIdleFinalization)return deferInitialDirectory("线路初筛已完成，等待空闲发布或确认结果");
      if(initialDiscoveryChain&&!initialDiscoveryComplete&&!feedBatches.isEmpty()){
        // Empty pages and recorded failures are durable progress too; rotate
        // rather than repeatedly back off the same WorkRequest.
        enqueueInitialContinuation(feedsOk==0?30L:slowPlayback?20L:0L);
        return Result.success();
      }
      if(manualRefresh&&!manualComplete){
        // Each bounded slice commits before a successor is enqueued. A retry
        // after process death reads the committed cursor, not an old input offset.
        androidx.work.OneTimeWorkRequest continuation=
            new androidx.work.OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
                .setConstraints(new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInitialDelay(10,java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
                    .putString("manual_refresh_id",manualId).build()).build();
        androidx.work.WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
            "mytv_manual_source_update",androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,continuation)
            .getResult().get();
      }
      // Routine failures rotate to other directories and wait for the next idle
      // eligibility check; no aggressive WorkManager retry loop on dead feeds.
      if(!initialDiscoveryChain)return Result.success();
      if(initialDiscoveryComplete){INITIAL_PROBES.clear();discardStagingReuse();}
      return (discoveryUnavailable&&!initialDiscoveryComplete
              &&!(initialDiscoveryChain&&candidates.size()>0))
          ||(initialDiscoveryChain&&!initialDiscoveryComplete&&candidates.isEmpty())
          ?Result.retry():Result.success();
    }catch(SourceProbeTimePolicy.PublicationPausedException paused){
      saveStatus(prefs,"频道播放优先，已撤销尚未完成的目录事务，原频道继续可用",0);
      return deferredViewingResult(manual,initialDiscoveryChain);
    }catch(Exception e){saveStatus(prefs,"更新异常："+e.getMessage()+"（原频道源保留）",0);return Result.retry();}
  }
  private static void discardStagingReuse(){
    committedStaging=null;committedStagingRevision="";committedStagingAt=0L;
  }
  private JSONArray takeCommittedStaging() throws JSONException {
    JSONArray candidate=committedStaging;
    String revision=committedStagingRevision;
    long savedAt=committedStagingAt;
    discardStagingReuse();
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    if(candidate!=null&&FullDiscoveryProgressPolicy.canRetainStaging(candidate.length(),slowPlayback)
        &&FullDiscoveryProgressPolicy.canReuseStaging(candidate.length(),revision,
        roomString(com.wodedianshi.tv.db.RoomCatalogueBuildStore.STAGING_REVISION_KEY,""),
        savedAt,android.os.SystemClock.elapsedRealtime())){
      return candidate;
    }
    return com.wodedianshi.tv.db.RoomCatalogueBuildStore.read(getApplicationContext(),pauseRequested);
  }
  private com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot readPublishedForInitialCommit(){
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot=
        com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),null,pauseRequested);
    if(snapshot==null)throw new IllegalStateException("Published catalogue not ready for merge");
    return snapshot;
  }
  private void enqueueInitialContinuation() throws Exception {
    enqueueInitialContinuation(slowPlayback?20L:0L);
  }
  private void enqueueInitialContinuation(long delaySeconds) throws Exception {
    if(isStopped()||Thread.currentThread().isInterrupted())throw new InterruptedException("Initial source work cancelled");
    androidx.work.OneTimeWorkRequest continuation=
        new androidx.work.OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
            .setInitialDelay(delaySeconds,java.util.concurrent.TimeUnit.SECONDS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,30L,java.util.concurrent.TimeUnit.SECONDS)
            .setConstraints(new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
                .putBoolean("initial_discovery_chain",true).build())
            .build();
    androidx.work.WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
        "mytv_startup_source_refresh",androidx.work.ExistingWorkPolicy.APPEND,continuation)
        .getResult().get();
  }
  private boolean areAllDiscoveryFeedsComplete(SharedPreferences prefs,
          List<FeedBatch> batches,int feedCount){
    for(int i=0;i<feedCount;i++){
      boolean done=roomBoolean(initialFeedDoneKey(i),false);
      if(!done){
        for(FeedBatch batch:batches){
          if(batch.index==i&&batch.success
              &&SourceUpdateSchedulePolicy.manualFeedFinished(batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED)){
            done=true;break;
          }
        }
      }
      if(!done)return false;
    }
    return true;
  }
  private String discoveryFeedsFingerprint(String[] feeds){
    StringBuilder value=new StringBuilder();
    // A prior pending full build may contain dead routes from the old
    // "stage even if probe failed" behavior. Restart only that isolated build
    // after the acceptance policy changes; never discard published Room rows.
    appendFingerprintPart(value,SourceDiscoveryAcceptancePolicy.BUILD_POLICY_VERSION);
    if(feeds!=null)for(String feed:feeds)appendFingerprintPart(value,feed);
    return CatalogueFingerprint.sha256(value.toString());
  }
  private String discoveryFingerprint(List<Entry> entries){
    StringBuilder value=new StringBuilder(entries.size()*96);
    for(Entry entry:entries){
      appendFingerprintPart(value,SourceSelectionPolicy.endpointKey(entry.url));
      appendFingerprintPart(value,entry.name);
      appendFingerprintPart(value,entry.group);
      appendFingerprintPart(value,entry.tvgId);
      appendFingerprintPart(value,entry.tvgName);
      appendFingerprintPart(value,entry.scope);
    }
    return CatalogueFingerprint.sha256(value.toString())+":"+entries.size();
  }
  private void appendFingerprintPart(StringBuilder target,String raw){
    String value=raw==null?"":raw;
    target.append(value.length()).append(':').append(value);
  }
  private JSONArray roomSnapshotToArray(com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot)
      throws JSONException {
    JSONArray result=new JSONArray();
    for(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row:snapshot.channels){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      JSONObject channel=new JSONObject();
      channel.put("name",row.name);channel.put("url",row.url);channel.put("group",row.group);
      channel.put("tvgId",row.tvgId);channel.put("tvgName",row.tvgName);
      channel.put("activeSource",row.activeSource);
      JSONArray sources=new JSONArray();
      for(String source:row.sources)sources.put(source);
      channel.put("sources",sources);
      JSONArray channelScopes=new JSONArray();
      for(String scope:row.sourceScopes)channelScopes.put(scope);
      channel.put("sourceScopes",channelScopes);
      JSONArray ownership=new JSONArray();
      for(java.util.Map.Entry<String,java.util.HashSet<String>> entry:row.backupSourceScopes.entrySet()){
        JSONObject item=new JSONObject();item.put("url",entry.getKey());
        JSONArray scopes=new JSONArray();
        for(String scope:entry.getValue())scopes.put(scope);
        item.put("scopes",scopes);ownership.put(item);
      }
      channel.put("backupSourceScopes",ownership);
      result.put(channel);
    }
    return result;
  }

  private boolean shouldYieldToViewing(SharedPreferences prefs, boolean manual,
      boolean initialDiscoveryChain, boolean hasPublishedChannels){
    return (slowPlayback?!SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime()):SourceUpdateActivityPolicy.isPlaybackActive())
        || SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
            isActivityActive(prefs),manual,initialDiscoveryChain,hasPublishedChannels);
  }

  private Result deferInitialDirectory(String reason){
    com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_initial_progress_phase",reason);
    return continueAfterViewingDelay(true);
  }
  private Result continueAfterViewingDelay(boolean waitingForIdle){
    // Never append work after cancellation. If the enqueue itself fails, retain
    // the current durable WorkManager request via its normal retry mechanism.
    if(isStopped()||Thread.currentThread().isInterrupted())return Result.retry();
    try{
      enqueueInitialContinuation(SourceUpdateSchedulePolicy.initialViewingDelaySeconds(waitingForIdle));
      return Result.success();
    }catch(Exception failure){
      android.util.Log.w("MyTVSourceDiscovery","Unable to schedule deferred initial slice",failure);
      return Result.retry();
    }
  }
  private Result deferredViewingResult(boolean manual,boolean initialDiscoveryChain){
    if(initialDiscoveryChain){
      // The existing counters and cursors stay unchanged while viewing.
      com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),
          "source_initial_progress_phase","播放或浏览优先：抓源已暂停，进度已保存");
      return continueAfterViewingDelay(false);
    }
    // Do not repeatedly wake the periodic job every few minutes while a user
    // watches television; the next daily check retains the overdue weekly intent.
    return SourceUpdateSchedulePolicy.shouldRetryWhenBusy(manual,initialDiscoveryChain)
        ?Result.retry():Result.success();
  }

  private boolean isActivityActive(SharedPreferences prefs){
    return SourceUpdateActivityPolicy.isActivityActive();
  }
  private static class Entry{
    String name,url,group,scope,tvgId,tvgName;boolean savedSource;
    // Unscoped saved URLs have unknown provenance; only discovered feeds carry an explicit scope.
    Entry(String n,String u,String g){this(n,u,g,false,"","","");}
    Entry(String n,String u,String g,boolean saved){this(n,u,g,saved,"","","");}
    Entry(String n,String u,String g,boolean saved,String sourceScope){
      this(n,u,g,saved,sourceScope,"","");
    }
    Entry(String n,String u,String g,boolean saved,String sourceScope,String id,String guideName){
      name=n;url=u;group=g;savedSource=saved;scope=sourceScope;
      tvgId=id==null?"":id;tvgName=guideName==null?"":guideName;
    }
  }
  private static class FeedBatch{
    final int index,cursor,parsed,indexedEntries;
    final boolean success,unavailable;
    final String version;
    FeedBatch(int i,int c,int p,int total,boolean ok,boolean unavailable,String version){
      index=i;cursor=c;parsed=p;indexedEntries=total;
      success=ok;this.unavailable=unavailable;this.version=version;
    }
  }
  /**
   * Retain existing published channels, backup URLs, selected source and country/
   * city membership at the first full-rebuild publication boundary. Staging
   * remains discovery-only until this point, so interrupted builds cannot change
   * live Room rows. The writer checks its original Room catalogue version.
   */
  private JSONArray preservePreviouslyPublishedChannels(JSONArray discovered,
      com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot prior)throws JSONException {
    if(prior==null||prior.channels==null||prior.channels.isEmpty())return discovered;
    JSONArray original=roomSnapshotToArray(prior);
    LinkedHashMap<String,ArrayDeque<JSONObject>> originalsByIdentity=new LinkedHashMap<>();
    ArrayList<JSONObject> originalInOrder=new ArrayList<>();
    for(int i=0;i<original.length();i++){
      JSONObject old=original.getJSONObject(i);
      String key=identity(old.optString("name"),old.optString("group"),
          old.optString("url"),old.optString("tvgId"),old.optString("tvgName"));
      originalsByIdentity.computeIfAbsent(key,k->new ArrayDeque<>()).add(old);
      originalInOrder.add(old);
    }
    Set<JSONObject> matched=Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());
    JSONArray publish=new JSONArray();
    for(int i=0;i<discovered.length();i++){
      JSONObject fresh=discovered.getJSONObject(i);
      String key=identity(fresh.optString("name"),fresh.optString("group"),
          fresh.optString("url"),fresh.optString("tvgId"),fresh.optString("tvgName"));
      ArrayDeque<JSONObject> matches=originalsByIdentity.get(key);
      if(matches==null||matches.isEmpty()){
        publish.put(fresh);
        continue;
      }
      JSONObject old=matches.removeFirst();
      matched.add(old);
      publish.put(mergeNewRoutesIntoSavedChannel(old,fresh));
    }
    // Keep even a station missing entirely from every new public directory.
    // Its old sources may still work despite a failed discovery feed.
    for(JSONObject old:originalInOrder)if(!matched.contains(old))publish.put(old);
    android.util.Log.i("MyTVSourceDiscovery",
        "first_full_publish_discovered="+discovered.length()+
        " preserved_old="+original.length()+" final="+publish.length());
    return publish;
  }

  private ArrayList<String> channelSourceUrls(JSONObject row){
    ArrayList<String> urls=new ArrayList<>();
    JSONArray sources=row.optJSONArray("sources");
    if(sources!=null)for(int i=0;i<sources.length();i++){
      String url=sources.optString(i,"");
      if(!url.isEmpty())urls.add(url);
    }
    if(urls.isEmpty()&&!row.optString("url","").isEmpty())
      urls.add(row.optString("url",""));
    return urls;
  }

  private JSONObject mergeNewRoutesIntoSavedChannel(JSONObject old,JSONObject fresh)
      throws JSONException {
    ArrayList<String> oldUrls=channelSourceUrls(old);
    ArrayList<String> newUrls=channelSourceUrls(fresh);
    FullRebuildRouteRetentionPolicy.Result retained=FullRebuildRouteRetentionPolicy.merge(
        oldUrls,old.optInt("activeSource",0),old.optString("url",""),
        newUrls,MAX_SOURCES_PER_CHANNEL);
    JSONArray kept=new JSONArray();
    HashMap<String,String> keptByEndpoint=new HashMap<>();
    for(String url:retained.urls){
      kept.put(url);
      keptByEndpoint.put(SourceSelectionPolicy.endpointKey(url),url);
    }
    // Some legacy rows have channel-level geo tags but no per-source provenance.
    // Attach them to their old URLs before recalculating the ownership union.
    JSONArray oldOwnership=old.optJSONArray("backupSourceScopes");
    if(oldOwnership==null||oldOwnership.length()==0){
      JSONArray oldScopes=old.optJSONArray("sourceScopes");
      if(oldScopes!=null)for(String url:oldUrls){
        String keptUrl=keptByEndpoint.get(SourceSelectionPolicy.endpointKey(url));
        if(keptUrl==null)continue;
        for(int j=0;j<oldScopes.length();j++)
          addBackupSourceScope(old,keptUrl,oldScopes.optString(j,""));
      }
    }
    JSONArray freshOwnership=fresh.optJSONArray("backupSourceScopes");
    if(freshOwnership!=null&&freshOwnership.length()>0){
      for(int i=0;i<freshOwnership.length();i++){
        JSONObject ownership=freshOwnership.optJSONObject(i);
        if(ownership==null)continue;
        String retainedUrl=keptByEndpoint.get(SourceSelectionPolicy.endpointKey(
            ownership.optString("url","")));
        if(retainedUrl==null)continue;
        JSONArray scopes=ownership.optJSONArray("scopes");
        if(scopes!=null)for(int j=0;j<scopes.length();j++)
          addBackupSourceScope(old,retainedUrl,scopes.optString(j,""));
      }
    }else{
      // Older sources may carry only channel-level tags.
      JSONArray scopes=fresh.optJSONArray("sourceScopes");
      if(scopes!=null)for(String newUrl:newUrls){
        String retainedUrl=keptByEndpoint.get(SourceSelectionPolicy.endpointKey(newUrl));
        if(retainedUrl==null)continue;
        for(int j=0;j<scopes.length();j++)
          addBackupSourceScope(old,retainedUrl,scopes.optString(j,""));
      }
    }
    // Keep the exact saved name, TVG, group and stable primary identity.
    // Enriching them here could change the Room channel ID and lose favorites.
    old.put("sources",kept);
    old.put("activeSource",retained.activeIndex);
    retainBackupSourceScopes(old,kept);
    return old;
  }

  private String scopeForFeed(String feed){
    return SourceFeedScopePolicy.scopeForFeed(feed);
  }
  private String feedCursorKey(int index){return "source_feed_cursor_"+index;}
  private String routineFeedCursorKey(int index){return "source_routine_feed_cursor_"+index;}
  private String manualFeedCursorKey(int index){return "source_manual_feed_cursor_"+index;}
  private String initialAcquiredVersionKey(int index){return "source_initial_acquired_version_"+index;}
  private void commitInitialAcquisition(int index,File cached) throws IOException {
    String version=PlaylistFeedCache.contentVersion(cached);
    if(PlaylistFeedIndex.indexedEntryCount(cached)<0)throw new IOException("Prepared directory index unavailable");
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
        .putString(initialAcquiredVersionKey(index),version)
        .putInt("source_initial_next_feed_index",index)
        .putString("source_initial_progress_phase","目录已准备，下一批继续线路初筛")
        .apply();
  }
  private String initialFeedDoneKey(int index){return "source_initial_feed_done_"+index;}
  private String initialFeedProgressKey(int index){return "source_initial_feed_progress_bp_"+index;}
  private String initialFeedUnavailableKey(int index){return "source_initial_feed_unavailable_"+index;}
  private String initialFeedFailureCountKey(int index){return "source_initial_feed_failure_count_"+index;}
  private String initialFeedFailureReasonKey(int index){return "source_initial_feed_failure_reason_"+index;}
  private String initialFeedDisplayLabelKey(int index){return "source_initial_feed_label_"+index;}

  private static String safeFeedLabel(String url){
    try{
      java.net.URL parsed=new java.net.URL(url);
      String path=parsed.getPath(),name=path.substring(path.lastIndexOf('/')+1);
      return parsed.getHost()+"/"+(name.isEmpty()?"目录":name);
    }catch(Exception ignored){return "公开来源目录";}
  }
  private String canonicalDisplayName(Entry entry){
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(
        entry.name,entry.tvgName,entry.tvgId,entry.group);
    if(!broadcaster.isEmpty())return broadcaster;
    String canonical=canonicalName(entry.name);
    return canonical.startsWith("CCTV-")||canonical.endsWith("卫视")?canonical:entry.name;
  }
  private void addBackupSourceScope(JSONObject channel,String url,String scope)throws JSONException{
    if(channel==null||url==null||url.isEmpty())return;
    // Use the same strict scope allowlist as the Room reader. Otherwise a
    // background refresh can silently erase country/state/city ownership.
    if(!ChannelCatalogueScopePolicy.isRetainableScope(scope))return;
    JSONArray channelScopes=channel.optJSONArray("sourceScopes");
    if(channelScopes==null){channelScopes=new JSONArray();channel.put("sourceScopes",channelScopes);}
    boolean channelHasScope=false;
    for(int i=0;i<channelScopes.length();i++)if(scope.equals(channelScopes.optString(i))){channelHasScope=true;break;}
    if(!channelHasScope)channelScopes.put(scope);
    JSONArray ownership=channel.optJSONArray("backupSourceScopes");
    if(ownership==null){ownership=new JSONArray();channel.put("backupSourceScopes",ownership);}
    for(int i=0;i<ownership.length();i++){
      JSONObject item=ownership.optJSONObject(i);
      if(item==null||!url.equals(item.optString("url","")))continue;
      JSONArray scopes=item.optJSONArray("scopes");
      if(scopes==null){scopes=new JSONArray();item.put("scopes",scopes);}
      for(int j=0;j<scopes.length();j++)if(scope.equals(scopes.optString(j)))return;
      scopes.put(scope);return;
    }
    JSONObject item=new JSONObject();JSONArray scopes=new JSONArray();
    scopes.put(scope);item.put("url",url);item.put("scopes",scopes);ownership.put(item);
  }
  private void retainBackupSourceScopes(JSONObject channel,JSONArray retainedSources)throws JSONException{
    JSONArray previous=channel.optJSONArray("backupSourceScopes");
    if(previous==null)return;
    HashSet<String> retainedUrls=new HashSet<>();
    for(int i=0;i<retainedSources.length();i++)retainedUrls.add(retainedSources.optString(i,""));
    JSONArray filtered=new JSONArray();
    HashMap<String,Set<String>> ownershipByUrl=new HashMap<>();
    for(int i=0;i<previous.length();i++){
      JSONObject item=previous.optJSONObject(i);
      if(item==null)continue;
      String url=item.optString("url","");
      if(!retainedUrls.contains(url))continue;
      filtered.put(item);
      JSONArray scopes=item.optJSONArray("scopes");
      HashSet<String> owned=new HashSet<>();
      if(scopes!=null)for(int j=0;j<scopes.length();j++)owned.add(scopes.optString(j,""));
      ownershipByUrl.put(url,owned);
    }
    channel.put("backupSourceScopes",filtered);
    // Channel-level tags are a derived union of the URLs that survived the
    // eight-source retention cap. Do not leave stale China/global membership.
    java.util.TreeSet<String> sortedScopes=new java.util.TreeSet<>(
        ChannelCatalogueScopePolicy.retainedScopes(ownershipByUrl,retainedUrls));
    JSONArray channelScopes=new JSONArray();
    for(String scope:sortedScopes)channelScopes.put(scope);
    channel.put("sourceScopes",channelScopes);
  }
  private int sourceQualityScore(String address){
    return SourceQualityScorer.labelScore(address);
  }
  private static class ProbeResult {
    boolean valid;
    int score;
    int width,height;
    long latencyMs;
    long bitrate;
  }
  // Keep the persisted preflight-score index bounded. Discovery feeds can rotate
  // thousands of URLs over time; an unbounded JSON object would slow every update
  // and gradually consume device storage. The current scan rewrites recent entries.
  private static final int MAX_PERSISTED_PROBE_SCORES = 8000;
  private void trimProbeScores(JSONObject scores,List<Entry> savedPool,List<Entry> recentCandidates){
    if(scores==null||scores.length()<=MAX_PERSISTED_PROBE_SCORES)return;
    // JSONObject key iteration order is not an LRU contract. Preserve scores for
    // currently saved channel URLs first, then this run's recent candidates, before
    // filling remaining capacity with older entries.
    LinkedHashSet<String> keep=new LinkedHashSet<>();
    if(savedPool!=null)for(Entry entry:savedPool){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      if(keep.size()>=MAX_PERSISTED_PROBE_SCORES)break;
      String key=scoreKey(entry.url);if(scores.has(key))keep.add(key);
    }
    if(recentCandidates!=null)for(int i=recentCandidates.size()-1;i>=0&&keep.size()<MAX_PERSISTED_PROBE_SCORES;i--){
      String key=scoreKey(recentCandidates.get(i).url);if(scores.has(key))keep.add(key);
    }
    Iterator<String> keys=scores.keys();
    while(keys.hasNext()&&keep.size()<MAX_PERSISTED_PROBE_SCORES)keep.add(keys.next());
    JSONObject retained=new JSONObject();
    for(String key:keep)try{retained.put(key,scores.get(key));}catch(JSONException ignored){}
    Iterator<String> remove=scores.keys();ArrayList<String> oldKeys=new ArrayList<>();
    while(remove.hasNext())oldKeys.add(remove.next());
    for(String key:oldKeys)scores.remove(key);
    Iterator<String> retainedKeys=retained.keys();
    while(retainedKeys.hasNext()){
      String key=retainedKeys.next();
      try{scores.put(key,retained.get(key));}catch(JSONException ignored){}
    }
  }
  private String scoreKey(String url){return StableKey.url(url);}
  private String legacyScoreKey(String url){return StableKey.legacyUrl(url);}
  private double combinedScore(String url,JSONObject probes,JSONObject qoe){
    String key=scoreKey(url),legacyKey=legacyScoreKey(url);
    double probeScore=probes.has(key)?probes.optDouble(key,0.0):probes.optDouble(legacyKey,0.0);
    // URL resolution labels contribute at most 12 points; media preflight and
    // observed playback QoE remain dominant, so a fake "1080p" label cannot
    // outrank a materially healthier stream by itself.
    double score=probeScore+sourceQualityScore(url)*0.01;
    // Keep a currently failed preflight below unprobed/valid sources even if its old QoE was excellent.
    if(SourceQualityScorer.isFailedProbeScore(probeScore))return score;
    JSONObject m=qoe.optJSONObject(key);
    if(m==null)m=qoe.optJSONObject(legacyKey);
    if(m!=null&&m.optInt("attempts",0)>=2){
      int attempts=Math.max(1,m.optInt("attempts",0)),ready=m.optInt("ready",0),fail=m.optInt("failures",0),buffers=m.optInt("buffers",0);
      int codecWarnings=m.optInt("audioCodecErrors",0)+m.optInt("audioSinkErrors",0)+m.optInt("videoCodecErrors",0);
      // Use exactly the same attempt-normalized QoE policy as live playback ranking.
      double qoeAdjustment=SourceQoeScoringPolicy.adjustment(
          attempts,ready,m.optInt("firstFrames",0),m.optInt("stablePlays",0),
          fail,buffers,m.optLong("avgReadyMs",0L),m.optLong("avgFirstFrameMs",0L),
          codecWarnings);
      long now=System.currentTimeMillis();
      score+=qoeAdjustment*SourceQoeScoringPolicy.historyWeight(m.optLong("updated",0L),now);
      score+=SourceQoeScoringPolicy.recentSuccessBonus(
          m.optLong("lastFirstFrame",0L),m.optLong("lastStable",0L),now);
    }
    return score;
  }
  // Sniff the response body as well as the URL: some IPTV gateways expose
  // HLS manifests through extensionless or tokenized endpoint URLs.
  private ProbeResult probe(String address,long deadline){
    ProbeResult result=new ProbeResult();
    long started=android.os.SystemClock.elapsedRealtime();
    try{
      if(probeDeadlineReached(deadline))return result;
      SampleResponse initialResponse=fetchSampleResponse(address,32768,deadline);
      byte[] initialBytes=initialResponse==null?null:initialResponse.bytes;
      if(initialBytes==null||initialBytes.length<7)return result;
      String initialText=new String(initialBytes,"UTF-8").replace("\\uFEFF","");
      String lower=address.toLowerCase(Locale.ROOT);
      boolean playlistUrl=lower.contains(".m3u8")||lower.contains("m3u8?");
      boolean hlsPlaylist=HlsVariantPolicy.isPlaylist(initialText);
      if(playlistUrl||hlsPlaylist){
        if(!hlsPlaylist)return result;
        SampleResponse manifestResponse=initialResponse;
        String manifest=initialText;
        String manifestUrl=manifestResponse.finalUrl;
        ArrayList<HlsVariantPolicy.Variant> variants=HlsVariantPolicy.parse(manifest,manifestUrl);
        if(variants.isEmpty())variants.add(new HlsVariantPolicy.Variant(manifestUrl,0L,0,0));
        String mediaUrl=manifestUrl;
        long bestBandwidth=0L;
        int bestWidth=0,bestHeight=0;
        boolean selectedAes128=false;
        HlsMediaPlaylistPolicy.Resources selectedResources=null;
        byte[] selectedAesKey=null;
        ArrayList<String> segments=null;
        byte[] sample=null;
        long segmentStart=0L;
        for(HlsVariantPolicy.Variant candidate:variants){
          if(probeDeadlineReached(deadline))return result;
          String candidateManifestUrl=candidate.url;
          String candidateManifest=manifest;
          if(!candidate.url.equals(manifestUrl)){
            SampleResponse variantResponse=fetchSampleResponse(candidate.url,65536,deadline);
            byte[] variant=variantResponse==null?null:variantResponse.bytes;
            if(variant==null||variant.length==0)continue;
            candidateManifest=new String(variant,"UTF-8").replace("\\uFEFF","");
            if(!HlsVariantPolicy.isPlaylist(candidateManifest))continue;
            candidateManifestUrl=variantResponse.finalUrl;
          }
          HlsMediaPlaylistPolicy.Resources resources=
              HlsMediaPlaylistPolicy.parse(candidateManifest,candidateManifestUrl);
          ArrayList<String> candidateSegments=resources.mediaUrls;
          if(candidateSegments.isEmpty()||resources.unsupportedInitializationByteRange||resources.unsupportedInitializationMap||resources.unsupportedEncryption)continue;
          // Fetch identity AES-128 key only for in-memory preflight; never log or persist key bytes.
          byte[] aesKey=null;
          if(resources.aes128Encrypted){
            aesKey=fetchSample(resources.encryptionKeyUrl,32,deadline);
            if(aesKey==null||aesKey.length!=16)continue;
          }
          // fMP4/CMAF playlists require the EXT-X-MAP initialization resource.
          // AES-128 maps require their explicit IV and must validate after decryption.
          if(resources.initializationUrl!=null){
            SampleResponse initializationResponse=fetchSampleResponse(resources.initializationUrl,32768,deadline);
            byte[] initialization=initializationResponse==null?null:initializationResponse.bytes;
            if(initialization==null)continue;
            if(resources.aes128Encrypted){
              byte[] mapIv=HlsAes128Preflight.parseExplicitIv(resources.encryptionIv);
              if(mapIv==null||!HlsAes128Preflight.validatesInitializationSegment(
                  initialization,aesKey,mapIv,HlsProbeValidationPolicy.isCompleteSample(initializationResponse.truncated)))continue;
            }else if(!MediaPayloadValidator.hasIsoBmffInitializationSegment(initialization))continue;
          }
          String candidateSegmentUrl=candidateSegments.get(0);
          long candidateSegmentStart=android.os.SystemClock.elapsedRealtime();
          SampleResponse candidateSampleResponse=fetchSampleResponse(candidateSegmentUrl,32768,deadline);
          byte[] candidateSample=candidateSampleResponse==null?null:candidateSampleResponse.bytes;
          if(candidateSample==null)continue;
          if(resources.aes128Encrypted){
            byte[] segmentIv=resources.encryptionIv!=null
                ?HlsAes128Preflight.parseExplicitIv(resources.encryptionIv)
                :HlsAes128Preflight.ivForMediaSequence(resources.firstMediaSequenceNumber);
            if(!HlsAes128Preflight.validatesMediaSegment(
                candidateSample,aesKey,segmentIv,HlsProbeValidationPolicy.isCompleteSample(candidateSampleResponse.truncated)))continue;
          }else if(!looksLikeHlsSegment(candidateSample))continue;
          mediaUrl=candidateManifestUrl;
          bestBandwidth=candidate.bandwidth;
          bestWidth=candidate.width;
          bestHeight=candidate.height;
          segments=candidateSegments;
          selectedAes128=resources.aes128Encrypted;
          selectedResources=resources;
          selectedAesKey=aesKey;
          sample=candidateSample;
          segmentStart=candidateSegmentStart;
          break;
        }
        if(segments==null||sample==null)return result;
        long segmentMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-segmentStart);
        boolean secondSegmentOk=true;
        if(segments.size()>1){
          String secondUrl=new URL(new URL(mediaUrl),segments.get(1)).toString();
          SampleResponse secondResponse=fetchSampleResponse(secondUrl,8192,deadline);
          byte[] second=secondResponse==null?null:secondResponse.bytes;
          if(selectedAes128&&second!=null){
            byte[] secondIv=selectedResources.encryptionIv!=null
                ?HlsAes128Preflight.parseExplicitIv(selectedResources.encryptionIv)
                :HlsAes128Preflight.ivForMediaSequence(selectedResources.firstMediaSequenceNumber+1L);
            secondSegmentOk=HlsAes128Preflight.validatesMediaSegment(
                second,selectedAesKey,secondIv,HlsProbeValidationPolicy.isCompleteSample(secondResponse.truncated));
          }else secondSegmentOk=second!=null&&looksLikeHlsSegment(second);
        }
        // A playlist exposing a second segment must prove that it is retrievable
        // and structurally valid too. A valid first segment alone can be a stale
        // edge/cache fragment and must not promote a dead live endpoint.
        if(segments.size()>1&&!HlsProbeValidationPolicy.acceptsSecondSegment(secondSegmentOk))
          return result;
        result.valid=true;result.width=bestWidth;result.height=bestHeight;
        result.bitrate=bestBandwidth;result.latencyMs=Math.max(1L,segmentStart-started);
        int resolution=(bestWidth>=1920||bestHeight>=1080)?900:(bestWidth>=1280||bestHeight>=720)?700:(bestWidth>=854||bestHeight>=480)?500:300;
        int bandwidth=(int)Math.min(500L,bestBandwidth/12000L);
        int speed=(int)Math.min(300L,(sample.length*1000L/segmentMs)/2048L);
        int latencyPenalty=(int)Math.min(250L,result.latencyMs/20L);
        result.score=500+resolution+bandwidth+speed-latencyPenalty+300+(secondSegmentOk?120:(segments.size()>1?-180:0));
        return result;
      }
      result.latencyMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-started);
      URL url=new URL(address);
      String ext=url.getPath().toLowerCase(Locale.ROOT);
      if(!MediaPayloadValidator.looksLikeMediaPayload(initialBytes,ext))return result;
      result.valid=true;
      result.score=350+(int)Math.min(300L,(initialBytes.length*1000L/result.latencyMs)/2048L);
      return result;
    }catch(Exception e){return result;}
  }
  // HLS media may be MPEG-TS or fragmented MP4 (CMAF/fMP4).
  // Do not reject valid fMP4 segments merely because they are shorter than 188 bytes.
  private boolean looksLikeHlsSegment(byte[] b){
    return MediaPayloadValidator.looksLikeHlsSegment(b);
  }
  private boolean hasIsoBmffBox(byte[] b){
    return MediaPayloadValidator.hasIsoBmffBox(b);
  }
  private boolean looksLikeMediaPayload(byte[] b){
    return MediaPayloadValidator.looksLikeMediaPayload(b);
  }
  private static class SampleResponse{
    final byte[] bytes;
    final String finalUrl;
    // True only when the bounded read observed at least one byte beyond its cap.
    // Reaching the cap alone does not prove that the HTTP resource ended there.
    final boolean truncated;
    SampleResponse(byte[] data,String url,boolean wasTruncated){bytes=data;finalUrl=url;truncated=wasTruncated;}
  }
  private boolean probeDeadlineReached(long deadline){
    return pauseRequested.getAsBoolean()||Thread.currentThread().isInterrupted()
        ||android.os.SystemClock.elapsedRealtime()>=deadline;
  }
  private byte[] fetchSample(String address,int maxBytes,long deadline)throws Exception{
    SampleResponse response=fetchSampleResponse(address,maxBytes,deadline);
    return response==null?null:response.bytes;
  }
  private SampleResponse fetchSampleResponse(String address,int maxBytes,long deadline)throws Exception{
    HttpURLConnection c=null;
    try{
      if(probeDeadlineReached(deadline))return null;
      c=(HttpURLConnection)new URL(address).openConnection();
      // URLConnection options must be configured before the first operation that connects it.
      // Reserve half the remaining budget for connection setup and the rest for response reads.
      long now=android.os.SystemClock.elapsedRealtime();
      long remaining=deadline-now;
      if(remaining<2L)return null;
      int connectTimeout=(int)Math.min(2500L,Math.max(1L,remaining/2L));
      int readTimeout=SourceProbeTimePolicy.boundedTimeoutMillis(
          deadline,now+connectTimeout,3000);
      if(readTimeout<=0)return null;
      c.setConnectTimeout(connectTimeout);
      c.setReadTimeout(readTimeout);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36 MyTV/1.24");
      // Read one sentinel byte beyond the retained sample to distinguish an
      // exact-length resource from a longer segment truncated at the sample cap.
      c.setRequestProperty("Range","bytes=0-"+maxBytes);
      int code=c.getResponseCode();if(probeDeadlineReached(deadline)||code<200||code>=400)return null;
      boolean rangeIncomplete=code==HttpURLConnection.HTTP_PARTIAL
          &&HlsProbeValidationPolicy.isIncompleteContentRange(c.getHeaderField("Content-Range"),maxBytes);
      String type=c.getContentType();
      if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))return null;
      String finalUrl=c.getURL().toString();
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[4096];int n,total=0;
        while(total<=maxBytes){
          if(probeDeadlineReached(deadline))return null;
          n=in.read(buffer);
          if(n==-1)break;
          int take=Math.min(n,maxBytes+1-total);out.write(buffer,0,take);total+=take;
          if(total>maxBytes)break;
        }
        if(probeDeadlineReached(deadline))return null;
        byte[] received=out.toByteArray();
        boolean truncated=rangeIncomplete
            ||HlsProbeValidationPolicy.isTruncatedSample(received.length,maxBytes);
        byte[] bounded=truncated?Arrays.copyOf(received,maxBytes):received;
        return new SampleResponse(bounded,finalUrl,truncated);
      }
    }finally{if(c!=null)c.disconnect();}
  }
  private String read(InputStream in,int max)throws Exception{
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;
    while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("频道目录过大");out.write(b,0,n);}return out.toString("UTF-8");
  }
  private String canonicalName(String name){
    String v=name==null?"":name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_—–·•]","");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)-?(\\d{1,2}\\+?|NEWS|4K|8K)").matcher(v);
    if(m.find())return "CCTV-"+m.group(1);
    String[] cn={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
    for(int i=1;i<cn.length;i++)if(v.matches(".*中央(?:电视台)?"+cn[i]+"套.*"))return "CCTV-"+i;
    if(v.contains("中央电视台综合频道"))return "CCTV-1";
    if(v.contains("中央电视台财经频道"))return "CCTV-2";
    if(v.contains("中央电视台综艺频道"))return "CCTV-3";
    if(v.contains("中央电视台中文国际频道"))return "CCTV-4";
    if(v.contains("中央电视台体育频道"))return "CCTV-5";
    if(v.contains("中央电视台电影频道"))return "CCTV-6";
    v=v.replace("超高清","").replace("高清","").replace("超清","").replace("1080P","").replace("720P","").replace("HD","").replace("FHD","").replace("直播","");
    if(v.endsWith("湖南台"))return "湖南卫视";
    if(v.endsWith("浙江台"))return "浙江卫视";
    if(v.endsWith("江苏台"))return "江苏卫视";
    if(v.endsWith("东方台"))return "东方卫视";
    if(v.endsWith("北京台"))return "北京卫视";
    if(v.endsWith("广东台"))return "广东卫视";
    return v;
  }
  private String identity(String name,String group,String url,String tvgId,String tvgName){
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
    String v=broadcaster.isEmpty()?canonicalName(name):broadcaster;
    String base=v.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
    String fallback=base;
    try{
      java.net.URI u=java.net.URI.create(url==null?"":url);
      String host=u.getHost();
      if(host!=null&&!host.isEmpty())fallback=base+"|host:"+host.toLowerCase(Locale.ROOT);
    }catch(Exception ignored){fallback=base+"|url:"+(url==null?"":url.trim());}
    String localIdentity=ChannelIdentityPolicy.localStationIdentity(name,tvgName);
    return ChannelIdentityPolicy.identity(v,tvgId,fallback,localIdentity);
  }
  private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
  private String roomString(String key,String fallback){
    return com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),key,fallback);
  }
  private int roomInt(String key,int fallback){
    try{return Integer.parseInt(roomString(key,Integer.toString(fallback)));}
    catch(Exception ignored){return fallback;}
  }
  private boolean roomBoolean(String key,boolean fallback){
    String value=roomString(key,Boolean.toString(fallback));
    return Boolean.parseBoolean(value);
  }
  private void saveStatus(SharedPreferences p,String result,int added){
    com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_update_time",now());
    com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_update_result",result);
  }
}
