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
  // A cancelled HTTP call may not honor interruption immediately. No later
  // source owner starts while the previous scoped probe executor is draining.
  private static volatile java.util.concurrent.ExecutorService retiringProbeExecutor;
  public static String currentInitialLiveStage(){return initialLiveStage;}
  static boolean isRetiringProbePoolActive(){
    java.util.concurrent.ExecutorService retiring=retiringProbeExecutor;
    return retiring!=null&&!retiring.isTerminated();
  }
  static boolean isSourceWorkActive(){
    return SOURCE_WORK_ACTIVE.get()||isRetiringProbePoolActive();
  }
  private void noteInitialStage(String stage){
    if(getInputData().getBoolean("initial_discovery_chain",false))initialLiveStage=stage;
  }
  // Single source-work owner accesses this bounded process-local cache.
  // Do not reuse old persisted quality scores as evidence of a new preflight.
  private static final LinkedHashMap<String,RecentProbe> INITIAL_PROBES=
      new LinkedHashMap<>(64,0.75f,true);
  private static final int INITIAL_PROBE_CACHE_CAP=1024;
  private static long initialProbeEarliestExpiry=Long.MAX_VALUE;
  private static String manualProbeCacheOwner="";
  private static void clearRecentProbeCache(){
    INITIAL_PROBES.clear();initialProbeEarliestExpiry=Long.MAX_VALUE;manualProbeCacheOwner="";
  }
  private static final InitialOriginCooldownPolicy INITIAL_ORIGINS=new InitialOriginCooldownPolicy();
  private static final InitialNetworkConcurrencyPolicy INITIAL_NETWORK=new InitialNetworkConcurrencyPolicy();
  private static final class RecentProbe {
    final ProbeResult result; final long at;
    RecentProbe(ProbeResult result,long at){this.result=result;this.at=at;}
  }
  // All three owner-confined admission paths use exactly the same cache rule.
  private RecentProbe reusableInitialProbe(String endpoint,boolean viewing,long now){
    RecentProbe cached=INITIAL_PROBES.get(endpoint);
    return cached!=null&&(!cached.result.provisional||allowProvisionalScreen)
        &&SourceUpdateSchedulePolicy.reusableInitialObservation(
        cached.result.valid,cached.result.provisional,cached.result.paced,viewing,cached.at,now)
        ?cached:null;
  }
  private static void rememberInitialProbe(String key,ProbeResult result,long at){
    INITIAL_PROBES.put(key,new RecentProbe(result,at));
    final long now=android.os.SystemClock.elapsedRealtime();
    initialProbeEarliestExpiry=Math.min(initialProbeEarliestExpiry,
        SourceUpdateSchedulePolicy.initialProbeExpiry(result.valid,result.provisional,at));
    initialProbeEarliestExpiry=SourceUpdateSchedulePolicy.trimInitialProbeCacheByExpiry(
        INITIAL_PROBES,INITIAL_PROBE_CACHE_CAP,initialProbeEarliestExpiry,now,
        entry->SourceUpdateSchedulePolicy.initialProbeExpiry(entry.result.valid,entry.result.provisional,entry.at));
  }
  private static void retainCompletedInitialSuccess(String key,ProbeResult result,
      long finishedAt,long addressDeadline,long batchDeadline){
    if(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(result.valid,
        result.inconclusive(),finishedAt,addressDeadline,batchDeadline))
      rememberInitialProbe(key,result,finishedAt);
  }
  private static final HashMap<Integer,Long> LIVE_DIRECTORY_RETRY_AFTER=new HashMap<>();
  // Reuse only committed staging owned by the sole source worker. A borrowed
  // mutable snapshot is removed from the cache BEFORE any mutation.
  private static JSONArray committedStaging;
  private static String committedStagingRevision="";
  private static long committedStagingAt;
  private boolean slowPlayback;
  private boolean allowProvisionalScreen;
  private boolean inlineInitialRequested;
  private final java.util.concurrent.atomic.AtomicBoolean initialSliceExpired=new java.util.concurrent.atomic.AtomicBoolean();
  private int ownedSlices;
  private long ownedStartedAt;
  // Set only when this initial idle slice admits a high-concurrency wave.
  // Keep it until the slice exits so a late input cannot authorize a stale commit.
  private volatile long idleWavePressureGeneration=-1L;
  private java.util.function.BooleanSupplier pauseRequested = () -> false;
  private static final int MAX_PROBES_PER_RUN = 24;
  private static final long PROBE_STAGE_DEADLINE_MS = 90_000L;
  private static final int MAX_SOURCES_PER_CHANNEL = 8;
  // The full rebuild reads one fixed-size feed page per worker run and spends
  // its initial probe budget on that page. This prevents large China playlists from being starved
  // by small pages interleaved from every worldwide feed.
  // Per-worker memory safety bound only; it is not a lifetime catalogue cap.
  // Reaching it must leave the feed cursor untouched rather than skip entries.
  private static final int MAX_DISCOVERY_CANDIDATES_PER_RUN = 24000;
  private static final int MAX_DISCOVERY_PER_FEED = 4;
  private static final int MAX_INITIAL_DISCOVERY_PER_FEED = DiscoveryBatchPolicy.INITIAL_PAGE_SIZE;
  /** Backend for an explicit single-channel refresh. Caller runs off the UI
   * thread and owns lifecycle cancellation. All source network work shares
   * the existing process gate; a busy request never waits on that thread. */
  static final class SingleRefreshResult {
    enum Status { BUSY, DEFERRED, FULL, NO_NEW_SOURCE, SAVED }
    final Status status;
    final int candidates,checked,validated,added;
    final long catalogueVersion;
    final boolean bounded;
    final Map<String,Set<String>> addedRoutes;
    final Map<String,String> replacedRoutes;
    SingleRefreshResult(Status status,int candidates,int checked,int validated,int added,
        long catalogueVersion,boolean bounded){
      this(status,candidates,checked,validated,added,catalogueVersion,bounded,Collections.emptyMap(),Collections.emptyMap());
    }
    SingleRefreshResult(Status status,int candidates,int checked,int validated,int added,
        long catalogueVersion,boolean bounded,Map<String,Set<String>> addedRoutes,Map<String,String> replacedRoutes){
      this.status=status;this.candidates=candidates;this.checked=checked;
      this.validated=validated;this.added=added;this.catalogueVersion=catalogueVersion;
      this.bounded=bounded;this.addedRoutes=addedRoutes;this.replacedRoutes=replacedRoutes;
    }
  }
  /** Resolve the visible channel against the newest committed Room row, not
   * the Activity's potentially older full-snapshot version. Ambiguity fails
   * closed; the existing backend rechecks the captured version under its gate. */
  static SingleRefreshResult refreshSingleChannelForVisibleChannel(Context context,String legacyKey,
      String group,String tvgId,String tvgName,String activeUrl,
      java.util.function.BooleanSupplier cancelled)throws Exception{
    SourceProbeTimePolicy.checkPaused(cancelled);
    com.wodedianshi.tv.db.ChannelDao dao=com.wodedianshi.tv.db.RoomDatabaseProvider
        .get(context.getApplicationContext()).channelDao();
    com.wodedianshi.tv.db.CatalogueMigrationEntity checkpoint=dao.getValidatedCheckpoint(
        com.wodedianshi.tv.db.MasterCatalogueRepository.MIGRATION_KEY);
    List<String> ids=dao.getChannelIdsByLegacyKey(legacyKey);
    if(checkpoint==null||ids.size()!=1)
      throw new com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException();
    com.wodedianshi.tv.db.ChannelEntity row=dao.getChannelById(ids.get(0));
    if(row==null||!legacyKey.equals(row.legacyKey)||!group.equals(row.groupName)
        ||!tvgId.equals(row.tvgId)||!tvgName.equals(row.tvgName))
      throw new com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException();
    boolean hasActive=false;
    for(com.wodedianshi.tv.db.BackupSourceEntity source:dao.getBackupSourcesForChannel(row.id))
      if(activeUrl.equals(source.url)){hasActive=true;break;}
    if(!hasActive)throw new com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException();
    return refreshSingleChannel(context,row.id,checkpoint.completedAt,activeUrl,cancelled);
  }
  static SingleRefreshResult refreshSingleChannel(Context context,String channelId,long expectedVersion,
      java.util.function.BooleanSupplier cancelled)throws Exception{
    return refreshSingleChannel(context,channelId,expectedVersion,"",cancelled);
  }
  static SingleRefreshResult refreshSingleChannel(Context context,String channelId,long expectedVersion,
      String playingUrl,java.util.function.BooleanSupplier cancelled)throws Exception{
    if(channelId==null||channelId.isEmpty()||expectedVersion<0L||cancelled==null)
      throw new IllegalArgumentException("Missing single channel refresh request");
    if(!SOURCE_WORK_ACTIVE.compareAndSet(false,true))
      return new SingleRefreshResult(SingleRefreshResult.Status.BUSY,0,0,0,0,expectedVersion,false);
    try{
      if(!awaitRetiringProbePool(cancelled))
        return new SingleRefreshResult(cancelled.getAsBoolean()?SingleRefreshResult.Status.DEFERRED:
            SingleRefreshResult.Status.BUSY,0,0,0,0,expectedVersion,false);
      final boolean viewing=SourceUpdateActivityPolicy.isPlaybackActive();
      final long stableGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
      final long pressure=SourceUpdateActivityPolicy.pressureGeneration();
      final long playbackStart=SourceUpdateActivityPolicy.playbackStartGeneration();
      java.util.function.BooleanSupplier paused=SourceProbeTimePolicy.latchPause(()->cancelled.getAsBoolean()
          ||SourceUpdateActivityPolicy.pressureGeneration()!=pressure
          ||SourceUpdateActivityPolicy.playbackStartGeneration()!=playbackStart
          ||(viewing?!SourceUpdateActivityPolicy.permitsInitialPlaybackWork(
              android.os.SystemClock.elapsedRealtime(),stableGeneration):SourceUpdateActivityPolicy.isPlaybackActive()));
      if(paused.getAsBoolean())
        return new SingleRefreshResult(SingleRefreshResult.Status.DEFERRED,0,0,0,0,expectedVersion,false);
      com.wodedianshi.tv.db.ChannelDao dao=com.wodedianshi.tv.db.RoomDatabaseProvider
          .get(context.getApplicationContext()).channelDao();
      com.wodedianshi.tv.db.CatalogueMigrationEntity checkpoint=dao.getValidatedCheckpoint(
          com.wodedianshi.tv.db.MasterCatalogueRepository.MIGRATION_KEY);
      com.wodedianshi.tv.db.ChannelEntity row=dao.getChannelById(channelId);
      if(checkpoint==null||checkpoint.completedAt!=expectedVersion||row==null)
        throw new com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException();
      ArrayList<String> existing=new ArrayList<>();
      for(com.wodedianshi.tv.db.BackupSourceEntity source:dao.getBackupSourcesForChannel(channelId))
        existing.add(source.url);
      // Full channels still search; retirement requires stored failure evidence.
      final boolean full=existing.size()>=MAX_SOURCES_PER_CHANNEL;
      SingleChannelCandidateSearch.Target target=new SingleChannelCandidateSearch.Target(row.name,row.groupName,row.tvgId,row.tvgName);
      final int limit=viewing?2:4;
      SingleChannelCandidateSearch.SearchResult found=SingleChannelCandidateSearch.searchCached(context,channelId,target,existing,limit,paused);
      SingleChannelCandidateSearch.tryOnlineFallback(found,target,existing,viewing,paused);
      final long stageDeadline=android.os.SystemClock.elapsedRealtime()+(viewing?30000L:60000L);
      int checked=0;
      boolean cachedConclusive=true;
      ArrayList<SingleChannelCandidateSearch.Candidate> passed=new ArrayList<>();
      HashMap<String,Integer> scores=new HashMap<>();
      for(SingleChannelCandidateSearch.Candidate candidate:found.candidates){
        SourceProbeTimePolicy.checkPaused(paused);
        if(checked>=limit)break;
        long addressDeadline=SourceProbeTimePolicy.initialAddressDeadline(
            stageDeadline,android.os.SystemClock.elapsedRealtime());
        if(addressDeadline==0L)break;
        checked++;
        ProbeResult observation=probe(candidate.url,addressDeadline,viewing,paused,false);
        SourceProbeTimePolicy.checkPaused(paused);
        if(observation.inconclusive()
            ||android.os.SystemClock.elapsedRealtime()>=addressDeadline){
          if(checked<=found.cachedCandidates)cachedConclusive=false;
          continue;
        }
        if(!observation.valid)continue;
        passed.add(candidate);scores.put(candidate.url,observation.score);
      }
      boolean bounded=found.bounded||found.missingFeeds>0||checked<found.candidates.size();
      passed.sort((x,y)->Integer.compare(scores.get(y.url),scores.get(x.url)));
      LinkedHashMap<String,Set<String>> verified=new LinkedHashMap<>();
      for(SingleChannelCandidateSearch.Candidate candidate:passed)
        verified.put(candidate.url,Collections.singleton(candidate.scope));
      SourceProbeTimePolicy.checkPublicationPaused(paused);
      if(verified.isEmpty()){
        SingleChannelCandidateSearch.persistCompletedSearch(context,found,checked,cachedConclusive,paused);
        return new SingleRefreshResult(SingleRefreshResult.Status.NO_NEW_SOURCE,found.candidates.size(),
            checked,0,0,expectedVersion,bounded);
      }
      HashSet<String> eligible=new HashSet<>();
      HashMap<String,Double> quality=new HashMap<>();
      for(String candidate:verified.keySet())quality.put(candidate,(double)scores.get(candidate));
      if(full||existing.size()+verified.size()>MAX_SOURCES_PER_CHANNEL){
        boolean indexed=com.wodedianshi.tv.db.ManualProbeQualityStore.hasPublished(context);
        JSONObject priorScores=new JSONObject(indexed?"{}":com.wodedianshi.tv.db.AppStateStore.get(context,"source_probe_scores_v1","{}"));
        JSONObject streaks=new JSONObject(indexed?"{}":com.wodedianshi.tv.db.AppStateStore.get(context,"source_probe_failure_streaks_v1","{}"));
        if(indexed)com.wodedianshi.tv.db.ManualProbeQualityStore.loadUrls(context,
            com.wodedianshi.tv.db.ManualProbeQualityStore.PUBLISHED_OWNER,existing,new HashSet<>(),priorScores,streaks,paused);
        for(String old:existing){
          String key=StableKey.url(old);
          double score=priorScores.optDouble(key,0.0);
          quality.put(old,score);
          if(streaks.optInt(key,0)>=SourceMaintenanceRetentionPolicy.FAILURES_BEFORE_RETIREMENT
              &&SourceQualityScorer.isFailedProbeScore(score))
            eligible.add(SourceSelectionPolicy.endpointKey(old));
        }
      }
      java.util.Comparator<String> bestFirst=(a,b)->Double.compare(quality.getOrDefault(b,0.0),quality.getOrDefault(a,0.0));
      com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelRefreshResult commit=
          com.wodedianshi.tv.db.RoomCatalogueWriter.refreshVerifiedChannelSources(
              context,channelId,expectedVersion,verified,playingUrl,eligible,bestFirst,paused);
      // A committed route must still be reported if cancellation or a cursor
      // write failure happens after the transaction. Re-scanning is safe.
      try{SingleChannelCandidateSearch.persistCompletedSearch(context,found,checked,cachedConclusive,paused);}
      catch(Exception cursorFailure){android.util.Log.w("MyTVSourceDiscovery","single-channel cursor save deferred",cursorFailure);}
      return new SingleRefreshResult(commit.addedSources>0?SingleRefreshResult.Status.SAVED:
          (full?SingleRefreshResult.Status.FULL:SingleRefreshResult.Status.NO_NEW_SOURCE),
          found.candidates.size(),checked,passed.size(),commit.addedSources,commit.catalogueVersion,
          bounded,commit.addedRoutes,commit.replacedRoutes);
    }finally{
      SOURCE_WORK_ACTIVE.set(false);
    }
  }

  public SourceUpdateWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
  @NonNull @Override public Result doWork(){
    if(!SOURCE_WORK_ACTIVE.compareAndSet(false,true)){
      return SourceUpdateSchedulePolicy.shouldRetryWhenBusy(
          getInputData().getBoolean("manual",false),
          getInputData().getBoolean("initial_discovery_chain",false))
          ?Result.retry():Result.success();
    }
    int originalPriority=android.os.Process.THREAD_PRIORITY_DEFAULT;
    boolean priorityChanged=false;
    try{
      try{
        originalPriority=android.os.Process.getThreadPriority(android.os.Process.myTid());
        if(originalPriority<android.os.Process.THREAD_PRIORITY_BACKGROUND){
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
          priorityChanged=true;
        }
      }catch(RuntimeException ignored){}
      long now=android.os.SystemClock.elapsedRealtime();
      if(!getInputData().getBoolean("initial_discovery_chain",false)
          ||now<committedStagingAt||now-committedStagingAt>60000L)discardStagingReuse();
      ownedStartedAt=android.os.SystemClock.elapsedRealtime();
      ownedSlices=0;
      for(;;){
        if(isStopped()||Thread.currentThread().isInterrupted())return Result.retry();
        // A drained pool must really terminate before the next slice admits
        // network work, including an inline continuation in the same owner.
        if(!awaitRetiringProbePool())return Result.retry();
        inlineInitialRequested=false;
        ownedSlices++;
        noteInitialStage("准备本轮目录整理");
        Result result=runOwnedWork();
        if(!inlineInitialRequested)return result;
        android.util.Log.i("MyTVSourceDiscovery","inline_initial_continuation completed_slices="+ownedSlices);
      }
    }finally{
      if(isStopped()||Thread.currentThread().isInterrupted())discardStagingReuse();
      initialLiveStage="";SOURCE_WORK_ACTIVE.set(false);
      if(priorityChanged)try{android.os.Process.setThreadPriority(originalPriority);}
      catch(RuntimeException ignored){}
    }
  }
  /** The exclusive Worker owner uses the same pool-drain gate for the first
   * slice and every inline continuation. Never bypass a live retired pool. */
  private boolean awaitRetiringProbePool(){
    return awaitRetiringProbePool(()->isStopped());
  }
  private static boolean awaitRetiringProbePool(java.util.function.BooleanSupplier cancelled){
    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return false;
    java.util.concurrent.ExecutorService previous=retiringProbeExecutor;
    if(previous!=null){
      try{
        if(!previous.isTerminated()&&!previous.awaitTermination(250L,
            java.util.concurrent.TimeUnit.MILLISECONDS))return false;
      }catch(InterruptedException interrupted){
        Thread.currentThread().interrupt();return false;
      }
      if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return false;
      retiringProbeExecutor=null;
    }
    return !cancelled.getAsBoolean()&&!Thread.currentThread().isInterrupted();
  }
  private Result runOwnedWork(){
    final long sliceStartedAt=android.os.SystemClock.elapsedRealtime();
    initialSliceExpired.set(false);
    SharedPreferences prefs=getApplicationContext().getSharedPreferences("mytv",0);
    final boolean manual=getInputData().getBoolean("manual",false);
    final boolean initialDiscoveryChain=getInputData().getBoolean("initial_discovery_chain",false);
    final boolean manualRefresh=manual&&!initialDiscoveryChain;
    final String manualId=getInputData().getString("manual_refresh_id")==null
        ?getId().toString():getInputData().getString("manual_refresh_id");
    if(!manual&&!prefs.getBoolean("auto_source_update",true))return Result.success();
    // A retry after the final durable commit must terminate even during playback.
    if(manualRefresh&&manualId.equals(roomString("source_manual_completed_id","")))return Result.success();
    // No full catalogue yet: the one-time chain owns discovery; a periodic
    // worker must neither compete with it nor publish a partial replacement.
    boolean provisionalOnly=false;
    if(!initialDiscoveryChain){
      long completedAt=0L;
      try{completedAt=Long.parseLong(roomString("source_full_rebuild_completed_at","0"));}
      catch(NumberFormatException ignored){}
      if(!SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true,completedAt))
        return manual?Result.retry():Result.success();
      long lastRoutineAt=0L;
      try{lastRoutineAt=Long.parseLong(roomString("source_last_routine_maintenance_at","0"));}
      catch(NumberFormatException ignored){}
      if(!manual&&!SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(completedAt,lastRoutineAt,System.currentTimeMillis())){
        String pending=roomString(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_KEY,"[]");
        long last=0L;
        try{last=Long.parseLong(roomString(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_LAST_KEY,"0"));}
        catch(NumberFormatException ignored){}
        provisionalOnly=SourceUpdateSchedulePolicy.provisionalRecheckDue(!"[]".equals(pending)&&!pending.isEmpty(),last,System.currentTimeMillis());
        if(!provisionalOnly)return Result.success();
      }
      // A manual chain may be between slices or paused for playback while the
      // Activity is in the background. Do not occupy its next source-work slot
      // or replace its cached feed. Preserve the routine due date when yielding.
      if(!manual&&!routineMayStartWithManualChain())return Result.success();
    }
    final long playbackStart=SourceUpdateActivityPolicy.playbackStartGeneration();
    final long activityStart=SourceUpdateActivityPolicy.activityStartGeneration();
    final long stableGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
    slowPlayback=initialDiscoveryChain&&SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime(),stableGeneration);
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
    idleWavePressureGeneration=-1L;
    pauseRequested=SourceProbeTimePolicy.latchPause(()->isStopped()
        ||observeInitialSliceExpiry(initialDiscoveryChain,sliceStartedAt)
        ||SourceUpdateActivityPolicy.idleWavePressureChanged(idleWavePressureGeneration)
        ||SourceUpdateActivityPolicy.playbackStartGeneration()!=playbackStart
        ||(deferForeground&&SourceUpdateActivityPolicy.activityStartGeneration()!=activityStart)
        ||shouldYieldToViewing(prefs,manual,initialDiscoveryChain,publishedAtStart,stableGeneration));
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
    final IdleProbeWave idleWave=new IdleProbeWave();
    try{
      if(provisionalOnly)return runProvisionalQualityRecheck();
      final String manualOwnerKey=com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_OWNER_KEY;
      JSONArray manualWorkingRows=null;
      boolean manualQueueOnly=false,manualIndexed=false;
      if(manualRefresh){
        String config=discoveryFeedsFingerprint(feeds);
        String owner=roomString(manualOwnerKey,"");
        boolean resume=manualId.equals(owner)&&manualId.equals(roomString("source_manual_active_id",""))
            &&config.equals(roomString("source_manual_feed_config",""));
        if(!SourceUpdateSchedulePolicy.manualProbeCacheMatches(resume,manualId,manualProbeCacheOwner)){
          clearRecentProbeCache();manualProbeCacheOwner=manualId;
        }
        if(!resume){
          // An older cancelled/failed request may leave private staging, never
          // a partially published directory. Only the source owner discards it.
          if(!owner.isEmpty())com.wodedianshi.tv.db.RoomCatalogueBuildStore.discardManual(
              getApplicationContext(),owner,null,pauseRequested);
          JSONArray seed=new JSONArray();
          com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot baseSnapshot=readPublishedRows(seed);
          if(baseSnapshot==null||seed.length()==0)throw new IllegalStateException("Manual base catalogue unavailable");
          com.wodedianshi.tv.db.AppStateEditor reset=
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
              .putString("source_manual_active_id",manualId)
              .putString("source_manual_feed_config",config)
              .putInt("source_manual_next_feed",0)
              .putInt("source_manual_failed_feeds",0)
              .putInt("source_manual_feed_attempts",0)
              .remove("source_manual_feed_batch_fingerprint")
              .remove("source_manual_feed_batch_scanned")
              .remove("source_manual_prepared_feed_version")
              .remove("source_manual_saved_pool_fingerprint")
              .remove("source_manual_saved_scanned")
              .remove(com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_READY_KEY);
          for(int i=0;i<feeds.length;i++)
            reset.remove(manualFeedCursorKey(i)).remove(manualFeedCursorKey(i)+"_version");
          for(String key:ManualSavedCoverage.keys())reset.remove(key);
          for(int i=0;i<seed.length();i++){
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
            JSONObject row=seed.getJSONObject(i);
            row.put("manualBaseIdentity",manualRowIdentity(row));
            row.put("manualBaseFingerprint",manualRowFingerprint(row));
          }
          reset.putInt(com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_BASE_COUNT_KEY,seed.length());
          com.wodedianshi.tv.db.RoomCatalogueBuildStore.seedManual(
              getApplicationContext(),seed,baseSnapshot.catalogueVersion,manualId,pauseRequested,reset);
          manualWorkingRows=seed;
        }else{
          // Structural edits are reconciled at the final publication, not by
          // throwing away already committed network work at every slice.
          manualQueueOnly=com.wodedianshi.tv.db.RoomCatalogueBuildStore.canRunManualQueueOnly(getApplicationContext(),manualId,feeds.length);
          if(!manualQueueOnly){
            manualIndexed=com.wodedianshi.tv.db.RoomCatalogueBuildStore.canRunManualIndexed(getApplicationContext(),manualId)
                &&roomInt("source_manual_next_feed",0)<feeds.length;
            if(manualIndexed)com.wodedianshi.tv.db.RoomCatalogueBuildStore.ensureManualIdentityIndex(getApplicationContext(),manualId,pauseRequested);
            else manualWorkingRows=com.wodedianshi.tv.db.RoomCatalogueBuildStore.readManual(getApplicationContext(),manualId,pauseRequested);
          }
        }
        if(!manualQueueOnly&&!manualIndexed){
          com.wodedianshi.tv.db.RoomCatalogueBuildStore.ensureManualQueue(getApplicationContext(),manualId,manualWorkingRows,pauseRequested);
          com.wodedianshi.tv.db.ManualProbeQualityStore.ensure(getApplicationContext(),manualId,pauseRequested);
          if(roomBoolean(com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_READY_KEY,false))
            return publishReadyManual(manualId,manualWorkingRows);
        }
      }
      // Manual and initial work now use isolated staging. Routine maintenance
      // alone reads the published directory for each bounded slice.
      final JSONArray publishedWorkingRows=initialDiscoveryChain||manualRefresh?null:new JSONArray();
      com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot roomSnapshot =
          initialDiscoveryChain||manualRefresh?null:readPublishedRows(publishedWorkingRows);
      if(initialDiscoveryChain||manualRefresh
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
          clearRecentProbeCache();INITIAL_ORIGINS.clear();INITIAL_NETWORK.clear();
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
                .remove(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(feed))
                .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"fingerprint").remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"scanned")
                .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"coverage_order")
                .remove(DiscoveryBatchPolicy.initialIdleRecheckKey(feed))
                .remove(DiscoveryBatchPolicy.initialTransientRecheckKey(feed))
                 .remove(DiscoveryBatchPolicy.initialReplayMaskKey(feed))
                .remove(DiscoveryBatchPolicy.initialTransientRecheckAtKey(feed))
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
              .remove(FullDiscoveryProgressPolicy.THROUGHPUT_STATUS_KEY)
              .putLong(FullDiscoveryProgressPolicy.INITIAL_BUILD_STARTED_AT_KEY,System.currentTimeMillis())
              .putInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0)
              .putInt(FullDiscoveryProgressPolicy.PREVIEW_MILESTONE_KEY,0)
              .putInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,0)
              .putInt(FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY,0)
              .putInt("source_initial_index_attempted",0)
              .putBoolean("source_initial_index_bypassed",slowPlayback)
              .putInt("source_initial_index_ready",0)
              .putLong("source_initial_index_entries",0L)
              .putInt("source_initial_progress_percent",0)
              .putInt("source_initial_progress_basis_points",0)
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
              .remove(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY)
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
          int bootstrapFeed=SourceDiscoveryPriorityPolicy.firstWatchBootstrapFeed(nextIndex);
          String preparedVersion="";
          noteInitialStage("首批中国目录准备 "+(nextIndex+1)+"/"+mainlandLimit+"（不检测线路）");
          try{
            File cached=PlaylistFeedCache.obtainInitialDuringIdle(getApplicationContext(),feeds[bootstrapFeed],
                roomInt(feedCursorKey(bootstrapFeed),0)>0,pauseRequested);
            if(cached==null){
              SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                  .putBoolean("source_initial_index_bypassed",true)
                  .putInt("source_initial_next_feed_index",
                      SourceDiscoveryPriorityPolicy.nextAfterFirstWatchTurn(bootstrapFeed,feeds.length))
                  .putString("source_initial_progress_phase","首批目录分段已保存，先检测其他中国目录")
                  .apply();
              return continueAfterAcquisition();
            }
            // readPage builds the complete random-access index if necessary.
            // Decode one record to check the sidecar without allocating all entries.
            PlaylistFeedIndex.readInitialPage(cached,0,1,source->{},pauseRequested);
            count=PlaylistFeedIndex.indexedEntryCount(cached);
            if(count>=0){
              PlaylistFeedCache.trimPreparedInitial(getApplicationContext(),cached);
              preparedVersion=PlaylistFeedCache.contentVersion(cached);
            }
          }catch(Exception failure){
            if(failure instanceof SourceProbeTimePolicy.PausedException||pauseRequested.getAsBoolean())
              return deferredViewingResult(manual,initialDiscoveryChain);
            android.util.Log.w("MyTVSourceDiscovery","initial_index_deferred feed="+bootstrapFeed);
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
          if(count>0)acquisition.putInt("source_initial_next_feed_index",bootstrapFeed);
          if(!preparedVersion.isEmpty())acquisition.putString(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(bootstrapFeed),preparedVersion);
          acquisition.apply();
          // Persist acquisition before any media probes. A quickly prepared
          // nonempty first directory may immediately enter the ordinary bounded
          // page path, which rechecks the exact body marker and playback gate.
          if(!DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(
              slowPlayback,count,android.os.SystemClock.elapsedRealtime()-sliceStartedAt)){
            enqueueInitialContinuation();
            return Result.success();
          }
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
        // Recover an already committed but not yet exposed preview even when
        // every remaining feed is deferred for idle. Do not probe another URL
        // or advance a cursor in this publication-only continuation.
        int stagedCount=roomInt("source_initial_progress_staged_channels",0);
        int chinaValidated=roomInt("source_initial_progress_china_validated_urls",0);
        int oldPreview=roomInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0);
        int oldMilestone=roomInt(FullDiscoveryProgressPolicy.PREVIEW_MILESTONE_KEY,0);
        int pendingPreview=FullDiscoveryProgressPolicy.nextPreviewLevel(oldPreview,stagedCount,
            chinaValidated,roomInt("source_initial_progress_china_done_feeds",0)>=SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
        int pendingMilestone=FullDiscoveryProgressPolicy.nextPreviewMilestone(oldMilestone,stagedCount,chinaValidated);
        int oldCoreMask=roomInt(FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY,0);
        int pendingCoreMask=FullDiscoveryProgressPolicy.nextPreviewCoreMask(oldCoreMask,
            roomInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,0),chinaValidated);
        if((pendingPreview>oldPreview||pendingMilestone>oldMilestone||pendingCoreMask!=oldCoreMask)
            &&FullDiscoveryProgressPolicy.canPublishPreview(slowPlayback,stagedCount,
                com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext()).channelDao().countChannels())){
          JSONArray ready=takeCommittedStaging();
          if(ready.length()==stagedCount&&publishInitialPreview(ready,pendingPreview,pendingMilestone,pendingCoreMask))
            return continueAfterAcquisition();
        }
        // Last viewing slice may already have committed every feed. Avoid
        // reloading/ranking staging on each retry while only publication waits.
        if(slowPlayback&&areAllDiscoveryFeedsComplete(prefs,Collections.emptyList(),feeds.length))
          return deferInitialDirectory("线路初筛已完成，等待空闲发布完整快照");
        // Acquisition and viewing deferral need only the durable row count.
        // Read the actual rows after these paths have had a chance to return.
        saved=null;
        com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),
            "source_initial_progress_phase","正在继续首次完整抓源");
      }else{
        // Manual work never mutates the published directory before completion.
        // Queue-only slices must not mutate or serialize a staging catalogue.
        saved=manualQueueOnly||manualIndexed?new JSONArray():manualRefresh?manualWorkingRows:publishedWorkingRows;

      }
      final int previousStagedSize=initialDiscoveryChain
          ?com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext())
              .channelDao().countCatalogueBuildRows()
          :saved.length();
      final int initialCheckedAtStart=initialDiscoveryChain
          ?roomInt("source_initial_progress_checked_urls",0):0;
      final boolean earlyFirstWatch=initialDiscoveryChain&&DiscoveryBatchPolicy.isEarlyFirstWatch(
          previousStagedSize,initialCheckedAtStart);
      final boolean preferCoreCoverage=initialDiscoveryChain&&DiscoveryBatchPolicy.preferCoreCoverage(
          previousStagedSize,initialCheckedAtStart,
          roomInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,-1));
      final int indexedBaseCount=manualIndexed?com.wodedianshi.tv.db.RoomCatalogueBuildStore.manualStagingCount(getApplicationContext(),manualId):0;
      final ArrayList<Integer> indexedPositions=new ArrayList<>();
      ArrayList<Entry> candidates=new ArrayList<>();
      // Include the user's already-saved backup URLs in the rotating health-check
      // pool. Previously only URLs rediscovered in public playlists were probed,
      // so a saved backup could silently die without ever losing its old score.
      ArrayList<Entry> savedPool=new ArrayList<>();
      HashSet<String> savedPoolUrls=new HashSet<>();
      // First discovery keeps its independent new-candidate budget. Explicit
      // manual refresh must check old routes even when requested from the TV UI;
      // otherwise a user reporting dead backups can scan only new candidates.
      // The viewing pause gate and the per-slice saved probe allotment still apply.
      if(!initialDiscoveryChain&&!manualRefresh
          &&SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(false,false,foregroundAtStart)){
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
            String endpoint=SourceSelectionPolicy.endpointKey(u);
            if(savedPoolUrls.add(endpoint)){
              Entry entry=new Entry(channelName,u,channelGroup,true,"",
                  channel.optString("tvgId",""),channel.optString("tvgName",""));
              entry.canonicalEndpoint=endpoint;
              savedPool.add(entry);
            }
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
      boolean[] visitedForCoverage=new boolean[feeds.length];
      boolean[] preparedForCoverage=new boolean[feeds.length];
      boolean[] failedForCoverage=new boolean[feeds.length];
      boolean acquisitionYield=false;
      boolean[] availableUnderPressure=null;
      boolean[] orderedReadyUnderPressure=new boolean[feeds.length];
      String[] acquiredVersions=new String[feeds.length];
      if(initialDiscoveryChain){
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        InitialFeedAdmission admission=readInitialFeedAdmission(feeds,slowPlayback);
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        System.arraycopy(admission.completed,0,inspectedFeeds,0,feeds.length);
        System.arraycopy(admission.deferred,0,attemptedFeedsThisRun,0,feeds.length);
        System.arraycopy(admission.visited,0,visitedForCoverage,0,feeds.length);
        System.arraycopy(admission.prepared,0,preparedForCoverage,0,feeds.length);
        System.arraycopy(admission.failed,0,failedForCoverage,0,feeds.length);
        acquisitionYield=admission.acquisitionYield;
        availableUnderPressure=admission.availableUnderPressure;
        orderedReadyUnderPressure=admission.orderedReadyUnderPressure;
        acquiredVersions=admission.acquiredVersions;
        final long[] recheckRemaining=admission.remaining;
        if(!slowPlayback&&SourceDiscoveryPriorityPolicy.activeStage(inspectedFeeds,
            SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
            SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT)<3
            &&SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(initialNextFeedIndex,inspectedFeeds,
                attemptedFeedsThisRun,visitedForCoverage,preparedForCoverage,failedForCoverage,
                SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
                SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT,preferCoreCoverage,slowPlayback,acquisitionYield,availableUnderPressure,orderedReadyUnderPressure)<0){
          // All unfinished feeds across the priority stages are cooling.
          // Do not treat this as exhaustion, or retry the same request in a loop.
          enqueueInitialContinuation(DiscoveryBatchPolicy.earliestEligibleCooldownSeconds(inspectedFeeds,
              recheckRemaining));
          return Result.success();
        }
      }
      int routineStart=manualRefresh?Math.max(0,Math.min(feeds.length,roomInt("source_manual_next_feed",0))):
          InitialFeedRotationPolicy.normalizeStart(roomInt("source_routine_next_feed_index",0),feeds.length);
      final boolean manualSavedOnly=manualRefresh&&routineStart>=feeds.length;
      if(manualQueueOnly&&!manualSavedOnly)throw new IllegalStateException("Queue-only feed cursor changed");
      int routineEnd=manualRefresh?Math.min(feeds.length,routineStart+1):
          SourceUpdateSchedulePolicy.routineSliceEnd(routineStart,feeds.length);
      int feedLimit=initialDiscoveryChain?feeds.length:routineEnd-routineStart;
      for(int feedOffset=0;feedOffset<feedLimit;feedOffset++){
        int feedIndex=initialDiscoveryChain
            ?SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(initialNextFeedIndex,
                inspectedFeeds,attemptedFeedsThisRun,visitedForCoverage,preparedForCoverage,failedForCoverage,
                SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
                SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT,preferCoreCoverage,slowPlayback,acquisitionYield,availableUnderPressure,orderedReadyUnderPressure)
            :routineStart+feedOffset;
        if(feedIndex<0)break;
        // Prefer mainland, then HK/Macau/Taiwan, then international feeds.
        // If an entire stage is temporarily deferred, use the next eligible stage.
        // Deferred feeds remain unfinished and retain their exact saved cursors.
        // Preserve the existing probe budgets and playback gates.
        String feed=feeds[feedIndex];
        String cursorKey=initialDiscoveryChain?feedCursorKey(feedIndex):
            manualRefresh?manualFeedCursorKey(feedIndex):routineFeedCursorKey(feedIndex);
        int feedCursor=roomInt(cursorKey,0);
        final String savedProbeVersion=roomString(cursorKey+"_version","");
        final int savedPartialScanned=initialDiscoveryChain?
            roomInt(DiscoveryBatchPolicy.initialBatchPrefix(feedIndex)+"scanned",0):0;
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
          boolean manualNewPage=manualRefresh&&DiscoveryBatchPolicy.manualRevalidateDirectory(
              feedCursor,roomString("source_manual_feed_batch_fingerprint",""));
          File cachedFeed;
          if(initialDiscoveryChain){
            cachedFeed=PlaylistFeedCache.findIndexedInitial(getApplicationContext(),feed);
          }else if(manualRefresh){
            cachedFeed=manualNewPage?PlaylistFeedCache.findPreparedManual(getApplicationContext(),feed,
                roomString("source_manual_prepared_feed_version","")):null;
            if(cachedFeed==null){
              cachedFeed=PlaylistFeedCache.obtainManual(getApplicationContext(),feed,manualNewPage,pauseRequested);
              if(manualNewPage){
                // Acquisition alone does not advance any candidate or feed cursor.
                String preparedVersion=PlaylistFeedCache.contentVersion(cachedFeed);
                SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
                new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                    .putString("source_manual_prepared_feed_version",preparedVersion).apply();
              }
            }
          }else{
            cachedFeed=PlaylistFeedCache.obtain(getApplicationContext(),feed,false,feedCursor>0,pauseRequested);
          }
          String acquiredVersion=initialDiscoveryChain&&!slowPlayback
              ?acquiredVersions[feedIndex]:"";
          if(initialDiscoveryChain&&!slowPlayback&&(cachedFeed==null||!DiscoveryBatchPolicy.isPreparedInitialFeed(
              acquiredVersion,PlaylistFeedCache.contentVersion(cachedFeed)))){
            // Restore the derived index from the exact committed body first.
            // Evicted or changed bodies still require ordinary acquisition.
            cachedFeed=PlaylistFeedCache.findAcquiredInitialBody(getApplicationContext(),feed,acquiredVersion,pauseRequested);
            boolean restoredLocalBody=cachedFeed!=null;
            if(cachedFeed!=null){
              noteInitialStage("复用已保存目录并恢复索引 "+(feedIndex+1)+"/"+feeds.length);
            }else{
              noteInitialStage("独立准备目录 "+(feedIndex+1)+"/"+feeds.length);
              cachedFeed=PlaylistFeedCache.obtainInitialDuringIdle(getApplicationContext(),feed,feedCursor>0,pauseRequested);
            }
            if(cachedFeed==null){
              SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
              new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                  .putInt("source_initial_next_feed_index",
                      preferCoreCoverage?SourceDiscoveryPriorityPolicy.nextAfterFirstWatchTurn(feedIndex,feeds.length)
                          :InitialFeedRotationPolicy.nextAfterPartialAcquisition(feedIndex,feeds.length,false))
                  .putBoolean(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY,true)
                  .putString("source_initial_progress_phase","空闲时已保存目录分段，轮换其他目录")
                  .apply();
              return continueAfterAcquisition();
            }
            if(PlaylistFeedIndex.preferCoverageOrder(PlaylistFeedCache.contentVersion(cachedFeed),
                savedProbeVersion,feedCursor,savedPartialScanned))
              PlaylistFeedIndex.readInitialPage(cachedFeed,0,1,source->{},pauseRequested);
            else PlaylistFeedIndex.readPage(cachedFeed,0,1,source->{},pauseRequested);
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
            commitInitialAcquisition(feedIndex,cachedFeed);
            preparedForCoverage[feedIndex]=true;
            acquiredVersions[feedIndex]=PlaylistFeedCache.contentVersion(cachedFeed);
            availableUnderPressure=PlaylistFeedCache.initialCachePressure(getApplicationContext(),feeds,inspectedFeeds,acquiredVersions,preparedForCoverage,orderedReadyUnderPressure);
            // Yield to another fresh eligible directory before deep scans; retain
            // immediate first-watch and the ordinary probe budget when none remains.
            int nextPreparation=SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(feedIndex,
                inspectedFeeds,attemptedFeedsThisRun,visitedForCoverage,preparedForCoverage,failedForCoverage,
                SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
                SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT,preferCoreCoverage,slowPlayback,false,availableUnderPressure,orderedReadyUnderPressure);
            if(!DiscoveryBatchPolicy.canProbePreparedDirectory(slowPlayback,
                PlaylistFeedIndex.indexedEntryCount(cachedFeed),
                android.os.SystemClock.elapsedRealtime()-sliceStartedAt,nextPreparation==feedIndex,
                restoredLocalBody,availableUnderPressure!=null))
              return continueAfterAcquisition();
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          }
          boolean viewingNeedsOrderedRecovery=initialDiscoveryChain&&slowPlayback&&cachedFeed!=null
              &&PlaylistFeedIndex.requiresIdleCoverageRepair(cachedFeed,
                  PlaylistFeedCache.contentVersion(cachedFeed),savedProbeVersion,feedCursor,savedPartialScanned);
          if(slowPlayback&&(cachedFeed==null||viewingNeedsOrderedRecovery)){
            // Acquisition is its own bounded slice: no stream probe in the same
            // worker. Never advance the verified cursor after a download alone.
            noteInitialStage("稳定直播低速下载目录 "+(feedIndex+1)+"/"+feeds.length);
            try{
              if(cachedFeed==null)cachedFeed=PlaylistFeedCache.obtainInitialViewingChunk(
                  getApplicationContext(),feed,feedCursor>0,pauseRequested);
              if(cachedFeed==null){
                // An exact validated byte range was committed; neither the
                // directory nor the line-probe cursor is complete yet.
                SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
                new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                    .putInt("source_initial_next_feed_index",
                        InitialFeedRotationPolicy.nextAfterPartialAcquisition(feedIndex,feeds.length,true))
                    .putBoolean(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY,true)
                    .putString("source_initial_progress_phase","已保存大型目录分段，稳定播放时继续下载")
                    .apply();
                return continueAfterAcquisition();
              }
              if(!SourceProbeTimePolicy.fitsLiveFullDirectory(cachedFeed.length()))
                throw new IOException("Directory exceeds stable-viewing index limit");
              if(viewingNeedsOrderedRecovery||PlaylistFeedIndex.requiresIdleCoverageRepair(cachedFeed,
                  PlaylistFeedCache.contentVersion(cachedFeed),savedProbeVersion,feedCursor,savedPartialScanned)){
                // The viewing retry branch rotates to another directory
                // without touching the committed cursor or page fingerprint.
                throw new IOException("Station-first index recovery deferred until idle");
              }
              noteInitialStage("低优先级整理目录 "+(feedIndex+1)+"/"+feeds.length);
              PlaylistFeedIndex.readPage(cachedFeed,0,1,source->{},pauseRequested);
              SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
              LIVE_DIRECTORY_RETRY_AFTER.remove(feedIndex);
              commitInitialAcquisition(feedIndex,cachedFeed);
              return continueAfterAcquisition();
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
          if(initialDiscoveryChain&&!slowPlayback&&!PlaylistFeedIndex.hasCoverageOrder(cachedFeed)
              &&PlaylistFeedIndex.preferCoverageOrder(PlaylistFeedCache.contentVersion(cachedFeed),
                  savedProbeVersion,feedCursor,savedPartialScanned)){
            noteInitialStage("空闲时整理频道首源顺序 "+(feedIndex+1)+"/"+feeds.length);
            PlaylistFeedIndex.readInitialPage(cachedFeed,0,1,source->{},pauseRequested);
            PlaylistFeedCache.trimPreparedInitial(getApplicationContext(),cachedFeed);
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
            // Fast local upgrades can use the same existing bounded probe path.
            // Slow preparation remains a separate slice.
            if(!DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(slowPlayback,
                PlaylistFeedIndex.indexedEntryCount(cachedFeed),
                android.os.SystemClock.elapsedRealtime()-sliceStartedAt))
              return continueAfterAcquisition();
          }
          feedVersion=PlaylistFeedCache.contentVersion(cachedFeed);
          final boolean coverageOrderForRead=initialDiscoveryChain&&PlaylistFeedIndex.hasCoverageOrder(cachedFeed);
          if(initialDiscoveryChain)feedVersion=PlaylistFeedIndex.initialProbeVersion(
              feedVersion,coverageOrderForRead);
          feedCursor=DiscoveryBatchPolicy.cursorForVersion(feedCursor,savedProbeVersion,feedVersion);
          int feedPageSize=initialDiscoveryChain
              ?MAX_INITIAL_DISCOVERY_PER_FEED:manualRefresh?DiscoveryBatchPolicy.MANUAL_PAGE_SIZE:MAX_DISCOVERY_PER_FEED;
          noteInitialStage("解析目录 "+(feedIndex+1)+"/"+feeds.length+" · 游标 "+feedCursor);
          final int[] conflictingBroadcastRows={0};
          final String directoryScope=scopeForFeed(feed);
          PlaylistParser.EntryConsumer consume=source->{
            // Reject contradictory recognized station labels before spending network budget.
            // Raw parser positions still count toward the same feed cursor.
            if(ChannelNamePolicy.consistentBroadcaster(source.name,source.tvgName,source.tvgId)==null){
              conflictingBroadcastRows[0]++;return;
            }
            String scope=RegionCategoryPolicy.catalogueScope(
                source.name,source.group,source.tvgName,source.tvgId,directoryScope);
            feedCandidates.add(new Entry(source.name,source.url,source.group,false,scope,
                source.tvgId,source.tvgName));
          };
          parsedCount[0]=slowPlayback?PlaylistFeedIndex.readExistingPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested):
              coverageOrderForRead?PlaylistFeedIndex.readInitialPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested):
              PlaylistFeedIndex.readPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested);
          if(initialDiscoveryChain){
            // Dedupe exact records only within the fixed raw page. Raw index
            // and parsedCount remain untouched; a changed candidate fingerprint
            // restarts that partial page rather than skipping its suffix.
            ArrayList<Entry> distinct=DiscoveryBatchPolicy.distinctExactPageRecords(feedCandidates,
                entry->Arrays.asList(entry.name,entry.url,entry.group,entry.tvgId,entry.tvgName,entry.scope));
            feedCandidates.clear();feedCandidates.addAll(distinct);
          }
          if(manualRefresh&&!initialDiscoveryChain&&feedIndex<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT){
            // Manual refresh retains its stable mainland-first sort. Initial
            // discovery already partitions by a refinement of this rank in
            // coverageFirst below; pre-sorting there cannot alter order within
            // any final rank and repeats expensive metadata classification.
            DiscoveryBatchPolicy.stablePrioritySort(feedCandidates,
                entry->ChinaDiscoveryCandidatePolicy.initialProbePriority(feedIndex,
                    entry.scope,entry.name,entry.group,entry.tvgName,entry.tvgId));
          }
          if(initialDiscoveryChain){
            ArrayList<Entry> ordered=IdleProbeWavePolicy.coverageFirst(feedCandidates,
                entry->entry.mergeIdentity(),
                Entry::host,entry->ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                    feedIndex,entry.scope,entry.name,entry.group,entry.tvgName,entry.tvgId),
                entry->ChinaDiscoveryCandidatePolicy.initialCoverageRegion(
                    feedIndex,entry.scope,entry.name,entry.group,entry.tvgName,entry.tvgId));
            feedCandidates.clear();feedCandidates.addAll(ordered);
          }
          indexedEntries=PlaylistFeedIndex.indexedEntryCount(cachedFeed);
          // Parsing a cached large playlist can overlap a newly started player.
          // Do not enqueue its candidates for probing or advance any cursor.
          if(isStopped())return Result.retry();
          if(pauseRequested.getAsBoolean()){
            saveStatus(prefs,"频道操作开始，本轮目录整理已暂停且未发布部分结果",0);
            return deferredViewingResult(manual,initialDiscoveryChain);
          }
          // Idle indexed reads bypass obtain(), which normally refreshes cache
          // recency. Keep an actively scanned directory out of the oldest-cache
          // eviction slot without modifying its body identity or freshness age.
          if(initialDiscoveryChain&&!slowPlayback&&indexedEntries>=0)
            PlaylistFeedCache.noteIndexedInitialAccess(cachedFeed,System.currentTimeMillis());
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
              +" broadcaster_conflicts="+conflictingBroadcastRows[0]
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
          // Failed pages never contribute partial candidates or advance a cursor.
          // The parsed count may already have been assigned before index validation
          // failed; it is not durable evidence of a successfully processed page.
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
          feedBatches.add(new FeedBatch(feedIndex,feedCursor,
              feedReadSucceeded?parsedCount[0]:0,
              feedReadSucceeded?indexedEntries:-1,feedReadSucceeded||feedTerminalUnavailable,
              feedTerminalUnavailable,feedReadSucceeded?feedVersion:""));
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
      ArrayList<String> manualSavedKeys=new ArrayList<>();
      if(manualRefresh){
        int limit=manualSavedOnly?DiscoveryBatchPolicy.MANUAL_PAGE_SIZE:12;
        for(com.wodedianshi.tv.db.ManualProbeQueueEntity row:com.wodedianshi.tv.db.RoomDatabaseProvider
            .get(getApplicationContext()).manualProbeQueueDao().pending(manualId,limit)){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          savedPool.add(new Entry(row.name,row.url,row.groupName,true,"",row.tvgId,row.tvgName));
          manualSavedKeys.add(row.endpoint);
        }
      }
      // Only prepare merge indexes and quality JSON after directory acquisition
      // has actually reached the probe/commit path. Acquisition-only and
      // deferred-viewing slices return above without rebuilding these structures.
      final long mergePreparationStartedAt=android.os.SystemClock.elapsedRealtime();
      // Only non-final large initial batches with a complete durable identity
      // index and known category mask may use a sparse working set. The final
      // page must keep the full ranking and publication path.
      final String initialRevision=initialDiscoveryChain?
          com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext()).appStateDao()
              .getValue(com.wodedianshi.tv.db.RoomCatalogueBuildStore.STAGING_REVISION_KEY):null;
      // The source owner is exclusive; completion flags cannot change before the
      // staging transaction. Overlay this slice's feed outcomes on admission's
      // Room snapshot once instead of rereading the same flags up to three times.
      final boolean allFeedsInspectedThisSlice=initialDiscoveryChain
          &&areAllDiscoveryFeedsComplete(inspectedFeeds,feedBatches);
      final boolean initialIndexEligible=initialDiscoveryChain&&FullDiscoveryProgressPolicy.mayUseIndexedInitialRows(
          previousStagedSize,roomInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,-1),
          allFeedsInspectedThisSlice);
      if(initialIndexEligible&&!slowPlayback&&!com.wodedianshi.tv.db.RoomCatalogueBuildStore.initialIndexReady(
          getApplicationContext(),previousStagedSize,initialRevision))
        com.wodedianshi.tv.db.RoomCatalogueBuildStore.backfillInitialIndex(
            getApplicationContext(),previousStagedSize,initialRevision,pauseRequested);
      final boolean initialIndexed=initialIndexEligible
          &&com.wodedianshi.tv.db.RoomCatalogueBuildStore.initialIndexReady(
              getApplicationContext(),previousStagedSize,initialRevision);
      if(initialDiscoveryChain){
        saved=initialIndexed?new JSONArray():takeCommittedStaging();
        if(!initialIndexed&&saved.length()!=previousStagedSize)
          throw new IllegalStateException("Initial staging count changed during acquisition");
      }
      IdentityHashMap<JSONObject,Integer> stagedPositions=new IdentityHashMap<>();
      HashSet<Integer> touchedStagedRows=new HashSet<>();
      LinkedHashMap<String,JSONObject> byIdentity=new LinkedHashMap<>();
      final String scoresKey=manualRefresh?com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_SCORES_KEY:"source_probe_scores_v1";
      final String streaksKey=manualRefresh?com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_STREAKS_KEY:"source_probe_failure_streaks_v1";
      final boolean publishedQualityAvailable=!manualRefresh&&com.wodedianshi.tv.db.ManualProbeQualityStore.hasPublished(getApplicationContext());
      final boolean indexedPublished=!initialDiscoveryChain&&publishedQualityAvailable;
      // All initial rebuilds use private indexed quality. Missing private
      // keys inherit authoritative published evidence on demand, not a copy.
      final boolean indexedInitial=initialDiscoveryChain;
      if(indexedInitial&&!com.wodedianshi.tv.db.ManualProbeQualityStore.hasInitial(getApplicationContext()))
        com.wodedianshi.tv.db.ManualProbeQualityStore.ensureInitial(getApplicationContext(),
            scoresKey,streaksKey,pauseRequested);
      final boolean indexedQuality=manualRefresh||indexedPublished||indexedInitial;
      final String qualityOwner=manualRefresh?manualId:indexedInitial?
          com.wodedianshi.tv.db.ManualProbeQualityStore.INITIAL_OWNER:com.wodedianshi.tv.db.ManualProbeQualityStore.PUBLISHED_OWNER;
      JSONObject measuredScores=new JSONObject(indexedQuality?"{}":roomString(scoresKey,"{}"));
      // A single failed probe merely reduces its score. It cannot delete an
      // existing saved backup; three independent failures are needed.
      JSONObject probeFailureStreaks=new JSONObject(indexedQuality?"{}":roomString(streaksKey,"{}"));
      HashSet<String> manualQualityLoaded=new HashSet<>(),manualQualityDirty=new HashSet<>();
      JSONObject observedQoe=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_qoe_v1","{}"));
      // A failed/empty initial page cannot merge any new identities. Final
      // ranking and progress still operate on the authoritative staging rows.
      for(int i=0;(!initialDiscoveryChain||!candidates.isEmpty())&&i<saved.length();i++){
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        JSONObject c=saved.getJSONObject(i);
        if(initialDiscoveryChain||manualRefresh)stagedPositions.put(c,i);
        byIdentity.put(identity(c.optString("name"),c.optString("group"),
            c.optString("url"),c.optString("tvgId"),c.optString("tvgName")),c);
      }
      if(initialDiscoveryChain)android.util.Log.i("MyTVSourceDiscovery",
          "merge_preparation_ms="+(android.os.SystemClock.elapsedRealtime()-mergePreparationStartedAt)
              +" staged_rows="+saved.length()+" indexed_identities="+byIdentity.size());
      if(initialIndexed){
        LinkedHashSet<String> identities=new LinkedHashSet<>();
        for(Entry entry:candidates)if(!entry.savedSource)
          identities.add(entry.mergeIdentity());
        for(com.wodedianshi.tv.db.CatalogueBuildRowEntity entity:
            com.wodedianshi.tv.db.RoomCatalogueBuildStore.readInitialByIdentity(getApplicationContext(),previousStagedSize,initialRevision,identities,pauseRequested)){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          JSONObject row=new JSONObject(entity.recordJson);
          String key=identity(row.optString("name"),row.optString("group"),row.optString("url"),row.optString("tvgId"),row.optString("tvgName"));
          if(!entity.mergeIdentity.equals(key)||!identities.contains(key))
            throw new IllegalStateException("Stale staging identity index");
          stagedPositions.put(row,saved.length());indexedPositions.add(entity.sortOrder);
          saved.put(row);byIdentity.put(key,row);
        }
      }
      if(indexedInitial){
        // Coverage ordering and committed-route reuse must see the same
        // authoritative scores BEFORE page admission/fingerprint selection.
        LinkedHashSet<String> needed=new LinkedHashSet<>();
        Set<JSONObject> collected=Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());
        for(Entry entry:candidates){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          needed.add(entry.url);
          collectQualityUrlsOnce(needed,collected,byIdentity.get(entry.mergeIdentity()));
        }
        com.wodedianshi.tv.db.ManualProbeQualityStore.loadUrls(getApplicationContext(),qualityOwner,needed,
            manualQualityLoaded,measuredScores,probeFailureStreaks,pauseRequested);
      }
      int savedScanStart=manualRefresh?0:SourceSelectionPolicy.nextScanStart(savedPool.size(),position->{
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        return savedPool.get(position).url;
      },roomString("source_saved_scan_after_key",""),roomInt("source_saved_scan_cursor",0));
      // During a full rebuild every probe slot belongs to newly discovered feed
      // candidates. Staged rows already retain their measured scores; repeatedly
      // health-checking them here would starve unseen entries and slow completion.
      int savedWindow=initialDiscoveryChain?0:manualRefresh?
          savedPool.size():
          Math.min(MAX_PROBES_PER_RUN/2,savedPool.size());
      LinkedHashSet<String> provisionalKeys=new LinkedHashSet<>();
      if(!manualRefresh){
        JSONArray pendingProvisional=new JSONArray(roomString(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_KEY,"[]"));
        for(int i=0;i<pendingProvisional.length();i++)
          SourceUpdateSchedulePolicy.rememberProvisional(provisionalKeys,pendingProvisional.optString(i,""));
      }
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
      // Coverage ordering uses only the committed staging at the start of a
      // new page. Store the mask beside the existing fingerprint/cursor so
      // playback, restart and exact replay cannot change positional identity.
      String coverageOrder="";
      if(initialDiscoveryChain&&pageFeed>=0&&!candidates.isEmpty()){
        String rawFingerprint=discoveryFingerprint;
        long covered=DiscoveryBatchPolicy.readCoverageMask(
            roomString(batchPrefix+"coverage_order",""),rawFingerprint,candidates.size());
        if(covered<0L&&!rawFingerprint.equals(previousDiscoveryFingerprint)){
          // A staged row alone is not evidence of a usable route.
          IdentityHashMap<JSONObject,Boolean> coverageEvidence=new IdentityHashMap<>();
          covered=DiscoveryBatchPolicy.coveredPositions(candidates,entry->{
            SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
            JSONObject row=byIdentity.get(entry.mergeIdentity());
            return row!=null&&coverageEvidence.computeIfAbsent(row,
                key->hasPositiveRetainedCoverage(key,measuredScores,provisionalKeys));
          });
        }
        if(covered>=0L){
          coverageOrder=rawFingerprint+":"+covered;
          final int coverageFeed=pageFeed;
          DiscoveryBatchPolicy.prioritizeUncovered(candidates,covered,
              entry->ChinaDiscoveryCandidatePolicy.initialCoveragePriority(coverageFeed,
                  entry.scope,entry.name,entry.group,entry.tvgName,entry.tvgId));
          discoveryFingerprint=CatalogueFingerprint.sha256(coverageOrder);
        }
      }
      int initialDiscoveryScanned=DiscoveryBatchPolicy.scannedForBatch(
          discoveryFingerprint,previousDiscoveryFingerprint,previousScanned,candidates.size());
      int transientRecheck=initialDiscoveryChain&&pageFeed>=0
          ?DiscoveryBatchPolicy.resumeTransientRecheck(discoveryFingerprint.equals(previousDiscoveryFingerprint),
              roomInt(DiscoveryBatchPolicy.initialTransientRecheckKey(pageFeed),DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK),
              initialDiscoveryScanned)
          :DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK;
      long replayMask=initialDiscoveryChain&&pageFeed>=0
          ?DiscoveryBatchPolicy.resumeReplayMask(roomString(DiscoveryBatchPolicy.initialReplayMaskKey(pageFeed),""),
              transientRecheck,candidates.size()):0L;
      // Resume by exact position inside an unchanged batch. URL-based cursors are
      // unsafe because distinct channel identities can share one endpoint; choosing
      // the last duplicate URL can skip entries that were not yet scanned.
      int discoveryScanStart=DiscoveryBatchPolicy.startIndex(
          initialDiscoveryScanned,candidates.size());
      final int runProbeLimit=manualRefresh?DiscoveryBatchPolicy.MANUAL_PAGE_SIZE+savedWindow:
          initialDiscoveryChain?DiscoveryBatchPolicy.firstWatchProbeLimit(
              slowPlayback,previousStagedSize,roomInt("source_initial_progress_checked_urls",0)):
          DiscoveryBatchPolicy.probeLimit(false,slowPlayback,MAX_PROBES_PER_RUN);
      int discoveryWindow=DiscoveryBatchPolicy.discoveryPositionWindow(
          earlyFirstWatch,slowPlayback,DiscoveryBatchPolicy.remaining(
              initialDiscoveryScanned,candidates.size()),runProbeLimit-savedWindow);
      Set<Entry> priorityRechecks=Collections.newSetFromMap(new IdentityHashMap<Entry,Boolean>());
      ArrayList<Entry> priorityOrder=new ArrayList<>();
      if(!initialDiscoveryChain&&!manualRefresh&&!foregroundAtStart&&!provisionalKeys.isEmpty()){
        for(int position:SourceUpdateSchedulePolicy.provisionalRecheckPositions(savedPool.size(),positionIndex->{
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          return scoreKey(savedPool.get(positionIndex).url);
        },savedScanStart,savedWindow,provisionalKeys)){
          Entry entry=savedPool.get(position);
          priorityRechecks.add(entry);priorityOrder.add(entry);
        }
      }
      ArrayList<Entry> probeCandidates=new ArrayList<>(savedWindow+discoveryWindow);
      int savedIndex=0,discoveryIndex=0;
      while(savedIndex<savedWindow||discoveryIndex<discoveryWindow){
        if(discoveryIndex<discoveryWindow){
          int position=(discoveryScanStart+discoveryIndex)%candidates.size();
          Entry e=candidates.get(position);
          e.skipReplay=DiscoveryBatchPolicy.skipReplayPosition(transientRecheck,replayMask,position);
          if(initialDiscoveryChain&&transientRecheck!=DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED&&!e.skipReplay){
            JSONObject staged=byIdentity.get(e.mergeIdentity());
            e.alreadyStaged=mayReuseRetainedPreflight(measuredScores,provisionalKeys,e.url)
                &&hasCommittedRouteEvidence(staged,e,canonicalDisplayName(e));
          }
          probeCandidates.add(e);discoveryIndex++;
        }
        if(savedIndex<savedWindow){
          // Keep the normal saved cursor contiguous; prioritized entries consume
          // only reserved slots and do not advance that cursor.
          Entry e=savedIndex<priorityOrder.size()?priorityOrder.get(savedIndex):
              savedPool.get((savedScanStart+savedIndex-priorityOrder.size())%savedPool.size());
          probeCandidates.add(e);savedIndex++;
        }
      }
      // Fix admission for the entire slice before any executor submission.
      // Reserve raw positions, not distinct endpoints, because aliases have
      // separate persistent quality keys. Full detection still runs when full.
      allowProvisionalScreen=initialDiscoveryChain&&!slowPlayback
          &&SourceUpdateSchedulePolicy.permitsProvisionalScreen(provisionalKeys.size(),probeCandidates.size());
      if(manualIndexed){
        if(manualIndexed&&manualSavedOnly)throw new IllegalStateException("Indexed discovery cursor changed");
        LinkedHashSet<String> identities=new LinkedHashSet<>();
        for(Entry entry:probeCandidates)if(!entry.savedSource)
          identities.add(entry.mergeIdentity());
        for(com.wodedianshi.tv.db.CatalogueBuildRowEntity entity:
            com.wodedianshi.tv.db.RoomCatalogueBuildStore.readManualByIdentity(getApplicationContext(),manualId,identities,pauseRequested)){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          JSONObject row=new JSONObject(entity.recordJson);
          String key=identity(row.optString("name"),row.optString("group"),row.optString("url"),row.optString("tvgId"),row.optString("tvgName"));
          if(!entity.mergeIdentity.equals(key)||!identities.contains(key))
            throw new IllegalStateException("Stale staging identity index");
          stagedPositions.put(row,saved.length());indexedPositions.add(entity.sortOrder);
          saved.put(row);byIdentity.put(key,row);
        }
      }
      if(indexedQuality&&!indexedInitial){
        LinkedHashSet<String> needed=new LinkedHashSet<>();
        // Routine still re-ranks the published snapshot; include every
        // retained URL rather than substituting the capped compatibility view.
        if(indexedPublished)for(int i=0;i<saved.length();i++){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          collectQualityUrls(needed,saved.getJSONObject(i));
        }
        // Routine already collected every published row above. Manual staging
        // needs only each distinct target row plus every exact candidate URL.
        Set<JSONObject> collected=indexedPublished?null:
            Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());
        for(Entry entry:probeCandidates){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          needed.add(entry.url);
          if(collected!=null&&!entry.savedSource)
            collectQualityUrlsOnce(needed,collected,byIdentity.get(entry.mergeIdentity()));
        }
        com.wodedianshi.tv.db.ManualProbeQualityStore.loadUrls(getApplicationContext(),qualityOwner,needed,
            manualQualityLoaded,measuredScores,probeFailureStreaks,pauseRequested);
      }
      // Use independent rotating cursors and a fixed per-run budget for discovery
      // and saved-source health checks. A moving 5,000-item window combined with one
      // cursor could skip most saved URLs forever as the window changed between runs.
      final long probeStartedAt=android.os.SystemClock.elapsedRealtime();
      long probeDeadline=probeStartedAt+PROBE_STAGE_DEADLINE_MS;
      final boolean boundedAddressBudget=SourceProbeTimePolicy.usesBoundedAddressBudget(
          initialDiscoveryChain,manualRefresh,slowPlayback);
      int savedScanned=0,discoveryScanned=0,reusedPreflights=0,initialTimeouts=0;
      int originDeferred=0,provisionalAccepted=0,retainedDuplicates=0;
      int uncertainSocket=0,uncertainTransport=0,uncertainBudget=0,uncertainMedia=0;
      int uncertainPaced=0;
      long consumedSampleRequests=0L,consumedSampleBytes=0L;
      boolean idleWaveTimedOut=false;
      boolean viewingDeferred=false;
      final int priorChinaValidated=initialDiscoveryChain
          ?roomInt("source_initial_progress_china_validated_urls",0):0;
      final int priorCoreMask=initialDiscoveryChain&&!slowPlayback&&previousStagedSize<=512
          ?roomInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,-1):-1;
      int observedCoreMask=priorCoreMask;
      HashSet<String> visitedUrls=new HashSet<>();
      HashMap<String,Integer> inconclusiveAttempts=new HashMap<>();
      HashSet<String> savedStreakUpdatedThisRun=new HashSet<>();
      HashMap<String,ProbeResult> measuredByEndpoint=new HashMap<>();
      for(int offset=0;offset<probeCandidates.size();offset++){
        if(pauseRequested.getAsBoolean()){
          idleWave.close();
          saveStatus(prefs,"用户使用频道时暂停后续线路检测",0);
          return deferredViewingResult(manual,initialDiscoveryChain);
        }
        // Once the first mainland candidate has been merged, do not start
        // another wave solely to fill the six-request budget. Finish all
        // already admitted requests before committing the exact prefix.
        if(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(earlyFirstWatch,slowPlayback,
            priorChinaValidated,chinaPreflightsValid,saved.length(),idleWave.hasPending()))break;
        if(initialDiscoveryChain&&DiscoveryBatchPolicy.yieldForNewCorePreview(slowPlayback,
            saved.length(),priorCoreMask,observedCoreMask,
            (int)Math.min(Integer.MAX_VALUE,(long)priorChinaValidated+chinaPreflightsValid),
            idleWave.hasPending()))break;
        Entry e=probeCandidates.get(offset);
        // A completed speculative result can still be consumed after the batch
        // deadline, but no fresh request may begin after it.
        if(!DiscoveryBatchPolicy.canConsumeProbePosition(initialDiscoveryChain,slowPlayback,
            checked,runProbeLimit,false)||isStopped())break;
        if(android.os.SystemClock.elapsedRealtime()>=probeDeadline
            &&!DiscoveryBatchPolicy.canConsumeAfterProbeDeadline(initialDiscoveryChain,slowPlayback,
                idleWave.has(e.endpoint()),e.skipReplay||e.alreadyStaged
                    ||!(e.url.startsWith("http://")||e.url.startsWith("https://"))
                    ||measuredByEndpoint.containsKey(e.endpoint())))break;
        boolean priorityRecheck=priorityRechecks.contains(e);
        if(e.savedSource){if(!priorityRecheck)savedScanned++;}else discoveryScanned++;
        if(e.skipReplay)continue;
        if(e.alreadyStaged){retainedDuplicates++;continue;}
        if(!e.url.startsWith("http://")&&!e.url.startsWith("https://"))continue;
        String endpoint=e.endpoint();
        String exactScoreKey=null,oldScoreKey=null;
        ProbeResult measured=measuredByEndpoint.get(endpoint);
        if(measured==null){
          if(!visitedUrls.add(endpoint))continue;
          boolean reuseRecent=initialDiscoveryChain||manualRefresh;
          String cacheKey=reuseRecent?endpoint:"";
          long clock=android.os.SystemClock.elapsedRealtime();
          RecentProbe cached=reuseRecent?reusableInitialProbe(cacheKey,slowPlayback,clock):null;
          boolean reuse=cached!=null;
          boolean allowOriginDeferral=InitialOriginCooldownPolicy.mayDefer(
              initialDiscoveryChain,slowPlayback,preferCoreCoverage,e.savedSource,
              transientRecheck==DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED);
          String origin=initialDiscoveryChain&&!slowPlayback
              ?e.origin():"";
          // Do not abandon a launched probe or discard a recent validation.
          // A deferred position must get its ordinary idle replay before EOF.
          if(allowOriginDeferral&&!reuse&&!idleWave.has(endpoint)
              &&INITIAL_ORIGINS.cooling(origin,clock)){
            if(pageFeed<0)throw new IllegalStateException("Missing origin recheck page");
            replayMask=DiscoveryBatchPolicy.noteReplayPosition(replayMask,initialDiscoveryScanned+discoveryScanned-1,candidates.size());
            transientRecheck=DiscoveryBatchPolicy.noteTransientRecheck(transientRecheck,initialDiscoveryScanned+discoveryScanned-1);
            originDeferred++;
            continue;
          }
          // The early idle position window may include cached observations after
          // its network budget is full. Stop BEFORE the first uncached position,
          // leaving its exact cursor for the next worker. Never launch more probes.
          if(!DiscoveryBatchPolicy.canConsumeProbePosition(initialDiscoveryChain,slowPlayback,
              checked,runProbeLimit,!reuse)){
            if(e.savedSource){if(!priorityRecheck)savedScanned--;}else discoveryScanned--;
            break;
          }
          long addressDeadline=probeDeadline;
          long measuredAt=clock;
          boolean prefetched=idleWave.has(endpoint);
          boolean parallelOutcome=false;
          if(boundedAddressBudget&&!reuse&&!prefetched){
            addressDeadline=SourceProbeTimePolicy.initialAddressDeadline(probeDeadline,clock);
            if(addressDeadline==0L){
              // Preserve the exact saved or discovery cursor at the stage tail.
              if(e.savedSource){if(!priorityRecheck)savedScanned--;}else discoveryScanned--;
              break;
            }
          }
          if(reuse){
            measured=cached.result;
            reusedPreflights++;
          }else{
            checked++;
            if(IdleProbeWavePolicy.permitsParallel(initialDiscoveryChain,manualRefresh,slowPlayback)
                &&(prefetched||idleWave.start(probeCandidates,offset,measuredByEndpoint,
                    visitedUrls,addressDeadline,probeDeadline,initialDiscoveryChain,
                    DiscoveryBatchPolicy.waveRequestAllowance(runProbeLimit,checked),
                    DiscoveryBatchPolicy.permitsRollingInitial(initialDiscoveryChain,earlyFirstWatch,
                        preferCoreCoverage,previousStagedSize,priorCoreMask)))){
              noteInitialStage("空闲并发检测 · 本组待合并 "+idleWave.pending.size()+" 条 · 本批第 "+checked+" 条（尚未提交）");
              TimedProbe outcome;
              try{outcome=idleWave.take(endpoint,probeDeadline);}
              catch(java.util.concurrent.TimeoutException timeout){
                if(!initialDiscoveryChain||slowPlayback)throw timeout;
                // The current position has no consumed result. Preserve the
                // previously processed contiguous prefix, not this position.
                if(e.savedSource){if(!priorityRecheck)savedScanned--;}else discoveryScanned--;
                idleWaveTimedOut=true;
                idleWave.close();
                if(!awaitRetiringProbePool())throw timeout;
                SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
                break;
              }
              measured=outcome.result;addressDeadline=outcome.deadline;measuredAt=outcome.finishedAt;
              parallelOutcome=true;
            }else{
              // A rejected wave may have spent the remaining address budget
              // preparing candidates. Keep this exact position for the next slice.
              if(initialDiscoveryChain&&!slowPlayback
                  &&android.os.SystemClock.elapsedRealtime()>=addressDeadline){
                checked--;
                if(e.savedSource){if(!priorityRecheck)savedScanned--;}else discoveryScanned--;
                break;
              }
              idleWave.serialProbes++;
              noteInitialStage((slowPlayback?"稳定直播低速串行":"空闲串行检测")+" · 本批第 "+checked+" 条（尚未提交）");
              measured=probe(e.url,addressDeadline);
              measuredAt=android.os.SystemClock.elapsedRealtime();
            }
          }
          // Count only fresh results actually consumed by this ordered owner.
          // Speculative abandoned work and cached observations are not included.
          if(!reuse){
            consumedSampleRequests+=measured.sampleRequests;
            consumedSampleBytes+=measured.sampleBytes;
          }
          // A completed positive observation can be reused on the next slice
          // even if a new user action prevents this slice from committing.
          // Never renew the timestamp of a reused result.
          if(reuseRecent&&!reuse)
            retainCompletedInitialSuccess(cacheKey,measured,measuredAt,addressDeadline,probeDeadline);
          if(pauseRequested.getAsBoolean()||Thread.currentThread().isInterrupted()){
            idleWave.close();
            saveStatus(prefs,"频道播放优先，线路检测已暂停，不记录失败",0);
            return deferredViewingResult(manual,initialDiscoveryChain);
          }
          if(parallelOutcome?measuredAt>=probeDeadline:probeDeadlineReached(probeDeadline)){
            // A source interrupted by the global deadline is inconclusive, not a failed source.
            if(e.savedSource){if(!priorityRecheck)savedScanned--;}else discoveryScanned--;
            break;
          }
          if(initialDiscoveryChain&&!slowPlayback&&!reuse){
            measured.publishOriginHealth(INITIAL_ORIGINS,origin,endpoint,measuredAt);
            INITIAL_NETWORK.observe(origin,measuredAt,
                measured.firstRequestFailed,measured.firstRequestReached);
          }
          if(!reuse&&(measured.inconclusive()||SourceQualityScorer.inconclusivePacedProbe(measured.paced,measured.valid)||(boundedAddressBudget
              &&SourceProbeTimePolicy.initialProbeInconclusive(false,measuredAt,addressDeadline)))){
            // Timeouts, transient network failures and unsuccessful paced validations are inconclusive: do not promote,
            // penalize, cache or increment the retirement streak of a saved route.
            initialTimeouts++;
            // Mutually exclusive primary reasons for consumed inconclusive results.
            // Cancellation and deferral are not media failures or socket timeouts.
            switch(measured.uncertainReason(boundedAddressBudget&&measuredAt>=addressDeadline)){
              case SOCKET: uncertainSocket++;break;
              case TRANSPORT: uncertainTransport++;break;
              case BUDGET: uncertainBudget++;break;
              case MEDIA: uncertainMedia++;break;
              case PACED: uncertainPaced++;break;
            }
            if(DiscoveryBatchPolicy.holdInconclusiveCandidate(initialDiscoveryChain,slowPlayback,e.savedSource)){
              if(pageFeed<0)throw new IllegalStateException("Missing initial recheck page");
              viewingDeferred=true;
              if(transientRecheck==DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED){
                // Defensive: a rewound replay must never be consumed in viewing.
                discoveryScanned--;
                update.putBoolean(DiscoveryBatchPolicy.initialIdleRecheckKey(pageFeed),true);
                break;
              }
              // Save the earliest unresolved position with the same immutable
              // page fingerprint, but let other positions have their paced turn.
              // At page end rewind once for idle replay before advancing raw cursor.
              replayMask=DiscoveryBatchPolicy.noteReplayPosition(replayMask,initialDiscoveryScanned+discoveryScanned-1,candidates.size());
              transientRecheck=DiscoveryBatchPolicy.noteTransientRecheck(transientRecheck,initialDiscoveryScanned+discoveryScanned-1);
            }
            // Preserve the existing transient failure replay. Also allow a timed-out
            // clearly mainland candidate to recover a missing early core category.
            // Do not expand ordinary timeout retries across the full catalogue.
            if(initialDiscoveryChain&&pageFeed>=0&&!slowPlayback&&!e.savedSource
                &&transientRecheck!=DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED){
              boolean coreTimeout=measured.recoverableCoreWindow()&&preferCoreCoverage
                  &&ChinaDiscoveryCandidatePolicy.isClearlyMainland(e.scope,e.name,e.group,e.tvgName,e.tvgId)
                  &&DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,saved.length(),
                      (long)initialCheckedAtStart+checked,observedCoreMask,
                      FullDiscoveryProgressPolicy.coreCategoryMask(e.name,e.group,e.tvgName,e.tvgId,"china".equals(e.scope)));
              if(measured.transientNetworkFailure||coreTimeout){
                replayMask=DiscoveryBatchPolicy.noteReplayPosition(replayMask,initialDiscoveryScanned+discoveryScanned-1,candidates.size());
                transientRecheck=DiscoveryBatchPolicy.noteTransientRecheck(transientRecheck,initialDiscoveryScanned+discoveryScanned-1);
              }
            }
            // An unpaced candidate had its probe opportunity; later maintenance may retry.
            int attempts=inconclusiveAttempts.getOrDefault(endpoint,0)+1;
            inconclusiveAttempts.put(endpoint,attempts);
            if(priorityRecheck)SourceUpdateSchedulePolicy.rememberProvisional(provisionalKeys,scoreKey(e.url));
            // Ordinary idle first-pass duplicates do not immediately repeat a
            // socket timeout. Keep the extra retry for core coverage, transient
            // transport failures, paced viewing, and the designated idle replay.
            // Later slices may retry; do not cache or penalize this observation.
            boolean ordinaryInitialIdleTimeout=initialDiscoveryChain&&!slowPlayback
                &&!e.savedSource&&!preferCoreCoverage
                &&transientRecheck!=DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED
                &&measured.socketTimedOut&&!measured.transientNetworkFailure;
            if(!measured.deterministicEvidenceLimit()
                &&SourceProbeTimePolicy.retryInconclusiveWithinSlice(attempts,ordinaryInitialIdleTimeout))
              visitedUrls.remove(endpoint);
            continue;
          }
          // Fresh positive initial results were already retained before the
          // cancellation gate. Here only cache a definitive negative result.
          if(initialDiscoveryChain&&!reuse&&!measured.valid)
            rememberInitialProbe(cacheKey,measured,measuredAt);
          measuredByEndpoint.put(endpoint,measured);
          // A previously good source that now fails its probe must lose its old high score.
          // Keep the URL as a fallback, but rank it below sources that still pass validation.
          if(!indexedQuality&&!initialDiscoveryChain){
            exactScoreKey=scoreKey(e.url);oldScoreKey=legacyScoreKey(e.url);
            storePreflightScore(measuredScores,exactScoreKey,oldScoreKey,measured);
          }
        }
        if(!manualRefresh&&((measured.provisional&&measured.valid)||(!measured.paced&&!measured.provisional))){
          if(exactScoreKey==null)exactScoreKey=scoreKey(e.url);
          if(measured.provisional)
            SourceUpdateSchedulePolicy.rememberProvisional(provisionalKeys,exactScoreKey);
          else provisionalKeys.remove(exactScoreKey);
        }
        if(indexedQuality||initialDiscoveryChain){
          // One conclusive endpoint observation can be consumed by distinct
          // exact URLs. Persist each association's own score in both initial
          // staging and indexed quality, without issuing another probe.
          if(exactScoreKey==null)exactScoreKey=scoreKey(e.url);
          if(oldScoreKey==null)oldScoreKey=legacyScoreKey(e.url);
          storePreflightScore(measuredScores,exactScoreKey,oldScoreKey,measured);
          if(indexedQuality){
            manualQualityDirty.add(exactScoreKey);manualQualityDirty.add(oldScoreKey);
          }
        }
        if(e.savedSource&&savedStreakUpdatedThisRun.add(endpoint)){
          String streakKey=exactScoreKey==null?scoreKey(e.url):exactScoreKey;
          int streak=SourceMaintenanceRetentionPolicy.nextFailureStreak(
              probeFailureStreaks.optInt(streakKey,0),measured.valid);
          if(streak==0)probeFailureStreaks.remove(streakKey);
          else probeFailureStreaks.put(streakKey,streak);
          if(indexedQuality)manualQualityDirty.add(streakKey);
        }
        // A queued old endpoint may have been retired after enqueue. Health
        // checks update its score/streak but cannot resurrect it as a discovery.
        if(manualRefresh&&e.savedSource)continue;
        if(measured.valid&&(!e.savedSource||initialDiscoveryChain)){
          discoveryProbesValid++;
          if(measured.provisional)provisionalAccepted++;
          if(initialDiscoveryChain&&ChinaDiscoveryCandidatePolicy.isClearlyMainland(
              e.scope,e.name,e.group,e.tvgName,e.tvgId))chinaPreflightsValid++;
        }
        // Whether this is the first full build or routine discovery, a
        // newly discovered URL must pass the media preflight before it may
        // become a published channel or a new backup. Failed saved sources
        // remain protected by the independent Room retention policy above.
        if(!SourceDiscoveryAcceptancePolicy.mayPromoteNewCandidate(measured.valid))
          continue;
        String key=e.mergeIdentity();JSONObject target=byIdentity.get(key);
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
          if(initialDiscoveryChain||manualRefresh)stagedPositions.put(target,saved.length());
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
          java.util.Comparator<String> bestFirst=rankingComparator(measuredScores,observedQoe);
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
            target.put("activeSource",SourceRankingRetentionPolicy.restoreSelectedIndex(retainedUrls,activeUrl));
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
        if(initialDiscoveryChain||manualRefresh)touchedStagedRows.add(stagedPositions.get(target));
        if(priorCoreMask>=0&&observedCoreMask!=FullDiscoveryProgressPolicy.ALL_CORE
            &&saved.length()<=512){
          observedCoreMask|=FullDiscoveryProgressPolicy.coreCategoryMask(
              target.optString("name"),target.optString("group"),target.optString("tvgName"),
              target.optString("tvgId"),hasChinaScope(target));
        }
      }
      // Even if every discovery directory is temporarily unavailable, continue
      // rotating through already-saved source URLs. Existing channels still need
      // health checks while public catalogue services are down; retry discovery
      // separately after persisting these local probe results.
      boolean discoveryUnavailable=feedsOk==0;
      // Never leave speculative network work alive while committing a prefix.
      // A missing result at the stage deadline leaves that slice for retry.
      try{idleWave.finishBeforeCommit();}
      catch(java.util.concurrent.TimeoutException timeout){
        if(!initialDiscoveryChain||slowPlayback)throw timeout;
        idleWaveTimedOut=true;
        idleWave.close();
        if(!awaitRetiringProbePool())throw timeout;
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      }
      idleWave.close();
      final long probesFinishedAt=android.os.SystemClock.elapsedRealtime();
      if(initialDiscoveryChain)android.util.Log.i("MyTVSourceDiscovery","network_probes="+checked+" reused_recent="+reusedPreflights+" retained_duplicate="+retainedDuplicates+" inconclusive_total="+initialTimeouts+" socket_timeout="+uncertainSocket+" transport="+uncertainTransport+" address_budget="+uncertainBudget+" media_uncertain="+uncertainMedia+" paced_uncertain="+uncertainPaced+" origin_deferred="+originDeferred+" provisional_accepted="+provisionalAccepted+" consumed_sample_requests="+consumedSampleRequests+" consumed_sample_bytes="+consumedSampleBytes+" wave_timeout="+idleWaveTimedOut+" network_ceiling="+INITIAL_NETWORK.limit(IdleProbeWavePolicy.MAX_CONCURRENT,probesFinishedAt)+" preparation_ms="+(probeStartedAt-sliceStartedAt)+" probe_and_merge_ms="+(probesFinishedAt-probeStartedAt)+" viewing="+slowPlayback+" scanned_candidates="+discoveryScanned+" parallel_launched="+idleWave.launched);
      // Keep each channel's best distinct sources together, ranked by measured
      // reachability/speed/resolution. Generic names remain host-separated; recognized
      // CCTV and satellite channels merge across source hosts.
      // Initial staging is append-only and touched rows alone need sorting.
      // A final idle pass re-ranks the complete snapshot before publication.
      int nextBatchScanned=Math.min(candidates.size(),initialDiscoveryScanned+discoveryScanned);
      if(initialDiscoveryChain&&pageFeed>=0&&DiscoveryBatchPolicy.shouldReplayTransientSuffix(
          transientRecheck,nextBatchScanned,candidates.size())){
        nextBatchScanned=transientRecheck;
        replayMask=DiscoveryBatchPolicy.includeReplayAliases(replayMask,candidates,Entry::endpoint);
        // An earlier alias may also have lacked conclusive evidence.
        if(replayMask>0L)nextBatchScanned=Math.min(nextBatchScanned,Long.numberOfTrailingZeros(replayMask));
        transientRecheck=DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED;
        // Recheck only in idle; never loop on an unavailable server during playback.
        update.putBoolean(DiscoveryBatchPolicy.initialIdleRecheckKey(pageFeed),true)
            .putLong(DiscoveryBatchPolicy.initialTransientRecheckAtKey(pageFeed),System.currentTimeMillis());
        if(slowPlayback)viewingDeferred=true;
        android.util.Log.i("MyTVSourceDiscovery","transient_page_recheck feed="+pageFeed
            +" resume_position="+nextBatchScanned);
      }
      boolean finalRanking=initialDiscoveryChain&&nextBatchScanned>=candidates.size()
          &&allFeedsInspectedThisSlice&&!slowPlayback;
      if(initialIndexed&&finalRanking)throw new IllegalStateException("Sparse staging cannot finalize a full snapshot");
      final int stagedSizeAfterProbe=initialIndexed
          ?FullDiscoveryProgressPolicy.indexedInitialRowCount(previousStagedSize,indexedPositions.size(),saved.length()):saved.length();
      // The manual directory is private until its ready marker is committed.
      // During collection rank only changed rows; health-only probes need no
      // full directory sort. A final pass uses the latest saved scoring state.
      Iterable<Integer> rankingRows=(manualRefresh||initialDiscoveryChain&&!finalRanking)?touchedStagedRows:
          allRowIndices(saved);
      if(indexedInitial&&finalRanking){
        LinkedHashSet<String> needed=new LinkedHashSet<>();
        for(int i=0;i<saved.length();i++){
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          collectQualityUrls(needed,saved.optJSONObject(i));
        }
        com.wodedianshi.tv.db.ManualProbeQualityStore.loadUrls(getApplicationContext(),qualityOwner,needed,
            manualQualityLoaded,measuredScores,probeFailureStreaks,pauseRequested);
      }
      rankCatalogueRows(saved,rankingRows,measuredScores,observedQoe);
      // Persist measured quality independently from URL labels so the player can rank
      // candidates by reachability, estimated speed and manifest resolution. This is
      // a preflight score, not a guarantee of audio/video playback.
      // Capture dirty rows BEFORE trimming the legacy compatibility cache.
      if(indexedPublished)update.putPublishedQuality(
          com.wodedianshi.tv.db.ManualProbeQualityStore.changes(qualityOwner,manualQualityDirty,measuredScores,probeFailureStreaks),pauseRequested);
      // Only legacy published readers still require a compatibility mirror.
      // After the first indexed preview/final commit, Room rows are the
      // authority; do not parse, trim or serialize the old JSON each slice.
      final boolean maintainLegacyQuality=SourceUpdateSchedulePolicy.maintainLegacyQualityMirror(
          manualRefresh,publishedQualityAvailable);
      if(maintainLegacyQuality){
        JSONObject compatibleScores=indexedInitial?
            mergeCompatibilityQuality(new JSONObject(roomString(scoresKey,"{}")),measuredScores,manualQualityDirty):measuredScores;
        JSONObject compatibleStreaks=indexedInitial?
            mergeCompatibilityQuality(new JSONObject(roomString(streaksKey,"{}")),probeFailureStreaks,manualQualityDirty):probeFailureStreaks;
        trimProbeScores(compatibleScores,savedPool,probeCandidates,initialDiscoveryChain,provisionalKeys);
        trimProbeScores(compatibleStreaks,savedPool,probeCandidates,false,Collections.emptySet());
        update.putString(scoresKey,compatibleScores.toString()).putString(streaksKey,compatibleStreaks.toString());
      }
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
      boolean feedBatchComplete=nextBatchScanned>=candidates.size();
      if(initialDiscoveryChain&&!feedBatches.isEmpty()){
        // Rotate even after a time-limited partial page. Its per-feed fingerprint
        // and scanned position are committed with staging below, while the raw
        // feed cursor only advances when the page is complete.
        int attemptedFeed=feedBatches.get(feedBatches.size()-1).index;
        initialNextFeedIndex=preferCoreCoverage
            ?SourceDiscoveryPriorityPolicy.nextAfterFirstWatchTurn(attemptedFeed,feeds.length)
            :InitialFeedRotationPolicy.nextAfterProbeBatch(
                attemptedFeed,initialNextFeedIndex,feeds.length,feedBatchComplete);
      }
      boolean initialFeedsExhausted=initialDiscoveryChain&&feedBatchComplete
          &&allFeedsInspectedThisSlice;
      boolean initialHasValidatedSource=initialDiscoveryChain
          &&(roomBoolean("source_initial_has_validated_source",false)||discoveryProbesValid>0);
      boolean awaitingIdleFinalization=FullDiscoveryProgressPolicy.awaitingIdleFinalization(initialFeedsExhausted,slowPlayback);
      boolean initialDiscoveryComplete=FullDiscoveryProgressPolicy.canPublishInitialSnapshot(
          initialFeedsExhausted,initialHasValidatedSource,slowPlayback);
      // Save the last probed page in staging even while viewing. Only merging
      // the live snapshot (or resetting an unsuccessful build) requires idle.
      JSONArray publication=saved;
      if(initialDiscoveryComplete){
        JSONArray priorRows=new JSONArray();
        roomSnapshot=readPublishedForInitialCommit(priorRows);
        publication=preservePreviouslyPublishedChannels(saved,priorRows);
        int removed=prunePendingForCompletePublication(provisionalKeys,publication,pauseRequested);
        android.util.Log.i("MyTVSourceDiscovery","provisional_publication_pruned="+removed+" pending="+provisionalKeys.size());
      }
      // Prepare the full publication and all progress before opening a write transaction.
      int publishedChannelCount=initialIndexed?stagedSizeAfterProbe:publication.length();
      if(initialDiscoveryComplete&&saved.length()==0)
        throw new IllegalStateException("Full rebuild has no validated channels");
      if(pauseRequested.getAsBoolean())
        return deferredViewingResult(manual,initialDiscoveryChain);
      update.putInt("source_scan_cursor",candidates.isEmpty()?0:(discoveryScanStart+discoveryScanned)%candidates.size())
          .putInt("source_saved_scan_cursor",!manualRefresh&&savedScanned>0&&!savedPool.isEmpty()
              ?(savedScanStart+savedScanned)%savedPool.size():roomInt("source_saved_scan_cursor",0))
          .putString("source_scan_after_key",discoveryScanned>0?SourceSelectionPolicy.lastScannedKey(candidates.size(),position->candidates.get(position).url,discoveryScanStart,discoveryScanned):roomString("source_scan_after_key",""))
          .putString("source_saved_scan_after_key",!manualRefresh&&savedScanned>0?SourceSelectionPolicy.lastScannedKey(savedPool.size(),position->savedPool.get(position).url,savedScanStart,savedScanned):roomString("source_saved_scan_after_key",""))
          // The exact catalogue snapshot is represented by a compact fingerprint.
          .putString("source_update_time",now())
          .putString("source_update_result",(initialDiscoveryChain
              ?(initialDiscoveryComplete?"全量目录采集完成并已原子发布；":"独立全量目录构建中，保留已发布频道；")
              :"已安全更新当前目录；")+(initialDiscoveryChain?"首次全量重建按批次覆盖全部配置目录；":(foregroundAtStart||isActivityActive(prefs)?"日常更新优先读取中国及港澳台目录，全球目录留待后台运行时补充；":"本轮已检查中国及全球公开目录；"))+(discoveryUnavailable?"公开目录暂不可用，仍完成已保存线路的轮检；":(feedsWithEntries==0?"目录可访问，但没有解析出有效频道条目；":(discoveryProbesValid==0?(initialHasValidatedSource?"本轮新增候选未通过媒体预检，未纳入已验证频道；":"已解析频道条目，但尚无候选线路通过媒体预检；"):"成功读取 "+feedsOk+" 个目录，"+feedsWithEntries+" 个含频道条目，"+discoveryProbesValid+" 条候选通过媒体预检；")))+"初筛 "+checked+" 个候选地址；新增/替换 "+added+" 个备用地址；每频道最多保留 "+MAX_SOURCES_PER_CHANNEL+" 条线路；网络初筛不能保证实际画面与声音，仍需播放器验证；"+(errors.length()>0?"部分目录失败。":""));
      if(!manualRefresh){
        update.putString(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_KEY,new JSONArray(provisionalKeys).toString());
      }
      if(initialDiscoveryChain){
        update.putInt("source_initial_next_feed_index",initialNextFeedIndex)
            .remove(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY);
      }
      // Advance a feed only after every candidate in the current multi-feed batch
      // has had its probe turn. This prevents losing the tail when the probe deadline
      // interrupts a run, and ensures later playlist pages are eventually discovered.
      // Recovery diagnostics use one committed Room read per completed
      // non-initial batch, not a separate query for every configured feed.
      // Keep the baseline shared by warning removal and remaining-count logic.
      Map<String,String> recoveryState=Collections.emptyMap();
      if(!initialDiscoveryChain&&feedBatchComplete){
        ArrayList<String> keys=new ArrayList<>(feeds.length);
        for(int i=0;i<feeds.length;i++)keys.add(initialFeedUnavailableKey(i));
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        recoveryState=readRoomValues(keys);
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      }
      if(feedBatchComplete){
        for(FeedBatch batch:feedBatches){
          if(!batch.success)continue;
          if(initialDiscoveryChain)update.remove(DiscoveryBatchPolicy.initialIdleRecheckKey(batch.index))
              .remove(DiscoveryBatchPolicy.initialTransientRecheckKey(batch.index))
              .remove(DiscoveryBatchPolicy.initialReplayMaskKey(batch.index))
              .remove(DiscoveryBatchPolicy.initialTransientRecheckAtKey(batch.index));
          if(!initialDiscoveryChain && recoveredDirectoryScanned(batch)
              && Boolean.parseBoolean(recoveryState.get(initialFeedUnavailableKey(batch.index)))){
            // The same indexed directory has reached EOF after processing its
            // pages. Do not clear its old warning after only a partial page.
            update.remove(initialFeedUnavailableKey(batch.index))
                .remove(initialFeedFailureCountKey(batch.index))
                .remove(initialFeedFailureReasonKey(batch.index))
                .remove(initialFeedDisplayLabelKey(batch.index));
          }
          if(initialDiscoveryChain&&batch.unavailable){
            String failedPrefix=DiscoveryBatchPolicy.initialBatchPrefix(batch.index);
            update.putBoolean(initialFeedUnavailableKey(batch.index),true)
                .remove(failedPrefix+"fingerprint").remove(failedPrefix+"scanned").remove(failedPrefix+"coverage_order");
          }else if(initialDiscoveryChain){
            update.remove(initialFeedUnavailableKey(batch.index))
                .remove(initialFeedFailureCountKey(batch.index))
                .remove(initialFeedFailureReasonKey(batch.index))
                .remove(initialFeedDisplayLabelKey(batch.index));
          }
          String cursorKey=initialDiscoveryChain?feedCursorKey(batch.index):
              manualRefresh?manualFeedCursorKey(batch.index):routineFeedCursorKey(batch.index);
          if(initialDiscoveryChain&&SourceUpdateSchedulePolicy.initialFeedInspected(
              batch.success,batch.unavailable,batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED)){
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
        update.remove(batchPrefix+"fingerprint").remove(batchPrefix+"scanned").remove(batchPrefix+"coverage_order");
      }else if(candidates.size()>0){
        update.putString(batchPrefix+"fingerprint",discoveryFingerprint)
            .putInt(batchPrefix+"scanned",nextBatchScanned);
        if(!coverageOrder.isEmpty())update.putString(batchPrefix+"coverage_order",coverageOrder);
        if(initialDiscoveryChain&&pageFeed>=0){
          update.putInt(DiscoveryBatchPolicy.initialTransientRecheckKey(pageFeed),transientRecheck)
              .putString(DiscoveryBatchPolicy.initialReplayMaskKey(pageFeed),Long.toString(replayMask));
          // Bind even a partially inspected first page to the exact body/order.
          // The cursor remains at the page start until every candidate is done.
          for(FeedBatch batch:feedBatches)if(batch.index==pageFeed&&batch.success&&!batch.unavailable){
            update.putInt(feedCursorKey(pageFeed),batch.cursor)
                .putString(feedCursorKey(pageFeed)+"_version",batch.version);
            break;
          }
        }
      }
      if(!initialDiscoveryChain&&feedBatchComplete){
        int stillUnavailable=0;
        for(int i=0;i<feeds.length;i++){
          boolean wasUnavailable=Boolean.parseBoolean(recoveryState.get(initialFeedUnavailableKey(i)));
          if(wasUnavailable){
            for(FeedBatch batch:feedBatches){
              if(batch.index==i&&recoveredDirectoryScanned(batch)){wasUnavailable=false;break;}
            }
          }
          if(wasUnavailable)stillUnavailable++;
        }
        update.putInt("source_initial_progress_unavailable_feeds",stillUnavailable);
      }
      int previewLevel=0, previousPreviewLevel=0;
      int previewMilestone=0, previousPreviewMilestone=0;
      int previewCoreMask=0,previousPreviewCoreMask=0;
      if(initialDiscoveryChain){
        // Only count a source feed as done after its entire page was probed and
        // end-of-feed was observed. A successful HTTP response alone is not done.
        // Read the persisted baseline for all progress counters together.
        // The current uncommitted feed outcomes are still overlaid below.
        ArrayList<String> progressKeys=new ArrayList<>(feeds.length*3);
        for(int i=0;i<feeds.length;i++){
          progressKeys.add(initialFeedDoneKey(i));
          progressKeys.add(initialFeedUnavailableKey(i));
          progressKeys.add(initialFeedProgressKey(i));
        }
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        Map<String,String> progressState=readRoomValues(progressKeys);
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        boolean[] finished=new boolean[feeds.length];
        int completedFeeds=0;
        for(int i=0;i<feeds.length;i++)finished[i]=Boolean.parseBoolean(progressState.get(initialFeedDoneKey(i)));
        if(feedBatchComplete){
          for(FeedBatch batch:feedBatches){
            if(batch.index>=0&&batch.index<feeds.length
                &&SourceUpdateSchedulePolicy.initialFeedInspected(
                    batch.success,batch.unavailable,batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED))
              finished[batch.index]=true;
          }
        }
        for(boolean done:finished)if(done)completedFeeds++;
        boolean[] unavailableFlags=new boolean[feeds.length];
        for(int i=0;i<feeds.length;i++)
          unavailableFlags[i]=Boolean.parseBoolean(progressState.get(initialFeedUnavailableKey(i)));
        if(feedBatchComplete){
          for(FeedBatch batch:feedBatches)
            if(batch.index>=0&&batch.index<feeds.length&&batch.success&&batch.unavailable)
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
        for(int i=0;i<feeds.length;i++){
          try{partial[i]=Integer.parseInt(progressState.get(initialFeedProgressKey(i)));}
          catch(NumberFormatException ignored){partial[i]=0;}
        }
        for(FeedBatch batch:feedBatches){
          if(batch.index<0||batch.index>=feeds.length||!batch.success)continue;
          int basis=finished[batch.index]?10000:
              FullDiscoveryProgressPolicy.feedProgress(
                  batch.cursor+(feedBatchComplete?batch.parsed:nextBatchScanned),
                  batch.indexedEntries);
          partial[batch.index]=basis;
          update.putInt(initialFeedProgressKey(batch.index),basis);
        }
        int progressBasisPoints=FullDiscoveryProgressPolicy.overallBasisPoints(
            finished,partial,initialDiscoveryComplete);
        int progressPercent=progressBasisPoints/100;
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
        previewLevel=FullDiscoveryProgressPolicy.nextPreviewLevel(previousPreviewLevel,stagedSizeAfterProbe,
            (int)Math.min(Integer.MAX_VALUE,(long)chinaValidatedSoFar+chinaPreflightsValid),activeStage>0);
        previousPreviewMilestone=roomInt(FullDiscoveryProgressPolicy.PREVIEW_MILESTONE_KEY,0);
        previewMilestone=FullDiscoveryProgressPolicy.nextPreviewMilestone(previousPreviewMilestone,stagedSizeAfterProbe,
            (int)Math.min(Integer.MAX_VALUE,(long)chinaValidatedSoFar+chinaPreflightsValid));
        // At most three independent category triggers over the entire build.
        // Once all categories were observed, do not scan the staging rows again.
        // On upgrade without this key, reconstruct once from committed+new rows.
        int stagedCoreMask=roomInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,-1);
        Iterable<Integer> coverageRows=stagedCoreMask<0?allRowIndices(saved):touchedStagedRows;
        stagedCoreMask=Math.max(0,stagedCoreMask)&FullDiscoveryProgressPolicy.ALL_CORE;
        for(int rowIndex:coverageRows){
          if(stagedCoreMask==FullDiscoveryProgressPolicy.ALL_CORE)break;
          SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
          JSONObject row=saved.getJSONObject(rowIndex);
          stagedCoreMask|=FullDiscoveryProgressPolicy.coreCategoryMask(
              row.optString("name"),row.optString("group"),row.optString("tvgName"),row.optString("tvgId"),
              hasChinaScope(row));
        }
        previousPreviewCoreMask=roomInt(FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY,0);
        previewCoreMask=FullDiscoveryProgressPolicy.nextPreviewCoreMask(previousPreviewCoreMask,stagedCoreMask,
            (int)Math.min(Integer.MAX_VALUE,(long)chinaValidatedSoFar+chinaPreflightsValid));
        update.putInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,stagedCoreMask);
        int timeoutTotal=(int)Math.min(Integer.MAX_VALUE,(long)Math.max(0,roomInt("source_initial_progress_timeout_urls",0))+initialTimeouts);
        update.putInt("source_initial_progress_timeout_urls",timeoutTotal)
            .putInt("source_initial_progress_percent",progressPercent)
            .putInt("source_initial_progress_basis_points",progressBasisPoints)
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
                :(awaitingIdleFinalization?"线路初筛已完成，等待空闲发布或确认结果":viewingDeferred?"低速检测部分未确认，记录补查位置，空闲后复检；"+stageDescription:stageDescription+"；暂不可用 "+unavailableCount+" 个"))
            .putString("source_update_result",initialDiscoveryComplete
                ?("首次全量目录检查已完成并发布；成功处理 "
                    +(completedFeeds-unavailableCount)+"/"+feeds.length
                    +" 个来源，另 "+unavailableCount+" 个已记录为暂不可用；通过媒体预检 "
                    +(validatedSoFar+Math.max(0,discoveryProbesValid))
                    +" 次（含近期重复地址复用，非实机播放验证）；预算超时未确认 "+timeoutTotal+" 次，待后续补查。")
                :("首次完整抓源进度 "+FullDiscoveryProgressPolicy.formatIncompleteProgress(progressBasisPoints)+"；已处理 "
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
              row.optString("tvgName"),row.optString("tvgId"),hasChinaScope(row));
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
      }else if(!initialDiscoveryChain&&!manualRefresh&&SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(discoveryProbesValid)){
        update.putLong("source_update_last_success_at",System.currentTimeMillis());
      }
      // A completed, committed probe pass advances the 30-day deadline;
      // skipped or failed work leaves the due date unchanged.
      boolean manualComplete=false;
      int manualNextFeed=0,manualFailedFeeds=0;
      if(manualRefresh){
        FeedBatch page=feedBatches.isEmpty()?null:feedBatches.get(0);
        boolean advance=page!=null&&feedBatchComplete&&page.success
            &&(page.unavailable||SourceUpdateSchedulePolicy.manualFeedFinished(
                page.cursor,page.parsed,page.indexedEntries,DiscoveryBatchPolicy.MANUAL_PAGE_SIZE));
        int next=advance?routineStart+1:routineStart;
        int failed=Math.max(0,roomInt("source_manual_failed_feeds",0));
        if(advance&&page.unavailable)failed++;
        if(advance||page!=null&&page.success&&!page.unavailable)
          update.putInt("source_manual_feed_attempts",0);
        if(advance)update.remove("source_manual_prepared_feed_version");
        manualNextFeed=next;manualFailedFeeds=failed;
        update.remove("source_manual_saved_pool_fingerprint")
            .putInt("source_manual_next_feed",next)
            .putInt("source_manual_failed_feeds",failed);
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
              .remove(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(feed))
              .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"fingerprint").remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"scanned")
                .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"coverage_order")
                .remove(DiscoveryBatchPolicy.initialIdleRecheckKey(feed))
                .remove(DiscoveryBatchPolicy.initialTransientRecheckKey(feed))
                 .remove(DiscoveryBatchPolicy.initialReplayMaskKey(feed))
                .remove(DiscoveryBatchPolicy.initialTransientRecheckAtKey(feed));
        }
        failedBuildReset.remove(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY)
            .remove(FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY)
            .remove("source_full_rebuild_active")
            .remove("source_full_rebuild_feed_fingerprint")
            .remove("source_initial_has_validated_source")
            .remove("source_initial_discovery_fingerprint")
            .remove("source_initial_discovery_scanned")
            .remove("source_feed_batch_fingerprint")
            .remove("source_feed_batch_scanned")
            .remove("source_initial_feed_batch_fingerprint")
            .remove("source_initial_feed_batch_scanned")
            .putInt("source_initial_next_feed_index",0)
              .remove(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY)
            .putInt("source_initial_progress_percent",0)
              .putInt("source_initial_progress_basis_points",0)
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
      if(indexedInitial)update.putInitialQuality(
          com.wodedianshi.tv.db.ManualProbeQualityStore.changes(qualityOwner,manualQualityDirty,measuredScores,probeFailureStreaks),pauseRequested);
      final long commitStartedAt=android.os.SystemClock.elapsedRealtime();
      if(initialDiscoveryChain)update.putString(FullDiscoveryProgressPolicy.THROUGHPUT_STATUS_KEY,
          FullDiscoveryProgressPolicy.throughputStatus(probeStartedAt-sliceStartedAt,
              probesFinishedAt-probeStartedAt,commitStartedAt-probesFinishedAt,checked+reusedPreflights,
              discoveryProbesValid,stagedSizeAfterProbe-previousStagedSize,slowPlayback)
              +"\n并发任务提交 "+idleWave.launched+" 条 · 并发任务实测峰值 "+idleWave.peakProbes.get()
              +" · 独立串行检测 "+idleWave.serialProbes+" 条（任务数，非同时网络连接数）"
              +"\n本批初步媒体筛查通过 "+provisionalAccepted+" 条（尚未证明真实播放质量）"
              +"\n已消费新检测：采样请求 "+consumedSampleRequests+" 次 · 应用层读取 "+consumedSampleBytes+" 字节（不含未消费任务及网络协议开销）");
      noteInitialStage(initialDiscoveryComplete?"正在发布完整快照":"正在保存本批已完成进度");
      if(initialDiscoveryChain&&!initialDiscoveryComplete){
        if(finalRanking)com.wodedianshi.tv.db.RoomCatalogueBuildStore.replace(
            getApplicationContext(),saved,pauseRequested,update);
        else {
          String revision=java.util.UUID.randomUUID().toString();
          update.putString(com.wodedianshi.tv.db.RoomCatalogueBuildStore.STAGING_REVISION_KEY,revision);
          if(initialIndexed){
            int committed=com.wodedianshi.tv.db.RoomCatalogueBuildStore.commitInitialIndexed(
                getApplicationContext(),previousStagedSize,initialRevision,indexedPositions,saved,
                touchedStagedRows,pauseRequested,update);
            if(committed!=stagedSizeAfterProbe)throw new IllegalStateException("Sparse staging commit count mismatch");
            discardStagingReuse();
          }else com.wodedianshi.tv.db.RoomCatalogueBuildStore.updateTouched(
              getApplicationContext(),saved,previousStagedSize,touchedStagedRows,pauseRequested,update);
          // Never cache a sparse working set as the complete staging catalogue.
          if(!initialIndexed&&FullDiscoveryProgressPolicy.canRetainStaging(saved.length(),slowPlayback)){
            committedStaging=saved;committedStagingRevision=revision;
            committedStagingAt=android.os.SystemClock.elapsedRealtime();
          }
        }
        if(previewLevel>previousPreviewLevel||previewMilestone>previousPreviewMilestone
            ||previewCoreMask!=previousPreviewCoreMask){
          if(!initialIndexed)publishInitialPreview(saved,previewLevel,previewMilestone,previewCoreMask);
          else if(FullDiscoveryProgressPolicy.canPublishPreview(slowPlayback,stagedSizeAfterProbe,
              com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext()).channelDao().countChannels())){
            JSONArray completePreview=takeCommittedStaging();
            if(completePreview.length()!=stagedSizeAfterProbe)
              throw new IllegalStateException("Indexed preview count mismatch");
            publishInitialPreview(completePreview,previewLevel,previewMilestone,previewCoreMask);
          }
        }
      }else if(manualRefresh){
        if(manualQueueOnly){
          if(!touchedStagedRows.isEmpty()||saved.length()!=0||!candidates.isEmpty()||!manualSavedOnly)
            throw new IllegalStateException("Unexpected discovery in queue-only slice");
          manualComplete=com.wodedianshi.tv.db.RoomCatalogueBuildStore.commitManualQueueOnly(
              getApplicationContext(),manualId,feeds.length,manualFailedFeeds,
              new ArrayList<>(manualSavedKeys.subList(0,savedScanned)),
              com.wodedianshi.tv.db.ManualProbeQualityStore.changes(manualId,manualQualityDirty,measuredScores,probeFailureStreaks),
              pauseRequested,update);
        }else if(manualIndexed){
          manualComplete=com.wodedianshi.tv.db.RoomCatalogueBuildStore.commitManualIndexed(
              getApplicationContext(),manualId,indexedBaseCount,indexedPositions,saved,touchedStagedRows,
              pauseRequested,update,new ArrayList<>(manualSavedKeys.subList(0,savedScanned)),
              manualNextFeed,feeds.length,manualFailedFeeds,
              com.wodedianshi.tv.db.ManualProbeQualityStore.changes(manualId,manualQualityDirty,measuredScores,probeFailureStreaks));
        }else manualComplete=com.wodedianshi.tv.db.RoomCatalogueBuildStore.updateManualTouchedWithQuality(
            getApplicationContext(),saved,previousStagedSize,touchedStagedRows,pauseRequested,update,manualId,
            new ArrayList<>(manualSavedKeys.subList(0,savedScanned)),manualSavedOnly,manualNextFeed,feeds.length,manualFailedFeeds,
            com.wodedianshi.tv.db.ManualProbeQualityStore.changes(manualId,manualQualityDirty,measuredScores,probeFailureStreaks));
        // Commit completion of detection first. A preference conflict or process
        // death during final publication retries only publication, not probes.
        if(manualComplete)return publishReadyManual(manualId,manualQueueOnly||manualIndexed?
            com.wodedianshi.tv.db.RoomCatalogueBuildStore.readManual(getApplicationContext(),manualId,pauseRequested):saved);
      }else{
        int written=com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshotWithProgress(
            getApplicationContext(),publication,roomSnapshot.catalogueVersion,
            initialDiscoveryComplete,pauseRequested,update);
        if(written!=publishedChannelCount)
          throw new IllegalStateException("Committed directory count mismatch");
      }
      if(initialDiscoveryChain||manualRefresh){
        final long commitFinishedAt=android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("MyTVSourceDiscovery","slice_committed prepare_ms="+(probeStartedAt-sliceStartedAt)
            +" probe_merge_ms="+(probesFinishedAt-probeStartedAt)
            +" finalize_ms="+(commitStartedAt-probesFinishedAt)
            +" commit_ms="+(commitFinishedAt-commitStartedAt)
            +" elapsed_ms="+(commitFinishedAt-sliceStartedAt)+" checked="+checked
            +" reused="+reusedPreflights+" staged="+(manualQueueOnly?"not_read":manualIndexed?Integer.toString(indexedBaseCount+saved.length()-indexedPositions.size()):Integer.toString(stagedSizeAfterProbe))+" viewing="+slowPlayback+" manual="+manualRefresh+" queue_only="+manualQueueOnly+" indexed_rows="+(manualIndexed||initialIndexed?indexedPositions.size():-1)
            +" parallel_launched="+idleWave.launched+" inconclusive="+initialTimeouts
            +" saved_scanned="+savedScanned+" discovery_scanned="+discoveryScanned
            +" inconclusive_endpoints="+inconclusiveAttempts.size()+" viewing_deferred="+viewingDeferred);
      }
      if(awaitingIdleFinalization)return deferInitialDirectory("线路初筛已完成，等待空闲发布或确认结果");
      if(initialDiscoveryChain&&!initialDiscoveryComplete&&!feedBatches.isEmpty()){
        // Empty pages and recorded failures are durable progress too; rotate
        // rather than repeatedly back off the same WorkRequest.
        boolean freshDifferentNextFeed=false;
        if(feedsOk==0&&!slowPlayback){
          // Use exactly the same admission policy as the next slice. A
          // cooling feed or an already visited family must not impose its
          // retry delay on a different eligible, unfailed directory.
          boolean preferCoreNow=DiscoveryBatchPolicy.preferCoreCoverage(
              roomInt("source_initial_progress_staged_channels",0),
              roomInt("source_initial_progress_checked_urls",0),
              roomInt(FullDiscoveryProgressPolicy.STAGED_CORE_MASK_KEY,-1));
          InitialFeedAdmission admission=readInitialFeedAdmission(feeds,false);
          int next=SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(initialNextFeedIndex,
              admission.completed,admission.deferred,admission.visited,admission.prepared,admission.failed,
              SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
              SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT,preferCoreNow,false,admission.acquisitionYield,admission.availableUnderPressure,admission.orderedReadyUnderPressure);
          int attempted=feedBatches.get(feedBatches.size()-1).index;
          freshDifferentNextFeed=next>=0&&next!=attempted&&!admission.failed[next];
        }
        long delay=SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(
            slowPlayback,feedsOk>0,freshDifferentNextFeed,feedBatchComplete,
            discoveryScanned,idleWaveTimedOut);
        // This branch follows the durable Room commit and any preview publish.
        // Each inline slice reconstructs its gates/cursors from current state.
        // No acquisition, delayed retry, or playback slice requests inline work.
        if(SourceUpdateSchedulePolicy.canContinueInitialInline(ownedSlices,
            android.os.SystemClock.elapsedRealtime()-ownedStartedAt,slowPlayback,
            SourceUpdateActivityPolicy.isPlaybackActive(),pauseRequested.getAsBoolean(),
            feedsOk>0&&discoveryScanned>0,delay)){
          inlineInitialRequested=true;
          return Result.success();
        }
        enqueueInitialContinuation(delay);
        return Result.success();
      }
      if(manualRefresh&&!manualComplete){
        // Each bounded slice commits before a successor is enqueued. A retry
        // after process death reads the committed cursor, not an old input offset.
        androidx.work.OneTimeWorkRequest continuation=
            new androidx.work.OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
                .setConstraints(new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInitialDelay(SourceUpdateSchedulePolicy.manualContinuationDelaySeconds(
                    feedsOk>0||manualSavedOnly,discoveryScanned+ (manualSavedOnly?savedScanned:0),
                    !manualSavedOnly&&feedBatchComplete),java.util.concurrent.TimeUnit.SECONDS)
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,30L,java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
                    .putString("manual_refresh_id",manualId).build()).build();
        enqueueSingleSuccessor("mytv_manual_source_update",continuation);
      }
      // Routine failures rotate to other directories and wait for the next idle
      // eligibility check; no aggressive WorkManager retry loop on dead feeds.
      if(!initialDiscoveryChain)return Result.success();
      if(initialDiscoveryComplete){clearRecentProbeCache();INITIAL_ORIGINS.clear();INITIAL_NETWORK.clear();discardStagingReuse();}
      return (discoveryUnavailable&&!initialDiscoveryComplete
              &&!(initialDiscoveryChain&&candidates.size()>0))
          ||(initialDiscoveryChain&&!initialDiscoveryComplete&&candidates.isEmpty())
          ?Result.retry():Result.success();
    }catch(SourceProbeTimePolicy.PublicationPausedException paused){
      // Signal probe cancellation before any Room status write or WorkManager
      // query/enqueue, either of which can block this background owner.
      idleWave.close();
      saveStatus(prefs,"频道播放优先，已撤销尚未完成的目录事务，原频道继续可用",0);
      // A quality pass that already began may have fresh full evidence in
      // the existing process cache. One WorkManager retry, not a successor
      // task or a polling loop. Busy admission on that retry exits normally.
      if(SourceUpdateSchedulePolicy.retryInterruptedQualityPass(provisionalOnly,
          getRunAttemptCount(),isStopped(),Thread.currentThread().isInterrupted()))
        return Result.retry();
      return deferredViewingResult(manual,initialDiscoveryChain);
    }catch(Exception e){idleWave.close();saveStatus(prefs,"更新异常："+e.getMessage()+"（原频道源保留）",0);return Result.retry();}
    finally{idleWave.close();}
  }
  /** Reuse the existing daily worker/ownership gate, but never download feeds
   * or rewrite the catalogue just to upgrade provisional quality evidence. */
  private static void collectPendingRoute(Set<String> pending,Map<String,String> found,String url){
    if(url==null||(!url.startsWith("http://")&&!url.startsWith("https://")))return;
    String key=StableKey.url(url);
    if(pending.contains(key))found.putIfAbsent(key,url);
  }
  static int prunePendingForCompletePublication(Set<String> pending,JSONArray rows,
      java.util.function.BooleanSupplier paused){
    if(pending.isEmpty())return 0;
    HashMap<String,String> found=new HashMap<>();
    for(int i=0;i<rows.length()&&found.size()<pending.size();i++){
      SourceProbeTimePolicy.checkPublicationPaused(paused);
      JSONObject row=rows.optJSONObject(i);
      if(row==null)continue;
      String primary=row.optString("url","");
      collectPendingRoute(pending,found,primary);
      JSONArray sources=row.optJSONArray("sources");
      if(sources!=null)for(int j=0;j<sources.length()&&found.size()<pending.size();j++){
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        String url=sources.optString(j,"");
        if(!url.equals(primary))collectPendingRoute(pending,found,url);
      }
    }
    // A partial membership scan must never remove unvisited keys.
    SourceProbeTimePolicy.checkPublicationPaused(paused);
    return SourceUpdateSchedulePolicy.pruneUnretainedProvisional(pending,found.keySet(),true);
  }
  private Result runProvisionalQualityRecheck()throws Exception{
    final Context context=getApplicationContext();
    LinkedHashSet<String> pending=new LinkedHashSet<>();
    JSONArray stored=new JSONArray(roomString(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_KEY,"[]"));
    for(int i=0;i<stored.length();i++)
      SourceUpdateSchedulePolicy.rememberProvisional(pending,stored.optString(i,""));
    if(pending.isEmpty())return Result.success();
    HashMap<String,String> current=new HashMap<>();
    long membershipStart=android.os.SystemClock.elapsedRealtime();
    Long catalogueVersion=com.wodedianshi.tv.db.RoomCatalogueReader.visitRouteUrls(context,url->{
      collectPendingRoute(pending,current,url);
      return current.size()==pending.size();
    },pauseRequested);
    if(catalogueVersion==null)return Result.retry();
    // Only discard missing keys after a complete, version-consistent read.
    SourceUpdateSchedulePolicy.pruneUnretainedProvisional(pending,current.keySet(),true);
    long membershipMs=android.os.SystemClock.elapsedRealtime()-membershipStart;
    if(pending.isEmpty()){
      // The version-consistent scan proved that no queued route survives.
      // Persist the empty queue, but do not hydrate QoE or quality state.
      publishQualityRecheckCheckpoint(context,pending,Collections.emptyMap(),catalogueVersion,false,true);
      android.util.Log.i("MyTVSourceDiscovery","provisional_quality_recheck checked=0 selected=0 conclusive=0"
          +" pending=0 membership_ms="+membershipMs+" sample_requests=0 sample_bytes=0");
      return Result.success();
    }
    // Use only exact-key persisted playback evidence. Legacy entries without
    // an explicit last event, malformed history and future timestamps fail open
    // to the ordinary probe. Never remove a pending key or alter its score here.
    JSONObject playbackHistory;
    try{playbackHistory=new JSONObject(roomString("source_qoe_v1","{}"));}
    catch(JSONException malformed){playbackHistory=new JSONObject();}
    final JSONObject observedPlayback=playbackHistory;
    final long evidenceNow=System.currentTimeMillis();
    final int[] stableDeferred={0};
    ArrayList<String> selected=SourceUpdateSchedulePolicy.provisionalRecheckOrder(pending,current,key->{
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      JSONObject metric=observedPlayback.optJSONObject(key);
      boolean defer=metric!=null&&SourceUpdateSchedulePolicy.deferRecentlyStableRecheck(
          metric.optString("lastEvent",""),metric.optLong("lastStable",0L),
          metric.optLong("updated",0L),evidenceNow);
      if(defer)stableDeferred[0]++;
      return defer;
    },SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_CAP);
    long stageDeadline=android.os.SystemClock.elapsedRealtime()+PROBE_STAGE_DEADLINE_MS;
    final boolean indexed=!selected.isEmpty()&&com.wodedianshi.tv.db.ManualProbeQualityStore.hasPublished(context);
    // Indexed quality supports bounded atomic checkpoints. Compatibility JSON
    // keeps the old single write to avoid repeated whole-score serialization.
    java.util.function.Consumer<Map<String,ProbeResult>> checkpoint=indexed?batch->{
      try{publishQualityRecheckCheckpoint(context,pending,batch,catalogueVersion,true,false);}
      catch(JSONException malformed){throw new IllegalStateException("Quality checkpoint encoding failed",malformed);}
    }:null;
    QualityRecheckSlice slice=probeQualityRecheckSlice(selected,pending,stageDeadline,pauseRequested,catalogueVersion,checkpoint);
    publishQualityRecheckCheckpoint(context,pending,slice.completed,catalogueVersion,indexed,true);
    int conclusive=slice.conclusive;
    android.util.Log.i("MyTVSourceDiscovery","provisional_quality_recheck checked="+slice.checked
        +" selected="+selected.size()+" conclusive="+conclusive+" origin_deferred="+slice.originDeferred
        +" stable_deferred="+stableDeferred[0]
        +" reused_full="+slice.reused+" extended="+slice.extended
        +" sample_requests="+slice.sampleRequests+" sample_bytes="+slice.sampleBytes
        +" pending="+pending.size()+" membership_ms="+membershipMs
        +" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-(stageDeadline-PROBE_STAGE_DEADLINE_MS)));
    return Result.success();
  }
  private void publishQualityRecheckCheckpoint(Context context,Set<String> pending,
      Map<String,ProbeResult> completed,long catalogueVersion,boolean indexed,boolean finished)throws JSONException{
    // Only completed measurements need quality rows. Skipped, timed-out and
    // never-admitted candidates must not incur score hydration or writes.
    JSONObject scores=new JSONObject(),streaks=new JSONObject();
    HashSet<String> dirty=new HashSet<>();
    if(!completed.isEmpty()){
      if(indexed)com.wodedianshi.tv.db.ManualProbeQualityStore.loadUrls(context,
          com.wodedianshi.tv.db.ManualProbeQualityStore.PUBLISHED_OWNER,completed.keySet(),
          new HashSet<>(),scores,streaks,pauseRequested);
      else scores=new JSONObject(roomString("source_probe_scores_v1","{}"));
      for(Map.Entry<String,ProbeResult> entry:completed.entrySet()){
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        String url=entry.getKey();
        String key=scoreKey(url),legacy=legacyScoreKey(url);
        storePreflightScore(scores,key,legacy,entry.getValue());
        // Keep the existing failure streak for ordinary health rotation.
        dirty.add(key);dirty.add(legacy);
      }
    }
    com.wodedianshi.tv.db.AppStateEditor update=new com.wodedianshi.tv.db.AppStateEditor(context)
        .putString(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_KEY,new JSONArray(pending).toString());
    // A partial checkpoint must not postpone the unfinished queue until tomorrow.
    if(finished)update.putLong(SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_LAST_KEY,System.currentTimeMillis());
    if(!completed.isEmpty()){
      if(indexed)update.putPublishedQuality(com.wodedianshi.tv.db.ManualProbeQualityStore.changes(
          com.wodedianshi.tv.db.ManualProbeQualityStore.PUBLISHED_OWNER,dirty,scores,streaks),pauseRequested);
      else update.putString("source_probe_scores_v1",scores.toString());
      update.putString(SourceUpdateSchedulePolicy.QUALITY_ONLY_REVISION_KEY,
          java.util.UUID.randomUUID().toString());
    }
    update.applyAtCatalogueVersion(catalogueVersion,pauseRequested);
  }
  /** Owner-local network phase; publication remains in the existing caller. */
  static final class QualityRecheckSlice {
    final Map<String,ProbeResult> completed;
    final int checked,originDeferred,reused,conclusive;
    final boolean extended;
    final long sampleRequests,sampleBytes;
    QualityRecheckSlice(Map<String,ProbeResult> completed,int checked,int originDeferred,
        int reused,long sampleRequests,long sampleBytes,boolean extended,int conclusive){
      this.completed=completed;this.checked=checked;this.originDeferred=originDeferred;
      this.reused=reused;this.sampleRequests=sampleRequests;this.sampleBytes=sampleBytes;this.extended=extended;
      this.conclusive=conclusive;
    }
  }
  static QualityRecheckSlice probeQualityRecheckSlice(List<String> selected,LinkedHashSet<String> pending,
      long stageDeadline,java.util.function.BooleanSupplier paused){
    return probeQualityRecheckSlice(selected,pending,stageDeadline,paused,null);
  }
  // Non-null version is supplied only by the exclusive source-work owner
  // (or a sequential host test). Namespace within the existing bounded cache;
  // initial/manual observations must not authorize a full quality recheck.
  static QualityRecheckSlice probeQualityRecheckSlice(List<String> selected,LinkedHashSet<String> pending,
      long stageDeadline,java.util.function.BooleanSupplier paused,Long catalogueVersion){
    return probeQualityRecheckSlice(selected,pending,stageDeadline,paused,catalogueVersion,null);
  }
  static final int QUALITY_CHECKPOINT_SIZE=16;
  private static boolean reusableQualityRetry(RecentProbe cached,long now){
    return cached!=null&&SourceUpdateSchedulePolicy.canReuseInitialProbe(true,false,cached.at,now)
        &&cached.result.valid&&!cached.result.inconclusive()&&!cached.result.paced&&!cached.result.provisional;
  }
  private static boolean hasQualityRetryEvidence(String prefix,long now){
    if(prefix==null)return false;
    // Owner-local read: iterating does not update the access-order cache.
    for(Map.Entry<String,RecentProbe> entry:INITIAL_PROBES.entrySet())
      if(entry.getKey().startsWith(prefix)&&reusableQualityRetry(entry.getValue(),now))return true;
    return false;
  }
  static QualityRecheckSlice probeQualityRecheckSlice(List<String> selected,LinkedHashSet<String> pending,
      long stageDeadline,java.util.function.BooleanSupplier paused,Long catalogueVersion,
      java.util.function.Consumer<Map<String,ProbeResult>> checkpoint){
    LinkedHashMap<String,ProbeResult> completed=new LinkedHashMap<>();
    // Scope positive full evidence to this single idle slice. Exact URL
    // keys still own their queue membership and durable quality records.
    HashMap<String,ProbeResult> successfulEndpoints=new HashMap<>();
    // One unresolved observation per exact endpoint per slice. This is
    // admission only, never a negative result or durable blacklist.
    HashSet<String> unresolvedEndpoints=new HashSet<>();
    int checked=0,conclusive=0,originDeferred=0,reused=0,positive=0;
    int limit=SourceUpdateSchedulePolicy.PROVISIONAL_DAILY_LIMIT;
    boolean extended=false,lastProbeHealthy=false,tailUsed=false;
    String retryPrefix=catalogueVersion==null?null:"quality-recheck\n"+catalogueVersion+"\n";
    Boolean retryEvidenceAvailable=null;
    long startedAt=android.os.SystemClock.elapsedRealtime();
    long sampleRequests=0L,sampleBytes=0L;
    // Reuse the bounded cooldown policy, but keep routine evidence local to
    // this slice: a temporary outage must not blacklist a saved source.
    InitialOriginCooldownPolicy recheckOrigins=new InitialOriginCooldownPolicy();
    for(String url:selected){
      SourceProbeTimePolicy.checkPublicationPaused(paused);
      long now=android.os.SystemClock.elapsedRealtime();
      if(now>=stageDeadline)break;
      // The default quota remains unchanged for slow, inconclusive or
      // cache-heavy batches. Only measured healthy fresh probes can extend.
      if(!extended&&lastProbeHealthy&&checked>=limit&&SourceUpdateSchedulePolicy.permitsFastRecheckExtension(
          checked,positive,now-startedAt,sampleRequests,sampleBytes)){
        extended=true;limit=SourceUpdateSchedulePolicy.PROVISIONAL_FAST_LIMIT;
      }
      if(conclusive>=limit)break;
      String endpoint=SourceSelectionPolicy.endpointKey(url);
      ProbeResult previous=successfulEndpoints.get(endpoint);
      String retryKey=retryPrefix==null?null:retryPrefix+endpoint;
      if(previous==null&&retryKey!=null){
        RecentProbe cached=INITIAL_PROBES.get(retryKey);
        if(reusableQualityRetry(cached,now))previous=cached.result;
      }
      if(previous!=null){
        completed.put(url,previous);
        pending.remove(StableKey.url(url));conclusive++;reused++;
        flushQualityCheckpoint(completed,checkpoint,paused);
        continue;
      }
      if(unresolvedEndpoints.contains(endpoint))continue;
      long deadline=checked>=limit
          ||(extended&&!SourceUpdateSchedulePolicy.permitsExtendedRecheckProbe(sampleRequests,sampleBytes))
          ?0L:SourceUpdateSchedulePolicy.qualityRecheckDeadline(stageDeadline,now,tailUsed,
              lastProbeHealthy,checked,positive,sampleRequests,sampleBytes);
      if(deadline==0L){
        // Every exhausted network budget uses the same cache-only tail.
        // A fresh row must not hide later full evidence from this slice or
        // the same-version retry cache. Keep a nominal commit margin.
        if(stageDeadline-now<2000L)break;
        if(successfulEndpoints.isEmpty()){
          // Without local positives or fresh versioned retry evidence, the
          // remaining bounded queue cannot complete anything without I/O.
          // Once network admission is exhausted it cannot reopen in this slice.
          if(retryEvidenceAvailable==null)retryEvidenceAvailable=hasQualityRetryEvidence(retryPrefix,now);
          if(!retryEvidenceAvailable)break;
        }
        continue;
      }
      String origin=InitialOriginCooldownPolicy.origin(url);
      if(recheckOrigins.cooling(origin,now)){
        // Not attempted: retain the original queue position and quality.
        originDeferred++;continue;
      }
      if(deadline-now<SourceProbeTimePolicy.INITIAL_ADDRESS_BUDGET_MS)tailUsed=true;
      checked++;
      ProbeResult measured=probe(url,deadline,false,paused,false);
      long finishedAt=android.os.SystemClock.elapsedRealtime();
      // Retain only a finished full positive observation, not the queue,
      // score transaction, or failed/cancelled work. Never refresh its age
      // on a cache hit. An intervening version change makes it ineligible.
      boolean inconclusive=measured.inconclusive();
      boolean fullPositive=SourceUpdateSchedulePolicy.reusableFullRecheck(measured.valid,inconclusive,
          measured.paced,measured.provisional,finishedAt,deadline);
      if(retryKey!=null&&finishedAt<stageDeadline&&fullPositive)
        rememberInitialProbe(retryKey,measured,finishedAt);
      SourceProbeTimePolicy.checkPublicationPaused(paused);
      String key=StableKey.url(url);
      sampleRequests+=measured.sampleRequests;sampleBytes+=measured.sampleBytes;
      measured.publishOriginHealth(recheckOrigins,origin,endpoint,finishedAt);
      // A previous fast batch is not permission to keep spending resources
      // after transport pressure or a newly slow response. Use the existing
      // idle read timeout as a conservative elapsed-cost threshold.
      boolean pressure=measured.hasOriginPressure()||measured.socketTimedOut
          ||measured.transientNetworkFailure||finishedAt>=deadline
          ||finishedAt-now>=SourceProbeTimePolicy.sampleReadTimeoutCap(false);
      lastProbeHealthy=fullPositive&&!pressure;
      if(inconclusive||finishedAt>=deadline){
        unresolvedEndpoints.add(endpoint);
        SourceUpdateSchedulePolicy.rememberProvisional(pending,key);
        if(extended&&pressure)break;
        continue;
      }
      if(fullPositive){
        successfulEndpoints.put(endpoint,measured);positive++;
      }
      completed.put(url,measured);
      pending.remove(key);conclusive++;
      flushQualityCheckpoint(completed,checkpoint,paused);
      if(extended&&pressure)break;
    }
    SourceProbeTimePolicy.checkPublicationPaused(paused);
    return new QualityRecheckSlice(completed,checked,originDeferred,reused,sampleRequests,sampleBytes,extended,conclusive);
  }
  private static void flushQualityCheckpoint(Map<String,ProbeResult> completed,
      java.util.function.Consumer<Map<String,ProbeResult>> checkpoint,java.util.function.BooleanSupplier paused){
    if(checkpoint==null||completed.size()<QUALITY_CHECKPOINT_SIZE)return;
    SourceProbeTimePolicy.checkPublicationPaused(paused);
    checkpoint.accept(completed);
    completed.clear(); // Only after the version-guarded transaction succeeds.
  }
  private Result abortManualStructuralConflict(String manualId){
    com.wodedianshi.tv.db.AppStateEditor status=new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
        .remove("source_manual_active_id")
        .putString("source_update_result","频道目录在手动整理期间已修改；未覆盖用户目录，请重新手动刷新");
    for(String key:ManualSavedCoverage.keys())status.remove(key);
    com.wodedianshi.tv.db.RoomCatalogueBuildStore.discardManual(getApplicationContext(),manualId,status,pauseRequested);
    return Result.failure();
  }
  private Result publishReadyManual(String manualId,JSONArray rows)throws Exception{
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    // Ranking is reproducible from the durable ready snapshot; a retry does
    // not repeat network work. Do this before rebase so user-edited rows win.
    LinkedHashSet<String> needed=new LinkedHashSet<>();
    for(int i=0;i<rows.length();i++){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      collectQualityUrls(needed,rows.optJSONObject(i));
    }
    JSONObject finalScores=com.wodedianshi.tv.db.ManualProbeQualityStore.readManualRankingScores(
        getApplicationContext(),manualId,needed,pauseRequested);
    rankCatalogueRows(rows,allRowIndices(rows),finalScores,new JSONObject(roomString("source_qoe_v1","{}")));
    String reconciledStructure=null;
    long reconciledVersion=-1L;
    if(!com.wodedianshi.tv.db.RoomCatalogueBuildStore.manualStructureStillCurrent(getApplicationContext(),manualId)){
      reconciledStructure=com.wodedianshi.tv.db.RoomCatalogueBuildStore.currentStructureVersion(getApplicationContext());
      JSONArray live=new JSONArray();
      com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot liveSnapshot=readPublishedRows(live);
      if(liveSnapshot==null)throw new IllegalStateException("Live directory unavailable for rebase");
      reconciledVersion=liveSnapshot.catalogueVersion;
      try{
        int baseCount=roomInt(com.wodedianshi.tv.db.RoomCatalogueBuildStore.MANUAL_BASE_COUNT_KEY,-1);
        List<JSONObject> merged=ManualSnapshotRebase.merge(manualRebaseRows(rows,true),manualRebaseRows(live,false),baseCount,pauseRequested);
        JSONArray publication=new JSONArray();for(JSONObject row:merged)publication.put(row);
        if(publication.length()==0)throw new IllegalArgumentException("Empty reconciled directory");
        rows=publication;
      }catch(IllegalArgumentException ambiguous){return abortManualStructuralConflict(manualId);}
    }
    com.wodedianshi.tv.db.CatalogueMigrationEntity checkpoint=com.wodedianshi.tv.db.RoomDatabaseProvider
        .get(getApplicationContext()).channelDao().getValidatedCheckpoint(
            com.wodedianshi.tv.db.MasterCatalogueRepository.MIGRATION_KEY);
    if(checkpoint==null)throw new IllegalStateException("Live catalogue checkpoint unavailable");
    // Publish the sparse private quality overlay in the snapshot transaction.
    // No legacy quality mirror is generated.
    com.wodedianshi.tv.db.AppStateEditor finish=new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
        .putString("source_manual_completed_id",manualId)
        .remove("source_manual_active_id")
        .putString("source_update_time",now())
        .putLong("source_update_last_success_at",System.currentTimeMillis())
        .putString("source_update_result",roomString("source_update_result","")+" 已原子发布完整手动目录。");
    for(String key:ManualSavedCoverage.keys())finish.remove(key);
    int written=reconciledStructure==null?com.wodedianshi.tv.db.RoomCatalogueWriter.publishManualSnapshot(
        getApplicationContext(),rows,checkpoint.completedAt,manualId,pauseRequested,finish):
        com.wodedianshi.tv.db.RoomCatalogueWriter.publishReconciledManualSnapshot(
            getApplicationContext(),rows,reconciledVersion,manualId,reconciledStructure,pauseRequested,finish);
    if(written!=rows.length())throw new IllegalStateException("Manual publication count mismatch");
    clearRecentProbeCache();
    return Result.success();
  }
  // Call only while preparing an immutable view, before any route merges.
  static void collectQualityUrlsOnce(Set<String> needed,Set<JSONObject> collected,JSONObject row){
    if(row!=null&&collected.add(row))collectQualityUrls(needed,row);
  }
  private static void collectQualityUrls(Set<String> needed,JSONObject row){
    if(row==null)return;
    needed.add(row.optString("url"));
    JSONArray urls=row.optJSONArray("sources");
    if(urls!=null)for(int i=0;i<urls.length();i++)needed.add(urls.optString(i));
  }
  static JSONObject mergeCompatibilityQuality(JSONObject compatible,JSONObject authoritative,
      Set<String> dirty)throws JSONException{
    for(String key:dirty){
      if(authoritative.has(key))compatible.put(key,authoritative.get(key));
      else compatible.remove(key);
    }
    return compatible;
  }
  private Iterable<Integer> allRowIndices(JSONArray rows){
    return ()->new Iterator<Integer>(){private int index;public boolean hasNext(){return index<rows.length();}public Integer next(){return index++;}};
  }
  private void rankCatalogueRows(JSONArray saved,Iterable<Integer> rankingRows,JSONObject measuredScores,
      JSONObject observedQoe)throws JSONException {
      // The score inputs do not change during this ranking pass. Share a small
      // bounded cache and one recency clock across all rows, not across merges.
      final long rankingTime=System.currentTimeMillis();
      java.util.Comparator<String> bestFirst=SourceRankingRetentionPolicy.boundedBestFirst(
          url->combinedScore(url,measuredScores,observedQoe,rankingTime),1024);
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
        ordered=SourceRankingRetentionPolicy.rankRequiredAndBest(
            ordered,stablePrimaryUrl,previousActiveUrl,MAX_SOURCES_PER_CHANNEL,bestFirst);
        JSONArray ranked=new JSONArray();int restoredActiveIndex=0;
        for(int j=0;j<ordered.size();j++){String u=ordered.get(j);ranked.put(u);if(u.equals(previousActiveUrl))restoredActiveIndex=j;}
        if(ranked.length()>0){
          // Keep url as the stable channel identity. The ranked source list and
          // activeSource carry playback preference independently.
          channel.put("sources",ranked);channel.put("activeSource",restoredActiveIndex);
          retainBackupSourceScopes(channel,ranked);
        }
      }
  }
  private String manualRowIdentity(JSONObject row){
    return com.wodedianshi.tv.db.LegacyChannelIdPolicy.stableIdentityKey(row.optString("name"),row.optString("url"),
        row.optString("tvgId"),row.optString("group"),row.optString("tvgName"));
  }
  private String manualRowFingerprint(JSONObject row){
    StringBuilder value=new StringBuilder();
    for(String key:new String[]{"name","url","group","tvgId","tvgName"})appendFingerprintPart(value,row.optString(key));
    JSONArray sources=row.optJSONArray("sources");
    appendFingerprintPart(value,Integer.toString(sources==null?0:sources.length()));
    if(sources!=null)for(int i=0;i<sources.length();i++)appendFingerprintPart(value,sources.optString(i));
    appendManualScopes(value,row.optJSONArray("sourceScopes"));
    TreeMap<String,TreeSet<String>> owned=new TreeMap<>();
    JSONArray ownership=row.optJSONArray("backupSourceScopes");
    if(ownership!=null)for(int i=0;i<ownership.length();i++){
      JSONObject item=ownership.optJSONObject(i);if(item==null)continue;
      String url=item.optString("url");TreeSet<String> scopes=owned.get(url);
      if(scopes==null){scopes=new TreeSet<>();owned.put(url,scopes);}
      JSONArray raw=item.optJSONArray("scopes");
      if(raw!=null)for(int j=0;j<raw.length();j++)scopes.add(raw.optString(j));
    }
    appendFingerprintPart(value,Integer.toString(owned.size()));
    for(Map.Entry<String,TreeSet<String>> entry:owned.entrySet()){
      appendFingerprintPart(value,entry.getKey());appendFingerprintPart(value,Integer.toString(entry.getValue().size()));
      for(String scope:entry.getValue())appendFingerprintPart(value,scope);
    }
    return CatalogueFingerprint.sha256(value.toString());
  }
  private void appendManualScopes(StringBuilder value,JSONArray raw){
    TreeSet<String> scopes=new TreeSet<>();
    if(raw!=null)for(int i=0;i<raw.length();i++)scopes.add(raw.optString(i));
    appendFingerprintPart(value,Integer.toString(scopes.size()));
    for(String scope:scopes)appendFingerprintPart(value,scope);
  }
  private List<ManualSnapshotRebase.Row<JSONObject>> manualRebaseRows(JSONArray rows,boolean staged)throws JSONException{
    List<ManualSnapshotRebase.Row<JSONObject>> result=new ArrayList<>();
    for(int i=0;i<rows.length();i++){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      JSONObject row=rows.getJSONObject(i);
      result.add(new ManualSnapshotRebase.Row<>(manualRowIdentity(row),
          staged?row.optString("manualBaseIdentity",""):"",
          staged?row.optString("manualBaseFingerprint",""):"",
          staged?"":manualRowFingerprint(row),row));
    }
    return result;
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
  private com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot readPublishedRows(JSONArray rows){
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot=
        com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),page->{
          try{appendRoomRows(rows,page);}
          catch(JSONException invalid){throw new IllegalStateException("Cannot convert Room catalogue page",invalid);}
        },pauseRequested);
    if(snapshot!=null&&rows.length()!=snapshot.channelCount)
      throw new IllegalStateException("Converted Room catalogue count mismatch");
    return snapshot;
  }
  private boolean publishInitialPreview(JSONArray saved,int level,int milestone,int coreMask)throws Exception{
    if(saved.length()==0||!FullDiscoveryProgressPolicy.canPublishPreview(slowPlayback,saved.length(),
        com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext()).channelDao().countChannels()))return false;
    noteInitialStage("正在发布阶段性已预检频道");
    JSONArray priorRows=new JSONArray();
    com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot base=readPublishedForInitialCommit(priorRows);
    if(!FullDiscoveryProgressPolicy.canPublishPreview(slowPlayback,saved.length(),priorRows.length()))return false;
    JSONArray preview=preservePreviouslyPublishedChannels(saved,priorRows);
    com.wodedianshi.tv.db.AppStateEditor state=new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
        .putInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,level)
        .putInt(FullDiscoveryProgressPolicy.PREVIEW_MILESTONE_KEY,milestone)
        .putInt(FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY,coreMask);
    if(com.wodedianshi.tv.db.ManualProbeQualityStore.hasInitial(getApplicationContext())){
      LinkedHashSet<String> previewUrls=new LinkedHashSet<>();
      for(int i=0;i<preview.length();i++){
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        collectQualityUrls(previewUrls,preview.optJSONObject(i));
      }
      state.putInitialPreviewQuality(previewUrls,pauseRequested);
    }
    int count=com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshotWithProgress(
        getApplicationContext(),preview,base.catalogueVersion,false,pauseRequested,state);
    if(count!=preview.length())throw new IllegalStateException("Preview directory count mismatch");
    return true;
  }
  private com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot readPublishedForInitialCommit(JSONArray rows){
    com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot=readPublishedRows(rows);
    if(snapshot==null)throw new IllegalStateException("Published catalogue not ready for merge");
    return snapshot;
  }
  // A scheduler failure after a successful acquisition is not a feed failure.
  private Result continueAfterAcquisition(){
    try{enqueueInitialContinuation();return Result.success();}
    catch(Exception failure){
      android.util.Log.w("MyTVSourceDiscovery","Prepared directory saved; continuation needs retry",failure);
      return Result.retry();
    }
  }
  private void enqueueInitialContinuation() throws Exception {
    enqueueInitialContinuation(slowPlayback?DiscoveryBatchPolicy.VIEWING_SUCCESS_DELAY_SECONDS:0L);
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
    enqueueSingleSuccessor("mytv_startup_source_refresh",continuation);
  }
  /** Low-priority maintenance must yield to every unfinished manual request,
   * including ENQUEUED/BLOCKED continuations. Run only on the background owner.
   * If the scheduler cannot confirm eligibility, leave maintenance due. */
  private boolean routineMayStartWithManualChain(){
    if(isStopped()||Thread.currentThread().isInterrupted())return false;
    try{
      List<androidx.work.WorkInfo> infos=androidx.work.WorkManager.getInstance(getApplicationContext())
          .getWorkInfosForUniqueWork("mytv_manual_source_update")
          .get(3L,java.util.concurrent.TimeUnit.SECONDS);
      for(androidx.work.WorkInfo info:infos){
        if(!info.getState().isFinished()){
          android.util.Log.i("MyTVSourceDiscovery","routine_deferred_manual_chain");
          return false;
        }
      }
      return !isStopped()&&!Thread.currentThread().isInterrupted();
    }catch(InterruptedException interrupted){
      Thread.currentThread().interrupt();return false;
    }catch(Exception unavailable){
      android.util.Log.w("MyTVSourceDiscovery","routine_manual_chain_status_unavailable",unavailable);
      return false;
    }
  }

  /** Called only by the source-work owner, after durable slice progress.
   * A retry after an ambiguous enqueue must reuse an existing unfinished
   * successor. Never revive a cancelled chain with APPEND_OR_REPLACE.
   * A query/operation timeout is not success: retain the predecessor for retry. */
  private void enqueueSingleSuccessor(String uniqueName,
          androidx.work.OneTimeWorkRequest continuation) throws Exception {
    if(isStopped()||Thread.currentThread().isInterrupted())
      throw new InterruptedException("Source work cancelled");
    final long schedulingStartedAt=android.os.SystemClock.elapsedRealtime();
    androidx.work.WorkManager manager=androidx.work.WorkManager.getInstance(getApplicationContext());
    List<androidx.work.WorkInfo> infos=manager.getWorkInfosForUniqueWork(uniqueName)
        .get(10L,java.util.concurrent.TimeUnit.SECONDS);
    boolean currentRunning=false;
    ArrayList<java.util.UUID> unfinished=new ArrayList<>();
    for(androidx.work.WorkInfo info:infos){
      if(info.getId().equals(getId()))
        currentRunning=info.getState()==androidx.work.WorkInfo.State.RUNNING;
      if(!info.getState().isFinished())unfinished.add(info.getId());
    }
    if(!currentRunning||isStopped()||Thread.currentThread().isInterrupted())
      throw new InterruptedException("Source work no longer running");
    if(SourceUpdateSchedulePolicy.hasOtherUnfinishedWork(getId(),unfinished)){
      android.util.Log.i("MyTVSourceDiscovery","continuation_reused chain="+uniqueName
          +" scheduling_ms="+(android.os.SystemClock.elapsedRealtime()-schedulingStartedAt));
      return;
    }
    manager.enqueueUniqueWork(uniqueName,androidx.work.ExistingWorkPolicy.APPEND,continuation)
        .getResult().get(10L,java.util.concurrent.TimeUnit.SECONDS);
    android.util.Log.i("MyTVSourceDiscovery","continuation_enqueued chain="+uniqueName
        +" scheduling_ms="+(android.os.SystemClock.elapsedRealtime()-schedulingStartedAt));
  }
  private boolean areAllDiscoveryFeedsComplete(SharedPreferences prefs,
          List<FeedBatch> batches,int feedCount){
    ArrayList<String> keys=new ArrayList<>(feedCount);
    for(int i=0;i<feedCount;i++)keys.add(initialFeedDoneKey(i));
    Map<String,String> values=readRoomValues(keys);
    boolean[] completed=new boolean[feedCount];
    for(int i=0;i<feedCount;i++)completed[i]=Boolean.parseBoolean(values.get(keys.get(i)));
    return areAllDiscoveryFeedsComplete(completed,batches);
  }
  private boolean areAllDiscoveryFeedsComplete(boolean[] completed,List<FeedBatch> batches){
    for(int i=0;i<completed.length;i++){
      boolean done=completed[i];
      if(!done){
        for(FeedBatch batch:batches){
          if(batch.index==i&&SourceUpdateSchedulePolicy.initialFeedInspected(
              batch.success,batch.unavailable,batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED)){
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
      appendFingerprintPart(value,entry.endpoint());
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
  private void appendRoomRows(JSONArray result,
      List<com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord> rows) throws JSONException {
    for(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row:rows){
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
  }

  private boolean observeInitialSliceExpiry(boolean initial,long started){
    if(initialSliceExpired.get())return true;
    if(!SourceUpdateSchedulePolicy.initialIdleSliceExpired(initial,slowPlayback,started,
        android.os.SystemClock.elapsedRealtime()))return false;
    if(initialSliceExpired.compareAndSet(false,true))
      android.util.Log.w("MyTVSourceDiscovery","initial_idle_slice_expired; retain committed checkpoint");
    return true;
  }

  private boolean shouldYieldToViewing(SharedPreferences prefs, boolean manual,
      boolean initialDiscoveryChain, boolean hasPublishedChannels,long stableGeneration){
    return (slowPlayback?!SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime(),stableGeneration):SourceUpdateActivityPolicy.isPlaybackActive())
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
      boolean playback=SourceUpdateActivityPolicy.isPlaybackActive();
      boolean inputYield=SourceUpdateActivityPolicy.idleWavePressureChanged(idleWavePressureGeneration);
      com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),
          "source_initial_progress_phase",initialSliceExpired.get()
               ?"本批空闲抓源超过安全时限，保留已保存断点，自动续抓":playback
               ?"播放资源占用：本批抓源暂停，已保存进度"
               :inputYield?"频道操作优先：高并发检测已让出，已保存进度"
               :"本批任务已让出或取消，已保存进度，等待续抓");
      if(!playback&&!isStopped()&&!Thread.currentThread().isInterrupted()){
        try{
          enqueueInitialContinuation(SourceUpdateSchedulePolicy.initialPressureDelaySeconds(
              playback,inputYield));
          return Result.success();
        }catch(Exception failure){
          android.util.Log.w("MyTVSourceDiscovery","Unable to schedule idle-yield continuation",failure);
          return Result.retry();
        }
      }
      return continueAfterViewingDelay(false);
    }
    // Do not repeatedly wake the periodic job every few minutes while a user
    // watches television; the next daily check retains the overdue monthly intent.
    return SourceUpdateSchedulePolicy.shouldRetryWhenBusy(manual,initialDiscoveryChain)
        ?Result.retry():Result.success();
  }

  private boolean isActivityActive(SharedPreferences prefs){
    return SourceUpdateActivityPolicy.isActivityActive();
  }
  static final class Entry{
    final String name,group,tvgId,tvgName;
    String scope;boolean savedSource,skipReplay,alreadyStaged;
    final String url;
    private String canonicalEndpoint;
    private String canonicalMergeIdentity;
    private String canonicalHost;
    private String canonicalOrigin;
    // Immutable original URL, owner-confined lazy identities. Preserve the
    // existing independent host and scheme/port semantics.
    String host(){
      if(canonicalHost==null)canonicalHost=probeHost(url);
      return canonicalHost;
    }
    String origin(){
      if(canonicalOrigin==null)canonicalOrigin=InitialOriginCooldownPolicy.origin(url);
      return canonicalOrigin;
    }
    // Immutable candidate metadata makes the same key reusable throughout
    // this owner's page preparation, Room lookup and merge.
    String mergeIdentity(){
      if(canonicalMergeIdentity==null)
        canonicalMergeIdentity=SourceDiscoveryIdentityPolicy.identity(name,group,url,tvgId,tvgName);
      return canonicalMergeIdentity;
    }
    // Candidate identities belong to the serial source-work owner. The HTTP
    // task receives the original immutable URL, never this normalized key.
    String endpoint(){
      if(canonicalEndpoint==null)canonicalEndpoint=SourceSelectionPolicy.endpointKey(url);
      return canonicalEndpoint;
    }
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
  private static boolean recoveredDirectoryScanned(FeedBatch batch){
    return SourceUpdateSchedulePolicy.recoveredDirectoryScanned(batch.success,batch.unavailable,
        batch.cursor,batch.parsed,batch.indexedEntries);
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
  private JSONArray preservePreviouslyPublishedChannels(JSONArray discovered,JSONArray original)throws JSONException {
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    if(original.length()==0)return discovered;
    LinkedHashMap<String,ArrayDeque<JSONObject>> originalsByIdentity=new LinkedHashMap<>();
    for(int i=0;i<original.length();i++){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      JSONObject old=original.getJSONObject(i);
      String key=identity(old.optString("name"),old.optString("group"),
          old.optString("url"),old.optString("tvgId"),old.optString("tvgName"));
      originalsByIdentity.computeIfAbsent(key,k->new ArrayDeque<>()).add(old);
    }
    Set<JSONObject> matched=Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());
    JSONArray publish=new JSONArray();
    for(int i=0;i<discovered.length();i++){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
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
    for(int i=0;i<original.length();i++){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      JSONObject old=original.getJSONObject(i);
      if(!matched.contains(old))publish.put(old);
    }
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
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
  private void commitInitialAcquisition(int index,File cached) throws IOException {
    String version=PlaylistFeedCache.contentVersion(cached);
    if(PlaylistFeedIndex.indexedEntryCount(cached)<0)throw new IOException("Prepared directory index unavailable");
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    // Defer additional eviction work while playing; the idle preparation path
    // accounts for the newly generated index before recording acquisition.
    if(!slowPlayback)PlaylistFeedCache.trimPreparedInitial(getApplicationContext(),cached);
    SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
    new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
        .putString(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(index),version)
        .putInt("source_initial_next_feed_index",index)
        .remove(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY)
        .putString("source_initial_progress_phase","目录已准备，下一批继续线路初筛")
        .apply();
  }
  static String initialFeedDoneKey(int index){return "source_initial_feed_done_"+index;}
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
  /** Only reuse an already accepted route association from THIS build's
   * committed staging. New provenance, metadata enrichment, URL aliases,
   * routine refresh and designated rechecks still follow normal probes. */
  static boolean mayReuseRetainedPreflight(JSONObject scores,Set<String> provisional,String url){
    if(scores==null||provisional==null||url==null||url.isEmpty())return false;
    String key=StableKey.url(url),legacy=StableKey.legacyUrl(url);
    if(provisional.contains(key)||provisional.contains(legacy))return false;
    // Require the exact current score rather than falling back to old hashed
    // keys; an evicted score or a later negative observation is not proof.
    double score=scores.optDouble(key,Double.NaN);
    return Double.isFinite(score)&&score>0.0;
  }
  /** Scheduling hint only, not proof of successful playback. */
  static boolean hasPositiveRetainedCoverage(JSONObject row,JSONObject scores,Set<String> provisional){
    if(row==null)return false;
    JSONArray routes=row.optJSONArray("sources");
    if(routes==null)return false;
    for(int i=0;i<routes.length();i++){
      String url=routes.optString(i,"");
      if((url.startsWith("http://")||url.startsWith("https://"))
          &&mayReuseRetainedPreflight(scores,provisional,url))return true;
    }
    return false;
  }
  static boolean hasCommittedRouteEvidence(JSONObject staged,Entry entry,String displayName){
    if(staged==null||entry==null||entry.savedSource
        ||!ChannelCatalogueScopePolicy.isRetainableScope(entry.scope)
        ||!staged.optString("name","").equals(displayName)
        ||!staged.optString("group","").equals(entry.group)
        ||(!entry.tvgId.isEmpty()&&!entry.tvgId.equals(staged.optString("tvgId","")))
        ||(!entry.tvgName.isEmpty()&&!entry.tvgName.equals(staged.optString("tvgName",""))))return false;
    JSONArray routes=staged.optJSONArray("sources");
    if(routes==null)return false;
    boolean retained=false;
    for(int i=0;i<routes.length();i++)if(entry.url.equals(routes.optString(i,""))){retained=true;break;}
    if(!retained)return false;
    JSONArray ownership=staged.optJSONArray("backupSourceScopes");
    if(ownership==null)return false;
    for(int i=0;i<ownership.length();i++){
      JSONObject item=ownership.optJSONObject(i);
      if(item==null||!entry.url.equals(item.optString("url","")))continue;
      JSONArray scopes=item.optJSONArray("scopes");
      if(scopes!=null)for(int j=0;j<scopes.length();j++)
        if(entry.scope.equals(scopes.optString(j,"")))return true;
    }
    return false;
  }
  private static boolean hasChinaScope(JSONObject channel){
    JSONArray scopes=channel.optJSONArray("sourceScopes");
    if(scopes!=null)for(int i=0;i<scopes.length();i++)
      if("china".equals(scopes.optString(i)))return true;
    return false;
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
  // Only the source owner reads/writes this map and all persisted/cache state.
  // Network tasks return a result, never merge a channel or advance a cursor.
  private static final class TimedProbe {
    final ProbeResult result; final long deadline,finishedAt;
    TimedProbe(ProbeResult r,long deadline,long finishedAt){
      this.result=r;this.deadline=deadline;this.finishedAt=finishedAt;
    }
  }
  private static String probeHost(String url){
    try{return new URL(url).getHost().toLowerCase(Locale.ROOT);}
    catch(Exception invalid){return "";}
  }
  private final class IdleProbeWave {
    private java.util.concurrent.ExecutorService executor;
    private final Map<String,java.util.concurrent.Future<TimedProbe>> pending=new HashMap<>();
    int launched,serialProbes;
    final java.util.concurrent.atomic.AtomicInteger activeProbes=new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger peakProbes=new java.util.concurrent.atomic.AtomicInteger();
    boolean retainWaveEvidence;
    long waveBatchDeadline;
    List<Entry> rollingEntries;
    Map<String,ProbeResult> rollingMeasured;
    Set<String> rollingVisited;
    int rollingNext,rollingRemaining;
    boolean has(String endpoint){return pending.containsKey(endpoint);}
    boolean hasPending(){return !pending.isEmpty();}
    
    boolean start(List<Entry> entries,int offset,Map<String,ProbeResult> measured,
        Set<String> visited,long deadline,long batchDeadline,boolean initial,int remainingRequests,
        boolean rolling){
      rollingEntries=null;
      if(!pending.isEmpty())throw new IllegalStateException("Unconsumed probe wave");
      if(pauseRequested.getAsBoolean())throw new SourceProbeTimePolicy.PublicationPausedException();
      long now=android.os.SystemClock.elapsedRealtime();
      long pressureAtAdmission=SourceUpdateActivityPolicy.pressureGeneration();
      int pressureCeiling=SourceUpdateActivityPolicy.idleProbeConcurrency(now,initial);
      int networkCeiling=initial?INITIAL_NETWORK.limit(pressureCeiling,now):pressureCeiling;
      int limit=Math.min(remainingRequests,networkCeiling);
      if(limit<2)return false;
      ArrayList<Entry> wave=new ArrayList<>();
      IdleProbeWavePolicy wavePolicy=new IdleProbeWavePolicy(limit);
      int i=offset;
      for(;i<entries.size()&&wave.size()<limit;i++){
        Entry entry=entries.get(i);
        if(initial&&entry.savedSource)break;
        if(entry.skipReplay||entry.alreadyStaged)continue;
        boolean http=entry.url.startsWith("http://")||entry.url.startsWith("https://");
        String endpoint=entry.endpoint();
        // An already measured or unsupported row cannot issue another network
        // request in the owner loop. Bridge only these deterministic gaps, and
        // keep the original row order/cursor. Never bridge cooling, expiring
        // cache entries, unresolved duplicates or host-limited fresh probes.
        if(initial&&i!=offset&&IdleProbeWavePolicy.canBridgeKnownRow(http,measured.containsKey(endpoint)))continue;
        if(!http)break;
        String host=entry.host();
        if(!wavePolicy.canAdd(endpoint,host))break;
        if(initial&&INITIAL_ORIGINS.requiresSerialProbe(entry.origin(),now))break;
        if(i!=offset){
          RecentProbe cached=(initial||!manualProbeCacheOwner.isEmpty())
              ?reusableInitialProbe(endpoint,slowPlayback,now):null;
          if(measured.containsKey(endpoint)||visited.contains(endpoint)||cached!=null)break;
        }
        wavePolicy.add(endpoint,host);wave.add(entry);
      }
      // Candidate preparation may consume the remaining time budget.
      now=android.os.SystemClock.elapsedRealtime();
      if(wave.size()<2||!IdleProbeWavePolicy.canStart(initial,now,deadline,batchDeadline)
          ||wave.size()>SourceUpdateActivityPolicy.idleProbeConcurrency(android.os.SystemClock.elapsedRealtime(),initial))return false;
      // Manual refresh and first build share the same high-concurrency
      // input-pressure gate. A user request to refresh is not permission
      // to compete with later remote-control or touch interaction.
      if(wave.size()>2&&idleWavePressureGeneration<0L){
        idleWavePressureGeneration=pressureAtAdmission;
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      }
      if(executor==null)executor=java.util.concurrent.Executors.newFixedThreadPool(initial?IdleProbeWavePolicy.MAX_CONCURRENT:IdleProbeWavePolicy.ORDINARY_CONCURRENT,r->{
        return new Thread(()->{
          try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);}
          catch(RuntimeException ignored){}
          r.run();
        },"mytv-idle-probe");
      });
      retainWaveEvidence=initial||!manualProbeCacheOwner.isEmpty();
      waveBatchDeadline=batchDeadline;
      for(Entry entry:wave)submit(entry,deadline);
      if(rolling&&initial){
        rollingEntries=entries;rollingMeasured=measured;rollingVisited=visited;
        rollingNext=i;rollingRemaining=remainingRequests-wave.size();
      }
      return true;
    }
    private void submit(Entry entry,long deadline){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      final String url=entry.url;
      final String endpoint=entry.endpoint();
      pending.put(endpoint,executor.submit(()->{
        int active=activeProbes.incrementAndGet();
        peakProbes.accumulateAndGet(active,Math::max);
        try{
          ProbeResult result=probe(url,deadline);
          return new TimedProbe(result,deadline,android.os.SystemClock.elapsedRealtime());
        }finally{activeProbes.decrementAndGet();}
      }));
      launched++;
    }
    /** Refill freed network slots while the ordered owner waits for a slow
     * result. Do not change candidate order or commit a speculative suffix.
     * Only cross rows already proved not to require another network request. */
    private void refillRolling(){
      if(rollingEntries==null||rollingRemaining<=0)return;
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      long now=android.os.SystemClock.elapsedRealtime();
      long pressureAtAdmission=SourceUpdateActivityPolicy.pressureGeneration();
      int ceiling=INITIAL_NETWORK.limit(SourceUpdateActivityPolicy.idleProbeConcurrency(now,true),now);
      IdleProbeWavePolicy active=new IdleProbeWavePolicy(ceiling);
      int running=0;
      for(Map.Entry<String,java.util.concurrent.Future<TimedProbe>> task:pending.entrySet()){
        if(task.getValue().isDone())continue;
        running++;
        String endpoint=task.getKey(),host=probeHost(endpoint);
        if(!active.canAdd(endpoint,host))return;
        active.add(endpoint,host);
      }
      int slots=IdleProbeWavePolicy.rollingAdmissionSlots(ceiling,running,pending.size(),rollingRemaining);
      while(slots>0&&rollingNext<rollingEntries.size()){
        Entry entry=rollingEntries.get(rollingNext);
        if(entry.savedSource)return;
        if(entry.skipReplay||entry.alreadyStaged){rollingNext++;continue;}
        boolean http=entry.url.startsWith("http://")||entry.url.startsWith("https://");
        String endpoint=entry.endpoint();
        if(IdleProbeWavePolicy.canBridgeKnownRow(http,rollingMeasured.containsKey(endpoint))){
          rollingNext++;continue;
        }
        // Cache-only, unresolved duplicate and host/cooldown barriers are
        // still handled by the normal owner. Never jump past fresh work.
        if(pending.containsKey(endpoint)||rollingVisited.contains(endpoint))return;
        String host=entry.host();
        if(!active.canAdd(endpoint,host))return;
        now=android.os.SystemClock.elapsedRealtime();
        if(INITIAL_ORIGINS.requiresSerialProbe(entry.origin(),now))return;
        if(reusableInitialProbe(endpoint,slowPlayback,now)!=null)return;
        long deadline=SourceProbeTimePolicy.initialAddressDeadline(waveBatchDeadline,now);
        if(deadline==0L||!IdleProbeWavePolicy.canStart(true,now,deadline,waveBatchDeadline))return;
        int currentCeiling=INITIAL_NETWORK.limit(SourceUpdateActivityPolicy.idleProbeConcurrency(now,true),now);
        if(running>=currentCeiling||currentCeiling<2)return;
        if(running>=2&&idleWavePressureGeneration<0L)idleWavePressureGeneration=pressureAtAdmission;
        SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
        active.add(endpoint,host);
        submit(entry,deadline);
        rollingNext++;rollingRemaining--;running++;slots--;
      }
    }
    TimedProbe take(String endpoint,long batchDeadline)throws Exception{
      java.util.concurrent.Future<TimedProbe> future=pending.get(endpoint);
      if(future==null)throw new IllegalStateException("Missing ordered probe result");
      for(;;){
        if(pauseRequested.getAsBoolean())throw new SourceProbeTimePolicy.PublicationPausedException();
        long remaining=batchDeadline-android.os.SystemClock.elapsedRealtime();
        if(remaining<=0&&!future.isDone())throw new java.util.concurrent.TimeoutException("Probe wave exceeded slice budget");
        try{
          // Even a completed head may have freed slots. Refill within the
          // same bounded pending/result window rather than wait for a slow sibling.
          if(remaining>0L)refillRolling();
          TimedProbe outcome=future.get(Math.max(0L,Math.min(100L,remaining)),java.util.concurrent.TimeUnit.MILLISECONDS);
          pending.remove(endpoint);return outcome;
        }catch(java.util.concurrent.TimeoutException waiting){/* poll viewing gate */}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SourceProbeTimePolicy.PublicationPausedException();}
      }
    }
    // Owner-only: harvest already completed positive observations before
    // cancelling unfinished work. No cursor, quality score or channel is
    // committed here; the next owner must consume the exact candidate again.
    private void retainCompletedSuccesses(){
      if(!retainWaveEvidence)return;
      for(Map.Entry<String,java.util.concurrent.Future<TimedProbe>> entry:pending.entrySet()){
        java.util.concurrent.Future<TimedProbe> task=entry.getValue();
        if(!task.isDone()||task.isCancelled())continue;
        try{
          TimedProbe outcome=task.get(0L,java.util.concurrent.TimeUnit.MILLISECONDS);
          retainCompletedInitialSuccess(entry.getKey(),outcome.result,outcome.finishedAt,
              outcome.deadline,waveBatchDeadline);
        }catch(InterruptedException interrupted){
          Thread.currentThread().interrupt();break;
        }catch(java.util.concurrent.ExecutionException|java.util.concurrent.CancellationException
            |java.util.concurrent.TimeoutException ignored){}
      }
    }
    void finishBeforeCommit()throws java.util.concurrent.TimeoutException{
      // The owner has consumed only a contiguous prefix. A completed but
      // unconsumed suffix can be discarded safely; its cursor is not advanced.
      // Never write while a speculative request is still running.
      if(!IdleProbeWavePolicy.allFinished(pending.values()))
        throw new java.util.concurrent.TimeoutException("Unconsumed probe wave still running");
      retainCompletedSuccesses();
      pending.clear();
      if(executor!=null)executor.shutdown();
    }
    void close(){
      java.util.concurrent.ExecutorService owned=executor;
      if(owned==null)return;
      executor=null; // owner-confined; finally may close again safely
      retiringProbeExecutor=owned;
      if(pending.isEmpty())owned.shutdown();
      else{
        int outstanding=pending.size();
        retainCompletedSuccesses();
        for(java.util.concurrent.Future<TimedProbe> task:pending.values())task.cancel(true);
        owned.shutdownNow();
        android.util.Log.i("MyTVSourceDiscovery","probe_cancel_signalled pending="+outstanding
            +" terminated="+owned.isTerminated());
      }
      pending.clear();
    }
  }
  static final class ProbeResult {
    boolean valid;
    boolean socketTimedOut;
    boolean transientNetworkFailure,incompleteManifest,incompleteSample,unsupportedPreflight,emptyLiveWindow;
    boolean inconclusive(){return socketTimedOut||transientNetworkFailure||incompleteManifest||incompleteSample||unsupportedPreflight||emptyLiveWindow;}
    // Diagnostics only: one primary reason per already-inconclusive result.
    enum UncertainReason { SOCKET, TRANSPORT, BUDGET, MEDIA, PACED }
    UncertainReason uncertainReason(boolean budgetExpired){
      if(socketTimedOut)return UncertainReason.SOCKET;
      if(transientNetworkFailure)return UncertainReason.TRANSPORT;
      if(budgetExpired)return UncertainReason.BUDGET;
      return inconclusive()?UncertainReason.MEDIA:UncertainReason.PACED;
    }
    // Reuse the existing bounded domestic-core suffix replay for a genuinely
    // temporary empty live window, but not for a deterministic parser limit.
    boolean recoverableCoreWindow(){return socketTimedOut||emptyLiveWindow;}
    boolean deterministicEvidenceLimit(){return (unsupportedPreflight||incompleteManifest||emptyLiveWindow)
        &&!socketTimedOut&&!transientNetworkFailure&&!incompleteSample;}
    boolean paced,provisional;
    boolean firstRequestFailed,firstRequestReached,firstOriginPressure;
    // Local to one probe, allocated only after an explicit pressure response.
    private HashSet<String> pressureOrigins;
    boolean originDeferred(String origin){
      return pressureOrigins!=null&&pressureOrigins.contains(origin);
    }
    void noteOriginPressure(String origin){
      if(origin==null||origin.isEmpty())return;
      if(pressureOrigins==null)pressureOrigins=new HashSet<>();
      pressureOrigins.add(origin);
    }
    boolean hasOriginPressure(){
      return firstOriginPressure||(pressureOrigins!=null&&!pressureOrigins.isEmpty());
    }
    // Only the serial owner calls this for fresh observations. A healthy
    // sibling does not erase an explicit pressure signal from another host.
    void publishOriginHealth(InitialOriginCooldownPolicy policy,String origin,String endpoint,long now){
      // Pressure wins over mere transport reachability, including a throttled
      // child on the same origin. Do not erase recovery history just before
      // recording the very response that demonstrates persistent pressure.
      if(firstOriginPressure||originDeferred(origin))policy.observePressure(origin,now);
      else policy.observe(origin,endpoint,now,firstRequestFailed,firstRequestReached);
      if(pressureOrigins!=null)for(String limited:pressureOrigins)
        if(!limited.equals(origin))policy.observePressure(limited,now);
    }
    int score;
    int width,height;
    long latencyMs;
    long bitrate;
    long sampleRequests,sampleBytes;
  }
  // Keep the persisted preflight-score index bounded. Discovery feeds can rotate
  // thousands of URLs over time; an unbounded JSON object would slow every update
  // and gradually consume device storage. The current scan rewrites recent entries.
  private static final int MAX_PERSISTED_PROBE_SCORES = 8000;
  private void trimProbeScores(JSONObject scores,List<Entry> savedPool,List<Entry> recentCandidates,
      boolean preferValidated,Set<String> provisional){
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
    fillRetainedScoreKeys(scores,keep,MAX_PERSISTED_PROBE_SCORES,preferValidated,provisional,pauseRequested);
    // JSONObject ordering is not a retention guarantee. Preserve precisely
    // the chosen key/value set in this slice, without clearing/reinserting
    // thousands of unchanged entries. Remove after the iteration completes.
    for(String key:SourceRankingRetentionPolicy.surplusScoreKeys(
        scores.keys(),keep,pauseRequested)){
      SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
      scores.remove(key);
    }
  }
  /** After required and recent keys, keep measured positive evidence ahead
   * of arbitrary historical negatives in the bounded initial compatibility
   * cache. Not a validity certificate; normal ownership/reuse gates remain. */
  static void fillRetainedScoreKeys(JSONObject scores,Set<String> keep,int limit,
      boolean preferValidated,Set<String> provisional,java.util.function.BooleanSupplier paused){
    if(preferValidated&&keep.size()<limit){
      Iterator<String> keys=scores.keys();
      while(keys.hasNext()&&keep.size()<limit){
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        String key=keys.next();
        double score=scores.optDouble(key,Double.NaN);
        if(key.startsWith("v2:")&&Double.isFinite(score)&&score>0
            &&!provisional.contains(key))keep.add(key);
      }
    }
    Iterator<String> keys=scores.keys();
    while(keys.hasNext()&&keep.size()<limit){
      SourceProbeTimePolicy.checkPublicationPaused(paused);
      keep.add(keys.next());
    }
  }
  static void storePreflightScore(JSONObject scores,String key,String legacyKey,ProbeResult result)throws JSONException{
    double prior=scores.has(key)?scores.optDouble(key,0.0):scores.optDouble(legacyKey,0.0);
    scores.put(key,result.valid?SourceQualityScorer.successfulPreflightScore(result.paced,result.provisional,prior,result.score):-500.0);
    scores.remove(legacyKey);
  }
  private String scoreKey(String url){return StableKey.url(url);}
  private String legacyScoreKey(String url){return StableKey.legacyUrl(url);}
  private java.util.Comparator<String> rankingComparator(JSONObject probes,JSONObject qoe){
    // Scores can change between probe merges, so never share this cache across
    // operations. Freeze recency time within a comparison to keep it consistent.
    final long now=System.currentTimeMillis();
    return SourceRankingRetentionPolicy.memoizedBestFirst(url->combinedScore(url,probes,qoe,now));
  }
  private double combinedScore(String url,JSONObject probes,JSONObject qoe,long now){
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
      score+=qoeAdjustment*SourceQoeScoringPolicy.historyWeight(m.optLong("updated",0L),now);
      score+=SourceQoeScoringPolicy.recentSuccessBonus(
          m.optLong("lastFirstFrame",0L),m.optLong("lastStable",0L),now);
    }
    return score;
  }
  // Sniff the response body as well as the URL: some IPTV gateways expose
  // HLS manifests through extensionless or tokenized endpoint URLs.
  private ProbeResult probe(String address,long deadline){
    return probe(address,deadline,slowPlayback,pauseRequested,
        getInputData().getBoolean("initial_discovery_chain",false)&&!slowPlayback,allowProvisionalScreen);
  }
  // Same production HTTP path, callable by host loopback regression tests.
  static ProbeResult probe(String address,long deadline,boolean slowPlayback,
      java.util.function.BooleanSupplier pauseRequested){
    return probe(address,deadline,slowPlayback,pauseRequested,false);
  }
  static ProbeResult probe(String address,long deadline,boolean slowPlayback,
      java.util.function.BooleanSupplier pauseRequested,boolean initialIdle){
    return probe(address,deadline,slowPlayback,pauseRequested,initialIdle,initialIdle);
  }
  static ProbeResult probe(String address,long deadline,boolean slowPlayback,
      java.util.function.BooleanSupplier pauseRequested,boolean initialIdle,boolean allowProvisional){
    ProbeResult result=new ProbeResult();
    result.paced=slowPlayback;
    long started=android.os.SystemClock.elapsedRealtime();
    try{
      if(probeSampleInterrupted(deadline,pauseRequested,result))return result;
      SampleResponse initialResponse;
      String lower=address.toLowerCase(Locale.ROOT);
      boolean playlistUrl=lower.contains(".m3u8")||lower.contains("m3u8?");
      try{
        initialResponse=fetchSampleResponse(address,32768,deadline,slowPlayback,pauseRequested,
            false,allowProvisional&&initialIdle&&!slowPlayback&&!playlistUrl,result,playlistUrl,true);
        result.firstRequestReached=initialResponse!=null;
      }catch(TransientProbeHttpException failure){
        // A redirect may have reached a different origin. Do not penalize the
        // requested origin for a different server's explicit pressure.
        result.firstOriginPressure=(failure.code==429||failure.code==503)
            &&!failure.origin.isEmpty()&&failure.origin.equals(InitialOriginCooldownPolicy.origin(address));
        throw failure;
      }catch(java.net.SocketTimeoutException|java.net.ConnectException|java.net.UnknownHostException failure){
        result.firstRequestFailed=true;
        throw failure;
      }
      byte[] initialBytes=initialResponse==null?null:initialResponse.bytes;
      if(initialBytes==null||initialBytes.length<7)return result;
      if(initialResponse.earlyDirectTs){
        // Preliminary structural evidence only; do not extrapolate short-prefix
        // throughput into a full-sample quality score.
        result.valid=true;
        result.latencyMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-started);
        result.score=350;
        result.provisional=true;
        return result;
      }
      boolean hlsPlaylist=HlsVariantPolicy.hasPlaylistMarker(initialBytes);
      if(playlistUrl||hlsPlaylist){
        if(!hlsPlaylist)return result;
        // A capped manifest can hide a later key/map change or missing segment.
        // Do not issue dependent requests or penalize a source on partial metadata.
        if(initialResponse.truncated){result.incompleteManifest=true;return result;}
        SampleResponse manifestResponse=initialResponse;
        String manifest=HlsVariantPolicy.decodePlaylist(initialBytes);
        String manifestUrl=manifestResponse.finalUrl;
        ArrayList<HlsVariantPolicy.Variant> variants=initialIdle||slowPlayback
            ?HlsVariantPolicy.parseWithOverflow(manifest,manifestUrl)
            :HlsVariantPolicy.parseWithRotatingFallback(manifest,manifestUrl,System.currentTimeMillis()/86400000L);
        boolean untestedRenditions=variants.size()>HlsVariantPolicy.MAX_VARIANTS;
        if(untestedRenditions)variants.remove(variants.size()-1);
        if(variants.isEmpty())variants.add(new HlsVariantPolicy.Variant(manifestUrl,0L,0,0));
        // Select a rendition only after every required sample for that rendition
        // passes. A stale second segment in a high-bitrate rendition must not
        // prevent trying a healthy lower-bitrate sibling within the same budget.
        // Positive clear initialization evidence is local to this single probe.
        // Do not reuse encrypted, failed, or historical resources.
        HashSet<String> confirmedClearMaps=new HashSet<>();
        // At most one metadata pair per attempted rendition; never retain
        // media bytes or reuse evidence across endpoint probes.
        HashMap<String,ClearSegmentEvidence> confirmedClearFirstSegments=new HashMap<>();
        for(int variantIndex=0;variantIndex<variants.size();variantIndex++){
          HlsVariantPolicy.Variant candidate=variants.get(variantIndex);
          if(probeSampleInterrupted(deadline,pauseRequested,result))return result;
          final long renditionDeadline=SourceProbeTimePolicy.initialRenditionDeadline(
              SourceProbeTimePolicy.reserveRenditionBudget(initialIdle,slowPlayback,started,deadline),
              variantIndex+1<variants.size(),
              android.os.SystemClock.elapsedRealtime(),deadline);
          try{
            String candidateManifestUrl=candidate.url;
            String candidateManifest=manifest;
            if(!candidate.url.equals(manifestUrl)){
              SampleResponse variantResponse=fetchSampleResponse(candidate.url,65536,renditionDeadline,slowPlayback,pauseRequested,false,false,result,true);
              byte[] variant=variantResponse==null?null:variantResponse.bytes;
              if(variant==null||variant.length==0)continue;
              if(!HlsVariantPolicy.hasPlaylistMarker(variant))continue;
              if(variantResponse.truncated){result.incompleteManifest=true;continue;}
              candidateManifest=HlsVariantPolicy.decodePlaylist(variant);
              candidateManifestUrl=variantResponse.finalUrl;
            }
            HlsMediaPlaylistPolicy.Resources resources=
                HlsMediaPlaylistPolicy.parse(candidateManifest,candidateManifestUrl);
            ArrayList<String> candidateSegments=resources.mediaUrls;
            if(resources.requiresUnsupportedPreflight()){
              result.unsupportedPreflight=true;continue;
            }
            if(candidateSegments.isEmpty()){
              // A live media playlist can temporarily have no available
              // segments (startup, encoder gap, LL-HLS publication delay).
              // Keep it unconfirmed, not a permanent negative observation.
              if(HlsMediaPlaylistPolicy.isEmptyLiveWindow(candidateManifest))
                result.emptyLiveWindow=true;
              continue;
            }
            // Fetch identity AES-128 key only for in-memory preflight; never log or persist key bytes.
            byte[] aesKey=null;
            if(resources.aes128Encrypted){
              aesKey=fetchSample(resources.encryptionKeyUrl,32,renditionDeadline,slowPlayback,pauseRequested,result);
              if(aesKey==null||aesKey.length!=16)continue;
            }
            // fMP4/CMAF playlists require the EXT-X-MAP initialization resource.
            // AES-128 maps require their explicit IV and must validate after decryption.
            boolean reusableClear=!slowPlayback&&!resources.aes128Encrypted;
            if(resources.initializationUrl!=null&&!(reusableClear
                &&confirmedClearMaps.contains(resources.initializationUrl))){
              SampleResponse initializationResponse=fetchSampleResponse(resources.initializationUrl,32768,renditionDeadline,slowPlayback,pauseRequested,false,false,result);
              byte[] initialization=initializationResponse==null?null:initializationResponse.bytes;
              if(initialization==null)continue;
              if(resources.aes128Encrypted){
                byte[] mapIv=HlsAes128Preflight.parseExplicitIv(resources.encryptionIv);
                if(mapIv==null||!HlsAes128Preflight.validatesInitializationSegment(
                    initialization,aesKey,mapIv,HlsProbeValidationPolicy.isCompleteSample(initializationResponse.truncated)))continue;
              }else{
                if(!MediaPayloadValidator.hasIsoBmffInitializationSegment(initialization))continue;
                if(reusableClear)confirmedClearMaps.add(resources.initializationUrl);
              }
            }
            String candidateSegmentUrl=candidateSegments.get(0);
            long candidateSegmentStart=android.os.SystemClock.elapsedRealtime();
            // Sharing confirmed clear evidence is independent of shortening
            // the sample. Only initial screening can use a provisional prefix.
            boolean screenFirst=initialIdle&&allowProvisional&&reusableClear&&candidateSegments.size()>1;
            ClearSegmentEvidence evidence=reusableClear?confirmedClearFirstSegments.get(candidateSegmentUrl):null;
            if(evidence!=null&&evidence.provisional&&!screenFirst)evidence=null;
            if(evidence==null){
              SampleResponse candidateSampleResponse=fetchSampleResponse(candidateSegmentUrl,32768,renditionDeadline,slowPlayback,pauseRequested,false,screenFirst,result);
              byte[] candidateSample=candidateSampleResponse==null?null:candidateSampleResponse.bytes;
              if(candidateSample==null)continue;
              if(resources.aes128Encrypted){
                byte[] segmentIv=resources.encryptionIv!=null
                    ?HlsAes128Preflight.parseExplicitIv(resources.encryptionIv)
                    :HlsAes128Preflight.ivForMediaSequence(resources.mediaSequenceNumbers.get(0));
                if(!HlsAes128Preflight.validatesMediaSegment(
                    candidateSample,aesKey,segmentIv,HlsProbeValidationPolicy.isCompleteSample(candidateSampleResponse.truncated)))continue;
              }else if(!MediaPayloadValidator.looksLikeHlsSegment(candidateSample))continue;
              if(probeSampleInterrupted(renditionDeadline,pauseRequested,result))continue;
              evidence=new ClearSegmentEvidence(candidateSample.length,
                  Math.max(1L,android.os.SystemClock.elapsedRealtime()-candidateSegmentStart),candidateSampleResponse.earlyDirectTs);
              if(reusableClear)confirmedClearFirstSegments.put(candidateSegmentUrl,evidence);
            }
            if(candidateSegments.size()>1){
              // Parser already resolves and deduplicates media URLs. Only
              // same-probe clear positive first-segment evidence may satisfy
              // confirmation of a distinct second URL in a sibling rendition.
              String secondUrl=candidateSegments.get(1);
              boolean secondSegmentOk=reusableClear
                  &&confirmedClearFirstSegments.containsKey(secondUrl);
              if(!secondSegmentOk){
                SampleResponse secondResponse=fetchSampleResponse(secondUrl,8192,renditionDeadline,slowPlayback,pauseRequested,
                    initialIdle&&reusableClear,false,result);
                byte[] second=secondResponse==null?null:secondResponse.bytes;
                if(resources.aes128Encrypted&&second!=null){
                  byte[] secondIv=resources.encryptionIv!=null
                      ?HlsAes128Preflight.parseExplicitIv(resources.encryptionIv)
                      :HlsAes128Preflight.ivForMediaSequence(resources.mediaSequenceNumbers.get(1));
                  secondSegmentOk=HlsAes128Preflight.validatesMediaSegment(
                      second,aesKey,secondIv,HlsProbeValidationPolicy.isCompleteSample(secondResponse.truncated));
                }else secondSegmentOk=MediaPayloadValidator.looksLikeHlsSegment(second);
              }
              if(!HlsProbeValidationPolicy.acceptsSecondSegment(secondSegmentOk))continue;
            }
            if(probeSampleInterrupted(renditionDeadline,pauseRequested,result))continue;
            result.socketTimedOut=false;
            result.transientNetworkFailure=false;
            result.incompleteManifest=false;
            result.emptyLiveWindow=false;
            result.incompleteSample=false;
            result.unsupportedPreflight=false;
            result.valid=true;result.provisional=evidence.provisional;
            result.width=candidate.width;result.height=candidate.height;
            result.bitrate=candidate.bandwidth;result.latencyMs=Math.max(1L,candidateSegmentStart-started);
            int resolution=(candidate.width>=1920||candidate.height>=1080)?900:(candidate.width>=1280||candidate.height>=720)?700:(candidate.width>=854||candidate.height>=480)?500:300;
            int bandwidth=(int)Math.min(500L,candidate.bandwidth/12000L);
            // A short structural sample is not a throughput measurement.
            int speed=SourceQualityScorer.preflightSpeedBonus(result.paced||result.provisional,evidence.bytes,evidence.elapsedMs);
            int latencyPenalty=SourceQualityScorer.preflightLatencyPenalty(result.paced,result.latencyMs);
            result.score=500+resolution+bandwidth+speed-latencyPenalty+300+120;
            return result;
          }catch(java.net.SocketTimeoutException timeout){
            // A sibling may still prove playable; otherwise preserve uncertainty.
            result.socketTimedOut=true;
          }catch(java.io.IOException unavailable){
            // A broken rendition must not reject the rest of the master.
            if(unavailable instanceof TransientProbeHttpException
                ||SourceProbeTimePolicy.transientProbeNetworkException(unavailable))
              result.transientNetworkFailure=true;
          }finally{
            // Budget exhaustion of a rendition is uncertainty, not a dead
            // endpoint. A later fully validated sibling clears this flag.
            if(!result.valid&&android.os.SystemClock.elapsedRealtime()>=renditionDeadline)
              result.socketTimedOut=true;
          }
        }
        // Exhausting a bounded selection is not evidence against the
        // untested distinct renditions. A successful sibling returned above.
        if(untestedRenditions)result.unsupportedPreflight=true;
        return result;
      }
      result.latencyMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-started);
      // Direct media validity depends on sampled content, not the URL suffix.
      if(!MediaPayloadValidator.looksLikeMediaPayload(initialBytes))return result;
      result.valid=true;
      result.score=350+SourceQualityScorer.preflightSpeedBonus(result.paced,initialBytes.length,result.latencyMs);
      return result;
    }catch(java.net.SocketTimeoutException timeout){result.socketTimedOut=true;return result;}
    catch(java.io.IOException unavailable){
      result.transientNetworkFailure|=unavailable instanceof TransientProbeHttpException
          ||SourceProbeTimePolicy.transientProbeNetworkException(unavailable);
      return result;
    }
    catch(Exception e){return result;}
    finally{
      // A pause can arrive inside a blocked HTTP operation, including one
      // that throws before the next ordinary cancellation check. Treat all
      // exit paths consistently; never publish an expired or cancelled result.
      if(probeSampleInterrupted(deadline,pauseRequested,result)){
        result.valid=false;
        result.provisional=false;
      }
    }
  }
  private static final class ClearSegmentEvidence {
    final int bytes;
    final long elapsedMs;
    final boolean provisional;
    ClearSegmentEvidence(int bytes,long elapsedMs,boolean provisional){this.bytes=bytes;this.elapsedMs=elapsedMs;this.provisional=provisional;}
  }
  /** Inspect the received prefix in place; copy only when returning a sample. */
  private static final class SampleBuffer extends ByteArrayOutputStream{
    boolean confirmedHlsPrefix(){return MediaPayloadValidator.hasConfirmedHlsPrefix(buf,count);}
  }
  private static class SampleResponse{
    final byte[] bytes;
    final String finalUrl;
    // True only when the bounded read observed at least one byte beyond its cap.
    // Reaching the cap alone does not prove that the HTTP resource ended there.
    final boolean truncated,earlyDirectTs;
    SampleResponse(byte[] data,String url,boolean wasTruncated){this(data,url,wasTruncated,false);}
    SampleResponse(byte[] data,String url,boolean wasTruncated,boolean earlyDirectTs){
      bytes=data;finalUrl=url;truncated=wasTruncated;this.earlyDirectTs=earlyDirectTs;
    }
  }
  private boolean probeDeadlineReached(long deadline){
    return probeDeadlineReached(deadline,pauseRequested);
  }
  private static boolean probeDeadlineReached(long deadline,java.util.function.BooleanSupplier pauseRequested){
    // A local cancelled probe must not latch a global viewing pause solely
    // because its own executor thread was interrupted.
    return Thread.currentThread().isInterrupted()||pauseRequested.getAsBoolean()
        ||android.os.SystemClock.elapsedRealtime()>=deadline;
  }
  // A pressure event interrupts pacing at the next poll. A blocked socket
  // remains subject to its separate inactivity timeout; do not promise instant
  // cancellation or create another player/network thread.
  // A cancelled or out-of-budget sample is not negative media evidence.
  // Keep cancellation local; never convert it into a global viewing latch.
  private static boolean probeSampleInterrupted(long deadline,
      java.util.function.BooleanSupplier paused,ProbeResult result){
    if(!probeDeadlineReached(deadline,paused))return false;
    if(android.os.SystemClock.elapsedRealtime()>=deadline)result.socketTimedOut=true;
    else result.incompleteSample=true;
    return true;
  }
  private static boolean paceViewingSample(int bytes,long readElapsedMs,long deadline,java.util.function.BooleanSupplier pauseRequested){
    long rate=SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(android.os.SystemClock.elapsedRealtime());
    long delay=SourceProbeTimePolicy.viewingSampleRemainingDelayMillis(bytes,readElapsedMs,rate);
    long end=android.os.SystemClock.elapsedRealtime()+delay;
    for(;;){
      if(probeDeadlineReached(deadline,pauseRequested))return false;
      long remaining=end-android.os.SystemClock.elapsedRealtime();
      if(remaining<=0L)return true;
      android.os.SystemClock.sleep(Math.min(remaining,SourceProbeTimePolicy.VIEWING_SAMPLE_PAUSE_POLL_MS));
    }
  }
  private static byte[] fetchSample(String address,int maxBytes,long deadline,boolean slowPlayback,java.util.function.BooleanSupplier pauseRequested,ProbeResult result)throws Exception{
    SampleResponse response=fetchSampleResponse(address,maxBytes,deadline,slowPlayback,pauseRequested,false,false,result);
    return response==null?null:response.bytes;
  }
  private static final class TransientProbeHttpException extends IOException {
    final int code;
    final String origin;
    TransientProbeHttpException(int code,String responseUrl){
      super("Temporary media HTTP "+code);
      this.code=code;this.origin=InitialOriginCooldownPolicy.origin(responseUrl);
    }
  }
  private static SampleResponse fetchSampleResponse(String address,int maxBytes,long deadline,boolean slowPlayback,
      java.util.function.BooleanSupplier pauseRequested,boolean stopOnConfirmedMedia,boolean stopOnDirectTs,
      ProbeResult result)throws Exception{
    return fetchSampleResponse(address,maxBytes,deadline,slowPlayback,pauseRequested,
        stopOnConfirmedMedia,stopOnDirectTs,result,false);
  }
  private static SampleResponse fetchSampleResponse(String address,int maxBytes,long deadline,boolean slowPlayback,
      java.util.function.BooleanSupplier pauseRequested,boolean stopOnConfirmedMedia,boolean stopOnDirectTs,
      ProbeResult result,boolean manifestCandidate)throws Exception{
    return fetchSampleResponse(address,maxBytes,deadline,slowPlayback,pauseRequested,
        stopOnConfirmedMedia,stopOnDirectTs,result,manifestCandidate,false);
  }
  private static SampleResponse fetchSampleResponse(String address,int maxBytes,long deadline,boolean slowPlayback,
      java.util.function.BooleanSupplier pauseRequested,boolean stopOnConfirmedMedia,boolean stopOnDirectTs,
      ProbeResult result,boolean manifestCandidate,boolean rootCandidate)throws Exception{
    // Expand only a confirmed playlist in a non-viewing request. Retain the
    // original media sample cap for mislabeled URLs and all binary resources.
    final int manifestLimit=131072;
    boolean allowManifestExpansion=manifestCandidate&&!slowPlayback&&maxBytes<manifestLimit;
    HttpURLConnection c=null;
    try{
      if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
      // Avoid another request to an origin that explicitly asked this
      // probe to back off. Independent origins remain eligible.
      if(result.pressureOrigins!=null
          &&result.originDeferred(InitialOriginCooldownPolicy.origin(address))){
        result.transientNetworkFailure=true;return null;
      }
      c=(HttpURLConnection)new URL(address).openConnection();
      // URLConnection options must be configured before the first operation that connects it.
      // Reserve half the remaining budget for connection setup and the rest for response reads.
      long now=android.os.SystemClock.elapsedRealtime();
      long remaining=deadline-now;
      if(remaining<2L){result.socketTimedOut=true;return null;}
      int connectTimeout=(int)Math.min(SourceProbeTimePolicy.sampleConnectTimeoutCap(slowPlayback),Math.max(1L,remaining/2L));
      int readTimeout=SourceProbeTimePolicy.boundedTimeoutMillis(
          deadline,now+connectTimeout,SourceProbeTimePolicy.sampleReadTimeoutCap(slowPlayback));
      if(readTimeout<=0){result.socketTimedOut=true;return null;}
      c.setConnectTimeout(connectTimeout);
      c.setReadTimeout(readTimeout);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36 MyTV/1.24");
      // Read one sentinel byte beyond the retained sample to distinguish an
      // exact-length resource from a longer segment truncated at the sample cap.
      c.setRequestProperty("Range","bytes=0-"+(allowManifestExpansion?manifestLimit:maxBytes));
      result.sampleRequests++;
      int code=c.getResponseCode();
      if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
      if(SourceProbeTimePolicy.transientProbeHttpStatus(code)){
        TransientProbeHttpException pressure=new TransientProbeHttpException(code,c.getURL().toString());
        if(code==429||code==503)result.noteOriginPressure(pressure.origin);
        throw pressure;
      }
      // A rejected byte range proves this sampling method failed, not
      // that the original stream is unplayable. Do not retry without Range
      // in this bounded probe.
      if(code==416){result.unsupportedPreflight=true;return null;}
      if(code<200||code>=400)return null;
      boolean partial=code==HttpURLConnection.HTTP_PARTIAL;
      // An extensionless root can be an HLS playlist. If the server ignored
      // Range and returned a full 200 response, continue that SAME response
      // only after confirming the playlist marker. Do not enlarge its Range
      // request, re-fetch a 206 response or expand dependent binary samples.
      if(rootCandidate&&code==HttpURLConnection.HTTP_OK&&!slowPlayback&&maxBytes<manifestLimit)
        allowManifestExpansion=true;
      long[] prefixRange=partial?HlsProbeValidationPolicy.parsePrefixRange(c.getHeaderField("Content-Range")):null;
      // A nonzero or malformed Content-Range cannot establish the requested
      // prefix evidence, even if the payload happens to resemble media.
      if(partial&&prefixRange==null){result.incompleteSample=true;return null;}
      String type=c.getContentType();
      if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))return null;
      String finalUrl=c.getURL().toString();
      String encoding=c.getHeaderField("Content-Encoding");
      // A transparently decoded body cannot be compared with compressed
      // wire length. Only use an explicit identity/unencoded HTTP length.
      int promised=!partial&&(encoding==null||"identity".equalsIgnoreCase(encoding.trim()))
          ?c.getContentLength():-1;
      try(InputStream in=c.getInputStream();SampleBuffer out=new SampleBuffer()){
        byte[] buffer=new byte[stopOnConfirmedMedia?1024:SourceProbeTimePolicy.VIEWING_SAMPLE_CHUNK_BYTES];int n,total=0;
        while(total<=maxBytes){
          if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
          int length=SourceProbeTimePolicy.sampleReadLength(maxBytes,total,buffer.length);
          // Fragmented socket reads must not overshoot the first screening threshold.
          if(stopOnDirectTs&&total<4096)length=Math.min(length,4096-total);
          if(length==0)break;
          long readStarted=slowPlayback?android.os.SystemClock.elapsedRealtime():0L;
          try{n=in.read(buffer,0,length);}
          catch(IOException brokenBody){
            if(promised>=0&&total<promised)result.incompleteSample=true;
            throw brokenBody;
          }
          if(n>0)result.sampleBytes+=n;
          long readElapsed=slowPlayback?android.os.SystemClock.elapsedRealtime()-readStarted:0L;
          if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
          if(n==-1)break;
          out.write(buffer,0,n);total+=n;
          if(partial&&total>prefixRange[0]){result.incompleteSample=true;return null;}
          if(allowManifestExpansion&&total>=maxBytes){
            allowManifestExpansion=false;
            if(HlsVariantPolicy.hasPlaylistMarker(out.toByteArray()))
              maxBytes=manifestLimit;
          }
          // Inspect at most once after a small prefix is available. If not TS,
          // retain the normal full read (including extensionless HLS manifests).
          if(stopOnDirectTs&&total>=4096){
            stopOnDirectTs=false;
            byte[] prefix=out.toByteArray();
            if(MediaPayloadValidator.hasDirectTsScreeningPrefix(prefix)){
              if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
              return new SampleResponse(prefix,finalUrl,true,true);
            }
          }
          if(stopOnConfirmedMedia&&total>=16&&total<=maxBytes&&out.confirmedHlsPrefix()){
            if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
            // Confirmation only: no byte-rate score or completeness claim.
            // This path is restricted to unencrypted idle second segments.
            return new SampleResponse(out.toByteArray(),finalUrl,true);
          }
          if(slowPlayback&&!paceViewingSample(n,readElapsed,deadline,pauseRequested)){
            probeSampleInterrupted(deadline,pauseRequested,result);
            return null;
          }
        }
        if(probeSampleInterrupted(deadline,pauseRequested,result))return null;
        byte[] received=out.toByteArray();
        if(promised>=0&&received.length<=maxBytes&&received.length<promised){
          result.incompleteSample=true;return null;
        }
        if(partial&&!HlsProbeValidationPolicy.prefixBodyMatches(prefixRange,received.length,maxBytes)){
          result.incompleteSample=true;return null;
        }
        boolean truncated=(partial&&HlsProbeValidationPolicy.incompletePrefixRange(prefixRange,maxBytes))
            ||HlsProbeValidationPolicy.isTruncatedSample(received.length,maxBytes);
        int retained=SourceProbeTimePolicy.sampleRetainedLength(received.length,maxBytes);
        byte[] bounded=retained<received.length?Arrays.copyOf(received,retained):received;
        return new SampleResponse(bounded,finalUrl,truncated);
      }
    }finally{if(c!=null)c.disconnect();}
  }
  private String read(InputStream in,int max)throws Exception{
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;
    while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("频道目录过大");out.write(b,0,n);}return out.toString("UTF-8");
  }
  private String canonicalName(String name){
    return SourceDiscoveryIdentityPolicy.canonicalName(name);
  }
  private String identity(String name,String group,String url,String tvgId,String tvgName){
    return SourceDiscoveryIdentityPolicy.identity(name,group,url,tvgId,tvgName);
  }
  private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
  private static final class InitialFeedAdmission {
    final boolean[] completed,deferred,visited,failed,prepared;
    final long[] remaining;
    boolean acquisitionYield;
    boolean[] availableUnderPressure;
    final boolean[] orderedReadyUnderPressure;
    final String[] acquiredVersions;
    InitialFeedAdmission(int count){
      completed=new boolean[count];deferred=new boolean[count];
      visited=new boolean[count];failed=new boolean[count];prepared=new boolean[count];remaining=new long[count];
      acquiredVersions=new String[count];
      orderedReadyUnderPressure=new boolean[count];
    }
  }
  /** One consistent Room read for both admission and the post-commit delay
   * decision. Masks are scheduling hints only; they never mark a feed done. */
  private InitialFeedAdmission readInitialFeedAdmission(String[] feeds,boolean viewing){
    int count=feeds.length;
    InitialFeedAdmission admission=new InitialFeedAdmission(count);
    ArrayList<String> keys=new ArrayList<>(1+count*7);
    keys.add(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY);
    for(int i=0;i<count;i++){
      keys.add(initialFeedDoneKey(i));
      keys.add(DiscoveryBatchPolicy.initialTransientRecheckKey(i));
      keys.add(DiscoveryBatchPolicy.initialTransientRecheckAtKey(i));
      keys.add(initialFeedFailureCountKey(i));
      keys.add(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(i));
      keys.add(feedCursorKey(i));
      keys.add(DiscoveryBatchPolicy.initialBatchPrefix(i)+"scanned");
    }
    Map<String,String> state=readRoomValues(keys);
    admission.acquisitionYield=Boolean.parseBoolean(state.get(SourceDiscoveryPriorityPolicy.ACQUISITION_YIELD_KEY));
    long wall=System.currentTimeMillis(),elapsed=android.os.SystemClock.elapsedRealtime();
    for(int i=0;i<count;i++){
      admission.completed[i]=Boolean.parseBoolean(state.get(initialFeedDoneKey(i)));
      admission.failed[i]=positiveFeedState(state,initialFeedFailureCountKey(i));
      String acquired=state.get(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(i));
      admission.acquiredVersions[i]=acquired;
      admission.prepared[i]=acquired!=null&&!acquired.isEmpty();
      admission.visited[i]=admission.failed[i]
          ||positiveFeedState(state,feedCursorKey(i))
          ||positiveFeedState(state,DiscoveryBatchPolicy.initialBatchPrefix(i)+"scanned");
      int marker=DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK;
      try{marker=Integer.parseInt(state.get(DiscoveryBatchPolicy.initialTransientRecheckKey(i)));}
      catch(NumberFormatException ignored){}
      admission.deferred[i]=DiscoveryBatchPolicy.deferInitialReplayForIdle(viewing,marker)
          ||(viewing&&elapsed<LIVE_DIRECTORY_RETRY_AFTER.getOrDefault(i,0L));
      if(!admission.completed[i]&&!admission.deferred[i]
          &&marker==DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED){
        long admittedAt=0L;
        try{admittedAt=Long.parseLong(state.get(DiscoveryBatchPolicy.initialTransientRecheckAtKey(i)));}
        catch(NumberFormatException ignored){}
        admission.remaining[i]=DiscoveryBatchPolicy.transientRecheckRemainingMs(admittedAt,wall);
        admission.deferred[i]=admission.remaining[i]>0L;
      }
    }
    if(!viewing){
      try{admission.availableUnderPressure=PlaylistFeedCache.initialCachePressure(getApplicationContext(),feeds,admission.completed,admission.acquiredVersions,admission.prepared,admission.orderedReadyUnderPressure);}
      catch(IOException unavailable){android.util.Log.w("MyTVSourceDiscovery","initial_cache_inventory_unavailable");}
    }
    return admission;
  }
  private static boolean positiveFeedState(Map<String,String> values,String key){
    try{return Long.parseLong(values.get(key))>0L;}
    catch(NumberFormatException invalid){return false;}
  }
  private Map<String,String> readRoomValues(List<String> keys){
    Map<String,String> values=new HashMap<>();
    if(keys.isEmpty())return values;
    for(com.wodedianshi.tv.db.AppStateEntity state:
        com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext())
            .appStateDao().getValues(keys))
      values.put(state.key,state.stateValue);
    return values;
  }
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
