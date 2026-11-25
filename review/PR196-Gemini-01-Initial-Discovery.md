# 《我的电视》2.74.5（PR #196）第一组：首次初始化、抓源、进度及让路调度

固定审查提交（CI 实际 checkout）：adcb720f2caf04ec7cca94bba632589c14a8ed2e
候选 head：527191ee4532bc76b51e499189eeaac5df1daf8d
版本：2.74.5 / 161；构建 #2753 已通过单元测试和 APK 生成，但未进行实机验收。
设备：当贝 D6 Android 9，兼顾 Android 手机。

## 请 Gemini 审查

- 首次安装种子频道播放、空暂存初始化、首批中国目录准备、独立目录版本和每页游标：是否存在 0% 假死或重复工作？
- 核对阶段优先、低速抓源许可/撤销、固定间隔续跑、异常退避、WorkManager APPEND 链增长与取消：是否能简化，既保证中国优先又不被少数大型目录卡死？
- 已验证频道阶段性发布在稳定观看中被推迟；如何尽快呈现中国频道并保护当前节目、焦点、Room 原子快照及用户修改？
- 对比首次初始化整体方案：是否有明显更简单的架构？不要为解决等待而新增无必要的数据库、线程池、依赖或伪造进度。
- 输出源码确认/潜在风险/需实机验证三类，提供准确文件行号、改法、可能副作用、回归测试（断网、强杀、断电、换台、VLC/Media3、第二次启动）。区分媒体预检和真正实播。只用已提供的源码证据，不推测未提供的文件。

范围：完整 Worker 与调度/进度/游标策略及对应测试；MainActivity 仅附两个关键调用区间。第二组会提供 PlaylistFeedCache、PlaylistFeedIndex、PlaylistParser；第三组会提供完整 Room 数据库链。此包不含私有运行日志、账号或密钥；URL 字面量如存在均替换为 [URL_REDACTED]，行号保持不变。不是整个项目源码。

## 文件清单（原始提交及行号）
- app/src/main/java/com/wodedianshi/tv/SourceUpdateWorker.java L1–1976 | blob c1b1c73ef5780dd23278d399aabbff3ea080e649
- app/src/main/java/com/wodedianshi/tv/SourceUpdateSchedulePolicy.java L1–109 | blob 2a1449f3b4b02c1deca72ff6e1b921a748885833
- app/src/main/java/com/wodedianshi/tv/SourceUpdateActivityPolicy.java L1–41 | blob 12fb0246bcb8b81d683a4a4696f64ea198e24e94
- app/src/main/java/com/wodedianshi/tv/FullDiscoveryProgressPolicy.java L1–154 | blob 3b9399a695f7fcae35d30b6e1d0d8ffdf6d341bb
- app/src/main/java/com/wodedianshi/tv/DiscoveryBatchPolicy.java L1–43 | blob 931ab473925cce7f82df1a31744ad30894562d24
- app/src/main/java/com/wodedianshi/tv/InitialFeedRotationPolicy.java L1–35 | blob dd3c25b3e2c071a79c034551ab827a145247f5fd
- app/src/main/java/com/wodedianshi/tv/InitialFeedFailurePolicy.java L1–45 | blob e49b913f181b281d802495d93f7b3e99ffea3fe7
- app/src/main/java/com/wodedianshi/tv/SourceDiscoveryPriorityPolicy.java L1–71 | blob 54afc031367f0b9e9c383ae1e10ff497d2c02a25
- app/src/main/java/com/wodedianshi/tv/SourceProbeTimePolicy.java L1–90 | blob 13fda299dd4195baf4fd8739f938a7fcb27db775
- app/src/test/java/com/wodedianshi/tv/SourceUpdateSchedulePolicyTest.java L1–144 | blob ad5fcaf0f68d42f0e410d3995d76d00d20503e2c
- app/src/test/java/com/wodedianshi/tv/SourceUpdateActivityPolicyTest.java L1–25 | blob 47b53f4002e85a6a8767947ea1547894959a0ba1
- app/src/test/java/com/wodedianshi/tv/FullDiscoveryProgressPolicyTest.java L1–128 | blob 3e4a0bd5addd15418cd53a83b951d69f757a23c7
- app/src/test/java/com/wodedianshi/tv/DiscoveryBatchPolicyTest.java L1–90 | blob 39bc0e1e6612a1f8d01e10593a61bfdaa0d117b2
- app/src/test/java/com/wodedianshi/tv/InitialFeedFailurePolicyTest.java L1–42 | blob 5c1c988f8a07f8b49bd40254a50fa476b301c585
- app/src/test/java/com/wodedianshi/tv/InitialFeedRotationPolicyTest.java L1–40 | blob 00e905f7c2064dd60c9d12bed3e497b2dd05f7a0
- app/src/test/java/com/wodedianshi/tv/SourceDiscoveryPriorityPolicyTest.java L1–62 | blob fee3b8a66bc0a6068295a8aa39cdc2a95c457535
- app/src/test/java/com/wodedianshi/tv/SourceProbeTimePolicyTest.java L1–157 | blob 7cc0f5902f95fcc9348999f2f5a3b2d7299e85e1
- app/src/main/java/com/wodedianshi/tv/MainActivity.java L1395–1626 | blob b842e1bf8a6e394c747e3397236aae189a30fd55
- app/src/main/java/com/wodedianshi/tv/MainActivity.java L5799–5869 | blob b842e1bf8a6e394c747e3397236aae189a30fd55

## app/src/main/java/com/wodedianshi/tv/SourceUpdateWorker.java — 原始行 1–1976

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import android.content.Context;
    4 | import android.content.SharedPreferences;
    5 | import androidx.annotation.NonNull;
    6 | import androidx.work.Worker;
    7 | import androidx.work.WorkerParameters;
    8 | import java.io.*;
    9 | import java.net.*;
   10 | import java.text.SimpleDateFormat;
   11 | import java.util.*;
   12 | import org.json.*;
   13 | 
   14 | public class SourceUpdateWorker extends Worker {
   15 |   // WorkManager unique names do not exclude one another. Keep every source
   16 |   // task in this process under one nonblocking ownership gate.
   17 |   private static final java.util.concurrent.atomic.AtomicBoolean SOURCE_WORK_ACTIVE=
   18 |       new java.util.concurrent.atomic.AtomicBoolean();
   19 |   // Transient, same-process status only; durable progress stays in Room.
   20 |   private static volatile String initialLiveStage="";
   21 |   public static String currentInitialLiveStage(){return initialLiveStage;}
   22 |   private void noteInitialStage(String stage){
   23 |     if(getInputData().getBoolean("initial_discovery_chain",false))initialLiveStage=stage;
   24 |   }
   25 |   // Single source-work owner accesses this bounded process-local cache.
   26 |   // Do not reuse old persisted quality scores as evidence of a new preflight.
   27 |   private static final LinkedHashMap<String,RecentProbe> INITIAL_PROBES=
   28 |       new LinkedHashMap<>(64,0.75f,true);
   29 |   private static final int INITIAL_PROBE_CACHE_CAP=1024;
   30 |   private static final class RecentProbe {
   31 |     final ProbeResult result; final long at;
   32 |     RecentProbe(ProbeResult result,long at){this.result=result;this.at=at;}
   33 |   }
   34 |   private static final HashMap<Integer,Long> LIVE_DIRECTORY_RETRY_AFTER=new HashMap<>();
   35 |   // Reuse only committed staging owned by the sole source worker. A borrowed
   36 |   // mutable snapshot is removed from the cache BEFORE any mutation.
   37 |   private static JSONArray committedStaging;
   38 |   private static String committedStagingRevision="";
   39 |   private static long committedStagingAt;
   40 |   private boolean slowPlayback;
   41 |   private java.util.function.BooleanSupplier pauseRequested = () -> false;
   42 |   private static final int MAX_PROBES_PER_RUN = 24;
   43 |   private static final long PROBE_STAGE_DEADLINE_MS = 90_000L;
   44 |   private static final int MAX_SOURCES_PER_CHANNEL = 8;
   45 |   // The full rebuild reads one feed page per worker run and spends its 24-probe
   46 |   // budget on that page. This prevents large China playlists from being starved
   47 |   // by small pages interleaved from every worldwide feed.
   48 |   // Per-worker memory safety bound only; it is not a lifetime catalogue cap.
   49 |   // Reaching it must leave the feed cursor untouched rather than skip entries.
   50 |   private static final int MAX_DISCOVERY_CANDIDATES_PER_RUN = 24000;
   51 |   private static final int MAX_DISCOVERY_PER_FEED = 4;
   52 |   private static final int MAX_INITIAL_DISCOVERY_PER_FEED = MAX_PROBES_PER_RUN;
   53 |   public SourceUpdateWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
   54 |   @NonNull @Override public Result doWork(){
   55 |     if(!SOURCE_WORK_ACTIVE.compareAndSet(false,true)){
   56 |       return SourceUpdateSchedulePolicy.shouldRetryWhenBusy(
   57 |           getInputData().getBoolean("manual",false),
   58 |           getInputData().getBoolean("initial_discovery_chain",false))
   59 |           ?Result.retry():Result.success();
   60 |     }
   61 |     try{
   62 |       long now=android.os.SystemClock.elapsedRealtime();
   63 |       if(!getInputData().getBoolean("initial_discovery_chain",false)
   64 |           ||now<committedStagingAt||now-committedStagingAt>60000L)discardStagingReuse();
   65 |       noteInitialStage("准备本轮目录整理");
   66 |       return runOwnedWork();
   67 |     }finally{
   68 |       if(isStopped()||Thread.currentThread().isInterrupted())discardStagingReuse();
   69 |       initialLiveStage="";SOURCE_WORK_ACTIVE.set(false);
   70 |     }
   71 |   }
   72 |   private Result runOwnedWork(){
   73 |     SharedPreferences prefs=getApplicationContext().getSharedPreferences("mytv",0);
   74 |     final boolean manual=getInputData().getBoolean("manual",false);
   75 |     final boolean initialDiscoveryChain=getInputData().getBoolean("initial_discovery_chain",false);
   76 |     final boolean manualRefresh=manual&&!initialDiscoveryChain;
   77 |     final String manualId=getInputData().getString("manual_refresh_id")==null
   78 |         ?getId().toString():getInputData().getString("manual_refresh_id");
   79 |     if(!manual&&!prefs.getBoolean("auto_source_update",true))return Result.success();
   80 |     // No full catalogue yet: the one-time chain owns discovery; a periodic
   81 |     // worker must neither compete with it nor publish a partial replacement.
   82 |     if(!initialDiscoveryChain){
   83 |       long completedAt=0L;
   84 |       try{completedAt=Long.parseLong(roomString("source_full_rebuild_completed_at","0"));}
   85 |       catch(NumberFormatException ignored){}
   86 |       if(!SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true,completedAt))
   87 |         return manual?Result.retry():Result.success();
   88 |       long lastRoutineAt=0L;
   89 |       try{lastRoutineAt=Long.parseLong(roomString("source_last_routine_maintenance_at","0"));}
   90 |       catch(NumberFormatException ignored){}
   91 |       if(!manual&&!SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(completedAt,lastRoutineAt,System.currentTimeMillis()))
   92 |         return Result.success();
   93 |     }
   94 |     final long playbackStart=SourceUpdateActivityPolicy.playbackStartGeneration();
   95 |     final long activityStart=SourceUpdateActivityPolicy.activityStartGeneration();
   96 |     final long stableGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
   97 |     slowPlayback=initialDiscoveryChain&&SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime());
   98 |     final boolean foregroundAtStart=isActivityActive(prefs);
   99 |     // Automatic discovery parses the saved catalogue and builds sizeable indexes.
  100 |     // Defer that work while the TV app is active so it cannot compete with playback,
  101 |     // channel rendering, or remote-control input. Explicit manual updates remain available.
  102 |     // First-run discovery must proceed while browsing, even with seed channels.
  103 |     // Actual playback still pauses discovery; routine automatic updates defer.
  104 |     boolean hasPublishedChannels=true;
  105 |     if(initialDiscoveryChain){
  106 |       hasPublishedChannels=com.wodedianshi.tv.db.RoomDatabaseProvider
  107 |           .get(getApplicationContext()).channelDao().countChannels()>0;
  108 |     }
  109 |     final boolean publishedAtStart=hasPublishedChannels;
  110 |     final boolean deferForeground=SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
  111 |         true,manual,initialDiscoveryChain,publishedAtStart);
  112 |     pauseRequested=SourceProbeTimePolicy.latchPause(()->isStopped()
  113 |         ||SourceUpdateActivityPolicy.playbackStartGeneration()!=playbackStart
  114 |         ||(slowPlayback&&SourceUpdateActivityPolicy.stableRevocationGeneration()!=stableGeneration)
  115 |         ||(deferForeground&&SourceUpdateActivityPolicy.activityStartGeneration()!=activityStart)
  116 |         ||shouldYieldToViewing(prefs,manual,initialDiscoveryChain,publishedAtStart));
  117 |     if(pauseRequested.getAsBoolean()){
  118 |       saveStatus(prefs,SourceUpdateSchedulePolicy.shouldRetryWhenBusy(manual,initialDiscoveryChain)
  119 |           ?"节目或频道界面正在使用，首次/手动更新已推迟"
  120 |           :"正在观看或浏览频道，维护仍待执行；下次空闲检查再继续",0);
  121 |       return deferredViewingResult(manual,initialDiscoveryChain);
  122 |     }
  123 |     int added=0,feedsOk=0,feedsWithEntries=0,discoveryProbesValid=0,chinaPreflightsValid=0,feedsFailed=0,checked=0;
  124 |     StringBuilder errors=new StringBuilder();
  125 |     // Centralized, testable feed order: China local-first, then global.
  126 |     // A feed-list fingerprint change intentionally restarts first-build cursors.
  127 |     String[] feeds=SourceDiscoveryFeedCatalog.feeds();
  128 |     try{
  129 |       if(manualRefresh){
  130 |         if(manualId.equals(roomString("source_manual_completed_id","")))return Result.success();
  131 |         String config=discoveryFeedsFingerprint(feeds);
  132 |         if(!manualId.equals(roomString("source_manual_active_id",""))
  133 |             ||!config.equals(roomString("source_manual_feed_config",""))){
  134 |           com.wodedianshi.tv.db.AppStateEditor reset=
  135 |               new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
  136 |               .putString("source_manual_active_id",manualId)
  137 |               .putString("source_manual_feed_config",config)
  138 |               .putInt("source_manual_next_feed",0)
  139 |               .putInt("source_manual_failed_feeds",0)
  140 |               .putInt("source_manual_feed_attempts",0)
  141 |               .remove("source_manual_feed_batch_fingerprint")
  142 |               .remove("source_manual_feed_batch_scanned");
  143 |           for(int i=0;i<feeds.length;i++)
  144 |             reset.remove(manualFeedCursorKey(i)).remove(manualFeedCursorKey(i)+"_version");
  145 |           SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  146 |           reset.apply();
  147 |         }
  148 |       }
  149 |       // First discovery works solely on isolated staging. Do not deserialize
  150 |       // the published catalogue every slice; read it afresh at publication.
  151 |       // Routine maintenance still needs its original optimistic snapshot.
  152 |       com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot roomSnapshot =
  153 |           initialDiscoveryChain?null:
  154 |               com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),null,pauseRequested);
  155 |       if(initialDiscoveryChain
  156 |           ?!com.wodedianshi.tv.db.RoomCatalogueReader.hasValidatedCatalogue(getApplicationContext())
  157 |           :roomSnapshot==null) {
  158 |         saveStatus(prefs,"Room频道目录尚未完成初始化，后台更新稍后重试",0);
  159 |         return Result.retry();
  160 |       }
  161 |       JSONArray saved;
  162 |       if(initialDiscoveryChain){
  163 |         String buildActive=com.wodedianshi.tv.db.AppStateStore.get(
  164 |             getApplicationContext(),"source_full_rebuild_active","false");
  165 |         long completedRebuildAt=0L;
  166 |         try{
  167 |           completedRebuildAt=Long.parseLong(com.wodedianshi.tv.db.AppStateStore.get(
  168 |               getApplicationContext(),"source_full_rebuild_completed_at","0"));
  169 |         }catch(NumberFormatException ignored){}
  170 |         // The Room publish transaction records completion and clears the active
  171 |         // flag atomically. If the process died after commit but before WorkManager
  172 |         // recorded success, its retry must not start a second full rebuild.
  173 |         if(!"true".equals(buildActive)&&completedRebuildAt>0L){
  174 |           saveStatus(prefs,"全量频道目录已完成发布，无需重复重建",0);
  175 |           return Result.success();
  176 |         }
  177 |         String configuredFeedFingerprint=discoveryFeedsFingerprint(feeds);
  178 |         String savedFeedFingerprint=roomString("source_full_rebuild_feed_fingerprint","");
  179 |         boolean rebuildProgressMustReset=!"true".equals(buildActive)
  180 |             ||!configuredFeedFingerprint.equals(savedFeedFingerprint);
  181 |         if(rebuildProgressMustReset){
  182 |           if(slowPlayback&&!FullDiscoveryProgressPolicy.canResetInitialStaging(true,
  183 |               com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext())
  184 |                   .channelDao().countCatalogueBuildRows()))
  185 |             return deferInitialDirectory("已有暂存目录，等待空闲重建，保留观看");
  186 |           // Empty staging initialization is only a small state transaction.
  187 |           // It must not block first discovery forever on a playing seed channel.
  188 |           INITIAL_PROBES.clear();
  189 |           LIVE_DIRECTORY_RETRY_AFTER.clear();
  190 |           discardStagingReuse();
  191 |           // Start from an empty isolated Room staging set. The live catalogue is
  192 |           // intentionally not read as input and remains untouched until publish.
  193 |           // Feed progress is tied to the exact ordered URL list: an app update that
  194 |           // adds, removes, or reorders feeds must not inherit stale per-index cursors.
  195 |           com.wodedianshi.tv.db.AppStateEditor resetBuildState=
  196 |               new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext());
  197 |           for(int feed=0;feed<feeds.length;feed++){
  198 |             resetBuildState.remove(initialFeedDoneKey(feed)).remove(feedCursorKey(feed))
  199 |                 .remove(feedCursorKey(feed)+"_version")
  200 |                 .remove(initialFeedProgressKey(feed))
  201 |                 .remove(initialAcquiredVersionKey(feed))
  202 |                 .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"fingerprint").remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"scanned")
  203 |                 .remove(initialFeedUnavailableKey(feed))
  204 |                 .remove(initialFeedFailureCountKey(feed))
  205 |                 .remove(initialFeedFailureReasonKey(feed))
  206 |                 .remove(initialFeedDisplayLabelKey(feed));
  207 |           }
  208 |           resetBuildState.remove("source_feed_batch_fingerprint")
  209 |               .remove("source_feed_batch_scanned")
  210 |               .remove("source_initial_feed_batch_fingerprint")
  211 |               .remove("source_initial_feed_batch_scanned")
  212 |               .remove("source_scan_after_key").remove("source_scan_cursor")
  213 |               .remove("source_saved_scan_after_key").remove("source_saved_scan_cursor")
  214 |               .remove("source_initial_has_validated_source")
  215 |               .remove(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY)
  216 |               .remove("source_initial_discovery_fingerprint")
  217 |               .remove("source_initial_discovery_scanned")
  218 |               .putInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0)
  219 |               .putInt("source_initial_index_attempted",0)
  220 |               .putBoolean("source_initial_index_bypassed",slowPlayback)
  221 |               .putInt("source_initial_index_ready",0)
  222 |               .putLong("source_initial_index_entries",0L)
  223 |               .putInt("source_initial_progress_percent",0)
  224 |               .putInt("source_initial_progress_done_feeds",0)
  225 |               .putInt("source_initial_progress_total_feeds",feeds.length)
  226 |               .putInt("source_initial_progress_china_done_feeds",0)
  227 |               .putInt("source_initial_progress_china_total_feeds",
  228 |                   SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT)
  229 |               .putInt("source_initial_progress_staged_channels",0)
  230 |               .putInt("source_initial_progress_timeout_urls",0)
  231 |               .putInt("source_initial_progress_checked_urls",0)
  232 |               .putInt("source_initial_progress_validated_urls",0)
  233 |               .putInt("source_initial_progress_china_validated_urls",0)
  234 |               .putInt("source_initial_progress_unavailable_feeds",0)
  235 |               .putInt("source_initial_progress_feed_index",0)
  236 |               .putInt("source_initial_progress_feed_cursor",0)
  237 |               .putInt("source_initial_progress_feed_entries",0)
  238 |               .putString("source_initial_progress_phase","正在准备首次完整抓源")
  239 |               .putInt("source_initial_next_feed_index",0)
  240 |               .putString("source_full_rebuild_feed_fingerprint",configuredFeedFingerprint)
  241 |               .putBoolean("source_full_rebuild_active",true);
  242 |           com.wodedianshi.tv.db.RoomCatalogueBuildStore.clear(
  243 |               getApplicationContext(),resetBuildState,pauseRequested);
  244 |         }
  245 |         // Acquisition phase: build disk indexes independently of media probes.
  246 |         // Keep the existing cache/index as the candidate store; Room records
  247 |         // only the acquisition cursor and counts, not duplicate unverified URLs.
  248 |         int nextIndex=Math.max(0,roomInt("source_initial_index_attempted",0));
  249 |         int mainlandCount=Math.min(feeds.length,SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
  250 |         int mainlandLimit=FullDiscoveryProgressPolicy.bootstrapIndexLimit(mainlandCount);
  251 |         long indexedEntriesSoFar=0L;
  252 |         try{indexedEntriesSoFar=Math.max(0L,Long.parseLong(roomString("source_initial_index_entries","0")));}
  253 |         catch(NumberFormatException ignored){}
  254 |         boolean bootstrapBypassed=roomBoolean("source_initial_index_bypassed",false);
  255 |         if(!bootstrapBypassed&&FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(slowPlayback,nextIndex,mainlandCount,
  256 |             roomInt("source_initial_progress_checked_urls",0),indexedEntriesSoFar,false)){
  257 |           int count=-1;
  258 |           String preparedVersion="";
  259 |           noteInitialStage("首批中国目录准备 "+(nextIndex+1)+"/"+mainlandLimit+"（不检测线路）");
  260 |           try{
  261 |             File cached=PlaylistFeedCache.obtain(getApplicationContext(),feeds[nextIndex],true,
  262 |                 roomInt(feedCursorKey(nextIndex),0)>0,pauseRequested);
  263 |             // readPage builds the complete random-access index if necessary.
  264 |             // Decode one record to check the sidecar without allocating all entries.
  265 |             PlaylistFeedIndex.readPage(cached,0,1,source->{},pauseRequested);
  266 |             count=PlaylistFeedIndex.indexedEntryCount(cached);
  267 |             if(count>=0)preparedVersion=PlaylistFeedCache.contentVersion(cached);
  268 |           }catch(Exception failure){
  269 |             if(failure instanceof SourceProbeTimePolicy.PausedException||pauseRequested.getAsBoolean())
  270 |               return deferredViewingResult(manual,initialDiscoveryChain);
  271 |             android.util.Log.w("MyTVSourceDiscovery","initial_index_deferred feed="+nextIndex);
  272 |           }
  273 |           SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  274 |           int ready=Math.max(0,roomInt("source_initial_index_ready",0))+(count>=0?1:0);
  275 |           long entries=indexedEntriesSoFar;
  276 |           com.wodedianshi.tv.db.AppStateEditor acquisition=
  277 |               new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
  278 |               .putInt("source_initial_index_attempted",nextIndex+1)
  279 |               .putInt("source_initial_index_ready",ready)
  280 |               .putLong("source_initial_index_entries",Math.min(Long.MAX_VALUE-Math.max(0,count),entries)+Math.max(0,count))
  281 |               .putString("source_initial_progress_phase",count>0||nextIndex+1>=mainlandLimit
  282 |                   ?"首批目录准备已结束，开始线路初筛"
  283 |                   :"首批目录准备："+(nextIndex+1)+"/"+mainlandLimit);
  284 |           // Start with the nonempty indexed feed, rather than immediately
  285 |           // retrying an earlier failed download. Other feeds remain unfinished.
  286 |           if(count>0)acquisition.putInt("source_initial_next_feed_index",nextIndex);
  287 |           if(!preparedVersion.isEmpty())acquisition.putString(initialAcquiredVersionKey(nextIndex),preparedVersion);
  288 |           acquisition.apply();
  289 |           // Persist acquisition before queueing the next slice. An unavailable
  290 |           // directory is retried by the ordinary bounded probe phase.
  291 |           enqueueInitialContinuation();
  292 |           return Result.success();
  293 |         }
  294 |         if(slowPlayback&&!bootstrapBypassed&&FullDiscoveryProgressPolicy.isMainlandIndexPhase(nextIndex,mainlandCount,
  295 |             roomInt("source_initial_progress_checked_urls",0),indexedEntriesSoFar,false)){
  296 |           // Mark only the optional bootstrap as bypassed, not any feed done.
  297 |           // The regular bounded feed path still reads and checks every feed.
  298 |           SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  299 |           new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
  300 |               .putBoolean("source_initial_index_bypassed",true)
  301 |               .putString("source_initial_progress_phase","稳定直播：开始低速准备中国目录")
  302 |               .apply();
  303 |         }
  304 |         // Last viewing slice may already have committed every feed. Avoid
  305 |         // reloading/ranking staging on each retry while only publication waits.
  306 |         if(slowPlayback&&areAllDiscoveryFeedsComplete(prefs,Collections.emptyList(),feeds.length))
  307 |           return deferInitialDirectory("线路初筛已完成，等待空闲发布完整快照");
  308 |         saved=takeCommittedStaging();
  309 |         com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),
  310 |             "source_initial_progress_phase","正在继续首次完整抓源");
  311 |       }else{
  312 |         saved=roomSnapshotToArray(roomSnapshot);
  313 |       }
  314 |       final int previousStagedSize=saved.length();
  315 |       IdentityHashMap<JSONObject,Integer> stagedPositions=new IdentityHashMap<>();
  316 |       HashSet<Integer> touchedStagedRows=new HashSet<>();
  317 |       LinkedHashMap<String,JSONObject> byIdentity=new LinkedHashMap<>();
  318 |       JSONObject measuredScores=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_probe_scores_v1","{}"));
  319 |       // A single failed probe merely reduces its score. It cannot delete an
  320 |       // existing saved backup; three independent failures are needed.
  321 |       JSONObject probeFailureStreaks=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(
  322 |           getApplicationContext(),"source_probe_failure_streaks_v1","{}"));
  323 |       JSONObject observedQoe=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_qoe_v1","{}"));
  324 |       for(int i=0;i<saved.length();i++){
  325 |         SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  326 |         JSONObject c=saved.getJSONObject(i);
  327 |         if(initialDiscoveryChain)stagedPositions.put(c,i);
  328 |         byIdentity.put(identity(c.optString("name"),c.optString("group"),
  329 |             c.optString("url"),c.optString("tvgId"),c.optString("tvgName")),c);
  330 |       }
  331 |       ArrayList<Entry> candidates=new ArrayList<>();
  332 |       // Include the user's already-saved backup URLs in the rotating health-check
  333 |       // pool. Previously only URLs rediscovered in public playlists were probed,
  334 |       // so a saved backup could silently die without ever losing its old score.
  335 |       ArrayList<Entry> savedPool=new ArrayList<>();
  336 |       HashSet<String> savedPoolUrls=new HashSet<>();
  337 |       // A foreground full rebuild probes the staged discovery candidates; it does not
  338 |       // import old catalogue sources. Routine background maintenance also checks saved URLs.
  339 |       // Avoid allocating up to 20,000 additional saved-source Entry objects while the
  340 |       // TV UI is open; the full rotating health-check pool is retained for background runs.
  341 |       if(!foregroundAtStart&&!initialDiscoveryChain){
  342 |         for(int i=0;i<saved.length()&&savedPool.size()<20000;i++){
  343 |           SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  344 |           JSONObject channel=saved.optJSONObject(i);if(channel==null)continue;
  345 |           String channelName=channel.optString("name",""),channelGroup=channel.optString("group","");
  346 |           LinkedHashSet<String> channelUrls=new LinkedHashSet<>();
  347 |           JSONArray sources=channel.optJSONArray("sources");
  348 |           if(sources!=null)for(int j=0;j<sources.length();j++){
  349 |             String u=sources.optString(j,"");
  350 |             if(u.startsWith("http://")||u.startsWith("https://"))channelUrls.add(u);
  351 |           }
  352 |           String primary=channel.optString("url","");
  353 |           if(primary.startsWith("http://")||primary.startsWith("https://"))channelUrls.add(primary);
  354 |           for(String u:channelUrls){
  355 |             SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  356 |             if(savedPool.size()>=20000)break;
  357 |             // A shared URL only needs one network probe, even if several channel
  358 |             // entries reference it. Keep the first channel identity for diagnostics.
  359 |             if(savedPoolUrls.add(SourceSelectionPolicy.endpointKey(u)))savedPool.add(new Entry(
  360 |                 channelName,u,channelGroup,true,"",channel.optString("tvgId",""),channel.optString("tvgName","")));
  361 |           }
  362 |         }
  363 |       }
  364 |       com.wodedianshi.tv.db.AppStateEditor update=
  365 |           new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext());
  366 |       ArrayList<FeedBatch> feedBatches=new ArrayList<>();
  367 |       int initialFeedStartIndex=InitialFeedRotationPolicy.normalizeStart(
  368 |           roomInt("source_initial_next_feed_index",0),feeds.length);
  369 |       int initialNextFeedIndex=initialFeedStartIndex;
  370 |       boolean[] inspectedFeeds=new boolean[feeds.length];
  371 |       boolean[] attemptedFeedsThisRun=new boolean[feeds.length];
  372 |       if(initialDiscoveryChain){
  373 |         for(int i=0;i<feeds.length;i++)
  374 |           inspectedFeeds[i]=roomBoolean(initialFeedDoneKey(i),false);
  375 |       }
  376 |       int routineStart=InitialFeedRotationPolicy.normalizeStart(
  377 |           roomInt(manualRefresh?"source_manual_next_feed":"source_routine_next_feed_index",0),feeds.length);
  378 |       int routineEnd=manualRefresh?Math.min(feeds.length,routineStart+1):
  379 |           SourceUpdateSchedulePolicy.routineSliceEnd(routineStart,feeds.length);
  380 |       int feedLimit=initialDiscoveryChain?feeds.length:routineEnd-routineStart;
  381 |       for(int feedOffset=0;feedOffset<feedLimit;feedOffset++){
  382 |         int feedIndex=initialDiscoveryChain
  383 |             ?SourceDiscoveryPriorityPolicy.nextFeed(initialNextFeedIndex,
  384 |                 inspectedFeeds,attemptedFeedsThisRun,
  385 |                 SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
  386 |                 SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT)
  387 |             :routineStart+feedOffset;
  388 |         if(feedIndex<0)break;
  389 |         // Full rebuild: inspect China mainland feeds first (CCTV, provincial,
  390 |         // county/local); then HK/Macau/Taiwan; only then international feeds.
  391 |         // Rotate fairly within a stage and resume each feed's saved page cursor.
  392 |         // Keep the 24-probe budget and yield to playback in every stage.
  393 |         String feed=feeds[feedIndex];
  394 |         String cursorKey=initialDiscoveryChain?feedCursorKey(feedIndex):
  395 |             manualRefresh?manualFeedCursorKey(feedIndex):routineFeedCursorKey(feedIndex);
  396 |         int feedCursor=roomInt(cursorKey,0);
  397 |         String feedVersion="";
  398 |         int[] parsedCount={0};
  399 |         int indexedEntries=-1; // Count from the validated cached playlist index.
  400 |         boolean feedReadSucceeded=false;
  401 |         boolean feedTerminalUnavailable=false;
  402 |         if(isStopped())return Result.retry();
  403 |         if(pauseRequested.getAsBoolean()){
  404 |           saveStatus(prefs,"频道播放或浏览开始，本轮抓源已让路",0);
  405 |           return deferredViewingResult(manual,initialDiscoveryChain);
  406 |         }
  407 |         ArrayList<Entry> feedCandidates=new ArrayList<>();
  408 |         boolean feedCandidatesAdded=false;
  409 |         try{
  410 |           // WorkManager may resume the same large feed over many runs. Reuse a
  411 |           // complete local snapshot so each continuation does not redownload
  412 |           // the entire playlist prefix. The cache revalidates after 24 hours so a multi-run rebuild keeps a stable feed snapshot.
  413 |           // A playback session can begin after the batch-level check above.
  414 |           // Do not start a potentially large network download in that case.
  415 |           if(isStopped())return Result.retry();
  416 |           if(pauseRequested.getAsBoolean()){
  417 |             saveStatus(prefs,"节目播放或频道操作优先，已推迟频道源下载",0);
  418 |             return deferredViewingResult(manual,initialDiscoveryChain);
  419 |           }
  420 |           if(slowPlayback&&android.os.SystemClock.elapsedRealtime()<LIVE_DIRECTORY_RETRY_AFTER.getOrDefault(feedIndex,0L))continue;
  421 |           noteInitialStage("读取目录 "+(feedIndex+1)+"/"+feeds.length+"（下载或缓存）");
  422 |           File cachedFeed=initialDiscoveryChain?PlaylistFeedCache.findIndexedInitial(getApplicationContext(),feed):
  423 |               PlaylistFeedCache.obtain(getApplicationContext(),feed,false,feedCursor>0,pauseRequested);
  424 |           if(initialDiscoveryChain&&!slowPlayback&&(cachedFeed==null||!DiscoveryBatchPolicy.isPreparedInitialFeed(
  425 |               roomString(initialAcquiredVersionKey(feedIndex),""),PlaylistFeedCache.contentVersion(cachedFeed)))){
  426 |             // The acquisition-only slice is independent of the probe cursor.
  427 |             // Never reuse a marker if eviction or a changed body invalidated it.
  428 |             noteInitialStage("独立准备目录 "+(feedIndex+1)+"/"+feeds.length);
  429 |             cachedFeed=PlaylistFeedCache.obtainInitialDuringIdle(getApplicationContext(),feed,feedCursor>0,pauseRequested);
  430 |             if(cachedFeed==null){
  431 |               SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  432 |               new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
  433 |                   .putInt("source_initial_next_feed_index",feedIndex)
  434 |                   .putString("source_initial_progress_phase","空闲时继续已保存目录分段")
  435 |                   .apply();
  436 |               return continueAfterAcquisition();
  437 |             }
  438 |             PlaylistFeedIndex.readPage(cachedFeed,0,1,source->{},pauseRequested);
  439 |             SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  440 |             commitInitialAcquisition(feedIndex,cachedFeed);
  441 |             return continueAfterAcquisition();
  442 |           }
  443 |           if(cachedFeed==null&&slowPlayback){
  444 |             // Acquisition is its own bounded slice: no stream probe in the same
  445 |             // worker. Never advance the verified cursor after a download alone.
  446 |             noteInitialStage("稳定直播低速下载目录 "+(feedIndex+1)+"/"+feeds.length);
  447 |             try{
  448 |               cachedFeed=PlaylistFeedCache.obtainInitialViewingChunk(
  449 |                   getApplicationContext(),feed,feedCursor>0,pauseRequested);
  450 |               if(cachedFeed==null){
  451 |                 // An exact validated byte range was committed; neither the
  452 |                 // directory nor the line-probe cursor is complete yet.
  453 |                 SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  454 |                 new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
  455 |                     .putInt("source_initial_next_feed_index",feedIndex)
  456 |                     .putString("source_initial_progress_phase","已保存大型目录分段，稳定播放时继续下载")
  457 |                     .apply();
  458 |                 return continueAfterAcquisition();
  459 |               }
  460 |               if(!SourceProbeTimePolicy.fitsLiveDirectory(cachedFeed.length()))
  461 |                 throw new IOException("Complete large directory awaits idle indexing");
  462 |               PlaylistFeedIndex.readPage(cachedFeed,0,1,source->{},pauseRequested);
  463 |               SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  464 |               LIVE_DIRECTORY_RETRY_AFTER.remove(feedIndex);
  465 |               commitInitialAcquisition(feedIndex,cachedFeed);
  466 |               return continueAfterAcquisition();
  467 |             }catch(Exception failure){
  468 |               if(pauseRequested.getAsBoolean()||failure instanceof SourceProbeTimePolicy.PausedException)
  469 |                 return deferredViewingResult(manual,initialDiscoveryChain);
  470 |               LIVE_DIRECTORY_RETRY_AFTER.put(feedIndex,android.os.SystemClock.elapsedRealtime()+300000L);
  471 |               // One network acquisition per viewing slice, including failures.
  472 |               // Next slice starts at another feed; the deferred page stays intact.
  473 |               SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  474 |               new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
  475 |                   .putInt("source_initial_next_feed_index",InitialFeedRotationPolicy.nextAfterAttempt(feedIndex,feeds.length,false,false))
  476 |                   .putString("source_initial_progress_phase","较大或较慢目录待空闲补查，继续其他目录")
  477 |                   .apply();
  478 |               enqueueInitialContinuation();
  479 |               return Result.success();
  480 |             }
  481 |           }
  482 |           feedVersion=PlaylistFeedCache.contentVersion(cachedFeed);
  483 |           feedCursor=DiscoveryBatchPolicy.cursorForVersion(feedCursor,
  484 |               roomString(cursorKey+"_version",""),feedVersion);
  485 |           int feedPageSize=initialDiscoveryChain
  486 |               ?MAX_INITIAL_DISCOVERY_PER_FEED:manualRefresh?MAX_PROBES_PER_RUN/2:MAX_DISCOVERY_PER_FEED;
  487 |           noteInitialStage("解析目录 "+(feedIndex+1)+"/"+feeds.length+" · 游标 "+feedCursor);
  488 |           PlaylistParser.EntryConsumer consume=source->{
  489 |             String scope=RegionCategoryPolicy.catalogueScope(
  490 |                 source.name,source.group,source.tvgName,source.tvgId,scopeForFeed(feed));
  491 |             feedCandidates.add(new Entry(source.name,source.url,source.group,false,scope,
  492 |                 source.tvgId,source.tvgName));
  493 |           };
  494 |           parsedCount[0]=slowPlayback?PlaylistFeedIndex.readExistingPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested):
  495 |               PlaylistFeedIndex.readPage(cachedFeed,feedCursor,feedPageSize,consume,pauseRequested);
  496 |           if((initialDiscoveryChain||manualRefresh)&&feedIndex<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT){
  497 |             // Preserve every address and the exact raw index cursor. Within the
  498 |             // current 24-row page, probe mainland candidates first. Java's List
  499 |             // sort is stable, so partial-batch fingerprint/resume stays deterministic.
  500 |             feedCandidates.sort((aEntry,bEntry)->Integer.compare(
  501 |                 ChinaDiscoveryCandidatePolicy.initialProbePriority(feedIndex,
  502 |                     aEntry.scope,aEntry.name,aEntry.group,aEntry.tvgName,aEntry.tvgId),
  503 |                 ChinaDiscoveryCandidatePolicy.initialProbePriority(feedIndex,
  504 |                     bEntry.scope,bEntry.name,bEntry.group,bEntry.tvgName,bEntry.tvgId)));
  505 |           }
  506 |           indexedEntries=PlaylistFeedIndex.indexedEntryCount(cachedFeed);
  507 |           // Parsing a cached large playlist can overlap a newly started player.
  508 |           // Do not enqueue its candidates for probing or advance any cursor.
  509 |           if(isStopped())return Result.retry();
  510 |           if(pauseRequested.getAsBoolean()){
  511 |             saveStatus(prefs,"频道操作开始，本轮目录整理已暂停且未发布部分结果",0);
  512 |             return deferredViewingResult(manual,initialDiscoveryChain);
  513 |           }
  514 |           // Keep per-feed discovery counts for diagnosing missing regional stations.
  515 |           // Log metadata only; never expose individual stream URLs or credentials.
  516 |           int localMetadataCandidates=0;
  517 |           int provinceResolvedCandidates=0;
  518 |           for(Entry candidate:feedCandidates){
  519 |             // Count the same metadata used by catalogueScope, including EPG
  520 |             // aliases. Otherwise diagnostics can report zero local stations
  521 |             // even when tvg-name/tvg-id identifies their province.
  522 |             String metadata=(candidate.name==null?"":candidate.name)+" "
  523 |                 +(candidate.group==null?"":candidate.group)+" "
  524 |                 +(candidate.tvgName==null?"":candidate.tvgName)+" "
  525 |                 +(candidate.tvgId==null?"":candidate.tvgId);
  526 |             String normalized=metadata.toLowerCase(Locale.ROOT);
  527 |             boolean provinceResolved=!ChannelNamePolicy.provinceForLocalChannel(metadata).isEmpty();
  528 |             if(provinceResolved)provinceResolvedCandidates++;
  529 |             if(provinceResolved||normalized.contains("地方")||normalized.contains("local")){
  530 |               localMetadataCandidates++;
  531 |             }
  532 |           }
  533 |           android.util.Log.i("MyTVSourceDiscovery","feed_index="+feedIndex
  534 |               +" cursor="+feedCursor+" parsed="+parsedCount[0]
  535 |               +" candidates="+feedCandidates.size()
  536 |               +" mainland_priority_page="
  537 |                   +((initialDiscoveryChain||manualRefresh)&&feedIndex<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT)
  538 |               +" local_metadata="+localMetadataCandidates
  539 |               +" province_resolved="+provinceResolvedCandidates
  540 |               +" feed_scope="+scopeForFeed(feed));
  541 |           if(!feedCandidates.isEmpty())feedsWithEntries++;
  542 |           int remaining=MAX_DISCOVERY_CANDIDATES_PER_RUN-candidates.size();
  543 |           if(feedCandidates.size()>remaining){
  544 |             // Never advance a feed cursor after silently truncating its parsed page.
  545 |             // The next worker run starts with a fresh per-run budget and retries it.
  546 |             feedCandidates.clear();
  547 |             throw new IOException("本轮候选容量已满，保留目录游标并在下轮继续");
  548 |           }
  549 |           candidates.addAll(feedCandidates);
  550 |           feedCandidatesAdded=true;
  551 |           feedReadSucceeded=true;
  552 |           feedsOk++;
  553 |         }catch(Exception e){
  554 |           // A viewing pause is not a source failure or an exhausted feed.
  555 |           if(e instanceof SourceProbeTimePolicy.PausedException||pauseRequested.getAsBoolean()){
  556 |             saveStatus(prefs,"频道播放优先，目录读取已暂停，保留原游标",0);
  557 |             return deferredViewingResult(manual,initialDiscoveryChain);
  558 |           }
  559 |           if(slowPlayback){
  560 |             LIVE_DIRECTORY_RETRY_AFTER.put(feedIndex,android.os.SystemClock.elapsedRealtime()+300000L);
  561 |             continue;
  562 |           }
  563 |           // Keep complete entries parsed before a transient stream read failure;
  564 |           // an incomplete feed is still reported as failed and will be retried later.
  565 |           // Failed pages never contribute partial candidates or advance a cursor.
  566 |           // A partial initial feed is retried from its unchanged cursor. Do not
  567 |           // mix its partial page with another feed's batch, or a later fingerprint
  568 |           // change could discard unprobed entries from the partial page.
  569 |           String failure=String.valueOf(e.getMessage());
  570 |           // A dead/blocked provider is an inspected, UNAVAILABLE outcome rather
  571 |           // than a successful feed. Bound transient failures to three separate
  572 |           // worker attempts; preserve its failure record for later idle/manual retry.
  573 |           if(initialDiscoveryChain){
  574 |             int attempts=InitialFeedFailurePolicy.nextAttempts(
  575 |                 roomInt(initialFeedFailureCountKey(feedIndex),0));
  576 |             update.putInt(initialFeedFailureCountKey(feedIndex),attempts)
  577 |                 .putString(initialFeedFailureReasonKey(feedIndex),
  578 |                     InitialFeedFailurePolicy.userVisibleReason(failure))
  579 |                 .putString(initialFeedDisplayLabelKey(feedIndex),safeFeedLabel(feed));
  580 |             feedTerminalUnavailable=InitialFeedFailurePolicy.isTerminalUnavailable(
  581 |                 failure,attempts);
  582 |             if(feedTerminalUnavailable)android.util.Log.w("MyTVSourceDiscovery",
  583 |                 "first_build_feed_marked_unavailable index="+feedIndex+" attempts="+attempts);
  584 |           }
  585 |           if(manualRefresh){
  586 |             int attempts=InitialFeedFailurePolicy.nextAttempts(
  587 |                 roomInt("source_manual_feed_attempts",0));
  588 |             update.putInt("source_manual_feed_attempts",attempts);
  589 |             feedTerminalUnavailable=InitialFeedFailurePolicy.isTerminalUnavailable(failure,attempts);
  590 |           }
  591 |           feedsFailed++;if(errors.length()<350)errors.append(feed.substring(feed.lastIndexOf('/')+1)).append("；");
  592 |         }finally{
  593 |           feedBatches.add(new FeedBatch(feedIndex,feedCursor,parsedCount[0],
  594 |               indexedEntries,feedReadSucceeded||feedTerminalUnavailable,
  595 |               feedTerminalUnavailable,feedVersion));
  596 |           if(initialDiscoveryChain)attemptedFeedsThisRun[feedIndex]=true;
  597 |         }
  598 |         if(initialDiscoveryChain){
  599 |           if(feedReadSucceeded){
  600 |             // A completed page yields to the next unfinished directory. The
  601 |             // per-feed cursor persists even if the directory has many pages.
  602 |             initialNextFeedIndex=InitialFeedRotationPolicy.nextAfterAttempt(
  603 |                 feedIndex,feeds.length,true,
  604 |                 parsedCount[0]>=MAX_INITIAL_DISCOVERY_PER_FEED);
  605 |             break;
  606 |           }
  607 |           // A bounded failure remains on record as unavailable, not as successful
  608 |           // download. Other feeds can still make a usable complete snapshot.
  609 |           initialNextFeedIndex=InitialFeedRotationPolicy.nextAfterAttempt(
  610 |               feedIndex,feeds.length,feedTerminalUnavailable,false);
  611 |           // One failed directory per idle slice, then commit its actual outcome.
  612 |           break;
  613 |         }
  614 |       }
  615 |       if(slowPlayback&&feedsOk==0)return deferInitialDirectory("稳定直播中：本阶段剩余目录待空闲补查，已保存断点");
  616 |       ArrayList<String> savedScanUrls=new ArrayList<>(savedPool.size());
  617 |       for(Entry entry:savedPool)savedScanUrls.add(entry.url);
  618 |       ArrayList<String> discoveryScanUrls=new ArrayList<>(candidates.size());
  619 |       for(Entry entry:candidates)discoveryScanUrls.add(entry.url);
  620 |       int savedScanStart=SourceSelectionPolicy.nextScanStart(savedScanUrls, roomString("source_saved_scan_after_key",""), roomInt("source_saved_scan_cursor",0));
  621 |       // During a full rebuild every probe slot belongs to newly discovered feed
  622 |       // candidates. Staged rows already retain their measured scores; repeatedly
  623 |       // health-checking them here would starve unseen entries and slow completion.
  624 |       int savedWindow=initialDiscoveryChain?0:Math.min(MAX_PROBES_PER_RUN/2,savedPool.size());
  625 |       int pageFeed=-1;
  626 |       if(initialDiscoveryChain)for(FeedBatch batch:feedBatches)
  627 |         if(batch.success&&!batch.unavailable){pageFeed=batch.index;break;}
  628 |       String batchPrefix=initialDiscoveryChain?DiscoveryBatchPolicy.initialBatchPrefix(pageFeed):
  629 |           manualRefresh?"source_manual_feed_batch_":"source_routine_feed_batch_";
  630 |       StringBuilder batchIdentity=new StringBuilder();
  631 |       appendFingerprintPart(batchIdentity,discoveryFingerprint(candidates));
  632 |       for(FeedBatch batch:feedBatches){
  633 |         // Only pages that actually contributed candidates determine probe
  634 |         // resume. A preceding unavailable feed must not invalidate the same page.
  635 |         if(!batch.success||batch.unavailable||batch.parsed<=0)continue;
  636 |         appendFingerprintPart(batchIdentity,Integer.toString(batch.index));
  637 |         appendFingerprintPart(batchIdentity,batch.version);
  638 |         appendFingerprintPart(batchIdentity,Integer.toString(batch.cursor));
  639 |       }
  640 |       String discoveryFingerprint=CatalogueFingerprint.sha256(batchIdentity.toString());
  641 |       String previousDiscoveryFingerprint=roomString(batchPrefix+"fingerprint","");
  642 |       int previousScanned=roomInt(batchPrefix+"scanned",0);
  643 |       // An upgrade may have one committed legacy page. Migrate only if the
  644 |       // full page fingerprint matches (including feed, version and cursor).
  645 |       if(initialDiscoveryChain&&pageFeed>=0&&previousDiscoveryFingerprint.isEmpty()
  646 |           &&discoveryFingerprint.equals(roomString("source_initial_feed_batch_fingerprint",""))){
  647 |         previousDiscoveryFingerprint=discoveryFingerprint;
  648 |         previousScanned=roomInt("source_initial_feed_batch_scanned",0);
  649 |         update.remove("source_initial_feed_batch_fingerprint").remove("source_initial_feed_batch_scanned");
  650 |       }
  651 |       int initialDiscoveryScanned=DiscoveryBatchPolicy.scannedForBatch(
  652 |           discoveryFingerprint,previousDiscoveryFingerprint,previousScanned,candidates.size());
  653 |       // Resume by exact position inside an unchanged batch. URL-based cursors are
  654 |       // unsafe because distinct channel identities can share one endpoint; choosing
  655 |       // the last duplicate URL can skip entries that were not yet scanned.
  656 |       int discoveryScanStart=DiscoveryBatchPolicy.startIndex(
  657 |           initialDiscoveryScanned,candidates.size());
  658 |       int discoveryWindow=Math.min(DiscoveryBatchPolicy.remaining(
  659 |           initialDiscoveryScanned,candidates.size()),MAX_PROBES_PER_RUN-savedWindow);
  660 |       if(slowPlayback)discoveryWindow=Math.min(1,discoveryWindow);
  661 |       ArrayList<Entry> probeCandidates=new ArrayList<>(savedWindow+discoveryWindow);
  662 |       int savedIndex=0,discoveryIndex=0;
  663 |       while(savedIndex<savedWindow||discoveryIndex<discoveryWindow){
  664 |         if(discoveryIndex<discoveryWindow){
  665 |           Entry e=candidates.get((discoveryScanStart+discoveryIndex)%candidates.size());
  666 |           probeCandidates.add(e);discoveryIndex++;
  667 |         }
  668 |         if(savedIndex<savedWindow){
  669 |           Entry e=savedPool.get((savedScanStart+savedIndex)%savedPool.size());
  670 |           probeCandidates.add(e);savedIndex++;
  671 |         }
  672 |       }
  673 |       // Use independent rotating cursors and a fixed per-run budget for discovery
  674 |       // and saved-source health checks. A moving 5,000-item window combined with one
  675 |       // cursor could skip most saved URLs forever as the window changed between runs.
  676 |       long probeDeadline=android.os.SystemClock.elapsedRealtime()+PROBE_STAGE_DEADLINE_MS;
  677 |       int savedScanned=0,discoveryScanned=0,reusedPreflights=0,initialTimeouts=0;
  678 |       HashSet<String> visitedUrls=new HashSet<>();
  679 |       HashSet<String> savedStreakUpdatedThisRun=new HashSet<>();
  680 |       HashMap<String,ProbeResult> measuredByEndpoint=new HashMap<>();
  681 |       for(int offset=0;offset<probeCandidates.size();offset++){
  682 |         if(pauseRequested.getAsBoolean()){
  683 |           saveStatus(prefs,"用户使用频道时暂停后续线路检测",0);
  684 |           return deferredViewingResult(manual,initialDiscoveryChain);
  685 |         }
  686 |         if(checked>=MAX_PROBES_PER_RUN||isStopped()||android.os.SystemClock.elapsedRealtime()>=probeDeadline)break;
  687 |         Entry e=probeCandidates.get(offset);
  688 |         if(e.savedSource)savedScanned++;else discoveryScanned++;
  689 |         if(!e.url.startsWith("http://")&&!e.url.startsWith("https://"))continue;
  690 |         String endpoint=SourceSelectionPolicy.endpointKey(e.url);
  691 |         ProbeResult measured=measuredByEndpoint.get(endpoint);
  692 |         if(measured==null){
  693 |           if(!visitedUrls.add(endpoint))continue;
  694 |           String cacheKey=initialDiscoveryChain?StableKey.url(e.url):"";
  695 |           RecentProbe cached=initialDiscoveryChain?INITIAL_PROBES.get(cacheKey):null;
  696 |           long clock=android.os.SystemClock.elapsedRealtime();
  697 |           boolean reuse=cached!=null&&SourceUpdateSchedulePolicy.canReuseInitialProbe(
  698 |               cached.result.valid,cached.at,clock);
  699 |           long addressDeadline=probeDeadline;
  700 |           if(initialDiscoveryChain&&!reuse){
  701 |             addressDeadline=SourceProbeTimePolicy.initialAddressDeadline(probeDeadline,clock);
  702 |             if(addressDeadline==0L){
  703 |               // Do not start a new probe with a shortened budget at the tail.
  704 |               discoveryScanned--;
  705 |               break;
  706 |             }
  707 |           }
  708 |           if(reuse){
  709 |             measured=cached.result;
  710 |             reusedPreflights++;
  711 |           }else{
  712 |             checked++;
  713 |             noteInitialStage((slowPlayback?"稳定直播低速检测 · ":"")+"本批检测第 "+checked+" 条（尚未提交）");
  714 |             measured=probe(e.url,addressDeadline);
  715 |           }
  716 |           if(pauseRequested.getAsBoolean()||Thread.currentThread().isInterrupted()){
  717 |             saveStatus(prefs,"频道播放优先，线路检测已暂停，不记录失败",0);
  718 |             return deferredViewingResult(manual,initialDiscoveryChain);
  719 |           }
  720 |           if(probeDeadlineReached(probeDeadline)){
  721 |             // A source interrupted by the global deadline is inconclusive, not a failed source.
  722 |             if(e.savedSource)savedScanned--;else discoveryScanned--;
  723 |             break;
  724 |           }
  725 |           if(initialDiscoveryChain&&!reuse
  726 |               &&android.os.SystemClock.elapsedRealtime()>=addressDeadline){
  727 |             // The address received its bounded first-pass opportunity. Do not
  728 |             // promote, penalize or cache an inconclusive result. The later
  729 |             // manual/routine scan can retry with the normal probe budget.
  730 |             initialTimeouts++;
  731 |             visitedUrls.remove(endpoint);
  732 |             continue;
  733 |           }
  734 |           if(initialDiscoveryChain&&!reuse){
  735 |             INITIAL_PROBES.put(cacheKey,new RecentProbe(measured,android.os.SystemClock.elapsedRealtime()));
  736 |             while(INITIAL_PROBES.size()>INITIAL_PROBE_CACHE_CAP)
  737 |               INITIAL_PROBES.remove(INITIAL_PROBES.keySet().iterator().next());
  738 |           }
  739 |           measuredByEndpoint.put(endpoint,measured);
  740 |           // A previously good source that now fails its probe must lose its old high score.
  741 |           // Keep the URL as a fallback, but rank it below sources that still pass validation.
  742 |           measuredScores.put(scoreKey(e.url),measured.valid?measured.score:-500);
  743 |           measuredScores.remove(legacyScoreKey(e.url));
  744 |         }
  745 |         if(e.savedSource&&savedStreakUpdatedThisRun.add(endpoint)){
  746 |           String streakKey=scoreKey(e.url);
  747 |           int streak=SourceMaintenanceRetentionPolicy.nextFailureStreak(
  748 |               probeFailureStreaks.optInt(streakKey,0),measured.valid);
  749 |           if(streak==0)probeFailureStreaks.remove(streakKey);
  750 |           else probeFailureStreaks.put(streakKey,streak);
  751 |         }
  752 |         if(measured.valid&&(!e.savedSource||initialDiscoveryChain)){
  753 |           discoveryProbesValid++;
  754 |           if(initialDiscoveryChain&&ChinaDiscoveryCandidatePolicy.isClearlyMainland(
  755 |               e.scope,e.name,e.group,e.tvgName,e.tvgId))chinaPreflightsValid++;
  756 |         }
  757 |         // Whether this is the first full build or routine discovery, a
  758 |         // newly discovered URL must pass the media preflight before it may
  759 |         // become a published channel or a new backup. Failed saved sources
  760 |         // remain protected by the independent Room retention policy above.
  761 |         if(!SourceDiscoveryAcceptancePolicy.mayPromoteNewCandidate(measured.valid))
  762 |           continue;
  763 |         String key=identity(e.name,e.group,e.url,e.tvgId,e.tvgName);JSONObject target=byIdentity.get(key);
  764 |         if(target!=null){
  765 |           String canonical=ChannelNamePolicy.canonicalBroadcasterName(
  766 |               e.name,e.tvgName,e.tvgId,e.group);
  767 |           if(!canonical.isEmpty())target.put("name",canonical);
  768 |           if(target.optString("tvgId","").isEmpty()&&!e.tvgId.isEmpty())target.put("tvgId",e.tvgId);
  769 |           if(target.optString("tvgName","").isEmpty()&&!e.tvgName.isEmpty())target.put("tvgName",e.tvgName);
  770 |         }
  771 |         // Merge every candidate for an existing channel through one retention policy.
  772 |         // This protects the stable primary and last selected source before any
  773 |         // capacity replacement occurs, rather than trying to restore them afterwards.
  774 |         if(target==null){
  775 |           target=new JSONObject();JSONArray sources=new JSONArray();sources.put(e.url);
  776 |           target.put("name",canonicalDisplayName(e));target.put("url",e.url);target.put("group",e.group);
  777 |           target.put("tvgId",e.tvgId);target.put("tvgName",e.tvgName);target.put("sources",sources);
  778 |           addBackupSourceScope(target,e.url,e.scope);
  779 |           if(initialDiscoveryChain)stagedPositions.put(target,saved.length());
  780 |           saved.put(target);byIdentity.put(key,target);added++;
  781 |         }else{
  782 |           JSONArray currentSources=target.optJSONArray("sources");
  783 |           ArrayList<String> currentUrls=new ArrayList<>();
  784 |           if(currentSources!=null){
  785 |             for(int i=0;i<currentSources.length();i++)currentUrls.add(currentSources.optString(i,""));
  786 |           }else{
  787 |             String primary=target.optString("url","");
  788 |             if(!primary.isEmpty())currentUrls.add(primary);
  789 |           }
  790 |           int activeIndex=Math.max(0,target.optInt("activeSource",0));
  791 |           String activeUrl=activeIndex<currentUrls.size()
  792 |               ?currentUrls.get(activeIndex):target.optString("url","");
  793 |           String primaryUrl=target.optString("url","");
  794 |           java.util.Comparator<String> bestFirst=(a,b)->
  795 |               Double.compare(combinedScore(b,measuredScores,observedQoe),
  796 |                   combinedScore(a,measuredScores,observedQoe));
  797 |           ArrayList<String> retainedUrls;
  798 |           if(initialDiscoveryChain){
  799 |             retainedUrls=new ArrayList<>(SourceChannelMergePolicy.attachSharedEndpoint(
  800 |                 currentUrls,e.url,MAX_SOURCES_PER_CHANNEL,bestFirst,primaryUrl,activeUrl));
  801 |           }else{
  802 |             HashSet<String> eligibleRetirement=new HashSet<>();
  803 |             for(String oldUrl:currentUrls){
  804 |               if(probeFailureStreaks.optInt(scoreKey(oldUrl),0)
  805 |                   >=SourceMaintenanceRetentionPolicy.FAILURES_BEFORE_RETIREMENT)
  806 |                 eligibleRetirement.add(SourceSelectionPolicy.endpointKey(oldUrl));
  807 |             }
  808 |             retainedUrls=new ArrayList<>(SourceMaintenanceRetentionPolicy.retainDuringRoutineUpdate(
  809 |                 currentUrls,e.url,MAX_SOURCES_PER_CHANNEL,bestFirst,
  810 |                 primaryUrl,activeUrl,eligibleRetirement));
  811 |           }
  812 |           boolean changed=currentUrls.size()!=retainedUrls.size();
  813 |           if(!changed){
  814 |             for(int i=0;i<currentUrls.size();i++){
  815 |               if(!currentUrls.get(i).equals(retainedUrls.get(i))){changed=true;break;}
  816 |             }
  817 |           }
  818 |           if(changed){
  819 |             JSONArray updatedSources=new JSONArray();
  820 |             for(String retainedUrl:retainedUrls)updatedSources.put(retainedUrl);
  821 |             target.put("sources",updatedSources);
  822 |             added++;
  823 |           }
  824 |           String retainedEndpointUrl="";
  825 |           for(String retainedUrl:retainedUrls){
  826 |             if(endpoint.equals(SourceSelectionPolicy.endpointKey(retainedUrl))){
  827 |               retainedEndpointUrl=retainedUrl;break;
  828 |             }
  829 |           }
  830 |           if(!retainedEndpointUrl.isEmpty()){
  831 |             addBackupSourceScope(target,retainedEndpointUrl,e.scope);
  832 |           }
  833 |         }
  834 |         if(initialDiscoveryChain)touchedStagedRows.add(stagedPositions.get(target));
  835 |       }
  836 |       // Even if every discovery directory is temporarily unavailable, continue
  837 |       // rotating through already-saved source URLs. Existing channels still need
  838 |       // health checks while public catalogue services are down; retry discovery
  839 |       // separately after persisting these local probe results.
  840 |       boolean discoveryUnavailable=feedsOk==0;
  841 |       if(initialDiscoveryChain)android.util.Log.i("MyTVSourceDiscovery","network_probes="+checked+" reused_recent="+reusedPreflights+" inconclusive_budget="+initialTimeouts);
  842 |       // Keep each channel's best distinct sources together, ranked by measured
  843 |       // reachability/speed/resolution. Generic names remain host-separated; recognized
  844 |       // CCTV and satellite channels merge across source hosts.
  845 |       // Initial staging is append-only and touched rows alone need sorting.
  846 |       // A final idle pass re-ranks the complete snapshot before publication.
  847 |       boolean finalRanking=initialDiscoveryChain&&initialDiscoveryScanned+discoveryScanned>=candidates.size()
  848 |           &&areAllDiscoveryFeedsComplete(prefs,feedBatches,feeds.length)&&!slowPlayback;
  849 |       Iterable<Integer> rankingRows=initialDiscoveryChain&&!finalRanking?touchedStagedRows:
  850 |           ()->new Iterator<Integer>(){private int index;public boolean hasNext(){return index<saved.length();}public Integer next(){return index++;}};
  851 |       for(int i:rankingRows){
  852 |         SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
  853 |         JSONObject channel=saved.optJSONObject(i);if(channel==null)continue;
  854 |         JSONArray sourceArray=channel.optJSONArray("sources");
  855 |         if(sourceArray==null||sourceArray.length()==0){String primary=channel.optString("url","");if(primary.isEmpty())continue;sourceArray=new JSONArray();sourceArray.put(primary);}
  856 |         // Preserve the user's last selected source across background re-ranking.
  857 |         // Ranking may change the preferred primary URL, but must not silently
  858 |         // replace the source that the user explicitly selected for this channel.
  859 |         int previousActiveIndex=Math.max(0,channel.optInt("activeSource",0));
  860 |         String previousActiveUrl=previousActiveIndex<sourceArray.length()?sourceArray.optString(previousActiveIndex,""):channel.optString("url","");
  861 |         ArrayList<String> ordered=new ArrayList<>();
  862 |         HashSet<String> orderedEndpoints=new HashSet<>();
  863 |         for(int j=0;j<sourceArray.length();j++){
  864 |           String u=sourceArray.optString(j,"");
  865 |           if((u.startsWith("http://")||u.startsWith("https://"))
  866 |               &&orderedEndpoints.add(SourceSelectionPolicy.endpointKey(u)))ordered.add(u);
  867 |         }
  868 |         String stablePrimaryUrl=channel.optString("url","");
  869 |         // Preserve both the stable channel identity and the user's last selected
  870 |         // source even when the ranked list is trimmed to the eight-source cap.
  871 |         ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
  872 |         ordered=new ArrayList<>(SourceRankingRetentionPolicy.retainRequiredAndBest(
  873 |             ordered,stablePrimaryUrl,previousActiveUrl,MAX_SOURCES_PER_CHANNEL));
  874 |         ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
  875 |         JSONArray ranked=new JSONArray();int restoredActiveIndex=0;
  876 |         for(int j=0;j<ordered.size();j++){String u=ordered.get(j);ranked.put(u);if(u.equals(previousActiveUrl))restoredActiveIndex=j;}
  877 |         if(ranked.length()>0){
  878 |           // Keep url as the stable channel identity. The ranked source list and
  879 |           // activeSource carry playback preference independently.
  880 |           channel.put("sources",ranked);channel.put("activeSource",restoredActiveIndex);
  881 |           retainBackupSourceScopes(channel,ranked);
  882 |         }
  883 |       }
  884 |       // Persist measured quality independently from URL labels so the player can rank
  885 |       // candidates by reachability, estimated speed and manifest resolution. This is
  886 |       // a preflight score, not a guarantee of audio/video playback.
  887 |       trimProbeScores(measuredScores,savedPool,probeCandidates);
  888 |       trimProbeScores(probeFailureStreaks,savedPool,probeCandidates);
  889 |       // The database snapshot is checked again at commit time by Room's transaction;
  890 |       // no SharedPreferences catalogue comparison participates in this update.
  891 |       // Playback can begin after the last probe-loop check but before persistence.
  892 |       // Re-check immediately before the catalogue commit so a just-started session
  893 |       // is not overlapped by the worker's final state write.
  894 |       if(pauseRequested.getAsBoolean()){
  895 |         saveStatus(prefs,"观看或频道操作已开始，放弃本轮发布，旧完整目录仍然可用",0);
  896 |         return deferredViewingResult(manual,initialDiscoveryChain);
  897 |       }
  898 |       if(isStopped())return Result.retry();
  899 |       int nextBatchScanned=Math.min(candidates.size(),initialDiscoveryScanned+discoveryScanned);
  900 |       boolean feedBatchComplete=nextBatchScanned>=candidates.size();
  901 |       if(initialDiscoveryChain&&!feedBatches.isEmpty()){
  902 |         // If a 90-second deadline paused probing inside the current feed page,
  903 |         // resume this exact feed and its fingerprint before rotating onward.
  904 |         // Otherwise its prefix would be rescanned and the tail could starve.
  905 |         int attemptedFeed=feedBatches.get(feedBatches.size()-1).index;
  906 |         initialNextFeedIndex=slowPlayback&&pageFeed>=0?(pageFeed+1)%feeds.length:
  907 |             InitialFeedRotationPolicy.nextAfterProbeBatch(
  908 |                 attemptedFeed,initialNextFeedIndex,feeds.length,feedBatchComplete);
  909 |       }
  910 |       boolean initialFeedsExhausted=initialDiscoveryChain&&feedBatchComplete
  911 |           &&areAllDiscoveryFeedsComplete(prefs,feedBatches,feeds.length);
  912 |       boolean initialHasValidatedSource=initialDiscoveryChain
  913 |           &&(roomBoolean("source_initial_has_validated_source",false)||discoveryProbesValid>0);
  914 |       boolean awaitingIdleFinalization=FullDiscoveryProgressPolicy.awaitingIdleFinalization(initialFeedsExhausted,slowPlayback);
  915 |       boolean initialDiscoveryComplete=FullDiscoveryProgressPolicy.canPublishInitialSnapshot(
  916 |           initialFeedsExhausted,initialHasValidatedSource,slowPlayback);
  917 |       // Save the last probed page in staging even while viewing. Only merging
  918 |       // the live snapshot (or resetting an unsuccessful build) requires idle.
  919 |       if(initialDiscoveryComplete)roomSnapshot=readPublishedForInitialCommit();
  920 |       // Prepare the full publication and all progress before opening a write transaction.
  921 |       JSONArray publication=initialDiscoveryComplete
  922 |           ?preservePreviouslyPublishedChannels(saved,roomSnapshot):saved;
  923 |       int publishedChannelCount=publication.length();
  924 |       if(initialDiscoveryComplete&&saved.length()==0)
  925 |         throw new IllegalStateException("Full rebuild has no validated channels");
  926 |       if(pauseRequested.getAsBoolean())
  927 |         return deferredViewingResult(manual,initialDiscoveryChain);
  928 |       update.putInt("source_scan_cursor",candidates.isEmpty()?0:(discoveryScanStart+discoveryScanned)%candidates.size())
  929 |           .putInt("source_saved_scan_cursor",savedPool.isEmpty()?0:(savedScanStart+savedScanned)%savedPool.size())
  930 |           .putString("source_scan_after_key",discoveryScanned>0?SourceSelectionPolicy.lastScannedKey(discoveryScanUrls,discoveryScanStart,discoveryScanned):roomString("source_scan_after_key",""))
  931 |           .putString("source_saved_scan_after_key",savedScanned>0?SourceSelectionPolicy.lastScannedKey(savedScanUrls,savedScanStart,savedScanned):roomString("source_saved_scan_after_key",""))
  932 |           .putString("source_probe_scores_v1",measuredScores.toString())
  933 |           .putString("source_probe_failure_streaks_v1",probeFailureStreaks.toString())
  934 |           // The exact catalogue snapshot is represented by a compact fingerprint.
  935 |           .putString("source_update_time",now())
  936 |           .putString("source_update_result",(initialDiscoveryChain
  937 |               ?(initialDiscoveryComplete?"全量目录采集完成并已原子发布；":"独立全量目录构建中，保留已发布频道；")
  938 |               :"已安全更新当前目录；")+(initialDiscoveryChain?"首次全量重建按批次覆盖全部配置目录；":(foregroundAtStart||isActivityActive(prefs)?"日常更新优先读取中国及港澳台目录，全球目录留待后台运行时补充；":"本轮已检查中国及全球公开目录；"))+(discoveryUnavailable?"公开目录暂不可用，仍完成已保存线路的轮检；":(feedsWithEntries==0?"目录可访问，但没有解析出有效频道条目；":(discoveryProbesValid==0?(initialHasValidatedSource?"本轮新增候选未通过媒体预检，未纳入已验证频道；":"已解析频道条目，但尚无候选线路通过媒体预检；"):"成功读取 "+feedsOk+" 个目录，"+feedsWithEntries+" 个含频道条目，"+discoveryProbesValid+" 条候选通过媒体预检；")))+"初筛 "+checked+" 个候选地址；新增/替换 "+added+" 个备用地址；每频道最多保留 "+MAX_SOURCES_PER_CHANNEL+" 条线路；网络初筛不能保证实际画面与声音，仍需播放器验证；"+(errors.length()>0?"部分目录失败。":""));
  939 |       if(initialDiscoveryChain){
  940 |         update.putInt("source_initial_next_feed_index",initialNextFeedIndex);
  941 |       }
  942 |       // Advance a feed only after every candidate in the current multi-feed batch
  943 |       // has had its probe turn. This prevents losing the tail when the probe deadline
  944 |       // interrupts a run, and ensures later playlist pages are eventually discovered.
  945 |       if(feedBatchComplete){
  946 |         for(FeedBatch batch:feedBatches){
  947 |           if(!batch.success)continue;
  948 |           if(!initialDiscoveryChain && batch.parsed>0
  949 |               && roomBoolean(initialFeedUnavailableKey(batch.index),false)){
  950 |             // A previously unavailable directory has now been fetched and its
  951 |             // candidates have completed this run's probe window. Clear its old
  952 |             // warning; other old source warnings remain intact.
  953 |             update.remove(initialFeedUnavailableKey(batch.index))
  954 |                 .remove(initialFeedFailureCountKey(batch.index))
  955 |                 .remove(initialFeedFailureReasonKey(batch.index))
  956 |                 .remove(initialFeedDisplayLabelKey(batch.index));
  957 |           }
  958 |           if(initialDiscoveryChain&&batch.unavailable){
  959 |             update.putBoolean(initialFeedUnavailableKey(batch.index),true);
  960 |           }else if(initialDiscoveryChain){
  961 |             update.remove(initialFeedUnavailableKey(batch.index))
  962 |                 .remove(initialFeedFailureCountKey(batch.index))
  963 |                 .remove(initialFeedFailureReasonKey(batch.index))
  964 |                 .remove(initialFeedDisplayLabelKey(batch.index));
  965 |           }
  966 |           String cursorKey=initialDiscoveryChain?feedCursorKey(batch.index):
  967 |               manualRefresh?manualFeedCursorKey(batch.index):routineFeedCursorKey(batch.index);
  968 |           if(initialDiscoveryChain&&SourceUpdateSchedulePolicy.manualFeedFinished(batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED)){
  969 |             update.putBoolean(initialFeedDoneKey(batch.index),true)
  970 |                 .remove(cursorKey).remove(cursorKey+"_version");
  971 |           }else if(batch.parsed>0){
  972 |             if(!initialDiscoveryChain&&batch.indexedEntries>=0&&batch.cursor+batch.parsed>=batch.indexedEntries)
  973 |               update.remove(cursorKey).remove(cursorKey+"_version");
  974 |             else update.putInt(cursorKey,batch.cursor+batch.parsed)
  975 |                 .putString(cursorKey+"_version",batch.version);
  976 |           }else if(initialDiscoveryChain){
  977 |             update.putBoolean(initialFeedDoneKey(batch.index),true)
  978 |                 .remove(cursorKey).remove(cursorKey+"_version");
  979 |           }else{
  980 |             update.remove(cursorKey).remove(cursorKey+"_version");
  981 |           }
  982 |         }
  983 |         update.remove(batchPrefix+"fingerprint").remove(batchPrefix+"scanned");
  984 |       }else if(candidates.size()>0){
  985 |         update.putString(batchPrefix+"fingerprint",discoveryFingerprint)
  986 |             .putInt(batchPrefix+"scanned",nextBatchScanned);
  987 |       }
  988 |       if(!initialDiscoveryChain&&feedBatchComplete){
  989 |         int stillUnavailable=0;
  990 |         for(int i=0;i<feeds.length;i++){
  991 |           boolean wasUnavailable=roomBoolean(initialFeedUnavailableKey(i),false);
  992 |           if(wasUnavailable){
  993 |             for(FeedBatch batch:feedBatches){
  994 |               if(batch.index==i&&batch.success&&batch.parsed>0){wasUnavailable=false;break;}
  995 |             }
  996 |           }
  997 |           if(wasUnavailable)stillUnavailable++;
  998 |         }
  999 |         update.putInt("source_initial_progress_unavailable_feeds",stillUnavailable);
 1000 |       }
 1001 |       int previewLevel=0, previousPreviewLevel=0;
 1002 |       if(initialDiscoveryChain){
 1003 |         // Only count a source feed as done after its entire page was probed and
 1004 |         // end-of-feed was observed. A successful HTTP response alone is not done.
 1005 |         boolean[] finished=new boolean[feeds.length];
 1006 |         int completedFeeds=0;
 1007 |         for(int i=0;i<feeds.length;i++)finished[i]=roomBoolean(initialFeedDoneKey(i),false);
 1008 |         if(feedBatchComplete){
 1009 |           for(FeedBatch batch:feedBatches){
 1010 |             if(batch.success&&batch.index>=0&&batch.index<feeds.length
 1011 |                 &&SourceUpdateSchedulePolicy.manualFeedFinished(batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED))
 1012 |               finished[batch.index]=true;
 1013 |           }
 1014 |         }
 1015 |         for(boolean done:finished)if(done)completedFeeds++;
 1016 |         boolean[] unavailableFlags=new boolean[feeds.length];
 1017 |         for(int i=0;i<feeds.length;i++)
 1018 |           unavailableFlags[i]=roomBoolean(initialFeedUnavailableKey(i),false);
 1019 |         if(feedBatchComplete){
 1020 |           for(FeedBatch batch:feedBatches)
 1021 |             if(batch.index>=0&&batch.index<feeds.length&&batch.unavailable
 1022 |                 &&batch.parsed<MAX_INITIAL_DISCOVERY_PER_FEED)
 1023 |               unavailableFlags[batch.index]=true;
 1024 |         }
 1025 |         int unavailableCount=0;
 1026 |         for(boolean missing:unavailableFlags)if(missing)unavailableCount++;
 1027 |         int activeFeed=initialNextFeedIndex;
 1028 |         int activeCursor=0,activeEntries=-1;
 1029 |         for(FeedBatch batch:feedBatches){
 1030 |           if(batch.success&&!finished[batch.index]&&batch.indexedEntries>0){
 1031 |             activeFeed=batch.index;
 1032 |             activeCursor=batch.cursor+(feedBatchComplete?batch.parsed:nextBatchScanned);
 1033 |             activeEntries=batch.indexedEntries;
 1034 |           }
 1035 |         }
 1036 |         int[] partial=new int[feeds.length];
 1037 |         for(int i=0;i<feeds.length;i++)
 1038 |           partial[i]=roomInt(initialFeedProgressKey(i),0);
 1039 |         for(FeedBatch batch:feedBatches){
 1040 |           if(batch.index<0||batch.index>=feeds.length||!batch.success)continue;
 1041 |           int basis=finished[batch.index]?10000:
 1042 |               FullDiscoveryProgressPolicy.feedProgress(
 1043 |                   batch.cursor+(feedBatchComplete?batch.parsed:nextBatchScanned),
 1044 |                   batch.indexedEntries);
 1045 |           partial[batch.index]=basis;
 1046 |           update.putInt(initialFeedProgressKey(batch.index),basis);
 1047 |         }
 1048 |         int progressPercent=FullDiscoveryProgressPolicy.overallPercent(
 1049 |             finished,partial,initialDiscoveryComplete);
 1050 |         int chinaFeedDone=SourceDiscoveryPriorityPolicy.completedInStage(
 1051 |             finished,0,SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
 1052 |         int activeStage=SourceDiscoveryPriorityPolicy.activeStage(finished,
 1053 |             SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,
 1054 |             SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT);
 1055 |         String stageDescription=SourceDiscoveryPriorityPolicy.stageLabel(activeStage);
 1056 |         // A changed feed version can legitimately reduce the measured work.
 1057 |         // Do not preserve an inflated high-water mark after a cursor reset.
 1058 |         int checkedSoFar=Math.max(0,roomInt("source_initial_progress_checked_urls",0));
 1059 |         int validatedSoFar=Math.max(0,roomInt("source_initial_progress_validated_urls",0));
 1060 |         int chinaValidatedSoFar=Math.max(0,
 1061 |             roomInt("source_initial_progress_china_validated_urls",0));
 1062 |         previousPreviewLevel=roomInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0);
 1063 |         previewLevel=FullDiscoveryProgressPolicy.nextPreviewLevel(previousPreviewLevel,saved.length(),
 1064 |             (int)Math.min(Integer.MAX_VALUE,(long)chinaValidatedSoFar+chinaPreflightsValid),activeStage>0);
 1065 |         int timeoutTotal=(int)Math.min(Integer.MAX_VALUE,(long)Math.max(0,roomInt("source_initial_progress_timeout_urls",0))+initialTimeouts);
 1066 |         update.putInt("source_initial_progress_timeout_urls",timeoutTotal)
 1067 |             .putInt("source_initial_progress_percent",progressPercent)
 1068 |             .putInt("source_initial_progress_done_feeds",completedFeeds)
 1069 |             .putInt("source_initial_progress_total_feeds",feeds.length)
 1070 |             .putInt("source_initial_progress_china_done_feeds",chinaFeedDone)
 1071 |             .putInt("source_initial_progress_china_total_feeds",
 1072 |                 SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT)
 1073 |             .putInt("source_initial_progress_staged_channels",publishedChannelCount)
 1074 |             .putInt("source_initial_progress_checked_urls",
 1075 |                 (int)Math.min(Integer.MAX_VALUE,(long)checkedSoFar+Math.max(0,checked)))
 1076 |             .putInt("source_initial_progress_validated_urls",
 1077 |                 (int)Math.min(Integer.MAX_VALUE,
 1078 |                     (long)validatedSoFar+Math.max(0,discoveryProbesValid)))
 1079 |             .putInt("source_initial_progress_china_validated_urls",
 1080 |                 (int)Math.min(Integer.MAX_VALUE,
 1081 |                     (long)chinaValidatedSoFar+Math.max(0,chinaPreflightsValid)))
 1082 |             .putInt("source_initial_progress_unavailable_feeds",unavailableCount)
 1083 |             .putInt("source_initial_progress_feed_index",Math.max(0,activeFeed))
 1084 |             .putInt("source_initial_progress_feed_cursor",Math.max(0,activeCursor))
 1085 |             .putInt("source_initial_progress_feed_entries",Math.max(0,activeEntries))
 1086 |             .putString("source_initial_progress_phase",initialDiscoveryComplete
 1087 |                 ?("完整频道快照已发布，另有 "+unavailableCount+" 个来源暂不可用，待空闲补查")
 1088 |                 :(awaitingIdleFinalization?"线路初筛已完成，等待空闲发布或确认结果":stageDescription+"；暂不可用 "+unavailableCount+" 个"))
 1089 |             .putString("source_update_result",initialDiscoveryComplete
 1090 |                 ?("首次全量目录检查已完成并发布；成功处理 "
 1091 |                     +(completedFeeds-unavailableCount)+"/"+feeds.length
 1092 |                     +" 个来源，另 "+unavailableCount+" 个已记录为暂不可用；通过媒体预检 "
 1093 |                     +(validatedSoFar+Math.max(0,discoveryProbesValid))
 1094 |                     +" 次（含近期重复地址复用，非实机播放验证）；预算超时未确认 "+timeoutTotal+" 次，待后续补查。")
 1095 |                 :("首次完整抓源进度 "+progressPercent+"%；已处理 "
 1096 |                     +completedFeeds+"/"+feeds.length+" 个来源，暂不可用 "
 1097 |                     +unavailableCount+" 个；预算超时未确认 "+timeoutTotal+" 次；已发布频道保留，未完成线路继续检测。"));
 1098 |       }
 1099 |       if(initialDiscoveryChain&&initialHasValidatedSource){
 1100 |         update.putBoolean("source_initial_has_validated_source",true);
 1101 |       }
 1102 |       if(initialDiscoveryChain&&initialDiscoveryComplete){
 1103 |         FullDiscoveryProgressPolicy.Coverage fresh=new FullDiscoveryProgressPolicy.Coverage();
 1104 |         for(int i=0;i<saved.length();i++){
 1105 |           SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
 1106 |           JSONObject row=saved.getJSONObject(i);
 1107 |           fresh.add(row.optString("name"),row.optString("group"),
 1108 |               row.optString("tvgName"),row.optString("tvgId"));
 1109 |         }
 1110 |         update.putString(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY,
 1111 |             fresh.summary("本次预检入库目录（合并旧频道之前，非实播验证）"));
 1112 |         if(fresh.cctvCount()==0||fresh.satelliteCount()==0||fresh.localCount()==0){
 1113 |           update.putString("source_initial_progress_phase",
 1114 |               "目录已发布，核心中国频道覆盖不足，待空闲或手动补查");
 1115 |         }
 1116 |         update.remove("source_initial_discovery_fingerprint").remove("source_initial_discovery_scanned")
 1117 |             .remove("source_initial_has_validated_source")
 1118 |             .remove("source_full_rebuild_active");
 1119 |         if(saved.length()>0){
 1120 |           long completedAt=System.currentTimeMillis();
 1121 |           update.putLong("source_update_last_success_at",completedAt)
 1122 |               .putLong("source_full_rebuild_completed_at",completedAt);
 1123 |         }
 1124 |       }else if(!initialDiscoveryChain&&SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(discoveryProbesValid)){
 1125 |         update.putLong("source_update_last_success_at",System.currentTimeMillis());
 1126 |       }
 1127 |       // A completed, committed probe pass advances the weekly deadline;
 1128 |       // skipped or failed work leaves the due date unchanged.
 1129 |       boolean manualComplete=false;
 1130 |       if(manualRefresh){
 1131 |         FeedBatch page=feedBatches.isEmpty()?null:feedBatches.get(0);
 1132 |         boolean advance=page!=null&&feedBatchComplete&&page.success
 1133 |             &&(page.unavailable||SourceUpdateSchedulePolicy.manualFeedFinished(
 1134 |                 page.cursor,page.parsed,page.indexedEntries,MAX_PROBES_PER_RUN/2));
 1135 |         int next=advance?routineStart+1:routineStart;
 1136 |         int failed=Math.max(0,roomInt("source_manual_failed_feeds",0));
 1137 |         if(advance&&page.unavailable)failed++;
 1138 |         if(advance||page!=null&&page.success&&!page.unavailable)
 1139 |           update.putInt("source_manual_feed_attempts",0);
 1140 |         manualComplete=next>=feeds.length;
 1141 |         update.putInt("source_manual_next_feed",next)
 1142 |             .putInt("source_manual_failed_feeds",failed)
 1143 |             .putString("source_update_result","手动完整目录轮检：已处理 "+next+"/"+feeds.length
 1144 |                 +" 个来源，暂不可用 "+failed+" 个。"
 1145 |                 +(manualComplete?"本轮目录处理结束；":"已保存断点，空闲时继续；")
 1146 |                 +"旧频道保留，媒体预检不等于实播。");
 1147 |         if(manualComplete)update.putString("source_manual_completed_id",manualId)
 1148 |             .remove("source_manual_active_id");
 1149 |       }else if(!initialDiscoveryChain){
 1150 |         int nextRoutine=SourceUpdateSchedulePolicy.routineNextFeed(routineStart,feeds.length,feedBatchComplete);
 1151 |         update.putInt("source_routine_next_feed_index",nextRoutine);
 1152 |         if(!manual&&feedBatchComplete&&nextRoutine==0&&publishedChannelCount>0&&checked>0)
 1153 |           update.putLong("source_last_routine_maintenance_at",System.currentTimeMillis());
 1154 |         update.putString("source_update_result","日常分批轮检：目录 "+(routineStart+1)+"–"+routineEnd
 1155 |             +"/"+feeds.length+"；读取成功 "+feedsOk+"，失败 "+feedsFailed
 1156 |             +"；媒体预检 "+checked+" 次；"+(feedBatchComplete?"本批已处理":"保留本批游标，下次继续")
 1157 |             +"。旧目录保留，实际播放待验证。");
 1158 |       }
 1159 |       update.remove("source_update_pending_base_fingerprint")
 1160 |           .remove("source_update_pending_base")
 1161 |           .remove("source_update_pending_channels");
 1162 |       if(initialFeedsExhausted&&!initialHasValidatedSource&&!awaitingIdleFinalization){
 1163 |         // Every feed and every current candidate has had its probe turn. Do not
 1164 |         // retry an exhausted, unpublishable build forever merely because the TV
 1165 |         // Activity happens to remain open; discard staging and retain the live DB.
 1166 |         // A failed full scan must not preserve per-feed "done" flags or cursors.
 1167 |         // Otherwise the next rebuild may start with every feed already exhausted,
 1168 |         // see no candidates, and never retry discovery after network recovery.
 1169 |         com.wodedianshi.tv.db.AppStateEditor failedBuildReset=update;
 1170 |         for(int feed=0;feed<feeds.length;feed++){
 1171 |           // Reset the publish/scan cursors for the next attempt, but leave the
 1172 |           // last failure diagnostics visible in Settings until that next attempt
 1173 |           // starts and explicitly initializes a fresh build.
 1174 |           failedBuildReset.remove(initialFeedDoneKey(feed)).remove(feedCursorKey(feed))
 1175 |               .remove(feedCursorKey(feed)+"_version")
 1176 |               .remove(initialFeedProgressKey(feed))
 1177 |               .remove(initialAcquiredVersionKey(feed))
 1178 |               .remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"fingerprint").remove(DiscoveryBatchPolicy.initialBatchPrefix(feed)+"scanned");
 1179 |         }
 1180 |         failedBuildReset.remove("source_full_rebuild_active")
 1181 |             .remove("source_full_rebuild_feed_fingerprint")
 1182 |             .remove("source_initial_has_validated_source")
 1183 |             .remove("source_initial_discovery_fingerprint")
 1184 |             .remove("source_initial_discovery_scanned")
 1185 |             .remove("source_feed_batch_fingerprint")
 1186 |             .remove("source_feed_batch_scanned")
 1187 |             .remove("source_initial_feed_batch_fingerprint")
 1188 |             .remove("source_initial_feed_batch_scanned")
 1189 |             .putInt("source_initial_next_feed_index",0)
 1190 |             .putInt("source_initial_progress_percent",0)
 1191 |             .putInt("source_initial_progress_done_feeds",0)
 1192 |             .putInt("source_initial_progress_china_done_feeds",0)
 1193 |             .putInt("source_initial_progress_staged_channels",0)
 1194 |             .putInt("source_initial_progress_checked_urls",0)
 1195 |             .putInt("source_initial_progress_validated_urls",0)
 1196 |             .putInt("source_initial_progress_china_validated_urls",0)
 1197 |             .putString("source_initial_progress_phase","本轮没有通过预检的有效频道，未发布快照；暂不可用目录和失败原因已保留，恢复网络后可手动重试");
 1198 |         discardStagingReuse();
 1199 |         com.wodedianshi.tv.db.RoomCatalogueBuildStore.clear(
 1200 |             getApplicationContext(),failedBuildReset,pauseRequested);
 1201 |         saveStatus(prefs,saved.length()==0
 1202 |             ?"全量目录采集已结束，但没有可发布的有效频道；旧目录继续保留，等待下次重新采集"
 1203 |             :"全量候选线路均未通过媒体预检；未替换旧目录，后续可重新采集",0);
 1204 |         return Result.failure();
 1205 |       }
 1206 |       // Commit one unit: either unpublished staging + progress or published
 1207 |       // rows + checkpoint + progress. Cancellation rolls back the whole unit.
 1208 |       noteInitialStage(initialDiscoveryComplete?"正在发布完整快照":"正在保存本批已完成进度");
 1209 |       if(initialDiscoveryChain&&!initialDiscoveryComplete){
 1210 |         if(finalRanking)com.wodedianshi.tv.db.RoomCatalogueBuildStore.replace(
 1211 |             getApplicationContext(),saved,pauseRequested,update);
 1212 |         else {
 1213 |           String revision=java.util.UUID.randomUUID().toString();
 1214 |           update.putString(com.wodedianshi.tv.db.RoomCatalogueBuildStore.STAGING_REVISION_KEY,revision);
 1215 |           com.wodedianshi.tv.db.RoomCatalogueBuildStore.updateTouched(
 1216 |               getApplicationContext(),saved,previousStagedSize,touchedStagedRows,pauseRequested,update);
 1217 |           // Remember only after the transaction has returned successfully.
 1218 |           if(FullDiscoveryProgressPolicy.canRetainStaging(saved.length(),slowPlayback)){
 1219 |             committedStaging=saved;committedStagingRevision=revision;
 1220 |             committedStagingAt=android.os.SystemClock.elapsedRealtime();
 1221 |           }
 1222 |         }
 1223 |         if(!slowPlayback&&previewLevel>previousPreviewLevel){
 1224 |           // First commit staging and cursors. A failed preview never loses that work.
 1225 |           noteInitialStage("正在发布阶段性已预检频道");
 1226 |           roomSnapshot=readPublishedForInitialCommit();
 1227 |           JSONArray preview=preservePreviouslyPublishedChannels(saved,roomSnapshot);
 1228 |           com.wodedianshi.tv.db.AppStateEditor previewState=
 1229 |               new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
 1230 |                   .putInt(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,previewLevel);
 1231 |           int count=com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshotWithProgress(
 1232 |               getApplicationContext(),preview,roomSnapshot.catalogueVersion,false,pauseRequested,previewState);
 1233 |           if(count!=preview.length())throw new IllegalStateException("Preview directory count mismatch");
 1234 |         }
 1235 |       }else{
 1236 |         int written=com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshotWithProgress(
 1237 |             getApplicationContext(),publication,roomSnapshot.catalogueVersion,
 1238 |             initialDiscoveryComplete,pauseRequested,update);
 1239 |         if(written!=publishedChannelCount)
 1240 |           throw new IllegalStateException("Committed directory count mismatch");
 1241 |       }
 1242 |       if(awaitingIdleFinalization)return deferInitialDirectory("线路初筛已完成，等待空闲发布或确认结果");
 1243 |       if(initialDiscoveryChain&&!initialDiscoveryComplete&&!feedBatches.isEmpty()){
 1244 |         // Empty pages and recorded failures are durable progress too; rotate
 1245 |         // rather than repeatedly back off the same WorkRequest.
 1246 |         enqueueInitialContinuation(feedsOk==0?30L:slowPlayback?20L:0L);
 1247 |         return Result.success();
 1248 |       }
 1249 |       if(manualRefresh&&!manualComplete){
 1250 |         // Each bounded slice commits before a successor is enqueued. A retry
 1251 |         // after process death reads the committed cursor, not an old input offset.
 1252 |         androidx.work.OneTimeWorkRequest continuation=
 1253 |             new androidx.work.OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
 1254 |                 .setConstraints(new androidx.work.Constraints.Builder()
 1255 |                     .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
 1256 |                 .setInitialDelay(10,java.util.concurrent.TimeUnit.SECONDS)
 1257 |                 .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
 1258 |                     .putString("manual_refresh_id",manualId).build()).build();
 1259 |         androidx.work.WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
 1260 |             "mytv_manual_source_update",androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,continuation)
 1261 |             .getResult().get();
 1262 |       }
 1263 |       // Routine failures rotate to other directories and wait for the next idle
 1264 |       // eligibility check; no aggressive WorkManager retry loop on dead feeds.
 1265 |       if(!initialDiscoveryChain)return Result.success();
 1266 |       if(initialDiscoveryComplete){INITIAL_PROBES.clear();discardStagingReuse();}
 1267 |       return (discoveryUnavailable&&!initialDiscoveryComplete
 1268 |               &&!(initialDiscoveryChain&&candidates.size()>0))
 1269 |           ||(initialDiscoveryChain&&!initialDiscoveryComplete&&candidates.isEmpty())
 1270 |           ?Result.retry():Result.success();
 1271 |     }catch(SourceProbeTimePolicy.PublicationPausedException paused){
 1272 |       saveStatus(prefs,"频道播放优先，已撤销尚未完成的目录事务，原频道继续可用",0);
 1273 |       return deferredViewingResult(manual,initialDiscoveryChain);
 1274 |     }catch(Exception e){saveStatus(prefs,"更新异常："+e.getMessage()+"（原频道源保留）",0);return Result.retry();}
 1275 |   }
 1276 |   private static void discardStagingReuse(){
 1277 |     committedStaging=null;committedStagingRevision="";committedStagingAt=0L;
 1278 |   }
 1279 |   private JSONArray takeCommittedStaging() throws JSONException {
 1280 |     JSONArray candidate=committedStaging;
 1281 |     String revision=committedStagingRevision;
 1282 |     long savedAt=committedStagingAt;
 1283 |     discardStagingReuse();
 1284 |     SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
 1285 |     if(candidate!=null&&FullDiscoveryProgressPolicy.canRetainStaging(candidate.length(),slowPlayback)
 1286 |         &&FullDiscoveryProgressPolicy.canReuseStaging(candidate.length(),revision,
 1287 |         roomString(com.wodedianshi.tv.db.RoomCatalogueBuildStore.STAGING_REVISION_KEY,""),
 1288 |         savedAt,android.os.SystemClock.elapsedRealtime())){
 1289 |       return candidate;
 1290 |     }
 1291 |     return com.wodedianshi.tv.db.RoomCatalogueBuildStore.read(getApplicationContext(),pauseRequested);
 1292 |   }
 1293 |   private com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot readPublishedForInitialCommit(){
 1294 |     SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
 1295 |     com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot=
 1296 |         com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),null,pauseRequested);
 1297 |     if(snapshot==null)throw new IllegalStateException("Published catalogue not ready for merge");
 1298 |     return snapshot;
 1299 |   }
 1300 |   // A scheduler failure after a successful acquisition is not a feed failure.
 1301 |   private Result continueAfterAcquisition(){
 1302 |     try{enqueueInitialContinuation();return Result.success();}
 1303 |     catch(Exception failure){
 1304 |       android.util.Log.w("MyTVSourceDiscovery","Prepared directory saved; continuation needs retry",failure);
 1305 |       return Result.retry();
 1306 |     }
 1307 |   }
 1308 |   private void enqueueInitialContinuation() throws Exception {
 1309 |     enqueueInitialContinuation(slowPlayback?20L:0L);
 1310 |   }
 1311 |   private void enqueueInitialContinuation(long delaySeconds) throws Exception {
 1312 |     if(isStopped()||Thread.currentThread().isInterrupted())throw new InterruptedException("Initial source work cancelled");
 1313 |     androidx.work.OneTimeWorkRequest continuation=
 1314 |         new androidx.work.OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
 1315 |             .setInitialDelay(delaySeconds,java.util.concurrent.TimeUnit.SECONDS)
 1316 |             .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,30L,java.util.concurrent.TimeUnit.SECONDS)
 1317 |             .setConstraints(new androidx.work.Constraints.Builder()
 1318 |                 .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
 1319 |             .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
 1320 |                 .putBoolean("initial_discovery_chain",true).build())
 1321 |             .build();
 1322 |     androidx.work.WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
 1323 |         "mytv_startup_source_refresh",androidx.work.ExistingWorkPolicy.APPEND,continuation)
 1324 |         .getResult().get();
 1325 |   }
 1326 |   private boolean areAllDiscoveryFeedsComplete(SharedPreferences prefs,
 1327 |           List<FeedBatch> batches,int feedCount){
 1328 |     for(int i=0;i<feedCount;i++){
 1329 |       boolean done=roomBoolean(initialFeedDoneKey(i),false);
 1330 |       if(!done){
 1331 |         for(FeedBatch batch:batches){
 1332 |           if(batch.index==i&&batch.success
 1333 |               &&SourceUpdateSchedulePolicy.manualFeedFinished(batch.cursor,batch.parsed,batch.indexedEntries,MAX_INITIAL_DISCOVERY_PER_FEED)){
 1334 |             done=true;break;
 1335 |           }
 1336 |         }
 1337 |       }
 1338 |       if(!done)return false;
 1339 |     }
 1340 |     return true;
 1341 |   }
 1342 |   private String discoveryFeedsFingerprint(String[] feeds){
 1343 |     StringBuilder value=new StringBuilder();
 1344 |     // A prior pending full build may contain dead routes from the old
 1345 |     // "stage even if probe failed" behavior. Restart only that isolated build
 1346 |     // after the acceptance policy changes; never discard published Room rows.
 1347 |     appendFingerprintPart(value,SourceDiscoveryAcceptancePolicy.BUILD_POLICY_VERSION);
 1348 |     if(feeds!=null)for(String feed:feeds)appendFingerprintPart(value,feed);
 1349 |     return CatalogueFingerprint.sha256(value.toString());
 1350 |   }
 1351 |   private String discoveryFingerprint(List<Entry> entries){
 1352 |     StringBuilder value=new StringBuilder(entries.size()*96);
 1353 |     for(Entry entry:entries){
 1354 |       appendFingerprintPart(value,SourceSelectionPolicy.endpointKey(entry.url));
 1355 |       appendFingerprintPart(value,entry.name);
 1356 |       appendFingerprintPart(value,entry.group);
 1357 |       appendFingerprintPart(value,entry.tvgId);
 1358 |       appendFingerprintPart(value,entry.tvgName);
 1359 |       appendFingerprintPart(value,entry.scope);
 1360 |     }
 1361 |     return CatalogueFingerprint.sha256(value.toString())+":"+entries.size();
 1362 |   }
 1363 |   private void appendFingerprintPart(StringBuilder target,String raw){
 1364 |     String value=raw==null?"":raw;
 1365 |     target.append(value.length()).append(':').append(value);
 1366 |   }
 1367 |   private JSONArray roomSnapshotToArray(com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot)
 1368 |       throws JSONException {
 1369 |     JSONArray result=new JSONArray();
 1370 |     for(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row:snapshot.channels){
 1371 |       SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
 1372 |       JSONObject channel=new JSONObject();
 1373 |       channel.put("name",row.name);channel.put("url",row.url);channel.put("group",row.group);
 1374 |       channel.put("tvgId",row.tvgId);channel.put("tvgName",row.tvgName);
 1375 |       channel.put("activeSource",row.activeSource);
 1376 |       JSONArray sources=new JSONArray();
 1377 |       for(String source:row.sources)sources.put(source);
 1378 |       channel.put("sources",sources);
 1379 |       JSONArray channelScopes=new JSONArray();
 1380 |       for(String scope:row.sourceScopes)channelScopes.put(scope);
 1381 |       channel.put("sourceScopes",channelScopes);
 1382 |       JSONArray ownership=new JSONArray();
 1383 |       for(java.util.Map.Entry<String,java.util.HashSet<String>> entry:row.backupSourceScopes.entrySet()){
 1384 |         JSONObject item=new JSONObject();item.put("url",entry.getKey());
 1385 |         JSONArray scopes=new JSONArray();
 1386 |         for(String scope:entry.getValue())scopes.put(scope);
 1387 |         item.put("scopes",scopes);ownership.put(item);
 1388 |       }
 1389 |       channel.put("backupSourceScopes",ownership);
 1390 |       result.put(channel);
 1391 |     }
 1392 |     return result;
 1393 |   }
 1394 | 
 1395 |   private boolean shouldYieldToViewing(SharedPreferences prefs, boolean manual,
 1396 |       boolean initialDiscoveryChain, boolean hasPublishedChannels){
 1397 |     return (slowPlayback?!SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime()):SourceUpdateActivityPolicy.isPlaybackActive())
 1398 |         || SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
 1399 |             isActivityActive(prefs),manual,initialDiscoveryChain,hasPublishedChannels);
 1400 |   }
 1401 | 
 1402 |   private Result deferInitialDirectory(String reason){
 1403 |     com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_initial_progress_phase",reason);
 1404 |     return continueAfterViewingDelay(true);
 1405 |   }
 1406 |   private Result continueAfterViewingDelay(boolean waitingForIdle){
 1407 |     // Never append work after cancellation. If the enqueue itself fails, retain
 1408 |     // the current durable WorkManager request via its normal retry mechanism.
 1409 |     if(isStopped()||Thread.currentThread().isInterrupted())return Result.retry();
 1410 |     try{
 1411 |       enqueueInitialContinuation(SourceUpdateSchedulePolicy.initialViewingDelaySeconds(waitingForIdle));
 1412 |       return Result.success();
 1413 |     }catch(Exception failure){
 1414 |       android.util.Log.w("MyTVSourceDiscovery","Unable to schedule deferred initial slice",failure);
 1415 |       return Result.retry();
 1416 |     }
 1417 |   }
 1418 |   private Result deferredViewingResult(boolean manual,boolean initialDiscoveryChain){
 1419 |     if(initialDiscoveryChain){
 1420 |       // The existing counters and cursors stay unchanged while viewing.
 1421 |       com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),
 1422 |           "source_initial_progress_phase","播放或浏览优先：抓源已暂停，进度已保存");
 1423 |       return continueAfterViewingDelay(false);
 1424 |     }
 1425 |     // Do not repeatedly wake the periodic job every few minutes while a user
 1426 |     // watches television; the next daily check retains the overdue weekly intent.
 1427 |     return SourceUpdateSchedulePolicy.shouldRetryWhenBusy(manual,initialDiscoveryChain)
 1428 |         ?Result.retry():Result.success();
 1429 |   }
 1430 | 
 1431 |   private boolean isActivityActive(SharedPreferences prefs){
 1432 |     return SourceUpdateActivityPolicy.isActivityActive();
 1433 |   }
 1434 |   private static class Entry{
 1435 |     String name,url,group,scope,tvgId,tvgName;boolean savedSource;
 1436 |     // Unscoped saved URLs have unknown provenance; only discovered feeds carry an explicit scope.
 1437 |     Entry(String n,String u,String g){this(n,u,g,false,"","","");}
 1438 |     Entry(String n,String u,String g,boolean saved){this(n,u,g,saved,"","","");}
 1439 |     Entry(String n,String u,String g,boolean saved,String sourceScope){
 1440 |       this(n,u,g,saved,sourceScope,"","");
 1441 |     }
 1442 |     Entry(String n,String u,String g,boolean saved,String sourceScope,String id,String guideName){
 1443 |       name=n;url=u;group=g;savedSource=saved;scope=sourceScope;
 1444 |       tvgId=id==null?"":id;tvgName=guideName==null?"":guideName;
 1445 |     }
 1446 |   }
 1447 |   private static class FeedBatch{
 1448 |     final int index,cursor,parsed,indexedEntries;
 1449 |     final boolean success,unavailable;
 1450 |     final String version;
 1451 |     FeedBatch(int i,int c,int p,int total,boolean ok,boolean unavailable,String version){
 1452 |       index=i;cursor=c;parsed=p;indexedEntries=total;
 1453 |       success=ok;this.unavailable=unavailable;this.version=version;
 1454 |     }
 1455 |   }
 1456 |   /**
 1457 |    * Retain existing published channels, backup URLs, selected source and country/
 1458 |    * city membership at the first full-rebuild publication boundary. Staging
 1459 |    * remains discovery-only until this point, so interrupted builds cannot change
 1460 |    * live Room rows. The writer checks its original Room catalogue version.
 1461 |    */
 1462 |   private JSONArray preservePreviouslyPublishedChannels(JSONArray discovered,
 1463 |       com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot prior)throws JSONException {
 1464 |     if(prior==null||prior.channels==null||prior.channels.isEmpty())return discovered;
 1465 |     JSONArray original=roomSnapshotToArray(prior);
 1466 |     LinkedHashMap<String,ArrayDeque<JSONObject>> originalsByIdentity=new LinkedHashMap<>();
 1467 |     ArrayList<JSONObject> originalInOrder=new ArrayList<>();
 1468 |     for(int i=0;i<original.length();i++){
 1469 |       JSONObject old=original.getJSONObject(i);
 1470 |       String key=identity(old.optString("name"),old.optString("group"),
 1471 |           old.optString("url"),old.optString("tvgId"),old.optString("tvgName"));
 1472 |       originalsByIdentity.computeIfAbsent(key,k->new ArrayDeque<>()).add(old);
 1473 |       originalInOrder.add(old);
 1474 |     }
 1475 |     Set<JSONObject> matched=Collections.newSetFromMap(new IdentityHashMap<JSONObject,Boolean>());
 1476 |     JSONArray publish=new JSONArray();
 1477 |     for(int i=0;i<discovered.length();i++){
 1478 |       JSONObject fresh=discovered.getJSONObject(i);
 1479 |       String key=identity(fresh.optString("name"),fresh.optString("group"),
 1480 |           fresh.optString("url"),fresh.optString("tvgId"),fresh.optString("tvgName"));
 1481 |       ArrayDeque<JSONObject> matches=originalsByIdentity.get(key);
 1482 |       if(matches==null||matches.isEmpty()){
 1483 |         publish.put(fresh);
 1484 |         continue;
 1485 |       }
 1486 |       JSONObject old=matches.removeFirst();
 1487 |       matched.add(old);
 1488 |       publish.put(mergeNewRoutesIntoSavedChannel(old,fresh));
 1489 |     }
 1490 |     // Keep even a station missing entirely from every new public directory.
 1491 |     // Its old sources may still work despite a failed discovery feed.
 1492 |     for(JSONObject old:originalInOrder)if(!matched.contains(old))publish.put(old);
 1493 |     android.util.Log.i("MyTVSourceDiscovery",
 1494 |         "first_full_publish_discovered="+discovered.length()+
 1495 |         " preserved_old="+original.length()+" final="+publish.length());
 1496 |     return publish;
 1497 |   }
 1498 | 
 1499 |   private ArrayList<String> channelSourceUrls(JSONObject row){
 1500 |     ArrayList<String> urls=new ArrayList<>();
 1501 |     JSONArray sources=row.optJSONArray("sources");
 1502 |     if(sources!=null)for(int i=0;i<sources.length();i++){
 1503 |       String url=sources.optString(i,"");
 1504 |       if(!url.isEmpty())urls.add(url);
 1505 |     }
 1506 |     if(urls.isEmpty()&&!row.optString("url","").isEmpty())
 1507 |       urls.add(row.optString("url",""));
 1508 |     return urls;
 1509 |   }
 1510 | 
 1511 |   private JSONObject mergeNewRoutesIntoSavedChannel(JSONObject old,JSONObject fresh)
 1512 |       throws JSONException {
 1513 |     ArrayList<String> oldUrls=channelSourceUrls(old);
 1514 |     ArrayList<String> newUrls=channelSourceUrls(fresh);
 1515 |     FullRebuildRouteRetentionPolicy.Result retained=FullRebuildRouteRetentionPolicy.merge(
 1516 |         oldUrls,old.optInt("activeSource",0),old.optString("url",""),
 1517 |         newUrls,MAX_SOURCES_PER_CHANNEL);
 1518 |     JSONArray kept=new JSONArray();
 1519 |     HashMap<String,String> keptByEndpoint=new HashMap<>();
 1520 |     for(String url:retained.urls){
 1521 |       kept.put(url);
 1522 |       keptByEndpoint.put(SourceSelectionPolicy.endpointKey(url),url);
 1523 |     }
 1524 |     // Some legacy rows have channel-level geo tags but no per-source provenance.
 1525 |     // Attach them to their old URLs before recalculating the ownership union.
 1526 |     JSONArray oldOwnership=old.optJSONArray("backupSourceScopes");
 1527 |     if(oldOwnership==null||oldOwnership.length()==0){
 1528 |       JSONArray oldScopes=old.optJSONArray("sourceScopes");
 1529 |       if(oldScopes!=null)for(String url:oldUrls){
 1530 |         String keptUrl=keptByEndpoint.get(SourceSelectionPolicy.endpointKey(url));
 1531 |         if(keptUrl==null)continue;
 1532 |         for(int j=0;j<oldScopes.length();j++)
 1533 |           addBackupSourceScope(old,keptUrl,oldScopes.optString(j,""));
 1534 |       }
 1535 |     }
 1536 |     JSONArray freshOwnership=fresh.optJSONArray("backupSourceScopes");
 1537 |     if(freshOwnership!=null&&freshOwnership.length()>0){
 1538 |       for(int i=0;i<freshOwnership.length();i++){
 1539 |         JSONObject ownership=freshOwnership.optJSONObject(i);
 1540 |         if(ownership==null)continue;
 1541 |         String retainedUrl=keptByEndpoint.get(SourceSelectionPolicy.endpointKey(
 1542 |             ownership.optString("url","")));
 1543 |         if(retainedUrl==null)continue;
 1544 |         JSONArray scopes=ownership.optJSONArray("scopes");
 1545 |         if(scopes!=null)for(int j=0;j<scopes.length();j++)
 1546 |           addBackupSourceScope(old,retainedUrl,scopes.optString(j,""));
 1547 |       }
 1548 |     }else{
 1549 |       // Older sources may carry only channel-level tags.
 1550 |       JSONArray scopes=fresh.optJSONArray("sourceScopes");
 1551 |       if(scopes!=null)for(String newUrl:newUrls){
 1552 |         String retainedUrl=keptByEndpoint.get(SourceSelectionPolicy.endpointKey(newUrl));
 1553 |         if(retainedUrl==null)continue;
 1554 |         for(int j=0;j<scopes.length();j++)
 1555 |           addBackupSourceScope(old,retainedUrl,scopes.optString(j,""));
 1556 |       }
 1557 |     }
 1558 |     // Keep the exact saved name, TVG, group and stable primary identity.
 1559 |     // Enriching them here could change the Room channel ID and lose favorites.
 1560 |     old.put("sources",kept);
 1561 |     old.put("activeSource",retained.activeIndex);
 1562 |     retainBackupSourceScopes(old,kept);
 1563 |     return old;
 1564 |   }
 1565 | 
 1566 |   private String scopeForFeed(String feed){
 1567 |     return SourceFeedScopePolicy.scopeForFeed(feed);
 1568 |   }
 1569 |   private String feedCursorKey(int index){return "source_feed_cursor_"+index;}
 1570 |   private String routineFeedCursorKey(int index){return "source_routine_feed_cursor_"+index;}
 1571 |   private String manualFeedCursorKey(int index){return "source_manual_feed_cursor_"+index;}
 1572 |   private String initialAcquiredVersionKey(int index){return "source_initial_acquired_version_"+index;}
 1573 |   private void commitInitialAcquisition(int index,File cached) throws IOException {
 1574 |     String version=PlaylistFeedCache.contentVersion(cached);
 1575 |     if(PlaylistFeedIndex.indexedEntryCount(cached)<0)throw new IOException("Prepared directory index unavailable");
 1576 |     SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
 1577 |     new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
 1578 |         .putString(initialAcquiredVersionKey(index),version)
 1579 |         .putInt("source_initial_next_feed_index",index)
 1580 |         .putString("source_initial_progress_phase","目录已准备，下一批继续线路初筛")
 1581 |         .apply();
 1582 |   }
 1583 |   private String initialFeedDoneKey(int index){return "source_initial_feed_done_"+index;}
 1584 |   private String initialFeedProgressKey(int index){return "source_initial_feed_progress_bp_"+index;}
 1585 |   private String initialFeedUnavailableKey(int index){return "source_initial_feed_unavailable_"+index;}
 1586 |   private String initialFeedFailureCountKey(int index){return "source_initial_feed_failure_count_"+index;}
 1587 |   private String initialFeedFailureReasonKey(int index){return "source_initial_feed_failure_reason_"+index;}
 1588 |   private String initialFeedDisplayLabelKey(int index){return "source_initial_feed_label_"+index;}
 1589 | 
 1590 |   private static String safeFeedLabel(String url){
 1591 |     try{
 1592 |       java.net.URL parsed=new java.net.URL(url);
 1593 |       String path=parsed.getPath(),name=path.substring(path.lastIndexOf('/')+1);
 1594 |       return parsed.getHost()+"/"+(name.isEmpty()?"目录":name);
 1595 |     }catch(Exception ignored){return "公开来源目录";}
 1596 |   }
 1597 |   private String canonicalDisplayName(Entry entry){
 1598 |     String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(
 1599 |         entry.name,entry.tvgName,entry.tvgId,entry.group);
 1600 |     if(!broadcaster.isEmpty())return broadcaster;
 1601 |     String canonical=canonicalName(entry.name);
 1602 |     return canonical.startsWith("CCTV-")||canonical.endsWith("卫视")?canonical:entry.name;
 1603 |   }
 1604 |   private void addBackupSourceScope(JSONObject channel,String url,String scope)throws JSONException{
 1605 |     if(channel==null||url==null||url.isEmpty())return;
 1606 |     // Use the same strict scope allowlist as the Room reader. Otherwise a
 1607 |     // background refresh can silently erase country/state/city ownership.
 1608 |     if(!ChannelCatalogueScopePolicy.isRetainableScope(scope))return;
 1609 |     JSONArray channelScopes=channel.optJSONArray("sourceScopes");
 1610 |     if(channelScopes==null){channelScopes=new JSONArray();channel.put("sourceScopes",channelScopes);}
 1611 |     boolean channelHasScope=false;
 1612 |     for(int i=0;i<channelScopes.length();i++)if(scope.equals(channelScopes.optString(i))){channelHasScope=true;break;}
 1613 |     if(!channelHasScope)channelScopes.put(scope);
 1614 |     JSONArray ownership=channel.optJSONArray("backupSourceScopes");
 1615 |     if(ownership==null){ownership=new JSONArray();channel.put("backupSourceScopes",ownership);}
 1616 |     for(int i=0;i<ownership.length();i++){
 1617 |       JSONObject item=ownership.optJSONObject(i);
 1618 |       if(item==null||!url.equals(item.optString("url","")))continue;
 1619 |       JSONArray scopes=item.optJSONArray("scopes");
 1620 |       if(scopes==null){scopes=new JSONArray();item.put("scopes",scopes);}
 1621 |       for(int j=0;j<scopes.length();j++)if(scope.equals(scopes.optString(j)))return;
 1622 |       scopes.put(scope);return;
 1623 |     }
 1624 |     JSONObject item=new JSONObject();JSONArray scopes=new JSONArray();
 1625 |     scopes.put(scope);item.put("url",url);item.put("scopes",scopes);ownership.put(item);
 1626 |   }
 1627 |   private void retainBackupSourceScopes(JSONObject channel,JSONArray retainedSources)throws JSONException{
 1628 |     JSONArray previous=channel.optJSONArray("backupSourceScopes");
 1629 |     if(previous==null)return;
 1630 |     HashSet<String> retainedUrls=new HashSet<>();
 1631 |     for(int i=0;i<retainedSources.length();i++)retainedUrls.add(retainedSources.optString(i,""));
 1632 |     JSONArray filtered=new JSONArray();
 1633 |     HashMap<String,Set<String>> ownershipByUrl=new HashMap<>();
 1634 |     for(int i=0;i<previous.length();i++){
 1635 |       JSONObject item=previous.optJSONObject(i);
 1636 |       if(item==null)continue;
 1637 |       String url=item.optString("url","");
 1638 |       if(!retainedUrls.contains(url))continue;
 1639 |       filtered.put(item);
 1640 |       JSONArray scopes=item.optJSONArray("scopes");
 1641 |       HashSet<String> owned=new HashSet<>();
 1642 |       if(scopes!=null)for(int j=0;j<scopes.length();j++)owned.add(scopes.optString(j,""));
 1643 |       ownershipByUrl.put(url,owned);
 1644 |     }
 1645 |     channel.put("backupSourceScopes",filtered);
 1646 |     // Channel-level tags are a derived union of the URLs that survived the
 1647 |     // eight-source retention cap. Do not leave stale China/global membership.
 1648 |     java.util.TreeSet<String> sortedScopes=new java.util.TreeSet<>(
 1649 |         ChannelCatalogueScopePolicy.retainedScopes(ownershipByUrl,retainedUrls));
 1650 |     JSONArray channelScopes=new JSONArray();
 1651 |     for(String scope:sortedScopes)channelScopes.put(scope);
 1652 |     channel.put("sourceScopes",channelScopes);
 1653 |   }
 1654 |   private int sourceQualityScore(String address){
 1655 |     return SourceQualityScorer.labelScore(address);
 1656 |   }
 1657 |   private static class ProbeResult {
 1658 |     boolean valid;
 1659 |     int score;
 1660 |     int width,height;
 1661 |     long latencyMs;
 1662 |     long bitrate;
 1663 |   }
 1664 |   // Keep the persisted preflight-score index bounded. Discovery feeds can rotate
 1665 |   // thousands of URLs over time; an unbounded JSON object would slow every update
 1666 |   // and gradually consume device storage. The current scan rewrites recent entries.
 1667 |   private static final int MAX_PERSISTED_PROBE_SCORES = 8000;
 1668 |   private void trimProbeScores(JSONObject scores,List<Entry> savedPool,List<Entry> recentCandidates){
 1669 |     if(scores==null||scores.length()<=MAX_PERSISTED_PROBE_SCORES)return;
 1670 |     // JSONObject key iteration order is not an LRU contract. Preserve scores for
 1671 |     // currently saved channel URLs first, then this run's recent candidates, before
 1672 |     // filling remaining capacity with older entries.
 1673 |     LinkedHashSet<String> keep=new LinkedHashSet<>();
 1674 |     if(savedPool!=null)for(Entry entry:savedPool){
 1675 |       SourceProbeTimePolicy.checkPublicationPaused(pauseRequested);
 1676 |       if(keep.size()>=MAX_PERSISTED_PROBE_SCORES)break;
 1677 |       String key=scoreKey(entry.url);if(scores.has(key))keep.add(key);
 1678 |     }
 1679 |     if(recentCandidates!=null)for(int i=recentCandidates.size()-1;i>=0&&keep.size()<MAX_PERSISTED_PROBE_SCORES;i--){
 1680 |       String key=scoreKey(recentCandidates.get(i).url);if(scores.has(key))keep.add(key);
 1681 |     }
 1682 |     Iterator<String> keys=scores.keys();
 1683 |     while(keys.hasNext()&&keep.size()<MAX_PERSISTED_PROBE_SCORES)keep.add(keys.next());
 1684 |     JSONObject retained=new JSONObject();
 1685 |     for(String key:keep)try{retained.put(key,scores.get(key));}catch(JSONException ignored){}
 1686 |     Iterator<String> remove=scores.keys();ArrayList<String> oldKeys=new ArrayList<>();
 1687 |     while(remove.hasNext())oldKeys.add(remove.next());
 1688 |     for(String key:oldKeys)scores.remove(key);
 1689 |     Iterator<String> retainedKeys=retained.keys();
 1690 |     while(retainedKeys.hasNext()){
 1691 |       String key=retainedKeys.next();
 1692 |       try{scores.put(key,retained.get(key));}catch(JSONException ignored){}
 1693 |     }
 1694 |   }
 1695 |   private String scoreKey(String url){return StableKey.url(url);}
 1696 |   private String legacyScoreKey(String url){return StableKey.legacyUrl(url);}
 1697 |   private double combinedScore(String url,JSONObject probes,JSONObject qoe){
 1698 |     String key=scoreKey(url),legacyKey=legacyScoreKey(url);
 1699 |     double probeScore=probes.has(key)?probes.optDouble(key,0.0):probes.optDouble(legacyKey,0.0);
 1700 |     // URL resolution labels contribute at most 12 points; media preflight and
 1701 |     // observed playback QoE remain dominant, so a fake "1080p" label cannot
 1702 |     // outrank a materially healthier stream by itself.
 1703 |     double score=probeScore+sourceQualityScore(url)*0.01;
 1704 |     // Keep a currently failed preflight below unprobed/valid sources even if its old QoE was excellent.
 1705 |     if(SourceQualityScorer.isFailedProbeScore(probeScore))return score;
 1706 |     JSONObject m=qoe.optJSONObject(key);
 1707 |     if(m==null)m=qoe.optJSONObject(legacyKey);
 1708 |     if(m!=null&&m.optInt("attempts",0)>=2){
 1709 |       int attempts=Math.max(1,m.optInt("attempts",0)),ready=m.optInt("ready",0),fail=m.optInt("failures",0),buffers=m.optInt("buffers",0);
 1710 |       int codecWarnings=m.optInt("audioCodecErrors",0)+m.optInt("audioSinkErrors",0)+m.optInt("videoCodecErrors",0);
 1711 |       // Use exactly the same attempt-normalized QoE policy as live playback ranking.
 1712 |       double qoeAdjustment=SourceQoeScoringPolicy.adjustment(
 1713 |           attempts,ready,m.optInt("firstFrames",0),m.optInt("stablePlays",0),
 1714 |           fail,buffers,m.optLong("avgReadyMs",0L),m.optLong("avgFirstFrameMs",0L),
 1715 |           codecWarnings);
 1716 |       long now=System.currentTimeMillis();
 1717 |       score+=qoeAdjustment*SourceQoeScoringPolicy.historyWeight(m.optLong("updated",0L),now);
 1718 |       score+=SourceQoeScoringPolicy.recentSuccessBonus(
 1719 |           m.optLong("lastFirstFrame",0L),m.optLong("lastStable",0L),now);
 1720 |     }
 1721 |     return score;
 1722 |   }
 1723 |   // Sniff the response body as well as the URL: some IPTV gateways expose
 1724 |   // HLS manifests through extensionless or tokenized endpoint URLs.
 1725 |   private ProbeResult probe(String address,long deadline){
 1726 |     ProbeResult result=new ProbeResult();
 1727 |     long started=android.os.SystemClock.elapsedRealtime();
 1728 |     try{
 1729 |       if(probeDeadlineReached(deadline))return result;
 1730 |       SampleResponse initialResponse=fetchSampleResponse(address,32768,deadline);
 1731 |       byte[] initialBytes=initialResponse==null?null:initialResponse.bytes;
 1732 |       if(initialBytes==null||initialBytes.length<7)return result;
 1733 |       String initialText=new String(initialBytes,"UTF-8").replace("\\uFEFF","");
 1734 |       String lower=address.toLowerCase(Locale.ROOT);
 1735 |       boolean playlistUrl=lower.contains(".m3u8")||lower.contains("m3u8?");
 1736 |       boolean hlsPlaylist=HlsVariantPolicy.isPlaylist(initialText);
 1737 |       if(playlistUrl||hlsPlaylist){
 1738 |         if(!hlsPlaylist)return result;
 1739 |         SampleResponse manifestResponse=initialResponse;
 1740 |         String manifest=initialText;
 1741 |         String manifestUrl=manifestResponse.finalUrl;
 1742 |         ArrayList<HlsVariantPolicy.Variant> variants=HlsVariantPolicy.parse(manifest,manifestUrl);
 1743 |         if(variants.isEmpty())variants.add(new HlsVariantPolicy.Variant(manifestUrl,0L,0,0));
 1744 |         String mediaUrl=manifestUrl;
 1745 |         long bestBandwidth=0L;
 1746 |         int bestWidth=0,bestHeight=0;
 1747 |         boolean selectedAes128=false;
 1748 |         HlsMediaPlaylistPolicy.Resources selectedResources=null;
 1749 |         byte[] selectedAesKey=null;
 1750 |         ArrayList<String> segments=null;
 1751 |         byte[] sample=null;
 1752 |         long segmentStart=0L;
 1753 |         for(HlsVariantPolicy.Variant candidate:variants){
 1754 |           if(probeDeadlineReached(deadline))return result;
 1755 |           String candidateManifestUrl=candidate.url;
 1756 |           String candidateManifest=manifest;
 1757 |           if(!candidate.url.equals(manifestUrl)){
 1758 |             SampleResponse variantResponse=fetchSampleResponse(candidate.url,65536,deadline);
 1759 |             byte[] variant=variantResponse==null?null:variantResponse.bytes;
 1760 |             if(variant==null||variant.length==0)continue;
 1761 |             candidateManifest=new String(variant,"UTF-8").replace("\\uFEFF","");
 1762 |             if(!HlsVariantPolicy.isPlaylist(candidateManifest))continue;
 1763 |             candidateManifestUrl=variantResponse.finalUrl;
 1764 |           }
 1765 |           HlsMediaPlaylistPolicy.Resources resources=
 1766 |               HlsMediaPlaylistPolicy.parse(candidateManifest,candidateManifestUrl);
 1767 |           ArrayList<String> candidateSegments=resources.mediaUrls;
 1768 |           if(candidateSegments.isEmpty()||resources.unsupportedInitializationByteRange||resources.unsupportedInitializationMap||resources.unsupportedEncryption)continue;
 1769 |           // Fetch identity AES-128 key only for in-memory preflight; never log or persist key bytes.
 1770 |           byte[] aesKey=null;
 1771 |           if(resources.aes128Encrypted){
 1772 |             aesKey=fetchSample(resources.encryptionKeyUrl,32,deadline);
 1773 |             if(aesKey==null||aesKey.length!=16)continue;
 1774 |           }
 1775 |           // fMP4/CMAF playlists require the EXT-X-MAP initialization resource.
 1776 |           // AES-128 maps require their explicit IV and must validate after decryption.
 1777 |           if(resources.initializationUrl!=null){
 1778 |             SampleResponse initializationResponse=fetchSampleResponse(resources.initializationUrl,32768,deadline);
 1779 |             byte[] initialization=initializationResponse==null?null:initializationResponse.bytes;
 1780 |             if(initialization==null)continue;
 1781 |             if(resources.aes128Encrypted){
 1782 |               byte[] mapIv=HlsAes128Preflight.parseExplicitIv(resources.encryptionIv);
 1783 |               if(mapIv==null||!HlsAes128Preflight.validatesInitializationSegment(
 1784 |                   initialization,aesKey,mapIv,HlsProbeValidationPolicy.isCompleteSample(initializationResponse.truncated)))continue;
 1785 |             }else if(!MediaPayloadValidator.hasIsoBmffInitializationSegment(initialization))continue;
 1786 |           }
 1787 |           String candidateSegmentUrl=candidateSegments.get(0);
 1788 |           long candidateSegmentStart=android.os.SystemClock.elapsedRealtime();
 1789 |           SampleResponse candidateSampleResponse=fetchSampleResponse(candidateSegmentUrl,32768,deadline);
 1790 |           byte[] candidateSample=candidateSampleResponse==null?null:candidateSampleResponse.bytes;
 1791 |           if(candidateSample==null)continue;
 1792 |           if(resources.aes128Encrypted){
 1793 |             byte[] segmentIv=resources.encryptionIv!=null
 1794 |                 ?HlsAes128Preflight.parseExplicitIv(resources.encryptionIv)
 1795 |                 :HlsAes128Preflight.ivForMediaSequence(resources.firstMediaSequenceNumber);
 1796 |             if(!HlsAes128Preflight.validatesMediaSegment(
 1797 |                 candidateSample,aesKey,segmentIv,HlsProbeValidationPolicy.isCompleteSample(candidateSampleResponse.truncated)))continue;
 1798 |           }else if(!looksLikeHlsSegment(candidateSample))continue;
 1799 |           mediaUrl=candidateManifestUrl;
 1800 |           bestBandwidth=candidate.bandwidth;
 1801 |           bestWidth=candidate.width;
 1802 |           bestHeight=candidate.height;
 1803 |           segments=candidateSegments;
 1804 |           selectedAes128=resources.aes128Encrypted;
 1805 |           selectedResources=resources;
 1806 |           selectedAesKey=aesKey;
 1807 |           sample=candidateSample;
 1808 |           segmentStart=candidateSegmentStart;
 1809 |           break;
 1810 |         }
 1811 |         if(segments==null||sample==null)return result;
 1812 |         long segmentMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-segmentStart);
 1813 |         boolean secondSegmentOk=true;
 1814 |         if(segments.size()>1){
 1815 |           String secondUrl=new URL(new URL(mediaUrl),segments.get(1)).toString();
 1816 |           SampleResponse secondResponse=fetchSampleResponse(secondUrl,8192,deadline);
 1817 |           byte[] second=secondResponse==null?null:secondResponse.bytes;
 1818 |           if(selectedAes128&&second!=null){
 1819 |             byte[] secondIv=selectedResources.encryptionIv!=null
 1820 |                 ?HlsAes128Preflight.parseExplicitIv(selectedResources.encryptionIv)
 1821 |                 :HlsAes128Preflight.ivForMediaSequence(selectedResources.firstMediaSequenceNumber+1L);
 1822 |             secondSegmentOk=HlsAes128Preflight.validatesMediaSegment(
 1823 |                 second,selectedAesKey,secondIv,HlsProbeValidationPolicy.isCompleteSample(secondResponse.truncated));
 1824 |           }else secondSegmentOk=second!=null&&looksLikeHlsSegment(second);
 1825 |         }
 1826 |         // A playlist exposing a second segment must prove that it is retrievable
 1827 |         // and structurally valid too. A valid first segment alone can be a stale
 1828 |         // edge/cache fragment and must not promote a dead live endpoint.
 1829 |         if(segments.size()>1&&!HlsProbeValidationPolicy.acceptsSecondSegment(secondSegmentOk))
 1830 |           return result;
 1831 |         result.valid=true;result.width=bestWidth;result.height=bestHeight;
 1832 |         result.bitrate=bestBandwidth;result.latencyMs=Math.max(1L,segmentStart-started);
 1833 |         int resolution=(bestWidth>=1920||bestHeight>=1080)?900:(bestWidth>=1280||bestHeight>=720)?700:(bestWidth>=854||bestHeight>=480)?500:300;
 1834 |         int bandwidth=(int)Math.min(500L,bestBandwidth/12000L);
 1835 |         int speed=(int)Math.min(300L,(sample.length*1000L/segmentMs)/2048L);
 1836 |         int latencyPenalty=(int)Math.min(250L,result.latencyMs/20L);
 1837 |         result.score=500+resolution+bandwidth+speed-latencyPenalty+300+(secondSegmentOk?120:(segments.size()>1?-180:0));
 1838 |         return result;
 1839 |       }
 1840 |       result.latencyMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-started);
 1841 |       URL url=new URL(address);
 1842 |       String ext=url.getPath().toLowerCase(Locale.ROOT);
 1843 |       if(!MediaPayloadValidator.looksLikeMediaPayload(initialBytes,ext))return result;
 1844 |       result.valid=true;
 1845 |       result.score=350+(int)Math.min(300L,(initialBytes.length*1000L/result.latencyMs)/2048L);
 1846 |       return result;
 1847 |     }catch(Exception e){return result;}
 1848 |   }
 1849 |   // HLS media may be MPEG-TS or fragmented MP4 (CMAF/fMP4).
 1850 |   // Do not reject valid fMP4 segments merely because they are shorter than 188 bytes.
 1851 |   private boolean looksLikeHlsSegment(byte[] b){
 1852 |     return MediaPayloadValidator.looksLikeHlsSegment(b);
 1853 |   }
 1854 |   private boolean hasIsoBmffBox(byte[] b){
 1855 |     return MediaPayloadValidator.hasIsoBmffBox(b);
 1856 |   }
 1857 |   private boolean looksLikeMediaPayload(byte[] b){
 1858 |     return MediaPayloadValidator.looksLikeMediaPayload(b);
 1859 |   }
 1860 |   private static class SampleResponse{
 1861 |     final byte[] bytes;
 1862 |     final String finalUrl;
 1863 |     // True only when the bounded read observed at least one byte beyond its cap.
 1864 |     // Reaching the cap alone does not prove that the HTTP resource ended there.
 1865 |     final boolean truncated;
 1866 |     SampleResponse(byte[] data,String url,boolean wasTruncated){bytes=data;finalUrl=url;truncated=wasTruncated;}
 1867 |   }
 1868 |   private boolean probeDeadlineReached(long deadline){
 1869 |     return pauseRequested.getAsBoolean()||Thread.currentThread().isInterrupted()
 1870 |         ||android.os.SystemClock.elapsedRealtime()>=deadline;
 1871 |   }
 1872 |   private byte[] fetchSample(String address,int maxBytes,long deadline)throws Exception{
 1873 |     SampleResponse response=fetchSampleResponse(address,maxBytes,deadline);
 1874 |     return response==null?null:response.bytes;
 1875 |   }
 1876 |   private SampleResponse fetchSampleResponse(String address,int maxBytes,long deadline)throws Exception{
 1877 |     HttpURLConnection c=null;
 1878 |     try{
 1879 |       if(probeDeadlineReached(deadline))return null;
 1880 |       c=(HttpURLConnection)new URL(address).openConnection();
 1881 |       // URLConnection options must be configured before the first operation that connects it.
 1882 |       // Reserve half the remaining budget for connection setup and the rest for response reads.
 1883 |       long now=android.os.SystemClock.elapsedRealtime();
 1884 |       long remaining=deadline-now;
 1885 |       if(remaining<2L)return null;
 1886 |       int connectTimeout=(int)Math.min(2500L,Math.max(1L,remaining/2L));
 1887 |       int readTimeout=SourceProbeTimePolicy.boundedTimeoutMillis(
 1888 |           deadline,now+connectTimeout,3000);
 1889 |       if(readTimeout<=0)return null;
 1890 |       c.setConnectTimeout(connectTimeout);
 1891 |       c.setReadTimeout(readTimeout);
 1892 |       c.setInstanceFollowRedirects(true);
 1893 |       c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36 MyTV/1.24");
 1894 |       // Read one sentinel byte beyond the retained sample to distinguish an
 1895 |       // exact-length resource from a longer segment truncated at the sample cap.
 1896 |       c.setRequestProperty("Range","bytes=0-"+maxBytes);
 1897 |       int code=c.getResponseCode();if(probeDeadlineReached(deadline)||code<200||code>=400)return null;
 1898 |       boolean rangeIncomplete=code==HttpURLConnection.HTTP_PARTIAL
 1899 |           &&HlsProbeValidationPolicy.isIncompleteContentRange(c.getHeaderField("Content-Range"),maxBytes);
 1900 |       String type=c.getContentType();
 1901 |       if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))return null;
 1902 |       String finalUrl=c.getURL().toString();
 1903 |       try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
 1904 |         byte[] buffer=new byte[4096];int n,total=0;
 1905 |         while(total<=maxBytes){
 1906 |           if(probeDeadlineReached(deadline))return null;
 1907 |           n=in.read(buffer);
 1908 |           if(n==-1)break;
 1909 |           int take=Math.min(n,maxBytes+1-total);out.write(buffer,0,take);total+=take;
 1910 |           if(total>maxBytes)break;
 1911 |         }
 1912 |         if(probeDeadlineReached(deadline))return null;
 1913 |         byte[] received=out.toByteArray();
 1914 |         boolean truncated=rangeIncomplete
 1915 |             ||HlsProbeValidationPolicy.isTruncatedSample(received.length,maxBytes);
 1916 |         byte[] bounded=truncated?Arrays.copyOf(received,maxBytes):received;
 1917 |         return new SampleResponse(bounded,finalUrl,truncated);
 1918 |       }
 1919 |     }finally{if(c!=null)c.disconnect();}
 1920 |   }
 1921 |   private String read(InputStream in,int max)throws Exception{
 1922 |     ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;
 1923 |     while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("频道目录过大");out.write(b,0,n);}return out.toString("UTF-8");
 1924 |   }
 1925 |   private String canonicalName(String name){
 1926 |     String v=name==null?"":name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_—–·•]","");
 1927 |     java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)-?(\\d{1,2}\\+?|NEWS|4K|8K)").matcher(v);
 1928 |     if(m.find())return "CCTV-"+m.group(1);
 1929 |     String[] cn={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
 1930 |     for(int i=1;i<cn.length;i++)if(v.matches(".*中央(?:电视台)?"+cn[i]+"套.*"))return "CCTV-"+i;
 1931 |     if(v.contains("中央电视台综合频道"))return "CCTV-1";
 1932 |     if(v.contains("中央电视台财经频道"))return "CCTV-2";
 1933 |     if(v.contains("中央电视台综艺频道"))return "CCTV-3";
 1934 |     if(v.contains("中央电视台中文国际频道"))return "CCTV-4";
 1935 |     if(v.contains("中央电视台体育频道"))return "CCTV-5";
 1936 |     if(v.contains("中央电视台电影频道"))return "CCTV-6";
 1937 |     v=v.replace("超高清","").replace("高清","").replace("超清","").replace("1080P","").replace("720P","").replace("HD","").replace("FHD","").replace("直播","");
 1938 |     if(v.endsWith("湖南台"))return "湖南卫视";
 1939 |     if(v.endsWith("浙江台"))return "浙江卫视";
 1940 |     if(v.endsWith("江苏台"))return "江苏卫视";
 1941 |     if(v.endsWith("东方台"))return "东方卫视";
 1942 |     if(v.endsWith("北京台"))return "北京卫视";
 1943 |     if(v.endsWith("广东台"))return "广东卫视";
 1944 |     return v;
 1945 |   }
 1946 |   private String identity(String name,String group,String url,String tvgId,String tvgName){
 1947 |     String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
 1948 |     String v=broadcaster.isEmpty()?canonicalName(name):broadcaster;
 1949 |     String base=v.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
 1950 |     String fallback=base;
 1951 |     try{
 1952 |       java.net.URI u=java.net.URI.create(url==null?"":url);
 1953 |       String host=u.getHost();
 1954 |       if(host!=null&&!host.isEmpty())fallback=base+"|host:"+host.toLowerCase(Locale.ROOT);
 1955 |     }catch(Exception ignored){fallback=base+"|url:"+(url==null?"":url.trim());}
 1956 |     String localIdentity=ChannelIdentityPolicy.localStationIdentity(name,tvgName);
 1957 |     return ChannelIdentityPolicy.identity(v,tvgId,fallback,localIdentity);
 1958 |   }
 1959 |   private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
 1960 |   private String roomString(String key,String fallback){
 1961 |     return com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),key,fallback);
 1962 |   }
 1963 |   private int roomInt(String key,int fallback){
 1964 |     try{return Integer.parseInt(roomString(key,Integer.toString(fallback)));}
 1965 |     catch(Exception ignored){return fallback;}
 1966 |   }
 1967 |   private boolean roomBoolean(String key,boolean fallback){
 1968 |     String value=roomString(key,Boolean.toString(fallback));
 1969 |     return Boolean.parseBoolean(value);
 1970 |   }
 1971 |   private void saveStatus(SharedPreferences p,String result,int added){
 1972 |     com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_update_time",now());
 1973 |     com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_update_result",result);
 1974 |   }
 1975 | }
 1976 | 
```


## app/src/main/java/com/wodedianshi/tv/SourceUpdateSchedulePolicy.java — 原始行 1–109

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /** Controls the one-time independent full-catalogue rebuild lifecycle. */
    4 | public final class SourceUpdateSchedulePolicy {
    5 |     private SourceUpdateSchedulePolicy() {}
    6 | 
    7 |     /**
    8 |      * Schedule the independent full rebuild only when automatic updates are
    9 |      * enabled and no full rebuild has been published. Older incremental-update
   10 |      * timestamps must not suppress the first complete rebuild.
   11 |      */
   12 |     public static boolean shouldRunInitialDiscovery(boolean automaticUpdatesEnabled,
   13 |                                                     long fullRebuildCompletedAtMillis) {
   14 |         return automaticUpdatesEnabled && fullRebuildCompletedAtMillis <= 0L;
   15 |     }
   16 | 
   17 |     // A completed Room snapshot is the default directory. Periodic discovery is
   18 |     // deliberately infrequent; WorkManager's timing is inexact by design.
   19 |     public static final long ROUTINE_REFRESH_DAYS = 7L;
   20 |     public static final long ROUTINE_ELIGIBILITY_CHECK_DAYS = 1L;
   21 |     private static final long ROUTINE_REFRESH_MS = ROUTINE_REFRESH_DAYS * 24L * 60L * 60L * 1000L;
   22 | 
   23 |     /** A skipped busy check must not advance the weekly maintenance deadline. */
   24 |     public static boolean isRoutineMaintenanceDue(long fullRebuildAt, long lastRoutineAt, long now) {
   25 |         if (fullRebuildAt <= 0L || now <= 0L) return false;
   26 |         long last = Math.max(fullRebuildAt, lastRoutineAt);
   27 |         return now < last || now - last >= ROUTINE_REFRESH_MS;
   28 |     }
   29 | 
   30 |     // Three 4-entry feed pages fit the 12 discovery slots even when saved
   31 |     // routes consume half of the 24-probe budget. No wrap inside one slice.
   32 |     public static final int ROUTINE_FEEDS_PER_SLICE = 3;
   33 | 
   34 |     public static int routineSliceEnd(int start, int feedCount) {
   35 |         if (feedCount <= 0) return 0;
   36 |         int safe = InitialFeedRotationPolicy.normalizeStart(start, feedCount);
   37 |         return Math.min(feedCount, safe + ROUTINE_FEEDS_PER_SLICE);
   38 |     }
   39 | 
   40 |     public static int routineNextFeed(int start, int feedCount, boolean batchComplete) {
   41 |         if (feedCount <= 0) return 0;
   42 |         int safe = InitialFeedRotationPolicy.normalizeStart(start, feedCount);
   43 |         return batchComplete ? routineSliceEnd(safe, feedCount) % feedCount : safe;
   44 |     }
   45 | 
   46 |     /** Reuse only a completed probe from this process, never a historical score.
   47 |      * Failures expire quickly so temporary outages are not held for long. */
   48 |     public static boolean canReuseInitialProbe(boolean valid, long measuredAt, long now) {
   49 |         return measuredAt >= 0L && now >= measuredAt
   50 |                 && now - measuredAt <= (valid ? 300_000L : 15_000L);
   51 |     }
   52 | 
   53 |     /** Manual refresh advances only at EOF, never just because a page was processed. */
   54 |     public static boolean manualFeedFinished(int cursor, int parsed, int indexedEntries,
   55 |             int pageSize) {
   56 |         if (cursor < 0 || parsed < 0 || pageSize <= 0) return false;
   57 |         return indexedEntries >= 0
   58 |                 ? (long)cursor + parsed >= indexedEntries
   59 |                 : parsed < pageSize;
   60 |     }
   61 | 
   62 |     /** Only a completed first rebuild enables low-priority routine maintenance. */
   63 |     public static boolean shouldScheduleRoutineMaintenance(boolean automaticEnabled,
   64 |             long fullRebuildCompletedAtMillis) {
   65 |         return automaticEnabled && fullRebuildCompletedAtMillis > 0L;
   66 |     }
   67 | 
   68 |     /**
   69 |      * A routine busy check is skipped until the next daily eligibility check. Never build a 10-minute retry loop while the TV is busy.
   70 |      * First-run and explicitly requested work should retain their queued intent.
   71 |      */
   72 |     public static boolean shouldRetryWhenBusy(boolean manual, boolean initialDiscoveryChain) {
   73 |         return manual || initialDiscoveryChain;
   74 |     }
   75 | 
   76 |     /** Normal viewing deferral is not a failed network attempt. */
   77 |     public static long initialViewingDelaySeconds(boolean stableButWaitingForIdle) {
   78 |         return stableButWaitingForIdle ? 60L : 30L;
   79 |     }
   80 | 
   81 |     /** Automatic catalogue work must not compete with foreground TV playback or input. */
   82 |     public static boolean shouldDeferForegroundAutoUpdate(boolean appForeground, boolean manual) {
   83 |         return appForeground && !manual;
   84 |     }
   85 | 
   86 |     /**
   87 |      * First-run setup must advance even when seed or previously saved channels exist.
   88 |      * Playback activity is checked separately by the worker before this gate.
   89 |      */
   90 |     public static boolean shouldDeferForegroundDiscovery(boolean appForeground,
   91 |             boolean manual, boolean initialDiscoveryChain, boolean hasPublishedChannels) {
   92 |         // Seed channels are not evidence of a completed initial rebuild.
   93 |         // Keep routine automatic maintenance idle-only, but allow initialization
   94 |         // or explicit manual work while browsing, regardless of catalogue size.
   95 |         return appForeground && !manual && !initialDiscoveryChain;
   96 |     }
   97 | 
   98 |     /** A first-run rebuild is complete only after every candidate and feed was covered. */
   99 |     public static boolean isInitialDiscoveryComplete(int candidateCount, int scannedCount,
  100 |                                                       int failedFeedCount) {
  101 |         return candidateCount > 0 && scannedCount >= candidateCount && failedFeedCount == 0;
  102 |     }
  103 | 
  104 |     /** At least one newly discovered source must pass the media preflight. */
  105 |     public static boolean hasValidatedDiscoverySources(int validatedSourceCount) {
  106 |         return validatedSourceCount > 0;
  107 |     }
  108 | }
  109 | 
```


## app/src/main/java/com/wodedianshi/tv/SourceUpdateActivityPolicy.java — 原始行 1–41

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /** In-process viewing gate. The manifest and default WorkManager use the same app process.
    4 |  * No persisted busy flag can survive process death, reboot, or a clock adjustment. */
    5 | public final class SourceUpdateActivityPolicy {
    6 |     private static volatile boolean activityActive;
    7 |     private static volatile boolean playbackActive;
    8 |     private static volatile long stableUntil;
    9 |     private static final java.util.concurrent.atomic.AtomicLong stableRevocations=new java.util.concurrent.atomic.AtomicLong();
   10 |     private SourceUpdateActivityPolicy() {}
   11 |     private static final java.util.concurrent.atomic.AtomicLong activityStarts = new java.util.concurrent.atomic.AtomicLong();
   12 |     private static final java.util.concurrent.atomic.AtomicLong playbackStarts = new java.util.concurrent.atomic.AtomicLong();
   13 |     public static synchronized void setActivityActive(boolean active) {
   14 |         if(!active)revokeStableLive();
   15 |         if (active && !activityActive) activityStarts.incrementAndGet();
   16 |         activityActive = active;
   17 |     }
   18 |     public static synchronized void setPlaybackActive(boolean active) {
   19 |         revokeStableLive();
   20 |         if (active && !playbackActive) playbackStarts.incrementAndGet();
   21 |         playbackActive = active;
   22 |     }
   23 |     /** A fresh, short-lived confirmation from the existing live watchdog only. */
   24 |     public static synchronized void reportStableLive(long now) {
   25 |         if (activityActive && playbackActive && now > 0L && now < Long.MAX_VALUE-15000L)
   26 |             stableUntil=now+15000L;
   27 |     }
   28 |     public static synchronized void revokeStableLive() {
   29 |         if(stableUntil!=0L){stableUntil=0L;stableRevocations.incrementAndGet();}
   30 |     }
   31 |     public static boolean permitsInitialPlaybackWork(long now) {
   32 |         long until=stableUntil;
   33 |         return activityActive&&playbackActive&&now>0L&&until>now&&until-now<=15000L;
   34 |     }
   35 |     public static long stableRevocationGeneration(){return stableRevocations.get();}
   36 |     public static long activityStartGeneration() { return activityStarts.get(); }
   37 |     public static long playbackStartGeneration() { return playbackStarts.get(); }
   38 |     public static boolean isActivityActive() { return activityActive; }
   39 |     public static boolean isPlaybackActive() { return playbackActive; }
   40 | }
   41 | 
```


## app/src/main/java/com/wodedianshi/tv/FullDiscoveryProgressPolicy.java — 原始行 1–154

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /**
    4 |  * Honest first-build progress: each configured feed is one unit of work,
    5 |  * with fractional progress from the indexed cursor of the active feed.
    6 |  * A percentage of work completed is not a prediction of remaining time,
    7 |  * and 100% is reserved for a successfully published complete Room snapshot.
    8 |  */
    9 | public final class FullDiscoveryProgressPolicy {
   10 |     private FullDiscoveryProgressPolicy() {}
   11 | 
   12 |     // Do not hold the first playable preview behind every mainland download.
   13 |     // At most two bootstrap acquisitions, then normal fair paged discovery.
   14 |     public static int bootstrapIndexLimit(int mainlandCount) {
   15 |         return Math.max(0,Math.min(2,mainlandCount));
   16 |     }
   17 |     /** Acquisition attempts are separate from the final verified-build percent. */
   18 |     public static boolean isMainlandIndexPhase(int attempted, int mainlandCount,
   19 |             int checked, long indexedEntries, boolean complete) {
   20 |         return !complete && checked <= 0 && indexedEntries <= 0
   21 |                 && Math.max(0,attempted) < bootstrapIndexLimit(mainlandCount);
   22 |     }
   23 | 
   24 |     /** Finishing discovery is independent of permission to replace live rows. */
   25 |     public static boolean awaitingIdleFinalization(boolean allFeedsInspected, boolean viewing) {
   26 |         return allFeedsInspected && viewing;
   27 |     }
   28 | 
   29 |     public static boolean canPublishInitialSnapshot(boolean allFeedsInspected,
   30 |             boolean hasValidatedSource, boolean viewing) {
   31 |         return allFeedsInspected && hasValidatedSource && !viewing;
   32 |     }
   33 | 
   34 |     // Bounded, opportunistic cache. Room is authoritative after every commit.
   35 |     public static boolean canRetainStaging(int rows,boolean viewing) {
   36 |         return rows>=0&&rows<=(viewing?512:1024);
   37 |     }
   38 |     public static boolean canReuseStaging(int rows,String revision,String durableRevision,long savedAt,long now) {
   39 |         return canRetainStaging(rows,false)&&revision!=null&&!revision.isEmpty()
   40 |                 &&revision.equals(durableRevision)&&savedAt>=0L&&now>=savedAt&&now-savedAt<=60000L;
   41 |     }
   42 | 
   43 |     /** A fresh empty staging set can be initialized during stable viewing.
   44 |      * Never clear a populated staging build while the viewer is watching. */
   45 |     public static boolean canResetInitialStaging(boolean stableViewing,int stagedRows) {
   46 |         return !stableViewing || stagedRows==0;
   47 |     }
   48 | 
   49 |     /** The optional idle bootstrap must not block the bounded viewing path. */
   50 |     public static boolean shouldRunIdleBootstrap(boolean stableViewing,int attempted,
   51 |             int mainlandCount,int checked,long indexedEntries,boolean complete) {
   52 |         return !stableViewing&&isMainlandIndexPhase(attempted,mainlandCount,checked,indexedEntries,complete);
   53 |     }
   54 | 
   55 |     public static final String PREVIEW_LEVEL_KEY = "source_initial_preview_level";
   56 |     // At most two extra publications: an early useful set, then mainland coverage.
   57 |     // A milestone is not a completed full build and does not change the probe policy.
   58 |     public static int nextPreviewLevel(int previous, int stagedChannels,
   59 |             int mainlandValidated, boolean mainlandInspected) {
   60 |         if (stagedChannels <= 0 || mainlandValidated <= 0) return previous;
   61 |         if (mainlandInspected) return Math.max(previous, 2);
   62 |         return stagedChannels >= 12 ? Math.max(previous, 1) : previous;
   63 |     }
   64 | 
   65 |     public static final String PUBLISHED_COVERAGE_KEY = "source_initial_published_coverage_v1";
   66 |     public static final String FRESH_COVERAGE_KEY = "source_initial_fresh_coverage_v1";
   67 |     private static final String[] PROVINCES = {"北京","天津","河北","山西","内蒙古","辽宁","吉林",
   68 |         "黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南",
   69 |         "广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
   70 | 
   71 |     /** Counts catalogue rows, not real-world playable channels or unique successful probes.
   72 |      * Retained old rows are intentionally included, with no claim of fresh validation. */
   73 |     public static final class Coverage {
   74 |         private int total, cctv, satellite, local, unresolvedLocal;
   75 |         private final java.util.Set<String> provinces = new java.util.HashSet<>();
   76 | 
   77 |         public void add(String name, String group, String tvgName, String tvgId) {
   78 |             total++;
   79 |             String metadata = safe(name) + " " + safe(tvgName) + " " + safe(tvgId);
   80 |             if (RegionCategoryPolicy.isHongKongMacauTaiwan(metadata, group)) return;
   81 |             String broadcaster = ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
   82 |             if (broadcaster.startsWith("CCTV-")) { cctv++; return; }
   83 |             if (ChannelNamePolicy.isSatellite(metadata, group)) { satellite++; return; }
   84 |             String all = (metadata + " " + safe(group)).toLowerCase(java.util.Locale.ROOT);
   85 |             String province = ChannelNamePolicy.provinceForLocalChannel(all);
   86 |             if (!province.isEmpty()) {
   87 |                 local++;
   88 |                 provinces.add(province);
   89 |             } else if (all.contains("地方") || all.contains("local")) {
   90 |                 // A generic foreign "Local" group is not evidence of China.
   91 |                 if (all.contains("地方") || all.contains("中国") || all.contains("china")
   92 |                         || all.contains(".cn")) {
   93 |                     local++;
   94 |                     unresolvedLocal++;
   95 |                 }
   96 |             }
   97 |         }
   98 | 
   99 |         public int provinceCount() { return provinces.size(); }
  100 |         public int cctvCount() { return cctv; }
  101 |         public int satelliteCount() { return satellite; }
  102 |         public int localCount() { return local; }
  103 | 
  104 |         public String summary() {
  105 |             return summary("首次发布目录（含保留旧频道，非实播验证）");
  106 |         }
  107 | 
  108 |         public String summary(String label) {
  109 |             java.util.ArrayList<String> missing = new java.util.ArrayList<>();
  110 |             for (String province : PROVINCES) if (!provinces.contains(province)) missing.add(province);
  111 |             String warning = cctv == 0 || satellite == 0 || local == 0 || !missing.isEmpty()
  112 |                     ? "覆盖有缺口，待补查" : "各省级地区均有记录，仍待实播验收";
  113 |             return label + "：" + total + " 条"
  114 |                     + "\n央视 " + cctv + " · 卫视 " + satellite + " · 识别地方台 " + local
  115 |                     + "\n省级地区覆盖 " + provinceCount() + "/31 · 地方台省份未识别 " + unresolvedLocal
  116 |                     + "\n" + warning + (missing.isEmpty() ? "" : "；无已识别地方台记录：" + String.join("、", missing));
  117 |         }
  118 |     }
  119 | 
  120 |     private static String safe(String value) { return value == null ? "" : value; }
  121 | 
  122 | 
  123 |     /** One feed contributes at most 10,000 basis points, including partial pages. */
  124 |     public static int feedProgress(int cursor, int entries) {
  125 |         if (entries <= 0 || cursor <= 0) return 0;
  126 |         return (int)Math.min(10000L, 10000L * cursor / entries);
  127 |     }
  128 | 
  129 |     public static int overallPercent(boolean[] finished, int[] partial, boolean published) {
  130 |         if (published) return 100;
  131 |         if (finished == null || finished.length == 0) return 0;
  132 |         long work = 0;
  133 |         for (int i = 0; i < finished.length; i++) {
  134 |             work += finished[i] ? 10000 : partial != null && i < partial.length
  135 |                     ? Math.max(0, Math.min(10000, partial[i])) : 0;
  136 |         }
  137 |         return (int)Math.min(99L, work / (100L * finished.length));
  138 |     }
  139 | 
  140 |     public static int percent(int completedFeeds, int totalFeeds,
  141 |             int activeCursor, int activeFeedEntries, boolean published) {
  142 |         if (published) return 100;
  143 |         if (totalFeeds <= 0) return 0;
  144 |         int done = Math.max(0, Math.min(completedFeeds, totalFeeds));
  145 |         double current = 0d;
  146 |         if (done < totalFeeds && activeFeedEntries > 0) {
  147 |             current = Math.min(1d, Math.max(0d,
  148 |                     (double) Math.max(0, activeCursor) / activeFeedEntries));
  149 |         }
  150 |         int measured = (int)Math.floor(100d * (done + current) / totalFeeds);
  151 |         return Math.max(0, Math.min(99, measured));
  152 |     }
  153 | }
  154 | 
```


## app/src/main/java/com/wodedianshi/tv/DiscoveryBatchPolicy.java — 原始行 1–43

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /**
    4 |  * Tracks progress inside an immutable discovery batch by position, not URL.
    5 |  * A playlist may legitimately list the same endpoint under several channel
    6 |  * identities, so URL-based resume keys can jump over unprocessed entries.
    7 |  */
    8 | public final class DiscoveryBatchPolicy {
    9 |     private DiscoveryBatchPolicy() {}
   10 | 
   11 |     /** Independent partial page checkpoints enable fair rotation. */
   12 |     public static String initialBatchPrefix(int feedIndex) {
   13 |         return "source_initial_page_"+feedIndex+"_";
   14 |     }
   15 | 
   16 |     /** A prepared index belongs to one exact body, not simply its URL. */
   17 |     public static boolean isPreparedInitialFeed(String preparedVersion,String currentVersion) {
   18 |         return currentVersion!=null&&!currentVersion.isEmpty()&&currentVersion.equals(preparedVersion);
   19 |     }
   20 | 
   21 |     /** Never apply an offset to a different or unidentified feed body. */
   22 |     public static int cursorForVersion(int cursor, String savedVersion, String currentVersion) {
   23 |         return cursor > 0 && currentVersion != null && !currentVersion.isEmpty()
   24 |                 && currentVersion.equals(savedVersion) ? cursor : 0;
   25 |     }
   26 | 
   27 |     public static int scannedForBatch(String fingerprint, String previousFingerprint,
   28 |             int previousScanned, int candidateCount) {
   29 |         if (candidateCount <= 0 || fingerprint == null
   30 |                 || !fingerprint.equals(previousFingerprint)) return 0;
   31 |         return Math.max(0, Math.min(previousScanned, candidateCount));
   32 |     }
   33 | 
   34 |     public static int startIndex(int scanned, int candidateCount) {
   35 |         if (candidateCount <= 0 || scanned >= candidateCount) return 0;
   36 |         return Math.max(0, scanned);
   37 |     }
   38 | 
   39 |     public static int remaining(int scanned, int candidateCount) {
   40 |         return Math.max(0, candidateCount - Math.max(0, scanned));
   41 |     }
   42 | }
   43 | 
```


## app/src/main/java/com/wodedianshi/tv/InitialFeedRotationPolicy.java — 原始行 1–35

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /**
    4 |  * Fair, resumable first-build discovery across China and global playlist feeds.
    5 |  * A huge public playlist cannot monopolize all worker runs: after each fully
    6 |  * probed page, move to the next unfinished feed, retaining that feed's cursor.
    7 |  */
    8 | public final class InitialFeedRotationPolicy {
    9 |     private InitialFeedRotationPolicy() {}
   10 | 
   11 |     public static int normalizeStart(int requested, int feedCount) {
   12 |         if (feedCount <= 0) return 0;
   13 |         int value = requested % feedCount;
   14 |         return value < 0 ? value + feedCount : value;
   15 |     }
   16 | 
   17 |     /** One completely probed page is a fair turn even when the feed has more pages.
   18 |      * The caller skips already-completed feeds and uses their persisted cursors.
   19 |      * A failed read also yields so one inaccessible provider cannot block the others.
   20 |      */
   21 |     public static int nextAfterAttempt(int current, int feedCount,
   22 |             boolean readSucceeded, boolean pageFull) {
   23 |         if (feedCount <= 0) return 0;
   24 |         return (normalizeStart(current, feedCount) + 1) % feedCount;
   25 |     }
   26 | 
   27 |     /** Do not rotate midway through a partly probed page: the next WorkManager
   28 |      * attempt must find its exact fingerprint/cursor to finish the same batch.
   29 |      */
   30 |     public static int nextAfterProbeBatch(int attemptedFeed, int candidateNext,
   31 |             int feedCount, boolean fullyProbed) {
   32 |         return normalizeStart(fullyProbed ? candidateNext : attemptedFeed, feedCount);
   33 |     }
   34 | }
   35 | 
```


## app/src/main/java/com/wodedianshi/tv/InitialFeedFailurePolicy.java — 原始行 1–45

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /**
    4 |  * Bounds first-build discovery attempts per independently configured feed.
    5 |  * A terminal unavailable feed is an inspected outcome, NOT a successful feed.
    6 |  * It remains visible in progress diagnostics for future manual/weekly retry.
    7 |  */
    8 | public final class InitialFeedFailurePolicy {
    9 |     private InitialFeedFailurePolicy() {}
   10 |     public static final int MAX_FAILURE_ATTEMPTS = 3;
   11 | 
   12 |     public static int nextAttempts(int previous) {
   13 |         if (previous >= MAX_FAILURE_ATTEMPTS) return MAX_FAILURE_ATTEMPTS;
   14 |         return Math.max(0, previous) + 1;
   15 |     }
   16 | 
   17 |     /** Safe, stable UI reasons: never echo an untrusted response or URL token. */
   18 |     public static String userVisibleReason(String message) {
   19 |         String reason=message==null?"":message.toLowerCase(java.util.Locale.ROOT);
   20 |         if(reason.contains("http 404"))return "HTTP 404：目录不存在";
   21 |         if(reason.contains("http 410"))return "HTTP 410：目录已撤销";
   22 |         if(reason.contains("http 429"))return "HTTP 429：访问频率受限";
   23 |         if(reason.contains("http 401")||reason.contains("http 403"))
   24 |             return "目录需要授权或拒绝访问";
   25 |         if(reason.contains("timeout")||reason.contains("timed out")
   26 |                 ||reason.contains("超时"))return "网络连接或读取超时";
   27 |         if(reason.contains("ssl")||reason.contains("certificate"))
   28 |             return "TLS 或证书验证失败";
   29 |         if(reason.contains("m3u")||reason.contains("playlist"))
   30 |             return "播放列表格式或解析失败";
   31 |         return "网络访问或目录解析失败";
   32 |     }
   33 | 
   34 |     public static boolean isTerminalUnavailable(String message, int attempts) {
   35 |         String reason = message == null ? "" : message;
   36 |         // These represent our own scanning limits, not an unavailable provider.
   37 |         // Retrying the same full page later is safer than skipping it forever.
   38 |         if (reason.contains("本轮候选容量已满") || reason.contains("source candidate capacity"))
   39 |             return false;
   40 |         // The publisher returned a clear gone response.
   41 |         if (reason.contains("HTTP 404") || reason.contains("HTTP 410")) return true;
   42 |         return attempts >= MAX_FAILURE_ATTEMPTS;
   43 |     }
   44 | }
   45 | 
```


## app/src/main/java/com/wodedianshi/tv/SourceDiscoveryPriorityPolicy.java — 原始行 1–71

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /**
    4 |  * Explicit, bounded first-build discovery order:
    5 |  * mainland China (CCTV, provincial, local), then Hong Kong/Macau/Taiwan,
    6 |  * then other countries. Round-robin is fair *within* each stage.
    7 |  *
    8 |  * A stage with uncompleted feeds cannot be bypassed just because every
    9 |  * feed in it failed once in the current worker: the next worker retries
   10 |  * and the bounded-failure policy eventually marks it unavailable.
   11 |  */
   12 | public final class SourceDiscoveryPriorityPolicy {
   13 |     private SourceDiscoveryPriorityPolicy() {}
   14 | 
   15 |     /** 0 mainland, 1 HMT, 2 other regions, 3 all inspected. */
   16 |     public static int activeStage(boolean[] completed, int mainlandEnd, int regionEnd) {
   17 |         int size = completed == null ? 0 : completed.length;
   18 |         int china = bound(mainlandEnd, size);
   19 |         int hmt = Math.max(china, bound(regionEnd, size));
   20 |         for (int i = 0; i < china; i++) if (!completed[i]) return 0;
   21 |         for (int i = china; i < hmt; i++) if (!completed[i]) return 1;
   22 |         for (int i = hmt; i < size; i++) if (!completed[i]) return 2;
   23 |         return 3;
   24 |     }
   25 | 
   26 |     /**
   27 |      * Select next feed without changing the saved cursor; completed and
   28 |      * attempted flags have the same indices as the persisted feed list.
   29 |      * Return -1 once all feeds in the active stage were tried in this run.
   30 |      */
   31 |     public static int nextFeed(int preferredStart, boolean[] completed,
   32 |             boolean[] attempted, int mainlandEnd, int regionEnd) {
   33 |         int size = completed == null ? 0 : completed.length;
   34 |         if (size == 0 || attempted == null || attempted.length != size) return -1;
   35 |         int mainland = bound(mainlandEnd, size);
   36 |         int regions = Math.max(mainland, bound(regionEnd, size));
   37 |         int stage = activeStage(completed, mainland, regions);
   38 |         if (stage == 3) return -1;
   39 |         int from = stage == 0 ? 0 : stage == 1 ? mainland : regions;
   40 |         int to = stage == 0 ? mainland : stage == 1 ? regions : size;
   41 |         if (to <= from) return -1;
   42 |         int start = preferredStart >= from && preferredStart < to
   43 |                 ? preferredStart : from;
   44 |         for (int distance = 0; distance < to - from; distance++) {
   45 |             int index = from + (start - from + distance) % (to - from);
   46 |             if (!completed[index] && !attempted[index]) return index;
   47 |         }
   48 |         return -1;
   49 |     }
   50 | 
   51 |     public static int completedInStage(boolean[] completed, int from, int end) {
   52 |         if (completed == null) return 0;
   53 |         int start = bound(from, completed.length);
   54 |         int stop = Math.max(start, bound(end, completed.length));
   55 |         int count = 0;
   56 |         for (int i = start; i < stop; i++) if (completed[i]) count++;
   57 |         return count;
   58 |     }
   59 | 
   60 |     public static String stageLabel(int activeStage) {
   61 |         if (activeStage == 0) return "优先抓取中国大陆：央视、卫视、各省地方台";
   62 |         if (activeStage == 1) return "中国大陆来源已检查，正在整理港澳台频道";
   63 |         if (activeStage == 2) return "中国频道优先阶段已完成，正在检查国际来源";
   64 |         return "所有配置目录已检查，等待完整快照发布";
   65 |     }
   66 | 
   67 |     private static int bound(int value, int size) {
   68 |         return Math.max(0, Math.min(size, value));
   69 |     }
   70 | }
   71 | 
```


## app/src/main/java/com/wodedianshi/tv/SourceProbeTimePolicy.java — 原始行 1–90

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | /** Computes request timeouts without allowing a network read to ignore the probe-stage budget. */
    4 | public final class SourceProbeTimePolicy {
    5 |     private SourceProbeTimePolicy() {}
    6 |     public static final class PausedException extends java.io.IOException {
    7 |         public PausedException() { super("Source discovery paused for viewing"); }
    8 |     }
    9 |     /** Unchecked so Room's transaction callback rolls back on a viewing pause. */
   10 |     public static final class PublicationPausedException extends RuntimeException {
   11 |         public PublicationPausedException() { super("Catalogue publication paused for viewing"); }
   12 |     }
   13 |     /** Once this execution observes cancellation it cannot resume or score it as failure. */
   14 |     public static java.util.function.BooleanSupplier latchPause(java.util.function.BooleanSupplier requested) {
   15 |         java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
   16 |         return () -> {
   17 |             if (Thread.currentThread().isInterrupted() || requested.getAsBoolean()) paused.set(true);
   18 |             return paused.get();
   19 |         };
   20 |     }
   21 | 
   22 |     public static void checkPublicationPaused(java.util.function.BooleanSupplier paused) {
   23 |         if (Thread.currentThread().isInterrupted() || (paused != null && paused.getAsBoolean()))
   24 |             throw new PublicationPausedException();
   25 |     }
   26 |     public static void checkPaused(java.util.function.BooleanSupplier paused) throws PausedException {
   27 |         if (Thread.currentThread().isInterrupted() || (paused != null && paused.getAsBoolean()))
   28 |             throw new PausedException();
   29 |     }
   30 | 
   31 |     public static final long LIVE_DIRECTORY_MAX_BYTES=256L*1024L;
   32 |     public static final long LIVE_DIRECTORY_BYTES_PER_SECOND=32L*1024L;
   33 |     public static final long LIVE_DIRECTORY_BUDGET_MS=15000L;
   34 |     /** Negative content lengths are unknown; enforce the same cap as bytes arrive. */
   35 |     public static boolean fitsLiveDirectory(long bytes) {
   36 |         return bytes<=LIVE_DIRECTORY_MAX_BYTES;
   37 |     }
   38 |     public static long liveDirectoryPacingMillis(long copied,long elapsed) {
   39 |         long bounded=Math.max(0L,Math.min(LIVE_DIRECTORY_MAX_BYTES,copied));
   40 |         return Math.max(0L,(bounded*1000L+LIVE_DIRECTORY_BYTES_PER_SECOND-1L)
   41 |                 /LIVE_DIRECTORY_BYTES_PER_SECOND-Math.max(0L,elapsed));
   42 |     }
   43 | 
   44 |     public static final long LIVE_RESUMABLE_MAX_BYTES=8L*1024L*1024L;
   45 |     public static boolean strongDirectoryValidator(String etag) {
   46 |         return etag!=null&&etag.length()>=2&&etag.startsWith("\"")&&etag.endsWith("\"")
   47 |                 &&etag.substring(1,etag.length()-1).indexOf('"')<0
   48 |                 &&etag.indexOf('\r')<0&&etag.indexOf('\n')<0;
   49 |     }
   50 |     /** Strict Content-Range check; reject unknown totals and changed offsets. */
   51 |     public static long[] liveDirectoryRange(String header,long offset,long priorTotal) {
   52 |         return directoryRange(header,offset,priorTotal,LIVE_DIRECTORY_MAX_BYTES);
   53 |     }
   54 | 
   55 |     public static long[] directoryRange(String header,long offset,long priorTotal,long chunkLimit) {
   56 |         if(chunkLimit<=0L||chunkLimit>LIVE_RESUMABLE_MAX_BYTES)return null;
   57 |         if(header==null||offset<0L||!header.matches("bytes [0-9]+-[0-9]+/[0-9]+"))return null;
   58 |         try{
   59 |             String[] values=header.substring(6).split("[-/]");
   60 |             long start=Long.parseLong(values[0]),end=Long.parseLong(values[1]),total=Long.parseLong(values[2]);
   61 |             if(start!=offset||end<start||total<=end||total>LIVE_RESUMABLE_MAX_BYTES
   62 |                     ||end-start>=chunkLimit
   63 |                     ||(priorTotal>0L&&total!=priorTotal))return null;
   64 |             return new long[]{end-start+1,total};
   65 |         }catch(NumberFormatException invalid){return null;}
   66 |     }
   67 | 
   68 |     // First build prioritizes breadth. Keep one difficult HLS address from
   69 |     // monopolizing an entire slice. A timed-out probe is inconclusive.
   70 |     public static final long INITIAL_ADDRESS_BUDGET_MS = 15_000L;
   71 |     public static long initialAddressDeadline(long batchDeadline, long now) {
   72 |         if (now < 0L || batchDeadline < now
   73 |                 || batchDeadline - now < INITIAL_ADDRESS_BUDGET_MS) return 0L;
   74 |         return now + INITIAL_ADDRESS_BUDGET_MS;
   75 |     }
   76 | 
   77 |     /**
   78 |      * Returns zero when the deadline has expired; otherwise caps the request timeout
   79 |      * at both the per-request maximum and the remaining elapsed-realtime budget.
   80 |      */
   81 |     public static int boundedTimeoutMillis(long deadlineElapsedRealtime,
   82 |                                            long nowElapsedRealtime,
   83 |                                            int maximumTimeoutMillis) {
   84 |         if (maximumTimeoutMillis <= 0) return 0;
   85 |         long remaining = deadlineElapsedRealtime - nowElapsedRealtime;
   86 |         if (remaining <= 0L) return 0;
   87 |         return (int) Math.min(remaining, (long) maximumTimeoutMillis);
   88 |     }
   89 | }
   90 | 
```


## app/src/test/java/com/wodedianshi/tv/SourceUpdateSchedulePolicyTest.java — 原始行 1–144

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.*;
    5 | 
    6 | public class SourceUpdateSchedulePolicyTest {
    7 |     @Test public void normalInitialViewingDeferralUsesFixedDelay() {
    8 |         assertEquals(30L,SourceUpdateSchedulePolicy.initialViewingDelaySeconds(false));
    9 |         assertEquals(60L,SourceUpdateSchedulePolicy.initialViewingDelaySeconds(true));
   10 |         assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true,false));
   11 |         assertFalse(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false,false));
   12 |     }
   13 | 
   14 |     @Test public void firstDiscoveryProbeReuseIsShortLivedAndClockSafe() {
   15 |         assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, 1000, 301000));
   16 |         assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, 1000, 301001));
   17 |         assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(false, 1000, 16000));
   18 |         assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(false, 1000, 16001));
   19 |         assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, 1000, 999));
   20 |         assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, -1, 999));
   21 |     }
   22 | 
   23 |     @org.junit.Test public void manualRefreshProcessesAllPagesBeforeAdvancing() {
   24 |         org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(0,12,25,12));
   25 |         org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(12,12,25,12));
   26 |         org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(24,1,25,12));
   27 |         org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(0,12,12,12));
   28 |         org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(0,12,-1,12));
   29 |         org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(12,0,-1,12));
   30 |     }
   31 | 
   32 |     @Test public void routineRotationCoversEveryFeedWithoutWrappingMidSlice() {
   33 |         int start=0, visited=0;
   34 |         do {
   35 |             int end=SourceUpdateSchedulePolicy.routineSliceEnd(start,26);
   36 |             assertTrue(end>start && end-start<=3);
   37 |             visited+=end-start;
   38 |             assertEquals(start,SourceUpdateSchedulePolicy.routineNextFeed(start,26,false));
   39 |             start=SourceUpdateSchedulePolicy.routineNextFeed(start,26,true);
   40 |         } while(start!=0);
   41 |         assertEquals(26,visited);
   42 |         assertEquals(26,SourceUpdateSchedulePolicy.routineSliceEnd(24,26));
   43 |     }
   44 | 
   45 |     @Test public void defersAutomaticUpdatesWhileAppIsForegrounded() {
   46 |         assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(true, false));
   47 |     }
   48 | 
   49 |     @Test public void allowsAutomaticUpdatesWhenAppIsInBackground() {
   50 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(false, false));
   51 |     }
   52 | 
   53 |     @Test public void explicitManualUpdateIsNotDeferredByForegroundPolicy() {
   54 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(true, true));
   55 |     }
   56 | 
   57 |     @Test public void firstInstallMayDiscoverWithEmptyOrSeededCatalogueInForeground() {
   58 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
   59 |                 true, false, true, false));
   60 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
   61 |                 true, false, true, true));
   62 |         assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
   63 |                 true, false, false, false));
   64 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
   65 |                 false, false, true, false));
   66 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
   67 |                 true, true, true, false));
   68 |     }
   69 | 
   70 |     @Test public void schedulesDiscoveryForNewInstall() {
   71 |         assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, 0L));
   72 |     }
   73 | 
   74 |     @Test public void doesNotScheduleWhenAutomaticUpdatesAreDisabled() {
   75 |         assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(false, 0L));
   76 |         assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(false, 1_700_000_000_000L));
   77 |     }
   78 | 
   79 |     @Test public void doesNotRepeatDiscoveryAfterAValidatedInitialDiscovery() {
   80 |         assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, 1_700_000_000_000L));
   81 |     }
   82 | 
   83 |     @Test public void failedAttemptsRemainEligibleForAnotherInitialDiscovery() {
   84 |         // A failed attempt must not write source_update_last_success_at.
   85 |         assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, 0L));
   86 |     }
   87 | 
   88 |     @Test public void negativeTimestampIsTreatedAsMissing() {
   89 |         assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, -1L));
   90 |     }
   91 | 
   92 |     @Test public void emptyOrUnvalidatedDiscoveryDoesNotCountAsSuccessful() {
   93 |         assertFalse(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(0));
   94 |         assertFalse(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(-1));
   95 |     }
   96 | 
   97 |     @Test public void initialDiscoveryRequiresEveryCandidateAndEveryFeed() {
   98 |         assertFalse(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(144, 24, 0));
   99 |         assertFalse(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(144, 144, 1));
  100 |         assertFalse(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(0, 0, 0));
  101 |         assertTrue(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(144, 144, 0));
  102 |     }
  103 | 
  104 |     @Test public void routineMaintenanceOnlyFollowsCompleteFirstCatalogue() {
  105 |         assertFalse(SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true, 0L));
  106 |         assertFalse(SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(false, 1234L));
  107 |         assertTrue(SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true, 1234L));
  108 |         assertEquals(7L, SourceUpdateSchedulePolicy.ROUTINE_REFRESH_DAYS);
  109 |     }
  110 | 
  111 |     @Test public void weeklyDeadlineSurvivesBusyDailyChecks() {
  112 |         long start=1_700_000_000_000L;
  113 |         long day=24L*60L*60L*1000L;
  114 |         assertEquals(1L, SourceUpdateSchedulePolicy.ROUTINE_ELIGIBILITY_CHECK_DAYS);
  115 |         assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+6*day));
  116 |         assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+7*day));
  117 |         assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+8*day));
  118 |         assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,start+8*day,start+9*day));
  119 |         assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,start+8*day,start+15*day));
  120 |         assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(0,0,start));
  121 |     }
  122 | 
  123 |     @Test public void busyRoutineUpdateSkipsRatherThanRetryingEveryFewMinutes() {
  124 |         assertFalse(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false, false));
  125 |         assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true, false));
  126 |         assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false, true));
  127 |         assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true, true));
  128 |     }
  129 | 
  130 |     @Test public void manualFirstRebuildMayProceedWhenSavedChannelsAlreadyExist() {
  131 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
  132 |                 true, true, true, true));
  133 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
  134 |                 true, true, true, false));
  135 |         assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
  136 |                 true, true, false, true));
  137 |     }
  138 | 
  139 |     @Test public void validatedDiscoverySourceCountsAsSuccessful() {
  140 |         assertTrue(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(1));
  141 |         assertTrue(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(24));
  142 |     }
  143 | }
  144 | 
```


## app/src/test/java/com/wodedianshi/tv/SourceUpdateActivityPolicyTest.java — 原始行 1–25

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.*;
    5 | 
    6 | public class SourceUpdateActivityPolicyTest {
    7 |     @Test public void viewingFlagsAreIndependentAndCanBeCleared() {
    8 |         try {
    9 |             SourceUpdateActivityPolicy.setActivityActive(true);
   10 |             SourceUpdateActivityPolicy.setPlaybackActive(false);
   11 |             assertTrue(SourceUpdateActivityPolicy.isActivityActive());
   12 |             assertFalse(SourceUpdateActivityPolicy.isPlaybackActive());
   13 |             SourceUpdateActivityPolicy.setPlaybackActive(true);
   14 |             SourceUpdateActivityPolicy.setActivityActive(false);
   15 |             assertFalse(SourceUpdateActivityPolicy.isActivityActive());
   16 |             assertTrue(SourceUpdateActivityPolicy.isPlaybackActive());
   17 |         } finally {
   18 |             SourceUpdateActivityPolicy.setPlaybackActive(false);
   19 |             SourceUpdateActivityPolicy.setActivityActive(false);
   20 |         }
   21 |         assertFalse(SourceUpdateActivityPolicy.isPlaybackActive());
   22 |         assertFalse(SourceUpdateActivityPolicy.isActivityActive());
   23 |     }
   24 | }
   25 | 
```


## app/src/test/java/com/wodedianshi/tv/FullDiscoveryProgressPolicyTest.java — 原始行 1–128

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.assertEquals;
    5 | import static org.junit.Assert.assertTrue;
    6 | 
    7 | public class FullDiscoveryProgressPolicyTest {
    8 |     @Test public void playingSeedDoesNotBlockEmptyStagingOrOptionalBootstrap() {
    9 |         assertTrue(FullDiscoveryProgressPolicy.canResetInitialStaging(true,0));
   10 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canResetInitialStaging(true,1));
   11 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canResetInitialStaging(true,-1));
   12 |         assertTrue(FullDiscoveryProgressPolicy.canResetInitialStaging(false,100));
   13 |         assertTrue(FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(false,0,10,0,0,false));
   14 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(true,0,10,0,0,false));
   15 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(false,2,10,0,0,false));
   16 |     }
   17 | 
   18 |     @Test public void stagingReuseRequiresMatchingCommittedRevisionAndFreshBoundedRows() {
   19 |         assertTrue(FullDiscoveryProgressPolicy.canRetainStaging(512,true));
   20 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canRetainStaging(513,true));
   21 |         assertTrue(FullDiscoveryProgressPolicy.canRetainStaging(1024,false));
   22 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canRetainStaging(1025,false));
   23 |         assertTrue(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","a",1000,61000));
   24 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","a",1000,61001));
   25 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","b",1000,1100));
   26 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"","",1000,1100));
   27 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","a",1000,999));
   28 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(-1,"a","a",1000,1100));
   29 |     }
   30 | 
   31 |     @Test public void finalizationDoesNotConfuseInspectedWithPublished() {
   32 |         assertTrue(FullDiscoveryProgressPolicy.awaitingIdleFinalization(true,true));
   33 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.awaitingIdleFinalization(false,true));
   34 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.awaitingIdleFinalization(true,false));
   35 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,true,true));
   36 |         assertTrue(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,true,false));
   37 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(false,true,false));
   38 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,false,false));
   39 |         assertEquals(99,FullDiscoveryProgressPolicy.overallPercent(new boolean[]{true,true},new int[]{0,0},false));
   40 |     }
   41 | 
   42 |     @Test public void mainlandIndexPhaseIsSeparateFromVerifiedProgress() {
   43 |         assertTrue(FullDiscoveryProgressPolicy.isMainlandIndexPhase(0,10,0,0,false));
   44 |         assertTrue(FullDiscoveryProgressPolicy.isMainlandIndexPhase(1,10,0,0,false));
   45 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(2,10,0,0,false));
   46 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(1,10,0,5,false));
   47 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(1,10,1,0,false));
   48 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(0,10,0,0,true));
   49 |         org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(0,0,0,0,false));
   50 |         assertEquals(2,FullDiscoveryProgressPolicy.bootstrapIndexLimit(10));
   51 |         assertEquals(1,FullDiscoveryProgressPolicy.bootstrapIndexLimit(1));
   52 |         assertEquals(0,FullDiscoveryProgressPolicy.bootstrapIndexLimit(-1));
   53 |     }
   54 | 
   55 |     @org.junit.Test public void earlyPreviewMilestonesNeverPretendToCompleteFullDiscovery() {
   56 |         org.junit.Assert.assertEquals(0, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 11, 3, false));
   57 |         org.junit.Assert.assertEquals(0, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 20, 0, true));
   58 |         org.junit.Assert.assertEquals(1, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 12, 3, false));
   59 |         org.junit.Assert.assertEquals(1, FullDiscoveryProgressPolicy.nextPreviewLevel(1, 40, 20, false));
   60 |         org.junit.Assert.assertEquals(2, FullDiscoveryProgressPolicy.nextPreviewLevel(1, 40, 20, true));
   61 |         org.junit.Assert.assertEquals(2, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 3, 3, true));
   62 |         org.junit.Assert.assertEquals(2, FullDiscoveryProgressPolicy.nextPreviewLevel(2, 40, 20, true));
   63 |     }
   64 | 
   65 |     @Test public void overallProgressIncludesEveryPartialFeed() {
   66 |         boolean[] done={true,false,false,false};
   67 |         int[] partial={0,5000,2500,0};
   68 |         assertEquals(43,FullDiscoveryProgressPolicy.overallPercent(done,partial,false));
   69 |         partial[1]=0;
   70 |         assertEquals(31,FullDiscoveryProgressPolicy.overallPercent(done,partial,false));
   71 |         assertEquals(99,FullDiscoveryProgressPolicy.overallPercent(
   72 |             new boolean[]{true,true},new int[]{0,0},false));
   73 |         assertEquals(100,FullDiscoveryProgressPolicy.overallPercent(done,partial,true));
   74 |         assertEquals(5000,FullDiscoveryProgressPolicy.feedProgress(12,24));
   75 |         assertEquals(0,FullDiscoveryProgressPolicy.feedProgress(12,-1));
   76 |         assertEquals(10000,FullDiscoveryProgressPolicy.feedProgress(Integer.MAX_VALUE,1));
   77 |     }
   78 | 
   79 |     @Test public void coverageSeparatesMainlandCategoriesAndDoesNotCountForeignLocal() {
   80 |         FullDiscoveryProgressPolicy.Coverage c = new FullDiscoveryProgressPolicy.Coverage();
   81 |         c.add("CCTV-2","央视","","");
   82 |         c.add("福建东南卫视","卫视","","");
   83 |         c.add("厦门新闻","福建","","");
   84 |         c.add("Shenzhen News","广东","","");
   85 |         c.add("Local News","US","","");
   86 |         c.add("香港新闻","香港","","");
   87 |         assertEquals(1,c.cctvCount());
   88 |         assertEquals(1,c.satelliteCount());
   89 |         assertEquals(2,c.localCount());
   90 |         assertEquals(2,c.provinceCount());
   91 |         assertTrue(c.summary().contains("覆盖有缺口"));
   92 |         assertTrue(c.summary().contains("非实播验证"));
   93 |     }
   94 |     @Test public void emptyCoverageNeverClaimsSuccess() {
   95 |         FullDiscoveryProgressPolicy.Coverage c = new FullDiscoveryProgressPolicy.Coverage();
   96 |         assertEquals(0,c.provinceCount());
   97 |         assertTrue(c.summary().contains("0/31"));
   98 |         assertTrue(c.summary().contains("覆盖有缺口"));
   99 |     }
  100 | 
  101 |     @Test public void countsCompletedFeedsAndIndexedCursor() {
  102 |         assertEquals(0, FullDiscoveryProgressPolicy.percent(0, 20, 0, 500, false));
  103 |         assertEquals(12, FullDiscoveryProgressPolicy.percent(2, 20, 250, 500, false));
  104 |         assertEquals(25, FullDiscoveryProgressPolicy.percent(5, 20, 0, 0, false));
  105 |     }
  106 | 
  107 |     @Test public void aUnpublishedSnapshotNeverShowsOneHundredPercent() {
  108 |         assertEquals(99, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, false));
  109 |         assertEquals(100, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, true));
  110 |     }
  111 | 
  112 |     @Test public void missingOrInvalidIndexNeverInventsFractionalProgress() {
  113 |         assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, 50, -1, false));
  114 |         assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, -5, 100, false));
  115 |         assertEquals(0, FullDiscoveryProgressPolicy.percent(3, 0, 50, 100, false));
  116 |     }
  117 | 
  118 |     @Test public void corruptOrOverflowingCursorIsClamped() {
  119 |         assertEquals(20, FullDiscoveryProgressPolicy.percent(3, 20, 5000, 100, false));
  120 |         assertEquals(99, FullDiscoveryProgressPolicy.percent(Integer.MAX_VALUE, 20,
  121 |                 Integer.MAX_VALUE, Integer.MAX_VALUE, false));
  122 |     }
  123 | 
  124 |     @Test public void completedPercentRequiresActualPublication() {
  125 |         assertEquals(100, FullDiscoveryProgressPolicy.percent(0, 20, 0, 0, true));
  126 |     }
  127 | }
  128 | 
```


## app/src/test/java/com/wodedianshi/tv/DiscoveryBatchPolicyTest.java — 原始行 1–90

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.assertEquals;
    5 | 
    6 | public class DiscoveryBatchPolicyTest {
    7 |     @org.junit.Test public void acquisitionMarkerRequiresExactNonemptyBodyVersion() {
    8 |         org.junit.Assert.assertTrue(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:hash","feed:hash"));
    9 |         org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:old","feed:new"));
   10 |         org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("",""));
   11 |         org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed(null,"feed:hash"));
   12 |         org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:hash",null));
   13 |     }
   14 | 
   15 |     @org.junit.Test public void incrementalStagingRequiresEveryAppendedRowAndValidPositions() {
   16 |         com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(5,5,java.util.Collections.emptySet());
   17 |         com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(5,7,
   18 |             new java.util.HashSet<>(java.util.Arrays.asList(0,5,6)));
   19 |         int[][] invalid={{5,4,0},{5,7,5},{5,5,5},{5,5,-1}};
   20 |         for(int[] item:invalid){
   21 |             try{
   22 |                 com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(
   23 |                     item[0],item[1],java.util.Collections.singleton(item[2]));
   24 |                 org.junit.Assert.fail("Invalid staging mutation accepted");
   25 |             }catch(IllegalArgumentException expected){}
   26 |         }
   27 |     }
   28 | 
   29 |     @org.junit.Test public void independentlyResumesInterleavedFeedPages() {
   30 |         String first=DiscoveryBatchPolicy.initialBatchPrefix(0);
   31 |         String second=DiscoveryBatchPolicy.initialBatchPrefix(1);
   32 |         org.junit.Assert.assertNotEquals(first,second);
   33 |         java.util.Map<String,Integer> offsets=new java.util.HashMap<>();
   34 |         offsets.put(first+"scanned",7);
   35 |         offsets.put(second+"scanned",2);
   36 |         org.junit.Assert.assertEquals(7,DiscoveryBatchPolicy.scannedForBatch("a","a",offsets.get(first+"scanned"),24));
   37 |         org.junit.Assert.assertEquals(2,DiscoveryBatchPolicy.scannedForBatch("b","b",offsets.get(second+"scanned"),24));
   38 |         org.junit.Assert.assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("new-a","a",offsets.get(first+"scanned"),24));
   39 |         offsets.remove(first+"scanned");
   40 |         org.junit.Assert.assertEquals(Integer.valueOf(2),offsets.get(second+"scanned"));
   41 |     }
   42 | 
   43 |     @Test public void cursorResumesOnlyForIdenticalKnownFeedVersion() {
   44 |         org.junit.Assert.assertEquals(24, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a","feed:a"));
   45 |         org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a","feed:b"));
   46 |         org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"","feed:b"));
   47 |         org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"",""));
   48 |         org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a",null));
   49 |         org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(-1,"feed:a","feed:a"));
   50 |     }
   51 | 
   52 |     @Test public void resumesByPositionEvenWhenSeveralChannelsShareTheSameUrl() {
   53 |         String fingerprint = "same-batch";
   54 |         int scanned = DiscoveryBatchPolicy.scannedForBatch(
   55 |                 fingerprint, fingerprint, 2, 5);
   56 |         assertEquals(2, DiscoveryBatchPolicy.startIndex(scanned, 5));
   57 |         assertEquals(3, DiscoveryBatchPolicy.remaining(scanned, 5));
   58 |     }
   59 | 
   60 |     @Test public void duplicateEndpointDoesNotSkipUnscannedChannelAfterIt() {
   61 |         java.util.List<String> channelEndpoints = java.util.Arrays.asList(
   62 |                 "[URL_REDACTED]",
   63 |                 "[URL_REDACTED]",
   64 |                 "[URL_REDACTED]",
   65 |                 "[URL_REDACTED]");
   66 |         int scanned = DiscoveryBatchPolicy.scannedForBatch(
   67 |                 "batch", "batch", 1, channelEndpoints.size());
   68 |         assertEquals("[URL_REDACTED]",
   69 |                 channelEndpoints.get(DiscoveryBatchPolicy.startIndex(
   70 |                         scanned, channelEndpoints.size())));
   71 |     }
   72 | 
   73 |     @Test public void changedBatchResetsPositionInsteadOfReusingOldCursor() {
   74 |         int scanned = DiscoveryBatchPolicy.scannedForBatch(
   75 |                 "new-batch", "old-batch", 4, 7);
   76 |         assertEquals(0, scanned);
   77 |         assertEquals(0, DiscoveryBatchPolicy.startIndex(scanned, 7));
   78 |         assertEquals(7, DiscoveryBatchPolicy.remaining(scanned, 7));
   79 |     }
   80 | 
   81 |     @Test public void clampsProgressAndHandlesAnExhaustedBatch() {
   82 |         int scanned = DiscoveryBatchPolicy.scannedForBatch(
   83 |                 "batch", "batch", 99, 3);
   84 |         assertEquals(0, DiscoveryBatchPolicy.startIndex(scanned, 3));
   85 |         assertEquals(0, DiscoveryBatchPolicy.remaining(scanned, 3));
   86 |         assertEquals(0, DiscoveryBatchPolicy.scannedForBatch(
   87 |                 "batch", "batch", 2, 0));
   88 |     }
   89 | }
   90 | 
```


## app/src/test/java/com/wodedianshi/tv/InitialFeedFailurePolicyTest.java — 原始行 1–42

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.*;
    5 | 
    6 | public class InitialFeedFailurePolicyTest {
    7 |     @Test public void temporaryFailureDoesNotBlockFullBuildForever() {
    8 |         assertFalse(InitialFeedFailurePolicy.isTerminalUnavailable("timed out",1));
    9 |         assertFalse(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 429",2));
   10 |         assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 429",3));
   11 |         assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("timed out",3));
   12 |     }
   13 | 
   14 |     @Test public void removedPublicFeedsAreImmediatelyMarkedUnavailable() {
   15 |         assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 404",1));
   16 |         assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 410",1));
   17 |     }
   18 | 
   19 |     @Test public void internalBatchBudgetMustNotBeReportedAsDeadFeed() {
   20 |         assertFalse(InitialFeedFailurePolicy.isTerminalUnavailable(
   21 |             "本轮候选容量已满，保留目录游标并在下轮继续",3));
   22 |     }
   23 | 
   24 |     @Test public void failureReasonsAreSafeAndUsefulForManualReview() {
   25 |         assertEquals("HTTP 404：目录不存在",
   26 |             InitialFeedFailurePolicy.userVisibleReason("failed HTTP 404"));
   27 |         assertEquals("HTTP 429：访问频率受限",
   28 |             InitialFeedFailurePolicy.userVisibleReason("HTTP 429"));
   29 |         assertEquals("网络连接或读取超时",
   30 |             InitialFeedFailurePolicy.userVisibleReason("Read timed out"));
   31 |         assertEquals("网络访问或目录解析失败",
   32 |             InitialFeedFailurePolicy.userVisibleReason("token=secret"));
   33 |     }
   34 | 
   35 |     @Test public void retryCountsAreClampedAndPreserveProgress() {
   36 |         assertEquals(1,InitialFeedFailurePolicy.nextAttempts(0));
   37 |         assertEquals(2,InitialFeedFailurePolicy.nextAttempts(1));
   38 |         assertEquals(3,InitialFeedFailurePolicy.nextAttempts(2));
   39 |         assertEquals(3,InitialFeedFailurePolicy.nextAttempts(Integer.MAX_VALUE));
   40 |     }
   41 | }
   42 | 
```


## app/src/test/java/com/wodedianshi/tv/InitialFeedRotationPolicyTest.java — 原始行 1–40

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.assertEquals;
    5 | 
    6 | public class InitialFeedRotationPolicyTest {
    7 |     @Test public void transientFailureRotatesToNextFeed() {
    8 |         assertEquals(1, InitialFeedRotationPolicy.nextAfterAttempt(
    9 |                 0, 21, false, false));
   10 |     }
   11 | 
   12 |     @Test public void largeFeedYieldsAfterEveryCompletedPage() {
   13 |         assertEquals(5, InitialFeedRotationPolicy.nextAfterAttempt(
   14 |                 4, 21, true, true));
   15 |         assertEquals(6, InitialFeedRotationPolicy.nextAfterAttempt(
   16 |                 5, 21, true, true));
   17 |     }
   18 | 
   19 |     @Test public void shortPageMovesToNextFeed() {
   20 |         assertEquals(5, InitialFeedRotationPolicy.nextAfterAttempt(
   21 |                 4, 21, true, false));
   22 |     }
   23 | 
   24 |     @Test public void incompleteProbeBatchMustResumeSameFeed() {
   25 |         assertEquals(4, InitialFeedRotationPolicy.nextAfterProbeBatch(
   26 |                 4, 5, 21, false));
   27 |         assertEquals(5, InitialFeedRotationPolicy.nextAfterProbeBatch(
   28 |                 4, 5, 21, true));
   29 |     }
   30 | 
   31 |     @Test public void wrapsAndNormalizesNegativeIndexes() {
   32 |         assertEquals(0, InitialFeedRotationPolicy.nextAfterAttempt(
   33 |                 20, 21, false, false));
   34 |         assertEquals(20, InitialFeedRotationPolicy.normalizeStart(-1, 21));
   35 |         assertEquals(20, InitialFeedRotationPolicy.nextAfterProbeBatch(
   36 |                 -1, 0, 21, false));
   37 |         assertEquals(0, InitialFeedRotationPolicy.nextAfterAttempt(0, 0, true, true));
   38 |     }
   39 | }
   40 | 
```


## app/src/test/java/com/wodedianshi/tv/SourceDiscoveryPriorityPolicyTest.java — 原始行 1–62

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.*;
    5 | 
    6 | public class SourceDiscoveryPriorityPolicyTest {
    7 |     @Test public void mainlandHasPriorityEvenAfterCursorRotatesIntoGlobalArea() {
    8 |         boolean[] done = new boolean[8], tried = new boolean[8];
    9 |         assertEquals(0, SourceDiscoveryPriorityPolicy.nextFeed(7, done, tried,3,5));
   10 |         tried[0] = true;
   11 |         assertEquals(1, SourceDiscoveryPriorityPolicy.nextFeed(1,done,tried,3,5));
   12 |         tried[1] = true;
   13 |         tried[2] = true;
   14 |         assertEquals(-1, SourceDiscoveryPriorityPolicy.nextFeed(5,done,tried,3,5));
   15 |         assertEquals(0,SourceDiscoveryPriorityPolicy.activeStage(done,3,5));
   16 |     }
   17 | 
   18 |     @Test public void onceMainlandIsInspectedHongKongMacauTaiwanGoNext() {
   19 |         boolean[] done = {true,true,true,false,false,false,false};
   20 |         boolean[] tried = new boolean[done.length];
   21 |         assertEquals(1,SourceDiscoveryPriorityPolicy.activeStage(done,3,5));
   22 |         assertEquals(3,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,5));
   23 |         tried[3] = true;
   24 |         assertEquals(4,SourceDiscoveryPriorityPolicy.nextFeed(6,done,tried,3,5));
   25 |     }
   26 | 
   27 |     @Test public void globalFeedsOnlyBeginAfterChineseStagesAreInspected() {
   28 |         boolean[] done = {true,true,true,true,true,false,false};
   29 |         boolean[] tried = new boolean[done.length];
   30 |         assertEquals(2,SourceDiscoveryPriorityPolicy.activeStage(done,3,5));
   31 |         assertEquals(6,SourceDiscoveryPriorityPolicy.nextFeed(6,done,tried,3,5));
   32 |         assertEquals(5,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,5));
   33 |     }
   34 | 
   35 |     @Test public void roundRobinDoesNotRepeatedlyProbeSameFeedInOneWorker() {
   36 |         boolean[] done=new boolean[6],tried=new boolean[6];
   37 |         assertEquals(2,SourceDiscoveryPriorityPolicy.nextFeed(2,done,tried,3,4));
   38 |         tried[2]=true;
   39 |         assertEquals(0,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,4));
   40 |         tried[0]=true;
   41 |         assertEquals(1,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,4));
   42 |     }
   43 | 
   44 |     @Test public void allFinishedOrInvalidStateEndsSafely() {
   45 |         boolean[] done = {true,true,true,true};
   46 |         boolean[] tried=new boolean[4];
   47 |         assertEquals(3,SourceDiscoveryPriorityPolicy.activeStage(done,2,3));
   48 |         assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,2,3));
   49 |         assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,null,tried,2,3));
   50 |         assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,done,new boolean[2],2,3));
   51 |     }
   52 | 
   53 |     @Test public void completedAndUnavailableBothCountAsInspected() {
   54 |         boolean[] done = {true,true,false,false};
   55 |         assertEquals(2,SourceDiscoveryPriorityPolicy.completedInStage(done,0,4));
   56 |         assertEquals(2,SourceDiscoveryPriorityPolicy.completedInStage(done,-5,20));
   57 |         assertEquals(0,SourceDiscoveryPriorityPolicy.completedInStage(done,3,1));
   58 |         assertEquals("优先抓取中国大陆：央视、卫视、各省地方台",
   59 |             SourceDiscoveryPriorityPolicy.stageLabel(0));
   60 |     }
   61 | }
   62 | 
```


## app/src/test/java/com/wodedianshi/tv/SourceProbeTimePolicyTest.java — 原始行 1–157

```text
    1 | package com.wodedianshi.tv;
    2 | 
    3 | import org.junit.Test;
    4 | import static org.junit.Assert.*;
    5 | 
    6 | public class SourceProbeTimePolicyTest {
    7 |     @Test public void idleResumeAllowsLargerButStillBoundedRanges() {
    8 |         org.junit.Assert.assertArrayEquals(new long[]{2097152L,4194304L},
    9 |             SourceProbeTimePolicy.directoryRange("bytes 262144-2359295/4194304",262144L,4194304L,2097152L));
   10 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 262144-2359296/4194304",262144L,4194304L,2097152L));
   11 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 262144-2359295/4194305",262144L,4194304L,2097152L));
   12 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 0-1/2",0L,-1L,0L));
   13 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 0-1/2",0L,-1L,8388609L));
   14 |     }
   15 | 
   16 |     @Test public void rangeResumeRejectsWeakValidatorAndInexactContentRanges() {
   17 |         assertTrue(SourceProbeTimePolicy.strongDirectoryValidator("\"a\""));
   18 |         assertFalse(SourceProbeTimePolicy.strongDirectoryValidator("W/\"a\""));
   19 |         assertFalse(SourceProbeTimePolicy.strongDirectoryValidator(null));
   20 |         assertFalse(SourceProbeTimePolicy.strongDirectoryValidator("\"a\nb\""));
   21 |         org.junit.Assert.assertArrayEquals(new long[]{262144L,524288L},
   22 |             SourceProbeTimePolicy.liveDirectoryRange("bytes 262144-524287/524288",262144L,524288L));
   23 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-262143/524288",262144L,524288L));
   24 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 262144-524287/524289",262144L,524288L));
   25 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-262144/524288",0L,-1L));
   26 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-1/*",0L,-1L));
   27 |         org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-1/8388609",0L,-1L));
   28 |     }
   29 | 
   30 |     @Test public void viewingDirectoryUsesBoundedSizeAndPacing() {
   31 |         assertTrue(SourceProbeTimePolicy.fitsLiveDirectory(-1L));
   32 |         assertTrue(SourceProbeTimePolicy.fitsLiveDirectory(262144L));
   33 |         assertFalse(SourceProbeTimePolicy.fitsLiveDirectory(262145L));
   34 |         assertEquals(125L,SourceProbeTimePolicy.liveDirectoryPacingMillis(4096L,0L));
   35 |         assertEquals(75L,SourceProbeTimePolicy.liveDirectoryPacingMillis(4096L,50L));
   36 |         assertEquals(0L,SourceProbeTimePolicy.liveDirectoryPacingMillis(4096L,200L));
   37 |         assertEquals(8000L,SourceProbeTimePolicy.liveDirectoryPacingMillis(Long.MAX_VALUE,0L));
   38 |     }
   39 | 
   40 |     @Test public void stableViewingLeaseExpiresAndRevokesOnNewPlaybackOrStop() {
   41 |         SourceUpdateActivityPolicy.setActivityActive(true);
   42 |         SourceUpdateActivityPolicy.setPlaybackActive(true);
   43 |         try {
   44 |             assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
   45 |             SourceUpdateActivityPolicy.reportStableLive(1000L);
   46 |             assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
   47 |             assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(16000L));
   48 |             SourceUpdateActivityPolicy.reportStableLive(15000L);
   49 |             assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(16000L));
   50 |             long generation=SourceUpdateActivityPolicy.stableRevocationGeneration();
   51 |             SourceUpdateActivityPolicy.revokeStableLive();
   52 |             assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(16001L));
   53 |             assertEquals(generation+1,SourceUpdateActivityPolicy.stableRevocationGeneration());
   54 |             SourceUpdateActivityPolicy.reportStableLive(17000L);
   55 |             SourceUpdateActivityPolicy.setPlaybackActive(true);
   56 |             assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(17001L));
   57 |             SourceUpdateActivityPolicy.reportStableLive(18000L);
   58 |             SourceUpdateActivityPolicy.setActivityActive(false);
   59 |             assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(18001L));
   60 |         } finally {
   61 |             SourceUpdateActivityPolicy.setPlaybackActive(false);
   62 |             SourceUpdateActivityPolicy.setActivityActive(false);
   63 |         }
   64 |     }
   65 | 
   66 |     @Test public void initialAddressRequiresFullBudgetBeforeStarting() {
   67 |         assertEquals(16000L, SourceProbeTimePolicy.initialAddressDeadline(91000L,1000L));
   68 |         assertEquals(16000L, SourceProbeTimePolicy.initialAddressDeadline(16000L,1000L));
   69 |         assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(15999L,1000L));
   70 |         assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(1000L,1001L));
   71 |         assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(1000L,-1L));
   72 |     }
   73 | 
   74 |     @Test public void observedPauseRemainsLatchedAfterViewerLeaves() {
   75 |         java.util.concurrent.atomic.AtomicBoolean viewing = new java.util.concurrent.atomic.AtomicBoolean();
   76 |         java.util.function.BooleanSupplier pause = SourceProbeTimePolicy.latchPause(viewing::get);
   77 |         assertFalse(pause.getAsBoolean());
   78 |         viewing.set(true);
   79 |         assertTrue(pause.getAsBoolean());
   80 |         viewing.set(false);
   81 |         assertTrue(pause.getAsBoolean());
   82 |         assertFalse(SourceProbeTimePolicy.latchPause(viewing::get).getAsBoolean());
   83 |     }
   84 | 
   85 |     @Test public void shortPlaybackStartIsVisibleEvenBetweenProbeChecks() {
   86 |         SourceUpdateActivityPolicy.setPlaybackActive(false);
   87 |         long start = SourceUpdateActivityPolicy.playbackStartGeneration();
   88 |         java.util.function.BooleanSupplier pause = SourceProbeTimePolicy.latchPause(
   89 |             () -> SourceUpdateActivityPolicy.playbackStartGeneration() != start);
   90 |         try {
   91 |             SourceUpdateActivityPolicy.setPlaybackActive(true);
   92 |             SourceUpdateActivityPolicy.setPlaybackActive(false);
   93 |             assertTrue(pause.getAsBoolean());
   94 |         } finally { SourceUpdateActivityPolicy.setPlaybackActive(false); }
   95 |     }
   96 | 
   97 |     @Test public void repeatedActiveSignalsDoNotGenerateNewStarts() {
   98 |         SourceUpdateActivityPolicy.setActivityActive(false);
   99 |         long start = SourceUpdateActivityPolicy.activityStartGeneration();
  100 |         try {
  101 |             SourceUpdateActivityPolicy.setActivityActive(true);
  102 |             SourceUpdateActivityPolicy.setActivityActive(true);
  103 |             assertEquals(start + 1, SourceUpdateActivityPolicy.activityStartGeneration());
  104 |         } finally { SourceUpdateActivityPolicy.setActivityActive(false); }
  105 |     }
  106 | 
  107 |     @Test public void cooperativePauseIsDistinctFromNetworkFailure() throws Exception {
  108 |         SourceProbeTimePolicy.checkPaused(() -> false);
  109 |         try {
  110 |             SourceProbeTimePolicy.checkPaused(() -> true);
  111 |             fail("Expected a cooperative pause");
  112 |         } catch (SourceProbeTimePolicy.PausedException expected) {
  113 |             assertTrue(expected instanceof java.io.IOException);
  114 |         }
  115 |     }
  116 | 
  117 |     @Test public void playlistParserStopsWithoutCompletingThePage() throws Exception {
  118 |         java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
  119 |         String playlist = "#EXTM3U\n#EXTINF:-1,First\n[URL_REDACTED]";
  120 |         try {
  121 |             PlaylistParser.parseEach(new java.io.StringReader(playlist), 0, 10,
  122 |                 item -> count.incrementAndGet(), () -> count.get() > 0);
  123 |             fail("Expected a cooperative pause");
  124 |         } catch (SourceProbeTimePolicy.PausedException expected) {
  125 |             assertEquals(1, count.get());
  126 |         }
  127 |     }
  128 | 
  129 |     @Test public void publicationPauseThrowsUncheckedToTriggerRollback() {
  130 |         SourceProbeTimePolicy.checkPublicationPaused(() -> false);
  131 |         try {
  132 |             SourceProbeTimePolicy.checkPublicationPaused(() -> true);
  133 |             fail("Expected publication rollback signal");
  134 |         } catch (SourceProbeTimePolicy.PublicationPausedException expected) {
  135 |             assertTrue(expected instanceof RuntimeException);
  136 |         }
  137 |     }
  138 | 
  139 |     @Test public void usesPerRequestMaximumWhenDeadlineIsFarAway() {
  140 |         assertEquals(2500, SourceProbeTimePolicy.boundedTimeoutMillis(20_000L, 1_000L, 2500));
  141 |     }
  142 | 
  143 |     @Test public void clipsTimeoutToRemainingDeadline() {
  144 |         assertEquals(400, SourceProbeTimePolicy.boundedTimeoutMillis(1_400L, 1_000L, 2500));
  145 |     }
  146 | 
  147 |     @Test public void returnsZeroWhenDeadlineHasExpired() {
  148 |         assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(1_000L, 1_000L, 3000));
  149 |         assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(900L, 1_000L, 3000));
  150 |     }
  151 | 
  152 |     @Test public void rejectsNonPositiveRequestMaximum() {
  153 |         assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(5_000L, 1_000L, 0));
  154 |         assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(5_000L, 1_000L, -10));
  155 |     }
  156 | }
  157 | 
```


## app/src/main/java/com/wodedianshi/tv/MainActivity.java — 原始行 1395–1626

```text
 1395 |   private void scheduleAutomaticSourceUpdates(){
 1396 |     WorkManager wm=WorkManager.getInstance(this);
 1397 |     android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
 1398 |     boolean enabled=prefs.getBoolean("auto_source_update",true);
 1399 |     if(!enabled){
 1400 |       wm.cancelUniqueWork("mytv_periodic_source_update");
 1401 |       wm.cancelUniqueWork("mytv_startup_source_refresh");
 1402 |       return;
 1403 |     }
 1404 |     Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
 1405 |     // The one-time full rebuild publishes an entire catalogue before routine
 1406 |     // maintenance is eligible. Existing channels remain usable during this build.
 1407 |     if(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(
 1408 |         enabled,roomFullRebuildCompletedAt)){
 1409 |       wm.cancelUniqueWork("mytv_periodic_source_update");
 1410 |       // The first discovery is part of first-run setup, not a routine background
 1411 |       // refresh. Let it run while the catalogue screen is open; playback-active
 1412 |       // protection inside the Worker still pauses it if the user starts watching.
 1413 |       OneTimeWorkRequest startupRefresh=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
 1414 |           .setConstraints(constraints)
 1415 |           .setInputData(new Data.Builder().putBoolean("manual",true).putBoolean("initial_discovery_chain",true).build())
 1416 |           .build();
 1417 |       wm.enqueueUniqueWork("mytv_startup_source_refresh",ExistingWorkPolicy.KEEP,startupRefresh);
 1418 |       return; // Never run a routine refresh concurrently with the first full build.
 1419 |     }
 1420 |     if(!SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(
 1421 |         enabled,roomFullRebuildCompletedAt))return;
 1422 |     PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(SourceUpdateWorker.class,
 1423 |         SourceUpdateSchedulePolicy.ROUTINE_ELIGIBILITY_CHECK_DAYS,TimeUnit.DAYS)
 1424 |         .setConstraints(constraints).build();
 1425 |     // The daily job only checks whether a weekly maintenance pass is due.
 1426 |     // The worker exits before discovery while busy or not due.
 1427 |     wm.enqueueUniquePeriodicWork("mytv_periodic_source_update",ExistingPeriodicWorkPolicy.UPDATE,request);
 1428 |   }
 1429 |   private void startInitialDiscoveryProgressTicker(){
 1430 |     stopInitialDiscoveryProgressTicker();
 1431 |     if(initialDiscoveryProgressPanel==null)return;
 1432 |     if(roomFullRebuildCompletedAt>0L){
 1433 |       initialDiscoveryProgressPanel.setVisibility(View.GONE);
 1434 |       return;
 1435 |     }
 1436 |     initialDiscoveryProgressPanel.setVisibility(View.VISIBLE);
 1437 |     initialDiscoveryProgressTicker=new Runnable(){
 1438 |       @Override public void run(){
 1439 |         if(!activityStarted||isFinishing()||isDestroyed()||initialDiscoveryProgressPanel==null)return;
 1440 |         requestInitialDiscoveryProgress();
 1441 |         if(roomFullRebuildCompletedAt<=0L)
 1442 |           uiHandler.postDelayed(this,4000L);
 1443 |       }
 1444 |     };
 1445 |     uiHandler.post(initialDiscoveryProgressTicker);
 1446 |   }
 1447 | 
 1448 |   private void stopInitialDiscoveryProgressTicker(){
 1449 |     if(initialDiscoveryProgressTicker!=null){
 1450 |       uiHandler.removeCallbacks(initialDiscoveryProgressTicker);
 1451 |       initialDiscoveryProgressTicker=null;
 1452 |     }
 1453 |   }
 1454 | 
 1455 |   private void requestInitialDiscoveryProgress(){
 1456 |     if(initialDiscoveryProgressPanel==null||!activityStarted
 1457 |         ||!initialDiscoveryProgressReadPending.compareAndSet(false,true))return;
 1458 |     try{
 1459 |       worker.execute(()->{
 1460 |         try{
 1461 |           final android.content.Context context=getApplicationContext();
 1462 |           final String prefix="source_initial_progress_";
 1463 |           java.util.ArrayList<String> keys=new java.util.ArrayList<>();
 1464 |           keys.add("source_full_rebuild_completed_at");
 1465 |           keys.add(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY);
 1466 |           keys.add("source_initial_index_attempted");
 1467 |           keys.add("source_initial_index_bypassed");
 1468 |           keys.add("source_initial_index_ready");
 1469 |           keys.add("source_initial_index_entries");
 1470 |           for(String suffix:new String[]{"done_feeds","total_feeds","china_done_feeds",
 1471 |               "china_total_feeds","china_validated_urls","staged_channels","checked_urls",
 1472 |               "validated_urls","timeout_urls","unavailable_feeds","feed_index","feed_cursor","feed_entries",
 1473 |               "percent","phase"})keys.add(prefix+suffix);
 1474 |           final Map<String,String> snapshot=readRoomStateValues(context,keys);
 1475 |           final long completed=readStateLong(snapshot,"source_full_rebuild_completed_at",0L);
 1476 |           final int previewLevel=(int)readStateLong(snapshot,FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0L);
 1477 |           final int indexAttempted=(int)readStateLong(snapshot,"source_initial_index_attempted",0L);
 1478 |           final boolean indexBypassed="true".equals(snapshot.get("source_initial_index_bypassed"));
 1479 |           final int indexReady=(int)readStateLong(snapshot,"source_initial_index_ready",0L);
 1480 |           final long indexEntries=readStateLong(snapshot,"source_initial_index_entries",0L);
 1481 |           final int done=(int)readStateLong(snapshot,prefix+"done_feeds",0L);
 1482 |           final int total=(int)readStateLong(snapshot,prefix+"total_feeds",0L);
 1483 |           final int chinaDone=(int)readStateLong(snapshot,prefix+"china_done_feeds",0L);
 1484 |           final int chinaTotal=(int)readStateLong(snapshot,prefix+"china_total_feeds",0L);
 1485 |           final int chinaValidated=(int)readStateLong(snapshot,prefix+"china_validated_urls",0L);
 1486 |           final int staged=(int)readStateLong(snapshot,prefix+"staged_channels",0L);
 1487 |           final int checked=(int)readStateLong(snapshot,prefix+"checked_urls",0L);
 1488 |           final int validated=(int)readStateLong(snapshot,prefix+"validated_urls",0L);
 1489 |           final int timeouts=(int)readStateLong(snapshot,prefix+"timeout_urls",0L);
 1490 |           final int unavailable=(int)readStateLong(snapshot,prefix+"unavailable_feeds",0L);
 1491 |           final int feedIndex=(int)readStateLong(snapshot,prefix+"feed_index",0L);
 1492 |           final int cursor=(int)readStateLong(snapshot,prefix+"feed_cursor",0L);
 1493 |           final int entries=(int)readStateLong(snapshot,prefix+"feed_entries",0L);
 1494 |           final int percent=(int)readStateLong(snapshot,prefix+"percent",0L);
 1495 |           final String phase=snapshot.getOrDefault(prefix+"phase","尚未开始首次完整抓源");
 1496 |           runOnUiThread(()->{
 1497 |             if(isFinishing()||isDestroyed()||!activityStarted
 1498 |                 ||initialDiscoveryProgressPanel==null)return;
 1499 |             if(startupDataReady&&previewLevel!=displayedInitialPreviewLevel){
 1500 |               displayedInitialPreviewLevel=previewLevel;
 1501 |               if(previewLevel>0)reloadChannelsAfterBackgroundUpdateIfNeeded();
 1502 |             }
 1503 |             if(completed>0L){
 1504 |               roomFullRebuildCompletedAt=completed;
 1505 |               reloadChannelsAfterBackgroundUpdateIfNeeded();
 1506 |               initialDiscoveryProgressBar.setProgress(100);
 1507 |               initialDiscoveryProgressTitle.setText("目录处理完成 · 100%");
 1508 |               initialDiscoveryProgressDetail.setText("完整频道快照已保存 · 已整理 "
 1509 |                   +Math.max(0,staged)+" 个频道"+"\n中国来源初筛通过 "
 1510 |                   +Math.max(0,chinaValidated)+" 次 · 全部来源 "
 1511 |                   +Math.max(0,validated)+" 次"+"\n暂不可用来源 "
 1512 |                   +Math.max(0,unavailable)+" 个 · 超时未确认 "+Math.max(0,timeouts)+" 次，待补查"
 1513 |                   +"\n100%不代表全部频道可播，详见设置→查看更新状态");
 1514 |               stopInitialDiscoveryProgressTicker();
 1515 |               // Give the user a confirmation, then restore the channel list space.
 1516 |               uiHandler.postDelayed(()->{
 1517 |                 if(initialDiscoveryProgressPanel!=null&&roomFullRebuildCompletedAt>0L)
 1518 |                   initialDiscoveryProgressPanel.setVisibility(View.GONE);
 1519 |               },12000L);
 1520 |               return;
 1521 |             }
 1522 |             initialDiscoveryProgressPanel.setVisibility(View.VISIBLE);
 1523 |             initialDiscoveryProgressBar.setProgress(Math.max(0,Math.min(99,percent)));
 1524 |             int bootstrapLimit=FullDiscoveryProgressPolicy.bootstrapIndexLimit(SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
 1525 |             boolean indexing=!indexBypassed&&FullDiscoveryProgressPolicy.isMainlandIndexPhase(indexAttempted,
 1526 |                 SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,checked,indexEntries,false);
 1527 |             initialDiscoveryProgressTitle.setText(indexing
 1528 |                 ?"首批中国目录准备 · "+Math.max(0,indexAttempted)+"/"+bootstrapLimit
 1529 |                 :"首次完整抓源 · "+Math.max(0,Math.min(99,percent))+"%");
 1530 |             String fraction=entries>0?" · 当前源 "+Math.min(cursor,entries)+"/"+entries:"";
 1531 |             String liveStage=SourceUpdateWorker.currentInitialLiveStage();
 1532 |             initialDiscoveryProgressDetail.setText(
 1533 |                 (indexing?"首批目录准备 "+indexAttempted+"/"+bootstrapLimit+" · 已索引 "+indexReady+" 个，原始条目 "+indexEntries:
 1534 |                 (total>0?"已处理目录 "+Math.max(0,done)+"/"+total
 1535 |                     +" · 中国大陆 "+Math.max(0,chinaDone)+"/"+Math.max(0,chinaTotal)
 1536 |                     :"正在准备来源列表"))
 1537 |                 +"\n已整理频道 "+Math.max(0,staged)+(previewLevel>0?" · 阶段目录已发布":"")+" · 中国初筛通过 "
 1538 |                     +Math.max(0,chinaValidated)+" 次"
 1539 |                 +"\n初筛地址 "+Math.max(0,checked)+" · 失败目录 "
 1540 |                     +Math.max(0,unavailable)+" 个 · 超时 "+Math.max(0,timeouts)+" 次"
 1541 |                 +"\n"+(liveStage.isEmpty()?phase+(total>0?" · 第 "+(Math.max(0,feedIndex)+1)+" 个"+fraction:""):liveStage));
 1542 |           });
 1543 |         }catch(Throwable error){
 1544 |           android.util.Log.w("MyTVDiscoveryProgress","read initial progress failed",error);
 1545 |         }finally{
 1546 |           initialDiscoveryProgressReadPending.set(false);
 1547 |         }
 1548 |       });
 1549 |     }catch(RuntimeException ignored){initialDiscoveryProgressReadPending.set(false);}
 1550 |   }
 1551 | 
 1552 |   private static Map<String,String> readRoomStateValues(Context context,List<String> keys){
 1553 |     Map<String,String> values=new HashMap<>();
 1554 |     for(com.wodedianshi.tv.db.AppStateEntity row:
 1555 |         com.wodedianshi.tv.db.RoomDatabaseProvider.get(context).appStateDao().getValues(keys))
 1556 |       values.put(row.key,row.stateValue);
 1557 |     return values;
 1558 |   }
 1559 |   private static long readStateLong(Map<String,String> state,String key,long fallback){
 1560 |     try{return Long.parseLong(state.getOrDefault(key,Long.toString(fallback)));}
 1561 |     catch(NumberFormatException invalid){return fallback;}
 1562 |   }
 1563 | 
 1564 |   private void observeStartupSourceUpdateResults(){
 1565 |     // Apply the guarded catalogue snapshot as soon as the startup refresh finishes.
 1566 |     // This avoids requiring the user to exit and relaunch to see newly found channels.
 1567 |     WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("mytv_startup_source_refresh")
 1568 |         .observe(this,workInfos->{
 1569 |           if(workInfos==null)return;
 1570 |           requestInitialDiscoveryProgress();
 1571 |           boolean allSucceeded=!workInfos.isEmpty();
 1572 |           for(androidx.work.WorkInfo workInfo:workInfos){
 1573 |             if(workInfo.getState()!=androidx.work.WorkInfo.State.SUCCEEDED){
 1574 |               allSucceeded=false;
 1575 |               break;
 1576 |             }
 1577 |           }
 1578 |           // A first batch may succeed while it has appended more work to the
 1579 |           // unique chain. Reload only after the entire discovery chain completes.
 1580 |           if(allSucceeded){
 1581 |             worker.execute(()->{
 1582 |               long completed=parseRoomLong("source_full_rebuild_completed_at",0L);
 1583 |               runOnUiThread(()->{
 1584 |                 roomFullRebuildCompletedAt=completed;
 1585 |                 if(completed>0L)scheduleAutomaticSourceUpdates();
 1586 |                 requestInitialDiscoveryProgress();
 1587 |                 reloadChannelsAfterBackgroundUpdateIfNeeded();
 1588 |               });
 1589 |             });
 1590 |           }
 1591 |         });
 1592 |   }
 1593 |   private void toggleAutomaticSourceUpdates(){
 1594 |     android.content.SharedPreferences p=getSharedPreferences("mytv",0);
 1595 |     boolean enabled=!p.getBoolean("auto_source_update",true);
 1596 |     p.edit().putBoolean("auto_source_update",enabled).apply();
 1597 |     if(enabled)scheduleAutomaticSourceUpdates();else{
 1598 |       WorkManager wm=WorkManager.getInstance(this);
 1599 |       wm.cancelUniqueWork("mytv_periodic_source_update");
 1600 |       wm.cancelUniqueWork("mytv_startup_source_refresh");
 1601 |     }
 1602 |     toast(enabled?"后台自动更新已开启（完整建库后约每 7 天、空闲时维护）":"后台自动更新已关闭；仍可在设置中手动更新");
 1603 |   }
 1604 |   private void startManualSourceUpdate(){
 1605 |     Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
 1606 |     boolean needsInitialFullRebuild=roomFullRebuildCompletedAt<=0L;
 1607 |     OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
 1608 |         .setConstraints(constraints)
 1609 |         .setInputData(new Data.Builder().putBoolean("manual",true)
 1610 |             .putBoolean("initial_discovery_chain",needsInitialFullRebuild).build())
 1611 |         .build();
 1612 |     WorkManager manager=WorkManager.getInstance(this);
 1613 |     if(needsInitialFullRebuild){
 1614 |       // The startup and manual entry points must share one unique chain: two
 1615 |       // simultaneous first-run rebuilds would race on the same Room staging rows
 1616 |       // and feed cursors. KEEP reuses an already-running startup rebuild.
 1617 |       manager.enqueueUniqueWork("mytv_startup_source_refresh",ExistingWorkPolicy.KEEP,request);
 1618 |       toast("已提交首次全量频道整理；可以在频道列表查看真实完成百分比");
 1619 |       startInitialDiscoveryProgressTicker();
 1620 |       return;
 1621 |     }
 1622 |     manager.enqueueUniqueWork("mytv_manual_source_update",ExistingWorkPolicy.KEEP,request);
 1623 |     toast("已请求完整频道轮检；已有任务将继续，播放优先，可在更新状态查看进度");
 1624 |     // The first WorkInfo is only one bounded slice, not the whole refresh.
 1625 |     // Existing Room reload-on-resume and status view consume committed state.
 1626 |   }
```


## app/src/main/java/com/wodedianshi/tv/MainActivity.java — 原始行 5799–5869

```text
 5799 |   private void schedulePlaybackWatchdog(){
 5800 |     cancelPlaybackWatchdog();
 5801 |     if(player==null||player.getPlaybackState()!=Player.STATE_READY||!player.getPlayWhenReady())return;
 5802 |     final String expectedMediaId=currentMediaId;
 5803 |     final int expectedChannel=selected;
 5804 |     playbackWatchdog=()->{
 5805 |       playbackWatchdog=null;
 5806 |       if(player==null||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId))return;
 5807 |       if(!player.getPlayWhenReady())return;
 5808 |       int state=player.getPlaybackState();
 5809 |       // 缓冲由独立计时器处理；READY 但没有真正推进时间轴也必须继续监测。
 5810 |       if(state==Player.STATE_BUFFERING){if(bufferingFallback==null)scheduleBufferingFallback();return;}
 5811 |       if(state!=Player.STATE_READY)return;
 5812 |       long now=android.os.SystemClock.elapsedRealtime();
 5813 |       long position=player.getCurrentPosition();
 5814 |       if(watchdogLastPosition<0||position>watchdogLastPosition+250){
 5815 |         watchdogLastPosition=position;
 5816 |         watchdogLastAdvanceAt=now;
 5817 |       }else if(PlaybackRecoveryPolicy.isTimelineStalled(now,watchdogLastAdvanceAt,45000L)){
 5818 |         recoverFromSilentFreeze("播放时间轴连续 45 秒没有推进");
 5819 |         return;
 5820 |       }
 5821 |       // A live source can leave ExoPlayer in READY while its video pipeline stalls.
 5822 |       // Startup success is gated by the rendered-first-frame analytics event; after
 5823 |       // that, decoder metadata activity is used as a lightweight stall signal.
 5824 |       // Start monitoring as soon as a supported video track is selected.
 5825 |       if(PlaybackRecoveryPolicy.isVideoOutputStalled(videoTrackSelected,sourceFirstFrameRecorded,
 5826 |           videoTrackSelectedAt,lastVideoPipelineActivityAt,now,20000L)){
 5827 |         recoverFromSilentFreeze(sourceFirstFrameRecorded
 5828 |             ?"检测到视频帧连续 20 秒没有输出"
 5829 |             :"播放器已连接，但 20 秒内没有渲染出首帧画面");
 5830 |         return;
 5831 |       }
 5832 |       // After two minutes of uninterrupted playback, replenish the failover budget.
 5833 |       // A source that was healthy for a long time should not consume the startup retry budget forever.
 5834 |       boolean confirmedOutput=TrackHealthPolicy.hasConfirmedTvOutput(audioTrackPresent,
 5835 |           audioTrackSupported,audioTrackSelected,audioPositionAdvancing,videoTrackPresent,
 5836 |           videoTrackSupported,videoTrackSelected,sourceFirstFrameRecorded);
 5837 |       if(confirmedOutput&&sourceReadyAt>0L&&now-sourceReadyAt>=30000L
 5838 |           &&now-lastStableDiscoveryInteraction>=20000L
 5839 |           &&watchdogLastAdvanceAt>0L&&now-watchdogLastAdvanceAt<5000L
 5840 |           &&lastVideoPipelineActivityAt>0L&&now-lastVideoPipelineActivityAt<5000L
 5841 |           &&player.isPlaying()&&activityStarted&&fullScreenPlayback&&hasWindowFocus()
 5842 |           &&!fullscreenControlsVisible&&!fullscreenRemoteDialogOpen
 5843 |           &&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())
 5844 |           &&currentVodPanel==null&&!isAnyPlaybackLockActive()
 5845 |           &&!libVlcReleaseInProgress&&!media3ReleaseUnconfirmed){
 5846 |         SourceUpdateActivityPolicy.reportStableLive(now);
 5847 |       }else SourceUpdateActivityPolicy.revokeStableLive();
 5848 |       if(!sourceStableRecorded&&confirmedOutput&&sourceReadyAt>0L
 5849 |           &&now-sourceReadyAt>=30000L&&now-watchdogLastAdvanceAt<15000L
 5850 |           &&lastVideoPipelineActivityAt>0L&&now-lastVideoPipelineActivityAt<20000L){
 5851 |         sourceStableRecorded=true;
 5852 |         String goodUrl=currentSourceUrl();
 5853 |         recordSourceMetric(goodUrl,"stable",0L);
 5854 |         if(selected>=0&&selected<channels.size())
 5855 |           SourceMemoryStore.saveLastGood(MainActivity.this,
 5856 |               SourceMemoryStore.channelIdentityKey(identity(channels.get(selected))),goodUrl);
 5857 |       }
 5858 |       if(TrackHealthPolicy.canRefreshRecoveryBudget(confirmedOutput,sourceReadyAt,
 5859 |           watchdogLastAdvanceAt,lastVideoPipelineActivityAt,now)){
 5860 |         automaticSwitchCount=0;
 5861 |         failedEndpointsThisPass.clear();
 5862 |         automaticChannelSwitchCount=0;
 5863 |         sameSourceRecoveryCount=0;
 5864 |         sourceReadyAt=now;
 5865 |       }
 5866 |       schedulePlaybackWatchdog();
 5867 |     };
 5868 |     uiHandler.postDelayed(playbackWatchdog,10000L);
 5869 |   }
```
