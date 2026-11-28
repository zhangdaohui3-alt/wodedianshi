package com.wodedianshi.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.*;
import org.json.JSONArray;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import androidx.work.WorkManager;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.ExistingWorkPolicy;
import androidx.work.Data;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class MainActivity extends androidx.activity.ComponentActivity {
  private static final int LIVE_PAGE_SIZE=48;
  // Read by Media3's playback-thread frame callback as well as the UI thread.
  private volatile ExoPlayer player;
  private boolean decoderInventoryRecorded=false;
  private PlayerView video;
  private FrameLayout liveStage;
  private View livePictureCover;
  private final LivePictureGate livePictureGate=new LivePictureGate();
  private VLCVideoLayout vlcVideoLayout;
  private volatile LibVlcPlaybackEngine libVlcEngine;
  // Serialize every new live tune behind asynchronous LibVLC teardown. The
  // callbacks are UI-thread owned; stale tune generations self-discard.
  private boolean libVlcReleaseInProgress=false;
  private int pendingVlcTeardowns=0;
  private static final java.util.concurrent.atomic.AtomicInteger PROCESS_VLC_TEARDOWNS=new java.util.concurrent.atomic.AtomicInteger();
  // Process-wide terminal quarantine, distinct from in-flight teardown count.
  private static volatile boolean decoderReleaseUnconfirmed;
  private static final DecoderOwnership DECODER_OWNERSHIP=new DecoderOwnership();
  private final Object decoderOwnerToken=new Object();

  private boolean acquireDecoderOwnership(){
    return !decoderReleaseUnconfirmed&&PROCESS_VLC_TEARDOWNS.get()==0
        &&DECODER_OWNERSHIP.acquire(decoderOwnerToken);
  }
  private void releaseDecoderOwnershipIfIdle(){
    if(player==null&&vodPlayer==null&&libVlcEngine==null&&vodFallbackBackend==null
        &&pendingVlcTeardowns==0&&!decoderReleaseUnconfirmed)
      DECODER_OWNERSHIP.release(decoderOwnerToken);
  }
  private void quarantineMedia3Release(String detail){
    decoderReleaseUnconfirmed=true;
    DECODER_OWNERSHIP.quarantine(decoderOwnerToken);
    // A late release error must close the same admission gate immediately.
    setSourceUpdatePlaybackActive(true);
    saveLiveDiagnostic("MEDIA3_RELEASE_UNCONFIRMED",detail);
  }
  // Media3 may report a timeout through onPlayerError rather than throw.
  // Attach this independent listener before old-player callbacks are revoked.
  private void watchMedia3Release(ExoPlayer old){
    if(old==null)return;
    try{
      old.addListener(new Player.Listener(){
        @Override public void onPlayerError(PlaybackException error){
          Throwable cause=error;
          for(int i=0;i<12&&cause!=null;i++,cause=cause.getCause()){
            if(cause instanceof androidx.media3.exoplayer.ExoTimeoutException){
              int op=((androidx.media3.exoplayer.ExoTimeoutException)cause).timeoutOperation;
              if(op==androidx.media3.exoplayer.ExoTimeoutException.TIMEOUT_OPERATION_RELEASE
                  ||op==androidx.media3.exoplayer.ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE)
                quarantineMedia3Release("timeout operation="+op);
              break;
            }
          }
        }
      });
    }catch(RuntimeException failure){quarantineMedia3Release("release listener registration failed");}
  }
  // stop() releases loaded playback resources; release() disposes the player.
  // Clearing the playlist on every successful stop sends an unnecessary
  // timeline mutation to the playback thread. Keep it as a recovery step
  // if stop throws, and always attempt the final release on the owner looper.
  private long stopMedia3BeforeRelease(ExoPlayer old,String mode){
    try{
      noteUiDecoderStage(mode+" Media3 stop begin");
      old.stop();
      return 0L;
    }catch(RuntimeException|LinkageError error){
      saveLiveDiagnostic(mode+"_STOP_RELEASE_ERROR",error.getClass().getSimpleName());
    }
    long started=android.os.SystemClock.elapsedRealtime();
    try{
      noteUiDecoderStage(mode+" Media3 emergency clear begin");
      old.clearMediaItems();
    }catch(RuntimeException|LinkageError error){
      saveLiveDiagnostic(mode+"_CLEAR_RELEASE_ERROR",error.getClass().getSimpleName());
    }
    return android.os.SystemClock.elapsedRealtime()-started;
  }
  private void releaseMedia3Confirmed(ExoPlayer old){
    long start=android.os.SystemClock.elapsedRealtime();
    try{
      noteUiDecoderStage("Media3 release begin");
      old.release();
      noteUiDecoderStage("Media3 release returned");
      // The default release timeout is 500ms. A callback may be queued rather
      // than delivered synchronously; treat reaching that bound conservatively.
      if(android.os.SystemClock.elapsedRealtime()-start>=
          androidx.media3.exoplayer.ExoPlayer.Builder.DEFAULT_RELEASE_TIMEOUT_MS)
        quarantineMedia3Release("release reached timeout budget");
    }catch(RuntimeException|LinkageError failure){
      quarantineMedia3Release(failure.getClass().getSimpleName());
    }finally{releaseDecoderOwnershipIfIdle();}
  }
  private Runnable pendingVodStartAfterRelease;
  private long vodStartRequestGeneration;
  private void revokePendingVodStart(){
    ++vodStartRequestGeneration;
    if(pendingVodStartAfterRelease!=null){
      libVlcReleaseCallbacks.remove(pendingVodStartAfterRelease);
      pendingVodStartAfterRelease=null;
    }
  }
  private Runnable liveVlcReleaseContinuation;
  private void revokePendingLiveTune(){
    if(liveVlcReleaseContinuation!=null){
      libVlcReleaseCallbacks.remove(liveVlcReleaseContinuation);
      liveVlcReleaseContinuation=null;
    }
  }
  private Runnable libVlcReleaseNotice;
  private final ArrayList<Runnable> libVlcReleaseCallbacks=new ArrayList<>();
  private boolean usingLibVlc=false;
  private boolean libVlcVideoOutputReady=false;
  private int libVlcAudioTrackCheckAttempts=0;
  private boolean libVlcAudioTrackReady;
  // Consecutive healthy checks permit low-priority discovery, not source promotion.
  private int libVlcStableChecks=0;
  private long libVlcLastHealthClockMs,libVlcLastHealthAt;
  // UI-thread epoch; a queued native observation must not cross a health reset.
  private long libVlcHealthEpoch;
  private final TrackHealthPolicy.VlcCounterProgress libVlcVideoProgress=new TrackHealthPolicy.VlcCounterProgress();
  private final TrackHealthPolicy.VlcCounterProgress libVlcAudioProgress=new TrackHealthPolicy.VlcCounterProgress();
  private boolean libVlcStableRecorded=false;
  private boolean libVlcAttemptedForSource=false;
  private Runnable libVlcStartTimeout;
  private Runnable libVlcWatchdog;
  private Runnable libVlcBufferingTimeout;
  private boolean libVlcRebuffering;
  private LinearLayout rootLayout, categoryPane, channelPane, mainPane;
  private ScrollView channelScroll;
  private TextView playbackInfo;
  // Also checked by the isolated geographic directory worker before any download.
  private volatile boolean fullScreenPlayback = false;
  private boolean fullscreenRemoteDialogOpen = false;
  private PopupWindow fullscreenMenuPopup;
  private long menuSelectedFocusGeneration=-1L;
  private TextView fullscreenMenuButton;
  private TextView fullscreenSourceButton;
  private TextView fullscreenFavoriteButton;
  private TextView fullscreenActionsButton;
  private TextView fullscreenBlockSourceButton;
  private DepartedLiveSource recentDepartedLiveSource;
  // Accepted picture output belongs to this tune, not a decoder's teardown flags.
  private String lastOutputMediaId="";
  private LinearLayout categoryMenuItems;
  private Button fullscreenAspectMenuItem;
  private final Runnable hideFullscreenControls=()->{if(!this.fullscreenControlGesture)hideFullscreenControlsNow();};
  private boolean fullscreenControlsVisible=false;
  private boolean fullscreenControlGesture;
  private long lastChannelZapKeyAt=0L;
  private Runnable revealVodControlsAction;
  private Runnable backVodToCatalogueAction;
  private TextView channelZapOverlay;
  private TextView networkSpeedOverlay;
  private Runnable networkSpeedUpdater;
  // Detect pauses in Activity input responsiveness while the video decoder may
  // continue playing normally. This is a delayed UI heartbeat, not a polling
  // thread or a playback watchdog.
  private Runnable liveUiStallProbe;
  // Independent diagnostic sampler: a delayed Handler callback alone cannot
  // capture the main thread's stack while it is blocked.
  private volatile long mainThreadSampleGeneration=0L;
  // UI-owned breadcrumb; the ANR sampler reads it without waiting on the UI.
  private volatile String lastUiDecoderStage="not started";
  private void noteUiDecoderStage(String phase){
    if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
      lastUiDecoderStage=phase+" uptimeMs="+android.os.SystemClock.uptimeMillis();
  }
  private volatile Thread mainThreadSampleWorker;
  private final Runnable hideChannelZapOverlay=()->{if(channelZapOverlay!=null)channelZapOverlay.setVisibility(View.GONE);};
  private boolean manualSourceSwitch = false;
  private Button hoveredVodCategoryButton;
  private volatile boolean startupDataReady=false;
  // Remember the most recent category tap while the saved catalogue is being restored.
  // Replay it once the directory is ready instead of silently discarding the tap.
  private volatile String pendingStartupCategory=null;
  private View hoveredVodCard;
  private LinearLayout list;
  private TextView info;
  private EditText search;
  private int livePageIndex=0;
  // A deliberate page-button action must not be overridden by the selected channel's page.
  private boolean preserveRequestedLivePageOnce=false;
  private Button livePreviousPageButton,liveNextPageButton;
  private Button livePageStatus;
  // Compact, nonblocking first-install progress. No network access from UI.
  private LinearLayout initialDiscoveryProgressPanel;
  private TextView initialDiscoveryProgressTitle;
  private String initialDiscoveryProgressDetail="正在准备读取来源目录…";
  private AlertDialog initialDiscoveryDetailsDialog;
  private boolean initialResumeRequestPending;
  private String initialRecoveryProgressSignature;
  private long initialRecoveryUnchangedSince;
  private boolean initialRecoveryRequested;
  private String initialDiscoverySchedulerDetail="";
  private ProgressBar initialDiscoveryProgressBar;
  private Runnable initialDiscoveryProgressTicker;
  private int displayedInitialPreviewLevel,displayedInitialPreviewMilestone,displayedInitialPreviewCoreMask;
  private boolean initialPreviewReloadPending;
  private boolean initialFinalCataloguePending;
  private long initialFinalCatalogueVersion=-1L,initialFinalReloadRequestedAt;
  private long initialPreviewReloadRequestedAt;
  private volatile long appendedInitialPreviewVersion=-1L;
  private String initialDiscoveryTerminalStatus="";
  private boolean initialDiscoveryWorkRunning,initialDiscoveryWorkUnfinished,initialDiscoveryWorkObserved;
  private final java.util.concurrent.atomic.AtomicBoolean initialDiscoveryProgressReadPending=
      new java.util.concurrent.atomic.AtomicBoolean(false);
  private final ArrayList<Channel> channels = new ArrayList<>();
  // Trust the restored Room catalogue only after its complete snapshot and indexes are ready.
  // Incomplete in-memory data must never replace the last committed directory.
  private volatile boolean catalogueSnapshotTrusted=false;
  // Invalidates a queued reload when a newer foreground return supersedes it.
  private boolean activityStarted=false;
  private static volatile boolean crashHandlerInstalled=false;
  private boolean startupAutoResumePending=false;
  private Runnable startupAutoResumeTask;
  private int startupResumeIndex=-1;
  private Channel startupResumeChannel;
  // Keep country/state/city playlists scoped to the destination currently selected.
  private String activeGeoScope=null,activeGeoCountry="",activeGeoLabel="";
  // Dedicated local-area filter; keep the sidebar category as 地方 so a label
  // such as “全部河北频道” can never fall through to the global category.
  private boolean localRegionFilterActive=false;
  private String activeLocalProvince=null;
  private String activeLocalCity=null;
  // Synchronize in-memory favorites while the Room writer consumes detached state.
  private final Set<String> favorites = Collections.synchronizedSet(new HashSet<String>());
  // Durable business state is loaded from Room on the startup worker.
  private static final String ROOM_STATE_LAST_CHANNEL_IDENTITY="last_channel_identity";
  private static final String ROOM_STATE_LAST_CHANNEL_URL="last_channel_url";
  private static final String ROOM_STATE_LAST_CHANNEL_NAME="last_channel_name";
  private static final String ROOM_STATE_LAST_CHANNEL_GROUP="last_channel_group";
  private static final String ROOM_STATE_LAST_CHANNEL_TVG_ID="last_channel_tvg_id";
  private static final String ROOM_STATE_VOD_FAVORITES="vod_favorites_v1";
  private static final String ROOM_STATE_VOD_HISTORY="vod_history_v1";
  private static final LatestStateWriter<LastChannelState> LAST_CHANNEL_WRITER=new LatestStateWriter<>();
  private volatile LastChannelState roomLastChannel=new LastChannelState("","","","","");
  private static final class LastChannelState {
    final String identity,url,name,group,tvgId;
    LastChannelState(String identity,String url,String name,String group,String tvgId){
      this.identity=identity==null?"":identity;
      this.url=url==null?"":url;
      this.name=name==null?"":name;
      this.group=group==null?"":group;
      this.tvgId=tvgId==null?"":tvgId;
    }
    @Override public boolean equals(Object other){
      if(!(other instanceof LastChannelState))return false;
      LastChannelState s=(LastChannelState)other;
      return identity.equals(s.identity)&&url.equals(s.url)&&name.equals(s.name)
          &&group.equals(s.group)&&tvgId.equals(s.tvgId);
    }
    @Override public int hashCode(){return java.util.Objects.hash(identity,url,name,group,tvgId);}
  }
  private volatile String roomVodFavoritesJson="[]";
  private volatile String roomVodHistoryJson="[]";
  private volatile long roomFullRebuildCompletedAt=0L;
  // Fast indexes prevent quadratic duplicate scans as channel lists grow.
  private final HashMap<String,Channel> channelsByUrl = new HashMap<>();
  private final HashMap<String,Channel> channelsByIdentity = new HashMap<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  // Catalogue snapshots and JSON encoding must never run on the UI thread. One
  // serial writer coalesces bursts of remote-control actions into the newest state.
  private final ExecutorService cataloguePersistWorker = Executors.newSingleThreadExecutor();
  private final Object cataloguePersistLock = new Object();
  // Track and coalesce asynchronous Room catalogue persistence requests.
  private long cataloguePersistRequestedGeneration=0L;
  private long cataloguePersistCompletedGeneration=0L;
  private boolean cataloguePersistLastSuccess=true;
  private boolean cataloguePersistScheduled=false;
  // Keep large catalogue filtering and sorting off the UI thread. Results are
  // generation-checked so an older search/category cannot overwrite a newer one.
  private final ExecutorService catalogueFilterWorker = newLatestRequestWorker();
  // Worker-confined, bounded positional indexes. No duplicate Channel records.
  private final SnapshotCategoryIndex categorySnapshotIndex=new SnapshotCategoryIndex();
  private volatile SnapshotCategoryIndex.PreparedLocal preparedLocalSnapshot;
  // The directory filter publishes the one order shared by rows and remote zapping.
  private volatile RemoteChannelOrder cachedRemoteChannelOrder;
  private String directoryFilterInFlightKey;
  private final LiveDirectoryTouchGate liveDirectoryTouchGate=new LiveDirectoryTouchGate();
  private void publishFilteredDirectory(long generation,Runnable publication){
    if(isFinishing()||isDestroyed()||generation!=catalogueFilterGeneration)return;
    if(liveDirectoryTouchGate.defer(()->publishFilteredDirectory(generation,publication)))return;
    publication.run();
  }
  private void finishLiveDirectoryTouch(){
    Runnable pending=liveDirectoryTouchGate.end();
    // Let the Button's queued performClick run before replacing its view.
    if(pending!=null)uiHandler.post(pending);
  }
  // If a cache rebuild is needed during fullscreen zapping, queue the key off-thread
  // instead of rebuilding/sorting the entire directory on the Android UI thread.
  private int pendingRemoteZapSteps=0;
  private long pendingRemoteZapGeneration=-1L;
  private Runnable pendingRemoteZapDispatch;
  private Runnable pendingRemoteSourceDispatch;
  private Channel pendingRemoteSourceChannel;
  private String pendingRemoteSourceUrl;
  // Probe scores are parsed on a background Room read, then published read-only.
  private volatile org.json.JSONObject cachedProbeScoreJson=new org.json.JSONObject();
  private volatile long indexedPlaybackScoreVersion=-1L;
  private volatile String indexedPlaybackQualityRevision="";

  private volatile long catalogueFilterGeneration=0L;
  private volatile int lastFilteredMatchCount=0;
  private volatile java.util.concurrent.Future<?> pendingCatalogueFilter=null;
  // Bound queued thumbnail work: fast scrolling must not build an unbounded
  // network/decode backlog on Android TV. Rejected cards use the local fallback poster.
  private final ExecutorService posterWorker = new ThreadPoolExecutor(
      2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(48),
      new ThreadPoolExecutor.AbortPolicy());
  // Keep catalogue orchestration separate from metadata I/O: nested tasks on one pool can starve each other.
  private final ExecutorService vodWorker = newLatestRequestWorker();
  private final ExecutorService vodMetadataWorker = Executors.newFixedThreadPool(5);
  private final ExecutorService vodSourceWorker = Executors.newFixedThreadPool(4);
  // Serialize LibVLC JNI startup/teardown away from the UI thread.
  private static final ExecutorService libVlcWorker = Executors.newSingleThreadExecutor();
  private volatile Future<?> activeVodCatalogueTask;
  // Bound poster memory on low-RAM TV boxes. Cache is measured in KiB and
  // capped independently of large heaps so image browsing cannot crowd playback.
  private final LruCache<String,Bitmap> vodPosterCache = new LruCache<String,Bitmap>(
      Math.max(2048,Math.min(8192,(int)(Runtime.getRuntime().maxMemory()/1024/32)))) {
    protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
  };
  // UI-thread owned subscribers; a recycled/rebound cell joins the existing
  // download instead of missing its result. Weak references do not retain cards.
  private final HashMap<String,ArrayList<java.lang.ref.WeakReference<ImageView>>> vodPosterRequests=new HashMap<>();
  private volatile long vodPosterSessionGeneration;
  private int vodRequestId=0;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private Runnable pendingSearchRefresh;
  private boolean favoriteMode = false;
  private int selected = -1;
  // Local parental controls: elapsed-time accounting is monotonic and only runs while the app is foregrounded.
  private long childLockLastElapsed=0L;
  private Runnable childLockTicker;
  private AlertDialog childLockDialog;
  private ParentRemoteLockManager remoteParentLockManager;
  private AlertDialog remoteParentLockDialog;
  private boolean remoteLockOverlayShowing=false;
  private boolean remoteLockUiActive=false;
  private boolean remoteLockTaskActive=false;
  private long playbackGeneration = 0;
  private String currentMediaId = "";
  // Video-frame metadata callbacks run on the playback thread. Gate one UI handoff
  // per source so no ExoPlayer getter is ever called from that callback thread.
  private static final class RemoteChannelOrder {
    final String key;
    final ArrayList<Integer> indexes;
    final HashMap<Integer,Integer> positions;
    // All four collections are worker-owned until publication and read-only thereafter.
    final ArrayList<Channel> visible;
    final HashMap<String,Integer> sourceIndexByKey;
    RemoteChannelOrder(String key,ArrayList<Integer> indexes,ArrayList<Channel> visible,
        HashMap<String,Integer> sourceIndexByKey) {
      this.key=key;this.indexes=indexes;this.visible=visible;this.sourceIndexByKey=sourceIndexByKey;
      this.positions=new HashMap<>(Math.max(16,indexes.size()*2));
      for(int i=0;i<indexes.size();i++)positions.put(indexes.get(i),i);
    }
    private RemoteChannelOrder(String key,RemoteChannelOrder previous) {
      this.key=key;this.indexes=previous.indexes;this.positions=previous.positions;
      this.visible=previous.visible;this.sourceIndexByKey=previous.sourceIndexByKey;
    }
    RemoteChannelOrder withKey(String key){return new RemoteChannelOrder(key,this);}
  }
  private static class Channel {
    String name, url, group, tvgId="", tvgName="";
    volatile LocalDirectoryFacts localDirectoryFacts;
    ArrayList<String> backups;
    // Channel-level scopes support category routing; URL-level scopes prevent a
    // refresh from deleting or misclassifying another catalogue's backup.
    HashSet<String> sourceScopes;
    HashMap<String,HashSet<String>> backupSourceScopes;
    int activeSource = 0;
    Channel(String n,String u,String g){
      name=n==null?"":n;url=u==null?"":u;group=g==null?"":g;
      backups=new ArrayList<>();if(!url.isEmpty())backups.add(url);
      sourceScopes=new HashSet<>();backupSourceScopes=new HashMap<>();
    }
    /** Room already provides the final ordered routes; copy once into independent ownership. */
    Channel(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row){
      name=row.name;url=row.url;group=row.group;tvgId=row.tvgId;tvgName=row.tvgName;
      backups=new ArrayList<>(row.sources);
      sourceScopes=new HashSet<>(row.sourceScopes);
      backupSourceScopes=new HashMap<>();
      for(Map.Entry<String,HashSet<String>> entry:row.backupSourceScopes.entrySet()){
        backupSourceScopes.put(entry.getKey(),new HashSet<>(entry.getValue()));
      }
      activeSource=Math.max(0,Math.min(row.activeSource,backups.size()-1));
    }
    void addBackupSource(String source,String scope){
      if(source==null||source.isEmpty())return;
      if(backups==null)backups=new ArrayList<>();
      if(!backups.contains(source))backups.add(source);
      if(ChannelCatalogueScopePolicy.isRetainableScope(scope)){
        HashSet<String> scopes=backupSourceScopes.get(source);
        if(scopes==null){scopes=new HashSet<>();backupSourceScopes.put(source,scopes);}
        scopes.add(scope);sourceScopes.add(scope);
        // The country/state/city playlist also belongs to the appropriate
        // global or China base directory. Persist both tags on its actual URL,
        // not just a temporary channel-level flag that the worker can erase.
        if(scope.startsWith("geo:")){
          String base=RegionCategoryPolicy.catalogueScope(
              name+" "+tvgName+" "+tvgId,group,"global");
          scopes.add(base);sourceScopes.add(base);
        }
      }
    }
    void mergeBackupOwnership(Channel other){
      if(other==null)return;
      if(sourceScopes==null)sourceScopes=new HashSet<>();
      if(backupSourceScopes==null)backupSourceScopes=new HashMap<>();
      if(other.sourceScopes!=null)sourceScopes.addAll(other.sourceScopes);
      if(other.backupSourceScopes==null)return;
      for(Map.Entry<String,HashSet<String>> entry:other.backupSourceScopes.entrySet()){
        if(entry.getKey()==null||entry.getValue()==null)continue;
        HashSet<String> scopes=backupSourceScopes.get(entry.getKey());
        if(scopes==null){scopes=new HashSet<>();backupSourceScopes.put(entry.getKey(),scopes);}
        scopes.addAll(entry.getValue());
      }
    }
    String key(){return (name==null?"":name)+"|"+(url==null?"":url);}
    void mergeMetadata(String id,String displayName){
      if((tvgId==null||tvgId.isEmpty())&&id!=null&&!id.isEmpty())tvgId=id;
      if((tvgName==null||tvgName.isEmpty())&&displayName!=null&&!displayName.isEmpty())tvgName=displayName;
    }
  }
  public void onCreate(Bundle b) {
    super.onCreate(b);
    installCrashDiagnosticsHandler();
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    // Show the real TV interface immediately. Room restoration and preference
    // warm-up continue in the background; startup must never replace the home
    // screen with a recovery page or make users wait on a modal/loading surface.
    setSourceUpdateActivityActive(true);
    makeUi();
    remoteParentLockManager = new ParentRemoteLockManager(this, new ParentRemoteLockManager.Listener() {
      @Override public void onLockStateChanged(boolean locked, String reason) {
        if (locked) { if(remoteLockUiActive)enforceRemoteParentLock(reason); }
        else dismissRemoteParentLock();
      }
      @Override public void onStatus(String message) {
        android.util.Log.i("MyTVRemoteLock", message);
      }
    });

    worker.execute(() -> {
      long prefsWarmStarted = android.os.SystemClock.elapsedRealtime();
      try {
        android.content.SharedPreferences startupPrefs=getSharedPreferences("mytv",0);
        // Previous releases are intentionally not migrated. Remove old business-data keys.
        boolean hadLegacyBusinessData=startupPrefs.contains("channels")
            ||startupPrefs.contains("fav")
            ||startupPrefs.contains("last_channel_identity")
            ||startupPrefs.contains("last_channel_url")
            ||startupPrefs.contains("vod_favorites_v1")
            ||startupPrefs.contains("vod_history_v1")
            ||startupPrefs.contains("source_probe_scores_v1")
            ||startupPrefs.contains("source_qoe_v1")
            ||startupPrefs.contains("source_update_result")
            ||startupPrefs.contains("source_update_time")
            ||startupPrefs.contains("source_last_good_v1")
            ||startupPrefs.contains("source_cooldowns_v1");
        for(String legacyKey:startupPrefs.getAll().keySet()){
          if(legacyKey.startsWith("vod_cache_")||legacyKey.startsWith("china_supplement_")
              ||legacyKey.startsWith("china_directory_")||legacyKey.startsWith("global_supplement_")
              ||legacyKey.startsWith("source_probe_")||legacyKey.startsWith("source_qoe_")
              ||legacyKey.startsWith("source_scan_")||legacyKey.startsWith("source_saved_scan_")
              ||legacyKey.startsWith("source_feed_")||legacyKey.startsWith("source_initial_discovery_")
              ||legacyKey.startsWith("source_update_last_success")||legacyKey.startsWith("geo_snapshot_")){
            hadLegacyBusinessData=true;
            break;
          }
        }
        if(hadLegacyBusinessData){
          android.content.SharedPreferences.Editor cleanup=startupPrefs.edit()
              .remove("channels").remove("fav").remove("defaults_loaded")
              .remove("source_update_pending_channels")
              .remove("source_update_pending_base")
              .remove("source_update_pending_base_fingerprint")
              .remove("last_channel_identity").remove("last_channel_url")
              .remove("vod_favorites_v1").remove("vod_history_v1")
              .remove("source_last_good_v1").remove("source_cooldowns_v1");
          for(String key:startupPrefs.getAll().keySet()){
            if(key.startsWith("source_probe_")||key.startsWith("source_qoe_")
                ||key.startsWith("source_scan_")||key.startsWith("source_saved_scan_")
                ||key.startsWith("source_feed_")||key.startsWith("source_initial_discovery_")
                ||key.startsWith("source_update_last_success")||key.startsWith("vod_cache_")
                ||key.startsWith("china_supplement_")||key.startsWith("china_directory_")
                ||key.startsWith("global_supplement_")||key.startsWith("geo_snapshot_")
                ||"source_update_time".equals(key)||"source_update_result".equals(key)){
              cleanup.remove(key);
            }
          }
          boolean cleared=cleanup.commit();
          android.util.Log.i("MyTVRoom","discarded legacy non-authoritative business data committed="+cleared);
        }
        recordPreviousProcessExitReason();
        android.util.Log.i("MyTVStartup","preferences_warm_ms="
            +(android.os.SystemClock.elapsedRealtime()-prefsWarmStarted));
      } catch (Throwable warmFailure) {
        android.util.Log.e("MyTVStartup","background preferences warm-up failed",warmFailure);
      }
      // Continue on the same serial worker. Re-enqueueing here can put restore
      // behind unrelated queued work or race shutdown during Activity teardown.
      if(Thread.currentThread().isInterrupted()||isFinishing()||isDestroyed())return;
      long restoreStarted=android.os.SystemClock.elapsedRealtime();
      Throwable restoreFailure=null;
      try{restore();}
      catch(Throwable failure){
        restoreFailure=failure;
        // Preserve fail-closed restore behavior. The complete Room read uses
        // private pages; rebuild only if recovery left any in-memory rows.
        try{if(!channels.isEmpty())rebuildChannelIndexes();}
        catch(Throwable indexFailure){android.util.Log.e("MyTVStartup","restore recovery index rebuild failed",indexFailure);}
        android.util.Log.e("MyTVStartup","saved catalogue restore failed; retained_channels="+channels.size(),failure);
      }
      final boolean restoreSucceeded=restoreFailure==null;
      // Resolve the saved identity on the restore worker, before enabling
      // channel input. Never repeat the full regex/alias scan on the UI thread.
      int resumeIndex=-1;
      Channel resumeChannel=null;
      if(restoreSucceeded){
        try{
          resumeIndex=findLastChannelForResume();
          if(resumeIndex>=0)resumeChannel=channels.get(resumeIndex);
        }catch(RuntimeException resumeFailure){
          android.util.Log.w("MyTVStartup","last-channel lookup failed",resumeFailure);
        }
      }
      final int resolvedResumeIndex=resumeIndex;
      final Channel resolvedResumeChannel=resumeChannel;
      final String restoreFailureMessage=restoreFailure==null?"":restoreFailure.getClass().getSimpleName()+": "+String.valueOf(restoreFailure.getMessage());
      // Room is the only startup source. A failed seed is surfaced, never hidden
      // by an in-memory fallback that cannot be persisted consistently.
      long restoreElapsed=android.os.SystemClock.elapsedRealtime()-restoreStarted;
      runOnUiThread(()->{
        if(isFinishing()||isDestroyed())return;
        startupDataReady=true;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(search!=null)search.setEnabled(true);
        if(categoryMenuItems!=null)for(int i=0;i<categoryMenuItems.getChildCount();i++)categoryMenuItems.getChildAt(i).setEnabled(true);
        try{
          refresh();
          if(info!=null)info.setText(!restoreSucceeded
              ?"频道目录初始化失败，请稍后重试"
              :channels.isEmpty()?"首次频道目录初始化中：正在完整抓取、分类和整理频道，请保持网络连接":"已载入 "+channels.size()+" 个频道 · 选择频道开始播放");
          replayPendingStartupCategory();
        }catch(Throwable renderFailure){
          android.util.Log.e("MyTVStartup","restored channel list render failed",renderFailure);
          if(info!=null)info.setText("频道列表刷新遇到问题；主界面仍可使用，请稍后重试。");
        }
        if(!restoreSucceeded){
          saveLiveDiagnostic("STARTUP_RESTORE_ERROR",restoreFailureMessage);
        }
        // Auto-resume is non-critical: do not attempt playback if saved catalogue
        // recovery itself failed. A stale/corrupt state must not block manual use.
        // Resume the last saved live channel after Room restoration. The directory
        // renders independently; a failed resume must not block manual selection.
        // First installs and empty catalogues have nothing to resume.
        startupResumeIndex=resolvedResumeIndex;
        startupResumeChannel=resolvedResumeChannel;
        startupAutoResumePending=restoreSucceeded && startupResumeChannel!=null
            && FullDiscoveryProgressPolicy.hasPublishedInitialSnapshot(roomFullRebuildCompletedAt);
        scheduleStartupAutoResume();
        // Do not show the first-run progress box on later completed launches.
        if(roomFullRebuildCompletedAt>0L&&!initialFinalCataloguePending&&initialDiscoveryProgressPanel!=null){
          stopInitialDiscoveryProgressTicker();
          initialDiscoveryProgressPanel.setVisibility(View.GONE);
        }
        // Register periodic source maintenance only after the saved catalogue
        // has been restored and the home screen is usable. WorkManager performs
        // refreshes independently; no network discovery is part of app entry.
        try{scheduleAutomaticSourceUpdates();observeStartupSourceUpdateResults();observeManualSourceUpdateResults();}
        catch(RuntimeException schedulingFailure){
          android.util.Log.w("MyTVStartup","background source scheduling deferred",schedulingFailure);
        }
        android.util.Log.i("MyTVStartup","restore_ms="+restoreElapsed+" channels="+channels.size());
      });
    });
  }
  // Replay only while foreground and unlocked, without posting a second
  // click that could override a newer explicit user action.
  private void replayPendingStartupCategory(){
    if(!startupDataReady||!activityStarted||isFinishing()||isDestroyed()||categoryMenuItems==null)return;
    final String queued=pendingStartupCategory;
    pendingStartupCategory=null;
    if(queued==null||isAnyPlaybackLockActive())return;
    for(int i=0;i<categoryMenuItems.getChildCount();i++){
      View child=categoryMenuItems.getChildAt(i);
      if(child instanceof Button&&queued.equals(((Button)child).getText().toString())){
        child.performClick();
        android.util.Log.i("MyTVStartup","replayed_category_after_restore="+queued);
        break;
      }
    }
  }
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  // Mouse hover and D-pad focus are independent states. Hover never steals or clears focus.
  private boolean isCurrentPlayingRow(View view){
    if(view==null||selected<0||selected>=channels.size())return false;
    Object rowKey=view.getTag(R.id.tag_channel_key);
    return rowKey instanceof String&&rowKey.equals(channels.get(selected).key());
  }
  // Remote focus and mouse hover must remain memory-only; never serialize diagnostics here.
  private void styleLiveChannelButton(Button b,int normalColor){
    b.setFocusable(true);b.setFocusableInTouchMode(true);
    b.setTextColor(Color.WHITE);
    applyLiveChannelRowStyle(b,normalColor);
    b.setOnFocusChangeListener((v,focused)->{

      if(!b.isHovered())applyLiveChannelRowStyle(b,normalColor);
      b.setScaleX(focused?1.04f:1f);b.setScaleY(focused?1.04f:1f);
      b.setElevation(focused?dp(8):0);
    });
    b.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){

        GradientDrawable hover=new GradientDrawable();hover.setColor(Color.rgb(32,78,118));
        hover.setCornerRadius(dp(7));hover.setStroke(dp(2),Color.rgb(105,185,245));
        b.setBackground(hover);b.setScaleX(1.04f);b.setScaleY(1.04f);b.setElevation(dp(8));
      }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
        applyLiveChannelRowStyle(b,normalColor);
        boolean focused=b.hasFocus();
        b.setScaleX(focused?1.04f:1f);b.setScaleY(focused?1.04f:1f);b.setElevation(focused?dp(8):0);
      }
      return false;
    });
  }
  private void applyLiveChannelRowStyle(Button button,int normalColor){
    boolean playing=isCurrentPlayingRow(button),focused=button.hasFocus();
    int base=playing?Color.rgb(12,92,132):normalColor;
    GradientDrawable bg=makeButtonBg(base,focused);
    if(playing)bg.setStroke(dp(focused?3:2),Color.rgb(255,190,70));
    button.setBackground(bg);
    button.setTextColor(Color.WHITE);
    button.setSelected(playing);
    button.setActivated(playing);
  }
  // A channel zap changes the playing marker for at most two visible rows.
  // Rebuilding every row's title, regex-based name and background on each
  // remote keypress caused unnecessary UI allocations on Android TV boxes.
  private void updateLiveChannelRow(Button button,int index){
    if(button==null||index<0||index>=channels.size())return;
    Channel channel=channels.get(index);
    if(channel==null)return;
    // An old row can outlive a catalogue index replacement.
    // Do not relabel it while its click still owns the old key.
    if(!channel.key().equals(button.getTag(R.id.tag_channel_key)))return;
    boolean broadcast=isCctvChannel(channel)||isSatelliteChannel(channel);
    String title=displayChannelName(channel);
    String group=broadcast?"":displayLiveChannelGroup(channel);
    button.setText((index==selected?"▶ ":"")+(favorites.contains(channel.key())?"★ ":"")+title+group);
    button.setContentDescription(index==selected?"当前正在播放："+title:title);
    applyLiveChannelRowStyle(button,Color.rgb(35,45,62));
  }
  private void updateLiveChannelPresentation(){
    if(list!=null){
      for(int i=0;i<list.getChildCount();i++){
        View child=list.getChildAt(i);
        if(child instanceof Button&&child.getTag() instanceof Integer)
          updateLiveChannelRow((Button)child,(Integer)child.getTag());
      }
    }
  }
  private void updateLiveChannelSelection(int previousSelected){
    if(list!=null){
      for(int i=0;i<list.getChildCount();i++){
        View child=list.getChildAt(i);
        if(!(child instanceof Button)||!(child.getTag() instanceof Integer))continue;
        int index=(Integer)child.getTag();
        if(index==previousSelected||index==selected)
          updateLiveChannelRow((Button)child,index);
      }
    }
  }
  // DPAD and mouse focus callbacks must not write diagnostic preferences on every input.
  private void styleTvButton(Button b,int normalColor){
    b.setFocusable(true);b.setFocusableInTouchMode(true);b.setTextColor(Color.WHITE);
    b.setBackground(makeButtonBg(normalColor,false));
    b.setOnFocusChangeListener((v,focused)->{

      if(!b.isHovered())b.setBackground(makeButtonBg(normalColor,focused));
      b.setScaleX(focused?1.025f:1f);b.setScaleY(focused?1.025f:1f);b.setElevation(focused?dp(6):0);
    });
    b.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){

        GradientDrawable hover=new GradientDrawable();hover.setColor(Color.rgb(35,91,139));
        hover.setCornerRadius(dp(7));hover.setStroke(dp(2),Color.rgb(105,185,245));
        b.setBackground(hover);b.setScaleX(1.025f);b.setScaleY(1.025f);b.setElevation(dp(6));
      }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
        boolean focused=b.hasFocus();b.setBackground(makeButtonBg(normalColor,focused));
        b.setScaleX(focused?1.025f:1f);b.setScaleY(focused?1.025f:1f);b.setElevation(focused?dp(6):0);
      }
      return false;
    });
  }
  private void styleVodTopControl(Button b){
    final int normal=Color.rgb(28,42,60);
    final int active=Color.rgb(25,105,185);
    b.setTextColor(Color.WHITE);
    b.setTypeface(null,android.graphics.Typeface.BOLD);
    b.setBackground(makeVodTopControlBg(normal,false));
    b.setPadding(dp(14),dp(6),dp(14),dp(6));
    b.setMinHeight(dp(48));
    b.setElevation(dp(3));
    b.setOnFocusChangeListener((v,focused)->{
      b.setBackground(makeVodTopControlBg(focused?active:normal,focused));
      b.setTextColor(focused?Color.WHITE:Color.rgb(235,242,250));
      b.setElevation(dp(focused?10:3));
      b.setScaleX(focused?1.05f:1f);b.setScaleY(focused?1.05f:1f);
    });
    b.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
        // Mouse hover is blue; remote/D-pad focus remains a distinct yellow outline.
        b.setBackground(makeVodTopControlHoverBg(Color.rgb(35,105,160)));
        b.setTextColor(Color.WHITE);b.setElevation(dp(11));b.setScaleX(1.05f);b.setScaleY(1.05f);
      }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
        boolean focused=b.hasFocus();
        b.setBackground(makeVodTopControlBg(focused?active:normal,focused));
        b.setTextColor(focused?Color.WHITE:Color.rgb(235,242,250));
        b.setElevation(dp(focused?10:3));b.setScaleX(focused?1.05f:1f);b.setScaleY(focused?1.05f:1f);
      }
      return false;
    });
  }
  private GradientDrawable makeVodTopControlBg(int color,boolean focused){
    GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(8));
    bg.setStroke(dp(focused?3:1),focused?Color.rgb(255,210,55):Color.rgb(112,137,164));
    return bg;
  }
  private GradientDrawable makeVodTopControlHoverBg(int color){
    GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(8));
    bg.setStroke(dp(2),Color.rgb(105,185,245));
    return bg;
  }
  private GradientDrawable makeButtonBg(int color,boolean focused){
    GradientDrawable d=new GradientDrawable(); d.setColor(focused?Color.rgb(22,105,185):color);
    d.setCornerRadius(dp(7));
    d.setStroke(dp(focused?3:1),focused?Color.rgb(80,190,255):Color.rgb(76,91,112));
    return d;
  }
  // Keep live TV and VOD on the same decoder policy. Hardware decoders remain preferred;
  // Media3 falls back to another available system decoder only when the selected decoder fails.
  private boolean isDecoderFallbackEnabled(){
    return DecoderPolicy.isDecoderFallbackEnabled(
        getSharedPreferences("mytv",0).getInt("decoder_mode",DecoderPolicy.DEFAULT_MODE));
  }
  /**
   * Room is the sole persistent channel catalogue. A fresh installation receives
   * an empty, validated initialization checkpoint; the first full discovery
   * publishes the first real catalogue only after every configured feed is handled.
   * Later launches restore the last complete Room snapshot without waiting for feeds.
   */
  private void restore(){
    catalogueSnapshotTrusted=false;
    loadRoomAppState();
    final long restoreStarted=android.os.SystemClock.elapsedRealtime();
    synchronized(channels){channels.clear();}
    synchronized(favorites){favorites.clear();}
    try{
      com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot roomSnapshot=
          readRoomCatalogueWithStartupRetry();
      if(roomSnapshot==null){
        // A first install starts with a valid empty Room checkpoint, not a tiny
        // bundled playlist masquerading as the completed channel catalogue.
        // The full China-first discovery worker owns the first real publication.
        android.util.Log.i("MyTVRoom","fresh Room database; creating empty initialization checkpoint");
        com.wodedianshi.tv.db.RoomCatalogueWriter.initializeEmptyCatalogue(getApplicationContext());
        roomSnapshot=readRoomCatalogueWithStartupRetry();
      }
      if(roomSnapshot==null){
        throw new IllegalStateException("Room catalogue initialization did not produce a valid checkpoint");
      }
      synchronized(favorites){
        favorites.clear();
        favorites.addAll(roomSnapshot.favorites);
      }
      rebuildChannelIndexes();
      prepareStartupLocalSnapshot();
      loadedRoomCatalogueVersion=roomSnapshot.catalogueVersion;
      catalogueSnapshotTrusted=true;
      // Restore the complete score cache from the same validated catalogue
      // on the startup worker. A quality read failure must not block TV startup.
      try{
        String qualityRevision=readQualityOnlyRevision();
        org.json.JSONObject indexed=readIndexedPlaybackScores(channels,roomSnapshot.catalogueVersion,()->isFinishing()||isDestroyed());
        if(indexed!=null&&qualityRevision.equals(readQualityOnlyRevision())){
          cachedProbeScoreJson=indexed;indexedPlaybackScoreVersion=roomSnapshot.catalogueVersion;
          indexedPlaybackQualityRevision=qualityRevision;
        }
      }catch(Exception qualityFailure){android.util.Log.w("MyTVStartup","Indexed quality restore deferred; startup continues with available evidence",qualityFailure);}
      int sourceCount=countChannelSources();
      logCatalogueMemory("room_restore_ready",roomSnapshot.channelCount,sourceCount,
          android.os.SystemClock.elapsedRealtime()-restoreStarted);
      android.util.Log.i("MyTVRoom","Room-only restore complete channels="
          +roomSnapshot.channelCount+" favorites="+roomSnapshot.favorites.size());
    }catch(Throwable failure){
      synchronized(channels){channels.clear();}
      synchronized(favorites){favorites.clear();}
      catalogueSnapshotTrusted=false;
      android.util.Log.e("MyTVRoom","Room-only startup initialization failed",failure);
      saveLiveDiagnostic("ROOM_STARTUP_ERROR",
          failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));
      throw new IllegalStateException("频道目录初始化失败，未载入不完整目录，请重试",failure);
    }
  }

  // Only transient optimistic read conflicts and temporarily unavailable
  // checkpoints are retried. Structural errors still fail closed. Each
  // attempt uses private staging pages; this runs on the startup worker.
  private com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot readRoomCatalogueWithStartupRetry(){
    for(int attempt=0;attempt<3;attempt++){
      try{
        com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot=readRoomCatalogueIntoMemory();
        if(snapshot!=null||attempt==2)return snapshot;
      }catch(com.wodedianshi.tv.db.RoomCatalogueReader.StaleSnapshotException conflict){
        if(attempt==2)throw conflict;
      }
      android.util.Log.i("MyTVStartup","room_startup_read_retry="+(attempt+1));
      try{Thread.sleep(100L*(attempt+1));}
      catch(InterruptedException interrupted){
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Room startup read interrupted",interrupted);
      }
    }
    throw new IllegalStateException("Room startup retry exhausted");
  }

  /** Read all Room pages and publish them to the in-memory UI model only on success. */
  private com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot readRoomCatalogueIntoMemory(){
    final ArrayList<Channel> prepared=new ArrayList<>();
    com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot=
        com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),page->{
      for(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row:page){
        prepared.add(new Channel(row));
      }
    });
    // The consumer above is private staging. A failed or stale read discards
    // it without exposing any partial page to the shared catalogue.
    if(snapshot!=null){
      synchronized(channels){channels.clear();channels.addAll(prepared);}
    }
    return snapshot;
  }

  private void loadRoomAppState(){
    java.util.HashMap<String,String> saved=new java.util.HashMap<>();
    for(com.wodedianshi.tv.db.AppStateEntity row:
        com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext()).appStateDao()
            .getValues(java.util.Arrays.asList(ROOM_STATE_LAST_CHANNEL_IDENTITY,ROOM_STATE_LAST_CHANNEL_URL,
                ROOM_STATE_LAST_CHANNEL_NAME,ROOM_STATE_LAST_CHANNEL_GROUP,ROOM_STATE_LAST_CHANNEL_TVG_ID))){
      saved.put(row.key,row.stateValue);
    }
    roomLastChannel=LAST_CHANNEL_WRITER.initialize(new LastChannelState(
        saved.get(ROOM_STATE_LAST_CHANNEL_IDENTITY),saved.get(ROOM_STATE_LAST_CHANNEL_URL),
        saved.get(ROOM_STATE_LAST_CHANNEL_NAME),saved.get(ROOM_STATE_LAST_CHANNEL_GROUP),
        saved.get(ROOM_STATE_LAST_CHANNEL_TVG_ID)));
    roomVodFavoritesJson=com.wodedianshi.tv.db.AppStateStore.get(
        getApplicationContext(),ROOM_STATE_VOD_FAVORITES,"[]");
    roomVodHistoryJson=com.wodedianshi.tv.db.AppStateStore.get(
        getApplicationContext(),ROOM_STATE_VOD_HISTORY,"[]");
    // First install starts with unknown scores; the version-checked Room
    // snapshot supplies authoritative observations when available.
    cachedProbeScoreJson=new org.json.JSONObject();
    indexedPlaybackScoreVersion=-1L;indexedPlaybackQualityRevision="";
    roomFullRebuildCompletedAt=parseRoomLong("source_full_rebuild_completed_at",0L);
    SourceMemoryStore.initialize(getApplicationContext());
  }

  private long parseRoomLong(String key,long fallback){
    try{return Long.parseLong(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),key,Long.toString(fallback)));}
    catch(Exception ignored){return fallback;}
  }

  private void queueRoomAppStateWrite(String key,String value){
    if(key==null||value==null)return;
    try{
      cataloguePersistWorker.execute(()->{
        try{
          com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),key,value);
        }catch(Throwable failure){
          android.util.Log.e("MyTVRoomState","failed to persist "+key,failure);
        }
      });
    }catch(RuntimeException rejected){
      android.util.Log.e("MyTVRoomState","state write queue rejected key="+key,rejected);
    }
  }

  private void saveRoomVodFavorites(String json){
    roomVodFavoritesJson=json==null?"[]":json;
    queueRoomAppStateWrite(ROOM_STATE_VOD_FAVORITES,roomVodFavoritesJson);
  }

  private void saveRoomVodHistory(String json){
    roomVodHistoryJson=json==null?"[]":json;
    queueRoomAppStateWrite(ROOM_STATE_VOD_HISTORY,roomVodHistoryJson);
  }

  private int countChannelSources(){
    int count=0;
    synchronized(channels){
      for(Channel channel:channels){
        if(channel!=null&&channel.backups!=null)count+=channel.backups.size();
      }
    }
    return count;
  }

  // Copy a complete, version-consistent Room snapshot in short channel-lock
  // batches. Previously persistNow() held synchronized(channels) while deep
  // copying 6k+ channels and their source ownership maps, delaying UI input.
  // Abort/retry rather than publishing a mixed snapshot if a producer modifies
  // any channel while its batches are being copied.
  private ArrayList<com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelSnapshot>
      snapshotChannelRows(){
    for(int attempt=0;attempt<3;attempt++){
      final long expectedMutation=catalogueMutationGeneration;
      final ArrayList<Channel> refs;
      synchronized(channels){refs=new ArrayList<>(channels);}
      final ArrayList<com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelSnapshot> rows=
          new ArrayList<>(refs.size());
      final int batchSize=32;
      for(int from=0;from<refs.size();from+=batchSize){
        if(Thread.currentThread().isInterrupted())return null;
        final int end=Math.min(from+batchSize,refs.size());
        synchronized(channels){
          for(int at=from;at<end;at++){
            Channel channel=refs.get(at);
            if(channel==null||channel.name==null||channel.name.trim().isEmpty()
                ||channel.url==null||channel.url.trim().isEmpty())continue;
            com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelSnapshot row=
                new com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelSnapshot();
            row.name=channel.name;row.url=channel.url;row.group=channel.group;
            row.tvgId=channel.tvgId;row.tvgName=channel.tvgName;
            row.activeSource=channel.activeSource;
            if(channel.backups!=null)row.sources.addAll(channel.backups);
            if(channel.sourceScopes!=null)row.sourceScopes.addAll(channel.sourceScopes);
            if(channel.backupSourceScopes!=null){
              for(Map.Entry<String,HashSet<String>> entry:channel.backupSourceScopes.entrySet())
                row.backupSourceScopes.put(entry.getKey(),new HashSet<>(entry.getValue()));
            }
            rows.add(row);
          }
        }
      }
      if(expectedMutation==catalogueMutationGeneration){
        return rows;
      }
      android.util.Log.i("MyTVCatalogue","Room snapshot changed during short-lock copy; retry="+attempt);
      Thread.yield();
    }
    // Never persist a hybrid of two catalogue versions. The coalescing
    // persist executor will receive another request when ingestion completes.
    android.util.Log.w("MyTVCatalogue","Room snapshot copy deferred after repeated mutations");
    return null;
  }
  private void rebuildChannelIndexes(){
    synchronized(channels){
      // If the user starts playback from the first progressively revealed page,
      // retain that selection when final de-duplication compacts the catalogue.
      String selectedIdentity=selected>=0&&selected<channels.size()
          ?identity(channels.get(selected)):null;
      // Preserve display order while using hash indexes for both channel identity
      // and backup-source de-duplication. ArrayList.contains() here made restoring
      // large catalogues quadratic when thousands of rows shared the same channel.
      HashMap<String,Channel> firstByIdentity=new HashMap<>(Math.max(16,channels.size()*2));
      HashMap<String,HashSet<String>> sourcesByIdentity=new HashMap<>(16);
      ArrayList<Channel> compact=new ArrayList<>(channels.size());
      for(Channel c:channels){
        normalizeBroadcasterLabel(c);
        if(c.backups==null)c.backups=new ArrayList<>();
        if(c.backups.isEmpty()&&c.url!=null&&!c.url.isEmpty())c.backups.add(c.url);
        String key=identity(c);
        Channel keep=firstByIdentity.get(key);
        if(keep==null){
          firstByIdentity.put(key,c);
          compact.add(c);
        }else{
          // Allocate a source set only for identities that actually have duplicates;
          // a set per channel would waste substantial heap for 15k unique channels.
          HashSet<String> seen=sourcesByIdentity.get(key);
          if(seen==null){
            seen=new HashSet<>(Math.max(4,keep.backups.size()*2));
            for(String source:keep.backups)if(source!=null&&!source.isEmpty())seen.add(source);
            sourcesByIdentity.put(key,seen);
          }
          for(String source:c.backups){
            if(source!=null&&!source.isEmpty()&&seen.add(source))keep.backups.add(source);
          }
          keep.mergeBackupOwnership(c);
          synchronized(favorites){
            if(favorites.contains(c.key()))favorites.add(keep.key());
          }
        }
      }
      channels.clear();channels.addAll(compact);
      channelsByUrl.clear();channelsByIdentity.clear();
      channelsByIdentity.putAll(firstByIdentity);
      for(Channel c:channels){
        for(String u:c.backups)channelsByUrl.put(u,c);
      }
      if(selectedIdentity!=null){
        selected=channels.indexOf(firstByIdentity.get(selectedIdentity));
      }
    }
    synchronized(cataloguePersistLock){catalogueMutationGeneration++;catalogueMembershipGeneration++;}
    cachedRemoteChannelOrder=null;
  }
  private void normalizeBroadcasterLabel(Channel channel){
    if(channel==null)return;
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(
        channel.name,channel.tvgName,channel.tvgId,channel.group);
    if(!broadcaster.isEmpty())channel.name=broadcaster;
  }
  private String displayChannelName(Channel channel){
    if(channel==null)return "";
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(
        channel.name,channel.tvgName,channel.tvgId,channel.group);
    if(!broadcaster.isEmpty())return displayChannelName(broadcaster);
    return ChannelNamePolicy.chineseDisplayName(
        channel.name,channel.tvgName,channel.tvgId,channel.group);
  }
  private String displayLiveChannelGroup(Channel c){
    if(c==null)return "";
    String group=c.group==null?"":c.group;
    // In the local directory show the resolved Chinese province, not an
    // aggregator's English genre/group label. Preserve raw metadata in Room.
    if("地方".equals(activeCategory)||localRegionFilterActive){
      LocalDirectoryFacts facts=localDirectoryFacts(c);
      String province=facts.excluded?ChannelNamePolicy.provinceForLocalChannel(facts.metadata):facts.province;
      if(!province.isEmpty())group=province;
      else if(ChannelCatalogueScopePolicy.matches(c.sourceScopes,"china"))group="地方台";
    }
    return group.isEmpty()?"":" · "+group;
  }
  private static final java.util.regex.Pattern CANONICAL_CHANNEL_SEPARATORS=java.util.regex.Pattern.compile("[\\s_—–·•]");
  private static final java.util.regex.Pattern CANONICAL_CCTV_NUMBER=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)[-]?(\\d{1,2}\\+?|NEWS|4K|8K)");
  private static final java.util.regex.Pattern RESUME_METADATA_WHITESPACE=java.util.regex.Pattern.compile("\\s+");
  private static final String[] CCTV_CHINESE_NUMBERS={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
  private static final String[] MAINLAND_PROVINCES={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
  private String canonicalChannelName(String name){
    String satellite=ChannelNamePolicy.canonicalSatelliteName(name);
    if(!satellite.isEmpty())return satellite;
    String value=name==null?"":CANONICAL_CHANNEL_SEPARATORS.matcher(name.trim().toUpperCase(Locale.ROOT)).replaceAll("");
    java.util.regex.Matcher m=CANONICAL_CCTV_NUMBER.matcher(value);
    if(m.find())return "CCTV-"+m.group(1);
    // All these patterns require the literal 中央; avoid compiling seventeen
    // regexes for every unrelated local or international station.
    if(value.contains("中央"))for(int i=1;i<CCTV_CHINESE_NUMBERS.length;i++)if(value.matches(".*中央(?:电视台)?"+CCTV_CHINESE_NUMBERS[i]+"套.*"))return "CCTV-"+i;
    if(value.contains("中央电视台综合频道"))return "CCTV-1";
    if(value.contains("中央电视台财经频道"))return "CCTV-2";
    if(value.contains("中央电视台综艺频道"))return "CCTV-3";
    if(value.contains("中央电视台中文国际频道"))return "CCTV-4";
    if(value.contains("中央电视台体育频道"))return "CCTV-5";
    if(value.contains("中央电视台电影频道"))return "CCTV-6";
    // Strip quality/live suffixes before matching the same satellite channel across hosts.
    value=value.replace("超高清","").replace("高清","").replace("超清","").replace("1080P","").replace("720P","").replace("4K高清","").replace("HD","").replace("FHD","").replace("直播","").replace("频道直播","");
    if(value.endsWith("湖南台"))return "湖南卫视";
    if(value.endsWith("浙江台"))return "浙江卫视";
    if(value.endsWith("江苏台"))return "江苏卫视";
    if(value.endsWith("东方台"))return "东方卫视";
    if(value.endsWith("北京台"))return "北京卫视";
    if(value.endsWith("广东台"))return "广东卫视";
    return value;
  }
  private boolean isCctvChannel(Channel c){
    return (c!=null&&ChannelNamePolicy.canonicalBroadcasterName(
        c.name,c.tvgName,c.tvgId,c.group).startsWith("CCTV-"))
        ||canonicalChannelName(c==null?"":c.name).startsWith("CCTV-")
        ||(c!=null&&((c.name+" "+c.group).toUpperCase(Locale.ROOT).contains("CCTV")
        ||(c.name+" "+c.group).contains("央视")||(c.name+" "+c.group).contains("中央电视台")));
  }
  private int cctvSortOrder(Channel c){
    String key=c==null?"":ChannelNamePolicy.canonicalBroadcasterName(
        c.name,c.tvgName,c.tvgId,c.group);
    if(!key.startsWith("CCTV-"))key=canonicalChannelName(c==null?"":c.name);
    if(!key.startsWith("CCTV-"))key=canonicalChannelName(c==null?"":c.group);
    try{
      String n=key.substring(5);
      if(n.matches("\\d{1,2}\\+?"))return Integer.parseInt(n.replace("+",""));
      if(n.equals("NEWS"))return 90;
      if(n.equals("4K"))return 91;
      if(n.equals("8K"))return 92;
    }catch(Exception ignored){}
    return 999;
  }
  private String displayChannelName(String name){
    String key=canonicalChannelName(name);
    if(key.endsWith("卫视"))return key;
    if(!key.startsWith("CCTV-"))return name==null?"":name;
    String suffix=key.substring(5);
    // CCTV-5+ is a separate sports-events channel. Dropping "+" produced two
    // visibly identical "CCTV-5 体育" rows in the live channel selector.
    if("5+".equals(suffix))return "CCTV-5+ 体育赛事";
    String[] titles={"","综合","财经","综艺","中文国际","体育","电影","国防军事","电视剧","纪录","科教","戏曲","社会与法","新闻","少儿","音乐","奥林匹克","农业农村"};
    try{
      if(suffix.matches("\\d{1,2}\\+?")){
        int n=Integer.parseInt(suffix.replace("+",""));
        if(n>=1&&n<titles.length)return "CCTV-"+n+" "+titles[n];
      }
    }catch(Exception ignored){}
    if(suffix.equals("NEWS"))return "CCTV-NEWS 新闻";
    if(suffix.equals("4K"))return "CCTV-4K 超高清";
    if(suffix.equals("8K"))return "CCTV-8K 超高清";
    return key;
  }
  // Satellite channels have a stable canonical identity across different source hosts.
  // Generic/local names retain host separation to avoid merging unrelated "News" channels.
  private String identity(Channel channel){
    if(channel==null)return "";
    return identity(channel.name,channel.group,channel.url,channel.tvgName,channel.tvgId);
  }
  private String identity(String name,String group,String url,String tvgName,String tvgId){
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
    return ChannelIdentityPolicy.identity(broadcaster,tvgId,identity(name,group,url));
  }
  private String identity(String name,String group,String url){
    String canonical=canonicalChannelName(name);
    if(canonical.startsWith("CCTV-"))return canonical+"|cctv";
    if(canonical.contains("卫视"))return canonical.toLowerCase(Locale.ROOT)+"|satellite";
    String base=canonical.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
    try{java.net.URI u=java.net.URI.create(url==null?"":url);String host=u.getHost();if(host!=null&&!host.isEmpty())return base+"|host:"+host.toLowerCase(Locale.ROOT);}
    catch(Exception ignored){}
    return base+"|url:"+(url==null?"":url.trim());
  }
  private void saveLastChannel(){
    if(selected<0||selected>=channels.size())return;
    Channel c=channels.get(selected);
    persistLastChannelState(new LastChannelState(identity(c),c.url,c.name,c.group,c.tvgId));
  }
  private void persistLastChannelState(LastChannelState next){
    roomLastChannel=next;
    final Context app=getApplicationContext();
    // One latest-value slot per process, with a serial transactional write.
    // Identical failed values remain eligible for retry.
    LAST_CHANNEL_WRITER.offer(next,cataloguePersistWorker,state->{
      try{
        new com.wodedianshi.tv.db.AppStateEditor(app)
            .putString(ROOM_STATE_LAST_CHANNEL_IDENTITY,state.identity)
            .putString(ROOM_STATE_LAST_CHANNEL_URL,state.url)
            .putString(ROOM_STATE_LAST_CHANNEL_NAME,state.name)
            .putString(ROOM_STATE_LAST_CHANNEL_GROUP,state.group)
            .putString(ROOM_STATE_LAST_CHANNEL_TVG_ID,state.tvgId).apply();
      }catch(RuntimeException failure){
        android.util.Log.e("MyTVRoomState","last channel transaction failed",failure);
        throw failure;
      }
    });
  }
  private int findLastChannelForResume(){
    LastChannelState resume=roomLastChannel;
    String savedIdentity=resume.identity;
    if(savedIdentity.isEmpty())return -1;
    String savedUrl=resume.url;
    String savedName=canonicalChannelName(resume.name);
    String savedGroup=normalizeResumeMetadata(resume.group);
    String savedTvgId=normalizeResumeMetadata(resume.tvgId);
    return RemoteChannelOrderPolicy.resolveSavedChannel(channels.size(),
        i->identity(channels.get(i)).equals(savedIdentity),
        i->{Channel c=channels.get(i);return identity(c.name,c.group,c.url).equals(savedIdentity);},
        i->{
          Channel c=channels.get(i);
          return !resume.name.isEmpty()
              &&canonicalChannelName(c.name).equals(savedName)
              &&normalizeResumeMetadata(c.group).equals(savedGroup)
              &&(resume.tvgId.isEmpty()||normalizeResumeMetadata(c.tvgId).equals(savedTvgId));
        },
        i->{
          Channel c=channels.get(i);
          // A reused endpoint must not override contradictory station metadata.
          return !savedUrl.isEmpty()&&(c.url.equals(savedUrl)||c.backups.contains(savedUrl))
              &&(resume.name.isEmpty()||canonicalChannelName(c.name).equals(savedName))
              &&(resume.tvgId.isEmpty()||normalizeResumeMetadata(c.tvgId).equals(savedTvgId));
        });
  }
  private String normalizeResumeMetadata(String value){
    return value==null?"":RESUME_METADATA_WHITESPACE.matcher(value.trim().toLowerCase(Locale.ROOT)).replaceAll("");
  }
  // Defensive guard for dynamically reparented video/player views. A ViewGroup
  // can only measure children with its own LayoutParams subtype; a stale
  // LinearLayout.LayoutParams inside a FrameLayout otherwise crashes in onMeasure.
  private void normalizeFrameLayoutChildren(View view){
    if(!(view instanceof ViewGroup))return;
    ViewGroup parent=(ViewGroup)view;
    for(int i=0;i<parent.getChildCount();i++){
      View child=parent.getChildAt(i);
      ViewGroup.LayoutParams params=child.getLayoutParams();
      if(parent instanceof FrameLayout&&params!=null&&!(params instanceof FrameLayout.LayoutParams)){
        FrameLayout.LayoutParams safe=new FrameLayout.LayoutParams(params.width,params.height);
        if(params instanceof ViewGroup.MarginLayoutParams){
          ViewGroup.MarginLayoutParams margins=(ViewGroup.MarginLayoutParams)params;
          safe.setMargins(margins.leftMargin,margins.topMargin,margins.rightMargin,margins.bottomMargin);
        }
        android.util.Log.w("MyTVLayoutGuard","Repaired "+child.getClass().getSimpleName()+" params in "+parent.getClass().getSimpleName()+" from "+params.getClass().getSimpleName());
        child.setLayoutParams(safe);
        saveLiveDiagnostic("LAYOUT_PARAMS_REPAIRED",child.getClass().getSimpleName()+" in "+parent.getClass().getSimpleName()+" from "+params.getClass().getSimpleName());
      }
      normalizeFrameLayoutChildren(child);
    }
  }
  private void logCatalogueMemory(String stage,int channelCount,int sourceCount,long elapsedMs){
    Runtime runtime=Runtime.getRuntime();
    long used=Math.max(0L,runtime.totalMemory()-runtime.freeMemory());
    android.util.Log.i("MyTVMemory","stage="+stage+" channels="+channelCount+" sources="+sourceCount+" used_mb="+(used/1048576L)+" heap_mb="+(runtime.totalMemory()/1048576L)+" max_mb="+(runtime.maxMemory()/1048576L)+" elapsed_ms="+elapsedMs);
  }
  /**
   * Queue catalogue persistence off the UI thread. Multiple rapid requests are
   * coalesced: the writer always snapshots the latest in-memory catalogue, and
   * a single worker prevents older snapshots from overwriting newer ones.
   *
   * Background callers wait for the requested generation so import flows can
   * still report a real save failure. UI callers return after the write is queued.
   */
  private boolean persist(){
    final long requestedGeneration;
    synchronized(cataloguePersistLock){
      // Any save request represents a catalogue mutation or a caller that needs
      // the latest catalogue durable. In-flight background reloads must not
      // publish a snapshot captured before this generation.
      catalogueMutationGeneration++;catalogueMembershipGeneration++;
      requestedGeneration=++cataloguePersistRequestedGeneration;
      if(!cataloguePersistScheduled){
        cataloguePersistScheduled=true;
        try{
          cataloguePersistWorker.execute(()->{
            while(true){
              final long generationToWrite;
              synchronized(cataloguePersistLock){
                generationToWrite=cataloguePersistRequestedGeneration;
              }
              boolean success=persistNow();
              synchronized(cataloguePersistLock){
                cataloguePersistCompletedGeneration=generationToWrite;
                cataloguePersistLastSuccess=success;
                cataloguePersistLock.notifyAll();
                if(cataloguePersistRequestedGeneration==generationToWrite){
                  cataloguePersistScheduled=false;
                  return;
                }
              }
            }
          });
        }catch(RuntimeException rejected){
          cataloguePersistScheduled=false;
          cataloguePersistLastSuccess=false;
          cataloguePersistCompletedGeneration=requestedGeneration;
          cataloguePersistLock.notifyAll();
          android.util.Log.e("MyTVCatalogue","catalogue persistence queue rejected",rejected);
          return false;
        }
      }
      if(Looper.myLooper()==Looper.getMainLooper()){
        android.util.Log.i("MyTVCatalogue","persist queued generation="+requestedGeneration);
        return true;
      }
      while(cataloguePersistCompletedGeneration<requestedGeneration){
        try{cataloguePersistLock.wait();}
        catch(InterruptedException interrupted){
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return cataloguePersistLastSuccess;
    }
  }
  // The exact Room checkpoint backing the currently loaded in-memory directory.
  // A newer SourceUpdateWorker checkpoint makes stale UI saves fail closed.
  private volatile long loadedRoomCatalogueVersion=-1L;

  private boolean persistNow(){
    if(!catalogueSnapshotTrusted){
      android.util.Log.e("MyTVCatalogue","Room save blocked because startup catalogue is not trusted");
      saveLiveDiagnostic("CATALOGUE_PERSIST_BLOCKED","Room startup restore/seed is incomplete");
      return false;
    }
    long started=android.os.SystemClock.elapsedRealtime();
    int sourceCount=0;
    try{
      // Capture the Room baseline BEFORE copying channels. If an onStart Room
      // reload installs a newer directory between the copy and the commit,
      // reading the baseline afterwards could falsely authorize stale rows.
      final long expectedVersion=loadedRoomCatalogueVersion;
      long copyStarted=android.os.SystemClock.elapsedRealtime();
      ArrayList<com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelSnapshot> rows=snapshotChannelRows();
      if(rows==null)throw new IllegalStateException("Room snapshot changed repeatedly during bounded copy");
      for(com.wodedianshi.tv.db.RoomCatalogueWriter.ChannelSnapshot row:rows)
        sourceCount+=row.sources.size();
      android.util.Log.i("MyTVCatalogue","room_snapshot_copy_ms="
          +(android.os.SystemClock.elapsedRealtime()-copyStarted)
          +" channels="+rows.size());
      if(rows.isEmpty())throw new IllegalStateException("Refusing to persist an empty channel catalogue");
      HashSet<String> favoriteSnapshot;
      favoriteSnapshot=FavoriteMembershipPolicy.snapshot(favorites);
      // The writer's transaction performs the final compare-and-swap. This
      // early guard avoids even attempting an inconsistent in-memory copy.
      if(expectedVersion!=loadedRoomCatalogueVersion)
        throw new com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException();
      com.wodedianshi.tv.db.RoomCatalogueWriter.CommitResult commit=
          com.wodedianshi.tv.db.RoomCatalogueWriter.writeRecordsAtVersion(
              getApplicationContext(),rows,favoriteSnapshot,expectedVersion);
      if(commit.channelCount<=0)throw new IllegalStateException("Room wrote zero channel rows");
      // Use this transaction's exact checkpoint, never a later database read.
      loadedRoomCatalogueVersion=commit.catalogueVersion;
      saveLiveDiagnostic("CATALOGUE_PERSIST_ROOM",
          "saved "+commit.channelCount+" channels, "+sourceCount+" source routes and "
              +favoriteSnapshot.size()+" favorites version="+commit.catalogueVersion);
      logCatalogueMemory("room_persist_done",commit.channelCount,sourceCount,
          android.os.SystemClock.elapsedRealtime()-started);
      return true;
    }catch(com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException stale){
      // A background source refresh won the transaction. Never overwrite its
      // newer complete directory with old Activity rows or blindly retry.
      android.util.Log.w("MyTVCatalogue","ROOM_STALE_SNAPSHOT_REJECTED"
          +" baseVersion="+loadedRoomCatalogueVersion+"; newer Room data retained");
      saveLiveDiagnostic("ROOM_STALE_SNAPSHOT_REJECTED",
          "检测到后台已更新完整频道目录，拒绝旧目录覆盖；播放不受影响");
      uiHandler.post(()->{
        if(!isFinishing()&&!isDestroyed()&&info!=null)
          info.setText("已保护后台更新的完整频道目录，本次旧目录保存被拒绝；"
              +"将尝试重新读取已保存快照。");
        if(activityStarted&&!isFinishing()&&!isDestroyed())
          reloadChannelsAfterBackgroundUpdateIfNeeded();
      });
      return false;
    }catch(Throwable failure){
      android.util.Log.e("MyTVCatalogue","Room catalogue transaction failed",failure);
      saveLiveDiagnostic("CATALOGUE_PERSIST_ROOM_ERROR",
          failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));
      return false;
    }
  }
  // Cold start must land on the mainland-China directory, never the mixed global list.
  private String activeCategory="中国";
  private void makeUi(){
    LinearLayout root=new LinearLayout(this);rootLayout=root;root.setOrientation(0);root.setPadding(dp(10),dp(8),dp(10),dp(8));root.setBackgroundColor(Color.rgb(8,13,24));
    LinearLayout cats=new LinearLayout(this);categoryPane=cats;cats.setOrientation(1);cats.setBackgroundColor(Color.rgb(16,24,40));
    TextView logo=new TextView(this);logo.setText("我的\n电视");logo.setTextColor(Color.WHITE);logo.setTextSize(21);logo.setGravity(Gravity.CENTER);cats.addView(logo,new LinearLayout.LayoutParams(-1,dp(64)));
    // China is the default home directory; CCTV, satellite and local areas remain
    // available as focused subcategories, while HK/Macau/Taiwan and global stay separate.
    String[] names={"中国","收藏","点播","央视","卫视","地方","港澳台","全部","全球频道","国家地区","娱乐频道","国际新闻","体育","天气","教育","自建频道"};
    LinearLayout catItems=new LinearLayout(this);categoryMenuItems=catItems;catItems.setOrientation(1);
    for(String name:names){Button x=new Button(this);x.setText(name);x.setTextColor(Color.WHITE);x.setTextSize(15);x.setMinHeight(dp(0));x.setMinimumHeight(0);x.setPadding(dp(4),0,dp(4),0);styleTvButton(x,name.equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40));x.setOnClickListener(v->{android.util.Log.i("MyTVDirectory","category_click name="+name+" startupReady="+startupDataReady+" channels="+channels.size());if(!startupDataReady){pendingStartupCategory=name;return;}pendingStartupCategory=null;if(name.equals("点播")){vodDialog();return;}activeGeoScope=null;activeGeoCountry="";activeGeoLabel="";localRegionFilterActive=false;activeLocalProvince=null;activeLocalCity=null;livePageIndex=0;activeCategory=name;favoriteMode=name.equals("收藏");for(int i=0;i<catItems.getChildCount();i++){View z=catItems.getChildAt(i);if(z instanceof Button)((Button)z).setBackground(makeButtonBg(((Button)z).getText().toString().equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40),z.hasFocus()));}if(name.equals("国家地区")){
      // "国家地区" opens a picker rather than being a channel category; showing
      // the unmatched label as a filter made the entire saved list appear empty.
      activeCategory="全球频道";refresh();
      info.setText("已显示保存的全球频道，请选择国家或地区；选定后优先展示本地缓存。");
      showCountryDialog();return;
    }
    if(name.equals("地方")){
      // The first tap must immediately display saved local stations, regardless
      // of whether the province picker is used or dismissed.
      refresh();
      info.setText("正在显示保存的地方台；可继续选择全部地方台、省份或城市。");
      showProvinceDialog();return;
    }if(name.equals("全球频道"))loadGlobalChannels();if(name.equals("娱乐频道"))loadEntertainmentChannels();if(name.equals("港澳台")||name.equals("香港")||name.equals("澳门")||name.equals("台湾"))loadRegionChannels();if(name.equals("央视")||name.equals("卫视"))loadChinaChannels();v.requestFocus();refresh();});catItems.addView(x,new LinearLayout.LayoutParams(-1,dp(43)));}
    ScrollView catScroll=new ScrollView(this);catScroll.setFillViewport(false);catScroll.addView(catItems);cats.addView(catScroll,new LinearLayout.LayoutParams(-1,0,1));
    Button settings=new Button(this);settings.setText("设置");settings.setTextColor(Color.WHITE);styleTvButton(settings,Color.rgb(35,45,62));settings.setOnClickListener(v->{if(!startupDataReady)return;new AlertDialog.Builder(this).setTitle("设置与管理").setItems(new String[]{"导入 M3U 文件","导入播放列表网址","手动添加频道","导出 M3U 配置","官方平台入口","全球正版电影点播","解码与声音设置","卡顿自动切换备用源","立即更新频道源","后台自动更新："+(getSharedPreferences("mytv",0).getBoolean("auto_source_update",true)?"开启":"关闭"),"查看更新状态","右下角显示网速："+(getSharedPreferences("mytv",0).getBoolean("show_network_speed",false)?"开启":"关闭"),"儿童锁与时段管理","云端家长锁设置","查看直播诊断记录","停止直播，继续首次抓源"},(d,w)->{if(w==0)chooseFile();else if(w==1)urlDialog();else if(w==2)addDialog();else if(w==3)exportFile();else if(w==4)officialDialog();else if(w==5)vodDialog();else if(w==6)decoderSettingsDialog();else if(w==7)bufferingSwitchSettingsDialog();else if(w==8)startManualSourceUpdate();else if(w==9)toggleAutomaticSourceUpdates();else if(w==10)showSourceUpdateStatus();else if(w==11)toggleNetworkSpeed();else if(w==12)childLockSettingsDialog();else if(w==13)configureRemoteParentLock();else if(w==14)showLiveDiagnostics();else if(w==15)stopLiveForInitialDiscovery();}).show();});cats.addView(settings,new LinearLayout.LayoutParams(-1,dp(48)));
    LinearLayout channelPane=new LinearLayout(this);this.channelPane=channelPane;channelPane.setOrientation(1);channelPane.setPadding(dp(8),0,dp(8),0);channelPane.setBackgroundColor(Color.rgb(19,29,47));
    TextView title=new TextView(this);title.setText("频道列表");title.setTextColor(Color.WHITE);title.setTextSize(19);title.setPadding(dp(8),dp(10),0,dp(8));channelPane.addView(title);
    search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);search.setHintTextColor(Color.LTGRAY);search.setHint("搜索频道");search.setEnabled(false);channelPane.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));
    LinearLayout livePaging=new LinearLayout(this);livePaging.setOrientation(0);livePaging.setGravity(Gravity.CENTER_VERTICAL);
    livePreviousPageButton=new Button(this);livePreviousPageButton.setText("上一页");livePreviousPageButton.setAllCaps(false);livePreviousPageButton.setTextSize(12);livePreviousPageButton.setMinHeight(0);livePreviousPageButton.setMinimumHeight(0);livePreviousPageButton.setPadding(dp(2),0,dp(2),0);styleTvButton(livePreviousPageButton,Color.rgb(35,55,78));
    livePageStatus=new Button(this);livePageStatus.setAllCaps(false);livePageStatus.setTextSize(11);
    livePageStatus.setSingleLine(true);livePageStatus.setMinWidth(0);livePageStatus.setMinimumWidth(0);
    livePageStatus.setMinHeight(0);livePageStatus.setMinimumHeight(0);
    livePageStatus.setGravity(Gravity.CENTER);livePageStatus.setPadding(dp(2),0,dp(2),0);
    livePageStatus.setText("1/1 页 ▾");styleTvButton(livePageStatus,Color.rgb(35,55,78));
    livePageStatus.setId(View.generateViewId());
    livePageStatus.setContentDescription("选择频道列表页码");
    livePageStatus.setOnClickListener(v->showLivePagePicker());
    liveNextPageButton=new Button(this);liveNextPageButton.setText("下一页");liveNextPageButton.setAllCaps(false);liveNextPageButton.setTextSize(12);liveNextPageButton.setMinHeight(0);liveNextPageButton.setMinimumHeight(0);liveNextPageButton.setPadding(dp(2),0,dp(2),0);styleTvButton(liveNextPageButton,Color.rgb(35,55,78));
    livePaging.addView(livePreviousPageButton,new LinearLayout.LayoutParams(0,dp(34),1));livePaging.addView(livePageStatus,new LinearLayout.LayoutParams(0,dp(34),1));livePaging.addView(liveNextPageButton,new LinearLayout.LayoutParams(0,dp(34),1));
    // First full source discovery is visible without covering playback or
    // blocking TV remote focus. This panel stays hidden after a completed build.
    initialDiscoveryProgressPanel=new LinearLayout(this);
    initialDiscoveryProgressPanel.setOrientation(1);
    initialDiscoveryProgressPanel.setPadding(dp(6),dp(3),dp(6),dp(3));
    initialDiscoveryProgressPanel.setBackground(makeButtonBg(Color.rgb(25,40,64),false));
    initialDiscoveryProgressTitle=new TextView(this);
    initialDiscoveryProgressTitle.setText("抓源进度 · 读取中");
    initialDiscoveryProgressTitle.setSingleLine(true);
    initialDiscoveryProgressTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    initialDiscoveryProgressTitle.setIncludeFontPadding(false);
    initialDiscoveryProgressTitle.setTextSize(13);
    initialDiscoveryProgressTitle.setTextColor(Color.WHITE);
    initialDiscoveryProgressTitle.setTypeface(null,android.graphics.Typeface.BOLD);
    initialDiscoveryProgressPanel.addView(initialDiscoveryProgressTitle);
    initialDiscoveryProgressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    initialDiscoveryProgressBar.setIndeterminate(false);
    initialDiscoveryProgressBar.setMax(10000);
    initialDiscoveryProgressBar.setProgress(0);
    LinearLayout.LayoutParams progressBarParams=new LinearLayout.LayoutParams(-1,dp(7));
    progressBarParams.topMargin=dp(3);
    initialDiscoveryProgressPanel.addView(initialDiscoveryProgressBar,progressBarParams);
    // Keep the weighted channel ScrollView usable on short landscape phone screens.
    // Full details are available without reserving six lines above the channel rows.
    initialDiscoveryProgressPanel.setContentDescription("抓源进度，点击查看详情");
    initialDiscoveryProgressPanel.setOnClickListener(v->{
      if(!activityStarted||isFinishing()||isDestroyed())return;
      AlertDialog.Builder details=new AlertDialog.Builder(this).setTitle(initialDiscoveryProgressTitle.getText())
          .setMessage(initialDiscoveryProgressDetail)
          .setPositiveButton("关闭",null);
      if(roomFullRebuildCompletedAt<=0L){
        if(SourceUpdateActivityPolicy.isPlaybackActive())
          details.setNeutralButton("停止直播，继续抓源",(d,w)->stopLiveForInitialDiscovery());
        else details.setNeutralButton("立即续抓（保留进度）",(d,w)->resumeIdleInitialDiscovery());
      }
      AlertDialog dialog=details.create();
      initialDiscoveryDetailsDialog=dialog;
      dialog.setOnKeyListener((d,key,event)->{
        if(event.getAction()==KeyEvent.ACTION_DOWN)pauseStableDiscovery();
        return false;
      });
      dialog.setOnDismissListener(d->{if(initialDiscoveryDetailsDialog==dialog){
        initialDiscoveryDetailsDialog=null;pauseStableDiscovery();
      }});
      dialog.show();
      View messageView=dialog.findViewById(android.R.id.message);
      if(messageView!=null)messageView.setOnTouchListener((messageTarget,event)->{
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)pauseStableDiscovery();
        return false;
      });
    });
    // The entire progress card is one remote/touch target; neither the title
    // nor the progress bar should take focus away from its click action.
    initialDiscoveryProgressPanel.setId(View.generateViewId());
    initialDiscoveryProgressPanel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    initialDiscoveryProgressPanel.setFocusable(true);
    initialDiscoveryProgressPanel.setClickable(true);
    initialDiscoveryProgressPanel.setOnFocusChangeListener((v,focused)->{
      v.setBackground(makeButtonBg(Color.rgb(25,40,64),focused));
    });
    // Up from either enabled paging button, or down from search, reaches
    // progress while visible. Android skips the target once it is GONE.
    search.setNextFocusDownId(initialDiscoveryProgressPanel.getId());
    livePreviousPageButton.setNextFocusUpId(initialDiscoveryProgressPanel.getId());
    liveNextPageButton.setNextFocusUpId(initialDiscoveryProgressPanel.getId());
    livePageStatus.setNextFocusUpId(initialDiscoveryProgressPanel.getId());
    livePreviousPageButton.setNextFocusRightId(livePageStatus.getId());
    liveNextPageButton.setNextFocusLeftId(livePageStatus.getId());
    channelPane.addView(initialDiscoveryProgressPanel,new LinearLayout.LayoutParams(-1,dp(38)));
    channelPane.addView(livePaging,new LinearLayout.LayoutParams(-1,dp(36)));
    livePreviousPageButton.setOnClickListener(v->requestLivePage(livePageIndex-1,v));
    liveNextPageButton.setOnClickListener(v->requestLivePage(livePageIndex+1,v));

    list=new LinearLayout(this);list.setOrientation(1);channelScroll=new ScrollView(this){
      @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)liveDirectoryTouchGate.begin();
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL
            ||(action==MotionEvent.ACTION_DOWN&&!handled))finishLiveDirectoryTouch();
        return handled;
      }
      @Override protected void onDetachedFromWindow(){
        finishLiveDirectoryTouch();super.onDetachedFromWindow();
      }
    };channelScroll.setFocusable(false);channelScroll.addView(list);channelPane.addView(channelScroll,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout main=new LinearLayout(this);mainPane=main;main.setOrientation(1);main.setPadding(dp(10),0,0,0);
    info=new TextView(this);playbackInfo=info;info.setText("欢迎使用我的电视\\n请从左侧选择栏目，或在设置中导入你有权使用的直播源。");info.setTextColor(Color.WHITE);info.setTextSize(16);info.setPadding(dp(8),dp(8),dp(8),dp(8));info.setOnClickListener(v->showLiveDiagnostics());info.setContentDescription("点击查看直播诊断信息");main.addView(info,new LinearLayout.LayoutParams(-1,dp(62)));
    liveStage=new FrameLayout(this){
      private boolean ownsVideoGesture,tapCandidate;
      private float downX,downY;
      private boolean hitsControl(View control,MotionEvent event){
        return control!=null&&control.isShown()&&control.getAlpha()>0.05f
            &&event.getX()>=control.getLeft()&&event.getX()<control.getRight()
            &&event.getY()>=control.getTop()&&event.getY()<control.getBottom();
      }
      @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
          fullscreenControlGesture=false;
          boolean hitsButton=fullscreenControlsVisible&&(hitsControl(fullscreenMenuButton,event)
              ||hitsControl(fullscreenSourceButton,event)||hitsControl(fullscreenFavoriteButton,event)
              ||hitsControl(fullscreenActionsButton,event)||hitsControl(fullscreenBlockSourceButton,event));
          if(fullScreenPlayback&&activityStarted&&currentVodPanel==null
              &&!isAnyPlaybackLockActive()&&!fullscreenRemoteDialogOpen
              &&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())&&hitsButton){
            fullscreenControlGesture=true;
            uiHandler.removeCallbacks(hideFullscreenControls);
          }
          ownsVideoGesture=fullScreenPlayback&&activityStarted&&currentVodPanel==null
              &&!isAnyPlaybackLockActive()&&!fullscreenRemoteDialogOpen
              &&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())
              &&!hitsButton&&!hitsControl(channelZapOverlay,event);
          tapCandidate=ownsVideoGesture;
          downX=event.getX();downY=event.getY();
        }
        if(ownsVideoGesture){
          if(action==MotionEvent.ACTION_POINTER_DOWN||event.getPointerCount()!=1
              ||Math.abs(event.getX()-downX)>dp(24)||Math.abs(event.getY()-downY)>dp(24))
            tapCandidate=false;
          if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            boolean toggle=action==MotionEvent.ACTION_UP&&tapCandidate;
            ownsVideoGesture=false;tapCandidate=false;
            if(toggle&&fullScreenPlayback&&activityStarted&&currentVodPanel==null
                &&!isAnyPlaybackLockActive()&&!fullscreenRemoteDialogOpen
                &&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())){
              if(fullscreenControlsVisible)hideFullscreenControlsNow();else showFullscreenControls();
            }
          }
          return true;
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(fullscreenControlGesture&&(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL
            ||(action==MotionEvent.ACTION_DOWN&&!handled))){
          fullscreenControlGesture=false;
          if(fullscreenControlsVisible&&fullScreenPlayback)scheduleFullscreenControlsHide();
        }
        return handled;
      }
      @Override protected void onDetachedFromWindow(){
        fullscreenControlGesture=false;
        ownsVideoGesture=false;tapCandidate=false;
        super.onDetachedFromWindow();
      }
    };liveStage.setFocusable(true);liveStage.setFocusableInTouchMode(true);video=new PlayerView(this);video.setKeepContentOnPlayerReset(false);video.setShutterBackgroundColor(Color.BLACK);liveStage.addView(video,new FrameLayout.LayoutParams(-1,-1));vlcVideoLayout=new VLCVideoLayout(this);vlcVideoLayout.setFocusable(false);vlcVideoLayout.setFocusableInTouchMode(false);vlcVideoLayout.setClickable(false);vlcVideoLayout.setVisibility(View.GONE);liveStage.addView(vlcVideoLayout,new FrameLayout.LayoutParams(-1,-1));
    livePictureCover=new View(this);livePictureCover.setBackgroundColor(Color.BLACK);
    livePictureCover.setElevation(dp(1));livePictureCover.setFocusable(false);livePictureCover.setClickable(false);
    livePictureCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    liveStage.addView(livePictureCover,new FrameLayout.LayoutParams(-1,-1));
    int liveAspect=getSharedPreferences("mytv",0).getInt("live_aspect_mode",0);video.setResizeMode(liveAspect==1?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM:liveAspect==2?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL:androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);// 按原始比例完整显示，避免部分直播源全屏时被放大裁切。直播采用实时电视控制：不显示进度条、快退/快进和暂停控制；音量仍由电视遥控器系统音量键控制。
    video.setUseController(false);video.setFocusable(true);video.setFocusableInTouchMode(true);video.setBackgroundColor(Color.BLACK);video.setOnClickListener(v->{if(fullScreenPlayback){if(fullscreenControlsVisible)hideFullscreenControlsNow();else showFullscreenControls();}});fullscreenMenuButton=new TextView(this);fullscreenMenuButton.setText("☰ 频道选择");fullscreenMenuButton.setTextColor(Color.WHITE);fullscreenMenuButton.setTextSize(16);fullscreenMenuButton.setGravity(Gravity.CENTER);fullscreenMenuButton.setPadding(dp(14),dp(9),dp(14),dp(9));fullscreenMenuButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenMenuButton.setFocusable(true);fullscreenMenuButton.setClickable(true);fullscreenMenuButton.setElevation(dp(12));fullscreenMenuButton.setOnClickListener(v->showFullscreenChannelMenu());liveStage.addView(fullscreenMenuButton,new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.LEFT));fullscreenMenuButton.setVisibility(View.GONE);channelZapOverlay=new TextView(this);channelZapOverlay.setGravity(Gravity.CENTER);channelZapOverlay.setTextColor(Color.WHITE);channelZapOverlay.setTextSize(17);channelZapOverlay.setTypeface(null,android.graphics.Typeface.BOLD);channelZapOverlay.setMaxWidth(dp(420));channelZapOverlay.setMaxLines(2);channelZapOverlay.setEllipsize(android.text.TextUtils.TruncateAt.END);channelZapOverlay.setPadding(dp(12),dp(7),dp(12),dp(7));GradientDrawable zapBg=new GradientDrawable();zapBg.setColor(0xDD101A2A);zapBg.setCornerRadius(dp(9));zapBg.setStroke(dp(1),0xFF6EA8E8);channelZapOverlay.setBackground(zapBg);channelZapOverlay.setElevation(dp(16));channelZapOverlay.setClickable(true);channelZapOverlay.setContentDescription("点击此状态框查看直播诊断记录");channelZapOverlay.setOnClickListener(v->showLiveDiagnostics());channelZapOverlay.setVisibility(View.GONE);FrameLayout.LayoutParams zapParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);zapParams.topMargin=dp(16);liveStage.addView(channelZapOverlay,zapParams);fullscreenSourceButton=new TextView(this);fullscreenSourceButton.setText("换源");fullscreenSourceButton.setTextColor(Color.WHITE);fullscreenSourceButton.setTextSize(15);fullscreenSourceButton.setGravity(Gravity.CENTER);fullscreenSourceButton.setPadding(dp(14),dp(9),dp(14),dp(9));fullscreenSourceButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenSourceButton.setElevation(dp(12));fullscreenSourceButton.setFocusable(true);fullscreenSourceButton.setClickable(true);fullscreenSourceButton.setOnClickListener(v->showSourcePicker());FrameLayout.LayoutParams sourceParams=new FrameLayout.LayoutParams(-2,dp(46),Gravity.TOP|Gravity.RIGHT);sourceParams.topMargin=dp(8);sourceParams.rightMargin=dp(8);liveStage.addView(fullscreenSourceButton,sourceParams);fullscreenSourceButton.setVisibility(View.GONE);fullscreenFavoriteButton=new TextView(this);fullscreenFavoriteButton.setText("☆ 收藏");fullscreenFavoriteButton.setTextColor(Color.WHITE);fullscreenFavoriteButton.setTextSize(15);fullscreenFavoriteButton.setGravity(Gravity.CENTER);fullscreenFavoriteButton.setPadding(dp(12),dp(9),dp(12),dp(9));fullscreenFavoriteButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenFavoriteButton.setElevation(dp(12));fullscreenFavoriteButton.setFocusable(true);fullscreenFavoriteButton.setClickable(true);fullscreenFavoriteButton.setContentDescription("收藏当前频道");fullscreenFavoriteButton.setOnClickListener(v->toggleCurrentLiveFavorite());FrameLayout.LayoutParams favoriteParams=new FrameLayout.LayoutParams(-2,dp(46),Gravity.TOP|Gravity.RIGHT);favoriteParams.topMargin=dp(8);favoriteParams.rightMargin=dp(76);liveStage.addView(fullscreenFavoriteButton,favoriteParams);fullscreenFavoriteButton.setVisibility(View.GONE);
    fullscreenActionsButton=new TextView(this);fullscreenActionsButton.setText("⚙ 功能");
    fullscreenActionsButton.setTextColor(Color.WHITE);fullscreenActionsButton.setTextSize(16);
    fullscreenActionsButton.setGravity(Gravity.CENTER);fullscreenActionsButton.setPadding(dp(14),dp(9),dp(14),dp(9));
    fullscreenActionsButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));
    fullscreenActionsButton.setElevation(dp(12));fullscreenActionsButton.setFocusable(false);
    fullscreenActionsButton.setClickable(true);fullscreenActionsButton.setContentDescription("打开直播全屏功能菜单");
    fullscreenActionsButton.setOnClickListener(v->{
      if(!activityStarted||!fullScreenPlayback||currentVodPanel!=null||isAnyPlaybackLockActive()
          ||fullscreenRemoteDialogOpen||(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()))return;
      hideFullscreenControlsNow();showLivePlaybackActions();
    });
    FrameLayout.LayoutParams actionsParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.BOTTOM|Gravity.LEFT);
    actionsParams.leftMargin=dp(8);actionsParams.bottomMargin=dp(8);
    liveStage.addView(fullscreenActionsButton,actionsParams);fullscreenActionsButton.setVisibility(View.GONE);
    fullscreenBlockSourceButton=new TextView(this);
    fullscreenBlockSourceButton.setTextColor(Color.WHITE);fullscreenBlockSourceButton.setTextSize(13);
    fullscreenBlockSourceButton.setGravity(Gravity.CENTER);fullscreenBlockSourceButton.setPadding(dp(8),0,dp(8),0);
    fullscreenBlockSourceButton.setMaxLines(2);fullscreenBlockSourceButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
    fullscreenBlockSourceButton.setMaxWidth(dp(148));fullscreenBlockSourceButton.setMinWidth(dp(112));
    fullscreenBlockSourceButton.setBackground(makeButtonBg(Color.rgb(35,55,78),false));
    fullscreenBlockSourceButton.setElevation(dp(12));fullscreenBlockSourceButton.setFocusable(false);
    fullscreenBlockSourceButton.setClickable(true);
    FrameLayout.LayoutParams blockParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
    blockParams.bottomMargin=dp(8);
    liveStage.addView(fullscreenBlockSourceButton,blockParams);fullscreenBlockSourceButton.setVisibility(View.GONE);
    networkSpeedOverlay=new TextView(this);networkSpeedOverlay.setElevation(dp(2));networkSpeedOverlay.setTextColor(Color.WHITE);networkSpeedOverlay.setTextSize(13);networkSpeedOverlay.setTypeface(null,android.graphics.Typeface.BOLD);networkSpeedOverlay.setPadding(dp(10),dp(6),dp(10),dp(6));GradientDrawable speedBg=new GradientDrawable();speedBg.setColor(0x99000000);speedBg.setCornerRadius(dp(7));networkSpeedOverlay.setBackground(speedBg);networkSpeedOverlay.setText("接收 0 KB/s");networkSpeedOverlay.setVisibility(getSharedPreferences("mytv",0).getBoolean("show_network_speed",false)?View.VISIBLE:View.GONE);FrameLayout.LayoutParams speedParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);speedParams.rightMargin=dp(12);speedParams.bottomMargin=dp(12);liveStage.addView(networkSpeedOverlay,speedParams);main.addView(liveStage,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(cats,new LinearLayout.LayoutParams(dp(88),-1));root.addView(channelPane,new LinearLayout.LayoutParams(dp(220),-1));root.addView(main,new LinearLayout.LayoutParams(0,-1,1));setContentView(root);normalizeFrameLayoutChildren(root);
    search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){clearPendingRemoteZap();livePageIndex=0;if(pendingSearchRefresh!=null)uiHandler.removeCallbacks(pendingSearchRefresh);pendingSearchRefresh=()->refresh();uiHandler.postDelayed(pendingSearchRefresh,220);}public void afterTextChanged(android.text.Editable e){}});
    refresh();
    root.post(()->{if(!fullScreenPlayback&&root.findFocus()==null&&catItems.getChildCount()>0)catItems.getChildAt(0).requestFocus();});
    if(!channels.isEmpty())info.setText("已载入 "+channels.size()+" 个频道 · 选择频道开始播放");
    // Restore the last channel only after the first frame is on screen. Player creation
    // can be expensive on older Android TV boxes and should not block app entry.
    // Last-channel restoration is scheduled after asynchronous catalogue recovery in onCreate.
    updateNetworkSpeedMonitor();
  }
  // Do not forward every remote-control key to PlayerView.
  // Doing so steals DPAD focus from AlertDialog lists on Android TV.
  private void scheduleAutomaticSourceUpdates(){
    WorkManager wm=WorkManager.getInstance(this);
    android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
    boolean enabled=prefs.getBoolean("auto_source_update",true);
    if(!enabled){
      wm.cancelUniqueWork("mytv_periodic_source_update");
      wm.cancelUniqueWork("mytv_startup_source_refresh");
      return;
    }
    Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    // The one-time full rebuild publishes an entire catalogue before routine
    // maintenance is eligible. Existing channels remain usable during this build.
    if(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(
        enabled,roomFullRebuildCompletedAt)){
      wm.cancelUniqueWork("mytv_periodic_source_update");
      // The first discovery is part of first-run setup, not a routine background
      // refresh. Let it run while the catalogue screen is open; playback-active
      // protection inside the Worker still pauses it if the user starts watching.
      OneTimeWorkRequest startupRefresh=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
          .setConstraints(constraints)
          .setInputData(new Data.Builder().putBoolean("manual",true).putBoolean("initial_discovery_chain",true).build())
          .build();
      wm.enqueueUniqueWork("mytv_startup_source_refresh",ExistingWorkPolicy.KEEP,startupRefresh);
      return; // Never run a routine refresh concurrently with the first full build.
    }
    if(!SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(
        enabled,roomFullRebuildCompletedAt))return;
    PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(SourceUpdateWorker.class,
        SourceUpdateSchedulePolicy.ROUTINE_ELIGIBILITY_CHECK_DAYS,TimeUnit.DAYS)
        .setConstraints(constraints).build();
    // The daily job only checks whether a 30-day maintenance pass is due.
    // The worker exits before discovery while busy or not due.
    wm.enqueueUniquePeriodicWork("mytv_periodic_source_update",ExistingPeriodicWorkPolicy.UPDATE,request);
  }
  // Explicit user choice, never invoked by automatic maintenance.
  private void stopLiveForInitialDiscovery(){
    if(!activityStarted||isFinishing()||isDestroyed()||currentVodPanel!=null||isAnyPlaybackLockActive())return;
    if(roomFullRebuildCompletedAt>0L){toast("首次完整抓源已经完成");return;}
    saveLastChannel();
    startupAutoResumePending=false;startupResumeIndex=-1;startupResumeChannel=null;
    if(startupAutoResumeTask!=null){uiHandler.removeCallbacks(startupAutoResumeTask);startupAutoResumeTask=null;}
    resumePlaybackOnStart=false;resumeLiveAfterVod=false;
    ++playbackGeneration;currentMediaId="";
    stopFailedLiveSession("已停止直播，继续首次抓源；进度保留");
    selected=-1;
    if(fullScreenPlayback)exitFullScreenPlayback();else refresh();
    reloadChannelsAfterBackgroundUpdateIfNeeded();
    resumeIdleInitialDiscovery();
  }
  // Explicit recovery only. Never cancel an actively running source worker or
  // delete Room staging just because the scheduler has a delayed request.
  private void resumeIdleInitialDiscovery(){
    if(!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive()
        ||currentVodPanel!=null||roomFullRebuildCompletedAt>0L||initialResumeRequestPending)return;
    if(SourceUpdateActivityPolicy.isPlaybackActive()){toast("播放资源仍在释放，请稍后重试");return;}
    initialResumeRequestPending=true;
    try{worker.execute(()->{
      final WorkManager manager=WorkManager.getInstance(getApplicationContext());
      boolean running=false,unfinished=false;
      boolean readable=true;
      try{
        for(androidx.work.WorkInfo info:manager.getWorkInfosForUniqueWork("mytv_startup_source_refresh").get(3L,TimeUnit.SECONDS)){
          running|=info.getState()==androidx.work.WorkInfo.State.RUNNING;
          unfinished|=!info.getState().isFinished();
        }
      }catch(Exception unavailable){readable=false;if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}
      final boolean active=running,pending=unfinished,known=readable;
      runOnUiThread(()->{
        initialResumeRequestPending=false;
        if(!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive()
            ||currentVodPanel!=null||roomFullRebuildCompletedAt>0L||SourceUpdateActivityPolicy.isPlaybackActive())return;
        if(!known){toast("暂时无法确认抓源任务状态，请重试");return;}
        if(active||SourceUpdateWorker.isSourceWorkActive()){toast("抓源任务正在运行或释放资源，保留当前任务");return;}
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,30L,TimeUnit.SECONDS)
            .setInputData(new Data.Builder().putBoolean("manual",true).putBoolean("initial_discovery_chain",true).build()).build();
        // REPLACE only the explicitly requested idle recovery. The worker's
        // ownership gate and transactional pause checks protect a race at dispatch.
        manager.enqueueUniqueWork("mytv_startup_source_refresh",pending?ExistingWorkPolicy.REPLACE:ExistingWorkPolicy.KEEP,request);
        toast("已请求立即续抓，保留已保存频道和进度；实际启动由系统调度");
        startInitialDiscoveryProgressTicker();
      });
    });}catch(RuntimeException rejected){initialResumeRequestPending=false;toast("暂时无法读取抓源任务状态");}
  }
  // Reading discovery progress must not itself permanently block a healthy
  // playing channel from granting its bounded low-priority discovery lease.
  // Require the exact tracked dialog to own focus, not just to be visible.
  private boolean hasStableDiscoveryWindowFocus(){
    if(hasWindowFocus())return true;
    AlertDialog dialog=initialDiscoveryDetailsDialog;
    Window window=dialog==null?null:dialog.getWindow();
    return dialog!=null&&dialog.isShowing()&&window!=null
        &&window.getDecorView().hasWindowFocus();
  }
  // Shared UI/resource gate; each engine still proves its own output health.
  private boolean canGrantStableDiscoveryUi(){
    return activityStarted&&hasStableDiscoveryWindowFocus()&&currentVodPanel==null
        &&!fullscreenControlsVisible&&!fullscreenRemoteDialogOpen
        &&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())
        &&!isAnyPlaybackLockActive()&&!libVlcReleaseInProgress&&!decoderReleaseUnconfirmed;
  }
  private void refreshInitialDiscoveryDetailsDialog(){
    AlertDialog dialog=initialDiscoveryDetailsDialog;
    if(dialog!=null&&dialog.isShowing()){
      dialog.setTitle(initialDiscoveryProgressTitle.getText());
      dialog.setMessage(initialDiscoveryProgressDetail);
    }
  }
  private void startInitialDiscoveryProgressTicker(){
    stopInitialDiscoveryProgressTicker();
    if(initialDiscoveryProgressPanel==null)return;
    if(roomFullRebuildCompletedAt>0L&&!initialFinalCataloguePending){
      initialDiscoveryProgressPanel.setVisibility(View.GONE);
      return;
    }
    initialDiscoveryProgressPanel.setVisibility(View.VISIBLE);
    initialDiscoveryProgressTicker=new Runnable(){
      @Override public void run(){
        if(!activityStarted||isFinishing()||isDestroyed()||initialDiscoveryProgressPanel==null)return;
        requestInitialDiscoveryProgress();
        if(roomFullRebuildCompletedAt<=0L||initialFinalCataloguePending)
          uiHandler.postDelayed(this,4000L);
      }
    };
    uiHandler.post(initialDiscoveryProgressTicker);
  }

  private void stopInitialDiscoveryProgressTicker(){
    if(initialDiscoveryProgressTicker!=null){
      uiHandler.removeCallbacks(initialDiscoveryProgressTicker);
      initialDiscoveryProgressTicker=null;
    }
  }

  private void requestInitialDiscoveryProgress(){
    if(initialDiscoveryProgressPanel==null||!activityStarted
        ||!initialDiscoveryProgressReadPending.compareAndSet(false,true))return;
    try{
      worker.execute(()->{
        try{
          final android.content.Context context=getApplicationContext();
          final String prefix="source_initial_progress_";
          java.util.ArrayList<String> keys=new java.util.ArrayList<>();
          keys.add("source_full_rebuild_completed_at");
          keys.add("source_full_rebuild_active");
          keys.add(FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY);
          keys.add(FullDiscoveryProgressPolicy.PREVIEW_MILESTONE_KEY);
          keys.add(FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY);
          keys.add(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY);
          keys.add("source_initial_index_attempted");
          keys.add("source_initial_index_bypassed");
          keys.add("source_initial_index_ready");
          keys.add("source_initial_index_entries");
          final int configuredFeeds=SourceDiscoveryFeedCatalog.feeds().length;
          for(int i=0;i<configuredFeeds;i++)keys.add(FullDiscoveryProgressPolicy.initialAcquiredVersionKey(i));
          for(String suffix:new String[]{"done_feeds","total_feeds","china_done_feeds",
              "china_total_feeds","china_validated_urls","staged_channels","checked_urls",
              "validated_urls","timeout_urls","unavailable_feeds","feed_index","feed_cursor","feed_entries",
              "percent","basis_points","phase"})keys.add(prefix+suffix);
          final Map<String,String> snapshot=com.wodedianshi.tv.db.AppStateStore.getValues(context,keys);
          final long completed=readStateLong(snapshot,"source_full_rebuild_completed_at",0L);
          final boolean buildActive="true".equals(snapshot.get("source_full_rebuild_active"));
          final com.wodedianshi.tv.db.CatalogueMigrationEntity finalCheckpoint=completed>0L
              ?com.wodedianshi.tv.db.RoomDatabaseProvider.get(context).channelDao()
                  .getMigration(com.wodedianshi.tv.db.MasterCatalogueRepository.MIGRATION_KEY):null;
          final long observedFinalVersion=finalCheckpoint==null?-1L:finalCheckpoint.completedAt;
          final int previewLevel=(int)readStateLong(snapshot,FullDiscoveryProgressPolicy.PREVIEW_LEVEL_KEY,0L);
          final int previewMilestone=(int)readStateLong(snapshot,FullDiscoveryProgressPolicy.PREVIEW_MILESTONE_KEY,0L);
          final int previewCoreMask=(int)readStateLong(snapshot,FullDiscoveryProgressPolicy.PREVIEW_CORE_MASK_KEY,0L);
          final int indexAttempted=(int)readStateLong(snapshot,"source_initial_index_attempted",0L);
          final boolean indexBypassed="true".equals(snapshot.get("source_initial_index_bypassed"));
          final int indexReady=(int)readStateLong(snapshot,"source_initial_index_ready",0L);
          final long indexEntries=readStateLong(snapshot,"source_initial_index_entries",0L);
          final int preparedFeeds=FullDiscoveryProgressPolicy.recordedAcquisitions(snapshot,0,configuredFeeds);
          final int preparedMainland=FullDiscoveryProgressPolicy.recordedAcquisitions(snapshot,0,SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
          final int done=(int)readStateLong(snapshot,prefix+"done_feeds",0L);
          final int total=(int)readStateLong(snapshot,prefix+"total_feeds",0L);
          final int chinaDone=(int)readStateLong(snapshot,prefix+"china_done_feeds",0L);
          final int chinaTotal=(int)readStateLong(snapshot,prefix+"china_total_feeds",0L);
          final int chinaValidated=(int)readStateLong(snapshot,prefix+"china_validated_urls",0L);
          final int staged=(int)readStateLong(snapshot,prefix+"staged_channels",0L);
          final int checked=(int)readStateLong(snapshot,prefix+"checked_urls",0L);
          final int validated=(int)readStateLong(snapshot,prefix+"validated_urls",0L);
          final int timeouts=(int)readStateLong(snapshot,prefix+"timeout_urls",0L);
          final int unavailable=(int)readStateLong(snapshot,prefix+"unavailable_feeds",0L);
          final int feedIndex=(int)readStateLong(snapshot,prefix+"feed_index",0L);
          final int cursor=(int)readStateLong(snapshot,prefix+"feed_cursor",0L);
          final int entries=(int)readStateLong(snapshot,prefix+"feed_entries",0L);
          final int percent=(int)readStateLong(snapshot,prefix+"percent",0L);
          final int basisPoints=(int)readStateLong(snapshot,prefix+"basis_points",100L*Math.max(0,Math.min(99,percent)));
          final String phase=snapshot.getOrDefault(prefix+"phase","尚未开始首次完整抓源");
          final String freshCoverage=snapshot.getOrDefault(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY,"");
          runOnUiThread(()->{
            if(isFinishing()||isDestroyed()||!activityStarted
                ||initialDiscoveryProgressPanel==null)return;
            if(completed<=0L&&startupDataReady&&(previewLevel!=displayedInitialPreviewLevel||previewMilestone!=displayedInitialPreviewMilestone
                ||previewCoreMask!=displayedInitialPreviewCoreMask)){
              displayedInitialPreviewLevel=previewLevel;
              displayedInitialPreviewMilestone=previewMilestone;
              displayedInitialPreviewCoreMask=previewCoreMask;
              if(previewLevel>0||previewMilestone>0||previewCoreMask>0)initialPreviewReloadPending=true;
            }
            long previewNow=android.os.SystemClock.elapsedRealtime();
            if(completed<=0L&&startupDataReady&&initialPreviewReloadPending
                &&(!hasPlaybackCatalogueOwner()||mayAppendInitialLivePreview())
                &&(initialPreviewReloadRequestedAt==0L||previewNow-initialPreviewReloadRequestedAt
                    >=FullDiscoveryProgressPolicy.previewReloadIntervalMs(
                        hasPlaybackCatalogueOwner(),previewMilestone))){
              initialPreviewReloadRequestedAt=previewNow;
              reloadChannelsAfterBackgroundUpdateIfNeeded();
            }
            if(FullDiscoveryProgressPolicy.hasPublishedInitialSnapshot(completed)){
              boolean newlyPublished=roomFullRebuildCompletedAt<=0L;
              roomFullRebuildCompletedAt=completed;
              // Capture the first observed post-publication checkpoint once. Do not
              // chase every later routine commit or claim an append-only preview is final.
              if(initialFinalCatalogueVersion<=0L&&observedFinalVersion>0L)
                initialFinalCatalogueVersion=observedFinalVersion;
              initialFinalCataloguePending=!startupDataReady||!RoomCatalogueReloadPolicy.hasLoadedFinalCatalogue(
                  catalogueSnapshotTrusted,loadedRoomCatalogueVersion,initialFinalCatalogueVersion);
              if(initialFinalCataloguePending){
                catalogueReloadDeferredForPlayback=true;
                if(startupDataReady&&!hasPlaybackCatalogueOwner()
                    &&(initialFinalReloadRequestedAt==0L||previewNow-initialFinalReloadRequestedAt>=15000L)){
                  initialFinalReloadRequestedAt=previewNow;
                  reloadChannelsAfterBackgroundUpdateIfNeeded();
                }
              }
              if(newlyPublished)scheduleAutomaticSourceUpdates();
              initialDiscoveryProgressBar.setProgress(10000);
              initialDiscoveryProgressTitle.setText(initialFinalCataloguePending
                  ?"目录已保存 · 等待载入频道":"目录处理完成 · 100%");
              // The panel is limited to four lines. Put the fresh core counts
              // and coverage warning ahead of supplementary probe statistics.
              String[] coverageLines=freshCoverage.split("\\n");
              String coreCounts=coverageLines.length>=2?coverageLines[1]:"本次中国频道覆盖尚无记录";
              String coverageWarning=coverageLines.length>=4
                  ?(coverageLines[3].startsWith("覆盖有缺口")?"中国覆盖有缺口，待补查":"省级地区有记录，仍待实播")
                  :"覆盖详情尚未确认";
              initialDiscoveryProgressDetail="快照已保存 · "+Math.max(0,staged)+" 个频道（非实播验证）"
                  +"\n"+coreCounts
                  +"\n"+coverageWarning+" · 暂不可用目录 "+Math.max(0,unavailable)+" 个"
                  +"\n100%仅指目录处理完成；详见设置→查看更新状态";
              if(initialFinalCataloguePending){
                initialDiscoveryProgressDetail+="\n"+(hasPlaybackCatalogueOwner()
                    ?"保留当前播放，空闲后载入完整目录":"正在等待完整目录载入；当前显示 "+channels.size()+" 个频道");
                refreshInitialDiscoveryDetailsDialog();
                return;
              }
              refreshInitialDiscoveryDetailsDialog();
              stopInitialDiscoveryProgressTicker();
              // Give the user a confirmation, then restore the channel list space.
              uiHandler.postDelayed(()->{
                if(initialDiscoveryProgressPanel!=null&&roomFullRebuildCompletedAt>0L)
                  initialDiscoveryProgressPanel.setVisibility(View.GONE);
              },12000L);
              return;
            }
            String recoverySignature=done+":"+checked+":"+validated+":"+staged+":"+basisPoints
                +":"+feedIndex+":"+cursor+":"+indexAttempted+":"+indexReady+":"+indexEntries+":"+preparedFeeds;
            long recoveryNow=android.os.SystemClock.elapsedRealtime();
            if(!recoverySignature.equals(initialRecoveryProgressSignature)
                ||recoveryNow<initialRecoveryUnchangedSince){
              initialRecoveryProgressSignature=recoverySignature;
              initialRecoveryUnchangedSince=recoveryNow;
              initialRecoveryRequested=false;
            }
            long unchangedMs=recoveryNow-initialRecoveryUnchangedSince;
            boolean autoEnabled=getSharedPreferences("mytv",0).getBoolean("auto_source_update",true);
            if(startupDataReady&&!initialResumeRequestPending&&!isAnyPlaybackLockActive()
                &&currentVodPanel==null&&SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(
                    unchangedMs,initialDiscoveryWorkObserved,initialDiscoveryWorkUnfinished,
                    SourceUpdateWorker.isSourceWorkActive(),SourceUpdateActivityPolicy.isPlaybackActive(),
                    autoEnabled,buildActive,initialRecoveryRequested)){
              initialRecoveryRequested=true;
              // KEEP handles a concurrent enqueue without cancelling it.
              // The worker rechecks the durable completion marker before doing work.
              try{
                OneTimeWorkRequest recovery=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,30L,TimeUnit.SECONDS)
                    .setInputData(new Data.Builder().putBoolean("manual",true)
                        .putBoolean("initial_discovery_chain",true).build()).build();
                WorkManager.getInstance(this).enqueueUniqueWork("mytv_startup_source_refresh",
                    ExistingWorkPolicy.KEEP,recovery);
                android.util.Log.i("MyTVSourceDiscovery","missing_initial_chain_recovery_requested unchanged_ms="+unchangedMs);
              }catch(RuntimeException failure){
                android.util.Log.w("MyTVSourceDiscovery","missing_initial_chain_recovery_failed",failure);
              }
            }
            initialDiscoveryProgressPanel.setVisibility(View.VISIBLE);
            initialDiscoveryProgressBar.setProgress(Math.max(0,Math.min(9999,basisPoints)));
            int bootstrapLimit=FullDiscoveryProgressPolicy.bootstrapIndexLimit(SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT);
            boolean indexing=!indexBypassed&&FullDiscoveryProgressPolicy.isMainlandIndexPhase(indexAttempted,
                SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,checked,indexEntries,false);
            initialDiscoveryProgressTitle.setText(indexing
                ?"中国目录 "+Math.max(0,indexAttempted)+"/"+bootstrapLimit
                :"抓源 "+FullDiscoveryProgressPolicy.formatIncompleteProgress(basisPoints)
                    +" · "+Math.max(0,checked)+"条");
            String fraction=entries>0?" · 当前源 "+Math.min(cursor,entries)+"/"+entries:"";
            String liveStage=SourceUpdateWorker.currentInitialLiveStage();
            final int loadedCount=channels.size();
            String taskStatus=initialDiscoveryTerminalStatus;
            if(taskStatus.isEmpty()){
              if(!liveStage.isEmpty())taskStatus=liveStage;
              else if(initialDiscoveryWorkRunning)taskStatus="抓源任务运行中，正在准备或保存本批进度";
              else if(initialDiscoveryWorkUnfinished)taskStatus=SourceUpdateActivityPolicy.isPlaybackActive()
                  ?(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(android.os.SystemClock.elapsedRealtime())
                      ?"稳定播放低速抓源已获准，等待下一批调度":"播放资源占用，等待稳定播放许可或下一批调度")
                  :"当前没有播放资源占用，等待下一批抓源调度";
              else taskStatus=initialDiscoveryWorkObserved?"当前无运行或排队的抓源任务，可在设置中继续":"正在读取抓源任务状态";
            }
            if(SourceUpdateWorker.isRetiringProbePoolActive())
              taskStatus="上批网络检测仍在安全释放，保留断点，暂不启动新检测";
            else if(initialRecoveryRequested&&!initialDiscoveryWorkUnfinished)
              taskStatus="已请求自动续抓，保留断点，等待系统调度";
            else if(unchangedMs>=SourceUpdateSchedulePolicy.INITIAL_STALL_OBSERVATION_MS
                &&initialDiscoveryWorkUnfinished&&!SourceUpdateActivityPolicy.isPlaybackActive())
              taskStatus+=" · 已超过3分钟未见已提交进度（保留现有任务）";
            initialDiscoveryProgressDetail=taskStatus
                +"\n上次保存状态："+phase
                +"\n当前已载入 "+loadedCount+" 个频道（不等于本轮完成）"
                +"\n"+(indexing?"首批目录已尝试 "+indexAttempted+"/"+bootstrapLimit+" · 已索引 "+indexReady+" 个，原始条目 "+indexEntries:
                (total>0?"已处理目录 "+Math.max(0,done)+"/"+total
                    +" · 中国大陆 "+Math.max(0,chinaDone)+"/"+Math.max(0,chinaTotal)
                    :"正在准备来源列表"))
                +"\n目录准备记录 "+preparedFeeds+"/"+configuredFeeds+" · 中国大陆 "+preparedMainland+"/"+SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT+"（非线路验证）"
                +"\n已整理频道 "+Math.max(0,staged)+(previewLevel>0?" · 阶段目录已发布":"")+" · 中国初筛通过 "
                    +Math.max(0,chinaValidated)+" 次"
                +"\n初筛地址 "+Math.max(0,checked)+" · 失败目录 "
                    +Math.max(0,unavailable)+" 个 · 检测不确定 "+Math.max(0,timeouts)+" 次"
                +(total>0?"\n当前目录 "+(Math.max(0,feedIndex)+1)+fraction:"")
                +(initialDiscoverySchedulerDetail.isEmpty()?"":"\n"+initialDiscoverySchedulerDetail);
            refreshInitialDiscoveryDetailsDialog();
          });
        }catch(Throwable error){
          android.util.Log.w("MyTVDiscoveryProgress","read initial progress failed",error);
        }finally{
          initialDiscoveryProgressReadPending.set(false);
        }
      });
    }catch(RuntimeException ignored){initialDiscoveryProgressReadPending.set(false);}
  }

  private static long readStateLong(Map<String,String> state,String key,long fallback){
    try{return Long.parseLong(state.getOrDefault(key,Long.toString(fallback)));}
    catch(NumberFormatException invalid){return fallback;}
  }

  private void observeStartupSourceUpdateResults(){
    // Apply the guarded catalogue snapshot as soon as the startup refresh finishes.
    // This avoids requiring the user to exit and relaunch to see newly found channels.
    WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("mytv_startup_source_refresh")
        .observe(this,workInfos->{
          if(workInfos==null)return;
          boolean unfinished=false,failed=false,cancelled=false,running=false;
          int queued=0,blocked=0,attempts=0;
          for(androidx.work.WorkInfo workInfo:workInfos){
            androidx.work.WorkInfo.State state=workInfo.getState();
            if(!state.isFinished()){unfinished=true;attempts=Math.max(attempts,workInfo.getRunAttemptCount());}
            if(state==androidx.work.WorkInfo.State.ENQUEUED)queued++;
            if(state==androidx.work.WorkInfo.State.BLOCKED)blocked++;
            if(state==androidx.work.WorkInfo.State.RUNNING)running=true;
            if(state==androidx.work.WorkInfo.State.FAILED)failed=true;
            if(state==androidx.work.WorkInfo.State.CANCELLED)cancelled=true;
          }
          initialDiscoverySchedulerDetail="系统任务：运行 "+(running?1:0)+" · 排队 "+queued+" · 阻塞 "+blocked+" · 未结束任务最大尝试次数 "+attempts;
          initialDiscoveryWorkObserved=true;
          initialDiscoveryWorkRunning=running;
          initialDiscoveryWorkUnfinished=unfinished;
          initialDiscoveryTerminalStatus=unfinished||roomFullRebuildCompletedAt>0L?""
              :failed?"抓源任务已停止，尚未完整发布，可在设置中手动继续"
              :cancelled?"抓源任务已取消，已保存进度可在设置中继续":"";
          // Room's durable completion marker, read by the existing ticker,
          // is the sole authority for completion and routine scheduling.
          // Do not reload or overwrite state simply because a WorkInfo list
          // happens to be all SUCCEEDED.
          requestInitialDiscoveryProgress();
        });
  }
  private void observeManualSourceUpdateResults(){
    // One lifecycle-bound observer; a historical terminal WorkInfo alone is
    // not a new refresh. Read only committed Room state after an active manual
    // chain becomes terminal, including partial commits before cancellation.
    final boolean[] sawUnfinished={false};
    WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("mytv_manual_source_update")
        .observe(this,workInfos->{
          if(workInfos==null)return;
          boolean unfinished=false;
          for(androidx.work.WorkInfo workInfo:workInfos){
            if(!workInfo.getState().isFinished()){unfinished=true;break;}
          }
          if(unfinished){sawUnfinished[0]=true;return;}
          if(!sawUnfinished[0])return;
          sawUnfinished[0]=false;
          if(activityStarted&&!isFinishing()&&!isDestroyed())
            reloadChannelsAfterBackgroundUpdateIfNeeded();
        });
  }
  private void toggleAutomaticSourceUpdates(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    boolean enabled=!p.getBoolean("auto_source_update",true);
    p.edit().putBoolean("auto_source_update",enabled).apply();
    if(enabled)scheduleAutomaticSourceUpdates();else{
      WorkManager wm=WorkManager.getInstance(this);
      wm.cancelUniqueWork("mytv_periodic_source_update");
      wm.cancelUniqueWork("mytv_startup_source_refresh");
    }
    toast(enabled?"后台自动更新已开启（完整建库后约每 30 天、空闲时维护）":"后台自动更新已关闭；仍可在设置中手动更新");
  }
  private void startManualSourceUpdate(){
    Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    boolean needsInitialFullRebuild=roomFullRebuildCompletedAt<=0L;
    OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
        .setConstraints(constraints)
        .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,30L,TimeUnit.SECONDS)
        .setInputData(new Data.Builder().putBoolean("manual",true)
            .putBoolean("initial_discovery_chain",needsInitialFullRebuild).build())
        .build();
    WorkManager manager=WorkManager.getInstance(this);
    if(needsInitialFullRebuild){
      // The startup and manual entry points must share one unique chain: two
      // simultaneous first-run rebuilds would race on the same Room staging rows
      // and feed cursors. KEEP reuses an already-running startup rebuild.
      manager.enqueueUniqueWork("mytv_startup_source_refresh",ExistingWorkPolicy.KEEP,request);
      toast("已提交首次全量频道整理；可以在频道列表查看真实完成百分比");
      startInitialDiscoveryProgressTicker();
      return;
    }
    manager.enqueueUniqueWork("mytv_manual_source_update",ExistingWorkPolicy.KEEP,request);
    toast("已请求完整频道轮检；已有任务将继续，播放优先，可在更新状态查看进度");
    // The lifecycle observer waits for the unique chain to become terminal,
    // then reloads only committed Room state, subject to playback protection.
  }
  private void showSourceUpdateStatus(){
    final boolean autoEnabled=getSharedPreferences("mytv",0).getBoolean("auto_source_update",true);
    try{
      worker.execute(()->{
        final String prefix="source_initial_progress_";
        ArrayList<String> keys=new ArrayList<>(Arrays.asList("source_update_time","source_update_result",
            FullDiscoveryProgressPolicy.PUBLISHED_COVERAGE_KEY,FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY,
            FullDiscoveryProgressPolicy.THROUGHPUT_STATUS_KEY,FullDiscoveryProgressPolicy.INITIAL_BUILD_STARTED_AT_KEY,
            "source_full_rebuild_completed_at","source_full_rebuild_active"));
        for(String suffix:new String[]{"unavailable_feeds","validated_urls","staged_channels",
            "china_done_feeds","china_total_feeds","china_validated_urls","total_feeds"})
          keys.add(prefix+suffix);
        int configuredCount=SourceDiscoveryFeedCatalog.feeds().length;
        for(int i=0;i<configuredCount;i++){
          keys.add("source_initial_feed_unavailable_"+i);
          keys.add("source_initial_feed_label_"+i);
          keys.add("source_initial_feed_failure_reason_"+i);
          keys.add("source_initial_feed_failure_count_"+i);
        }
        Map<String,String> snapshot=com.wodedianshi.tv.db.AppStateStore.getValues(getApplicationContext(),keys);
        String time=snapshot.getOrDefault("source_update_time","尚未更新");
        String result=snapshot.getOrDefault("source_update_result","尚无更新记录");
        int unavailable=(int)readStateLong(snapshot,prefix+"unavailable_feeds",0L);
        int validated=(int)readStateLong(snapshot,prefix+"validated_urls",0L);
        int staged=(int)readStateLong(snapshot,prefix+"staged_channels",0L);
        int chinaDone=(int)readStateLong(snapshot,prefix+"china_done_feeds",0L);
        int chinaTotal=(int)readStateLong(snapshot,prefix+"china_total_feeds",0L);
        int chinaValidated=(int)readStateLong(snapshot,prefix+"china_validated_urls",0L);
        int feedTotal=(int)readStateLong(snapshot,prefix+"total_feeds",0L);
        String coverage=snapshot.getOrDefault(FullDiscoveryProgressPolicy.PUBLISHED_COVERAGE_KEY,"");
        String freshCoverage=snapshot.getOrDefault(FullDiscoveryProgressPolicy.FRESH_COVERAGE_KEY,"");
        String throughput=snapshot.getOrDefault(FullDiscoveryProgressPolicy.THROUGHPUT_STATUS_KEY,"尚无新版检测批次统计");
        String buildDuration=FullDiscoveryProgressPolicy.buildDurationStatus(
            readStateLong(snapshot,FullDiscoveryProgressPolicy.INITIAL_BUILD_STARTED_AT_KEY,0L),
            readStateLong(snapshot,"source_full_rebuild_completed_at",0L),
            Boolean.parseBoolean(snapshot.getOrDefault("source_full_rebuild_active","false")),System.currentTimeMillis());
        StringBuilder failedDetails=new StringBuilder();
        int failedShown=0;
        for(int i=0;i<feedTotal&&i<configuredCount;i++){
          if(!Boolean.parseBoolean(snapshot.getOrDefault("source_initial_feed_unavailable_"+i,"false")))continue;
          if(failedShown>=5)break;
          String label=snapshot.getOrDefault("source_initial_feed_label_"+i,"来源 "+(i+1));
          String reason=snapshot.getOrDefault("source_initial_feed_failure_reason_"+i,"尚无失败说明");
          int attempts=(int)readStateLong(snapshot,"source_initial_feed_failure_count_"+i,0L);
          failedDetails.append("\n").append(i+1).append(". ")
              .append(label).append("：").append(reason).append("（尝试 ")
              .append(Math.max(0,attempts)).append(" 次）");
          failedShown++;
        }
        String failedList=failedShown==0?"":"\n暂不可用来源记录："
            +failedDetails+(unavailable>failedShown?"\n其余 "+(unavailable-failedShown)+" 个来源记录略。":"");
        runOnUiThread(()->{
          if(isFinishing()||isDestroyed())return;
          new AlertDialog.Builder(this).setTitle("频道源更新状态")
            .setMessage("后台自动更新："+(autoEnabled?"开启":"关闭")
                +"（完整建库后约每30天启动轮检，空闲时分批继续；播放或前台使用时跳过）\n上次检查："+time
                +"\n更新结果："+result
                +"\n中国大陆目录："+Math.max(0,chinaDone)+"/"+Math.max(0,chinaTotal)
                +" · 中国线路预检通过："+Math.max(0,chinaValidated)+" 次"
                +"\n已整理频道："+Math.max(0,staged)+" · 全部预检通过："
                +Math.max(0,validated)+" 次 · 暂不可用来源："+Math.max(0,unavailable)
                +"\n\n"+buildDuration+"\n"+throughput
                +failedList
                +"\n\n"+(coverage.isEmpty()?"首次发布覆盖统计尚未生成（旧版记录或尚未发布）":coverage)
                +(freshCoverage.isEmpty()?"":"\n\n"+freshCoverage)
                +"\n\n说明：100%只表示配置目录处理结束且快照已发布，不代表所有目录成功、频道齐全或实际可播。失败目录按上限重试，记录后不阻碍其他有效目录发布。旧频道及已选线路优先保留；预检不等于真实播放稳定或获得授权。")
            .setPositiveButton("立即更新",(d,w)->startManualSourceUpdate()).setNegativeButton("关闭",null).show();
        });
      });
    }catch(RuntimeException rejected){toast("暂时无法读取更新状态，请稍后再试");}
  }
  private void decoderSettingsDialog(){
    android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
    int current=prefs.getInt("decoder_mode",DecoderPolicy.DEFAULT_MODE);
    String[] modes={"系统默认解码（关闭备用解码器回退）","兼容解码（优先系统硬件解码，初始化失败时自动尝试其他解码器）"};
    new AlertDialog.Builder(this).setTitle("解码与声音设置").setSingleChoiceItems(modes,current,(dialog,which)->{
      prefs.edit().putInt("decoder_mode",which).apply();
      dialog.dismiss();
      if(selected>=0&&selected<channels.size()){
        int channel=selected;
        releaseLivePlayerSafely();
        play(channel);
      }
      toast(which==0?"已切换为自动解码":"已切换为兼容解码；如果仍无声音，可能是音频格式或音轨不兼容");
    }).setNegativeButton("关闭",null).show();
  }
  private static class VodItem {
    String title,identifier,url,license,posterUrl;
    VodItem(String t,String id,String u,String l){this(t,id,u,l,"");}
    VodItem(String t,String id,String u,String l,String poster){title=t;identifier=id;url=u;license=l;posterUrl=poster==null?"":poster;}
  }
  private static org.json.JSONObject vodItemJson(VodItem item)throws org.json.JSONException{
    return new org.json.JSONObject().put("title",item.title).put("id",item.identifier)
        .put("url",item.url).put("license",item.license).put("poster",item.posterUrl);
  }
  private static VodItem vodItemFromJson(org.json.JSONObject row){
    return new VodItem(row.optString("title",""),row.optString("id",""),
        row.optString("url",""),row.optString("license",""),row.optString("poster",""));
  }
  private Dialog vodPlayerDialog;
  private Dialog vodCatalogueDialog;
  // Keep explicit references to the active VOD view model; never infer controls by child index.
  private LinearLayout currentVodPanel,currentVodResults;
  private TextView currentVodStatus;
  private String activeVodCategory="电影";
  private String activeVodQuery="";
  private String activeVodRegion="中国";
  // Keep the user scroll position when the same category finishes background refresh.
  private String currentVodRenderedCategory="";
  private ExoPlayer vodPlayer;
  private LibVlcVodBackend vodFallbackBackend;
  private VLCVideoLayout vodVlcVideoLayout;
  private boolean vodFallbackAttempted=false;
  private long vodSessionGeneration=0L;
  private boolean vodHistoryEnded;
  private long vodHistoryPendingResumeMs;
  private Runnable vodPendingSeekTask;
  private Runnable vodPlaybackWatchdog;
  private Runnable vodFallbackStartupWatchdog;
  private Runnable vodUiUpdater;
  private SeekBar vodSeekBar;
  private TextView vodPositionLabel,vodDurationLabel;
  private VodItem activeVodItem;
  private Runnable vodProgressSaver;
  private boolean resumeLiveAfterVod=false;
  private View vodCategoryFocusTarget;
  private void vodDialog(){
    if(!startupDataReady||!activityStarted||isFinishing()||isDestroyed())return;
    if(isChildLockCurrentlyActive()){enforceChildLock();return;}
    if(isRemotePlaybackLockActive())return;
    // 点播期间暂停直播，避免目录和电影播放时主频道仍在后台发声/占用解码器。
    if(currentVodPanel!=null||vodCatalogueDialog!=null)return;
    activeVodCategory="电影";activeVodQuery="";currentVodRenderedCategory="";
    resumeLiveAfterVod=(player!=null&&player.getPlayWhenReady())||libVlcEngine!=null
        ||liveVlcReleaseContinuation!=null;
    // Revoke the live item BEFORE pause: synchronous or queued old Media3
    // callbacks must not recover the live edge, play(), or start another source.
    currentMediaId="";
    recentDepartedLiveSource=null;
    lastOutputMediaId="";
    cancelSingleChannelRefresh();
    ++liveTuneRequestSerial;
    clearPendingRemoteZap();
    pauseStableDiscovery();
    cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();
    cancelLibVlcStartTimeout();cancelLibVlcWatchdog();cancelLibVlcBufferingTimeout();
    if(player!=null){try{player.setPlayWhenReady(false);}catch(RuntimeException ignored){}}
    // Revoke live VLC immediately and queue native release on its owner.
    // No native pause/query is allowed on the UI thread during initialization.
    revokePendingLiveTune();
    releaseLibVlcEngine();
    activeVodRegion=getSharedPreferences("mytv",0).getString("vod_search_region","中国");
    if(!"国外".equals(activeVodRegion))activeVodRegion="中国";
    setSourceUpdatePlaybackActive(true);
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    // Defer decoder teardown until after the VOD dialog is shown. OEM ExoPlayer/
    // LibVLC release calls can block the UI thread long enough to make the home
    // screen appear stuck instead of opening the requested VOD window.
    final java.util.ArrayList<Button> vodCategoryHoverTargets=new java.util.ArrayList<>();
    final GridView[] vodGridHoverTarget=new GridView[1];
    // Track pointer movement at the dialog content root. Some Android 9 TV
    // firmwares omit child HOVER_EXIT when the pointer crosses between GridView
    // cards and the category strip, leaving a stale poster highlight behind.
    LinearLayout panel=new LinearLayout(this){
      @Override public boolean onInterceptHoverEvent(android.view.MotionEvent event){
        int action=event.getActionMasked();
        if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
          float x=event.getRawX(),y=event.getRawY();Button hit=null;
          for(Button candidate:vodCategoryHoverTargets){
            int[] loc=new int[2];candidate.getLocationOnScreen(loc);
            if(candidate.isShown()&&candidate.isEnabled()
                &&x>=loc[0]&&x<loc[0]+candidate.getWidth()
                &&y>=loc[1]&&y<loc[1]+candidate.getHeight()){hit=candidate;break;}
          }
          setVodCategoryHover(hit);
          GridView grid=vodGridHoverTarget[0];
          boolean insideGrid=false;
          if(grid!=null&&grid.isShown()){
            int[] loc=new int[2];grid.getLocationOnScreen(loc);
            insideGrid=x>=loc[0]&&x<loc[0]+grid.getWidth()
                &&y>=loc[1]&&y<loc[1]+grid.getHeight();
          }
          // Explicitly clear the card ring whenever the pointer leaves the poster
          // viewport, even if the OEM firmware failed to dispatch HOVER_EXIT.
          if(hit!=null||!insideGrid)clearVodMouseHover();
        }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
          setVodCategoryHover(null);
          clearVodMouseHover();
        }
        return super.onInterceptHoverEvent(event);
      }
    };
    panel.setOrientation(1);panel.setPadding(dp(8),0,dp(8),dp(2));panel.setBackgroundColor(Color.rgb(12,20,34));
    LinearLayout titleBar=new LinearLayout(this);titleBar.setOrientation(0);titleBar.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
    Button backToVodCategories=new Button(this);backToVodCategories.setText("返回分类");backToVodCategories.setAllCaps(false);backToVodCategories.setTextSize(12);backToVodCategories.setMinWidth(0);backToVodCategories.setMinHeight(0);backToVodCategories.setPadding(dp(6),0,dp(6),0);styleTvButton(backToVodCategories,Color.rgb(35,78,112));titleBar.addView(backToVodCategories,new LinearLayout.LayoutParams(dp(88),dp(38)));
    Button refreshVodButton=new Button(this);refreshVodButton.setText("重新抓取");refreshVodButton.setAllCaps(false);refreshVodButton.setTextSize(12);refreshVodButton.setMinWidth(0);refreshVodButton.setMinHeight(0);refreshVodButton.setPadding(dp(6),0,dp(6),0);styleTvButton(refreshVodButton,Color.rgb(26,100,160));titleBar.addView(refreshVodButton,new LinearLayout.LayoutParams(dp(88),dp(38)));
    Button closeButton=new Button(this);closeButton.setText("关闭");closeButton.setAllCaps(false);closeButton.setTextSize(13);closeButton.setMinWidth(0);closeButton.setMinHeight(0);closeButton.setIncludeFontPadding(false);closeButton.setPadding(dp(8),0,dp(8),0);styleTvButton(closeButton,Color.rgb(65,78,96));titleBar.setPadding(dp(4),dp(3),dp(4),dp(3));titleBar.addView(closeButton,new LinearLayout.LayoutParams(dp(82),dp(38)));panel.addView(titleBar,new LinearLayout.LayoutParams(-1,dp(38)));
    LinearLayout filters=new LinearLayout(this);filters.setOrientation(0);filters.setGravity(Gravity.CENTER_VERTICAL);
    java.util.HashMap<String,Button> categoryButtons=new java.util.HashMap<>();
    String[] categories={"我的收藏","播放记录","电影","电视剧","综艺","纪录片","动漫","正版平台"};
    // Hover is tracked by the dialog root so leaving the strip is observable.
    HorizontalScrollView categoryScroll=new HorizontalScrollView(this);
    categoryScroll.setHorizontalScrollBarEnabled(false);categoryScroll.addView(filters);
    LinearLayout searchRow=new LinearLayout(this);searchRow.setOrientation(0);
    LinearLayout suggestionPanel=new LinearLayout(this);suggestionPanel.setOrientation(1);
    suggestionPanel.setPadding(dp(4),dp(3),dp(4),dp(3));
    suggestionPanel.setBackgroundColor(Color.rgb(16,28,44));
    EditText query=new EditText(this);
    for(int categoryIndex=0;categoryIndex<categories.length;categoryIndex++){
      String category=categories[categoryIndex];
      Button b=new Button(this);b.setText(category);b.setTextSize(13);b.setAllCaps(false);styleVodCategoryButton(b);
      vodCategoryHoverTargets.add(b);
      categoryButtons.put(category,b);
      filters.addView(b,new LinearLayout.LayoutParams(dp(100),dp(40)));
      b.setOnClickListener(v->{
        // Resolve the clicked view at click time; never reuse a stale hover/focus row.
        Button clicked=(Button)v;String chosen=clicked.getText().toString();
        setVodCategoryHover(null);
        // Platform navigation is not a catalogue category: do not overwrite the
        // active film category or let the refresh button search for "正版平台".
        if("正版平台".equals(chosen)){openOfficialVodPlatforms();return;}
        activeVodCategory=chosen;activeVodQuery="";query.setText("");
        vodCategoryFocusTarget=clicked;
        updateVodCategoryHighlight(categoryButtons,chosen);
        if("播放记录".equals(chosen))loadVodHistory(panel);else if("我的收藏".equals(chosen))loadVodFavorites(panel);else loadVodCatalogue(chosen,panel);
      });
      if(categoryIndex==0){
        Button searchTab=new Button(this);searchTab.setText("搜索");searchTab.setTextSize(13);searchTab.setAllCaps(false);styleTvButton(searchTab,Color.rgb(26,100,160));
        filters.addView(searchTab,new LinearLayout.LayoutParams(dp(84),dp(40)));
        searchTab.setOnClickListener(v->{boolean show=searchRow.getVisibility()!=View.VISIBLE;searchRow.setVisibility(show?View.VISIBLE:View.GONE);suggestionPanel.setVisibility(show?View.VISIBLE:View.GONE);if(show){query.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}});
      }
    }
    panel.addView(categoryScroll,new LinearLayout.LayoutParams(-1,dp(38)));
    vodCategoryFocusTarget=categoryButtons.get("电影");
    updateVodCategoryHighlight(categoryButtons,"电影");
    Button chinaRegionButton=new Button(this);chinaRegionButton.setText("中国");chinaRegionButton.setAllCaps(false);chinaRegionButton.setTextSize(12);styleTvButton(chinaRegionButton,"中国".equals(activeVodRegion)?Color.rgb(26,112,190):Color.rgb(35,55,78));
    Button foreignRegionButton=new Button(this);foreignRegionButton.setText("国外");foreignRegionButton.setAllCaps(false);foreignRegionButton.setTextSize(12);styleTvButton(foreignRegionButton,"国外".equals(activeVodRegion)?Color.rgb(26,112,190):Color.rgb(35,55,78));
    searchRow.addView(chinaRegionButton,new LinearLayout.LayoutParams(dp(66),dp(40)));
    searchRow.addView(foreignRegionButton,new LinearLayout.LayoutParams(dp(66),dp(40)));
    query.setSingleLine(true);query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);query.setTextColor(Color.WHITE);query.setHintTextColor(Color.LTGRAY);query.setHint("输入片名/演员/关键词");searchRow.addView(query,new LinearLayout.LayoutParams(0,dp(40),1));
    Button searchButton=new Button(this);searchButton.setText("搜索");searchButton.setAllCaps(false);styleTvButton(searchButton,Color.rgb(26,100,160));searchRow.addView(searchButton,new LinearLayout.LayoutParams(dp(84),dp(40)));
    View.OnClickListener regionClick=v->{
      String selectedRegion=v==chinaRegionButton?"中国":"国外";
      if(selectedRegion.equals(activeVodRegion))return;
      activeVodRegion=selectedRegion;
      getSharedPreferences("mytv",0).edit().putString("vod_search_region",activeVodRegion).apply();
      styleTvButton(chinaRegionButton,"中国".equals(activeVodRegion)?Color.rgb(26,112,190):Color.rgb(35,55,78));
      styleTvButton(foreignRegionButton,"国外".equals(activeVodRegion)?Color.rgb(26,112,190):Color.rgb(35,55,78));
      if(!activeVodQuery.isEmpty())loadVodCatalogue("搜索："+activeVodQuery,panel);
      else if(currentVodStatus!=null)currentVodStatus.setText("已切换到"+activeVodRegion+"影视搜索，请输入片名或关键词");
    };
    chinaRegionButton.setOnClickListener(regionClick);
    foreignRegionButton.setOnClickListener(regionClick);
    panel.addView(searchRow,new LinearLayout.LayoutParams(-1,dp(42)));searchRow.setVisibility(View.GONE);
    panel.addView(suggestionPanel,new LinearLayout.LayoutParams(-1,dp(166)));suggestionPanel.setVisibility(View.GONE);
    TextView status=new TextView(this);status.setText("正在载入精选影片…");status.setTextColor(Color.LTGRAY);status.setTextSize(12);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setPadding(dp(4),0,dp(4),0);panel.addView(status,new LinearLayout.LayoutParams(-1,dp(20)));
    // No visible previous/next buttons: poster pages are navigated by
    // native GridView scrolling, mouse wheel/gesture and TV D-pad.
    // GridView recycles off-screen cards while retaining native touch and D-pad navigation.
    LinearLayout results=new LinearLayout(this);results.setOrientation(1);results.setFocusable(false);
    GridView vodGrid=ensureVodGrid(results,3);
    vodGridHoverTarget[0]=vodGrid;
    vodGrid.setId(View.generateViewId());
    panel.addView(results,new LinearLayout.LayoutParams(-1,0,1));
    clearPendingRemoteZap();
    currentVodPanel=panel;currentVodStatus=status;currentVodResults=results;
    panel.setLayoutParams(new ViewGroup.LayoutParams(-1,-1));
    Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    vodCatalogueDialog=dialog;
    dialog.setContentView(panel);
    closeButton.setOnClickListener(v->dialog.dismiss());
    backToVodCategories.setOnClickListener(v->{
      View target=vodCategoryFocusTarget;
      if(target!=null&&target.isShown()){
        target.requestFocus();
        categoryScroll.smoothScrollTo(0,0);
        setVodCategoryHover(null);
      }else categoryScroll.requestFocus();
    });
    refreshVodButton.setOnClickListener(v->{
      if(activeVodQuery.isEmpty()&&"播放记录".equals(activeVodCategory))loadVodHistory(panel);
      else if(activeVodQuery.isEmpty()&&"我的收藏".equals(activeVodCategory))loadVodFavorites(panel);
      else loadVodCatalogue(activeVodQuery.isEmpty()?activeVodCategory:"搜索："+activeVodQuery,panel,status,results,true);
    });
    searchButton.setOnClickListener(v->{
       String q=query.getText().toString().trim();
       if(q.isEmpty()){toast("请输入影片名称或关键词");query.requestFocus();return;}
       activeVodQuery=q;
       updateVodCategoryHighlight(categoryButtons,null);
       ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);
       loadVodCatalogue("搜索："+q,panel,status,results,true);
     });
     query.setOnEditorActionListener((v,actionId,event)->{
       if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH||actionId==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){searchButton.performClick();return true;}
       return false;
     });
    // Search landing panel inspired by iQIYI: grouped content types, genres,
    // and regions/catalogues are visible directly under the search input.
    String[][] searchGroups={
      {"内容类型","电影","电视剧","综艺","纪录片","动漫"},
      {"题材筛选","动作片","战争片","悬疑","犯罪","喜剧","科幻","恐怖片","爱情","家庭","历史"},
      {"地区筛选","中国电影","华语经典","香港","台湾","欧美经典","日本"}
    };
    for(String[] group:searchGroups){
      TextView groupTitle=new TextView(this);groupTitle.setText(group[0]);groupTitle.setTextColor(Color.rgb(170,202,230));
      groupTitle.setTextSize(11);groupTitle.setTypeface(null,android.graphics.Typeface.BOLD);
      groupTitle.setPadding(dp(4),dp(1),0,0);
      suggestionPanel.addView(groupTitle,new LinearLayout.LayoutParams(-1,dp(19)));
      HorizontalScrollView rowScroll=new HorizontalScrollView(this);rowScroll.setHorizontalScrollBarEnabled(false);
      LinearLayout row=new LinearLayout(this);row.setOrientation(0);row.setGravity(Gravity.CENTER_VERTICAL);
      for(int i=1;i<group.length;i++){
        String term=group[i];
        Button chip=new Button(this);chip.setText(term);chip.setAllCaps(false);chip.setTextSize(11);
        chip.setMinHeight(0);chip.setMinimumHeight(0);chip.setMinWidth(0);
        chip.setPadding(dp(9),0,dp(9),0);styleTvButton(chip,Color.rgb(35,58,82));
        row.addView(chip,new LinearLayout.LayoutParams(-2,dp(32)));
        chip.setOnClickListener(v->{query.setText(term);query.setSelection(query.length());searchButton.performClick();});
      }
      rowScroll.addView(row);suggestionPanel.addView(rowScroll,new LinearLayout.LayoutParams(-1,dp(34)));
    }
    // Initial catalogue loading is deferred until after dialog.show().
    dialog.setOnDismissListener(d->{
      if(vodCatalogueDialog!=dialog)return;
      revokePendingVodStart();
      vodCatalogueDialog=null;
      invalidateVodPosterSession();
      clearVodMouseHover();
      setVodCategoryHover(null);
      cancelActiveVodCatalogue();
      // Activity stop also dismisses this dialog. In that path leave live
      // restoration to onStart(); starting a decoder while onStop() is tearing
      // it down creates a start/release race on Android TV.
      boolean shouldResumeLive=activityStarted&&!isFinishing()&&resumeLiveAfterVod
          &&selected>=0&&selected<channels.size()&&!isAnyPlaybackLockActive();
      // Release the VOD catalogue ownership before attempting to resume live.
      // The asynchronous VLC release continuation also checks this owner.
      if(currentVodPanel==panel){currentVodPanel=null;currentVodStatus=null;currentVodResults=null;}
      if(shouldResumeLive){
        // Recreate the live player only after VOD releases its decoder resources.
        // startCurrentSource() sets the playback-active lease before async decoder
        // preparation; do not immediately clear it just because the new player has
        // not reached STATE_READY yet.
        startCurrentSource();
      }else{
        setSourceUpdatePlaybackActive(
            (player!=null&&player.getPlayWhenReady())||libVlcEngine!=null);
      }
      resumeLiveAfterVod=false;
      reloadDeferredCatalogueIfIdle();
    });
    dialog.setOnShowListener(d->{
      if(dialog.getWindow()!=null){
        dialog.getWindow().setLayout(-1,-1);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.rgb(12,20,34)));
        dialog.getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        // A standalone full-screen Dialog avoids AlertDialog's internal custom-view
        // wrapper. The weighted GridView now receives a finite viewport directly.
        panel.setFocusableInTouchMode(true);
        panel.requestLayout();
        vodGrid.requestFocus();
      }
    });
    final int initialCatalogueRequest=vodRequestId;
    dialog.show();
    // Show the full-screen VOD window before parsing the potentially large cache.
    panel.post(()->{
      if(!activityStarted||isFinishing()||isDestroyed()||!dialog.isShowing()
          ||currentVodPanel!=panel||isAnyPlaybackLockActive())return;
      // Pause happened before construction; now that the VOD window is visible,
      // release both live decoder backends before starting catalogue work.
      releaseLivePlayerSafely();
      // LibVLC release runs off the UI thread because some Android 9 TV
      // firmware blocks during decoder teardown. Do not expose playable VOD
      // cards until the live backend has actually released its audio session.
      releaseLibVlcEngine(()->{
        if(activityStarted&&!isFinishing()&&!isDestroyed()&&dialog.isShowing()
            &&currentVodPanel==panel&&!isAnyPlaybackLockActive()
            &&initialCatalogueRequest==vodRequestId)
          loadVodCatalogue("电影",panel,status,results);
      });
    });
  }
  private void loadVodCatalogue(String category,LinearLayout panel){
    if(panel==null||panel!=currentVodPanel||currentVodStatus==null||currentVodResults==null)return;
    loadVodCatalogue(category,panel,currentVodStatus,currentVodResults);
  }
  private static final class VodCacheSnapshot {
    final ArrayList<VodItem> items=new ArrayList<>();
    long savedAt;
  }
  private void invalidateVodCacheFreshness(String cacheKey){
    queueRoomAppStateWrite(cacheKey+"_saved_at","0");
  }
  private VodCacheSnapshot readVodCache(String cacheKey){
    VodCacheSnapshot snapshot=new VodCacheSnapshot();
    try{
      String raw="";
      String timeKey=cacheKey+"_saved_at";
      // One SELECT observes the rows from the same committed Room snapshot.
      for(com.wodedianshi.tv.db.AppStateEntity row:com.wodedianshi.tv.db.RoomDatabaseProvider
          .get(getApplicationContext()).appStateDao().getValues(java.util.Arrays.asList(cacheKey,timeKey))){
        if(cacheKey.equals(row.key))raw=row.stateValue;
        else if(timeKey.equals(row.key)){
          try{snapshot.savedAt=Long.parseLong(row.stateValue);}catch(NumberFormatException ignored){}
        }
      }
      if(raw.isEmpty())return snapshot;
      org.json.JSONArray a=new org.json.JSONArray(raw);
      for(int i=0;i<a.length();i++){
        org.json.JSONObject o=a.optJSONObject(i);if(o==null)continue;
        VodItem item=vodItemFromJson(o);
        if(!item.title.isEmpty()&&!item.identifier.isEmpty()&&(item.url.startsWith("https://")||item.identifier.startsWith("meta:")))snapshot.items.add(item);
      }
    }catch(Exception ignored){snapshot.savedAt=0L;}
    return snapshot;
  }
  private String vodTitleIdentity(String title){
    if(title==null)return "";
    String key=title.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|（.*?）|\\[.*?\\]|【.*?】","");
    key=key.replaceAll("(19|20)\\d{2}","").replaceAll("(?:1080p|720p|4k|高清|超清|国语|粤语|中字)","");
    return key.replaceAll("[^\\p{L}\\p{N}]","");
  }
  private boolean addUniqueVodItem(ArrayList<VodItem> items,Set<String> ids,Set<String> titles,VodItem item){
    if(item==null||item.identifier==null||item.identifier.isEmpty()||ids.contains(item.identifier))return false;
    String titleKey=vodTitleIdentity(item.title);
    boolean incomingPlayable=item.url!=null&&!item.url.trim().isEmpty()&&!item.identifier.startsWith("meta:");
    // Very short names are common labels rather than reliable film identities.
    if(titleKey.length()>=6&&titles.contains(titleKey)){
      // Provider completion order is nondeterministic. Never let a metadata-only
      // card hide a real direct video that arrives later for the same title.
      for(int i=0;i<items.size();i++){
        VodItem existing=items.get(i);
        if(!titleKey.equals(vodTitleIdentity(existing.title)))continue;
        boolean existingPlayable=existing.url!=null&&!existing.url.trim().isEmpty()
            &&!existing.identifier.startsWith("meta:");
        if(VodResultMergePolicy.shouldReplace(existingPlayable,incomingPlayable)){
          ids.remove(existing.identifier);
          items.set(i,item);
          ids.add(item.identifier);
          return true;
        }
        return false;
      }
      return false;
    }
    ids.add(item.identifier);
    if(titleKey.length()>=6)titles.add(titleKey);
    items.add(item);
    if(titleKey.length()<6)titles.add(item.identifier);
    return true;
  }
  private void writeVodCache(String cacheKey,ArrayList<VodItem> items,boolean complete){
    try{
      org.json.JSONArray a=new org.json.JSONArray();
      for(VodItem item:items){
        a.put(vodItemJson(item));
      }
      // Cache rows and freshness metadata commit together in Room.
      long savedAt=complete?System.currentTimeMillis():0L;
      try{
        cataloguePersistWorker.execute(()->{
          try{
            new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
                .putString(cacheKey,a.toString())
                .putLong(cacheKey+"_saved_at",savedAt)
                .apply();
          }catch(Throwable failure){
            android.util.Log.e("MyTVVodCache","Room cache write failed for "+cacheKey,failure);
          }
        });
      }catch(RuntimeException rejected){
        android.util.Log.e("MyTVVodCache","Room cache write queue rejected for "+cacheKey,rejected);
      }
    }catch(Exception ignored){}
  }
  // Mouse-wheel events are often dispatched to the card/image under the pointer,
  // not to the GridView. Forward them so users can scroll without moving to the edge.
  private void attachVodSwipeScroll(View target,GridView grid){
    final float[] lastY={0f},downX={0f},downY={0f};
    final boolean[] scrolling={false};
    final int slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();
    target.setOnTouchListener((view,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_DOWN){downX[0]=event.getX();downY[0]=event.getY();lastY[0]=event.getY();scrolling[0]=false;return false;}
      if(action==android.view.MotionEvent.ACTION_MOVE){float dx=event.getX()-downX[0],dy=event.getY()-downY[0];if(!scrolling[0]&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.15f)scrolling[0]=true;if(scrolling[0]){float y=event.getY();grid.scrollListBy(Math.round(lastY[0]-y));lastY[0]=y;return true;}}
      if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL){boolean consumed=scrolling[0];scrolling[0]=false;return consumed;}
      return scrolling[0];
    });
  }
  private boolean handleVodGridScroll(View source,android.view.MotionEvent event){
    if(event==null||event.getAction()!=android.view.MotionEvent.ACTION_SCROLL)return false;
    float wheel=event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
    if(wheel==0f)wheel=-event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL);
    if(wheel==0f)return false;
    GridView grid=source instanceof GridView?(GridView)source:null;
    ViewParent p=source.getParent();
    while(grid==null&&p!=null){
      if(p instanceof GridView)grid=(GridView)p;
      else p=p.getParent();
    }
    if(grid==null&&currentVodResults!=null){
      for(int i=0;i<currentVodResults.getChildCount();i++){
        View child=currentVodResults.getChildAt(i);
        if(child instanceof GridView){grid=(GridView)child;break;}
      }
    }
    if(grid==null)return false;
    // Consume the wheel at the list itself, even when the pointer is over a poster.
    // scrollListBy is more reliable than smoothScrollBy for mouse wheels on Android TV.
    int step=Math.max(dp(96),grid.getHeight()/3);
    int delta=Math.round(-wheel*step);
    if(delta==0)delta=wheel>0?-dp(96):dp(96);
    grid.smoothScrollBy(delta,180);
    return true;
  }
  private GridView ensureVodGrid(LinearLayout host,int columns){
    GridView grid=null;
    for(int i=0;i<host.getChildCount();i++)if(host.getChildAt(i) instanceof GridView){grid=(GridView)host.getChildAt(i);break;}
    if(grid==null){
      grid=new GridView(this){
        @Override public boolean dispatchHoverEvent(android.view.MotionEvent event){
          int action=event.getActionMasked();
          // Snapshot coordinates before dispatching to children. Android 9 TV firmware
          // can offset raw coordinates inconsistently when GridView is scrolled.
          final float localX=event.getX(),localY=event.getY();
          boolean handled=super.dispatchHoverEvent(event);
          if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
            View hit=null;
            // Child hit rectangles are already expressed in GridView-local coordinates.
            // This avoids mixing screen coordinates, recycled adapter positions and columns.
            for(int i=0;i<getChildCount();i++){
              View child=getChildAt(i);
              android.graphics.Rect bounds=new android.graphics.Rect();
              child.getHitRect(bounds);
              if(bounds.contains(Math.round(localX),Math.round(localY))){hit=child;break;}
            }
            if(hit!=null)showVodMouseHover(hit);else clearVodMouseHover();
          }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
            clearVodMouseHover();
          }
          return handled;
        }
        @Override public boolean onGenericMotionEvent(android.view.MotionEvent event){
          if(handleVodGridScroll(this,event))return true;
          return super.onGenericMotionEvent(event);
        }
      };
      grid.setVerticalSpacing(dp(10));grid.setHorizontalSpacing(dp(8));
      grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
      grid.setFocusable(true);grid.setFocusableInTouchMode(true);
      grid.setVerticalScrollBarEnabled(true);grid.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
      // Do not consume hover at the GridView level. On some Android 9 TV boxes,
      // a full-grid hover listener makes the hardware cursor disappear over the
      // lower poster rows. Let child cards and the system pointer dispatch normally.
      if(Build.VERSION.SDK_INT>=24)grid.setPointerIcon(android.view.PointerIcon.getSystemIcon(this,android.view.PointerIcon.TYPE_ARROW));
      host.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
    }
    grid.setNumColumns(columns);return grid;
  }
  private GridView resetVodGrid(LinearLayout host,int columns){
    GridView grid=ensureVodGrid(host,columns);
    for(int i=host.getChildCount()-1;i>=0;i--)if(host.getChildAt(i)!=grid)host.removeViewAt(i);
    return grid;
  }
  // High-contrast mouse hover for VOD categories, tuned for TV viewing distance.
  private void setVodCategoryHover(Button target){
    if(hoveredVodCategoryButton==target)return;
    if(hoveredVodCategoryButton!=null){
      Button old=hoveredVodCategoryButton;
      old.setBackgroundTintList(null);
      old.setBackground(makeVodCategoryBg(old.isSelected(),old.hasFocus()));
      old.setTextColor(old.isSelected()?Color.WHITE:Color.rgb(235,243,252));
      old.setTypeface(null,old.isSelected()?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
      old.setScaleX(1f);old.setScaleY(1f);
      old.setElevation(old.isFocused()?dp(3):0);
    }
    hoveredVodCategoryButton=target;
    if(target!=null){
      GradientDrawable hover=new GradientDrawable();
      hover.setColor(Color.rgb(255,150,20));
      hover.setCornerRadius(dp(7));
      hover.setStroke(dp(3),Color.WHITE);
      target.setBackgroundTintList(null);
      target.setBackground(hover);
      target.setTextColor(Color.BLACK);
      target.setTypeface(null,android.graphics.Typeface.BOLD);
      // Keep category hit rectangles stable for mouse movement and D-pad.
      target.setScaleX(1f);target.setScaleY(1f);
      target.setElevation(dp(10));
    }
  }
  private void styleVodCategoryButton(Button button){
    button.setFocusable(true);
    button.setFocusableInTouchMode(false);
    button.setTextColor(Color.WHITE);
    button.setTypeface(null,android.graphics.Typeface.NORMAL);
    button.setSelected(false);
    button.setBackground(makeVodCategoryBg(false,false));
    button.setOnFocusChangeListener((v,focused)->{
      if(hoveredVodCategoryButton!=button){
        button.setBackground(makeVodCategoryBg(button.isSelected(),focused));
        button.setScaleX(1f);button.setScaleY(1f);button.setElevation(0f);
      }
    });
    button.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
        setVodCategoryHover(button);
      }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT&&hoveredVodCategoryButton==button){
        setVodCategoryHover(null);
      }
      // Do not consume pointer events: some Android TV firmware only updates
      // the parent hover target when the child allows normal event propagation.
      return false;
    });
    button.setOnTouchListener((v,event)->{
      if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){
        setVodCategoryHover(button);
      }
      return false;
    });
  }
  private GradientDrawable makeVodCategoryBg(boolean selected,boolean focused){
    int fill=selected?Color.rgb(26,100,160):(focused?Color.rgb(48,70,96):Color.rgb(35,55,78));
    GradientDrawable drawable=new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(7));
    drawable.setStroke(dp(selected?2:1),selected?Color.rgb(105,180,235):Color.rgb(76,91,112));
    return drawable;
  }
  // Keep the active VOD category visibly selected across mouse and D-pad input.
  private void updateVodCategoryHighlight(java.util.Map<String,Button> buttons,String selected){
    if(buttons==null)return;
    for(java.util.Map.Entry<String,Button> entry:buttons.entrySet()){
      boolean active=selected!=null&&selected.equals(entry.getKey());
      Button button=entry.getValue();
      button.setSelected(active);
      if(hoveredVodCategoryButton==button)continue;
      button.setBackground(makeVodCategoryBg(active,button.isFocused()));
      button.setTextColor(active?Color.rgb(170,220,255):Color.WHITE);
      button.setTypeface(null,active?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
      button.setElevation(button.isFocused()?dp(3):0);
    }
  }
  // Keep mouse hover separate from D-pad focus. Consuming hover on every
  // card surface prevents GridView's default hover selection from moving focus
  // between recycled cells while the pointer is merely passing over posters.
  // Register hover only on the card container. Child poster/text views remain
  // clickable, but do not compete with the card for hover ownership.
  private void clearVodMouseHover(){
    View old=hoveredVodCard;
    hoveredVodCard=null;
    if(old!=null){
      old.setForeground(null);
      Object tag=old.getTag();
      if(tag instanceof VodCardHolder){
        VodCardHolder h=(VodCardHolder)tag;
        h.title.setBackgroundColor(Color.TRANSPARENT);
        h.meta.setBackgroundColor(Color.TRANSPARENT);
        h.meta.setTextColor(Color.rgb(154,190,220));
      }
      old.setBackground(makeButtonBg(Color.rgb(25,39,59),old.hasFocus()));
      old.setScaleX(1f);old.setScaleY(1f);old.setElevation(old.hasFocus()?dp(4):0);
    }
  }
  private void showVodMouseHover(View card){
    if(card==null||hoveredVodCard==card)return;
    clearVodMouseHover();
    hoveredVodCard=card;
    // The poster image covers the card background, so changing only the card
    // background looks gray/unchanged. Draw a foreground ring above the poster
    // and tint the text bands; this is a true whole-card mouse indicator.
    GradientDrawable hoverBg=new GradientDrawable();
    hoverBg.setColor(Color.rgb(0,88,112));hoverBg.setCornerRadius(dp(7));
    card.setBackground(hoverBg);
    GradientDrawable ring=new GradientDrawable();
    ring.setColor(Color.TRANSPARENT);ring.setCornerRadius(dp(7));
    ring.setStroke(dp(4),Color.rgb(0,238,220));
    card.setForeground(ring);
    Object tag=card.getTag();
    if(tag instanceof VodCardHolder){
      VodCardHolder h=(VodCardHolder)tag;
      h.title.setBackgroundColor(Color.rgb(0,112,132));
      h.meta.setBackgroundColor(Color.rgb(0,82,101));
      h.title.setTextColor(Color.WHITE);
      h.meta.setTextColor(Color.WHITE);
    }
    // Keep the fixed GridView cell geometry from 2.8; no scaling/reflow.
    card.setScaleX(1f);card.setScaleY(1f);card.setElevation(dp(8));
  }
  private void configureVodMouseTarget(View card,View... surfaces){
    // GridView alone owns hover state; child-level listeners race at Android 9 row boundaries.
    if(Build.VERSION.SDK_INT>=24){
      android.view.PointerIcon arrow=android.view.PointerIcon.getSystemIcon(this,android.view.PointerIcon.TYPE_ARROW);
      if(card!=null)card.setPointerIcon(arrow);
      if(surfaces!=null)for(View surface:surfaces)if(surface!=null)surface.setPointerIcon(arrow);
    }
  }
  private static class VodCardHolder{ImageView poster;TextView title,meta;VodCardHolder(ImageView p,TextView t,TextView m){poster=p;title=t;meta=m;}}
  private class VodGridAdapter extends BaseAdapter{
    private final ArrayList<VodItem> items;
    private final HashSet<String> favoriteIds=new HashSet<>();
    private long navigationGeneration;
    VodGridAdapter(ArrayList<VodItem> data){items=data==null?new ArrayList<>():new ArrayList<>(data);for(VodItem saved:readVodFavorites())favoriteIds.add(saved.identifier);}
    public int getCount(){return items.size();}
    public Object getItem(int position){return items.get(position);}
    public long getItemId(int position){return position;}
    public View getView(int position,View convertView,android.view.ViewGroup parent){
      VodItem item=items.get(position);LinearLayout card;VodCardHolder holder;
      if(convertView instanceof LinearLayout&&convertView.getTag() instanceof VodCardHolder){card=(LinearLayout)convertView;holder=(VodCardHolder)card.getTag();}
      else{
        card=new LinearLayout(MainActivity.this);card.setOrientation(1);
        // Every GridView cell has the same measured height. Without fixed child
        // heights, Android 9 sizes each row from its tallest wrapped title.
        card.setPadding(dp(6),dp(5),dp(6),dp(5));
        card.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,dp(154)));
        card.setBackground(makeButtonBg(Color.rgb(25,39,59),false));card.setFocusable(true);card.setClickable(true);
        ImageView poster=new ImageView(MainActivity.this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackgroundColor(Color.rgb(43,61,82));poster.setImageResource(android.R.drawable.ic_media_play);poster.setAdjustViewBounds(false);card.addView(poster,new LinearLayout.LayoutParams(-1,dp(78)));
        TextView title=new TextView(MainActivity.this);title.setTextColor(Color.WHITE);title.setTextSize(12);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(4),0,dp(4),0);card.addView(title,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView meta=new TextView(MainActivity.this);meta.setText("开放授权 · 可尝试播放");meta.setTextColor(Color.rgb(154,190,220));meta.setTextSize(10);meta.setSingleLine(true);meta.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.setGravity(Gravity.CENTER_VERTICAL);meta.setPadding(dp(4),0,dp(4),0);card.addView(meta,new LinearLayout.LayoutParams(-1,dp(24)));
        holder=new VodCardHolder(poster,title,meta);card.setTag(holder);
      }
      // Recycled cells must not inherit old foreground rings and text bands.
      if(hoveredVodCard==card)clearVodMouseHover();
      card.setForeground(null);
      holder.title.setBackgroundColor(Color.TRANSPARENT);
      holder.meta.setBackgroundColor(Color.TRANSPARENT);
      holder.title.setTextColor(Color.WHITE);
      holder.meta.setTextColor(Color.rgb(154,190,220));
      card.setBackground(makeButtonBg(Color.rgb(25,39,59),card.hasFocus()));
      card.setElevation(card.hasFocus()?dp(4):0);
      card.setOnFocusChangeListener((v,focused)->{
        if(hoveredVodCard!=card){
          card.setBackground(makeButtonBg(focused?Color.rgb(34,83,128):Color.rgb(25,39,59),focused));
          card.setScaleX(1f);card.setScaleY(1f);card.setElevation(focused?dp(4):0);
        }
        if(focused&&parent instanceof GridView){
          GridView owner=(GridView)parent;
          if(position<owner.getFirstVisiblePosition()||position>owner.getLastVisiblePosition())owner.smoothScrollToPosition(position);
        }
      });
      holder.title.setText((favoriteIds.contains(item.identifier)?"★ ":"☆ ")+item.title);
      holder.meta.setText(item.identifier.startsWith("meta:")?"影片资料 · 暂无已验证直播放源":(item.identifier.startsWith("cctv:")?"央视网官方节目 · HLS":"开放授权 · 可尝试播放"));
      card.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.poster.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.title.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.meta.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      loadVodPoster(holder.poster,item.identifier,item.title,item.posterUrl);
      // Mouse hover highlights exactly one card but never changes D-pad focus.
      // Explicit arrow pointer avoids an invisible/default cursor on Android TV.
      configureVodMouseTarget(card,holder.poster,holder.title,holder.meta);
      // Make every visible poster/text surface an explicit mouse click target.
      // Forward child clicks to the card so Android 9 TV mouse dispatch cannot
      // leave the poster visually hovered but functionally unclickable.
      holder.poster.setClickable(true);
      holder.title.setClickable(true);
      holder.meta.setClickable(true);
      GridView swipeGrid=parent instanceof GridView?(GridView)parent:null;
      if(swipeGrid!=null){attachVodSwipeScroll(card,swipeGrid);attachVodSwipeScroll(holder.poster,swipeGrid);attachVodSwipeScroll(holder.title,swipeGrid);attachVodSwipeScroll(holder.meta,swipeGrid);}
      View.OnClickListener openItem=v->card.performClick();
      holder.poster.setOnClickListener(openItem);
      holder.title.setOnClickListener(openItem);
      holder.meta.setOnClickListener(openItem);
      card.setOnClickListener(v->{
        if(item.identifier.startsWith("meta:")){
          new AlertDialog.Builder(MainActivity.this).setTitle(item.title)
              .setMessage("已找到影片资料"+(item.posterUrl.isEmpty()?"，暂时没有取得海报":"和影片缩略图")+"。\n\n目前没有查到可直接播放的公开授权视频源，因此不会把资料页或平台网页冒充成影片播放。你可以查看影片信息，或在已安装的正版电视客户端中搜索片名。")
              .setPositiveButton("知道了",null).show();
        }else playVodInside(item);
      });
      // Deterministic D-pad navigation: up/down preserve the current column.
      card.setOnKeyListener((v,keyCode,event)->{
        if(event.getAction()!=android.view.KeyEvent.ACTION_DOWN||!(parent instanceof GridView))return false;
        GridView owner=(GridView)parent;
        int current=owner.getPositionForView(card);
        int count=owner.getAdapter()==null?0:owner.getAdapter().getCount();
        int direction=keyCode==android.view.KeyEvent.KEYCODE_DPAD_UP?VodGridNavigationPolicy.UP:
            keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN?VodGridNavigationPolicy.DOWN:
            keyCode==android.view.KeyEvent.KEYCODE_DPAD_LEFT?VodGridNavigationPolicy.LEFT:
            keyCode==android.view.KeyEvent.KEYCODE_DPAD_RIGHT?VodGridNavigationPolicy.RIGHT:-1;
        int target=VodGridNavigationPolicy.targetPosition(current,count,owner.getNumColumns(),direction);
        final long navigation=++navigationGeneration;
        if(target==VodGridNavigationPolicy.RETURN_TO_CATEGORIES){
          View categoryTarget=vodCategoryFocusTarget;
          if(categoryTarget!=null&&categoryTarget.isShown()){categoryTarget.requestFocus();return true;}
          return false;
        }
        // At a grid edge, hand the key back to Android's native focus/scroll
        // engine. Consuming an out-of-range DOWN key traps focus on the last row.
        if(target==VodGridNavigationPolicy.NATIVE_FOCUS)return false;
        final int destination=target;
        // setSelection() schedules GridView layout; looking for the child in the
        // same frame often finds it still off-screen. That left focus on the old
        // poster and made UP appear unable to scroll back through search results.
        owner.setSelection(destination);
        owner.requestLayout();
        owner.post(()->{
          if(navigation!=navigationGeneration||owner.getAdapter()!=this||!owner.isShown()||vodPlayerDialog!=null)return;
          int childIndex=destination-owner.getFirstVisiblePosition();
          if(childIndex>=0&&childIndex<owner.getChildCount()){
            View next=owner.getChildAt(childIndex);
            if(next!=null){next.requestFocus();return;}
          }
          owner.smoothScrollToPosition(destination);
          owner.post(()->{
            if(navigation!=navigationGeneration||owner.getAdapter()!=this||!owner.isShown()||vodPlayerDialog!=null)return;
            int retryIndex=destination-owner.getFirstVisiblePosition();
            if(retryIndex>=0&&retryIndex<owner.getChildCount()){
              View next=owner.getChildAt(retryIndex);
              if(next!=null)next.requestFocus();
            }
          });
        });
        return true;
      });
      card.setOnLongClickListener(v->{if(item.identifier.startsWith("meta:")){toast("这是影片资料结果，暂不能作为播放收藏");return true;}toggleVodFavorite(item);boolean nowFavorite=isVodFavorite(item);if(nowFavorite)favoriteIds.add(item.identifier);else favoriteIds.remove(item.identifier);holder.title.setText((nowFavorite?"★ ":"☆ ")+item.title);toast(nowFavorite?"已加入我的收藏":"已取消收藏");
        if(!nowFavorite&&"我的收藏".equals(activeVodCategory)&&activeVodQuery.isEmpty()
            &&parent instanceof GridView&&((GridView)parent).getAdapter()==this
            &&currentVodPanel!=null)loadVodFavorites(currentVodPanel);
        return true;});
      return card;
    }
  }
  private void renderVodItems(String category,ArrayList<VodItem> items,TextView status,LinearLayout results,boolean cached){
    clearVodMouseHover();
    status.setText("「"+category+"」 · "+items.size()+" 部"+(cached?"（缓存秒开，正在后台检查更新）":"影片资料与可播放内容"));
    GridView grid=resetVodGrid(results,4);
    int oldFirst=grid.getFirstVisiblePosition();
    View firstChild=grid.getChildAt(0);
    int oldTop=firstChild==null?0:firstChild.getTop();
    View oldFocus=grid.findFocus();
    int oldFocusedPosition=oldFocus==null?-1:grid.getPositionForView(oldFocus);
    View oldFocusedCard=null;
    if(oldFocusedPosition>=0){
      int focusedChildIndex=oldFocusedPosition-grid.getFirstVisiblePosition();
      if(focusedChildIndex>=0&&focusedChildIndex<grid.getChildCount())
        oldFocusedCard=grid.getChildAt(focusedChildIndex);
    }
    int oldFocusedTop=oldFocusedCard==null?oldTop:oldFocusedCard.getTop();
    boolean preservePosition=category.equals(currentVodRenderedCategory)&&grid.getAdapter()!=null&&grid.getAdapter().getCount()>0;
    final VodGridAdapter renderedAdapter=new VodGridAdapter(items);
    final int renderedRequest=vodRequestId;
    grid.setAdapter(renderedAdapter);
    if(preservePosition&&items!=null&&!items.isEmpty()){
      final int restorePosition=Math.min(oldFocusedPosition>=0?oldFocusedPosition:oldFirst,items.size()-1);
      final int restoreTop=oldFocusedPosition>=0?oldFocusedTop:oldTop;
      final boolean restoreFocus=oldFocusedPosition>=0;
      grid.post(()->{
        if(!ownsVodGridRestore(grid,renderedAdapter,renderedRequest,results))return;
        grid.setSelectionFromTop(restorePosition,restoreTop);
        if(restoreFocus)grid.post(()->{
          if(!ownsVodGridRestore(grid,renderedAdapter,renderedRequest,results))return;
          int childIndex=restorePosition-grid.getFirstVisiblePosition();
          if(childIndex>=0&&childIndex<grid.getChildCount()){
            View child=grid.getChildAt(childIndex);
            if(child!=null)child.requestFocus();
          }else{
            grid.smoothScrollToPosition(restorePosition);
            grid.postDelayed(()->{
              if(!ownsVodGridRestore(grid,renderedAdapter,renderedRequest,results))return;
              int retryIndex=restorePosition-grid.getFirstVisiblePosition();
              if(retryIndex>=0&&retryIndex<grid.getChildCount()){
                View retry=grid.getChildAt(retryIndex);
                if(retry!=null)retry.requestFocus();
              }
            },120L);
          }
        });
      });
    }else grid.post(()->{
      if(ownsVodGridRestore(grid,renderedAdapter,renderedRequest,results))grid.setSelection(0);
    });
    currentVodRenderedCategory=category;
  }
  private boolean ownsVodGridRestore(GridView grid,VodGridAdapter adapter,int request,LinearLayout results){
    return activityStarted&&!isFinishing()&&!isDestroyed()&&currentVodPanel!=null
        &&currentVodResults==results&&request==vodRequestId
        &&grid.getAdapter()==adapter&&grid.isAttachedToWindow();
  }
  private ArrayList<VodItem> readVodFavorites(){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      org.json.JSONArray a=new org.json.JSONArray(roomVodFavoritesJson);
      for(int i=0;i<a.length();i++){
        org.json.JSONObject o=a.optJSONObject(i);if(o==null)continue;
        VodItem item=vodItemFromJson(o);
        if(!item.identifier.isEmpty()&&!item.identifier.startsWith("meta:")&&item.url.startsWith("https://"))items.add(item);
      }
    }catch(Exception ignored){}
    return items;
  }
  private boolean isVodFavorite(VodItem item){
    if(item==null)return false;
    for(VodItem saved:readVodFavorites())if(item.identifier.equals(saved.identifier))return true;
    return false;
  }
  private void toggleVodFavorite(VodItem item){
    if(item==null)return;
    ArrayList<VodItem> items=readVodFavorites();boolean removed=false;
    org.json.JSONArray out=new org.json.JSONArray();
    try{
      for(VodItem saved:items){
        if(item.identifier.equals(saved.identifier)){removed=true;continue;}
        out.put(vodItemJson(saved));
      }
      if(!removed&&!item.identifier.startsWith("meta:")&&item.url.startsWith("https://"))out.put(vodItemJson(item));
      saveRoomVodFavorites(out.toString());
    }catch(Exception ignored){}
  }
  private void loadVodFavorites(LinearLayout panel){
    if(panel==null||panel!=currentVodPanel||currentVodStatus==null||currentVodResults==null)return;
    cancelActiveVodCatalogue();
    invalidateVodPosterSession();
    TextView status=currentVodStatus;LinearLayout results=currentVodResults;
    ArrayList<VodItem> items=readVodFavorites();renderVodItems("我的收藏",items,status,results,false);
    status.setText(items.isEmpty()?"还没有收藏影片。点播列表中长按影片即可收藏。":"我的收藏 · "+items.size()+" 部（长按可取消收藏）");
  }
  private ArrayList<org.json.JSONObject> readVodHistory(){
    ArrayList<org.json.JSONObject> list=new ArrayList<>();
    try{org.json.JSONArray a=new org.json.JSONArray(roomVodHistoryJson);for(int i=0;i<a.length();i++){org.json.JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("url","").isEmpty())list.add(o);}}catch(Exception ignored){}
    return list;
  }
  private void checkpointVodHistory(){
    if(activeVodItem==null||vodHistoryEnded||vodPlayer==null&&vodFallbackBackend==null)return;
    long position=vodPositionMs(),duration=vodDurationMs();
    if(!VodTimelinePolicy.shouldSaveHistory(vodHistoryEnded,true,vodHistoryPendingResumeMs,position,duration))return;
    vodHistoryPendingResumeMs=0L;
    saveVodHistory(activeVodItem,position,duration);
  }
  private void stopVodTerminalTimers(){
    if(vodProgressSaver!=null){uiHandler.removeCallbacks(vodProgressSaver);vodProgressSaver=null;}
    if(vodUiUpdater!=null){uiHandler.removeCallbacks(vodUiUpdater);vodUiUpdater=null;}
    if(vodPlaybackWatchdog!=null){uiHandler.removeCallbacks(vodPlaybackWatchdog);vodPlaybackWatchdog=null;}
    if(vodFallbackStartupWatchdog!=null){uiHandler.removeCallbacks(vodFallbackStartupWatchdog);vodFallbackStartupWatchdog=null;}
    if(vodPendingSeekTask!=null){uiHandler.removeCallbacks(vodPendingSeekTask);vodPendingSeekTask=null;}
  }
  private void markVodHistoryEnded(VodItem item,long duration){
    vodHistoryEnded=true;vodHistoryPendingResumeMs=0L;
    stopVodTerminalTimers();
    saveVodHistory(item,0L,duration);
  }
  private void saveVodHistory(VodItem item,long position,long duration){
    if(item==null)return;
    try{
      ArrayList<org.json.JSONObject> old=readVodHistory();org.json.JSONArray out=new org.json.JSONArray();
      org.json.JSONObject entry=vodItemJson(item);
      if(item.posterUrl.isEmpty())for(org.json.JSONObject previous:old){
        if(item.identifier.equals(previous.optString("id",""))&&!previous.optString("poster","").isEmpty()){
          entry.put("poster",previous.optString("poster",""));break;
        }
      }
      entry.put("position",Math.max(0,position));entry.put("duration",Math.max(0,duration));entry.put("watchedAt",System.currentTimeMillis());out.put(entry);
      for(org.json.JSONObject o:old){if(!item.identifier.equals(o.optString("id","")))out.put(o);if(out.length()>=100)break;}
      saveRoomVodHistory(out.toString());
    }catch(Exception ignored){}
  }
  private void loadVodHistory(LinearLayout panel){
    if(panel==null||panel!=currentVodPanel||currentVodStatus==null||currentVodResults==null)return;
    loadVodHistory(currentVodStatus,currentVodResults);
  }
  private class VodHistoryAdapter extends BaseAdapter{
    private final ArrayList<org.json.JSONObject> history;private final TextView status;private final LinearLayout results;
    VodHistoryAdapter(ArrayList<org.json.JSONObject> data,TextView st,LinearLayout host){history=data;status=st;results=host;}
    public int getCount(){return history.size();}
    public Object getItem(int position){return history.get(position);}
    public long getItemId(int position){return position;}
    public View getView(int position,View convertView,android.view.ViewGroup parent){
      org.json.JSONObject o=history.get(position);
      String title=o.optString("title","未命名影片"),id=o.optString("id","");
      VodItem historyItem=vodItemFromJson(o);
      long pos=o.optLong("position",0),duration=o.optLong("duration",0);
      LinearLayout row;TextView name,detail;
      if(convertView instanceof LinearLayout&&((LinearLayout)convertView).getChildCount()>=2){row=(LinearLayout)convertView;name=(TextView)row.getChildAt(0);detail=(TextView)row.getChildAt(1);}
      else{
        row=new LinearLayout(MainActivity.this);row.setOrientation(1);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setFocusable(true);row.setFocusableInTouchMode(true);row.setClickable(true);row.setBackground(makeButtonBg(Color.rgb(25,39,59),false));
        name=new TextView(MainActivity.this);name.setTextColor(Color.WHITE);name.setTextSize(16);row.addView(name);
        detail=new TextView(MainActivity.this);detail.setTextColor(Color.rgb(154,190,220));detail.setTextSize(12);row.addView(detail);
        row.setOnFocusChangeListener((v,focused)->{row.setBackground(makeButtonBg(Color.rgb(25,39,59),focused));row.setScaleX(focused?1.025f:1f);row.setScaleY(focused?1.025f:1f);row.setElevation(focused?dp(7):0);name.setTextColor(focused?Color.rgb(255,235,170):Color.WHITE);detail.setTextColor(focused?Color.WHITE:Color.rgb(154,190,220));});
      }
      name.setText(title);detail.setText(pos>0?"上次看到 "+formatVodTime(pos)+(duration>0?" / "+formatVodTime(duration):"")+" · 点击续播":"已观看 · 点击播放");
      row.setOnClickListener(v->playVodInside(historyItem));
      row.setOnLongClickListener(v->{new AlertDialog.Builder(MainActivity.this).setTitle("删除播放记录").setMessage(title).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{ArrayList<org.json.JSONObject> keep=readVodHistory();org.json.JSONArray a=new org.json.JSONArray();for(org.json.JSONObject x:keep)if(!id.equals(x.optString("id","")))a.put(x);saveRoomVodHistory(a.toString());loadVodHistory(status,results);}).show();return true;});
      return row;
    }
  }
  private void loadVodHistory(TextView status,LinearLayout results){
    if(!activityStarted||isFinishing()||isDestroyed()||currentVodPanel==null
        ||status!=currentVodStatus||results!=currentVodResults
        ||!"播放记录".equals(activeVodCategory)||!activeVodQuery.isEmpty())return;
    // Local history owns the page: revoke queued search results and old posters.
    cancelActiveVodCatalogue();
    invalidateVodPosterSession();
    currentVodRenderedCategory="";
    ArrayList<org.json.JSONObject> history=readVodHistory();results.removeAllViews();
    LinearLayout tools=new LinearLayout(this);tools.setOrientation(0);
    Button clear=new Button(this);clear.setText("清空播放记录");clear.setAllCaps(false);styleTvButton(clear,Color.rgb(125,48,48));tools.addView(clear,new LinearLayout.LayoutParams(-2,dp(44)));
    clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空播放记录？").setMessage("将删除所有点播记录和续播进度。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{saveRoomVodHistory("[]");loadVodHistory(status,results);}).show());
    results.addView(tools);
    status.setText(history.isEmpty()?"还没有点播记录。观看过的影片会自动保存在这里。":"最近观看 · "+history.size()+" 部（点击继续观看，长按删除）");
    GridView grid=ensureVodGrid(results,1);grid.setAdapter(new VodHistoryAdapter(history,status,results));grid.setSelection(0);
  }
  private String formatVodTime(long ms){
    long seconds=Math.max(0,ms/1000),hours=seconds/3600,minutes=(seconds%3600)/60,remain=seconds%60;
    return hours>0?String.format(Locale.US,"%d:%02d:%02d",hours,minutes,remain):String.format(Locale.US,"%02d:%02d",minutes,remain);
  }

  private long vodPositionMs(){if(vodFallbackBackend!=null)return vodFallbackBackend.getPositionMs();return vodPlayer==null?0L:Math.max(0L,vodPlayer.getCurrentPosition());}
  private long vodDurationMs(){if(vodFallbackBackend!=null)return vodFallbackBackend.getDurationMs();return vodPlayer==null?0L:Math.max(0L,vodPlayer.getDuration());}
  private boolean vodIsPlaying(){if(vodFallbackBackend!=null)return vodFallbackBackend.isPlaying();return vodPlayer!=null&&vodPlayer.isPlaying();}
  private void vodPause(){if(vodFallbackBackend!=null)vodFallbackBackend.pause();else if(vodPlayer!=null)vodPlayer.pause();}
  private void vodResume(){if(isAnyPlaybackLockActive())return;if(vodFallbackBackend!=null)vodFallbackBackend.resume();else if(vodPlayer!=null)vodPlayer.play();}
  private boolean vodSeekTo(long position){
    long duration=vodDurationMs();if(duration<=0L)return false;
    long target=VodTimelinePolicy.clampPosition(position,duration);
    if(vodFallbackBackend!=null){if(!vodFallbackBackend.seekToMs(target))return false;}
    else if(vodPlayer!=null)vodPlayer.seekTo(target);else return false;
    // Explicit user navigation supersedes automatic resume.
    vodHistoryPendingResumeMs=0L;
    if(vodPendingSeekTask!=null){uiHandler.removeCallbacks(vodPendingSeekTask);vodPendingSeekTask=null;}
    return true;
  }
  // Release VOD Media3 in independent stages. A vendor player may throw from
  // pause()/stop(); a failed stop gets an emergency playlist clear, and final
  // release must still be attempted.
  private void releaseVodPlayerSafely(PlayerView view){
    ExoPlayer old=vodPlayer;
    vodPlayer=null;
    watchMedia3Release(old);
    if(view!=null){
      try{view.setKeepScreenOn(false);}catch(RuntimeException ignored){}
      try{if(view.getPlayer()!=null){noteUiDecoderStage("VOD surface detach begin");view.setPlayer(null);}}catch(RuntimeException error){
        saveLiveDiagnostic("VOD_VIEW_DETACH_ERROR",error.getClass().getSimpleName());
      }
    }
    if(old==null){releaseDecoderOwnershipIfIdle();return;}
    try{old.setPlayWhenReady(false);}catch(RuntimeException error){
      saveLiveDiagnostic("VOD_DISABLE_PLAY_ERROR",error.getClass().getSimpleName());
    }
    stopMedia3BeforeRelease(old,"VOD");
    releaseMedia3Confirmed(old);
  }
  private void releaseVodFallback(){
    if(vodPendingSeekTask!=null){uiHandler.removeCallbacks(vodPendingSeekTask);vodPendingSeekTask=null;}
    if(vodFallbackStartupWatchdog!=null){uiHandler.removeCallbacks(vodFallbackStartupWatchdog);vodFallbackStartupWatchdog=null;}
    LibVlcVodBackend old=vodFallbackBackend;
    vodFallbackBackend=null;
    if(vodVlcVideoLayout!=null)vodVlcVideoLayout.setVisibility(View.GONE);
    if(old!=null){
      old.revoke();
      queueVlcTeardown(old::releaseAndConfirm);
    }else releaseDecoderOwnershipIfIdle();
  }
  private void fallbackVodToLibVlc(Dialog dialog,VodItem item,PlayerView media3View,VLCVideoLayout vlcLayout,long resumePosition,Runnable onEnded,Runnable onFailure){
    if(isAnyPlaybackLockActive()||!activityStarted||vodHistoryEnded||vodFallbackAttempted||vodPlayerDialog!=dialog||activeVodItem!=item||dialog==null||!dialog.isShowing())return;
    final long session=vodSessionGeneration;final long fallbackStartedAt=android.os.SystemClock.elapsedRealtime();vodFallbackAttempted=true;
    // Stop the Media3 watchdog before releasing its player; no stale tick may race the LibVLC session.
    if(vodPlaybackWatchdog!=null){uiHandler.removeCallbacks(vodPlaybackWatchdog);vodPlaybackWatchdog=null;}
    saveLiveDiagnostic("VOD_ENGINE_FALLBACK","Media3 -> LibVLC; resumeMs="+Math.max(0L,resumePosition));
    // Preserve the resume point before the primary engine is discarded.
    checkpointVodHistory();
    vodHistoryPendingResumeMs=Math.max(vodHistoryPendingResumeMs,Math.max(0L,resumePosition));
    if(vodHistoryPendingResumeMs>0L)
      saveVodHistory(item,vodHistoryPendingResumeMs,Math.max(0L,vodDurationMs()));
    releaseVodPlayerSafely(media3View);
    if(decoderReleaseUnconfirmed||PROCESS_VLC_TEARDOWNS.get()>0){
      if(onFailure!=null)onFailure.run();
      toast("旧播放器资源尚未确认释放，暂不能启动备用播放器");return;
    }
    media3View.setVisibility(View.GONE);vlcLayout.setVisibility(View.VISIBLE);
    try{
      if(!acquireDecoderOwnership()){if(onFailure!=null)onFailure.run();return;}
      final LibVlcVodBackend backend=new LibVlcVodBackend(this,vlcLayout,libVlcWorker);vodFallbackBackend=backend;
      final boolean[] resumeSeekRequested={false};
      final boolean[] fallbackPlaying={false},fallbackVideoOutput={false};
      final Runnable restoreResume=()->{
        if(resumeSeekRequested[0]||session!=vodSessionGeneration||vodPlayerDialog!=dialog
            ||activeVodItem!=item||vodFallbackBackend!=backend||vodHistoryPendingResumeMs<=0L
            ||backend.getDurationMs()<=0L)return;
        long target=VodTimelinePolicy.clampPosition(vodHistoryPendingResumeMs,backend.getDurationMs());
        if(backend.seekToMs(target)){
          resumeSeekRequested[0]=true;
          saveLiveDiagnostic("VOD_FALLBACK_RESUME","requestedMs="+target);
          if(vodPendingSeekTask!=null){uiHandler.removeCallbacks(vodPendingSeekTask);vodPendingSeekTask=null;}
        }
      };
      backend.setListener(new VodPlaybackBackend.Listener(){
        private boolean current(){return activityStarted&&!vodHistoryEnded&&session==vodSessionGeneration&&vodPlayerDialog==dialog&&activeVodItem==item&&vodFallbackBackend==backend&&!isFinishing()&&!isDestroyed();}
        private void restorePosition(){if(current())restoreResume.run();}
        private void confirmStartup(){
          if(vodFallbackStartupWatchdog==null||!TrackHealthPolicy.vlcVodStartupConfirmed(
              fallbackPlaying[0],fallbackVideoOutput[0]))return;
          uiHandler.removeCallbacks(vodFallbackStartupWatchdog);vodFallbackStartupWatchdog=null;
          saveLiveDiagnostic("VOD_LIBVLC_START_CONFIRMED","playing and video output events received; real picture/audio unverified");
        }
        @Override public void onReady(){if(current()){fallbackPlaying[0]=true;saveLiveDiagnostic("VOD_LIBVLC_PLAYING","playing_event=true");confirmStartup();restorePosition();}}
        @Override public void onVideoOutput(boolean available){if(current()){fallbackVideoOutput[0]=available;saveLiveDiagnostic("VOD_LIBVLC_VIDEO_OUTPUT","available="+available);confirmStartup();if(available)restorePosition();}}
        @Override public void onAudioTrackState(int count,int selected){
          if(current())saveLiveDiagnostic("VOD_VLC_AUDIO_TRACK_CHECK",
              "tracks="+count+" selected="+selected+"; speaker output requires device validation");
        }
        @Override public void onFailure(String reason){if(current()){saveLiveDiagnostic("VOD_LIBVLC_FAILURE","reason="+safeDiagnosticDetail(reason));checkpointVodHistory();releaseVodFallback();if(onFailure!=null)onFailure.run();toast("备用播放器也无法播放："+reason);}}
        @Override public void onEnded(){
          if(!current())return;
          // Capture duration/history via the terminal callback before release
          // clears the backend. No native polling or startup timer after EOF.
          stopVodTerminalTimers();
          try{if(onEnded!=null)onEnded.run();else markVodHistoryEnded(item,backend.getDurationMs());}
          finally{if(vodFallbackBackend==backend)releaseVodFallback();}
        }
      });
      vodFallbackStartupWatchdog=new Runnable(){
        @Override public void run(){
          if(vodFallbackStartupWatchdog!=this)return;
          if(!activityStarted||session!=vodSessionGeneration||vodPlayerDialog!=dialog||activeVodItem!=item||vodFallbackBackend!=backend||!dialog.isShowing()){vodFallbackStartupWatchdog=null;return;}
          if(android.os.SystemClock.elapsedRealtime()-fallbackStartedAt>=30000L){
            saveLiveDiagnostic("VOD_LIBVLC_START_TIMEOUT","timeoutMs=30000; playing_event="+fallbackPlaying[0]+" video_output="+fallbackVideoOutput[0]);
            releaseVodFallback();
            if(onFailure!=null)onFailure.run();
            toast("备用播放器等待 30 秒仍未确认播放及视频输出，已停止本次播放");
            return;
          }
          uiHandler.postDelayed(this,1000L);
        }
      };
      uiHandler.postDelayed(vodFallbackStartupWatchdog,1000L);
      backend.play(item.url);
      backend.setVideoScaleMode(getSharedPreferences("mytv",0).getInt("vod_aspect_mode",0));
      if(vodHistoryPendingResumeMs>0L){
        final int[] attempts={0};
        vodPendingSeekTask=new Runnable(){@Override public void run(){
          if(session!=vodSessionGeneration||vodFallbackBackend!=backend||vodPendingSeekTask!=this)return;
          restoreResume.run();
          if(vodPendingSeekTask!=this)return;
          if(vodHistoryPendingResumeMs>0L&&!resumeSeekRequested[0]&&++attempts[0]<20)uiHandler.postDelayed(this,500L);
          else vodPendingSeekTask=null;
        }};
        uiHandler.postDelayed(vodPendingSeekTask,500L);
      }
    }catch(RuntimeException|LinkageError error){releaseVodFallback();if(onFailure!=null)onFailure.run();toast("LibVLC 备用播放器启动失败："+error.getClass().getSimpleName());saveLiveDiagnostic("VOD_LIBVLC_START_FAILURE","type="+error.getClass().getSimpleName());}
  }

  // China-first provider: CCTV/CNTV public programme catalogue.
  private JSONArray cctvList(org.json.JSONObject root){
    if(root==null)return new JSONArray();
    org.json.JSONObject data=root.optJSONObject("data");
    org.json.JSONArray list=data==null?null:data.optJSONArray("list");
    if(list==null)list=root.optJSONArray("list");
    return list==null?new JSONArray():list;
  }
  private org.json.JSONObject cctvGetJson(String address)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setConnectTimeout(3500);c.setReadTimeout(5000);c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
    c.setRequestProperty("Referer","https://tv.cctv.com/");
    c.setRequestProperty("Accept","application/json,text/plain,*/*");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("央视目录响应过大");out.write(buf,0,n);}
      return new org.json.JSONObject(out.toString("UTF-8"));
    }finally{c.disconnect();}
  }
  private VodItem resolveCctvVideo(String guid,String fallbackTitle){
    if(guid==null||guid.trim().isEmpty())return null;
    try{
      org.json.JSONObject info=cctvGetJson("https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid="+URLEncoder.encode(guid,"UTF-8"));
      String status=info.optString("status","");
      String stream=info.optString("hls_url","");
      if(stream.isEmpty())stream=info.optString("hls_url_high","");
      if(!stream.startsWith("https://")||(!status.isEmpty()&&!"001".equals(status)))return null;
      String title=info.optString("title",fallbackTitle==null?"央视节目":fallbackTitle);
      return new VodItem(title,"cctv:"+guid,stream,"央视网公开节目 · 官方播放地址");
    }catch(Exception ignored){return null;}
  }
  private ArrayList<VodItem> fetchCctvVod(String category){
    ArrayList<VodItem> found=new ArrayList<>();
    String channelId=CctvVodCategoryPolicy.channelIdFor(category);
    if(channelId==null)return found;
    HashSet<String> ids=new HashSet<>();
    long deadline=android.os.SystemClock.elapsedRealtime()+22000L;
    int albumChecks=0;
    try{
      for(int page=1;page<=2&&found.size()<24&&android.os.SystemClock.elapsedRealtime()<deadline;page++){
        String endpoint="https://api.cntv.cn/list/getVideoAlbumList?channelid="+channelId+"&p="+page+"&n=24&serviceId=tvcctv&topv=1&t=json";
        JSONArray albums=cctvList(cctvGetJson(endpoint));
        if(albums.length()==0)break;
        for(int i=0;i<albums.length()&&found.size()<24&&albumChecks<8&&android.os.SystemClock.elapsedRealtime()<deadline;i++){
          if(Thread.currentThread().isInterrupted())return found;
          albumChecks++;
          org.json.JSONObject album=albums.optJSONObject(i);if(album==null)continue;
          String albumId=album.optString("id",album.optString("albumId",""));
          String albumTitle=album.optString("title","央视节目");
          String directGuid=album.optString("guid",album.optString("pid",""));
          if(!directGuid.isEmpty()&&ids.add(directGuid)){
            VodItem direct=resolveCctvVideo(directGuid,albumTitle);if(direct!=null)found.add(direct);
          }
          if(albumId.isEmpty())continue;
          try{
            String episodeUrl="https://api.cntv.cn/NewVideo/getVideoListByAlbumIdNew?id="+URLEncoder.encode(albumId,"UTF-8")+"&serviceId=tvcctv&p=1&n=30&mode=0&pub=1";
            JSONArray episodes=cctvList(cctvGetJson(episodeUrl));
            for(int j=0;j<episodes.length()&&found.size()<24&&j<12&&android.os.SystemClock.elapsedRealtime()<deadline;j++){
              if(Thread.currentThread().isInterrupted())return found;
              org.json.JSONObject episode=episodes.optJSONObject(j);if(episode==null)continue;
              String guid=episode.optString("guid",episode.optString("pid",""));
              if(guid.isEmpty()||!ids.add(guid))continue;
              VodItem item=resolveCctvVideo(guid,episode.optString("title",albumTitle));if(item!=null)found.add(item);
            }
          }catch(Exception ignored){}
        }
      }
    }catch(Exception ignored){}
    return found;
  }

  private VodItem fetchAuthorizedVod(org.json.JSONObject doc){return fetchAuthorizedVod(doc,false);}
  private VodItem fetchAuthorizedVod(org.json.JSONObject doc,boolean require1080p){
    String id=doc.optString("identifier",""),title=doc.optString("title",id);
    if(id.isEmpty())return null;
    try{
      org.json.JSONObject meta=new org.json.JSONObject(httpGetFast("https://archive.org/metadata/"+URLEncoder.encode(id,"UTF-8")));
      org.json.JSONObject md=meta.optJSONObject("metadata");if(md==null)return null;
      String license=md.optString("licenseurl",md.optString("rights",""));
      String low=license.toLowerCase(Locale.US);
      boolean allowed=low.contains("creativecommons.org/licenses/by/")||low.contains("creativecommons.org/licenses/by-sa/")||low.contains("creativecommons.org/publicdomain/")||low.contains("creativecommons.org/zero/")||low.contains("public domain");
      if(!allowed)return null;
      org.json.JSONArray files=meta.optJSONArray("files");if(files==null)return null;
      String fileName=null;
      long bestFileScore=Long.MIN_VALUE;
      for(int j=0;j<files.length();j++){
        org.json.JSONObject file=files.optJSONObject(j);if(file==null||file.optBoolean("private",false))continue;
        String name=file.optString("name",""),lowerName=name.toLowerCase(Locale.ROOT),format=file.optString("format","").toLowerCase(Locale.US);
        boolean mp4=lowerName.endsWith(".mp4")&&(format.contains("mpeg4")||format.contains("h.264")||format.contains("mpeg"));
        boolean hls=lowerName.endsWith(".m3u8")&&(format.contains("m3u8")||format.contains("hls")||format.contains("playlist"));
        boolean dash=lowerName.endsWith(".mpd")&&(format.contains("dash")||format.contains("mpd")||format.contains("xml"));
        boolean webm=lowerName.endsWith(".webm")&&(format.contains("webm")||format.contains("video"));
        boolean quicktime=lowerName.endsWith(".mov")&&(format.contains("quicktime")||format.contains("video"));
        if(!(mp4||hls||dash||webm||quicktime))continue;
        int width=file.optInt("width",0),height=file.optInt("height",0);
        boolean verified1080=width>=1920&&height>=1080;
        boolean explicit1080=lowerName.matches(".*(?:1080p|1080i|1920x1080).*");
        if(require1080p&&!verified1080&&!explicit1080)continue;
        // Prefer a broadly compatible 1080p MP4 when available; do not simply
        // take the first file in the archive (often a tiny preview or low-res copy).
        int qualityClass=(verified1080||explicit1080)?5:(height>=720||lowerName.matches(".*(?:720p|1280x720).*"))?4:1;
        if(hls)qualityClass=Math.max(qualityClass,3);
        if(dash)qualityClass=Math.max(qualityClass,2);
        if(height>1080||width>1920)qualityClass=Math.min(qualityClass,2);
        long size=Math.max(0L,file.optLong("size",0L));
        long score=(long)qualityClass*1000000000000L+Math.min(size,999999999L);
        if(score>bestFileScore){bestFileScore=score;fileName=name;}
      }
      if(fileName==null)return null;
      return new VodItem(title,id,"https://archive.org/download/"+URLEncoder.encode(id,"UTF-8")+"/"+encodeArchivePath(fileName),license);
    }catch(Exception ignored){return null;}
  }
  private ArrayList<VodItem> fetchCommonsPublicDomainVod(boolean chineseOnly){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      String category="Category:Videos_of_films_in_the_public_domain";
      String api="https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers&gcmtitle="+URLEncoder.encode(category,"UTF-8")+"&gcmtype=file&gcmlimit=100&prop=imageinfo&iiprop=url|extmetadata&iiextmetadatalanguage=en&format=json";
      org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
      org.json.JSONObject query=root.optJSONObject("query");
      org.json.JSONObject pages=query==null?null:query.optJSONObject("pages");
      if(pages==null)return items;
      java.util.Iterator<String> keys=pages.keys();
      while(keys.hasNext()&&items.size()<48){
        org.json.JSONObject page=pages.optJSONObject(keys.next());if(page==null)continue;
        String title=page.optString("title","");String lower=title.toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".mp4")||lower.endsWith(".webm")))continue;
        if(chineseOnly&&!title.matches(".*[\\u3400-\\u9fff].*"))continue;
        org.json.JSONArray info=page.optJSONArray("imageinfo");if(info==null||info.length()==0)continue;
        org.json.JSONObject ii=info.optJSONObject(0);if(ii==null)continue;
        org.json.JSONObject ext=ii.optJSONObject("extmetadata");if(ext==null)continue;
        org.json.JSONObject license=ext.optJSONObject("LicenseShortName");
        String licenseName=license==null?"":android.text.Html.fromHtml(license.optString("value",""),android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        String l=licenseName.toLowerCase(Locale.ROOT);
        if(!(l.contains("public domain")||l.equals("cc0")||l.contains("cc by")||l.contains("cc-by")))continue;
        String url=ii.optString("url","");if(!url.startsWith("https://"))continue;
        String clean=title.substring(title.lastIndexOf(':')+1).replaceAll("\\.(mp4|webm)$","");
        items.add(new VodItem(clean,"commons:"+title,url,licenseName));
      }
    }catch(Exception ignored){}
    return items;
  }


  // Commons search is independent from its curated public-domain category listing.
  // Keep only direct video files carrying a public-domain or Creative Commons license.
  private ArrayList<VodItem> fetchCommonsSearchVod(String query){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      String api="https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch="+URLEncoder.encode(query,"UTF-8")+"&gsrnamespace=6&gsrlimit=50&prop=imageinfo&iiprop=url|extmetadata&iiextmetadatalanguage=en&format=json";
      org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
      org.json.JSONObject queryObject=root.optJSONObject("query");
      org.json.JSONObject pages=queryObject==null?null:queryObject.optJSONObject("pages");
      if(pages==null)return items;
      java.util.Iterator<String> keys=pages.keys();
      while(keys.hasNext()&&items.size()<32){
        org.json.JSONObject page=pages.optJSONObject(keys.next());if(page==null)continue;
        String title=page.optString("title",""),lower=title.toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".mp4")||lower.endsWith(".webm")||lower.endsWith(".ogv")||lower.endsWith(".mov")))continue;
        org.json.JSONArray info=page.optJSONArray("imageinfo");if(info==null||info.length()==0)continue;
        org.json.JSONObject ii=info.optJSONObject(0);if(ii==null)continue;
        org.json.JSONObject ext=ii.optJSONObject("extmetadata");if(ext==null)continue;
        org.json.JSONObject license=ext.optJSONObject("LicenseShortName");
        String licenseName=license==null?"":android.text.Html.fromHtml(license.optString("value",""),android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        String l=licenseName.toLowerCase(Locale.ROOT);
        if(!(l.contains("public domain")||l.equals("cc0")||l.contains("cc by")||l.contains("cc-by")))continue;
        String url=ii.optString("url","");if(!url.startsWith("https://"))continue;
        String clean=title.substring(title.lastIndexOf(':')+1).replaceAll("\\.(mp4|webm|ogv|mov)$","");
        items.add(new VodItem(clean,"commons-search:"+title,url,licenseName));
      }
    }catch(Exception ignored){}
    return items;
  }

  // Independent Library of Congress film/video catalogue. Require explicit public-domain
  // or no-known-restrictions wording plus a downloadable video file marked unrestricted.
  private void collectLocVideoFiles(Object node,ArrayList<String> urls){
    if(node instanceof org.json.JSONObject){
      org.json.JSONObject o=(org.json.JSONObject)node;
      String mime=o.optString("mimetype","").toLowerCase(Locale.ROOT);
      boolean unrestricted=o.has("rights_restricted")&&!o.optBoolean("rights_restricted",true);
      if(unrestricted&&mime.startsWith("video/")){
        String[] fields={"url","video_stream","video"};
        for(String field:fields){String u=o.optString(field,"");if(u.startsWith("https://")&&!urls.contains(u))urls.add(u);}
      }
      java.util.Iterator<String> it=o.keys();
      while(it.hasNext())collectLocVideoFiles(o.opt(it.next()),urls);
    }else if(node instanceof org.json.JSONArray){
      org.json.JSONArray a=(org.json.JSONArray)node;
      for(int i=0;i<a.length();i++)collectLocVideoFiles(a.opt(i),urls);
    }
  }
  private VodItem fetchLocVideoItem(String itemId,String fallbackTitle){
    try{
      String endpoint=itemId.startsWith("http://")?"https://"+itemId.substring(7):itemId;
      if(!endpoint.startsWith("https://www.loc.gov/"))return null;
      org.json.JSONObject root=new org.json.JSONObject(httpGetFast(endpoint+(endpoint.contains("?")?"&":"?")+"fo=json"));
      org.json.JSONObject item=root.optJSONObject("item");if(item==null)return null;
      String rights=item.optString("rights","")+" "+item.optString("rights_advisory","")+" "+item.optString("rights_information","");
      org.json.JSONArray rightsArray=item.optJSONArray("rights");
      if(rightsArray!=null)for(int i=0;i<rightsArray.length();i++)rights+=" "+rightsArray.optString(i,"");
      String low=rights.toLowerCase(Locale.ROOT);
      boolean allowed=low.contains("public domain")||low.contains("no known restrictions")||low.contains("no known copyright restrictions")||low.contains("u.s. government work")||low.contains("united states government work");
      if(!allowed)return null;
      ArrayList<String> urls=new ArrayList<>();
      collectLocVideoFiles(root.opt("resources"),urls);
      if(urls.isEmpty())collectLocVideoFiles(root.opt("resource"),urls);
      if(urls.isEmpty())return null;
      String title=item.optString("title",fallbackTitle);
      String id=item.optString("id",itemId);
      return new VodItem(title,"loc:"+id,urls.get(0),"Library of Congress · "+rights.trim());
    }catch(Exception ignored){return null;}
  }
  private ArrayList<VodItem> fetchMetadataVod(String query,String region){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      for(VodTitleRecord record:VodMetadataSearch.search(query,region)){
        if(record==null||record.title.isEmpty())continue;
        String metadataPage=record.metadataUrl;
        items.add(new VodItem(record.title,"meta:"+record.id,"",
            "影片资料 · "+record.metadataProvider+" · 暂无已验证直播放源"+(metadataPage.isEmpty()?"":" · "+metadataPage),record.posterUrl));
      }
    }catch(Exception ignored){}
    return items;
  }

  private ArrayList<VodItem> fetchLocPublicDomainVod(String query){
    ArrayList<VodItem> items=new ArrayList<>();
    ArrayList<Future<VodItem>> tasks=new ArrayList<>();
    try{
      if(Thread.currentThread().isInterrupted())return items;
      String api="https://www.loc.gov/film-and-videos/?q="+URLEncoder.encode(query,"UTF-8")+"&fo=json&c=18&sp=1";
      org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
      org.json.JSONArray docs=root.optJSONArray("results");if(docs==null)return items;
      CompletionService<VodItem> completion=new PurgeableCompletionService<>(vodMetadataWorker);
      int submitted=0;
      for(int i=0;i<docs.length()&&submitted<12&&!Thread.currentThread().isInterrupted();i++){
        org.json.JSONObject doc=docs.optJSONObject(i);if(doc==null)continue;
        String id=doc.optString("id","");if(!id.startsWith("http"))continue;
        String title=doc.optString("title","公开影像");
        tasks.add(completion.submit(()->fetchLocVideoItem(id,title)));submitted++;
      }
      long deadline=android.os.SystemClock.elapsedRealtime()+7000L;
      while(items.size()<8&&submitted>0&&android.os.SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted()){
        Future<VodItem> completed=completion.poll(350,TimeUnit.MILLISECONDS);
        if(completed==null)continue;
        submitted--;
        try{VodItem item=completed.get();if(item!=null)items.add(item);}
        catch(CancellationException ignored){}
        catch(ExecutionException ignored){}
      }
    }catch(InterruptedException interrupted){
      Thread.currentThread().interrupt();
    }catch(Exception ignored){
      // A failed catalogue request must not leave its child metadata lookups running.
    }finally{
      for(Future<VodItem> task:tasks)if(!task.isDone())task.cancel(true);
      purgeCancelledTasks(vodMetadataWorker);
    }
    return items;
  }

  private void loadVodCatalogue(String category,LinearLayout panel,TextView status,LinearLayout results){
    loadVodCatalogue(category,panel,status,results,false);
  }
  private void loadVodCatalogue(String category,LinearLayout panel,TextView status,LinearLayout results,boolean forceRefresh){
    Future<?> previous=activeVodCatalogueTask;if(previous!=null)previous.cancel(true);
    purgeCancelledTasks(vodWorker);
    final int request=++vodRequestId;
    final String requestRegion=activeVodRegion;
    final String cacheKey=VodCachePolicy.cacheKey(category,requestRegion);
    // Room may contain a large VOD cache. Read and parse it
    // away from the UI thread; only publish the resulting list on the main thread.
    activeVodCatalogueTask=vodWorker.submit(()->{
      VodCacheSnapshot cache=readVodCache(cacheKey);
      ArrayList<VodItem> cached=cache.items;
      uiHandler.post(()->{
        if(isFinishing()||request!=vodRequestId||panel!=currentVodPanel)return;
        if(!cached.isEmpty())renderVodItems(category,cached,status,results,true);
        else{
          status.setText("正在启动多页点播检索「"+category+"」…");
          GridView grid=resetVodGrid(results,4);
          grid.setAdapter(new VodGridAdapter(new ArrayList<>()));
        }
        if(!forceRefresh&&!cached.isEmpty()&&VodCachePolicy.isFresh(cache.savedAt,System.currentTimeMillis(),6L*60L*60L*1000L)){
          status.setText("「"+category+"」 · 已缓存 "+cached.size()+" 部，6小时内自动复用");
          return;
        }
        if(forceRefresh)invalidateVodCacheFreshness(cacheKey);
        startVodCatalogueSearch(category,panel,status,results,forceRefresh,request,requestRegion,cacheKey,cached);
      });
    });
  }
  private void startVodCatalogueSearch(String category,LinearLayout panel,TextView status,LinearLayout results,boolean forceRefresh,int request,String requestRegion,String cacheKey,ArrayList<VodItem> cached){
    activeVodCatalogueTask=vodWorker.submit(()->{
      ArrayList<VodItem> found=new ArrayList<>();String error=null;
      ArrayList<Future<VodItem>> pendingVodChecks=new ArrayList<>();
      ArrayList<Future<org.json.JSONArray>> pageTasks=new ArrayList<>();
      ArrayList<Future<ArrayList<VodItem>>> sourceFutures=new ArrayList<>();
      CompletionService<ArrayList<VodItem>> sourceCompletion=new PurgeableCompletionService<>(vodSourceWorker);
      boolean titleSearch=category.startsWith("搜索：");
      String searchTerm=titleSearch?category.substring("搜索：".length()).trim():"";
      if(titleSearch&&!searchTerm.isEmpty())sourceFutures.add(sourceCompletion.submit(()->fetchMetadataVod(searchTerm,requestRegion)));
      HashSet<String> submittedIds=new HashSet<>(),foundIds=new HashSet<>(),foundTitleKeys=new HashSet<>();
      try{

        // Prefer official CCTV catalogue for Chinese documentary/animation categories.
        if(Arrays.asList("中国纪录片","中国历史影像","中国经典影像","中国动画","中文动画").contains(category)){
          ArrayList<VodItem> cctvItems=fetchCctvVod(category);
          for(VodItem item:cctvItems)if(found.size()<80)addUniqueVodItem(found,foundIds,foundTitleKeys,item);
        }

        String q="mediatype:movies";
        if(titleSearch){
          String term=searchTerm.replace("\\"," ").replace("\""," ").replaceAll("[\\r\\n]+"," ").trim();
          String phrase=term.replaceAll("\\s+"," ");
          if(phrase.isEmpty())phrase="film";
          q="mediatype:movies AND (title:\""+phrase+"\" OR description:\""+phrase+"\" OR subject:\""+phrase+"\" OR creator:\""+phrase+"\")";
        }
        else if("电影".equals(category))q="mediatype:movies AND (subject:feature film OR subject:public domain OR collection:feature_films)";
        else if("公开电视剧".equals(category)||"电视剧".equals(category))q="mediatype:movies AND (subject:television OR title:television series OR title:TV series OR collection:television)";
        else if("公开综艺".equals(category)||"综艺".equals(category))q="mediatype:movies AND (subject:variety show OR title:variety show OR title:talk show OR subject:talk show)";
        else if("纪录片·历史".equals(category))q="mediatype:movies AND subject:documentary AND (subject:history OR title:history OR title:historical)";
        else if("纪录片·自然".equals(category))q="mediatype:movies AND subject:documentary AND (subject:nature OR title:nature OR subject:wildlife)";
        else if("纪录片·科学".equals(category))q="mediatype:movies AND subject:documentary AND (subject:science OR title:science OR subject:technology)";
        else if("戏曲".equals(category))q="mediatype:movies AND (title:Chinese opera OR title:traditional opera OR subject:Chinese opera OR title:Peking opera OR title:Beijing opera OR title:黄梅戏 OR title:京剧 OR title:越剧 OR title:豫剧)";
        else if("1080P高清电影".equals(category))q="mediatype:movies AND (title:1080p OR title:1080 OR description:1080p OR description:1920x1080)";
        else if("多源精选".equals(category))q="mediatype:movies AND (subject:documentary OR subject:animation OR subject:comedy OR subject:science_fiction OR subject:short film) AND (language:English OR language:eng)";
        else if("精选电影".equals(category))q="collection:feature_films AND mediatype:movies AND (language:English OR language:eng) AND (subject:classic OR title:classic OR subject:feature film)";
        else if("中国经典影像".equals(category))q="mediatype:movies AND (language:Chinese OR language:chi OR language:zho OR title:中国 OR title:中文 OR title:华语 OR title:国语 OR title:Mandarin OR subject:Chinese OR subject:China) AND (subject:classic OR title:classic OR title:feature OR subject:cinema OR title:电影)";
        else if("中国历史影像".equals(category))q="mediatype:movies AND (subject:documentary OR title:documentary OR title:historical OR subject:history) AND (title:China OR title:Chinese OR subject:China OR subject:Chinese OR language:chi OR language:zho)";
        else if("中国电影".equals(category)||"中国老电影".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:中国 OR title:中文 OR title:华语 OR title:China OR title:Chinese OR title:Mandarin OR subject:Chinese OR subject:China)";
        else if("华语经典".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:Mandarin OR title:Chinese OR subject:Chinese)";
        else if("中国动画".equals(category)||"中文动画".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:中国 OR title:中文 OR title:China) AND (subject:animation OR subject:cartoons OR title:动画)";
        else if("中国纪录片".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:China OR title:Chinese OR subject:China) AND (subject:documentary OR title:documentary)";
        else if("中文纪录片".equals(category))q+=" AND (language:chi OR language:zho OR title:中文 OR title:国语 OR title:华语) AND (subject:documentary OR title:documentary)";
        else if("国产短片".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho) AND (subject:short film OR title:短片)";
        else if("纪录片".equals(category))q+=" AND subject:documentary AND NOT (language:chi OR language:zho OR title:China OR title:Chinese)";
        else if("科学纪录片".equals(category))q+=" AND subject:documentary AND (subject:science OR title:science)";
        else if("历史纪录片".equals(category))q+=" AND subject:documentary AND (subject:history OR title:history)";
        else if("自然纪录片".equals(category))q+=" AND subject:documentary AND (subject:nature OR title:nature OR subject:wildlife)";
        else if("NASA影像".equals(category))q="mediatype:movies AND (collection:nasa OR creator:NASA)";
        else if("战争片".equals(category))q="mediatype:movies AND (subject:war OR subject:military OR title:war)";
        else if("恐怖片".equals(category))q="mediatype:movies AND (subject:horror OR title:horror)";
        else if("动作片".equals(category))q="mediatype:movies AND (subject:action OR title:action)";
        else if("动画".equals(category)||"动漫".equals(category))q+=" AND (subject:animation OR subject:cartoons)";
        else if("喜剧".equals(category))q+=" AND subject:comedy";
        else if("科幻".equals(category))q+=" AND (subject:science_fiction OR subject:science-fiction)";
        else if("剧情".equals(category))q+=" AND subject:drama";
        else if("欧美经典".equals(category))q+=" AND (language:English OR language:eng) AND (collection:feature_films OR subject:classic)";
        else if("默片经典".equals(category))q+=" AND (subject:silent film OR title:silent)";
        else if("短片".equals(category))q+=" AND (subject:short film OR subject:shorts)";
        else if(!titleSearch&&!Arrays.asList("世界公版电影","精选电影","多源精选","电影","公开电视剧","公开综艺","纪录片·历史","纪录片·自然","纪录片·科学","中国电影","中国老电影","华语经典","中国经典影像","中国历史影像","中国动画","中文动画","中国纪录片","中文纪录片","戏曲","1080P高清电影","纪录片","科学纪录片","历史纪录片","自然纪录片","NASA影像","战争片","恐怖片","动作片","动画","喜剧","科幻","剧情","欧美经典","默片经典","短片","国产短片").contains(category))q+=" AND title:("+category.replace(" "," AND ")+")";

        ArrayList<String> queries=new ArrayList<>();
        if(titleSearch)queries.addAll(VodSearchPlan.archiveQueries(searchTerm,requestRegion));
        else queries.add(q);
        if("戏曲".equals(category)){
          queries.add("mediatype:movies AND (title:Chinese opera OR title:traditional Chinese opera OR subject:Chinese opera OR title:Peking opera)");
          queries.add("mediatype:movies AND (title:京剧 OR title:越剧 OR title:黄梅戏 OR title:豫剧 OR title:粤剧 OR title:戏曲)");
        }else if("1080P高清电影".equals(category)){
          queries.add("mediatype:movies AND (title:1080p OR title:1080i OR description:1080p OR description:1920x1080)");
          queries.add("mediatype:movies AND (title:full HD OR title:fullhd OR subject:1080p)");
        }
        // Chinese film records are inconsistently catalogued; search several independent
        // title/language/region vocabularies rather than trusting one query field.
        if("中国电影".equals(category)||"中国老电影".equals(category)||"华语经典".equals(category)){
          String broad="mediatype:movies AND (title:Mandarin OR title:Chinese OR title:China OR subject:Chinese OR subject:China OR language:chi OR language:zho)";
          String chineseTitle="mediatype:movies AND (title:中国 OR title:中文 OR title:华语 OR title:国语 OR title:民国 OR title:上海 OR subject:Chinese)";
          String regional="mediatype:movies AND (title:Hong Kong OR title:Taiwan OR title:Shanghai OR title:Mandarin OR subject:Hong Kong cinema OR subject:Chinese cinema)";
          String silent="mediatype:movies AND (title:Chinese silent OR subject:Chinese silent film OR title:China film)";
          for(String extra:Arrays.asList(broad,chineseTitle,regional,silent))if(!queries.contains(extra))queries.add(extra);
        }else if("中国经典影像".equals(category)){
          queries.add("mediatype:movies AND (language:chi OR language:zho OR title:Chinese OR title:Mandarin OR subject:Chinese cinema) AND (title:classic OR subject:classic film OR subject:feature film)");
          queries.add("mediatype:movies AND (title:Hong Kong classic OR title:Taiwan classic OR subject:Hong Kong cinema OR subject:Chinese cinema)");
        }else if("中国动画".equals(category)||"中文动画".equals(category)){
          queries.add("mediatype:movies AND (title:Chinese animation OR title:China animation OR subject:Chinese animation OR language:chi OR language:zho)");
        }else if("中国历史影像".equals(category)){
          queries.add("mediatype:movies AND (title:Chinese history documentary OR title:China history documentary OR subject:Chinese history OR subject:Chinese historical documentary)");
          queries.add("mediatype:movies AND (title:China newsreel OR title:Chinese archive footage OR subject:China historical footage)");
        }else if("中国纪录片".equals(category)){
          queries.add("mediatype:movies AND (title:China documentary OR title:Chinese documentary OR subject:Chinese documentary OR language:chi OR language:zho)");
        }else if("多源精选".equals(category)){
          queries.add("mediatype:movies AND (subject:documentary OR subject:animation OR subject:comedy) AND (language:English OR language:eng)");
          queries.add("mediatype:movies AND (subject:science_fiction OR subject:short film OR subject:educational film) AND (language:English OR language:eng)");
        }else if("精选电影".equals(category)||"世界公版电影".equals(category)){
          queries.add("mediatype:movies AND (collection:feature_films OR subject:public_domain OR title:classic feature film)");
          queries.add("mediatype:movies AND (subject:film noir OR subject:western OR subject:silent film OR subject:classic cinema)");
        }
        if("精选电影".equals(category)){
          queries.add("collection:feature_films AND mediatype:movies AND (language:English OR language:eng) AND (title:classic OR subject:classic film OR subject:feature film)");
          queries.add("mediatype:movies AND (subject:film noir OR subject:western OR subject:silent film OR subject:classic cinema) AND (language:English OR language:eng)");
        }

        CompletionService<VodItem> completion=new PurgeableCompletionService<>(vodMetadataWorker);
        CompletionService<org.json.JSONArray> pageCompletion=new PurgeableCompletionService<>(vodSourceWorker);
        int submitted=0,completed=0;
        // Prioritize page 1 across every independent query before deeper pages.
        // This avoids letting one broad query occupy the queue with pages 1-3 while
        // Chinese/regional query variants wait behind it. Later pages still run,
        // preserving catalogue breadth after the first-page coverage is queued.
        for(VodSearchPlan.PageRequest pageRequest:VodSearchPlan.orderedPages(queries.size(),3)){
          if(Thread.currentThread().isInterrupted())break;
          final String pageQuery=queries.get(pageRequest.queryIndex);
          final int pageNumber=pageRequest.page;
          pageTasks.add(pageCompletion.submit(()->{
            String api="https://archive.org/advancedsearch.php?q="+URLEncoder.encode(pageQuery,"UTF-8")+"&fl[]=identifier&fl[]=title&rows=100&page="+pageNumber+"&output=json&sort[]=downloads+desc";
            org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
            return root.getJSONObject("response").getJSONArray("docs");
          }));
        }
        long pageDeadline=android.os.SystemClock.elapsedRealtime()+14000L;
        int pagesRemaining=pageTasks.size();
        while(pagesRemaining>0&&submitted<180&&!Thread.currentThread().isInterrupted()){
          long remaining=pageDeadline-android.os.SystemClock.elapsedRealtime();
          if(remaining<=0)break;
          Future<org.json.JSONArray> pageFuture=pageCompletion.poll(Math.min(500L,remaining),TimeUnit.MILLISECONDS);
          if(pageFuture==null)continue;
          pagesRemaining--;
          try{
            org.json.JSONArray docs=pageFuture.get();
            for(int i=0;i<docs.length()&&submitted<180;i++){
              org.json.JSONObject doc=docs.optJSONObject(i);if(doc==null)continue;
              String id=doc.optString("identifier","");
              if(id.isEmpty()||!submittedIds.add(id))continue;
              final org.json.JSONObject candidate=doc;
              pendingVodChecks.add(completion.submit(()->fetchAuthorizedVod(candidate,"1080P高清电影".equals(category))));
              submitted++;
            }
          }catch(Exception ignored){}
        }
        for(Future<org.json.JSONArray> task:pageTasks)if(!task.isDone())task.cancel(true);
        long metadataDeadline=android.os.SystemClock.elapsedRealtime()+16000L;
        int lastProgressCount=0;
        while(completed<submitted&&found.size()<80&&!Thread.currentThread().isInterrupted()){
          long remaining=metadataDeadline-android.os.SystemClock.elapsedRealtime();
          if(remaining<=0)break;
          Future<VodItem> future=completion.poll(Math.min(500L,remaining),TimeUnit.MILLISECONDS);
          if(future==null)continue;
          completed++;
          try{
            VodItem item=future.get();
            if(item!=null&&found.size()<80)addUniqueVodItem(found,foundIds,foundTitleKeys,item);
            if(found.size()>=6&&found.size()-lastProgressCount>=4){
              lastProgressCount=found.size();
              ArrayList<VodItem> partialBatch=new ArrayList<>(found);
              uiHandler.post(()->{
                if(!isFinishing()&&request==vodRequestId){
                  writeVodCache(cacheKey,partialBatch,false);
                  renderVodItems(category,partialBatch,status,results,false);
                  status.setText("「"+category+"」 · 已找到 "+partialBatch.size()+" 部，继续搜索其他结果…");
                }
              });
            }
          }catch(Exception ignored){}
        }
        for(Future<VodItem> future:pendingVodChecks)if(!future.isDone())future.cancel(true);
        // Publish the first usable batch before optional catalogues finish.
        if(!found.isEmpty()){
          ArrayList<VodItem> firstBatch=new ArrayList<>(found);
          uiHandler.post(()->{
            if(!isFinishing()&&request==vodRequestId){
              writeVodCache(cacheKey,firstBatch,false);
              renderVodItems(category,firstBatch,status,results,false);
              status.setText("「"+category+"」 · 已先显示 "+firstBatch.size()+" 部，其他片库继续检索…");
            }
          });
        }

        // Start independent open catalogues together after the first Archive batch.
        // Waiting is bounded by the slowest provider rather than the sum of their delays.
        // Collect independent catalogue providers by completion order. Waiting on
        // each Future in submission order can accidentally add three full timeouts
        // together when the first provider is slow.
        // Metadata search is already running in parallel with direct-stream search.
        boolean useCommonsPublic=Arrays.asList("世界公版电影","精选电影","中国电影","中国老电影","华语经典","中国动画","中文动画").contains(category)&&found.size()<48;
        if(useCommonsPublic)sourceFutures.add(sourceCompletion.submit(()->fetchCommonsPublicDomainVod(!"世界公版电影".equals(category))));
        String commonsQuery=null;
        if("中国电影".equals(category)||"中国老电影".equals(category)||"华语经典".equals(category))commonsQuery="Chinese film";
        else if("中国经典影像".equals(category))commonsQuery="Chinese classic feature film";
        else if("中国历史影像".equals(category))commonsQuery="Chinese historical newsreel archive footage";
        else if("中国动画".equals(category)||"中文动画".equals(category))commonsQuery="Chinese animation";
        else if("中国纪录片".equals(category))commonsQuery="China documentary";
        else if("中文纪录片".equals(category))commonsQuery="Chinese language documentary";
        else if("纪录片".equals(category))commonsQuery="documentary film";
        else if("戏曲".equals(category))commonsQuery="Chinese traditional opera performance film";
         else if("1080P高清电影".equals(category))commonsQuery="1080p full HD feature film";
         else if("多源精选".equals(category))commonsQuery="public domain documentary animation short film";
        else if("精选电影".equals(category)||"世界公版电影".equals(category))commonsQuery="public domain feature film";
        else if("战争片".equals(category))commonsQuery="war film";
        else if("恐怖片".equals(category))commonsQuery="horror film";
        else if("动作片".equals(category))commonsQuery="action film";
        else if(!Arrays.asList("我的收藏","播放记录","多源精选","精选电影","中国电影","中国老电影","中国经典影像","华语经典","中国历史影像","中国动画","中文动画","中国纪录片","中文纪录片","世界公版电影","欧美经典","默片经典","纪录片","科学纪录片","历史纪录片","自然纪录片","NASA影像","动画","喜剧","科幻","剧情","短片").contains(category))commonsQuery=category;
        if(titleSearch)commonsQuery=searchTerm+("中国".equals(requestRegion)?" Chinese film":" international film");
         if(commonsQuery!=null&&found.size()<80){final String term=commonsQuery;sourceFutures.add(sourceCompletion.submit(()->fetchCommonsSearchVod(term)));}
        String locQuery=null;
        if("中国电影".equals(category)||"华语经典".equals(category)||"中国老电影".equals(category))locQuery="Chinese film";
        else if("中国经典影像".equals(category))locQuery="Chinese classic feature film";
        else if("中国历史影像".equals(category))locQuery="China historical documentary newsreel";
        else if("中国动画".equals(category)||"中文动画".equals(category))locQuery="Chinese animation";
        else if("中国纪录片".equals(category))locQuery="China documentary";
        else if("中文纪录片".equals(category))locQuery="Chinese language documentary";
        else if("纪录片".equals(category))locQuery="documentary film";
        else if("历史纪录片".equals(category))locQuery="historical documentary";
        else if("自然纪录片".equals(category)||"科学纪录片".equals(category))locQuery="nature science documentary";
        else if("戏曲".equals(category))locQuery="Chinese opera traditional opera film";
         else if("1080P高清电影".equals(category))locQuery="1080p full HD feature film";
         else if("多源精选".equals(category))locQuery="documentary animation short film";
        else if("世界公版电影".equals(category)||"精选电影".equals(category))locQuery="public domain feature film";
        else if("欧美经典".equals(category)||"默片经典".equals(category))locQuery="silent film";
        else if("战争片".equals(category))locQuery="war film";
        else if("恐怖片".equals(category))locQuery="horror film";
        else if("动作片".equals(category))locQuery="action film";
        else if(!Arrays.asList("我的收藏","播放记录","多源精选","精选电影","中国电影","中国老电影","中国经典影像","华语经典","中国历史影像","中国动画","中文动画","中国纪录片","中文纪录片","世界公版电影","欧美经典","默片经典","纪录片","科学纪录片","历史纪录片","自然纪录片","NASA影像","动画","喜剧","科幻","剧情","短片").contains(category))locQuery=category;
        if(titleSearch)locQuery=searchTerm;
         if(locQuery!=null&&found.size()<80){final String term=locQuery;sourceFutures.add(sourceCompletion.submit(()->fetchLocPublicDomainVod(term)));}
        long sourceDeadline=android.os.SystemClock.elapsedRealtime()+6500L;
        for(int completedSources=0;completedSources<sourceFutures.size()&&!Thread.currentThread().isInterrupted();completedSources++){
          long remaining=sourceDeadline-android.os.SystemClock.elapsedRealtime();
          if(remaining<=0L)break;
          Future<ArrayList<VodItem>> completedSource=sourceCompletion.poll(remaining,TimeUnit.MILLISECONDS);
          if(completedSource==null)break;
          try{
            ArrayList<VodItem> sourceItems=completedSource.get();
            if(sourceItems!=null)for(VodItem item:sourceItems)if(found.size()<80)addUniqueVodItem(found,foundIds,foundTitleKeys,item);
          }catch(Exception ignored){}
        }
        // Enforce one shared provider budget, not 6.5 seconds per provider.
        for(Future<ArrayList<VodItem>> pendingSource:sourceFutures)if(!pendingSource.isDone())pendingSource.cancel(true);
      }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
      finally{
        // A cancelled catalogue request must release every child request too.
        // Otherwise stale metadata/page/provider tasks can occupy the bounded pools
        // and delay the user's next category or title search.
        for(Future<org.json.JSONArray> task:pageTasks)if(!task.isDone())task.cancel(true);
        for(Future<VodItem> task:pendingVodChecks)if(!task.isDone())task.cancel(true);
        for(Future<ArrayList<VodItem>> task:sourceFutures)if(!task.isDone())task.cancel(true);
        // Fixed thread pools retain cancelled FutureTasks in their work queues
        // until dequeued. Purge them immediately so rapid category changes cannot
        // build a backlog of dead requests on low-memory Android TV devices.
        purgeCancelledTasks(vodMetadataWorker);
        purgeCancelledTasks(vodSourceWorker);
      }

      final String err=error;
      final ArrayList<VodItem> result=new ArrayList<>(found);
      uiHandler.post(()->{
        if(isFinishing()||request!=vodRequestId)return;
        if(!result.isEmpty()){
          writeVodCache(cacheKey,result,true);
          renderVodItems(category,result,status,results,false);
          status.setText("「"+category+"」 · 多目录聚合 "+result.size()+" 部（含公开馆藏；首次播放会验证链接）");
        }else if(cached.isEmpty()){
          status.setText(err==null?"暂时没有检索到匹配的影片资料或可验证直播放源。可换关键词或稍后重试。":"点播目录暂时无法连接："+err);
        }else{
          status.setText("显示上次缓存的 "+cached.size()+" 部影片；本次多源检索暂不可用。");
        }
      });
    });
  }
  // These three existing serial request workers are independently owned.
  // Unlike Executors.newSingleThreadExecutor's wrapper, the concrete executor
  // allows cancelled queued FutureTasks to be removed before submitting again.
  private static ExecutorService newLatestRequestWorker(){
    return new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new java.util.concurrent.LinkedBlockingQueue<Runnable>());
  }
  private void purgeCancelledTasks(ExecutorService executor){
    if(executor instanceof ThreadPoolExecutor)((ThreadPoolExecutor)executor).purge();
  }
  private void cancelActiveVodCatalogue(){
    // Invalidate already-posted UI batches before stopping the network work.
    ++vodRequestId;
    Future<?> pending=activeVodCatalogueTask;
    if(pending!=null)pending.cancel(true);
    activeVodCatalogueTask=null;
    purgeCancelledTasks(vodWorker);
    purgeCancelledTasks(vodMetadataWorker);
    purgeCancelledTasks(vodSourceWorker);
  }
  private String httpGetFast(String address)throws Exception{
    // Future.cancel(true) interrupts catalogue workers. Check that signal before
    // opening a socket and between reads so stale searches release resources
    // promptly instead of continuing to parse responses no longer requested.
    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("点播检索已取消");
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setConnectTimeout(2500);c.setReadTimeout(3500);c.setInstanceFollowRedirects(true);c.setUseCaches(false);
    c.setRequestProperty("User-Agent","MyTV/2.0 Android TV");
    c.setRequestProperty("Accept","application/json,*/*");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n,total=0;final int maxBytes=2*1024*1024;
      while(true){
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("点播检索已取消");
        n=in.read(buf);
        if(n==-1)break;
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("点播检索已取消");
        total+=n;if(total>maxBytes)throw new IOException("点播目录响应超过2MB，已停止读取以保护设备内存");
        out.write(buf,0,n);
      }
      if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("点播检索已取消");
      return out.toString("UTF-8");
    }finally{c.disconnect();}
  }
  private Bitmap createVodFallbackPoster(String title,String identifier){
    int w=360,h=240;Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565);Canvas c=new Canvas(b);
    int seed=identifier==null?0:identifier.hashCode();int[] colors={
      Color.rgb(20,55,91),Color.rgb(72,42,84),Color.rgb(25,78,75),Color.rgb(86,54,32),Color.rgb(38,48,88)};
    int base=colors[SourceSelectionPolicy.wrapIndex(seed,colors.length)];
    Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    c.drawColor(Color.rgb(14,25,42));
    p.setShader(new android.graphics.LinearGradient(0,0,w,h,base,Color.rgb(12,20,34),android.graphics.Shader.TileMode.CLAMP));
    c.drawRect(0,0,w,h,p);p.setShader(null);
    p.setColor(Color.argb(38,255,255,255));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
    for(int x=14;x<w;x+=24){c.drawRoundRect(x,12,x+9,h-12,3,3,p);}
    p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(85,255,255,255));c.drawCircle(w-48,48,25,p);
    p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD));
    p.setTextSize(20);c.drawText("MY TV",w/2f,h*0.38f,p);
    String text=android.text.TextUtils.isEmpty(title)?"暂无海报":title.trim();
    p.setTextSize(text.length()>15?19:25);p.setColor(Color.rgb(255,238,190));
    if(text.length()>18)text=text.substring(0,18)+"…";
    c.drawText(text,w/2f,h*0.66f,p);
    p.setTextSize(13);p.setColor(Color.rgb(190,210,230));c.drawText("影片封面",w/2f,h*0.84f,p);
    return b;
  }
  private String fetchCommonsPosterUrl(String identifier){
    HttpURLConnection c=null;
    try{
      String title=identifier.substring(identifier.indexOf(':')+1);
      String api="https://commons.wikimedia.org/w/api.php?action=query&titles="+URLEncoder.encode(title,"UTF-8")+
        "&prop=pageimages&piprop=thumbnail&pithumbsize=800&format=json";
      c=(HttpURLConnection)new URL(api).openConnection();c.setConnectTimeout(2200);c.setReadTimeout(2800);
      c.setRequestProperty("User-Agent","MyTV/1.81 (Android TV)");
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buf=new byte[4096];int n,total=0;
        while((n=in.read(buf))!=-1){
          if(Thread.currentThread().isInterrupted()||n>1024*1024-total)return "";
          out.write(buf,0,n);total+=n;
        }
        org.json.JSONObject root=new org.json.JSONObject(out.toString("UTF-8"));
        org.json.JSONObject pages=root.optJSONObject("query")==null?null:root.optJSONObject("query").optJSONObject("pages");
        if(pages!=null){java.util.Iterator<String> it=pages.keys();while(it.hasNext()){
          org.json.JSONObject page=pages.optJSONObject(it.next());if(page==null)continue;
          org.json.JSONObject thumb=page.optJSONObject("thumbnail");if(thumb!=null){String u=thumb.optString("source","");if(u.startsWith("https://"))return u;}
        }}
      }
    }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
    return "";
  }
  private void invalidateVodPosterSession(){
    ++vodPosterSessionGeneration;
    vodPosterRequests.clear();
    // This bounded pool is dedicated to posters; only stale queued tasks exist
    // at this boundary. Running reads use the existing session/time limits.
    if(posterWorker instanceof ThreadPoolExecutor)((ThreadPoolExecutor)posterWorker).getQueue().clear();
  }
  private void loadVodPoster(ImageView target,String identifier,String title){
    loadVodPoster(target,identifier,title,"");
  }
  private void loadVodPoster(ImageView target,String identifier,String title,String suppliedPosterUrl){
    boolean sameBinding=identifier.equals(target.getTag());
    target.setTag(identifier);
    Bitmap cached=vodPosterCache.get(identifier);
    if(cached!=null){target.setImageBitmap(cached);return;}
    // Keep the existing placeholder for repeated binds of the same cell.
    if(!sameBinding||target.getDrawable()==null)target.setImageBitmap(createVodFallbackPoster(title,identifier));
    final long posterSession=vodPosterSessionGeneration;
    final String requestKey=posterSession+":"+identifier;
    ArrayList<java.lang.ref.WeakReference<ImageView>> waiting=vodPosterRequests.get(requestKey);
    if(waiting!=null){
      boolean registered=false;
      for(int i=waiting.size()-1;i>=0;i--){
        ImageView view=waiting.get(i).get();
        if(view==null||!identifier.equals(view.getTag()))waiting.remove(i);
        else if(view==target)registered=true;
      }
      if(!registered)waiting.add(new java.lang.ref.WeakReference<>(target));
      return;
    }
    waiting=new ArrayList<>();
    waiting.add(new java.lang.ref.WeakReference<>(target));
    vodPosterRequests.put(requestKey,waiting);
    try{posterWorker.execute(()->{
      Bitmap bitmap=null;HttpURLConnection connection=null;
      try{
        if(posterSession!=vodPosterSessionGeneration||Thread.currentThread().isInterrupted())return;
        String imageUrl=suppliedPosterUrl==null?"":suppliedPosterUrl;
        if(imageUrl.isEmpty()&&(identifier.startsWith("commons:")||identifier.startsWith("commons-search:")))imageUrl=fetchCommonsPosterUrl(identifier);
        else if(imageUrl.isEmpty()&&!identifier.startsWith("loc:")&&!identifier.startsWith("cctv:")&&!identifier.startsWith("meta:"))
          imageUrl="https://archive.org/services/img/"+URLEncoder.encode(identifier,"UTF-8");
        if(posterSession!=vodPosterSessionGeneration||Thread.currentThread().isInterrupted())return;
        if(!imageUrl.isEmpty()&&imageUrl.startsWith("https://")){
          connection=(HttpURLConnection)new URL(imageUrl).openConnection();
          connection.setConnectTimeout(2500);connection.setReadTimeout(3500);connection.setInstanceFollowRedirects(true);
          connection.setRequestProperty("User-Agent","MyTV/1.81 Android TV");
          // Posters are displayed in a small TV card. Decode near display size
          // instead of allocating a full-resolution bitmap for every thumbnail.
          try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n,total=0;final int maxImageBytes=4*1024*1024;
            while((n=in.read(buffer))!=-1&&total<maxImageBytes){
              if(posterSession!=vodPosterSessionGeneration||Thread.currentThread().isInterrupted())return;
              int take=Math.min(n,maxImageBytes-total);out.write(buffer,0,take);total+=take;
              if(total>=maxImageBytes)break;
            }
            byte[] imageBytes=out.toByteArray();
            if(imageBytes.length>0){
              BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
              BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.length,bounds);
              if(bounds.outWidth>0&&bounds.outHeight>0){
                int sample=1;
                while(bounds.outWidth/sample>640||bounds.outHeight/sample>480)sample*=2;
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
                bitmap=BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.length,options);
              }
            }
          }
        }
      }catch(Exception ignored){}finally{
        if(connection!=null)connection.disconnect();
        final Bitmap image=bitmap;
        // Registration and delivery share the UI looper. Remove subscribers only
        // when delivering, so a bind racing the network result cannot be lost.
        uiHandler.post(()->{
          ArrayList<java.lang.ref.WeakReference<ImageView>> subscribers=vodPosterRequests.remove(requestKey);
          if(image==null||posterSession!=vodPosterSessionGeneration||isFinishing()||isDestroyed())return;
          vodPosterCache.put(identifier,image);
          if(subscribers==null)return;
          for(java.lang.ref.WeakReference<ImageView> ref:subscribers){
            ImageView view=ref.get();
            if(view!=null&&identifier.equals(view.getTag()))view.setImageBitmap(image);
          }
        });
      }
    });}catch(RejectedExecutionException ignored){vodPosterRequests.remove(requestKey);}
  }
  private String encodeArchivePath(String path){
    try{String[] parts=path.split("/");StringBuilder out=new StringBuilder();for(String p:parts){if(out.length()>0)out.append("/");out.append(URLEncoder.encode(p,"UTF-8").replace("+","%20"));}return out.toString();}catch(Exception e){return path;}
  }
  private void playVodInside(VodItem item){
    if(isChildLockCurrentlyActive()){enforceChildLock();return;}
    if(isRemotePlaybackLockActive())return;
    long resumeAt=0;for(org.json.JSONObject o:readVodHistory())if(item.identifier.equals(o.optString("id",""))){resumeAt=o.optLong("position",0);break;}
    final long savedPosition=resumeAt;
    if(savedPosition>10000){new AlertDialog.Builder(this).setTitle("继续观看？").setMessage("《"+item.title+"》上次看到 "+formatVodTime(savedPosition)+"，要从上次位置继续吗？").setPositiveButton("继续观看",(x,y)->startVodPlayback(item,savedPosition)).setNegativeButton("从头播放",(x,y)->startVodPlayback(item,0)).setNeutralButton("取消",null).show();return;}
    startVodPlayback(item,0);
  }
  private VodItem findNextVodItem(VodItem current){
    if(current==null||currentVodResults==null)return null;
    for(int i=0;i<currentVodResults.getChildCount();i++){
      View child=currentVodResults.getChildAt(i);
      if(child instanceof GridView){
        android.widget.ListAdapter adapter=((GridView)child).getAdapter();
        if(adapter==null)return null;
        for(int p=0;p<adapter.getCount();p++){
          Object value=adapter.getItem(p);
          if(value instanceof VodItem&&current.identifier.equals(((VodItem)value).identifier))
            return p+1<adapter.getCount()&&adapter.getItem(p+1) instanceof VodItem?(VodItem)adapter.getItem(p+1):null;
        }
      }
    }
    return null;
  }
  private void startVodPlayback(VodItem item,long startPosition){
    if(isChildLockCurrentlyActive()){enforceChildLock();return;}
    if(isRemotePlaybackLockActive())return;
    if(currentVodPanel==null||vodCatalogueDialog==null||!vodCatalogueDialog.isShowing()
        ||!activityStarted||isFinishing()||isDestroyed())return;
    revokePendingVodStart();
    final long request=vodStartRequestGeneration;
    if(decoderReleaseUnconfirmed){toast("播放器资源未确认释放，请强制停止应用后重开");return;}
    // A cached catalogue may be clicked before the deferred live teardown.
    // Complete the handoff on the Media3 application looper; the existing VLC
    // barrier below retains the latest valid VOD request until native release.
    if(player!=null)releaseLivePlayerSafely();
    if(libVlcEngine!=null)releaseLibVlcEngine();
    if(decoderReleaseUnconfirmed){toast("播放器资源未确认释放，请强制停止应用后重开");return;}
    if(player!=null||libVlcEngine!=null)return;
    if(vodPlayerDialog!=null){
      // Dialog dismissal posts its listener; do not build a new player before
      // the old listener finishes clearing its fields and releasing ownership.
      final Dialog previous=vodPlayerDialog;
      final LinearLayout expectedPanel=currentVodPanel;
      if(previous.isShowing())previous.dismiss();
      uiHandler.post(()->{
        if(request==vodStartRequestGeneration&&activityStarted&&vodPlayerDialog==null
            &&currentVodPanel==expectedPanel&&!isAnyPlaybackLockActive())
          startVodPlayback(item,startPosition);
      });
      return;
    }
    if(libVlcReleaseInProgress){
      final long expectedSession=vodSessionGeneration;
      final LinearLayout expectedPanel=currentVodPanel;
      if(pendingVodStartAfterRelease!=null)libVlcReleaseCallbacks.remove(pendingVodStartAfterRelease);
      pendingVodStartAfterRelease=()->{
        pendingVodStartAfterRelease=null;
        if(request==vodStartRequestGeneration&&activityStarted
            &&expectedSession==vodSessionGeneration&&currentVodPanel==expectedPanel
            &&!isAnyPlaybackLockActive())startVodPlayback(item,startPosition);
      };
      libVlcReleaseCallbacks.add(pendingVodStartAfterRelease);
      return;
    }
    if(PROCESS_VLC_TEARDOWNS.get()>0){toast("旧播放器仍在释放，请稍后重试");return;}
    // Both live engines were already revoked above. Do not issue redundant
    // native pause commands from the UI during the ownership handoff.
    releaseVodFallback();
    if(libVlcReleaseInProgress||decoderReleaseUnconfirmed||PROCESS_VLC_TEARDOWNS.get()>0)return;
    vodSessionGeneration++;vodFallbackAttempted=false;vodHistoryEnded=false;vodHistoryPendingResumeMs=Math.max(0L,startPosition);
    activeVodItem=item;saveVodHistory(item,startPosition,0);
    Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);vodPlayerDialog=d;
    final SeekBar[] vodSeekHolder=new SeekBar[1];
    final LinearLayout[] vodControlsHolder=new LinearLayout[1];
    final Runnable[] hideVodSeekTask=new Runnable[1];
    final View[] vodTopControls=new View[4];
    final View[] vodTerminalPanel={null};
    final Runnable hideVodControls=()->{
      if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);
      if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);
      for(View top:vodTopControls)if(top!=null)top.setVisibility(View.GONE);
    };
    FrameLayout stage=new FrameLayout(this){
      private boolean terminalPanelVisible(){
        return vodTerminalPanel[0]!=null&&vodTerminalPanel[0].getVisibility()==View.VISIBLE;
      }
      private void revealVodSeekBar(){
        if(terminalPanelVisible())return;
        boolean controlsWereHidden=vodControlsHolder[0]!=null&&vodControlsHolder[0].getVisibility()!=View.VISIBLE;
        if(vodControlsHolder[0]!=null){vodControlsHolder[0].setVisibility(View.VISIBLE);vodControlsHolder[0].bringToFront();}
        if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.VISIBLE);
        for(View top:vodTopControls)if(top!=null)top.setVisibility(View.VISIBLE);
        if(hideVodSeekTask[0]!=null){
          removeCallbacks(hideVodSeekTask[0]);
          postDelayed(hideVodSeekTask[0],3500);
        }
        if(controlsWereHidden&&vodSeekHolder[0]!=null)vodSeekHolder[0].requestFocus();
      }
      private final int[] topFocusOrder={3,1,2};
      @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(terminalPanelVisible())return super.dispatchKeyEvent(event);
        if(event.getKeyCode()==KeyEvent.KEYCODE_MENU||event.getKeyCode()==KeyEvent.KEYCODE_SETTINGS){
          if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
            revealVodControlsAction=()->revealVodSeekBar();
            showVodPlaybackActions();
          }
          return true;
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
          View focused=findFocus();
          int topPosition=-1;
          for(int i=0;i<topFocusOrder.length;i++){
            View top=vodTopControls[topFocusOrder[i]];
            if(top!=null&&top==focused){topPosition=i;break;}
          }
          if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP&&focused==vodSeekHolder[0]&&vodTopControls[3]!=null){
            revealVodSeekBar();vodTopControls[3].requestFocus();return true;
          }
          if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN&&topPosition>=0&&vodSeekHolder[0]!=null){
            revealVodSeekBar();vodSeekHolder[0].requestFocus();return true;
          }
          if(topPosition>=0&&(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT)){
            int next=topPosition+(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);
            if(next>=0&&next<topFocusOrder.length){
              View target=vodTopControls[topFocusOrder[next]];
              if(target!=null){revealVodSeekBar();target.requestFocus();return true;}
            }
          }
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN)revealVodSeekBar();
        return super.dispatchKeyEvent(event);
      }
      private float videoTouchX,videoTouchY;
      private boolean videoTapCandidate=false,ownsVodVideoGesture=false;
      private void hideVodControlsNow(){
        if(hideVodSeekTask[0]!=null)removeCallbacks(hideVodSeekTask[0]);
        hideVodControls.run();
      }
      private void toggleVodControls(){
        if(terminalPanelVisible())return;
        boolean visible=vodControlsHolder[0]!=null&&vodControlsHolder[0].getVisibility()==View.VISIBLE;
        if(visible)hideVodControlsNow();else revealVodSeekBar();
      }
      private boolean isMouseEvent(android.view.MotionEvent event){
        return event.getPointerCount()>0&&event.getToolType(0)==android.view.MotionEvent.TOOL_TYPE_MOUSE;
      }
      @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event){
        int action=event.getActionMasked();
        if(isMouseEvent(event)&&(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE)){
          revealVodSeekBar();
        }
        return super.dispatchGenericMotionEvent(event);
      }
      @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        int action=event.getActionMasked();
        boolean mouse=isMouseEvent(event);
        if(action==android.view.MotionEvent.ACTION_DOWN){
          ownsVodVideoGesture=false;videoTapCandidate=false;
          // Terminal actions own their complete DOWN/UP sequence. Do not
          // intercept a tap merely because the normal transport bar is hidden.
          if(terminalPanelVisible())return super.dispatchTouchEvent(event);
          boolean controlsHidden=vodControlsHolder[0]==null||vodControlsHolder[0].getVisibility()!=View.VISIBLE;
          if(mouse&&controlsHidden){
            // Mouse hover normally reveals controls before click; keep mouse hit-testing native.
            revealVodSeekBar();
            videoTapCandidate=false;
          }else{
            float y=event.getY();
            videoTapCandidate=!mouse&&controlsHidden;
            if(!mouse&&!controlsHidden)videoTapCandidate=(y>dp(56)&&y<getHeight()-dp(90));
          }
          videoTouchX=event.getX();videoTouchY=event.getY();
          // Intercept the whole first finger tap while controls are hidden.
          // Otherwise PlayerView can own ACTION_DOWN and the stage never receives ACTION_UP.
          ownsVodVideoGesture=videoTapCandidate;
        }
        // Never forward MOVE or POINTER events to a child that did not receive
        // DOWN. Reject multi-touch and drags without losing gesture ownership.
        if(ownsVodVideoGesture){
          if(action==MotionEvent.ACTION_POINTER_DOWN||event.getPointerCount()!=1
              ||Math.abs(event.getX()-videoTouchX)>=dp(28)
              ||Math.abs(event.getY()-videoTouchY)>=dp(28))videoTapCandidate=false;
          if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            boolean toggle=action==MotionEvent.ACTION_UP&&videoTapCandidate;
            ownsVodVideoGesture=false;videoTapCandidate=false;
            if(toggle&&!terminalPanelVisible())toggleVodControls();
          }
          return true;
        }
        return super.dispatchTouchEvent(event);
      }
    };stage.setBackgroundColor(Color.BLACK);stage.setFocusable(true);stage.setFocusableInTouchMode(true);stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    PlayerView pv=new PlayerView(this);pv.setUseController(false);pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);pv.setFocusable(false);pv.setFocusableInTouchMode(false);int vodAspect=getSharedPreferences("mytv",0).getInt("vod_aspect_mode",0);pv.setResizeMode(vodAspect==1?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM:vodAspect==2?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL:androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
    stage.addView(pv,new FrameLayout.LayoutParams(-1,-1));
    VLCVideoLayout vodVlcView=new VLCVideoLayout(this);vodVlcVideoLayout=vodVlcView;vodVlcView.setVisibility(View.GONE);stage.addView(vodVlcView,new FrameLayout.LayoutParams(-1,-1));
    // End-of-film overlay: keep the last video frame visible and offer clear
    // remote-friendly actions instead of leaving the viewer on a black screen.
    LinearLayout endedPanel=new LinearLayout(this);vodTerminalPanel[0]=endedPanel;endedPanel.setOrientation(1);endedPanel.setGravity(Gravity.CENTER);
    endedPanel.setPadding(dp(24),dp(20),dp(24),dp(20));endedPanel.setBackgroundColor(0xDD101820);endedPanel.setVisibility(View.GONE);
    TextView endedTitle=new TextView(this);endedTitle.setText("播放完毕");endedTitle.setTextColor(Color.WHITE);endedTitle.setTextSize(24);endedTitle.setGravity(Gravity.CENTER);
    TextView endedSubtitle=new TextView(this);endedSubtitle.setText("《"+item.title+"》已播放结束");endedSubtitle.setTextColor(0xFFD4DFE8);endedSubtitle.setTextSize(15);endedSubtitle.setGravity(Gravity.CENTER);endedSubtitle.setPadding(0,dp(8),0,dp(18));
    LinearLayout endedActions=new LinearLayout(this);endedActions.setOrientation(0);endedActions.setGravity(Gravity.CENTER);
    Button replayEnded=new Button(this);replayEnded.setText("重新播放");replayEnded.setAllCaps(false);
    Button nextEnded=new Button(this);nextEnded.setText("下一部");nextEnded.setAllCaps(false);
    Button backEnded=new Button(this);backEnded.setText("返回影片列表");backEnded.setAllCaps(false);
    for(Button action:new Button[]{replayEnded,nextEnded,backEnded}){action.setFocusable(true);action.setTextSize(14);action.setPadding(dp(8),0,dp(8),0);endedActions.addView(action,new LinearLayout.LayoutParams(-2,dp(48)));}
    endedPanel.addView(endedTitle,new LinearLayout.LayoutParams(-1,-2));endedPanel.addView(endedSubtitle,new LinearLayout.LayoutParams(-1,-2));endedPanel.addView(endedActions,new LinearLayout.LayoutParams(-1,-2));
    FrameLayout.LayoutParams endedParams=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);endedParams.leftMargin=dp(24);endedParams.rightMargin=dp(24);stage.addView(endedPanel,endedParams);
    replayEnded.setOnClickListener(v->{if(vodPlayerDialog!=d||!d.isShowing()||!activityStarted||isAnyPlaybackLockActive())return;startVodPlayback(item,0);});
    nextEnded.setOnClickListener(v->{if(vodPlayerDialog!=d||!d.isShowing()||!activityStarted||isAnyPlaybackLockActive())return;VodItem next=findNextVodItem(item);if(next==null){toast("当前列表没有下一部影片");return;}startVodPlayback(next,0);});
    backEnded.setOnClickListener(v->d.dismiss());
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(10),dp(4),dp(10),dp(4));controls.setBackgroundColor(0xDD101820);
    TextView playPauseIndicator=new TextView(this);playPauseIndicator.setText("Ⅱ");playPauseIndicator.setTextColor(Color.WHITE);playPauseIndicator.setTextSize(15);playPauseIndicator.setGravity(Gravity.CENTER);playPauseIndicator.setPadding(0,0,0,0);android.graphics.drawable.GradientDrawable playPauseCircle=new android.graphics.drawable.GradientDrawable();playPauseCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);playPauseCircle.setColor(0xCC263746);playPauseCircle.setStroke(dp(1),0xFF8FA7BA);playPauseIndicator.setBackground(playPauseCircle);playPauseIndicator.setFocusable(false);playPauseIndicator.setClickable(true);Runnable toggleVodPlayback=()->{if(vodIsPlaying())vodPause();else vodResume();playPauseIndicator.setText(vodIsPlaying()?"Ⅱ":"▶");};
    playPauseIndicator.setOnClickListener(v->toggleVodPlayback.run());
    vodPositionLabel=new TextView(this);vodPositionLabel.setText("00:00");vodPositionLabel.setTextColor(Color.WHITE);vodPositionLabel.setTextSize(12);vodPositionLabel.setGravity(Gravity.CENTER);vodPositionLabel.setPadding(dp(6),0,dp(6),0);
    vodSeekBar=new SeekBar(this){
      private int remoteDirection=0;
      private Runnable remoteRepeat;
      private void seekBy(long delta){
        long duration=vodDurationMs();
        if(duration<=0)return;
        long target=VodTimelinePolicy.addAndClamp(vodPositionMs(),delta,duration);
        if(!vodSeekTo(target))return;
        setProgress(VodTimelinePolicy.toProgress(target,duration));
        if(vodPositionLabel!=null)vodPositionLabel.setText(formatVodTime(target));
      }
      @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER||keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER){if(event.getRepeatCount()==0)toggleVodPlayback.run();return true;}
        int direction=keyCode==KeyEvent.KEYCODE_DPAD_LEFT?-1:keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:0;
        if(direction==0)return super.onKeyDown(keyCode,event);
        if(event.getRepeatCount()==0){
          remoteDirection=direction;
          seekBy(direction*5000L);
          if(remoteRepeat!=null)removeCallbacks(remoteRepeat);
          remoteRepeat=()->{
            if(remoteDirection!=0&&hasFocus()&&isAttachedToWindow()
                &&vodPlayerDialog==d&&activeVodItem==item&&d.isShowing()
                &&endedPanel.getVisibility()!=View.VISIBLE){
              seekBy(remoteDirection*15000L);
              postDelayed(remoteRepeat,220L);
            }
          };
          postDelayed(remoteRepeat,450L);
        }
        return true;
      }
      private void stopRemoteSeekRepeat(){
        remoteDirection=0;
        if(remoteRepeat!=null){removeCallbacks(remoteRepeat);remoteRepeat=null;}
      }
      @Override protected void onFocusChanged(boolean gainFocus,int direction,android.graphics.Rect previous){
        if(!gainFocus)stopRemoteSeekRepeat();
        super.onFocusChanged(gainFocus,direction,previous);
      }
      @Override protected void onDetachedFromWindow(){
        stopRemoteSeekRepeat();
        super.onDetachedFromWindow();
      }
      @Override public boolean onKeyUp(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){
          stopRemoteSeekRepeat();
          return true;
        }
        return super.onKeyUp(keyCode,event);
      }
    };vodSeekBar.setMax(1000);vodSeekBar.setFocusable(true);vodSeekBar.setFocusableInTouchMode(true);
    vodDurationLabel=new TextView(this);vodDurationLabel.setText("--:--");vodDurationLabel.setTextColor(Color.WHITE);vodDurationLabel.setTextSize(12);vodDurationLabel.setGravity(Gravity.CENTER);vodDurationLabel.setPadding(dp(6),0,dp(6),0);
    LinearLayout seekContainer=new LinearLayout(this);seekContainer.setOrientation(LinearLayout.HORIZONTAL);seekContainer.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams playPauseCircleParams=new LinearLayout.LayoutParams(dp(36),dp(36));playPauseCircleParams.rightMargin=dp(8);seekContainer.addView(playPauseIndicator,playPauseCircleParams);seekContainer.addView(vodSeekBar,new LinearLayout.LayoutParams(0,dp(48),1));
    controls.addView(vodPositionLabel,new LinearLayout.LayoutParams(dp(52),-2));
    controls.addView(seekContainer,new LinearLayout.LayoutParams(0,dp(48),1));
    controls.addView(vodDurationLabel,new LinearLayout.LayoutParams(dp(52),-2));
    vodControlsHolder[0]=controls;
    FrameLayout.LayoutParams controlsParams=new FrameLayout.LayoutParams(-1,dp(62),Gravity.BOTTOM);controlsParams.setMargins(dp(12),0,dp(12),dp(12));stage.addView(controls,controlsParams);vodSeekHolder[0]=vodSeekBar;
    hideVodSeekTask[0]=()->{hideVodControls.run();stage.requestFocus();};
    stage.postDelayed(hideVodSeekTask[0],2500);
    Runnable showVodTerminalPanel=()->{
      endedPanel.setVisibility(View.VISIBLE);endedPanel.bringToFront();
      if(hideVodSeekTask[0]!=null)stage.removeCallbacks(hideVodSeekTask[0]);
      hideVodControls.run();
      replayEnded.requestFocus();
    };
    Runnable showVodFallbackEnded=()->{markVodHistoryEnded(item,vodDurationMs());showVodTerminalPanel.run();};
    Runnable showVodFallbackFailure=()->{if(vodPlayerDialog!=d||activeVodItem!=item||vodHistoryEnded)return;stopVodTerminalTimers();endedTitle.setText("暂时无法播放");endedSubtitle.setText("备用播放器也无法播放《"+item.title+"》。可以重新尝试，或返回影片列表。");replayEnded.setText("重新尝试");showVodTerminalPanel.run();};
    vodSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){long duration=vodDurationMs();if(duration>0)vodSeekTo(VodTimelinePolicy.fromProgress(bar.getProgress(),duration));}
      public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(!fromUser)return;long duration=vodDurationMs();if(duration>0)vodPositionLabel.setText(formatVodTime(VodTimelinePolicy.fromProgress(progress,duration)));}
    });
    vodUiUpdater=new Runnable(){public void run(){
      if(vodUiUpdater!=this||!activityStarted||vodHistoryEnded||(vodPlayer==null&&vodFallbackBackend==null)||vodPlayerDialog!=d||!d.isShowing())return;
      long duration=vodDurationMs(),position=vodPositionMs();
      if(duration>0){vodDurationLabel.setText(formatVodTime(duration));if(!vodSeekBar.isPressed())vodSeekBar.setProgress(VodTimelinePolicy.toProgress(position,duration));}else{vodDurationLabel.setText("--:--");vodSeekBar.setProgress(0);}
      if(!vodSeekBar.isPressed())vodPositionLabel.setText(formatVodTime(position));
      playPauseIndicator.setText(vodIsPlaying()?"Ⅱ":"▶");
      uiHandler.postDelayed(this,500);
    }};
    TextView heading=new TextView(this);heading.setText(item.title+" · 开放授权影片");heading.setTextColor(Color.WHITE);heading.setTextSize(15);heading.setPadding(dp(14),dp(10),dp(14),dp(10));heading.setBackgroundColor(0x99000000);
    FrameLayout.LayoutParams headingParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);stage.addView(heading,headingParams);vodTopControls[0]=heading;
    Button favorite=new Button(this);favorite.setText(isVodFavorite(item)?"★ 已收藏":"☆ 收藏");favorite.setAllCaps(false);favorite.setTextSize(14);favorite.setFocusable(true);favorite.setFocusableInTouchMode(true);styleVodTopControl(favorite);favorite.setOnClickListener(v->{toggleVodFavorite(item);favorite.setText(isVodFavorite(item)?"★ 已收藏":"☆ 收藏");toast(isVodFavorite(item)?"已加入我的收藏":"已取消收藏");});FrameLayout.LayoutParams favoriteParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);favoriteParams.topMargin=dp(4);favoriteParams.rightMargin=dp(116);stage.addView(favorite,favoriteParams);vodTopControls[1]=favorite;
    Button back=new Button(this);back.setText("返回目录");back.setAllCaps(false);back.setTextSize(14);back.setFocusable(true);back.setFocusableInTouchMode(true);styleVodTopControl(back);back.setOnClickListener(v->d.dismiss());FrameLayout.LayoutParams backParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);backParams.topMargin=dp(4);backParams.rightMargin=dp(8);stage.addView(back,backParams);vodTopControls[2]=back;
     Button aspect=new Button(this);aspect.setText("画面比例");aspect.setAllCaps(false);aspect.setTextSize(14);aspect.setFocusable(true);aspect.setFocusableInTouchMode(true);styleVodTopControl(aspect);aspect.setOnClickListener(v->showVodAspectRatioDialog(pv,"vod_aspect_mode"));FrameLayout.LayoutParams aspectParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);aspectParams.topMargin=dp(4);aspectParams.rightMargin=dp(220);stage.addView(aspect,aspectParams);vodTopControls[3]=aspect;
    d.setOnKeyListener((dialog,keyCode,event)->{if(keyCode==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP){d.dismiss();}return true;}return false;});
     d.setContentView(stage);stage.post(()->normalizeFrameLayoutChildren(stage));
    // The old dialog may deliver its dismissal after a failed start or a new session.
    // Only the current owner may clear shared timers and decoder references.
    Runnable cleanupVodSession=()->{
      if(hideVodSeekTask[0]!=null)stage.removeCallbacks(hideVodSeekTask[0]);
      vodSeekHolder[0]=null;vodControlsHolder[0]=null;
      if(vodPlayerDialog!=d)return;
      stopVodTerminalTimers();vodSeekBar=null;vodPositionLabel=null;vodDurationLabel=null;checkpointVodHistory();releaseVodFallback();releaseVodPlayerSafely(pv);
      // Do not resume live playback here: the VOD catalogue dialog is still
      // visible. Its own dismiss handler restores the channel after the whole
      // nested VOD session has closed.
      vodSessionGeneration++;activeVodItem=null;getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(vodPlayerDialog==d)vodPlayerDialog=null;if(backVodToCatalogueAction!=null)backVodToCatalogueAction=null;if(revealVodControlsAction!=null)revealVodControlsAction=null;
    };
    d.setOnDismissListener(x->cleanupVodSession.run());
    try{
      recordDecoderInventoryOnce();
      if(!acquireDecoderOwnership())throw new IllegalStateException("旧播放器仍在使用资源");
      vodPlayer=PlaybackPlayerFactory.createVodPlayer(this,isDecoderFallbackEnabled());
      final ExoPlayer vodListenerOwner=vodPlayer;
      final boolean[] vodHasVideoTrack={false},vodRenderedFirstFrame={false};
      final long[] vodReadySince={0L},vodLastAdvanceAt={0L},vodLastPosition={0L};
      vodPlayer.addAnalyticsListener(new AnalyticsListener(){
        private boolean isCurrentVod(){return vodListenerOwner==MainActivity.this.vodPlayer;}
        @Override public void onVideoDecoderInitialized(AnalyticsListener.EventTime eventTime,String decoderName,long initializedTimestampMs,long initializationDurationMs){
          if(isCurrentVod())saveLiveDiagnostic("VOD_VIDEO_DECODER","name="+safeDiagnosticDetail(decoderName)+" initMs="+initializationDurationMs);
        }
        @Override public void onAudioDecoderInitialized(AnalyticsListener.EventTime eventTime,String decoderName,long initializedTimestampMs,long initializationDurationMs){
          if(isCurrentVod())saveLiveDiagnostic("VOD_AUDIO_DECODER","name="+safeDiagnosticDetail(decoderName)+" initMs="+initializationDurationMs);
        }
        @Override public void onVideoInputFormatChanged(AnalyticsListener.EventTime eventTime,Format format,androidx.media3.exoplayer.DecoderReuseEvaluation evaluation){
          if(isCurrentVod()&&format!=null){vodHasVideoTrack[0]=true;saveLiveDiagnostic("VOD_VIDEO_FORMAT","mime="+format.sampleMimeType+" codecs="+safeDiagnosticDetail(format.codecs)+" size="+format.width+"x"+format.height+" fps="+format.frameRate);}
        }
        @Override public void onAudioInputFormatChanged(AnalyticsListener.EventTime eventTime,Format format,androidx.media3.exoplayer.DecoderReuseEvaluation evaluation){
          if(isCurrentVod()&&format!=null)saveLiveDiagnostic("VOD_AUDIO_FORMAT","mime="+format.sampleMimeType+" codecs="+safeDiagnosticDetail(format.codecs)+" sampleRate="+format.sampleRate+" channels="+format.channelCount);
        }
        @Override public void onRenderedFirstFrame(AnalyticsListener.EventTime eventTime,Object output,long renderTimeMs){
          if(isCurrentVod()){vodRenderedFirstFrame[0]=true;vodLastAdvanceAt[0]=android.os.SystemClock.elapsedRealtime();vodLastPosition[0]=Math.max(0L,vodListenerOwner.getCurrentPosition());saveLiveDiagnostic("VOD_FIRST_FRAME","renderTimeMs="+renderTimeMs);}
        }
        private long lastDropLogAt;
        private long pendingDroppedFrames;
        @Override public void onDroppedVideoFrames(AnalyticsListener.EventTime eventTime,int droppedFrames,long elapsedMs){
          if(!isCurrentVod()||droppedFrames<=0)return;
          pendingDroppedFrames+=droppedFrames;
          long now=android.os.SystemClock.elapsedRealtime();
          if(lastDropLogAt!=0L&&now-lastDropLogAt<10000L)return;
          saveLiveDiagnostic("VOD_DROPPED_FRAMES","accumulatedCount="+pendingDroppedFrames+" latestWindowMs="+elapsedMs);
          pendingDroppedFrames=0L;
          lastDropLogAt=now;
        }
        @Override public void onPlayerError(AnalyticsListener.EventTime eventTime,PlaybackException error){
          if(isCurrentVod())saveLiveDiagnostic("VOD_PLAYER_ERROR","code="+error.errorCode+" name="+error.getErrorCodeName()+" cause="+(error.getCause()==null?"":error.getCause().getClass().getSimpleName()));
        }
      });
      pv.setPlayer(vodPlayer);pv.setKeepScreenOn(true);
       MediaItem media=PlaybackPlayerFactory.buildMediaItem(item.url,item.identifier);vodPlayer.setMediaItem(media);vodPlayer.prepare();if(startPosition>0)vodPlayer.seekTo(startPosition);vodPlayer.addListener(new Player.Listener(){
         private boolean isCurrent(){return activityStarted&&!vodHistoryEnded&&vodListenerOwner==MainActivity.this.vodPlayer&&vodPlayerDialog==d&&activeVodItem==item;}
         private void showEnded(){if(!isCurrent())return;markVodHistoryEnded(item,vodListenerOwner.getDuration());showVodTerminalPanel.run();}
         @Override public void onPlaybackStateChanged(int state){if(isCurrent()&&state==Player.STATE_ENDED)showEnded();}
         @Override public void onPlayerError(PlaybackException error){if(!isCurrent()||vodFallbackAttempted)return;
           if(PlaybackErrorPolicy.classify(error.getErrorCodeName(),extractHttpStatusCode(error.getCause())).skipSameUrlVlcRetry){
             vodFallbackAttempted=true;
             if(vodPlaybackWatchdog!=null){uiHandler.removeCallbacks(vodPlaybackWatchdog);vodPlaybackWatchdog=null;}
             checkpointVodHistory();
             releaseVodPlayerSafely(pv);
             saveLiveDiagnostic("VOD_NETWORK_ERROR_NO_ENGINE_RETRY",error.getErrorCodeName());
             showVodFallbackFailure.run();
             endedSubtitle.setText("影片地址暂时不可用，请重试或返回影片列表。");
             return;
           }
           long resume=vodListenerOwner.getCurrentPosition();if(resume<=0L)resume=startPosition;fallbackVodToLibVlc(d,item,pv,vodVlcView,resume,showVodFallbackEnded,showVodFallbackFailure);}
       });vodPlayer.play();if(!vodHistoryEnded&&vodPlayerDialog==d&&endedPanel.getVisibility()!=View.VISIBLE){vodProgressSaver=new Runnable(){@Override public void run(){if(vodProgressSaver==this&&activityStarted&&!vodHistoryEnded&&vodPlayerDialog==d&&activeVodItem==item&&d.isShowing()&&!isAnyPlaybackLockActive()&&(vodPlayer!=null||vodFallbackBackend!=null)){checkpointVodHistory();uiHandler.postDelayed(this,5000);}}};uiHandler.postDelayed(vodProgressSaver,5000);
      vodPlaybackWatchdog=new Runnable(){
        @Override public void run(){
          if(vodPlaybackWatchdog!=this||!activityStarted||vodHistoryEnded||vodListenerOwner!=vodPlayer||vodPlayerDialog!=d||activeVodItem!=item||!d.isShowing()||vodFallbackAttempted||vodFallbackBackend!=null)return;
          long now=android.os.SystemClock.elapsedRealtime();
          int state=vodListenerOwner.getPlaybackState();
          long position=Math.max(0L,vodListenerOwner.getCurrentPosition());
          if(state!=Player.STATE_READY||!vodListenerOwner.getPlayWhenReady()){
            vodReadySince[0]=0L;vodLastAdvanceAt[0]=now;vodLastPosition[0]=position;
          }else{
            if(vodReadySince[0]==0L){vodReadySince[0]=now;vodLastAdvanceAt[0]=now;vodLastPosition[0]=position;}
            if(vodHasVideoTrack[0]&&!vodRenderedFirstFrame[0]&&now-vodReadySince[0]>=25000L){
              saveLiveDiagnostic("VOD_WATCHDOG_NO_FIRST_FRAME","readyMs="+(now-vodReadySince[0]));
              fallbackVodToLibVlc(d,item,pv,vodVlcView,Math.max(position,startPosition),showVodFallbackEnded,showVodFallbackFailure);
              return;
            }
            if(vodRenderedFirstFrame[0]){
              if(position!=vodLastPosition[0]){vodLastPosition[0]=position;vodLastAdvanceAt[0]=now;}
              else if(now-vodLastAdvanceAt[0]>=20000L){
                saveLiveDiagnostic("VOD_WATCHDOG_STALLED","positionMs="+position+" stalledMs="+(now-vodLastAdvanceAt[0]));
                fallbackVodToLibVlc(d,item,pv,vodVlcView,Math.max(position,startPosition),showVodFallbackEnded,showVodFallbackFailure);
                return;
              }
            }
          }
          uiHandler.postDelayed(this,2000L);
        }
      };
      uiHandler.postDelayed(vodPlaybackWatchdog,2000L);}
      getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      d.show();d.getWindow().setLayout(-1,-1);stage.post(()->{if(vodPlayerDialog!=d||endedPanel.getVisibility()==View.VISIBLE||vodSeekBar==null)return;controls.setVisibility(View.VISIBLE);controls.bringToFront();vodSeekBar.requestFocus();});d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);d.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);if(vodUiUpdater!=null)uiHandler.post(vodUiUpdater);
    }catch(Exception|LinkageError e){
      cleanupVodSession.run();
      toast("影片无法播放："+e.getMessage());d.dismiss();
    }
  }

  // Official VOD navigation is isolated in its own UI module.
  private void openOfficialVodPlatforms(){
    OfficialMovieNavigation.show(this);
  }
  private void officialDialog(){
    String[] categories={"央视频道（CCTV）","省级卫视","各省市地方频道","港澳台频道","国内影视与节目"};
    new AlertDialog.Builder(this).setTitle("国内直播频道分类")
      .setItems(categories,(dialog,which)->{
        if(which==0) openOfficialList("央视频道",new String[]{"央视网直播","央视网节目官网"},new String[]{"https://tv.cctv.com/live/","https://cctv.cntv.cn/"});
        else if(which==1) openOfficialList("省级卫视",new String[]{"北京卫视","东方卫视","江苏卫视","浙江卫视","湖南卫视","广东卫视","深圳卫视","四川卫视"},new String[]{"https://www.btime.com/","https://www.kankanews.com/","https://live.jstv.com/","https://tv.cztv.com/live1","https://www.mgtv.com/","https://www.gdtv.com.cn/","https://www.cutv.com/","https://www.sctv.com/"});
        else if(which==2) new AlertDialog.Builder(this).setTitle("选择省份/地区").setItems(new String[]{"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆","香港","澳门","台湾"},(d,p)->{String region=new String[]{"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆","香港","澳门","台湾"}[p];String target="https://local.cctv.cn/";String label="央视网地方频道导航";if(region.equals("福建")){target="https://live.fjtv.net/";label="福建网络广播电视";}else if(region.equals("浙江")){target="https://tv.cztv.com/";label="浙江网络广播电视";}else if(region.equals("江苏")){target="https://live.jstv.com/";label="江苏网络广播电视";}else if(region.equals("广东")){target="https://www.gdtv.com.cn/";label="广东广播电视台";}else if(region.equals("四川")){target="https://www.sctv.com/";label="四川广播电视台";}else if(region.equals("上海")){target="https://www.kankanews.com/";label="看看新闻（上海广播电视台）";}else if(region.equals("香港")){target="https://www.rthk.hk/tv";label="香港电台 RTHK";}else if(region.equals("澳门")){target="https://www.tdm.com.mo/";label="澳门广播电视 TDM";}else if(region.equals("台湾")){target="https://www.pts.org.tw/";label="台湾公共电视 PTS";}final String dest=target;final String destName=label;new AlertDialog.Builder(this).setTitle(region+" · "+destName).setMessage("打开该地区的官方/电视台网站入口。页面是否提供直播及能否在当前网络播放，以网站实际情况为准；其他地区暂用央视网地方频道导航。").setPositiveButton("打开",(x,y)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(dest)));}catch(Exception e){toast("无法打开，请检查浏览器");}}).setNegativeButton("取消",null).show();}).setNegativeButton("返回",null).show();
        else if(which==3) openOfficialList("港澳台频道",new String[]{"香港电台 RTHK","香港无线新闻 TVB News","澳门广播电视 TDM","台湾公共电视 PTS"},new String[]{"https://www.rthk.hk/tv","https://news.tvb.com/","https://www.tdm.com.mo/","https://www.pts.org.tw/"});
        else if(which==4) openOfficialList("国内影视与节目",new String[]{"央视网","央视频","爱奇艺","腾讯视频","优酷","芒果TV","哔哩哔哩"},new String[]{"https://cctv.cntv.cn/","https://www.yangshipin.cn/","https://www.iqiyi.com/","https://v.qq.com/","https://www.youku.com/","https://www.mgtv.com/","https://www.bilibili.com/"});
      }).setNegativeButton("关闭",null).show();
  }
  private void openOfficialList(String title,String[] names,String[] urls){
    new AlertDialog.Builder(this).setTitle(title).setItems(names,(dialog,which)->{
      try{Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(urls[which]));startActivity(i);}
      catch(Exception e){toast("无法打开，请检查电视是否安装浏览器或对应官方应用");}
    }).setNegativeButton("返回",null).show();
  }
  private interface ChoiceAction{void onChoice(int which);}
  private void showHoverChoiceDialog(String title,String[] choices,String negative,ChoiceAction action){
    showHoverChoiceDialog(title,choices,negative,-1,action);
  }
  private void showHoverChoiceDialog(String title,String[] choices,String negative,int selectedIndex,ChoiceAction action){
    final int normal=Color.rgb(25,39,59);
    final int hoverColor=Color.rgb(35,91,139);
    final int[] activeChoice={selectedIndex};
    final int[] hoveredChoice={-1};
    android.widget.ListView listView=new android.widget.ListView(this);
    listView.setFocusable(true);
    listView.setFocusableInTouchMode(true);
    // Redraw hover and D-pad selection independently. A mouse movement must
    // never mutate the ListView's actual selected/focused row.
    final Runnable redrawChoiceRows=()->{
      for(int i=0;i<listView.getChildCount();i++){
        View child=listView.getChildAt(i);
        int childPosition=listView.getFirstVisiblePosition()+i;
        if(childPosition==hoveredChoice[0]){
          GradientDrawable bg=new GradientDrawable();bg.setColor(hoverColor);bg.setCornerRadius(dp(6));
          bg.setStroke(dp(2),Color.rgb(125,205,255));child.setBackground(bg);
        }else child.setBackground(makeButtonBg(normal,childPosition==activeChoice[0]));
      }
    };
    listView.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(55,72,94)));
    listView.setDividerHeight(dp(1));
    listView.setSelector(android.R.color.transparent);
    listView.setItemsCanFocus(false);
    listView.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
    android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,choices){
      @Override public android.view.View getView(int position,android.view.View convertView,android.view.ViewGroup parent){
        android.widget.TextView row=convertView instanceof android.widget.TextView?(android.widget.TextView)convertView:new android.widget.TextView(MainActivity.this);
        row.setTag(position);
        row.setText(getItem(position));row.setTextColor(Color.WHITE);row.setTextSize(16);
        row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(18),dp(11),dp(12),dp(11));
        row.setMinHeight(dp(48));row.setFocusable(false);row.setClickable(false);
        row.setBackground(makeButtonBg(normal,position==activeChoice[0]));
        row.setOnHoverListener((v,event)->{
          int act=event.getActionMasked();
          int rowPosition=v.getTag() instanceof Integer?(Integer)v.getTag():-1;
          if(act==android.view.MotionEvent.ACTION_HOVER_ENTER||act==android.view.MotionEvent.ACTION_HOVER_MOVE){
            if(rowPosition>=0)hoveredChoice[0]=rowPosition;
            redrawChoiceRows.run();
            return true;
          }else if(act==android.view.MotionEvent.ACTION_HOVER_EXIT){
            if(hoveredChoice[0]==rowPosition)hoveredChoice[0]=-1;
            redrawChoiceRows.run();
            return true;
          }
          return false;
        });
        return row;
      }
    };
    listView.setAdapter(adapter);
    listView.setPadding(dp(12),dp(4),dp(12),dp(4));
    listView.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
        activeChoice[0]=position;
        redrawChoiceRows.run();
      }
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
    });
    listView.setOnHoverListener((v,event)->{
      int act=event.getActionMasked();
      if(act==android.view.MotionEvent.ACTION_HOVER_MOVE||act==android.view.MotionEvent.ACTION_HOVER_ENTER){
        int position=listView.pointToPosition((int)event.getX(),(int)event.getY());
        hoveredChoice[0]=position>=0?position:-1;
        redrawChoiceRows.run();
      }else if(act==android.view.MotionEvent.ACTION_HOVER_EXIT){
        hoveredChoice[0]=-1;
        redrawChoiceRows.run();
      }
      return false;
    });
    LinearLayout content=new LinearLayout(this);content.setOrientation(1);
    LinearLayout topRow=new LinearLayout(this);topRow.setOrientation(0);topRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading=new TextView(this);heading.setText(title);heading.setTextColor(Color.WHITE);heading.setTextSize(18);heading.setTypeface(null,android.graphics.Typeface.BOLD);
    topRow.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1f));
    Button cancel=new Button(this);cancel.setText(negative);cancel.setAllCaps(false);cancel.setTextSize(13);
    styleTvButton(cancel,Color.rgb(65,78,96));
    topRow.addView(cancel,new LinearLayout.LayoutParams(dp(88),dp(40)));
    content.addView(topRow,new LinearLayout.LayoutParams(-1,dp(48)));
    content.addView(listView,new LinearLayout.LayoutParams(-1,0,1f));
    AlertDialog dialog=new AlertDialog.Builder(this).setView(content).create();
    cancel.setOnClickListener(v->dialog.dismiss());
    listView.setOnItemClickListener((parent,view,position,id)->{
      int actual=listView.getPositionForView(view);
      if(actual<0)actual=position;
      if(actual<0||actual>=choices.length)return;
      dialog.dismiss();
      action.onChoice(actual);
    });
    dialog.show();
    // Give D-pad users a deterministic starting point in the choice list.
    listView.post(()->{
      if(!dialog.isShowing()||choices.length==0)return;
      int initial=selectedIndex>=0&&selectedIndex<choices.length?selectedIndex:0;
      listView.setSelection(initial);
      listView.requestFocus();
    });
    if(dialog.getWindow()!=null){
      int screenW=getResources().getDisplayMetrics().widthPixels;
      int screenH=getResources().getDisplayMetrics().heightPixels;
      boolean touch=isTouchDevice();
      int width=(int)(screenW*(touch?0.92f:0.58f));
      int height=Math.min(dp(560),(int)(screenH*(touch?0.76f:0.80f)));
      dialog.getWindow().setLayout(width,height);
    }
  }
  private void showProvinceDialog(){
    String[] provinces={"全部地方台","北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    showHoverChoiceDialog("选择省级地区",provinces,"取消",which->{
      favoriteMode=false;
      localRegionFilterActive=true;activeCategory="地方";activeLocalProvince=null;activeLocalCity=null;
      if(which==0){refresh();info.setText("显示全部地方台");}
      else showCityDialog(provinces[which]);
    });
  }
  private void showCityDialog(String province){
    String[] cities=ChannelNamePolicy.localitiesForProvince(province);
    String[] choices=new String[cities.length+1];choices[0]="全部"+province+"频道";
    System.arraycopy(cities,0,choices,1,cities.length);
    showHoverChoiceDialog(province+" · 选择城市",choices,"返回",w->{
      favoriteMode=false;
      localRegionFilterActive=true;activeCategory="地方";activeLocalProvince=province;
      if(w==0){activeLocalCity=null;info.setText("地方频道目录 · "+province);}
      else{activeLocalCity=cities[w-1];info.setText("地方频道目录 · "+province+" · "+activeLocalCity);}
      refresh();
    });
  }
  private String regionalMetadata(Channel c){
    if(c==null)return "";
    // Use broadcaster metadata, never the stream URL: CDN hostnames can contain
    // misleading city/province tokens and must not determine a channel's region.
    return (c.name+" "+c.tvgName+" "+c.tvgId+" "+c.group).toLowerCase(Locale.ROOT)
        .replace("臺","台").replace("廣","广");
  }
  private boolean hasRegionalToken(String metadata,String token){
    return token!=null&&!token.isEmpty()&&metadata.contains(token.toLowerCase(Locale.ROOT));
  }
  private boolean isSatelliteChannel(Channel c){
    if(c==null)return false;
    return isSatelliteChannel(c,regionalMetadata(c));
  }
  private boolean isSatelliteChannel(Channel c,String metadata){
    // Legacy and partially imported records can have a null group. Keep the
    // same normalized value for both the policy call and the fast group checks.
    String rawGroup=c.group==null?"":c.group;
    String group=rawGroup.toLowerCase(Locale.ROOT);
    return ChannelNamePolicy.isSatellite(metadata,rawGroup)
        ||metadata.contains("卫视")||group.equals("卫视")||group.contains("satellite");
  }
  /** Derived from exact broadcaster fields, never a playback URL or source score.
   * Replaced snapshot rows start empty. Same-object edits invalidate by field
   * equality and China-scope evidence, not by potentially stale array index. */
  private static final class LocalDirectoryFacts {
    final String name,group,tvgName,tvgId,metadata,province;
    final boolean chinaScope,excluded,local;
    LocalDirectoryFacts(String name,String group,String tvgName,String tvgId,
        boolean chinaScope,String metadata,String province,boolean excluded,boolean local){
      this.name=name;this.group=group;this.tvgName=tvgName;this.tvgId=tvgId;
      this.chinaScope=chinaScope;this.metadata=metadata;this.province=province;
      this.excluded=excluded;this.local=local;
    }
    boolean matches(Channel c,boolean china){
      return chinaScope==china&&java.util.Objects.equals(name,c.name)
          &&java.util.Objects.equals(group,c.group)&&java.util.Objects.equals(tvgName,c.tvgName)
          &&java.util.Objects.equals(tvgId,c.tvgId);
    }
  }
  private LocalDirectoryFacts localDirectoryFacts(Channel c){
    boolean china=ChannelCatalogueScopePolicy.matches(c.sourceScopes,"china");
    LocalDirectoryFacts cached=c.localDirectoryFacts;
    if(cached!=null&&cached.matches(c,china))return cached;
    String name=c.name,group=c.group,tvgName=c.tvgName,tvgId=c.tvgId;
    String metadata=regionalMetadata(c);
    boolean excluded=isSatelliteChannel(c,metadata)||isCctvChannel(c)
        ||RegionCategoryPolicy.isHongKongMacauTaiwan(c.name,c.group);
    String province=excluded?"":ChannelNamePolicy.provinceForLocalChannel(metadata);
    boolean local=!excluded&&(!province.isEmpty()
        ||(china&&(metadata.contains("地方")||metadata.contains("local"))));
    if(!excluded&&!local){
      for(String token:localRegionTokens())if(metadata.contains(token)){local=true;break;}
    }
    LocalDirectoryFacts result=new LocalDirectoryFacts(name,group,tvgName,tvgId,
        china,metadata,province,excluded,local);
    if(result.matches(c,ChannelCatalogueScopePolicy.matches(c.sourceScopes,"china")))
      c.localDirectoryFacts=result;
    return result;
  }
  private boolean matchesProvinceLocal(Channel c,String province){
    if(c==null||province==null)return false;
    LocalDirectoryFacts facts=localDirectoryFacts(c);
    // Ambiguous local labels remain available only under all local channels.
    return !facts.excluded&&province.equals(facts.province);
  }
  // Build the province/city token index once. The former implementation
  // recreated every province's city array for every channel; filtering 10k+
  // records therefore allocated hundreds of thousands of short-lived arrays.
  private static volatile Set<String> localRegionTokenIndex;
  private Set<String> localRegionTokens(){
    Set<String> cached=localRegionTokenIndex;
    if(cached!=null)return cached;
    synchronized(MainActivity.class){
      cached=localRegionTokenIndex;
      if(cached==null){
        HashSet<String> tokens=new HashSet<>();
        for(String province:MAINLAND_PROVINCES){
          tokens.add(province.toLowerCase(Locale.ROOT));
          for(String city:ChannelNamePolicy.localitiesForProvince(province)){
            if(city!=null&&!city.isEmpty())tokens.add(city.toLowerCase(Locale.ROOT));
          }
        }
        cached=Collections.unmodifiableSet(tokens);
        localRegionTokenIndex=cached;
      }
    }
    return cached;
  }
  private boolean isLocalChannel(Channel c){
    return c!=null&&localDirectoryFacts(c).local;
  }
  private boolean matchesProvinceCity(Channel c,String province,String city){
    if(c==null||province==null||city==null)return false;
    LocalDirectoryFacts facts=localDirectoryFacts(c);
    if(facts.excluded||!province.equals(facts.province))return false;
    if(!ChannelNamePolicy.isKnownLocality(province,city))return false;
    return ChannelNamePolicy.matchesLocalCityWithProvince(facts.metadata,province,city,facts.province)
        ||hasRegionalToken(facts.metadata,city)||province.equals(city);
  }
  private boolean categoryMatch(Channel c,String cat){
    if(c==null||cat==null)return false;
    if("地方".equals(cat))return isLocalChannel(c);
    if("卫视".equals(cat))return isSatelliteChannel(c);
    // Legacy/imported M3U entries may omit group or metadata fields. Category
    // counting runs before a refresh, so a single incomplete record must never
    // crash the UI or prevent the rest of the saved catalogue from being counted.
    String group=c.group==null?"":c.group;
    return categoryMatch(c,cat,categoryMetadata(c,group),group.toLowerCase(Locale.ROOT));
  }
  private static String categoryMetadata(Channel c,String group){
    String name=c.name==null?"":c.name;
    String tvgName=c.tvgName==null?"":c.tvgName;
    String tvgId=c.tvgId==null?"":c.tvgId;
    return (name+" "+group+" "+tvgName+" "+tvgId).toLowerCase(Locale.ROOT);
  }
  private boolean categoryMatch(Channel c,String cat,String x,String g){
    if("地方".equals(cat))return isLocalChannel(c);
    if("卫视".equals(cat))return isSatelliteChannel(c);
    if(cat.equals("自建频道"))return "自建频道".equals(g)||"自定义".equals(g);
    if(cat.startsWith("城市:")){
      String city=cat.substring(3);
      String metadata=regionalMetadata(c);
      if(isSatelliteChannel(c,metadata))return false;
      String explicit=ChannelNamePolicy.provinceForLocalChannel(metadata);
      if(!explicit.isEmpty()){
        for(String province:MAINLAND_PROVINCES){
          if(explicit.equals(province)&&!ChannelNamePolicy.isKnownLocality(province,city))return false;
        }
      }
      return hasRegionalToken(metadata,city);
    }
    // Province labels are checked only after the fixed categories below.
    if(cat.equals("中国"))return (ChannelCatalogueScopePolicy.matches(c.sourceScopes,"china")||x.contains("cctv")||x.contains("央视")||x.contains("中央电视台")||canonicalChannelName(c.name).startsWith("CCTV-")||isSatelliteChannel(c)||isLocalChannel(c))&&!RegionCategoryPolicy.isHongKongMacauTaiwan(c.name,c.group);
    if(cat.equals("全球频道"))return ChannelCatalogueScopePolicy.matches(c.sourceScopes,"global");
    if(cat.equals("娱乐频道"))return g.contains("entertainment")||g.contains("comedy")||g.contains("music")||g.contains("lifestyle")||g.contains("movies")||g.contains("animation")||g.contains("series")||g.contains("culture")||g.contains("family")||g.contains("kids")||g.contains("children")||g.contains("documentary")||g.contains("cartoon")||g.contains("娱乐")||g.contains("综艺")||g.contains("音乐")||g.contains("电影")||g.contains("喜剧")||g.contains("生活")||g.contains("少儿")||g.contains("动画")||g.contains("纪录");
    if(cat.equals("央视"))return x.contains("cctv")||x.contains("央视")||x.contains("中央电视台")||canonicalChannelName(c.name).startsWith("CCTV-")||g.equals("cctv");
    if(cat.equals("港澳台"))return RegionCategoryPolicy.isHongKongMacauTaiwan(c.name,c.group);
    if(cat.equals("香港")||cat.equals("澳门")||cat.equals("台湾"))return cat.equals(RegionCategoryPolicy.subcategory(c.name,c.group));
    if(cat.equals("国际新闻"))return g.contains("international news")||g.contains("国际新闻")||x.contains("al jazeera")||x.contains("france 24")||x.contains("dw ")||x.contains("abc news");
    if(cat.equals("体育"))return g.contains("sports")||g.contains("体育");
    if(cat.equals("天气"))return g.contains("weather")||g.contains("天气")||x.contains("气象");
    if(cat.equals("教育"))return g.contains("education")||g.contains("教育");
    for(String province:MAINLAND_PROVINCES)
      if(cat.equals(province))return matchesProvinceLocal(c,province);
    return false;
  }
  private int findChannelIndexByKey(String key){
    return findChannelIndexByKey(key,-1);
  }
  private int findChannelIndexByKey(String key,int hint){
    if(key==null)return -1;
    synchronized(channels){
      // A rendered row already owns a position. Trust it only when the
      // current catalogue still contains the exact same key at that position.
      if(hint>=0&&hint<channels.size()){
        Channel candidate=channels.get(hint);
        if(candidate!=null&&key.equals(candidate.key()))return hint;
      }
      for(int i=0;i<channels.size();i++){
        Channel c=channels.get(i);
        if(c!=null&&key.equals(c.key()))return i;
      }
    }
    return -1;
  }
  private void deleteLiveChannel(String key){
    if(!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive()||currentVodPanel!=null)return;
    int removed=findChannelIndexByKey(key);
    if(removed<0)return;
    if(channels.size()<=1){toast("至少保留一个频道");return;}
    final Channel oldSelected=selected>=0&&selected<channels.size()?channels.get(selected):null;
    final Channel target=channels.get(removed);
    final boolean clearSavedResume=findLastChannelForResume()==removed;
    final boolean restart=oldSelected!=null&&oldSelected!=target
        &&(player!=null||libVlcEngine!=null||liveVlcReleaseContinuation!=null);
    // Native callbacks hold the old positional channel index. Revoke the live
    // session before a user-requested structural deletion, even when the
    // selected station survives. Do not restart under a different identity.
    if(player!=null||libVlcEngine!=null||liveVlcReleaseContinuation!=null){
      ++playbackGeneration;currentMediaId="";
      stopFailedLiveSession("正在更新频道目录");
    }
    clearPendingRemoteZap();
    synchronized(channels){
      if(!channels.remove(target))return;
      selected=oldSelected==null||oldSelected==target?-1:channels.indexOf(oldSelected);
    }
    synchronized(favorites){favorites.remove(key);}
    rebuildChannelIndexes();
    if(startupAutoResumePending){
      startupResumeIndex=channels.indexOf(startupResumeChannel);
      if(startupResumeIndex<0){
        startupResumeChannel=null;startupAutoResumePending=false;
        if(startupAutoResumeTask!=null){uiHandler.removeCallbacks(startupAutoResumeTask);startupAutoResumeTask=null;}
      }
    }
    if(clearSavedResume)persistLastChannelState(new LastChannelState("","","","",""));
    persist();refresh();
    if(restart&&selected>=0&&selected<channels.size()&&channels.get(selected)==oldSelected)
      startCurrentSource();
    else if(oldSelected==target){
      resumePlaybackOnStart=false;
      if(info!=null)info.setText("当前频道已删除，请选择其他频道");
      if(fullScreenPlayback)exitFullScreenPlayback();
    }
  }

  private void playChannelByKey(String key,int hint){
    int index=findChannelIndexByKey(key,hint);
    if(index>=0)play(index);
  }
  private void playChannelSourceByKey(String key,String sourceUrl){
    int index=findChannelIndexByKey(key);
    if(index<0||sourceUrl==null)return;
    final int sourceIndex;
    synchronized(channels){
      if(index>=channels.size())return;
      Channel current=channels.get(index);
      if(current==null||!key.equals(current.key())||current.backups==null)return;
      sourceIndex=current.backups.indexOf(sourceUrl);
      if(sourceIndex<0)return;
    }
    // Release the catalogue lock before player setup, persistence, or UI work.
    play(index,sourceIndex);
  }
  /** One category/region membership path for both directory rendering and DPAD order. */
  private boolean matchesDirectoryScope(Channel c,String category,boolean regionOnly,
      String province,String city,Set<String> geoKeys,boolean favoritesOnly,Set<String> favoriteKeys){
    if(c==null)return false;
    if((geoKeys!=null||favoritesOnly)&&!RemoteChannelOrderPolicy.matchesMembership(
        c.key(),geoKeys,favoritesOnly,favoriteKeys))return false;
    if(regionOnly)return province==null?isLocalChannel(c):
        city==null?matchesProvinceLocal(c,province):matchesProvinceCity(c,province,city);
    return "全部".equals(category)||"收藏".equals(category)||categoryMatch(c,category);
  }

  private static final String[] PREPARED_FIXED_CATEGORIES={"中国","央视","卫视","港澳台","香港","澳门","台湾","全部","全球频道","娱乐频道","国际新闻","体育","天气","教育","自建频道"};
  private SnapshotCategoryIndex.LocalMembership buildLocalMembership(List<Channel> entries,
      java.util.function.BooleanSupplier cancelled,boolean prepareFixed){
    ArrayList<Integer> allLocal=new ArrayList<>();
    java.util.Map<String,java.util.List<Integer>> provinces=new HashMap<>();
    java.util.Map<String,java.util.List<Integer>> fixed=prepareFixed?new HashMap<>():Collections.emptyMap();
    java.util.Map<String,java.util.Set<String>> geo=prepareFixed?new HashMap<>():null;
    if(prepareFixed)for(String category:PREPARED_FIXED_CATEGORIES)fixed.put(category,new ArrayList<>());
    for(int i=0;i<entries.size();i++){
      if((i&127)==0)SourceProbeTimePolicy.checkPublicationPaused(cancelled);
      Channel candidate=entries.get(i);
      if(candidate==null)continue;
      LocalDirectoryFacts facts=localDirectoryFacts(candidate);
      if(facts.local)allLocal.add(i);
      if(!facts.excluded&&!facts.province.isEmpty())
        provinces.computeIfAbsent(facts.province,k->new ArrayList<>()).add(i);
      if(prepareFixed){
        // Prepare one shared normalized metadata value for all fixed category
        // predicates. Keep the same predicates as the uncached filter.
        String group=candidate.group==null?"":candidate.group;
        String metadata=categoryMetadata(candidate,group),lowerGroup=group.toLowerCase(Locale.ROOT);
        for(String category:PREPARED_FIXED_CATEGORIES)
          if("全部".equals(category)||categoryMatch(candidate,category,metadata,lowerGroup))
            fixed.get(category).add(i);
        String key=null;
        if(candidate.sourceScopes!=null)for(String scope:candidate.sourceScopes){
          if(scope!=null&&scope.startsWith("geo:")&&ChannelCatalogueScopePolicy.isRetainableScope(scope)){
            if(key==null)key=candidate.key();
            geo.computeIfAbsent(scope,k->new HashSet<>()).add(key);
          }
        }
        String countryScope=GeoPlaylistCachePolicy.countryScopeForTvgId(candidate.tvgId);
        if(!countryScope.isEmpty())geo.computeIfAbsent(countryScope,k->new HashSet<>())
            .add(key==null?candidate.key():key);
      }
    }
    if(prepareFixed){
      java.util.List<Integer> cctv=fixed.get("央视");
      HashMap<Integer,Integer> orders=new HashMap<>();
      HashMap<Integer,String> names=new HashMap<>();
      for(int i:cctv){
        SourceProbeTimePolicy.checkPublicationPaused(cancelled);
        Channel c=entries.get(i);orders.put(i,cctvSortOrder(c));names.put(i,displayChannelName(c));
      }
      cctv.sort((a,b)->{
        int order=Integer.compare(orders.get(a),orders.get(b));
        return order!=0?order:names.get(a).compareTo(names.get(b));
      });
    }
    SourceProbeTimePolicy.checkPublicationPaused(cancelled);
    return new SnapshotCategoryIndex.LocalMembership(entries.size(),allLocal,provinces,fixed,geo);
  }
  private void prepareStartupLocalSnapshot(){
    long started=android.os.SystemClock.elapsedRealtime();
    final long epoch;
    ArrayList<Channel> entries;
    synchronized(channels){epoch=catalogueMembershipGeneration;entries=new ArrayList<>(channels);}
    try{
      SnapshotCategoryIndex.LocalMembership membership=buildLocalMembership(entries,
          ()->isFinishing()||isDestroyed()||epoch!=catalogueMembershipGeneration,true);
      if(epoch==catalogueMembershipGeneration)
        preparedLocalSnapshot=new SnapshotCategoryIndex.PreparedLocal(epoch,membership);
      android.util.Log.i("MyTVDirectory","startup_local_index_ms="+
          (android.os.SystemClock.elapsedRealtime()-started)+" rows="+entries.size());
    }catch(RuntimeException deferred){
      // Derived index failure must never invalidate the authoritative Room snapshot.
      android.util.Log.w("MyTVDirectory","startup_local_index_deferred",deferred);
    }
  }
  private void refresh(){refresh(false);}
  private void refreshPage(){refresh(false,true);}
  private void refresh(boolean preserveQueuedZap){refresh(preserveQueuedZap,false);}
  private void refresh(boolean preserveQueuedZap,boolean pageOnly){
    if(list==null||search==null||isFinishing()||isDestroyed())return;
    // Only explicit paging can reuse the already published filtered order.
    // Favorite membership can change independently; keep its full filter path.
    final RemoteChannelOrder pageOrder=pageOnly&&!favoriteMode&&directoryFilterInFlightKey==null
        ?currentRemoteChannelOrder():null;
    final long oldGeneration=catalogueFilterGeneration;
    final long generation=++catalogueFilterGeneration;
    if(preserveQueuedZap&&pendingRemoteZapGeneration==oldGeneration)
      pendingRemoteZapGeneration=generation;
    else clearPendingRemoteZap();
    final long mutation=catalogueMutationGeneration;
    final long membershipEpoch=catalogueMembershipGeneration;
    java.util.concurrent.Future<?> previousFilter=pendingCatalogueFilter;
    if(previousFilter!=null)previousFilter.cancel(true);
    purgeCancelledTasks(catalogueFilterWorker);
    // Keep paging controls focusable while the filter runs. Their click
    // handler rejects concurrent requests instead of disabling focused Views.
    final String query=RemoteChannelOrderPolicy.normalizeQuery(search.getText().toString());
    final String categorySnapshot=activeCategory==null?"全部":activeCategory;
    final boolean favoriteOnly=favoriteMode;
    final boolean regionOnly=localRegionFilterActive&&"地方".equals(categorySnapshot);
    final String provinceSnapshot=activeLocalProvince;
    final String citySnapshot=activeLocalCity;
    final String geoScopeSnapshot=activeGeoScope,geoCountrySnapshot=activeGeoCountry,geoLabelSnapshot=activeGeoLabel;
    // Collections.synchronizedSet synchronizes individual methods, not
    // iteration by HashSet(Collection). Take the entire favorites snapshot
    // under the same lock used by remote order/persistence; this prevents
    // ConcurrentModificationException during a quick favorite toggle.
    final Set<String> favoriteSnapshot;
    favoriteSnapshot=FavoriteMembershipPolicy.snapshot(favorites);
    final int expectedCount; synchronized(channels){expectedCount=channels.size();}
    final int favoriteHash=favoriteOnly?favoriteSnapshot.hashCode():0;
    final String orderKey=remoteChannelOrderKey(categorySnapshot,query,favoriteOnly,
        generation,mutation,expectedCount,favoriteHash);
    directoryFilterInFlightKey=orderKey;
    final int requestedPage=livePageIndex;
    final long filterStartedAt=android.os.SystemClock.elapsedRealtime();
    android.util.Log.i("MyTVDirectory","filter_start category="+categorySnapshot+" total_snapshot_pending=true generation="+generation);
    final int previousFocusedIndex;
    final String previousFocusedKey;
    View focusRoot=fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()
        ?fullscreenMenuPopup.getContentView():rootLayout;
    View currentFocus=pageOnly||focusRoot==null?null:focusRoot.findFocus();
    previousFocusedIndex=currentFocus!=null&&currentFocus.getTag() instanceof Integer?(Integer)currentFocus.getTag():-1;
    Object focusedKeyTag=currentFocus==null?null:currentFocus.getTag(R.id.tag_channel_key);
    previousFocusedKey=focusedKeyTag instanceof String?(String)focusedKeyTag:null;
    final boolean customCategory="自建频道".equals(categorySnapshot);
    // Do not walk and clone a potentially huge catalogue on the UI thread.
    // Capture only the list of references under the short catalogue lock, then
    // copy the small filtering metadata on the catalogue worker.
    pendingCatalogueFilter=catalogueFilterWorker.submit(()->{
      int scanned=0,categoryMatches=0,queryMatches=0,nullRecords=0,nullNames=0,nullGroups=0,nullTvgNames=0,nullTvgIds=0,nullUrls=0;
      try{
        if(Thread.currentThread().isInterrupted()||generation!=catalogueFilterGeneration)return;
        final boolean reusePage;
        final ArrayList<Channel> sources;
        synchronized(channels){
          reusePage=pageOrder!=null&&pageOrder.key.equals(remoteChannelOrderKey(
              categorySnapshot,query,favoriteOnly,oldGeneration,mutation,expectedCount,favoriteHash))
              &&mutation==catalogueMutationGeneration&&expectedCount==channels.size();
          sources=reusePage?null:new ArrayList<>(channels);
        }
        final RemoteChannelOrder ready;
        if(reusePage){
          // Exact prior result; do not rebuild channel arrays or key/position maps.
          ready=pageOrder.withKey(orderKey);
          categoryMatches=queryMatches=ready.indexes.size();
          android.util.Log.i("MyTVDirectory","filter_ready category="+categorySnapshot
              +" total="+expectedCount+" scanned=0 page_order_reuse=true matches="+ready.indexes.size()
              +" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-filterStartedAt)+" generation="+generation);
        }else{
        final boolean geographic=geoScopeSnapshot!=null;
        final boolean indexEligible=!favoriteOnly;
        final java.util.List<String> scopeKey=SnapshotCategoryIndex.scopeKey(categorySnapshot,
            regionOnly?provinceSnapshot:null,regionOnly?citySnapshot:null,geoScopeSnapshot,geoCountrySnapshot);
        java.util.List<Integer> exactScope=indexEligible?categorySnapshotIndex.get(membershipEpoch,sources.size(),scopeKey):null;
        // A cached geographic position list already proves exact membership.
        // On a miss retain the existing provenance-based membership resolution.
        final Set<String> geoKeys=geographic&&exactScope==null?
            geoKeysForSnapshot(sources,membershipEpoch,geoScopeSnapshot,geoCountrySnapshot):null;
        // Filter the stable reference snapshot directly. Cloning every Channel here
        // duplicated 11k+ objects and their display strings on every category/search
        // refresh, compounding the already large restored catalogue in memory.
        // Channel edits replace/append under the catalogue lock; playback actions
        // resolve by stable key, so these references are read-only in this pass.
        HashMap<String,Integer> sourceIndexByKey=new HashMap<>(Math.max(16,Math.min(1024,sources.size())));
        ArrayList<Integer> indexes=new ArrayList<>();
        if(indexEligible&&!geographic&&"地方".equals(categorySnapshot)
            &&!categorySnapshotIndex.hasLocalIndex(membershipEpoch,sources.size())){
          SnapshotCategoryIndex.PreparedLocal prebuilt=preparedLocalSnapshot;
          if(prebuilt!=null&&prebuilt.matches(membershipEpoch,sources.size())){
            categorySnapshotIndex.putLocalMembership(membershipEpoch,sources.size(),prebuilt.membership);
          }else{
            SnapshotCategoryIndex.LocalMembership membership;
            try{
              membership=buildLocalMembership(sources,()->generation!=catalogueFilterGeneration,false);
            }catch(SourceProbeTimePolicy.PublicationPausedException superseded){return;}
            if(generation!=catalogueFilterGeneration)return;
            // A changed epoch must reach the existing publication/retry gate.
            if(membershipEpoch==catalogueMembershipGeneration)
              categorySnapshotIndex.putLocalMembership(membershipEpoch,sources.size(),membership);
          }
        }
        SnapshotCategoryIndex.PreparedLocal preparedFixed=preparedLocalSnapshot;
        if(exactScope==null&&indexEligible&&!geographic&&!regionOnly&&preparedFixed!=null&&preparedFixed.matches(membershipEpoch,sources.size()))
          exactScope=preparedFixed.membership.fixedCategory(categorySnapshot);
        java.util.List<Integer> localCandidates=indexEligible&&!geographic&&"地方".equals(categorySnapshot)
            ?categorySnapshotIndex.localCandidates(membershipEpoch,sources.size(),regionOnly?provinceSnapshot:null):null;
        final java.util.List<Integer> cachedScope=exactScope!=null?exactScope:
            (citySnapshot==null?localCandidates:null);
        final java.util.List<Integer> scanPositions=cachedScope!=null?cachedScope:localCandidates;
        final int scanCount=scanPositions==null?sources.size():scanPositions.size();
        final int[] cctvOrders=cachedScope==null&&"央视".equals(categorySnapshot)?new int[sources.size()]:null;
        final String[] cctvNames=cctvOrders==null?null:new String[sources.size()];
        for(int position=0;position<scanCount;position++){
          if((position&127)==0&&(Thread.currentThread().isInterrupted()||generation!=catalogueFilterGeneration))return;
          int i=scanPositions==null?position:scanPositions.get(position);
          scanned++;
          Channel c=sources.get(i);
          if(c==null){nullRecords++;continue;}
          if(c.name==null)nullNames++;
          if(c.group==null)nullGroups++;
          if(c.tvgName==null)nullTvgNames++;
          if(c.tvgId==null)nullTvgIds++;
          if(c.url==null)nullUrls++;
          if(cachedScope==null&&!matchesDirectoryScope(c,categorySnapshot,regionOnly,provinceSnapshot,citySnapshot,
              geoKeys,favoriteOnly,favoriteSnapshot))continue;
          categoryMatches++;
          String display=null;
          if(!RemoteChannelOrderPolicy.matchesNormalizedQuery(query,c.name,c.group,null)){
            display=displayChannelName(c);
            if(!RemoteChannelOrderPolicy.matchesNormalizedQuery(query,null,null,display))continue;
          }
          queryMatches++;
          indexes.add(i);
          if(cctvOrders!=null){
            cctvOrders[i]=cctvSortOrder(c);
            cctvNames[i]=display==null?displayChannelName(c):display;
          }
          sourceIndexByKey.putIfAbsent(c.key(),i);
        }
        // Diagnose empty local directories without mistaking a completed UI filter
        // for a completed network discovery. Count candidates from the saved snapshot.
        if("地方".equals(categorySnapshot) && indexes.isEmpty() && scanPositions==null) {
          int localCandidateCount=0, provinceCandidates=0, satelliteExcluded=0;
          for(Channel candidate:sources) {
            if(Thread.currentThread().isInterrupted()||generation!=catalogueFilterGeneration)return;
            if(candidate==null)continue;
            if(isSatelliteChannel(candidate)){satelliteExcluded++;continue;}
            if(isLocalChannel(candidate))localCandidateCount++;
            if(provinceSnapshot!=null && matchesProvinceLocal(candidate,provinceSnapshot))provinceCandidates++;
          }
          String localDetail="total="+sources.size()
              +" local_candidates="+localCandidateCount+" province_candidates="+provinceCandidates
              +" satellite_excluded="+satelliteExcluded+" province="+provinceSnapshot
              +" city="+citySnapshot+" query_matches="+queryMatches
              +" search_active="+(!query.isEmpty());
          android.util.Log.w("MyTVDirectory","local_empty "+localDetail);
          // Available from the Settings copy button; no adb/desktop needed.
          saveLiveDiagnostic("LOCAL_DIRECTORY_EMPTY",localDetail);
        }
        if(cctvOrders!=null){
          indexes.sort((a,b)->{
            int order=Integer.compare(cctvOrders[a],cctvOrders[b]);
            return order!=0?order:cctvNames[a].compareTo(cctvNames[b]);
          });
        }
        if(Thread.currentThread().isInterrupted()||generation!=catalogueFilterGeneration)return;
        if(indexEligible&&cachedScope==null&&query.isEmpty()&&membershipEpoch==catalogueMembershipGeneration)
          categorySnapshotIndex.put(membershipEpoch,sources.size(),scopeKey,indexes);
        ArrayList<Channel> visible=new ArrayList<>(indexes.size());
        for(int index:indexes)visible.add(sources.get(index));
        ready=new RemoteChannelOrder(orderKey,indexes,visible,sourceIndexByKey);
        long filterElapsed=android.os.SystemClock.elapsedRealtime()-filterStartedAt;
        android.util.Log.i("MyTVDirectory","filter_ready category="+categorySnapshot+" total="+sources.size()+" scanned="+scanned+" scope_index_hit="+(cachedScope!=null)+" page_order_reuse="+reusePage+" category_matches="+categoryMatches+" query_matches="+queryMatches+" matches="+visible.size()+" null_records="+nullRecords+" null_name="+nullNames+" null_group="+nullGroups+" null_tvg_name="+nullTvgNames+" null_tvg_id="+nullTvgIds+" null_url="+nullUrls+" elapsed_ms="+filterElapsed+" generation="+generation);
        }
        if(Thread.currentThread().isInterrupted()||generation!=catalogueFilterGeneration)return;
        runOnUiThread(()->publishFilteredDirectory(generation,()->{
          if(isFinishing()||isDestroyed()||generation!=catalogueFilterGeneration||list==null)return;
          directoryFilterInFlightKey=null;
          if(!orderKey.equals(currentDirectoryOrderKey())
              ||!favoriteSnapshot.equals(FavoriteMembershipPolicy.snapshot(favorites))){
            // Never publish positions from a replaced directory. Recompute with
            // the newest metadata; pending keys belong to this same filter only.
            refresh(true);
            return;
          }
          cachedRemoteChannelOrder=ready;
          String currentSelectedKey=null;
          if(selected>=0&&selected<channels.size())currentSelectedKey=channels.get(selected).key();
          renderFilteredChannels(ready.visible,currentSelectedKey,favoriteSnapshot,customCategory,requestedPage,
              preserveRequestedLivePageOnce,previousFocusedIndex,previousFocusedKey,ready.sourceIndexByKey);
          if(geoScopeSnapshot!=null&&info!=null)info.setText(geoLabelSnapshot+"：已显示保存的 "+ready.visible.size()+" 个频道"+(ready.visible.isEmpty()?"；可手动更新或导入":""));
          if(menuSelectedFocusGeneration==generation){
            menuSelectedFocusGeneration=-1L;
            focusSelectedChannelInMenu();
          }
          if(RemoteChannelOrderPolicy.canDispatchQueuedZap(pendingRemoteZapDispatch!=null,pendingRemoteZapGeneration,generation,pendingRemoteZapSteps)){
            int steps=pendingRemoteZapSteps;
            clearPendingRemoteZap();
            if(canApplyRemoteZap())applyChannelZap(ready,steps);
          }
        }));
      }catch(Throwable failure){
        android.util.Log.e("MyTVCatalogue","background catalogue filter failed category="+categorySnapshot+" scanned="+scanned+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-filterStartedAt),failure);
        runOnUiThread(()->{
          if(generation!=catalogueFilterGeneration||isFinishing()||isDestroyed())return;
          directoryFilterInFlightKey=null;
          // Reconcile paging boundaries after a failed background filter.
          // The busy click guard is released with the in-flight key above.
          updateLivePaging(lastFilteredMatchCount);
          // Do not replay old remote keys after a failed filter on the next
          // unrelated user action. A fresh press can start a fresh filter.
          if(pendingRemoteZapGeneration==generation&&pendingRemoteZapSteps!=0){
            clearPendingRemoteZap();
            if(canApplyRemoteZap())showChannelZapMessage("频道顺序暂未更新，请重新按上下键");
          }
          if(info!=null)info.setText("频道目录筛选遇到问题，已保留上一次频道列表；可以继续翻页或重试。");
        });
      }
    });
  }
  private void renderFilteredChannels(ArrayList<Channel> visible,String selectedKey,Set<String> favoriteSnapshot,
      boolean customCategory,int requestedPage,boolean preserveRequestedPage,
      int previousFocusedIndex,String previousFocusedKey,HashMap<String,Integer> sourceIndexByKey){
    final long renderStartedAt=android.os.SystemClock.elapsedRealtime();
    android.util.Log.i("MyTVDirectory","render_start category="+activeCategory+" visible="+visible.size()+" page="+requestedPage);
    list.removeAllViews();
    if(customCategory){
      TextView customHeader=new TextView(this);
      customHeader.setText("自建频道\\n添加自己的频道、网站播放列表入口；保存后会记住配置。");
      customHeader.setTextColor(Color.WHITE);customHeader.setTextSize(14);
      customHeader.setPadding(dp(10),dp(10),dp(10),dp(10));
      list.addView(customHeader,new LinearLayout.LayoutParams(-1,-2));
      LinearLayout customActions=new LinearLayout(this);customActions.setOrientation(0);
      Button customAdd=new Button(this);customAdd.setText("添加频道");customAdd.setAllCaps(false);styleTvButton(customAdd,Color.rgb(26,100,160));customAdd.setOnClickListener(v->addDialog());
      Button customUrl=new Button(this);customUrl.setText("输入网站入口");customUrl.setAllCaps(false);styleTvButton(customUrl,Color.rgb(35,55,78));customUrl.setOnClickListener(v->urlDialog());
      customActions.addView(customAdd,new LinearLayout.LayoutParams(0,dp(52),1));
      customActions.addView(customUrl,new LinearLayout.LayoutParams(0,dp(52),1));
      list.addView(customActions,new LinearLayout.LayoutParams(-1,dp(56)));
    }
    // Keep the catalogue complete, but limit live View inflation per page on low-memory TV boxes.
    final int maxRows=LIVE_PAGE_SIZE;
    int selectedPosition=-1;
    RemoteChannelOrder renderedOrder=cachedRemoteChannelOrder;
    if(renderedOrder!=null&&renderedOrder.indexes.size()==visible.size()){
      Integer position=renderedOrder.positions.get(selected);
      if(position!=null)selectedPosition=position;
    }
    livePageIndex=LivePagePolicy.choosePage(
        requestedPage,selectedPosition,maxRows,preserveRequestedPage);
    if(preserveRequestedPage)preserveRequestedLivePageOnce=false;
    int pageCount=LivePagePolicy.pageCount(visible.size(),maxRows);
    if(livePageIndex>=pageCount)livePageIndex=pageCount-1;
    int pageStart=livePageIndex*maxRows;
    int pageEnd=pageStart+Math.min(maxRows,visible.size()-pageStart);
    for(int row=pageStart;row<pageEnd;row++){
      Channel c=visible.get(row);
      if(c==null)continue;
      // Filtering runs on a worker over the same Channel references. UI rendering
      // must NEVER mutate their fields; use null-safe display locals instead.
      final String channelKey=c.key();
      final String title=displayChannelName(c);
      String group=(isCctvChannel(c)||isSatelliteChannel(c))?"":displayLiveChannelGroup(c);
      Integer mappedIndex=sourceIndexByKey==null?null:sourceIndexByKey.get(channelKey);
      final int currentIndex=mappedIndex==null?-1:mappedIndex;
      Button b=new Button(this);b.setTag(currentIndex);b.setTag(R.id.tag_channel_key,channelKey);
      boolean playing=channelKey.equals(selectedKey);
      b.setText((playing?"▶ ":"")+(favoriteSnapshot.contains(channelKey)?"★ ":"")+title+group);
      b.setContentDescription(playing?"当前正在播放："+title:title);
      b.setTextSize(16);b.setMinHeight(dp(52));styleLiveChannelButton(b,Color.rgb(35,45,62));
      b.setOnClickListener(v->playChannelByKey(channelKey,currentIndex));
      b.setOnLongClickListener(v->{
        int current=findChannelIndexByKey(channelKey,currentIndex);if(current<0)return true;
        Channel latest;
        synchronized(channels){latest=current<channels.size()?channels.get(current):null;if(latest==null||!channelKey.equals(latest.key()))return true;}
        ArrayList<String> sourceUrls=latest.backups==null?new ArrayList<>():new ArrayList<>(latest.backups);
        new AlertDialog.Builder(this).setItems(new String[]{favorites.contains(channelKey)?"取消收藏":"加入收藏","查看/选择备用源（"+sourceUrls.size()+"个）","删除频道"},(d,w)->{
          if(w==0){
            boolean nowFavorite=toggleFavoriteMembership(channelKey);
            // Favorite-only changes must not serialize and CAS-write the entire
            // 6k+ channel directory; the fullscreen favorite path already uses
            // the exact one-row Room mutation queue.
            queueFavoriteMutation(channelKey,nowFavorite);
            refresh();
          }
          else if(w==1){
            if(sourceUrls.isEmpty())return;
            String[] opts=new String[sourceUrls.size()];
            for(int j=0;j<opts.length;j++)opts[j]="播放源 "+(j+1)+(j==0?"（主源）":"（备用）");
            new AlertDialog.Builder(this).setTitle(title+" · 播放源").setItems(opts,(dd,j)->{if(j>=0&&j<sourceUrls.size())playChannelSourceByKey(channelKey,sourceUrls.get(j));}).setNegativeButton("关闭",null).show();
          }else{
            new AlertDialog.Builder(this).setTitle("删除频道？")
                .setMessage(title+"。删除时若正在直播，将先安全停止播放器；保留的当前频道会重新播放。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(confirm,which)->deleteLiveChannel(channelKey)).show();
          }
        }).show();return true;
      });
      list.addView(b);
    }
    lastFilteredMatchCount=visible.size();
    updateLivePaging(visible.size());
    if(preserveRequestedPage&&channelScroll!=null)channelScroll.scrollTo(0,0);
    // Newly constructed rows already have their title, favorite/playing marker,
    // accessibility text and initial style. Avoid immediately processing all 48 again.
    // Replace the dialog's transient "正在显示/正在筛选" text only after the
    // latest background filter has actually rendered its result.
    if(info!=null){
      if("央视".equals(activeCategory)||"卫视".equals(activeCategory)){
        info.setText(activeCategory+"频道已显示 "+visible.size()+" 个");
      }else if("地方".equals(activeCategory)&&localRegionFilterActive){
        String region=activeLocalProvince==null?"全部地方台":activeLocalProvince
            +(activeLocalCity==null?"地方台":(" · "+activeLocalCity));
        info.setText("已显示："+region+"，共 "+visible.size()+" 个频道");
      }
    }
    if(visible.isEmpty()){
      TextView empty=new TextView(this);
      empty.setText(customCategory
        ?"还没有自建频道。点击上方“添加频道”或“输入网站入口”开始添加。"
        :"这个分类暂时没有匹配的频道。你可以导入播放列表，或更换分类/搜索关键词。");
      empty.setTextColor(Color.LTGRAY);empty.setTextSize(15);empty.setGravity(Gravity.CENTER);
      empty.setPadding(dp(18),dp(22),dp(18),dp(22));list.addView(empty,new LinearLayout.LayoutParams(-1,dp(180)));
    }
    if(pageCount>1){TextView more=new TextView(this);more.setText("共 "+visible.size()+" 个频道，当前第 "+(pageStart+1)+"–"+pageEnd+" 个。可翻页或选择页码。");more.setTextColor(Color.LTGRAY);more.setPadding(dp(8),dp(12),dp(8),dp(12));list.addView(more);}
    android.util.Log.i("MyTVDirectory","render_ready category="+activeCategory+" total_matches="+visible.size()+" rendered_buttons="+(pageEnd-pageStart)+" child_views="+list.getChildCount()+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-renderStartedAt));
    final int restoreIndex=previousFocusedIndex;
    final long focusGeneration=catalogueFilterGeneration;
    final PopupWindow focusPopup=fullscreenMenuPopup;
    if(fullScreenPlayback&&fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()
        &&(previousFocusedKey!=null||previousFocusedIndex>=0))list.post(()->{
      if(!activityStarted||!fullScreenPlayback||focusPopup!=fullscreenMenuPopup
          ||focusPopup==null||!focusPopup.isShowing()||focusGeneration!=catalogueFilterGeneration
          ||hasLivePagingFocus())return;
      View target=null,selectedRow=null;
      // Resolve previous focus and selected-channel fallback in one pass.
      // An old integer index is valid only when no stable focus key was saved.
      int childCount=list.getChildCount();
      for(int i=0;i<childCount;i++){
        View child=list.getChildAt(i);
        Object keyTag=child.getTag(R.id.tag_channel_key);
        if(previousFocusedKey!=null?previousFocusedKey.equals(keyTag)
            :restoreIndex>=0&&child.getTag() instanceof Integer&&((Integer)child.getTag())==restoreIndex){
          target=child;break;
        }
        if(selectedRow==null&&selectedKey!=null&&selectedKey.equals(keyTag))selectedRow=child;
      }
      if(target==null)target=selectedRow;
      if(target==null&&childCount>0)target=list.getChildAt(0);
      if(target!=null)target.requestFocus();
    });
  }
  private void requestLivePage(int target,View control){
    if(!LivePagePolicy.canRequestPage(target,livePageIndex,getCurrentLiveMatchCount(),
        LIVE_PAGE_SIZE,directoryFilterInFlightKey!=null))return;
    if(control!=null)control.requestFocus();
    livePageIndex=target;
    preserveRequestedLivePageOnce=true;
    refreshPage();
  }

  private void showLivePagePicker(){
    if(!startupDataReady||!activityStarted||isFinishing()||isDestroyed())return;
    RemoteChannelOrder order=currentRemoteChannelOrder();
    if(order==null||directoryFilterInFlightKey!=null){
      toast("频道目录正在整理，请稍后选择页码");return;
    }
    final String renderedKey=order.key;
    final int count=getCurrentLiveMatchCount();
    final int pages=LivePagePolicy.pageCount(count,LIVE_PAGE_SIZE);
    if(pages<=1){toast("当前分类只有一页");return;}
    final String[] choices=new String[pages];
    for(int i=0;i<pages;i++)choices[i]="第 "+(i+1)+" 页";
    showHoverChoiceDialog("选择页码 · 共 "+pages+" 页",choices,"取消",
        Math.max(0,Math.min(pages-1,livePageIndex)),which->{
      // Reject a choice belonging to an obsolete category/search/snapshot.
      RemoteChannelOrder latest=currentRemoteChannelOrder();
      if(latest==null||directoryFilterInFlightKey!=null||!renderedKey.equals(latest.key)){
        toast("频道目录已更新，请重新选择页码");return;
      }
      requestLivePage(which,livePageStatus);
    });
  }
  private int getCurrentLiveMatchCount(){
    // The exact count is produced by the background filter and published with the
    // rendered page. Never rescan a large catalogue from a paging-button click.
    return lastFilteredMatchCount;
  }
  private void updateLivePaging(int total){
    int pages=LivePagePolicy.pageCount(total,LIVE_PAGE_SIZE);
    if(livePageIndex>=pages)livePageIndex=pages-1;
    if(livePageStatus!=null){
      livePageStatus.setText((livePageIndex+1)+"/"+pages+" 页 ▾");
      livePageStatus.setContentDescription("第 "+(livePageIndex+1)+" 页，共 "+pages+" 页，点击选择页码");
    }
    // At an actual page boundary, move focus locally BEFORE disabling the
    // focused direction button, rather than letting Android jump to a category.
    boolean previousAvailable=livePageIndex>0,nextAvailable=livePageIndex+1<pages;
    if(livePageStatus!=null&&((!previousAvailable&&livePreviousPageButton!=null&&livePreviousPageButton.hasFocus())
        ||(!nextAvailable&&liveNextPageButton!=null&&liveNextPageButton.hasFocus())))
      livePageStatus.requestFocus();
    if(livePreviousPageButton!=null){
      boolean enabled=previousAvailable;livePreviousPageButton.setEnabled(enabled);
      livePreviousPageButton.setAlpha(enabled?1f:0.42f);
    }
    if(liveNextPageButton!=null){
      boolean enabled=nextAvailable;liveNextPageButton.setEnabled(enabled);
      liveNextPageButton.setAlpha(enabled?1f:0.42f);
    }
  }
  private View findChannelRow(int channelIndex){
    if(channelIndex<0||channelIndex>=channels.size()||list==null)return null;
    String key=channels.get(channelIndex).key();
    for(int i=0;i<list.getChildCount();i++){
      View child=list.getChildAt(i);
      if(key.equals(child.getTag(R.id.tag_channel_key)))return child;
    }
    return null;
  }
  private boolean hasLivePagingFocus(){
    return (livePreviousPageButton!=null&&livePreviousPageButton.hasFocus())
        ||(liveNextPageButton!=null&&liveNextPageButton.hasFocus())
        ||(livePageStatus!=null&&livePageStatus.hasFocus());
  }
  private void focusSelectedChannelInMenu(){
    if(!fullScreenPlayback||fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())return;
    final PopupWindow expectedPopup=fullscreenMenuPopup;
    final long expectedGeneration=catalogueFilterGeneration;
    channelPane.post(()->{
      if(!activityStarted||!fullScreenPlayback||expectedPopup!=fullscreenMenuPopup||!expectedPopup.isShowing()
          ||expectedGeneration!=catalogueFilterGeneration)return;
      View target=findChannelRow(selected);
      if(target==null&&list.getChildCount()>0)target=list.getChildAt(0);
      if(target==null)return;
      final View focusTarget=target;
      channelScroll.post(()->{
        if(!activityStarted||!fullScreenPlayback||expectedPopup!=fullscreenMenuPopup
            ||!expectedPopup.isShowing()||focusTarget.getParent()!=list
            ||expectedGeneration!=catalogueFilterGeneration)return;
        channelScroll.scrollTo(0,Math.max(0,focusTarget.getTop()-dp(72)));
        focusTarget.requestFocus();
      });
    });
  }
  private void chooseFile(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
  // Category entry is a pure Room-cache operation. Only the first complete
  // rebuild and explicit/idle WorkManager jobs perform network discovery.
  private void loadChinaChannels(){
    if(info!=null)info.setText(channels.isEmpty()
        ?"尚未生成完整国内频道快照；首次抓源在后台进行"
        :"已显示保存的国内频道；节目优先，空闲时再更新源");
  }
  private void showChoiceDialog(String title,String[] items,DialogInterface.OnClickListener listener){
    ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,items);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setAdapter(adapter,listener).setNegativeButton("返回",null).create();
    dialog.setOnShowListener(d->{
      ListView choices=dialog.getListView();
      if(choices!=null){
        choices.setFocusable(true);
        choices.setFocusableInTouchMode(true);
        choices.setItemsCanFocus(false);
        choices.setChoiceMode(ListView.CHOICE_MODE_NONE);
        choices.setSelection(0);
        choices.post(()->choices.requestFocus());
      }
    });
    dialog.show();
    if(dialog.getWindow()!=null)dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.72),-2);
  }
  private void showCountryDialog(){
    String[] labels={"美国","加拿大","英国","澳大利亚","日本","韩国","法国","德国","意大利","西班牙","葡萄牙","荷兰","比利时","瑞士","奥地利","瑞典","挪威","芬兰","丹麦","爱尔兰","巴西","墨西哥","阿根廷","智利","印度","新加坡","马来西亚","泰国","印度尼西亚","菲律宾","越南","俄罗斯","土耳其","南非","国际频道"};
    String[] codes={"us","ca","gb","au","jp","kr","fr","de","it","es","pt","nl","be","ch","at","se","no","fi","dk","ie","br","mx","ar","cl","in","sg","my","th","id","ph","vn","ru","tr","za","int"};
    showChoiceDialog("选择国家/地区",labels,(d,w)->{
      if(codes[w].equals("us"))showUsStateDialog();
      else if(codes[w].equals("ca"))showSubdivisionDialog("加拿大",new String[]{"安大略","魁北克","不列颠哥伦比亚","阿尔伯塔","曼尼托巴","萨斯喀彻温","新斯科舍","新不伦瑞克","纽芬兰与拉布拉多","爱德华王子岛"},new String[]{"ca-on","ca-qc","ca-bc","ca-ab","ca-mb","ca-sk","ca-ns","ca-nb","ca-nl","ca-pe"});
      else if(codes[w].equals("au"))showSubdivisionDialog("澳大利亚",new String[]{"新南威尔士","维多利亚","昆士兰","西澳大利亚","南澳大利亚","塔斯马尼亚","首都领地","北领地"},new String[]{"au-nsw","au-vic","au-qld","au-wa","au-sa","au-tas","au-act","au-nt"});
      else if(codes[w].equals("int")){
        // "International" is an umbrella category, not a country feed code.
        // countries/int.m3u does not represent an actual country directory.
        activeGeoScope=null;activeGeoCountry="";activeGeoLabel="";activeCategory="全球频道";refresh();
        if(info!=null)info.setText("已显示已保存的全球频道；请选择具体国家查看地区目录。");
      }else loadGeoPlaylist(labels[w],"https://iptv-org.github.io/iptv/countries/"+codes[w]+".m3u");
    });
  }
  private void showUsStateDialog(){
    String[] names={"亚利桑那","加利福尼亚","内华达","纽约","新泽西","德克萨斯","佛罗里达","华盛顿州","伊利诺伊","马萨诸塞","夏威夷","科罗拉多","俄勒冈","宾夕法尼亚","佐治亚","北卡罗来纳","密歇根","俄亥俄","弗吉尼亚","犹他","新墨西哥","阿拉斯加"};
    String[] codes={"us-az","us-ca","us-nv","us-ny","us-nj","us-tx","us-fl","us-wa","us-il","us-ma","us-hi","us-co","us-or","us-pa","us-ga","us-nc","us-mi","us-oh","us-va","us-ut","us-nm","us-ak"};
    showSubdivisionDialog("美国州",names,codes);
  }
  private void showSubdivisionDialog(String title,String[] names,String[] codes){
    // Before this change, choosing US/Canada/Australia forced a state/province
    // selection and never offered the previously available whole-country list.
    // A missing state-specific feed must not hide all saved country channels.
    final String countryCode=title.startsWith("美国")?"us":
        title.startsWith("加拿大")?"ca":title.startsWith("澳大利亚")?"au":"";
    if(countryCode.isEmpty()){
      showChoiceDialog(title+" · 选择州/省",names,(d,w)->showCityPlaylistDialog(title,names[w],codes[w]));
      return;
    }
    String[] options=new String[names.length+1];
    options[0]="全部"+(countryCode.equals("us")?"美国":countryCode.equals("ca")?"加拿大":"澳大利亚")+"频道";
    System.arraycopy(names,0,options,1,names.length);
    showChoiceDialog(title+" · 选择地区",options,(d,w)->{
      if(w==0)loadGeoPlaylist(options[0],
          "https://iptv-org.github.io/iptv/countries/"+countryCode+".m3u");
      else showCityPlaylistDialog(title,names[w-1],codes[w-1]);
    });
  }
  private void showCityPlaylistDialog(String country,String region,String subdivisionCode){
    String[] data;
    switch(subdivisionCode){
      case "us-az":data=new String[]{"Bullhead City|usifp","Glendale|usgda","Phoenix|usphx","Scottsdale|usstz","Tucson|ustuz","Yuma|usyum"};break;
      case "us-ca":data=new String[]{"Bakersfield|usbfl","Beverly Hills|usbvh","Burbank|usbur","Fresno|usfat","Los Angeles|uslax","Sacramento|ussac","San Diego|ussan","San Francisco|ussfo","San Jose|ussjc","Stockton|ussck"};break;
      case "us-nv":data=new String[]{"Las Vegas|uslas"};break;
      case "us-ny":data=new String[]{"Albany|usalb","New York City|usnyc","Rochester|usroc","Southampton|usssk"};break;
      case "us-nj":data=new String[]{"Newark|usewr","Paterson|uspat","Trenton|usttn"};break;
      case "us-tx":data=new String[]{"Austin|usaus","Dallas|usdal","Houston|ushou","San Antonio|ussat","El Paso|uselp","Fort Worth|usfwt","Waco|usact"};break;
      case "us-fl":data=new String[]{"Jacksonville|usjax","Miami|usmia","Orlando|usorl","Tampa|ustpa","Tallahassee|ustlh","West Palm Beach|uspbi"};break;
      case "us-wa":data=new String[]{"Seattle|ussea","Tacoma|ustiw"};break;
      case "ca-ab":data=new String[]{"Calgary|cacal","Edmonton|caedm"};break;
      case "ca-bc":data=new String[]{"Vancouver|cavan","Victoria|cavic","Langley|calng"};break;
      case "ca-mb":data=new String[]{"Winnipeg|cawnp","Neepawa|canee"};break;
      case "ca-nb":data=new String[]{"Fredericton|cafre","Moncton|camnt"};break;
      case "ca-nl":data=new String[]{"St. John's|casjf"};break;
      case "ca-ns":data=new String[]{"Halifax|cahal"};break;
      case "ca-on":data=new String[]{"Ottawa|caott","Toronto|cator","Windsor|cawnd"};break;
      case "ca-pe":data=new String[]{"Charlottetown|cacha"};break;
      case "ca-qc":data=new String[]{"Montréal|camtr"};break;
      case "au-nsw":data=new String[]{"Sydney|ausyd"};break;
      default:data=new String[0];break;
    }
    String[] choices=new String[data.length+1];choices[0]="整个"+region+"（州/省频道）";
    for(int i=0;i<data.length;i++)choices[i+1]=data[i].split("\\|")[0];
    showChoiceDialog(country+" · "+region+" · 选择城市",choices,(d,w)->{
      if(w==0)loadGeoPlaylist(country+" · "+region,"https://iptv-org.github.io/iptv/subdivisions/"+subdivisionCode+".m3u");
      else{String[] pair=data[w-1].split("\\|");loadGeoPlaylist(country+" · "+region+" · "+pair[0],"https://iptv-org.github.io/iptv/cities/"+pair[1]+".m3u");}
    });
  }
  // Saved country membership is first read from the authoritative Room-backed
  // in-memory snapshot. A country TVG-ID suffix is trustworthy enough to identify
  // the country, while finer province/city filtering requires actual feed membership.
  private Set<String> geoKeysForSnapshot(List<Channel> snapshot,long epoch,String scope,String country){
    SnapshotCategoryIndex.PreparedLocal prepared=preparedLocalSnapshot;
    if(prepared!=null&&prepared.matches(epoch,snapshot.size())){
      Set<String> indexed=prepared.membership.geoKeys(scope);
      if(indexed!=null)return indexed;
    }
    HashSet<String> keys=new HashSet<>();
    for(Channel channel:snapshot){
      if(Thread.currentThread().isInterrupted())break;
      if(channel==null)continue;
      if(channel.sourceScopes!=null&&channel.sourceScopes.contains(scope)){
        keys.add(channel.key());continue;
      }
      if(!country.isEmpty()&&GeoPlaylistCachePolicy.matchesCountryTvgId(channel.tvgId,country))
        keys.add(channel.key());
    }
    return keys;
  }
  private void loadGeoPlaylist(String label,String address){
    String scope=GeoPlaylistCachePolicy.scopeForPlaylist(address);
    if(scope.isEmpty()){if(info!=null)info.setText("地区频道目录地址无效");return;}
    // Select the scope, not a frozen set of old channel keys. The existing
    // filter worker resolves membership from the very same snapshot it renders.
    
    activeGeoScope=scope;activeGeoCountry=GeoPlaylistCachePolicy.countryForPlaylist(address);
    activeGeoLabel=label;activeCategory="全部";favoriteMode=false;livePageIndex=0;
    localRegionFilterActive=false;activeLocalProvince=null;activeLocalCity=null;
    if(info!=null)info.setText("正在读取已保存的 "+label+" 频道…");
    refresh();
  }
  private void loadGlobalChannels(){
    if(info!=null)info.setText("已使用保存的全球频道；浏览时不触发联网更新");
  }
  private void loadRegionChannels(){
    if(info!=null)info.setText("已使用保存的港澳台频道；空闲维护或手动更新后补充");
  }
  private void loadEntertainmentChannels(){
    if(info!=null)info.setText("已使用保存的娱乐频道；不因切换分类而重新抓源");
  }
  private void urlDialog(){EditText e=new EditText(this);e.setSingleLine(true);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);e.setHint("https://网站或 /playlist.m3u");e.setText(getSharedPreferences("mytv",0).getString("last_playlist_url",""));e.setSelection(e.getText().length());new AlertDialog.Builder(this).setTitle("输入网站 / 播放列表入口").setMessage("支持可直接访问的 M3U/M3U8 播放列表网址；普通网页需提供实际播放列表或视频地址。").setView(e).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{String url=e.getText().toString().trim();getSharedPreferences("mytv",0).edit().putString("last_playlist_url",url).apply();download(url);}).show();}
  private void download(String address){
    if(!address.startsWith("https://")&&!address.startsWith("http://")){toast("网址格式不正确");return;}info.setText("正在下载播放列表…");
    worker.execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.2 Android");c.setRequestProperty("Accept","*/*");int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);try(InputStream in=c.getInputStream()){String data=read(in);runOnUiThread(()->finishImport(data));}finally{c.disconnect();}}catch(Exception e){runOnUiThread(()->info.setText("导入失败："+e.getMessage()));}});
  }
  private String read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>50*1024*1024)throw new IOException("播放列表超过 50 MB，已停止导入");b.write(buf,0,n);}return b.toString("UTF-8");}
  private void finishImport(String data){
    if(info!=null)info.setText("正在整理频道数据…");
    // Parsing, de-duplication and JSON persistence can involve tens of thousands
    // of rows. Never run this work on the UI thread: doing so caused long freezes
    // and process kills on phones/low-memory TV boxes.
    worker.execute(()->{
      try{
        int added=parse(data);
        if(!persist())throw new IllegalStateException("频道配置保存失败");

        runOnUiThread(()->{
          if(isFinishing())return;
          refresh();
          if(info!=null)info.setText("导入完成，新增 "+added+" 个频道 · 共 "+channels.size()+" 个频道");
        });
      }catch(Throwable failure){
        android.util.Log.e("MyTVImport","large playlist import failed; restoring last saved catalogue",failure);
        // parse() merges rows into live channel objects. If an unexpected parser,
        // allocation or persistence failure occurs mid-import, reload the last
        // committed Room snapshot so the in-memory catalogue cannot
        // remain half-imported while the next save overwrites the good copy.
        try{restore();}
        catch(Throwable rollbackFailure){
          android.util.Log.e("MyTVImport","catalogue rollback failed",rollbackFailure);
          saveLiveDiagnostic("IMPORT_ROLLBACK_ERROR",rollbackFailure.getClass().getSimpleName()+": "+rollbackFailure.getMessage());
        }
        runOnUiThread(()->{
          if(isFinishing())return;
          refresh();
          if(info!=null)info.setText("频道导入失败，已尝试恢复上次保存的数据："+failure.getClass().getSimpleName());
          toast("导入失败，已恢复上次保存的数据");
        });
      }
    });
  }
  private int parse(String data){
    return parse(data,null,"custom");
  }
  // Destination filtering and source ownership belong to each parse invocation,
  // never to Activity-wide mutable state: imports and refreshes can overlap.
  private int parse(String data,Set<String> destinationKeys){
    return parse(data,destinationKeys,"custom");
  }
  private int parse(String data,Set<String> destinationKeys,String sourceScope){
    if(data==null)return 0;
    final int[] added={0};
    PlaylistParser.parseEach(data,Integer.MAX_VALUE,entry->{
      if(mergePlaylistEntry(entry,destinationKeys,sourceScope))added[0]++;
    });
    return added[0];
  }
  // Reader overload lets remote feeds be merged incrementally. partialAdded is updated
  // after each successful row so useful rows are not lost from the progress report if
  // the remote stream later fails or exceeds the configured byte cap.
  private int parse(Reader reader,Set<String> destinationKeys,String sourceScope,int[] partialAdded)throws IOException{
    final int[] added={0};
    PlaylistParser.parseEach(reader,Integer.MAX_VALUE,entry->{
      if(mergePlaylistEntry(entry,destinationKeys,sourceScope)){
        added[0]++;
        if(partialAdded!=null)partialAdded[0]++;
      }
    });
    return added[0];
  }
  private boolean mergePlaylistEntry(PlaylistParser.ChannelSource entry,Set<String> destinationKeys,String sourceScope){
    String name=entry.name==null?"":entry.name.trim();
    String line=entry.url;
    boolean added=false;
    synchronized(channels){
      if(name.isEmpty())name="频道 "+(channels.size()+1);
      // Global master indexes can include HMT stations; route them into China by metadata.
      String channelScope=RegionCategoryPolicy.catalogueScope(name,entry.group,sourceScope);
      if(!channelsByUrl.containsKey(line)){
        // The URL index already proved this source is new. Compute identity
        // directly from metadata instead of allocating a temporary Channel for
        // every playlist row; this matters for 15k+ entry imports.
        String key=identity(name,entry.group,line,entry.tvgName,entry.tvgId);
        Channel same=channelsByIdentity.get(key);
        if(same!=null){
          same.mergeMetadata(entry.tvgId,entry.tvgName);
          normalizeBroadcasterLabel(same);
          // channelsByUrl.containsKey(line) was false above, so this URL cannot
          // already be present in the channel's backup list.
          same.addBackupSource(line,channelScope);
          channelsByUrl.put(line,same);
          added=true;
        }else{
          Channel fresh=new Channel(name,line,entry.group);
          fresh.mergeMetadata(entry.tvgId,entry.tvgName);
          fresh.addBackupSource(line,channelScope);
          normalizeBroadcasterLabel(fresh);
          channels.add(fresh);
          channelsByIdentity.put(key,fresh);
          channelsByUrl.put(line,fresh);
          added=true;
        }
      }else{
        Channel existing=channelsByUrl.get(line);
        if(existing!=null){
          existing.mergeMetadata(entry.tvgId,entry.tvgName);
          existing.addBackupSource(line,channelScope);
          normalizeBroadcasterLabel(existing);
        }
      }
      // Read the channel index under the same lock as its mutation. Keeping the
      // destination set local prevents an unrelated concurrent import from
      // contaminating a selected country/province result.
      if(destinationKeys!=null){
        Channel scoped=channelsByUrl.get(line);
        if(scoped!=null)destinationKeys.add(scoped.key());
      }
      // Playlist ingestion runs on a separate worker and can mutate the live
      // catalogue before its final persist() call. Invalidate any background
      // catalogue reload immediately, not only after the import has finished.
      synchronized(cataloguePersistLock){catalogueMutationGeneration++;catalogueMembershipGeneration++;}
    }
    return added;
  }
  protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req==10&&result==RESULT_OK&&data!=null){
      Uri u=data.getData();
      worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(u)){
        String content=read(in);runOnUiThread(()->finishImport(content));
      }catch(Exception e){runOnUiThread(()->toast("读取失败"));}});
    }
    if(req==11&&result==RESULT_OK&&data!=null){
      Uri target=data.getData();
      worker.execute(()->{try(OutputStream o=getContentResolver().openOutputStream(target)){
        StringBuilder b=new StringBuilder("#EXTM3U\n");
        synchronized(channels){
          for(Channel c:channels){
            String group=(c.group==null?"":c.group).replace("\"","'");
            String name=c.name.replace("\n"," ").replace("\r"," ").replace("\"","'");
            for(String source:c.backups){
              b.append("#EXTINF:-1 group-title=\"").append(group).append("\",").append(name).append("\n")
                .append(source).append("\n");
            }
          }
        }
        o.write(b.toString().getBytes("UTF-8"));
        runOnUiThread(()->toast("已导出，包含全部备用播放源"));
      }catch(Exception e){runOnUiThread(()->toast("导出失败："+e.getMessage()));}});
    }
  }
  private void exportFile(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_TITLE,"mytv.m3u");startActivityForResult(i,11);}
  private void addDialog(){LinearLayout f=new LinearLayout(this);f.setOrientation(1);EditText n=new EditText(this);n.setHint("频道名称");EditText u=new EditText(this);u.setHint("播放网址（http/https，m3u8 等）");f.addView(n);f.addView(u);new AlertDialog.Builder(this).setTitle("添加自建频道").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String name=n.getText().toString().trim(),url=u.getText().toString().trim();if(!name.isEmpty()&&(url.startsWith("http://")||url.startsWith("https://"))){Channel c=new Channel(name,url,"自建频道");c.addBackupSource(url,"custom");int newIndex;synchronized(channels){channels.add(c);newIndex=channels.size()-1;}rebuildChannelIndexes();persist();activeCategory="自建频道";favoriteMode=false;refresh();play(newIndex);}else toast("请填写频道名称和有效网址");}).show();}
  private void showFullscreenChannelMenu(){
    pauseStableDiscovery();
    if(!activityStarted||isFinishing()||isDestroyed()||!fullScreenPlayback
        ||fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())return;
    clearPendingRemoteZap();
    if(fullscreenAspectMenuItem==null){fullscreenAspectMenuItem=new Button(this);fullscreenAspectMenuItem.setText("画面比例");fullscreenAspectMenuItem.setAllCaps(false);fullscreenAspectMenuItem.setFocusable(true);fullscreenAspectMenuItem.setFocusableInTouchMode(true);styleTvButton(fullscreenAspectMenuItem,Color.rgb(35,55,78));fullscreenAspectMenuItem.setOnClickListener(v->{closeFullscreenChannelMenu();showAspectRatioDialog(video,"live_aspect_mode");});}
    if(fullscreenAspectMenuItem.getParent()==null&&categoryPane!=null)categoryPane.addView(fullscreenAspectMenuItem,1,new LinearLayout.LayoutParams(-1,dp(48)));
    if(categoryPane.getParent()==rootLayout)rootLayout.removeView(categoryPane);
    if(channelPane.getParent()==rootLayout)rootLayout.removeView(channelPane);
    categoryPane.setVisibility(View.VISIBLE);channelPane.setVisibility(View.VISIBLE);
    LinearLayout menu=new LinearLayout(this);menu.setOrientation(0);menu.setBackgroundColor(Color.argb(200,8,13,24));
    // The same panes are reused on the normal screen: tint only while fullscreen.
    categoryPane.setBackgroundColor(Color.argb(205,16,24,40));
    channelPane.setBackgroundColor(Color.argb(205,19,29,47));
    menu.addView(categoryPane,new LinearLayout.LayoutParams(dp(104),-1));
    menu.addView(channelPane,new LinearLayout.LayoutParams(dp(250),-1));
    // These panes are re-parented into a PopupWindow after initial Activity layout.
    // Revalidate descendants at this dynamic boundary, not only during startup.
    normalizeFrameLayoutChildren(menu);
    int screenWidth=getResources().getDisplayMetrics().widthPixels;
    int menuWidth=Math.min(screenWidth,dp(354));
    fullscreenMenuPopup=new PopupWindow(menu,menuWidth,-1,true);
    // 弹出层只占左侧菜单宽度，背景透明；播放器仍在其后持续显示。
    fullscreenMenuPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    fullscreenMenuPopup.setOutsideTouchable(true);
    fullscreenMenuPopup.setOnDismissListener(()->{
      if(categoryPane.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)categoryPane.getParent()).removeView(categoryPane);
      if(channelPane.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)channelPane.getParent()).removeView(channelPane);
      rootLayout.addView(categoryPane,0,new LinearLayout.LayoutParams(dp(104),-1));
      rootLayout.addView(channelPane,1,new LinearLayout.LayoutParams(dp(250),-1));
      // Reparenting changes the measurement path; validate the restored tree too.
      normalizeFrameLayoutChildren(rootLayout);
      categoryPane.setVisibility(View.GONE);channelPane.setVisibility(View.GONE);
      categoryPane.setBackgroundColor(Color.rgb(16,24,40));
      channelPane.setBackgroundColor(Color.rgb(19,29,47));
      fullscreenMenuPopup=null;
      menuSelectedFocusGeneration=-1L;
      if(fullscreenAspectMenuItem!=null&&fullscreenAspectMenuItem.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)fullscreenAspectMenuItem.getParent()).removeView(fullscreenAspectMenuItem);
      if(activityStarted&&!isFinishing()&&!isDestroyed()&&fullScreenPlayback&&isTouchDevice())showFullscreenControls();
      else if(fullscreenMenuButton!=null)fullscreenMenuButton.setVisibility(View.GONE);
      if(!isTouchDevice())restoreFullscreenInputFocus();
    });
    fullscreenMenuPopup.showAtLocation(rootLayout,Gravity.LEFT|Gravity.TOP,0,0);
    fullscreenMenuButton.setVisibility(View.GONE);
    // When zapping crossed a page boundary, render the selected channel's
    // page before focusing using the existing filter worker.
    RemoteChannelOrder order=currentRemoteChannelOrder();
    if(findChannelRow(selected)==null&&order!=null&&order.positions.containsKey(selected)){
      preserveRequestedLivePageOnce=false;
      refresh(true);
      menuSelectedFocusGeneration=catalogueFilterGeneration;
    }else focusSelectedChannelInMenu();
  }
  // VLC hides the Media3 PlayerView. The shared stage is an engine-neutral
  // focus anchor and must remain available during native teardown.
  private void restoreFullscreenInputFocus(){
    if(!activityStarted||!fullScreenPlayback||currentVodPanel!=null
        ||fullscreenRemoteDialogOpen||isAnyPlaybackLockActive()
        ||(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()))return;
    if(video!=null&&video.isShown()&&video.requestFocus())return;
    if(liveStage!=null&&liveStage.isShown())liveStage.requestFocus();
  }
  private boolean isTouchDevice(){
    return getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
  }
  private void closeFullscreenChannelMenu(){
    if(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())fullscreenMenuPopup.dismiss();
  }
  // Runs only while this Activity is foreground. A single daemon samples the
  // UI thread if a posted acknowledgement cannot run within 2.5 seconds.
  // The sampler never waits from the UI thread, never touches a View, and never
  // logs every input event. D6 Android 9 can use this without ExitInfo (API 30).
  private void startMainThreadAnrSampler(){
    if(mainThreadSampleWorker!=null&&mainThreadSampleWorker.isAlive())return;
    final long generation=++mainThreadSampleGeneration;
    Thread watcher=new Thread(()->{
      while(generation==mainThreadSampleGeneration&&!Thread.currentThread().isInterrupted()){
        final java.util.concurrent.CountDownLatch acknowledged=
            new java.util.concurrent.CountDownLatch(1);
        long postedAt=android.os.SystemClock.uptimeMillis();
        Runnable heartbeat=acknowledged::countDown;
        if(!uiHandler.post(heartbeat))break;
        try{
          if(!acknowledged.await(2500L,java.util.concurrent.TimeUnit.MILLISECONDS)
              &&generation==mainThreadSampleGeneration){
            long waited=android.os.SystemClock.uptimeMillis()-postedAt;
            StackTraceElement[] stack=android.os.Looper.getMainLooper()
                .getThread().getStackTrace();
            StringBuilder report=new StringBuilder("waitMs=").append(waited)
                .append(" mainThread=");
            Thread main=android.os.Looper.getMainLooper().getThread();
            report.append(main.getState()).append(" decoderBreadcrumb=").append(lastUiDecoderStage);
            for(int i=0;i<Math.min(18,stack.length);i++)
              report.append("\n#").append(i).append(' ').append(stack[i]);
            String text=report.length()>2800?report.substring(0,2800):report.toString();
            android.util.Log.e("MyTVANR","MAIN_THREAD_BLOCK_STACK "+text);
            // Only one small snapshot is retained. Never store stream URLs or
            // user input contents, and avoid fsync/commit on the blocked UI thread.
            try{
              getApplicationContext().getSharedPreferences("mytv",0)
                  .edit().putString("last_main_thread_block_stack_v1",
                      new java.util.Date().toString()+" | "+text).apply();
            }catch(RuntimeException storageFailure){
              android.util.Log.w("MyTVANR","Could not save main-thread sample",storageFailure);
            }
            // Keep only this heartbeat outstanding until UI recovery or cancellation.
            // A long native freeze must not accumulate more main-thread callbacks.
            while(generation==mainThreadSampleGeneration&&!Thread.currentThread().isInterrupted()
                &&!acknowledged.await(8000L,java.util.concurrent.TimeUnit.MILLISECONDS)){}
          }
          Thread.sleep(750L);
        }catch(InterruptedException interrupted){
          Thread.currentThread().interrupt();
          break;
        }finally{uiHandler.removeCallbacks(heartbeat);}
      }
    },"MyTV-ANR-Sampler");
    watcher.setDaemon(true);
    mainThreadSampleWorker=watcher;
    watcher.start();
  }
  private void stopMainThreadAnrSampler(){
    ++mainThreadSampleGeneration;
    Thread watcher=mainThreadSampleWorker;
    mainThreadSampleWorker=null;
    if(watcher!=null)watcher.interrupt();
  }

  private void startLiveUiStallProbe(){
    if(liveUiStallProbe!=null)return;
    final long[] expectedAt={android.os.SystemClock.uptimeMillis()+1000L};
    liveUiStallProbe=new Runnable(){
      @Override public void run(){
        long now=android.os.SystemClock.uptimeMillis();
        long late=Math.max(0L,now-expectedAt[0]);
        if(late>=3000L&&activityStarted){
          String detail="uiDelayMs="+late+" fullscreen="+fullScreenPlayback
              +" popup="+(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())
              +" remoteDialog="+fullscreenRemoteDialogOpen
              +" category="+activeCategory+" channels="+channels.size();
          android.util.Log.w("MyTVInput",detail);
          // Capture BOTH touch-device catalogue stalls and TV fullscreen stalls.
          // Never save every touch or timer tick, only an observed long delay.
          saveLiveDiagnostic(fullScreenPlayback?"LIVE_UI_DELAY":"CATALOGUE_UI_DELAY",detail);
        }
        expectedAt[0]=now+1000L;
        if(liveUiStallProbe!=null)uiHandler.postDelayed(this,1000L);
      }
    };
    uiHandler.postDelayed(liveUiStallProbe,1000L);
  }
  private void stopLiveUiStallProbe(){
    if(liveUiStallProbe!=null){uiHandler.removeCallbacks(liveUiStallProbe);liveUiStallProbe=null;}
  }
  private void enterFullScreenPlayback(){
    if(fullScreenPlayback||rootLayout==null)return;
    fullScreenPlayback=true;
    // 播放期间阻止系统自动熄屏，避免手机锁屏后直播被系统挂起。
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    // Keep the actual video surface awake as well as the Activity window. This is
    // useful on phone firmware that applies display timeout aggressively.
    video.setKeepScreenOn(true);
    categoryPane.setVisibility(View.GONE);
    channelPane.setVisibility(View.GONE);
    playbackInfo.setVisibility(View.GONE);
    if(categoryPane.getParent()==rootLayout)rootLayout.removeView(categoryPane);
    if(channelPane.getParent()==rootLayout)rootLayout.removeView(channelPane);
    rootLayout.setPadding(0,0,0,0);
    mainPane.setPadding(0,0,0,0);
    mainPane.setLayoutParams(new LinearLayout.LayoutParams(-1,-1));
    // PlayerView is a direct child of liveStage (FrameLayout); always use matching params.
    // LinearLayout.LayoutParams here crashes during FrameLayout measurement on device.
    FrameLayout.LayoutParams videoParams=new FrameLayout.LayoutParams(-1,-1);
    video.setLayoutParams(videoParams);
    fullscreenMenuButton.setVisibility(View.GONE);
    fullscreenSourceButton.setVisibility(View.GONE);
    fullscreenFavoriteButton.setText(isCurrentLiveFavorite()?"★ 已收藏":"☆ 收藏");
    fullscreenFavoriteButton.setVisibility(View.GONE);
    fullscreenActionsButton.setVisibility(View.GONE);
    fullscreenBlockSourceButton.setVisibility(View.GONE);
    fullscreenControlsVisible=false;
    updateLiveChannelPresentation();
    prewarmRemoteChannelOrder();
    if(isTouchDevice())showFullscreenControls();
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    restoreFullscreenInputFocus();
    startLiveUiStallProbe();
  }
  private void exitFullScreenPlayback(){
    if(!fullScreenPlayback||rootLayout==null)return;
    cancelSingleChannelRefresh();
    closeFullscreenChannelMenu();
    fullScreenPlayback=false;
    clearPendingRemoteZap();
    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if(video!=null)video.setKeepScreenOn(false);
    clearLiveOverlayCallbacks();
    if(categoryPane.getParent()!=rootLayout)rootLayout.addView(categoryPane,0,new LinearLayout.LayoutParams(dp(104),-1));
    if(channelPane.getParent()!=rootLayout)rootLayout.addView(channelPane,1,new LinearLayout.LayoutParams(dp(250),-1));
    categoryPane.setVisibility(View.VISIBLE);
    channelPane.setVisibility(View.VISIBLE);
    playbackInfo.setVisibility(View.VISIBLE);
    rootLayout.setPadding(dp(10),dp(8),dp(10),dp(8));
    mainPane.setPadding(dp(10),0,0,0);
    mainPane.setLayoutParams(new LinearLayout.LayoutParams(0,-1,1));
    // Restore the PlayerView using its actual parent type, not LinearLayout params.
    video.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    // Rebuild the list so the currently playing channel keeps its marker and selected tint.
    refresh();
    View restoreFocus=list.getChildAt(0);
    if(selected>=0){
      for(int i=0;i<list.getChildCount();i++){
        View child=list.getChildAt(i);
        if(child.getTag() instanceof Integer && ((Integer)child.getTag())==selected){restoreFocus=child;break;}
      }
    }
    if(restoreFocus!=null){
      final View target=restoreFocus;
      channelScroll.post(()->{
        if(!activityStarted||fullScreenPlayback||isFinishing()||isDestroyed()
            ||!target.isAttachedToWindow()||target.getParent()!=list)return;
        channelScroll.scrollTo(0,Math.max(0,target.getTop()-dp(72)));
        target.requestFocus();
      });
    }
  }
  private void clearLiveOverlayCallbacks(){
    uiHandler.removeCallbacks(hideFullscreenControls);
    uiHandler.removeCallbacks(hideChannelZapOverlay);
    fullscreenControlsVisible=false;fullscreenControlGesture=false;
    recentDepartedLiveSource=null;
    lastOutputMediaId="";
    if(fullscreenBlockSourceButton!=null)fullscreenBlockSourceButton.setOnClickListener(null);
    for(TextView control:new TextView[]{fullscreenMenuButton,fullscreenSourceButton,fullscreenFavoriteButton,fullscreenActionsButton,fullscreenBlockSourceButton}){
      if(control==null)continue;
      control.animate().cancel();control.setAlpha(1f);control.setVisibility(View.GONE);
    }
    if(channelZapOverlay!=null)channelZapOverlay.setVisibility(View.GONE);
  }
  private void showFullscreenControls(){
    if(!activityStarted||isFinishing()||isDestroyed()||!fullScreenPlayback)return;
    if(!fullscreenControlsVisible){
      final Channel shown=selected>=0&&selected<channels.size()?channels.get(selected):null;
      final String station=shown==null?"":identity(shown);
      final String route=shown==null?"":currentSourceUrl();
      fullscreenBlockSourceButton.setEnabled(!SourceSelectionPolicy.channelBlockKey(station,route).isEmpty());
      fullscreenBlockSourceButton.setText(shown==null?"屏蔽广告源":
          "屏蔽广告源\n"+displayChannelName(shown)+" · 第 "+(shown.activeSource+1)+" 源");
      fullscreenBlockSourceButton.setOnClickListener(v->{
        if(!fullscreenControlsVisible||!fullScreenPlayback||fullscreenRemoteDialogOpen
            ||(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()))return;
        hideFullscreenControlsNow();
        blockCapturedLiveSource(station,route);
      });
    }
    fullscreenControlsVisible=true;
    TextView[] controls={fullscreenMenuButton,fullscreenSourceButton,fullscreenFavoriteButton,fullscreenActionsButton,fullscreenBlockSourceButton};
    // Make visible controls hit-testable immediately rather than losing a fast
    // second tap during the fade-in. Keep a complete button gesture together.
    for(TextView control:controls){control.animate().cancel();control.setVisibility(View.VISIBLE);control.setAlpha(1f);}
    scheduleFullscreenControlsHide();
  }
  private void scheduleFullscreenControlsHide(){
    uiHandler.removeCallbacks(hideFullscreenControls);
    if(fullscreenControlGesture||!fullScreenPlayback||!fullscreenControlsVisible)return;
    uiHandler.postDelayed(hideFullscreenControls,3500);
  }
  private void hideFullscreenControlsNow(){
    if(!fullScreenPlayback)return;
    fullscreenControlsVisible=false;fullscreenControlGesture=false;
    TextView[] controls={fullscreenMenuButton,fullscreenSourceButton,fullscreenFavoriteButton,fullscreenActionsButton,fullscreenBlockSourceButton};
    for(TextView control:controls){control.animate().cancel();control.animate().alpha(0f).setDuration(220).withEndAction(()->{if(!fullscreenControlsVisible&&fullScreenPlayback)control.setVisibility(View.GONE);}).start();}
    uiHandler.removeCallbacks(hideFullscreenControls);
  }
  private boolean isCurrentLiveFavorite(){return selected>=0&&selected<channels.size()&&favorites.contains(channels.get(selected).key());}
  // Single Set calls are synchronized, but contains + toggle must be atomic
  // for concurrent remote-order snapshots and catalogue readers.
  private boolean toggleFavoriteMembership(String channelKey){
    return FavoriteMembershipPolicy.toggle(favorites,channelKey);
  }
  // A favorite toggle changes one Room relation, not the channel catalogue.
  // Serialize it with full catalogue writes so a queued older snapshot cannot
  // overwrite the user's latest favorite state.
  private void queueFavoriteMutation(String legacyKey,boolean favorite){
    synchronized(cataloguePersistLock){
      // Favorites change membership only in their uncached, dynamic directory.
      catalogueMutationGeneration++;
      try{
        cataloguePersistWorker.execute(()->{
          try{
            boolean persisted=com.wodedianshi.tv.db.RoomCatalogueMutations.setFavorite(
                getApplicationContext(),legacyKey,favorite);
            if(persisted){
              saveLiveDiagnostic("FAVORITE_ROOM_MUTATION",
                  (favorite?"added ":"removed ")+legacyKey);
            }else{
              // The channel may have disappeared in a new complete Room snapshot.
              // Do not claim success or replay a stale whole-catalogue write.
              saveLiveDiagnostic("FAVORITE_ROOM_MISS",
                  "收藏频道已不在最新 Room 目录，请刷新后重试");
              uiHandler.post(()->{
                if(!isFinishing()&&!isDestroyed())toast("收藏未保存：频道目录已变化，请刷新后重试");
              });
            }
          }catch(Throwable failure){
            android.util.Log.e("MyTVCatalogue","Room favorite mutation failed",failure);
            saveLiveDiagnostic("FAVORITE_ROOM_MUTATION_ERROR",
                failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));
            // Never try a full Room overwrite as a fallback for a single failed
            // favorite update. A background refresh may already own a newer snapshot.
            uiHandler.post(()->{
              if(!isFinishing()&&!isDestroyed())toast("收藏写入失败，请重试");
            });
          }
        });
      }catch(RuntimeException rejected){
        android.util.Log.e("MyTVCatalogue","favorite mutation queue was rejected",rejected);
        saveLiveDiagnostic("FAVORITE_ROOM_QUEUE_ERROR",rejected.getClass().getSimpleName());
        uiHandler.post(()->{
          if(!isFinishing()&&!isDestroyed())toast("收藏保存队列不可用，请重试");
        });
      }
    }
  }
  // Preserve an explicitly chosen URL without copying or rewriting the whole
  // channel catalogue. This shares the serial Room writer queue with favorite
  // mutations, so rapid DPAD/source changes cannot reorder the final user state.
  private void queueActiveSourceMutation(String legacyKey,String sourceUrl){
    if(legacyKey==null||legacyKey.isEmpty()||sourceUrl==null||sourceUrl.isEmpty())return;
    synchronized(cataloguePersistLock){
      // A selected route does not change station category membership.
      catalogueMutationGeneration++;
      try{
        cataloguePersistWorker.execute(()->{
          try{
            boolean changed=com.wodedianshi.tv.db.RoomCatalogueMutations
                .setActiveSourceUrl(getApplicationContext(),legacyKey,sourceUrl);
            if(!changed){
              // The URL may have been removed by a newer background catalogue.
              // Never fall back to a full, potentially stale Room overwrite.
              android.util.Log.w("MyTVCatalogue","selected URL absent in latest Room snapshot");
              saveLiveDiagnostic("SOURCE_SELECTION_ROOM_MISS",
                  "已选择播放源，但最新目录中找不到对应源；保留当前播放，不覆盖频道快照");
            }
          }catch(Throwable failure){
            android.util.Log.e("MyTVCatalogue","Room source selection write failed",failure);
            saveLiveDiagnostic("SOURCE_SELECTION_ROOM_ERROR",
                failure.getClass().getSimpleName());
          }
        });
      }catch(RuntimeException rejected){
        android.util.Log.e("MyTVCatalogue","Room source selection queue rejected",rejected);
        saveLiveDiagnostic("SOURCE_SELECTION_QUEUE_ERROR",rejected.getClass().getSimpleName());
      }
    }
  }

  private void toggleCurrentLiveFavorite(){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    boolean favorite=toggleFavoriteMembership(c.key());
    toast((favorite?"已加入收藏：":"已取消收藏：")+displayChannelName(c));
    queueFavoriteMutation(c.key(),favorite);

    if(fullscreenFavoriteButton!=null)fullscreenFavoriteButton.setText(isCurrentLiveFavorite()?"★ 已收藏":"☆ 收藏");
    if(fullScreenPlayback&&isTouchDevice())showFullscreenControls();
  }
  private void showVodAspectRatioDialog(PlayerView target,String preferenceKey){
    String[] choices={"完整显示（保留比例）","铺满屏幕（边缘可能裁切）","拉伸全屏（可能变形）"};
    int saved=getSharedPreferences("mytv",0).getInt(preferenceKey,0);
    saved=Math.max(0,Math.min(2,saved));
    showHoverChoiceDialog("画面比例",choices,"取消",saved,which->{
      int mode=which==1?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
        which==2?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL:
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
      if(target!=null)target.setResizeMode(mode);
      if(vodFallbackBackend!=null)vodFallbackBackend.setVideoScaleMode(which);
      getSharedPreferences("mytv",0).edit().putInt(preferenceKey,which).apply();
    });
  }
  private void showAspectRatioDialog(PlayerView target,String preferenceKey){
    if(target==null)return;
    String[] choices={"完整显示（保留比例）","铺满屏幕（边缘可能裁切）","拉伸全屏（可能变形）"};
    int saved=getSharedPreferences("mytv",0).getInt(preferenceKey,0);
    saved=Math.max(0,Math.min(2,saved));
    showHoverChoiceDialog("画面比例",choices,"取消",saved,which->{
      int mode=which==1?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
        which==2?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL:
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;
      target.setResizeMode(mode);
      getSharedPreferences("mytv",0).edit().putInt(preferenceKey,which).apply();
      if(fullScreenPlayback&&isTouchDevice())showFullscreenControls();
    });
  }
  private FullscreenMenuConfig loadFullscreenMenuConfig(){
    android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
    String saved=prefs.getString("fullscreen_menu_config_v1",null);
    FullscreenMenuConfig config=saved==null?FullscreenMenuConfig.defaults()
        :FullscreenMenuConfig.decode(saved);
    if(!prefs.getBoolean("fullscreen_single_refresh_migrated",false)){
      config.addSingleChannelRefreshForUpgrade();
      prefs.edit().putString("fullscreen_menu_config_v1",config.encode())
          .putBoolean("fullscreen_single_refresh_migrated",true).apply();
    }else if(saved==null)prefs.edit().putString("fullscreen_menu_config_v1",config.encode()).apply();
    return config;
  }
  private void saveFullscreenMenuConfig(FullscreenMenuConfig config){
    getSharedPreferences("mytv",0).edit()
        .putString("fullscreen_menu_config_v1",config.encode()).apply();
  }
  private void showLivePlaybackActions(){showFullscreenRemoteMenu(false);}
  private void showVodPlaybackActions(){showFullscreenRemoteMenu(true);}
  private void showFullscreenRemoteMenu(boolean vodMode){
    if(!vodMode&&(selected<0||selected>=channels.size())){toast("请先选择频道");return;}
    final Channel menuChannel=vodMode?null:channels.get(selected);
    final String menuStation=menuChannel==null?"":identity(menuChannel);
    final String menuUrl=menuChannel==null?"":currentSourceUrl();
    final DepartedLiveSource departed=vodMode?null:recentDepartedForBlock();
    final FullscreenMenuConfig config=loadFullscreenMenuConfig();
    final Dialog dialog=new Dialog(this);
    LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);
    shell.setPadding(dp(12),dp(10),dp(12),dp(10));shell.setBackgroundColor(Color.argb(208,8,13,24));
    TextView heading=new TextView(this);heading.setText(vodMode?"点播全屏菜单":"直播全屏菜单");heading.setTextColor(Color.WHITE);
    heading.setTextSize(19);heading.setPadding(dp(8),dp(4),dp(8),dp(10));shell.addView(heading);
    final Button quickBlock;
    if(!vodMode){
      quickBlock=new Button(this);quickBlock.setAllCaps(false);
      quickBlock.setText("屏蔽当前广告源 · "+displayChannelName(menuChannel)+" · 第 "+(menuChannel.activeSource+1)+" 源");
      quickBlock.setSingleLine(true);quickBlock.setEllipsize(android.text.TextUtils.TruncateAt.END);
      styleTvButton(quickBlock,Color.rgb(35,55,78));
      quickBlock.setEnabled(!SourceSelectionPolicy.channelBlockKey(menuStation,menuUrl).isEmpty());
      quickBlock.setOnClickListener(v->{dialog.dismiss();blockCapturedLiveSource(menuStation,menuUrl);});
      shell.addView(quickBlock,new LinearLayout.LayoutParams(-1,dp(48)));
    }else quickBlock=null;
    if(departed!=null){
      Button previousBlock=new Button(this);previousBlock.setAllCaps(false);
      previousBlock.setText("屏蔽刚跳过的源 · "+departed.label);
      previousBlock.setSingleLine(true);previousBlock.setEllipsize(android.text.TextUtils.TruncateAt.END);
      styleTvButton(previousBlock,Color.rgb(35,55,78));
      previousBlock.setOnClickListener(v->{dialog.dismiss();blockCapturedLiveSource(departed.station,departed.url);});
      shell.addView(previousBlock,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    LinearLayout columns=new LinearLayout(this);columns.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);
    LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);
    ScrollView leftScroll=new ScrollView(this);leftScroll.addView(left);
    ScrollView rightScroll=new ScrollView(this);rightScroll.addView(right);
    // The phone's landscape viewport may be shorter than the TV menu.
    int columnHeight=vodMode?dp(284):Math.min(dp(284),Math.max(dp(80),
        getResources().getDisplayMetrics().heightPixels-dp(departed==null?224:272)));
    columns.addView(leftScroll,new LinearLayout.LayoutParams(0,columnHeight,1.0f));
    columns.addView(rightScroll,new LinearLayout.LayoutParams(0,columnHeight,1.45f));
    shell.addView(columns);
    Button manage=new Button(this);manage.setText("管理菜单：选择 / 添加 / 排序");manage.setAllCaps(false);
    manage.setOnClickListener(v->{dialog.dismiss();showFullscreenMenuEditor();});
    shell.addView(manage,new LinearLayout.LayoutParams(-1,dp(46)));
    Button close=new Button(this);close.setText("关闭");close.setAllCaps(false);
    close.setOnClickListener(v->dialog.dismiss());
    shell.addView(close,new LinearLayout.LayoutParams(-1,dp(42)));
    final int[] selectedCategory={0};
    final ArrayList<Button> leftCategoryButtons=new ArrayList<>();
    final ArrayList<Button> rightActionButtons=new ArrayList<>();
    Runnable[] renderRight=new Runnable[1];
    renderRight[0]=()->{
      right.removeAllViews();rightActionButtons.clear();
      List<FullscreenMenuConfig.Category> categories=new ArrayList<>();
      for(FullscreenMenuConfig.Category candidate:config.getCategories())
        if(!vodMode||candidate.id.equals("vod"))categories.add(candidate);
      if(categories.isEmpty())return;
      int index=Math.max(0,Math.min(selectedCategory[0],categories.size()-1));
      FullscreenMenuConfig.Category category=categories.get(index);
      TextView subheading=new TextView(this);subheading.setText(category.title);
      subheading.setTextColor(Color.rgb(255,205,95));subheading.setTextSize(16);
      subheading.setPadding(dp(10),dp(7),dp(6),dp(7));right.addView(subheading);
      for(FullscreenMenuConfig.Item item:category.getItems()){
        Button action=new Button(this);action.setText(item.title);action.setAllCaps(false);
        action.setTextColor(Color.WHITE);action.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        action.setPadding(dp(12),0,dp(8),0);styleTvButton(action,Color.rgb(20,34,54));
        action.setOnKeyListener((view,keyCode,event)->{
          if(event.getAction()==KeyEvent.ACTION_DOWN&&keyCode==KeyEvent.KEYCODE_DPAD_LEFT){
            int leftIndex=Math.max(0,Math.min(selectedCategory[0],leftCategoryButtons.size()-1));
            if(leftIndex<leftCategoryButtons.size())leftCategoryButtons.get(leftIndex).requestFocus();
            return true;
          }
          return false;
        });
        rightActionButtons.add(action);
        action.setOnClickListener(v->{
          String id=item.id;dialog.dismiss();
          if("favorite".equals(id)){if(vodMode)toast("收藏频道请返回直播后操作");else toggleCurrentLiveFavorite();}
          else if("sources".equals(id)){if(vodMode)toast("备用直播源请返回直播后操作");else showSourcePicker();}
          else if("channel_list".equals(id)||"category".equals(id)){if(vodMode)vodDialog();else showFullscreenChannelMenu();}
          else if("aspect".equals(id)){if(vodMode)toast("画面比例设置请在直播菜单中调整");else showAspectRatioDialog(video,"live_aspect_mode");}
          else if("decoder".equals(id))decoderSettingsDialog();
          else if("refresh_channel".equals(id)){if(!vodMode)requestSingleChannelRefresh();}
          else if("refresh".equals(id))startManualSourceUpdate();
          else if("vod_catalogue".equals(id)){if(vodMode&&backVodToCatalogueAction!=null)backVodToCatalogueAction.run();else vodDialog();}
          else if("vod_controls".equals(id)){if(revealVodControlsAction!=null)revealVodControlsAction.run();else toast("点播控制暂不可用");}
          else toast("此快捷项目尚未绑定操作："+item.title);
        });
        right.addView(action,new LinearLayout.LayoutParams(-1,dp(48)));
      }
      if(category.getItems().isEmpty()){
        TextView empty=new TextView(this);empty.setText("此主项目还没有子项目");
        empty.setTextColor(Color.LTGRAY);empty.setPadding(dp(10),dp(12),dp(6),dp(12));right.addView(empty);
      }
    };
    List<FullscreenMenuConfig.Category> visibleCategories=new ArrayList<>();
    for(FullscreenMenuConfig.Category candidate:config.getCategories())
      if(!vodMode||candidate.id.equals("vod"))visibleCategories.add(candidate);
    for(int i=0;i<visibleCategories.size();i++){
      final int index=i;FullscreenMenuConfig.Category category=visibleCategories.get(i);
      Button button=new Button(this);button.setText(category.title);button.setAllCaps(false);
      button.setTextColor(Color.WHITE);button.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
      button.setPadding(dp(8),0,dp(4),0);
      styleTvButton(button,i==0?Color.rgb(35,91,145):Color.rgb(18,27,43));
      button.setOnKeyListener((view,keyCode,event)->{
        if(event.getAction()==KeyEvent.ACTION_DOWN&&keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){
          if(!rightActionButtons.isEmpty())rightActionButtons.get(0).requestFocus();
          return true;
        }
        return false;
      });
      leftCategoryButtons.add(button);
      button.setOnClickListener(v->{
        selectedCategory[0]=index;
        for(int n=0;n<left.getChildCount();n++){
          View child=left.getChildAt(n);
          if(child instanceof Button)styleTvButton((Button)child,n==index?Color.rgb(35,91,145):Color.rgb(18,27,43));
        }
        renderRight[0].run();
      });
      left.addView(button,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    renderRight[0].run();
    dialog.setContentView(shell);
    Window window=dialog.getWindow();
    if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(520)),
          WindowManager.LayoutParams.WRAP_CONTENT);}
    clearPendingRemoteZap();
    fullscreenRemoteDialogOpen=true;
    dialog.setOnDismissListener(d->fullscreenRemoteDialogOpen=false);
    dialog.show();
    if(dialog.getWindow()!=null){
      dialog.getWindow().setDimAmount(0.12f);
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      dialog.getWindow().setLayout(
          Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(520)),
          WindowManager.LayoutParams.WRAP_CONTENT);
    }
    if(quickBlock!=null&&quickBlock.isEnabled())quickBlock.requestFocus();
    else if(left.getChildCount()>0)left.getChildAt(0).requestFocus();
  }
  private void showFullscreenMenuEditor(){
    final FullscreenMenuConfig config=loadFullscreenMenuConfig();
    final ArrayList<String> ids=new ArrayList<>();
    final ArrayList<String> labels=new ArrayList<>();
    for(FullscreenMenuConfig.Category category:config.getCategories()){
      ids.add(category.id);labels.add(category.title+"（"+category.getItems().size()+"项）");
    }
    String[] choices=labels.toArray(new String[0]);
    new AlertDialog.Builder(this).setTitle("全屏菜单管理")
      .setItems(choices,(d,which)->showFullscreenMenuCategoryEditor(config,ids.get(which)))
      .setPositiveButton("添加快捷项目",(d,w)->showFullscreenMenuAddChoice(config)).setNeutralButton("恢复默认",(d,w)->{
        saveFullscreenMenuConfig(FullscreenMenuConfig.defaults());toast("已恢复默认菜单");
      }).setNegativeButton("关闭",null).show();
  }
  private void showFullscreenMenuAddChoice(FullscreenMenuConfig config){
    // TV remotes should choose an existing action rather than open the keyboard
    // just to add a working fullscreen menu entry.
    final String[] names={"收藏当前频道","选择备用播放源","打开频道列表",
        "画面比例","解码与声音设置","频道分类","更新频道目录","刷新当前频道",
        "播放控制","返回点播目录","新建自定义主项目…"};
    final String[] ids={"favorite","sources","channel_list","aspect","decoder",
        "category","refresh","refresh_channel","vod_controls","vod_catalogue"};
    new AlertDialog.Builder(this).setTitle("选择要添加的菜单功能")
        .setItems(names,(actionDialog,actionIndex)->{
          if(actionIndex==ids.length){
            EditText input=new EditText(this);input.setHint("输入自定义主项目名称");
            new AlertDialog.Builder(this).setTitle("新建自定义主项目").setView(input)
                .setNegativeButton("取消",null).setPositiveButton("创建",(d,w)->{
                  String id="custom_"+UUID.randomUUID().toString().replace("-","");
                  if(config.addCategory(id,input.getText().toString().trim())){
                    saveFullscreenMenuConfig(config);toast("自定义主项目已创建");
                  }else toast("名称无效或主项目数量已达上限");
                }).show();
            return;
          }
          final ArrayList<FullscreenMenuConfig.Category> destinations=new ArrayList<>();
          final ArrayList<String> destinationLabels=new ArrayList<>();
          for(FullscreenMenuConfig.Category category:config.getCategories()){
            // Point VOD controls to the VOD section and live functions to a
            // live section, to avoid adding an invisible or unusable action.
            boolean vodAction=ids[actionIndex].startsWith("vod_");
            if(vodAction!=category.id.equals("vod"))continue;
            destinations.add(category);
            destinationLabels.add(category.title);
          }
          if(destinations.isEmpty()){toast("没有可添加该功能的菜单分类");return;}
          new AlertDialog.Builder(this).setTitle("选择放入哪个菜单")
              .setItems(destinationLabels.toArray(new String[0]),(categoryDialog,categoryIndex)->{
                String categoryId=destinations.get(categoryIndex).id;
                if(config.addItem(categoryId,ids[actionIndex],names[actionIndex])){
                  saveFullscreenMenuConfig(config);
                  toast("已添加："+names[actionIndex]);
                }else toast("此功能已经添加，或菜单项目已达上限");
              }).setNegativeButton("取消",null).show();
        }).setNegativeButton("取消",null).show();
  }
  private void showFullscreenMenuCategoryEditor(FullscreenMenuConfig config,String categoryId){
    FullscreenMenuConfig.Category category=config.findCategory(categoryId);if(category==null)return;
    String[] actions={"添加子项目","重命名主项目","删除主项目","主项目上移","主项目下移","管理已有子项目"};
    new AlertDialog.Builder(this).setTitle(category.title+" · 编辑")
      .setItems(actions,(d,which)->{
        if(which==0){
          String[] names={"收藏当前频道","选择备用播放源","打开频道列表","画面比例","解码与声音设置","频道分类","更新频道目录","返回点播目录"};
          String[] actionIds={"favorite","sources","channel_list","aspect","decoder","category","refresh","vod_catalogue"};
          new AlertDialog.Builder(this).setTitle("添加子项目").setItems(names,(x,n)->{
            if(config.addItem(categoryId,actionIds[n],names[n])){saveFullscreenMenuConfig(config);toast("子项目已添加");}
            else toast("添加失败：项目已存在或数量已达上限");
          }).show();
        }else if(which==1){
          EditText input=new EditText(this);input.setText(category.title);
          new AlertDialog.Builder(this).setTitle("重命名主项目").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("保存",(x,y)->{
              if(config.renameCategory(categoryId,input.getText().toString()))saveFullscreenMenuConfig(config);
              else toast("名称无效");
            }).show();
        }else if(which==2){
          new AlertDialog.Builder(this).setTitle("删除主项目？")
            .setMessage("该主项目及其子项目将从快捷菜单移除。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->{
              config.removeCategory(categoryId);saveFullscreenMenuConfig(config);
            }).show();
        }else if(which==3||which==4){
          int index=0;for(int i=0;i<config.getCategories().size();i++)
            if(config.getCategories().get(i).id.equals(categoryId)){index=i;break;}
          if(config.moveCategory(categoryId,index+(which==3?-1:1)))saveFullscreenMenuConfig(config);
          else toast("已经到达边界");
        }else showFullscreenMenuItemEditor(config,categoryId);
      }).show();
  }
  private void showFullscreenMenuItemEditor(FullscreenMenuConfig config,String categoryId){
    FullscreenMenuConfig.Category category=config.findCategory(categoryId);if(category==null)return;
    List<FullscreenMenuConfig.Item> items=category.getItems();
    if(items.isEmpty()){toast("此主项目没有子项目");return;}
    String[] labels=new String[items.size()];for(int i=0;i<items.size();i++)labels[i]=items.get(i).title;
    new AlertDialog.Builder(this).setTitle("选择要编辑的子项目").setItems(labels,(d,which)->{
      String itemId=items.get(which).id;
      String[] actions={"重命名","删除","上移","下移"};
      new AlertDialog.Builder(this).setTitle(items.get(which).title).setItems(actions,(x,action)->{
        if(action==0){
          EditText input=new EditText(this);input.setText(items.get(which).title);
          new AlertDialog.Builder(this).setTitle("重命名子项目").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("保存",(a,b)->{
              if(config.renameItem(categoryId,itemId,input.getText().toString()))saveFullscreenMenuConfig(config);
              else toast("名称无效");
            }).show();
        }else if(action==1){config.removeItem(categoryId,itemId);saveFullscreenMenuConfig(config);}
        else if(config.moveItem(categoryId,itemId,which+(action==2?-1:1)))saveFullscreenMenuConfig(config);
        else toast("已经到达边界");
      }).show();
    }).show();
  }
  // 全屏播放时的遥控器快捷键；频道菜单打开后，方向键和确认键交给菜单控件。
  @Override public boolean dispatchTouchEvent(MotionEvent event){
    if(event!=null&&event.getActionMasked()==MotionEvent.ACTION_DOWN)pauseStableDiscovery();
    // MotionEvent timestamps are monotonic uptime. Measure dispatch latency for
    // phones and mice without adding per-touch disk writes or expensive work.
    if(event!=null&&event.getActionMasked()==MotionEvent.ACTION_DOWN){
      long deliveryMs=Math.max(0L,android.os.SystemClock.uptimeMillis()-event.getEventTime());
      if(deliveryMs>=500L){
        String detail="touchDeliveryMs="+deliveryMs+" fullscreen="+fullScreenPlayback
            +" category="+activeCategory;
        android.util.Log.w("MyTVInput",detail);
        if(deliveryMs>=1500L)saveLiveDiagnostic("TOUCH_DELIVERY_DELAY",detail);
      }
    }
    return super.dispatchTouchEvent(event);
  }
  @Override public boolean dispatchKeyEvent(KeyEvent event){
    if(event.getAction()==KeyEvent.ACTION_DOWN)pauseStableDiscovery();
    if(fullScreenPlayback&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
      int receivedKey=event.getKeyCode();
      if(receivedKey!=KeyEvent.KEYCODE_VOLUME_UP&&receivedKey!=KeyEvent.KEYCODE_VOLUME_DOWN
          &&receivedKey!=KeyEvent.KEYCODE_VOLUME_MUTE)
        android.util.Log.i("MyTVInput","live_key="+receivedKey
            +" popup="+(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())
            +" remoteDialog="+fullscreenRemoteDialogOpen);
    }
    // While the parental overlay is active, consume all keys delivered to this app window.
    // Android reserves HOME for the system; ordinary apps cannot reliably intercept it.
    if(remoteParentLockManager!=null&&remoteParentLockManager.isLocked())return true;
    // Android tracks Back from down to uncancelled up. Do not change the live
    // page on down or pass the same key to PlayerView: one gesture, one action.
    // A focusable PopupWindow handles its own Back gesture and dismisses only itself.
    if(event.getKeyCode()==KeyEvent.KEYCODE_BACK)return super.dispatchKeyEvent(event);
    if(fullScreenPlayback&&!fullscreenRemoteDialogOpen&&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())){
      int key=event.getKeyCode();
      if(key==KeyEvent.KEYCODE_MENU||key==KeyEvent.KEYCODE_SETTINGS){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)showLivePlaybackActions();
        return true;
      }
      boolean ok=key==KeyEvent.KEYCODE_DPAD_CENTER
          ||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER;
      boolean channelUp=key==KeyEvent.KEYCODE_CHANNEL_UP;
      boolean channelDown=key==KeyEvent.KEYCODE_CHANNEL_DOWN;
      boolean direction=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN
          ||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
      if(ok||direction||channelUp||channelDown){
        if(event.getAction()==KeyEvent.ACTION_DOWN){
          boolean zapKey=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN
              ||channelUp||channelDown;
          long now=android.os.SystemClock.elapsedRealtime();
          // Android TV remotes send ACTION_DOWN repeats while a key is held.
          // Accept them at a controlled cadence instead of silently ignoring
          // every repeat, which made press-and-hold look like a dead remote.
          boolean acceptRepeat=zapKey&&event.getRepeatCount()>0
              &&now-lastChannelZapKeyAt>=300L;
          if(event.getRepeatCount()==0||acceptRepeat){
            if(ok)showFullscreenChannelMenu();
            else if(key==KeyEvent.KEYCODE_DPAD_UP||channelUp){
              lastChannelZapKeyAt=now;stepChannel(-1);
            }else if(key==KeyEvent.KEYCODE_DPAD_DOWN||channelDown){
              lastChannelZapKeyAt=now;stepChannel(1);
            }else if(event.getRepeatCount()==0&&key==KeyEvent.KEYCODE_DPAD_LEFT)stepSource(-1);
            else if(event.getRepeatCount()==0&&key==KeyEvent.KEYCODE_DPAD_RIGHT)stepSource(1);
          }
        }
        return true;
      }
    }
    if(fullScreenPlayback&&!fullscreenRemoteDialogOpen&&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())
        &&video!=null&&video.dispatchKeyEvent(event))return true;
    return super.dispatchKeyEvent(event);
  }
  private String remoteChannelOrderKey(String category,String query,boolean favoritesOnly,
      long filterGeneration,long mutationGeneration,int channelCount,int favoritesHash){
    // The channel array may be rebuilt without changing its size.
    // Include mutation epoch so old positional zapping indexes cannot be reused.
    return filterGeneration+"|"+mutationGeneration+"|"+channelCount+"|"+favoritesHash+"|"+favoritesOnly+"|"
        +(category==null?"全部":category)+"|"+(query==null?"":query);
  }
  private void prewarmRemoteChannelOrder(){
    String key=currentDirectoryOrderKey();
    RemoteChannelOrder cached=cachedRemoteChannelOrder;
    if((cached!=null&&key.equals(cached.key))||key.equals(directoryFilterInFlightKey))return;
    refresh(true);
  }
  private void clearPendingRemoteSource(){
    if(pendingRemoteSourceDispatch!=null)uiHandler.removeCallbacks(pendingRemoteSourceDispatch);
    pendingRemoteSourceDispatch=null;pendingRemoteSourceChannel=null;pendingRemoteSourceUrl=null;
  }
  private void clearPendingRemoteZap(){
    clearPendingRemoteSource();clearPendingRemoteChannel();
  }
  private void clearPendingRemoteChannel(){
    if(pendingRemoteZapDispatch!=null){uiHandler.removeCallbacks(pendingRemoteZapDispatch);pendingRemoteZapDispatch=null;}
    pendingRemoteZapSteps=0;
    pendingRemoteZapGeneration=-1L;
  }
  private String currentDirectoryOrderKey(){
    String category=activeCategory==null?"全部":activeCategory;
    String query=search==null?"":RemoteChannelOrderPolicy.normalizeQuery(search.getText().toString());
    int count; synchronized(channels){count=channels.size();}
    int favoritesHash=0;
    if(favoriteMode){synchronized(favorites){favoritesHash=favorites.hashCode();}}
    return remoteChannelOrderKey(category,query,favoriteMode,
        catalogueFilterGeneration,catalogueMutationGeneration,count,favoritesHash);
  }
  private RemoteChannelOrder currentRemoteChannelOrder(){
    RemoteChannelOrder cached=cachedRemoteChannelOrder;
    return cached!=null&&currentDirectoryOrderKey().equals(cached.key)?cached:null;
  }
  private void applyChannelZap(RemoteChannelOrder order,int delta){
    if(order==null||order.indexes.isEmpty()){toast("当前栏目没有可切换的频道");return;}
    Integer current=order.positions.get(selected);
    int currentPosition=current==null?-1:current;
    if(delta==0)return;
    int nextPosition=currentPosition<0?SourceSelectionPolicy.wrapIndex(
        delta>0?delta-1:delta,order.indexes.size())
        :SourceSelectionPolicy.wrapIndex(currentPosition+delta,order.indexes.size());
    play(order.indexes.get(nextPosition));
    showChannelZapOverlay();
  }
  private boolean canApplyRemoteZap(){
    return RemoteChannelOrderPolicy.canApplyQueuedZap(
        activityStarted&&!isFinishing()&&!isDestroyed(),fullScreenPlayback,
        fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing(),fullscreenRemoteDialogOpen,
        currentVodPanel!=null,isAnyPlaybackLockActive());
  }
  private void stepChannel(int delta){
    if(!canApplyRemoteZap())return;
    clearPendingRemoteSource();
    long started=android.os.SystemClock.elapsedRealtime();
    long generation=catalogueFilterGeneration;
    // Keep accepted steps within the same user-selected filter. A catalogue
    // replacement can rebase them onto a new complete order, not a stale index.
    if(pendingRemoteZapGeneration!=generation)clearPendingRemoteZap();
    int steps=RemoteChannelOrderPolicy.mergeZapSteps(pendingRemoteZapSteps,delta);
    RemoteChannelOrder order=currentRemoteChannelOrder();
    if(order==null){
      pendingRemoteZapSteps=steps;
      pendingRemoteZapGeneration=generation;
      if(steps!=0)prewarmRemoteChannelOrder();
      android.util.Log.i("MyTVZap","channel_order_cache_miss_async total_ms="
          +(android.os.SystemClock.elapsedRealtime()-started));
      return;
    }
    pendingRemoteZapSteps=steps;
    pendingRemoteZapGeneration=generation;
    if(steps==0){clearPendingRemoteZap();return;}
    if(pendingRemoteZapDispatch!=null)return;
    pendingRemoteZapDispatch=()->{
      pendingRemoteZapDispatch=null;
      if(!canApplyRemoteZap()||!RemoteChannelOrderPolicy.canDispatchQueuedZap(false,
          pendingRemoteZapGeneration,catalogueFilterGeneration,pendingRemoteZapSteps)){
        clearPendingRemoteZap();return;
      }
      RemoteChannelOrder current=currentRemoteChannelOrder();
      if(current==null){prewarmRemoteChannelOrder();return;}
      int accumulated=pendingRemoteZapSteps;
      clearPendingRemoteZap();
      applyChannelZap(current,accumulated);
      android.util.Log.i("MyTVZap","channel_key_to_tune_ms="
          +(android.os.SystemClock.elapsedRealtime()-started)+" burst_steps="+accumulated
          +" total="+current.indexes.size());
    };
    uiHandler.postDelayed(pendingRemoteZapDispatch,RemoteChannelOrderPolicy.ZAP_BURST_WINDOW_MS);
  }
  private void showChannelZapOverlay(){
    if(selected<0||selected>=channels.size())return;
    showChannelZapMessage(displayChannelName(channels.get(selected)));
  }
  private void showChannelZapMessage(String message){
    if(!activityStarted||isFinishing()||isDestroyed()||channelZapOverlay==null)return;
    uiHandler.removeCallbacks(hideChannelZapOverlay);
    channelZapOverlay.setText(message);
    channelZapOverlay.setVisibility(View.VISIBLE);
    uiHandler.postDelayed(hideChannelZapOverlay,1800L);
  }
  // User preference only: do not alter network QoE or delete catalogue rows.
  private Set<String> sourceBlockRecords(){
    return getSharedPreferences("mytv",0).getStringSet("blocked_ad_sources",Collections.emptySet());
  }
  private boolean isSourceBlocked(Channel channel,String url){
    return SourceSelectionPolicy.isUserBlocked(sourceBlockRecords(),identity(channel),url);
  }
  private Set<String> blockedSourceUrls(Channel channel){
    return SourceSelectionPolicy.blockedUrls(sourceBlockRecords(),identity(channel),channel.backups);
  }
  private void setSourceBlocked(String stationIdentity,String url,boolean blocked){
    String key=SourceSelectionPolicy.channelBlockKey(stationIdentity,url);
    if(key.isEmpty())return;
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    HashSet<String> records=new HashSet<>(sourceBlockRecords());
    if(blocked)records.add(key);
    else{records.remove(key);records.remove(url);}
    p.edit().putStringSet("blocked_ad_sources",records).apply();
  }
  // Only one previously output-capable, automatically departed route is retained.
  // No guess from the current source index, and no history of failed blank attempts.
  private static final class DepartedLiveSource {
    final String station,url,label;
    final long departedAt;
    DepartedLiveSource(String station,String url,String label,long departedAt){
      this.station=station;this.url=url;this.label=label;this.departedAt=departedAt;
    }
  }
  private void rememberDepartedLiveSource(Channel c){
    if(currentMediaId.isEmpty()||!currentMediaId.equals(lastOutputMediaId))return;
    String url=currentSourceUrl(),station=identity(c);
    if(SourceSelectionPolicy.channelBlockKey(station,url).isEmpty())return;
    recentDepartedLiveSource=new DepartedLiveSource(station,url,
        displayChannelName(c)+" · 第 "+(c.activeSource+1)+" 源",
        android.os.SystemClock.elapsedRealtime());
  }
  private DepartedLiveSource recentDepartedForBlock(){
    DepartedLiveSource previous=recentDepartedLiveSource;
    if(previous==null)return null;
    long age=android.os.SystemClock.elapsedRealtime()-previous.departedAt;
    if(age<0L||age>30000L){recentDepartedLiveSource=null;return null;}
    if(SourceSelectionPolicy.isUserBlocked(sourceBlockRecords(),previous.station,previous.url))return null;
    Channel current=selected>=0&&selected<channels.size()?channels.get(selected):null;
    if(current!=null&&SourceSelectionPolicy.channelBlockKey(previous.station,previous.url).equals(
        SourceSelectionPolicy.channelBlockKey(identity(current),currentSourceUrl())))return null;
    return previous;
  }
  // The user acts on the station/route displayed when the menu opened, not
  // the numeric source index or the next route selected by automatic recovery.
  private void blockCapturedLiveSource(String stationIdentity,String url){
    if(!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive()||currentVodPanel!=null)return;
    String capturedKey=SourceSelectionPolicy.channelBlockKey(stationIdentity,url);
    if(capturedKey.isEmpty()){toast("没有可屏蔽的播放源");return;}
    setSourceBlocked(stationIdentity,url,true);
    saveLiveDiagnostic("SOURCE_USER_BLOCK","station="+StableKey.channelIdentity(stationIdentity).substring(0,19)
        +" route="+StableKey.url(SourceSelectionPolicy.endpointKey(url)).substring(0,19));
    Channel current=selected>=0&&selected<channels.size()?channels.get(selected):null;
    if(current==null||!capturedKey.equals(SourceSelectionPolicy.channelBlockKey(identity(current),currentSourceUrl()))){
      toast("已屏蔽刚才的广告源，当前播放不变");
      return;
    }
    clearPendingRemoteZap();
    recentDepartedLiveSource=null;
    resetLiveRecoveryBudget();
    manualSourceSwitch=false;
    if(current.backups==null||current.backups.isEmpty()){
      stopFailedLiveSession("已屏蔽此源，本频道没有其他未屏蔽源");
      return;
    }
    // Blocking is explicit, but the replacement route is an automatic choice.
    // Reuse normal ranked admission; do not persist a guessed manual selection.
    if(!prepareAutomaticChannelSource(current))return;
    toast("已屏蔽广告源，正在选择优质备用源");
    startCurrentSource();
  }
  private static final class PendingSingleChannelRefresh {
    final Channel channel;
    final String key,group,tvgId,tvgName,activeUrl;
    final long tuneSerial,waitUntil;
    volatile boolean cancelled;
    PendingSingleChannelRefresh(Channel channel,String activeUrl,long tuneSerial,long waitUntil){
      this.channel=channel;this.key=channel.key();this.group=channel.group;
      this.tvgId=channel.tvgId;this.tvgName=channel.tvgName;
      this.activeUrl=activeUrl;this.tuneSerial=tuneSerial;this.waitUntil=waitUntil;
    }
  }
  private PendingSingleChannelRefresh pendingSingleChannelRefresh;
  private void cancelSingleChannelRefresh(){
    PendingSingleChannelRefresh request=pendingSingleChannelRefresh;
    if(request!=null){request.cancelled=true;pendingSingleChannelRefresh=null;}
  }
  private boolean singleChannelRequestCurrent(PendingSingleChannelRefresh request){
    return pendingSingleChannelRefresh==request&&!request.cancelled&&activityStarted
        &&!isFinishing()&&!isDestroyed()&&fullScreenPlayback&&currentVodPanel==null
        &&!isAnyPlaybackLockActive()&&selected>=0&&selected<channels.size()
        &&channels.get(selected)==request.channel&&liveTuneRequestSerial==request.tuneSerial
        &&request.activeUrl.equals(currentSourceUrl());
  }
  private void requestSingleChannelRefresh(){
    if(!activityStarted||!catalogueSnapshotTrusted||!fullScreenPlayback||selected<0
        ||selected>=channels.size()||currentVodPanel!=null||isAnyPlaybackLockActive()){
      toast("当前频道尚未准备好，暂不能刷新");return;
    }
    if(pendingSingleChannelRefresh!=null){toast("已有频道刷新任务，请勿重复提交");return;}
    Channel target=channels.get(selected);
    String active=currentSourceUrl();
    if(active.isEmpty()){toast("当前没有播放线路");return;}
    hideFullscreenControlsNow();
    PendingSingleChannelRefresh request=new PendingSingleChannelRefresh(target,active,
        liveTuneRequestSerial,android.os.SystemClock.elapsedRealtime()+90000L);
    pendingSingleChannelRefresh=request;
    toast("已申请刷新："+displayChannelName(target)+"，保持当前播放，待安全时检测");
    uiHandler.postDelayed(()->pollSingleChannelRefresh(request),1000L);
  }
  private void pollSingleChannelRefresh(PendingSingleChannelRefresh request){
    if(!singleChannelRequestCurrent(request)){
      if(pendingSingleChannelRefresh==request)cancelSingleChannelRefresh();
      return;
    }
    long now=android.os.SystemClock.elapsedRealtime();
    if(SingleChannelRefreshPolicy.requestExpired(now,request.waitUntil)){
      cancelSingleChannelRefresh();toast("等待安全刷新超时，原线路未改变");return;
    }
    if(!hasWindowFocus()||fullscreenControlsVisible||fullscreenRemoteDialogOpen
        ||(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())
        ||(SourceUpdateActivityPolicy.isPlaybackActive()
            &&!SourceUpdateActivityPolicy.permitsInitialPlaybackWork(now))){
      uiHandler.postDelayed(()->pollSingleChannelRefresh(request),2000L);return;
    }
    final long admittedPressure=SourceUpdateActivityPolicy.pressureGeneration();
    try{
      worker.execute(()->{
        SourceUpdateWorker.SingleRefreshResult result=null;
        Exception failure=null;
        try{
          java.util.function.BooleanSupplier cancelled=()->SingleChannelRefreshPolicy.shouldCancelRequest(
              request.cancelled,admittedPressure,SourceUpdateActivityPolicy.pressureGeneration(),
              android.os.SystemClock.elapsedRealtime(),request.waitUntil);
          SourceProbeTimePolicy.checkPaused(cancelled);
          result=SourceUpdateWorker.refreshSingleChannelForVisibleChannel(getApplicationContext(),
              request.key,request.group,request.tvgId,request.tvgName,request.activeUrl,cancelled);
        }catch(Exception error){failure=error;}
        final SourceUpdateWorker.SingleRefreshResult outcome=result;
        final Exception error=failure;
        uiHandler.post(()->finishSingleChannelRefresh(request,outcome,error));
      });
    }catch(RuntimeException rejected){
      cancelSingleChannelRefresh();toast("刷新任务暂不可用");
    }
  }
  private void finishSingleChannelRefresh(PendingSingleChannelRefresh request,
      SourceUpdateWorker.SingleRefreshResult outcome,Exception error){
    // Even if the user leaves the channel just after the durable commit, the
    // new Room version must be read on the next idle reload. Do not claim that
    // the complete in-memory catalogue now matches the new checkpoint.
    if(outcome!=null&&outcome.status==SourceUpdateWorker.SingleRefreshResult.Status.SAVED)
      catalogueReloadDeferredForPlayback=true;
    if(!singleChannelRequestCurrent(request)){
      if(pendingSingleChannelRefresh==request)cancelSingleChannelRefresh();
      reloadDeferredCatalogueIfIdle();return;
    }
    if(outcome!=null&&(outcome.status==SourceUpdateWorker.SingleRefreshResult.Status.BUSY
        ||outcome.status==SourceUpdateWorker.SingleRefreshResult.Status.DEFERRED)){
      uiHandler.postDelayed(()->pollSingleChannelRefresh(request),2000L);return;
    }
    cancelSingleChannelRefresh();
    if(error!=null){
      if(error instanceof com.wodedianshi.tv.db.RoomCatalogueWriter.StaleCatalogueException)
        toast("频道快照已变化，请重新刷新");
      else if(error instanceof SourceProbeTimePolicy.PausedException
          ||error instanceof SourceProbeTimePolicy.PublicationPausedException)
        toast(SingleChannelRefreshPolicy.requestExpired(android.os.SystemClock.elapsedRealtime(),request.waitUntil)
            ?"频道刷新超时，原线路未改变":"刷新已取消，原线路未改变");
      else{android.util.Log.w("MyTVSourceDiscovery","single-channel refresh failed",error);toast("刷新未完成，保留原有线路");}
      return;
    }
    if(outcome==null){toast("刷新未完成");return;}
    if(outcome.status==SourceUpdateWorker.SingleRefreshResult.Status.SAVED){
      synchronized(channels){
        synchronized(cataloguePersistLock){
          Channel channel=request.channel;
          String playing=request.activeUrl;
          // Patch only the visible channel and only exact retired URLs. Keep
          // its current source by URL even if the picker previously re-ranked.
          boolean coherent=channel.backups!=null&&channel.backups.contains(playing);
          for(Map.Entry<String,String> replacement:outcome.replacedRoutes.entrySet()){
            if(!coherent||!channel.backups.contains(replacement.getKey())
                ||replacement.getKey().equals(playing)
                ||channel.backups.contains(replacement.getValue())){coherent=false;break;}
          }
          if(coherent){
            for(Map.Entry<String,String> replacement:outcome.replacedRoutes.entrySet()){
              String retired=replacement.getKey(),incoming=replacement.getValue();
              channel.backups.set(channel.backups.indexOf(retired),incoming);
              channel.backupSourceScopes.remove(retired);
              if(channelsByUrl.get(retired)==channel)channelsByUrl.remove(retired);
            }
            for(Map.Entry<String,Set<String>> entry:outcome.addedRoutes.entrySet()){
              channel.addBackupSource(entry.getKey(),"");
              for(String scope:entry.getValue())channel.addBackupSource(entry.getKey(),scope);
              channelsByUrl.putIfAbsent(entry.getKey(),channel);
            }
            channel.activeSource=channel.backups.indexOf(playing);
            catalogueMutationGeneration++;catalogueMembershipGeneration++;
          }
        }
      }
      int replaced=outcome.replacedRoutes.size();
      toast("刷新完成：新增 "+(outcome.added-replaced)+" 条，替换 "+replaced+" 条失效备用线路，已保存；当前播放不变");
    }else if(outcome.status==SourceUpdateWorker.SingleRefreshResult.Status.FULL){
      toast("已搜索新线路，但没有符合安全淘汰条件的备用线路，原线路保留");
    }else{
      toast("本轮未找到通过预检的新线路"+(outcome.bounded?"（搜索范围有限）":"")+"，原线路保留");
    }
    reloadDeferredCatalogueIfIdle();
  }
  private void showSourcePicker(){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    if(c.backups==null||c.backups.isEmpty()){toast("当前频道没有可用播放源");return;}
    // Re-rank whenever the picker opens so the latest playback results are reflected.
    // The sorter preserves the currently playing URL; ordering stays in memory.
    sortSourcesByObservedQuality(c);
    final String displayedActiveUrl=currentSourceUrl();
    final String displayedStation=identity(c);
    ArrayList<String> labels=new ArrayList<>();
    labels.add("屏蔽当前广告源 · 第 "+(c.activeSource+1)+" 源");
    final DepartedLiveSource departed=recentDepartedForBlock();
    if(departed!=null)labels.add("屏蔽刚跳过的源 · "+departed.label);
    final int sourceOffset=labels.size();
    ArrayList<String> displayedUrls=new ArrayList<>();
    Set<String> blockedUrls=blockedSourceUrls(c);
    for(int i=0;i<c.backups.size();i++){
      String url=c.backups.get(i);
      boolean blocked=blockedUrls.contains(url);
      labels.add("播放源 "+(i+1)+(i==c.activeSource?"（当前）":"")+(i==0?" · 主源":" · 备用源")+(blocked?" · 已屏蔽（点此恢复）":""));
      displayedUrls.add(url);
    }
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(displayChannelName(c)+" · 选择播放源")
      .setItems(labels.toArray(new String[0]),(d,which)->{
        if(!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive()||currentVodPanel!=null)return;
        if(which==0){blockCapturedLiveSource(displayedStation,displayedActiveUrl);return;}
        if(which==1&&departed!=null){blockCapturedLiveSource(departed.station,departed.url);return;}
        Channel current=selected>=0&&selected<channels.size()?channels.get(selected):null;
        if(current!=c){toast("频道已变化，请重新打开换源菜单");return;}
        int displayedIndex=which-sourceOffset;
        if(displayedIndex<0||displayedIndex>=displayedUrls.size())return;
        String url=displayedUrls.get(displayedIndex);
        int index=SourceSelectionPolicy.resolveManualSelection(c,current,c.backups,url);
        if(index<0){toast("播放源已变化，请重新打开换源菜单");return;}
        if(isSourceBlocked(c,url)){
          setSourceBlocked(identity(c),url,false);
          toast("已恢复该播放源");
        }
        selectManualSource(c,index);startCurrentSource();
      }).setNegativeButton("取消",null).create();
    if(fullScreenPlayback){
      clearPendingRemoteZap();
      fullscreenRemoteDialogOpen=true;
      dialog.setOnDismissListener(d->fullscreenRemoteDialogOpen=false);
    }
    dialog.show();
  }
  private int findNextUnblockedSource(Channel c,int from,int direction){
    if(c==null||c.backups==null||c.backups.size()<2)return -1;
    return SourceSelectionPolicy.findNextManualSource(c.backups,from,direction,blockedSourceUrls(c));
  }
  private int findNextAutomaticSource(Channel c,int from){
    if(c==null||c.backups==null||c.backups.size()<2)return -1;
    return SourceSelectionPolicy.findNextByEndpoint(c.backups,from,blockedSourceUrls(c),
        failedEndpointsThisPass,SourceMemoryStore::isEndpointCoolingDown);
  }
  private void stepSource(int delta){
    clearPendingRemoteChannel();
    if(!canApplyRemoteZap()){clearPendingRemoteSource();return;}
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    if(c.backups==null||c.backups.isEmpty()){toast("当前频道没有可用播放源");return;}
    if(c.backups.size()<2){toast("当前频道只有 1 个播放源");return;}
    if(pendingRemoteSourceChannel!=c)clearPendingRemoteSource();
    int from=pendingRemoteSourceUrl==null?c.activeSource:c.backups.indexOf(pendingRemoteSourceUrl);
    if(from<0){clearPendingRemoteSource();from=c.activeSource;}
    int next=findNextUnblockedSource(c,from,delta>=0?1:-1);
    if(next<0){toast("本频道备用源都已被屏蔽，请在换源菜单中恢复");return;}
    pendingRemoteSourceChannel=c;
    pendingRemoteSourceUrl=c.backups.get(next);
    if(pendingRemoteSourceDispatch!=null)return;
    final long expectedSerial=liveTuneRequestSerial;
    final String expectedActive=currentSourceUrl();
    pendingRemoteSourceDispatch=()->{
      String target=pendingRemoteSourceUrl;
      clearPendingRemoteSource();
      if(!canApplyRemoteZap()||selected<0||selected>=channels.size())return;
      int resolved=SourceSelectionPolicy.resolveQueuedManualSelection(c,channels.get(selected),
          expectedSerial,liveTuneRequestSerial,c.backups,expectedActive,currentSourceUrl(),target,
          blockedSourceUrls(c));
      if(resolved>=0)applyRemoteSource(c,resolved);
    };
    uiHandler.postDelayed(pendingRemoteSourceDispatch,RemoteChannelOrderPolicy.ZAP_BURST_WINDOW_MS);
  }
  /** Reset only per-channel source recovery; automatic channel scans retain their bound. */
  private void resetChannelSourceRecoveryBudget(){
    automaticSwitchCount=0;
    failedEndpointsThisPass.clear();
    sameSourceRecoveryCount=0;
  }
  /** A fresh manual request or confirmed stable output renews the full recovery budget. */
  private void resetLiveRecoveryBudget(){
    resetChannelSourceRecoveryBudget();
    automaticChannelSwitchCount=0;
  }
  /** Commit an already validated manual source choice before starting playback. */
  private void selectManualSource(Channel c,int next){
    recentDepartedLiveSource=null;
    c.activeSource=next;
    resetLiveRecoveryBudget();
    manualSourceSwitch=true;
    queueActiveSourceMutation(c.key(),c.backups.get(next));
  }
  private void applyRemoteSource(Channel c,int next){
    selectManualSource(c,next);
    startCurrentSource();
    showChannelZapMessage(displayChannelName(c)+"\n正在尝试第 "+(c.activeSource+1)+" / "+c.backups.size()+" 个源");
  }
  @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event){
    // Android TV may route USB/Bluetooth mouse-wheel events to the Activity,
    // not the poster or GridView. Intercept centrally while the VOD list is open.
    if(event!=null&&event.getAction()==android.view.MotionEvent.ACTION_SCROLL&&currentVodPanel!=null&&currentVodPanel.isShown()&&currentVodResults!=null){
      GridView grid=null;
      for(int i=0;i<currentVodResults.getChildCount();i++){
        View child=currentVodResults.getChildAt(i);
        if(child instanceof GridView){grid=(GridView)child;break;}
      }
      if(grid!=null&&grid.getAdapter()!=null&&grid.getAdapter().getCount()>0){
        float wheel=event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
        if(wheel==0f)wheel=-event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL);
        if(wheel!=0f){
          int step=Math.max(dp(120),grid.getHeight()/2);
          int delta=Math.round(-wheel*step);
          if(delta==0)delta=wheel>0?-dp(120):dp(120);
          grid.smoothScrollBy(delta,180);
          return true;
        }
      }
    }
    return super.dispatchGenericMotionEvent(event);
  }
  private boolean isInsideView(View child,View parent){
    View current=child;
    while(current!=null){
      if(current==parent)return true;
      android.view.ViewParent p=current.getParent();
      current=p instanceof View?(View)p:null;
    }
    return false;
  }
  private void focusActiveLeftMenuItem(){
    if(categoryMenuItems==null)return;
    String wanted=activeCategory==null?"全部":activeCategory;
    for(int i=0;i<categoryMenuItems.getChildCount();i++){
      View item=categoryMenuItems.getChildAt(i);
      if(item instanceof Button&&wanted.contentEquals(((Button)item).getText())){
        item.requestFocus();
        item.setFocusableInTouchMode(true);
        return;
      }
    }
    if(categoryMenuItems.getChildCount()>0)categoryMenuItems.getChildAt(0).requestFocus();
  }
  @Override public void onBackPressed(){
    if(isAnyPlaybackLockActive())return;
    View focused=rootLayout==null?null:rootLayout.findFocus();
    boolean liveAvailable=activityStarted&&!isFinishing()&&!isDestroyed()
        &&currentVodPanel==null&&vodPlayer==null&&vodFallbackBackend==null
        &&!decoderReleaseUnconfirmed&&selected>=0&&selected<channels.size()
        &&(player!=null||libVlcEngine!=null||liveVlcReleaseContinuation!=null);
    switch(LiveBackPolicy.choose(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing(),
        fullScreenPlayback,categoryPane!=null,isInsideView(focused,categoryPane),liveAvailable)){
      case CLOSE_MENU: closeFullscreenChannelMenu();return;
      case EXIT_FULLSCREEN: exitFullScreenPlayback();return;
      // Only restore the layout; keep the current live engine and source.
      case RETURN_FULLSCREEN: enterFullScreenPlayback();return;
      case FOCUS_CATEGORY: focusActiveLeftMenuItem();return;
      default: super.onBackPressed();
    }
  }

  // Keep a small, user-shareable live diagnostic trail. Query strings and full
  // stream URLs are deliberately excluded so signed URLs/tokens are not exposed.
  private void installCrashDiagnosticsHandler(){
    if(crashHandlerInstalled)return;
    synchronized(MainActivity.class){
      if(crashHandlerInstalled)return;
      final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
      final Context appContext=getApplicationContext();
      Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
        try{
          String message=error==null?"":String.valueOf(error.getMessage());
          message=message.replaceAll("(?i)https?://\\S+","[URL]").replaceAll("[\\r\\n\\t]+"," ");
          if(message.length()>260)message=message.substring(0,260);
          StringBuilder detail=new StringBuilder();
          detail.append("thread=").append(thread==null?"?":thread.getName())
              .append(" exception=").append(error==null?"?":error.getClass().getName())
              .append(" message=").append(message);
          if(error!=null){
            StackTraceElement[] frames=error.getStackTrace();
            int limit=Math.min(frames==null?0:frames.length,24);
            for(int i=0;i<limit;i++)detail.append("\n at ").append(frames[i].toString());
          }
          appContext.getSharedPreferences("mytv",0).edit()
              .putString("last_uncaught_crash_v1",detail.length()>7000?detail.substring(0,7000):detail.toString())
              .putString("last_uncaught_crash_time",new Date().toString())
              .commit();
        }catch(Throwable ignored){}
        if(previous!=null)previous.uncaughtException(thread,error);
      });
      crashHandlerInstalled=true;
    }
  }

  private static final java.util.regex.Pattern DIAGNOSTIC_URL=java.util.regex.Pattern.compile("(?i)https?://\\S+");
  private static final java.util.regex.Pattern DIAGNOSTIC_WHITESPACE=java.util.regex.Pattern.compile("[\\r\\n\\t]+");
  private String safeDiagnosticDetail(String value){
    if(value==null)return "";
    String clean=DIAGNOSTIC_URL.matcher(value).replaceAll("[URL]");
    clean=DIAGNOSTIC_WHITESPACE.matcher(clean).replaceAll(" ");
    return clean.length()>260?clean.substring(0,260):clean;
  }
  // Best-effort asynchronous native breadcrumb. A native abort or abrupt
  // process death can occur before SharedPreferences persists it.
  private void saveCriticalEngineStage(String stage,String detail){
    saveLiveDiagnostic(stage,detail,true);
  }
  private static final Object LIVE_DIAGNOSTIC_LOCK=new Object();
  private static final java.util.ArrayDeque<String> LIVE_DIAGNOSTIC_LINES=new java.util.ArrayDeque<>();
  private static boolean liveDiagnosticLoaded;
  private void saveLiveDiagnostic(String stage,String detail){
    saveLiveDiagnostic(stage,detail,false);
  }
  private void saveLiveDiagnostic(String stage,String detail,boolean criticalStage){
    long recordedAt=System.currentTimeMillis();
    String sanitized=safeDiagnosticDetail(detail);
    // Capture before persistence without re-sanitizing the diagnostic text.
    if(criticalStage)noteUiDecoderStage(stage+" "+sanitized);
    String line=java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM,Locale.getDefault()).format(new Date(recordedAt))
        +" | "+stage+" | "+sanitized;
    android.util.Log.w("MyTVLiveDiag",line);
    try{
      android.content.SharedPreferences p=getSharedPreferences("mytv",0);
      synchronized(LIVE_DIAGNOSTIC_LOCK){
        if(!liveDiagnosticLoaded){
          String old=p.getString("live_diagnostics_v1","");
          if(old!=null&&!old.isEmpty())LIVE_DIAGNOSTIC_LINES.addAll(Arrays.asList(old.split("\\n")));
          liveDiagnosticLoaded=true;
        }
        LIVE_DIAGNOSTIC_LINES.addLast(line);
        while(LIVE_DIAGNOSTIC_LINES.size()>30)LIVE_DIAGNOSTIC_LINES.removeFirst();
        android.content.SharedPreferences.Editor editor=p.edit().putString("live_diagnostics_v1",
            android.text.TextUtils.join("\n",LIVE_DIAGNOSTIC_LINES));
        // One formatting pass and one asynchronous preferences edit per stage.
        // The last critical breadcrumb and ring now share the same timestamp
        // and update together; persistence before abrupt process death is best effort.
        if(criticalStage)editor.putString("last_native_engine_stage_v1",line)
            .putLong("last_native_engine_stage_time_v1",recordedAt);
        editor.apply();
      }
    }catch(Exception ignored){}
  }
  private void recordDecoderInventoryOnce(){
    if(decoderInventoryRecorded)return;
    decoderInventoryRecorded=true;
    // Codec enumeration may query vendor media services; never block the TV
    // player's first frame or the main/UI thread on this diagnostic task.
    Thread inventoryThread=new Thread(()->{
      try{
        for(String capability:DeviceCodecCapabilityInspector.describeDecoderInventory()){
          saveLiveDiagnostic("DECODER_INVENTORY",capability);
        }
        saveLiveDiagnostic("DECODER_NOTE","设备声明的解码器不代表所有配置均可播放，仍需实测编码、Profile、Level、分辨率和帧率");
      }catch(RuntimeException error){
        saveLiveDiagnostic("DECODER_INVENTORY_ERROR",error.getClass().getSimpleName());
      }
    },"MyTV-CodecInventory");
    inventoryThread.setDaemon(true);
    inventoryThread.start();
  }
  // Android 11+ exposes the previous process exit classification. This is the
  // most useful way to distinguish a Java crash, native codec crash and ANR after
  // Android has already killed the previous app process.
  // A system ANR report may retain a readable trace on Android 11+.
  // Never read the full trace on the main thread and never store unrelated
  // process/thread information. The caller runs on the startup worker.
  private String extractHistoricalAnrMainStack(java.io.InputStream stream)throws java.io.IOException{
    if(stream==null)return "";
    java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
    byte[] chunk=new byte[4096];
    int total=0,count;
    while(total<196608&&(count=stream.read(chunk,0,Math.min(chunk.length,196608-total)))>0){
      output.write(chunk,0,count);
      total+=count;
    }
    String text=new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
    int start=text.indexOf("\"main\"");
    if(start<0)return "";
    int end=Math.min(text.length(),start+3600);
    int next=text.indexOf("\n\n\"",start+6);
    if(next>start&&next<end)end=next;
    return text.substring(start,end)
        .replaceAll("(?i)https?://\\S+","[URL]");
  }
  private void recordPreviousProcessExitReason(){
    if(android.os.Build.VERSION.SDK_INT<30)return;
    try{
      android.app.ActivityManager manager=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
      if(manager==null)return;
      android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
      long reported=prefs.getLong("last_reported_exit_timestamp_v1",0L);
      java.util.List<android.app.ApplicationExitInfo> exits=manager.getHistoricalProcessExitReasons(getPackageName(),0,8);
      if(exits==null||exits.isEmpty())return;
      // Recover the trace from an already-reported ANR as well. The previous
      // build persisted the exit summary and marked its timestamp, but did not
      // try ApplicationExitInfo.getTraceInputStream().
      long stackRecordedAt=prefs.getLong("last_anr_trace_timestamp_v1",0L);
      for(android.app.ApplicationExitInfo exit:exits){
        if(exit==null||exit.getReason()!=android.app.ApplicationExitInfo.REASON_ANR
            ||exit.getTimestamp()<=0L||exit.getTimestamp()==stackRecordedAt)continue;
        // Never place a binder trace read ahead of the Room restore on the
        // startup executor; schedule it independently after initial UI paint.
        final android.app.ApplicationExitInfo anrExit=exit;
        Thread traceWorker=new Thread(()->{
          try{
            Thread.sleep(3500L);
            try(java.io.InputStream trace=anrExit.getTraceInputStream()){
              String mainStack=extractHistoricalAnrMainStack(trace);
              if(!mainStack.isEmpty()){
                prefs.edit().putString("last_anr_historical_main_stack_v1",
                        new java.util.Date(anrExit.getTimestamp()).toString()+" | "+mainStack)
                    .putLong("last_anr_trace_timestamp_v1",anrExit.getTimestamp()).apply();
                android.util.Log.i("MyTVANR","Recovered historical main-thread ANR trace");
              }
            }
          }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
          }catch(java.io.IOException|RuntimeException traceError){
            android.util.Log.w("MyTVANR","Historical ANR trace unavailable",traceError);
          }
        },"MyTV-ExitTrace");
        traceWorker.setDaemon(true);
        traceWorker.start();
        break;
      }
      for(android.app.ApplicationExitInfo exit:exits){
        if(exit==null||exit.getTimestamp()<=reported)continue;
        int reason=exit.getReason();
        String reasonName;
        switch(reason){
          case android.app.ApplicationExitInfo.REASON_CRASH: reasonName="JAVA_CRASH";break;
          case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: reasonName="NATIVE_CRASH";break;
          case android.app.ApplicationExitInfo.REASON_ANR: reasonName="ANR";break;
          case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: reasonName="LOW_MEMORY";break;
          case android.app.ApplicationExitInfo.REASON_SIGNALED: reasonName="SIGNALED";break;
          case android.app.ApplicationExitInfo.REASON_EXIT_SELF: reasonName="EXIT_SELF";break;
          case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: reasonName="USER_REQUESTED";break;
          case android.app.ApplicationExitInfo.REASON_USER_STOPPED: reasonName="USER_STOPPED";break;
          case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: reasonName="EXCESSIVE_RESOURCE_USAGE";break;
          case android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED: reasonName="DEPENDENCY_DIED";break;
          default: reasonName="OTHER_"+reason;break;
        }
        String description=safeDiagnosticDetail(String.valueOf(exit.getDescription()));
        String detail=reasonName+" | time="+new java.util.Date(exit.getTimestamp())
            +" | process="+safeDiagnosticDetail(exit.getProcessName())
            +" | status="+exit.getStatus()+" | importance="+exit.getImportance()
            +" | pssKb="+exit.getPss()+" | rssKb="+exit.getRss()
            +" | description="+description;
        prefs.edit().putString("previous_process_exit_v1",detail)
            .putLong("last_reported_exit_timestamp_v1",exit.getTimestamp()).commit();
        saveLiveDiagnostic("PREVIOUS_PROCESS_EXIT",detail);
        break;
      }
    }catch(RuntimeException error){
      saveLiveDiagnostic("PREVIOUS_PROCESS_EXIT_UNAVAILABLE",error.getClass().getSimpleName());
    }
  }
  private void showLiveDiagnostics(){
    android.content.SharedPreferences diagnostics=getSharedPreferences("mytv",0);
    String details=diagnostics.getString("live_diagnostics_v1","暂无直播诊断记录。请打开一个直播频道并等待自动检测。");
    String crash=diagnostics.getString("last_uncaught_crash_v1","");
    if(!crash.isEmpty())details+="\n\n===== 最近一次未捕获崩溃（脱敏） =====\n"+crash;
    String previousExit=diagnostics.getString("previous_process_exit_v1","");
    if(!previousExit.isEmpty())details+="\n\n===== 上一次进程退出原因（Android 11+ 系统记录） =====\n"+previousExit;
    String historicalAnrStack=diagnostics.getString("last_anr_historical_main_stack_v1","");
    if(!historicalAnrStack.isEmpty())details+="\n\n===== Android 11+ 历史 ANR 的主线程调用堆栈 =====\n"+historicalAnrStack;
    String nativeStage=diagnostics.getString("last_native_engine_stage_v1","");
    if(!nativeStage.isEmpty())details+="\n\n===== 最近一次原生播放器关键阶段（不代表已确认崩溃） =====\n"+nativeStage;
    String blockedStack=diagnostics.getString("last_main_thread_block_stack_v1","");
    if(!blockedStack.isEmpty())details+="\n\n===== 主线程超过2.5秒未响应时的调用堆栈 =====\n"+blockedStack;
    final String copyDetails=details;
    new AlertDialog.Builder(this).setTitle("直播诊断记录")
        .setMessage(copyDetails+"\n\n提示：点击“复制”后可把记录发给我；记录不包含完整播放地址。")
        .setPositiveButton("复制记录",(d,w)->{
          android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
          if(cm!=null){cm.setPrimaryClip(ClipData.newPlainText("我的电视直播诊断",copyDetails));toast("诊断记录已复制");}
        }).setNegativeButton("关闭",null).show();
  }

  private int automaticSwitchCount=0;
  private final HashSet<String> failedEndpointsThisPass=new HashSet<>();
  private long liveTuneRequestSerial=0L;
  // A complete source failure advances through channels at most once per manual play.
  private int automaticChannelSwitchCount=0;
  private Runnable bufferingFallback;
  private Runnable playbackWatchdog;
  private long watchdogLastPosition=-1L;
  private long watchdogLastAdvanceAt=0L;
  // Updated from the decoder playback thread whenever a video frame is about to render.
  private volatile long lastVideoPipelineActivityAt=0L;
  private androidx.media3.exoplayer.DecoderCounters liveRenderedCounter;
  private int liveRenderedCount=-1;
  private long liveRenderedAdvanceAt=0L,lastLiveAudioWarningAt=0L;
  private int liveRenderedStationaryChecks;
  // One same-source recovery per selected channel before moving to its backup.
  private int sameSourceRecoveryCount=0;
  // A stale HLS live-window position gets one in-place recovery before source failover.
  private boolean liveWindowRecoveryAttempted=false;
  private long sourceAttemptStartedAt=0L;
  // Monotonic timing only; never persist endpoints in tune diagnostics.
  private long sourceFirstLoadAt=0L;
  private long sourceReadyAt=0L;
  // The retry-budget clock must never reset the continuous READY clock used
  // by initial discovery's viewing permission.
  private long lastRecoveryBudgetRefreshAt=0L;
  // Start the no-first-frame deadline only once a supported video track is selected.
  private long videoTrackSelectedAt=0L;
  private boolean resumePlaybackOnStart=false;
  private boolean sourceReadyRecorded=false;
  private boolean sourceStableRecorded=false;
  private volatile boolean sourceFirstFrameRecorded=false;
  private volatile boolean sourceFirstLoadCompleted=false;
  private boolean sourceFailureRecorded=false;
  // Per-attempt cooldown chosen from the final Media3 error; non-error failovers use the conservative default.
  private long sourceFailureCooldownMs=120000L;
  private boolean sourceWasBuffering=false;
  private boolean audioTrackPresent=false;
  private boolean audioTrackSupported=false;
  private boolean audioTrackSelected=false;
  private boolean audioPositionAdvancing=false;
  private boolean videoTrackPresent=false;
  private boolean videoTrackSupported=false;
  private boolean videoTrackSelected=false;
  private Runnable audioTrackCheck;
  private void cancelAudioTrackCheck(){
    if(audioTrackCheck!=null){uiHandler.removeCallbacks(audioTrackCheck);audioTrackCheck=null;}
  }
  private void scheduleAudioTrackCheck(){
    long delay=TrackHealthPolicy.initialTrackCheckDelayMs(
        audioTrackPresent&&audioTrackSupported&&audioTrackSelected,
        videoTrackPresent&&videoTrackSupported&&videoTrackSelected);
    scheduleAudioTrackCheck(delay,delay==8000L);
  }
  private void scheduleAudioTrackCheck(long delay,boolean earlyCheck){
    cancelAudioTrackCheck();
    if(player==null||player.getPlaybackState()!=Player.STATE_READY)return;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    audioTrackCheck=()->{
      audioTrackCheck=null;
      if(player==null||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId)
          ||player.getPlaybackState()!=Player.STATE_READY||!player.getPlayWhenReady())return;
      boolean usableAudioTrack=audioTrackPresent&&audioTrackSupported&&audioTrackSelected;
      boolean usableVideoTrack=videoTrackPresent&&videoTrackSupported&&videoTrackSelected;
      long grace=TrackHealthPolicy.remainingAudioGraceMs(earlyCheck,usableAudioTrack,
          usableVideoTrack,audioPositionAdvancing);
      if(grace>0L){
        scheduleAudioTrackCheck(grace,false);
        return;
      }
      boolean missingAudio=!usableAudioTrack||!audioPositionAdvancing;
      boolean missingVideo=!usableVideoTrack;
      if(TrackHealthPolicy.shouldFailoverForTrackHealth(missingAudio,missingVideo)){
        String reason=missingAudio?"音频轨道或输出不可用":"没有可用视频轨道";
        if(!tryStartLibVlcFallback("检测到当前源"+reason)){
          advanceToNextSourceAutomatically("检测到当前源"+reason);
        }
      }else{
        // Tracks can disappear after startup; keep checking while READY.
        scheduleAudioTrackCheck();
      }
    };
    // Earlier check for missing/unsupported tracks; keep the longer audio
    // advancement grace when both tracks are already selected.
    uiHandler.postDelayed(audioTrackCheck,delay);
  }
  private void cancelPlaybackWatchdog(){
    if(playbackWatchdog!=null){uiHandler.removeCallbacks(playbackWatchdog);playbackWatchdog=null;}
  }
  private void resetLiveRenderedEvidence(){
    liveRenderedCounter=null;liveRenderedCount=-1;liveRenderedAdvanceAt=0L;liveRenderedStationaryChecks=0;
  }
  private void sampleLiveRenderedEvidence(long now){
    androidx.media3.exoplayer.DecoderCounters counters=player==null?null:player.getVideoDecoderCounters();
    if(counters==null){resetLiveRenderedEvidence();return;}
    counters.ensureUpdated();
    int count=counters.renderedOutputBufferCount;
    boolean same=counters==liveRenderedCounter;
    if(!same||count<0||count<liveRenderedCount){liveRenderedAdvanceAt=0L;liveRenderedStationaryChecks=0;}
    else if(TrackHealthPolicy.renderedCounterAdvanced(true,liveRenderedCount,count)){
      liveRenderedAdvanceAt=now;liveRenderedStationaryChecks=0;
    }else if(liveRenderedAdvanceAt>0L)liveRenderedStationaryChecks=Math.min(2,liveRenderedStationaryChecks+1);
    liveRenderedCounter=counters;liveRenderedCount=count;
  }
  private void schedulePlaybackWatchdog(){
    cancelPlaybackWatchdog();
    if(player==null||player.getPlaybackState()!=Player.STATE_READY||!player.getPlayWhenReady())return;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    playbackWatchdog=()->{
      playbackWatchdog=null;
      if(player==null||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId))return;
      if(!player.getPlayWhenReady()){pauseStableDiscovery();return;}
      int state=player.getPlaybackState();
      // 缓冲由独立计时器处理；READY 但没有真正推进时间轴也必须继续监测。
      if(state==Player.STATE_BUFFERING){pauseStableDiscovery();if(bufferingFallback==null)scheduleBufferingFallback();return;}
      if(state!=Player.STATE_READY){pauseStableDiscovery();return;}
      long now=android.os.SystemClock.elapsedRealtime();
      if(player.isPlaying())sampleLiveRenderedEvidence(now);else resetLiveRenderedEvidence();
      if(videoTrackSelected&&sourceFirstFrameRecorded&&TrackHealthPolicy.renderedCounterStalled(
          liveRenderedAdvanceAt,now,liveRenderedStationaryChecks)){
        pauseStableDiscovery();
        saveLiveDiagnostic("MEDIA3_RENDER_STALLED","previously advancing rendered output counter stationary for at least 20 seconds");
        recoverFromSilentFreeze("检测到 Media3 实际渲染计数持续停滞");
        return;
      }
      long position=player.getCurrentPosition();
      if(watchdogLastPosition<0||position>watchdogLastPosition+250){
        watchdogLastPosition=position;
        watchdogLastAdvanceAt=now;
      }else if(PlaybackRecoveryPolicy.isTimelineStalled(now,watchdogLastAdvanceAt,45000L)){
        recoverFromSilentFreeze("播放时间轴连续 45 秒没有推进");
        return;
      }
      // A live source can leave ExoPlayer in READY while its video pipeline stalls.
      // Startup success is gated by the rendered-first-frame analytics event; after
      // that, decoder metadata activity is used as a lightweight stall signal.
      // Start monitoring as soon as a supported video track is selected.
      if(PlaybackRecoveryPolicy.isVideoOutputStalled(videoTrackSelected,sourceFirstFrameRecorded,
          videoTrackSelectedAt,lastVideoPipelineActivityAt,now,sourceFirstFrameRecorded?20000L:15000L)){
        recoverFromSilentFreeze(sourceFirstFrameRecorded
            ?"检测到视频帧连续 20 秒没有输出"
            :"播放器已连接，但 15 秒内没有渲染出首帧画面");
        return;
      }
      // After two minutes of uninterrupted playback, replenish the failover budget.
      // A source that was healthy for a long time should not consume the startup retry budget forever.
      boolean confirmedOutput=TrackHealthPolicy.hasConfirmedTvOutput(audioTrackPresent,
          audioTrackSupported,audioTrackSelected,audioPositionAdvancing,videoTrackPresent,
           videoTrackSupported,videoTrackSelected,sourceFirstFrameRecorded)
           &&TrackHealthPolicy.recentRenderedCounter(liveRenderedAdvanceAt,now)
           &&TrackHealthPolicy.audioWarningQuiet(lastLiveAudioWarningAt,now);
      if(TrackHealthPolicy.canGrantStableDiscovery(confirmedOutput,sourceReadyAt,
          lastStableDiscoveryInteraction,watchdogLastAdvanceAt,lastVideoPipelineActivityAt,now)
          &&player.isPlaying()&&canGrantStableDiscoveryUi()){
        boolean alreadyPermitted=SourceUpdateActivityPolicy.permitsInitialPlaybackWork(now);
        SourceUpdateActivityPolicy.reportStableLive(now);
        SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(now,player.getTotalBufferedDuration());
        if(!alreadyPermitted&&SourceUpdateActivityPolicy.permitsInitialPlaybackWork(now))
          android.util.Log.i("MyTVSourceDiscovery","MEDIA3_DISCOVERY_PERMITTED bounded low-priority discovery");
      }else SourceUpdateActivityPolicy.revokeStableLive();
      if(!sourceStableRecorded&&confirmedOutput&&sourceReadyAt>0L
          &&now-sourceReadyAt>=30000L&&now-watchdogLastAdvanceAt<15000L
          &&lastVideoPipelineActivityAt>0L&&now-lastVideoPipelineActivityAt<20000L){
        sourceStableRecorded=true;
        String goodUrl=currentSourceUrl();
        recordSourceMetric(goodUrl,"stable",0L);
        if(selected>=0&&selected<channels.size())
          SourceMemoryStore.saveLastGood(MainActivity.this,
              SourceMemoryStore.channelIdentityKey(identity(channels.get(selected))),goodUrl);
      }
      if(TrackHealthPolicy.canRefreshRecoveryBudget(confirmedOutput,
          Math.max(sourceReadyAt,lastRecoveryBudgetRefreshAt),
          watchdogLastAdvanceAt,lastVideoPipelineActivityAt,now)){
        resetLiveRecoveryBudget();
        lastRecoveryBudgetRefreshAt=now;
      }
      schedulePlaybackWatchdog();
    };
    uiHandler.postDelayed(playbackWatchdog,sourceFirstFrameRecorded?10000L:5000L);
  }
  private void recoverFromSilentFreeze(String reason){
    if(sameSourceRecoveryCount<1){
      sameSourceRecoveryCount++;
      info.setText(reason+"，正在重新连接当前频道…");
      startCurrentSource();
    }else{
      sameSourceRecoveryCount=0;
      if(!tryStartLibVlcFallback(reason+"，Media3 重连无效"))
        advanceToNextSourceAutomatically(reason+"，当前源重连无效");
    }
  }
  // A given live stream may report error, buffering timeout and ENDED almost together.
  // Only allow one automatic failover for each playback generation.
  private String lastAutoFailoverMediaId="";
  private void coverLivePicture(){
    livePictureGate.cover();
    if(livePictureCover!=null)livePictureCover.setVisibility(View.VISIBLE);
  }
  private boolean revealLivePicture(Object owner,String mediaId,long renderedAt){
    if(!livePictureGate.reveal(owner,mediaId,renderedAt))return false;
    lastOutputMediaId=mediaId;
    if(livePictureCover!=null)livePictureCover.setVisibility(View.GONE);
    return true;
  }
  private void releaseLivePlayerSafely(){
    pauseStableDiscovery();
    ExoPlayer old=player;
    if(old!=null||(video!=null&&video.getPlayer()!=null))coverLivePicture();
    // Invalidate callbacks first. Preserve independent cleanup stages even when
    // a vendor codec call fails; stage timing is diagnostic, not a new teardown path.
    player=null;
    watchMedia3Release(old);
    final long begun=android.os.SystemClock.elapsedRealtime();
    final boolean onUiThread=android.os.Looper.myLooper()==android.os.Looper.getMainLooper();
    if(old==null){
      if(video!=null&&video.getPlayer()!=null){try{noteUiDecoderStage("live orphan view detach");video.setPlayer(null);}catch(RuntimeException error){
        saveLiveDiagnostic("LIVE_VIEW_DETACH_ERROR",error.getClass().getSimpleName());
      }}
      long detachMs=android.os.SystemClock.elapsedRealtime()-begun;
      if(detachMs>=150L){
        android.util.Log.w("MyTVPlayback","media3_empty_detach_ms="+detachMs
            +" uiThread="+onUiThread);
        if(detachMs>=500L)saveLiveDiagnostic("MEDIA3_DETACH_SLOW",
            "emptyPlayerDetachMs="+detachMs+" uiThread="+onUiThread);
      }
      releaseDecoderOwnershipIfIdle();
      return;
    }
    long checkpoint=begun;
    try{old.setPlayWhenReady(false);}catch(RuntimeException error){
      saveLiveDiagnostic("LIVE_DISABLE_PLAY_ERROR",error.getClass().getSimpleName());
    }
    long disableMs=android.os.SystemClock.elapsedRealtime()-checkpoint;
    // pause() is equivalent to setPlayWhenReady(false); do not issue it twice.
    checkpoint=android.os.SystemClock.elapsedRealtime();
    if(video!=null&&video.getPlayer()!=null){try{noteUiDecoderStage("live surface detach begin");video.setPlayer(null);}catch(RuntimeException error){
      saveLiveDiagnostic("LIVE_VIEW_DETACH_ERROR",error.getClass().getSimpleName());
    }}
    long detachMs=android.os.SystemClock.elapsedRealtime()-checkpoint;
    // Share the stop/recovery path with VOD. Normal stop does not need a
    // separate playlist clear before the final decoder release.
    checkpoint=android.os.SystemClock.elapsedRealtime();
    long clearMs=stopMedia3BeforeRelease(old,"LIVE");
    long stopMs=Math.max(0L,android.os.SystemClock.elapsedRealtime()-checkpoint-clearMs);
    checkpoint=android.os.SystemClock.elapsedRealtime();
    releaseMedia3Confirmed(old);
    long releaseMs=android.os.SystemClock.elapsedRealtime()-checkpoint;
    long totalMs=android.os.SystemClock.elapsedRealtime()-begun;
    // Healthy teardown produces no diagnostic writes. Capture a single
    // sanitized summary only when there is measurable input-latency risk.
    if(totalMs>=150L||disableMs>=100L||detachMs>=100L
        ||stopMs>=100L||clearMs>=100L||releaseMs>=100L){
      String summary="totalMs="+totalMs+" disableMs="+disableMs
          +" detachMs="+detachMs+" stopMs="+stopMs
          +" clearMs="+clearMs+" releaseMs="+releaseMs
          +" uiThread="+onUiThread;
      android.util.Log.w("MyTVPlayback","MEDIA3_RELEASE_TIMING "+summary);
      if(totalMs>=500L)saveLiveDiagnostic("MEDIA3_RELEASE_SLOW",summary);
    }
  }
  private void cancelBufferingFallback(){
    if(bufferingFallback!=null){uiHandler.removeCallbacks(bufferingFallback);bufferingFallback=null;}
  }
  private void scheduleBufferingFallback(){
    cancelBufferingFallback();
    int configured=getSharedPreferences("mytv",0).getInt("buffer_switch_seconds",12);
    if(configured<=0)return;
    // Keep first startup bounded, but make failover attempts deliberately faster.
    // A source that has already failed should not make the viewer wait another 20-30s.
    int seconds=BufferingFailoverPolicy.timeoutSeconds(
        sourceReadyRecorded,automaticSwitchCount,configured);
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    bufferingFallback=()->{
      bufferingFallback=null;
      if(player!=null&&selected==expectedChannel&&currentMediaId.equals(expectedMediaId)
          &&player.getPlaybackState()==Player.STATE_BUFFERING){
        saveLiveDiagnostic("BUFFER_TIMEOUT","still buffering after "+seconds+"s");
        // A buffering timeout alone is not evidence of decoder incompatibility.
        // Trying LibVLC on the same unreachable URL doubles the wait and risks
        // expensive native decoder teardown on Android 9 TV hardware. Prefer
        // the next verified backup; codec/track failures retain engine fallback.
        advanceToNextSourceAutomatically("连续缓冲超过 "+seconds+" 秒");
      }
    };
    uiHandler.postDelayed(bufferingFallback,seconds*1000L);
  }
  private void advanceToNextSourceAutomatically(String reason){
    if(isAnyPlaybackLockActive()||currentVodPanel!=null||!activityStarted)return;
    if(selected<0||selected>=channels.size())return;
    String failedMediaId=currentMediaId;
    if(failedMediaId.isEmpty()||failedMediaId.equals(lastAutoFailoverMediaId))return;
    rememberDepartedLiveSource(channels.get(selected));
    // Every automatic failover path (buffer timeout, silent freeze, ended stream,
    // track failure or player error) must update the same failure memory. Some
    // paths arrive here without passing through onPlayerError.
    failedEndpointsThisPass.add(SourceSelectionPolicy.endpointKey(currentSourceUrl()));
    recordCurrentSourceFailure();
    lastAutoFailoverMediaId=failedMediaId;
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    cancelAudioTrackCheck();
    if(selected<0||selected>=channels.size())return;
    Channel c=channels.get(selected);
    if(c.backups.size()<2){
      advanceToNextChannelAutomatically(reason+"；本频道没有可用备用源");
      return;
    }
    if(automaticSwitchCount>=c.backups.size()-1){
      advanceToNextChannelAutomatically(reason+"；本频道备用源已全部尝试");
      return;
    }
    int next=findNextAutomaticSource(c,c.activeSource);
    if(next<0){
      advanceToNextChannelAutomatically(reason+"；本频道其余源均已屏蔽或尝试");
      return;
    }
    automaticSwitchCount++;
    sameSourceRecoveryCount=0;
    c.activeSource=next;

    info.setText(reason+"，正在自动切换备用源 "+(c.activeSource+1)+"/"+c.backups.size());
    startCurrentSource();
  }
  private void stopFailedLiveSession(String reason){
    clearPendingRemoteZap();
    cancelSingleChannelRefresh();
    ++liveTuneRequestSerial;
    revokePendingLiveTune();
    cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();
    releaseLivePlayerSafely();releaseLibVlcEngine();
    if(video!=null)video.setVisibility(View.VISIBLE);
    sourceWasBuffering=false;
    // Do not return background discovery to the foreground during native teardown.
    setSourceUpdatePlaybackActive(libVlcReleaseInProgress);
    if(info!=null)info.setText(reason);
    showChannelZapMessage(reason);
    reloadDeferredCatalogueIfIdle();
  }
  private void advanceToNextChannelAutomatically(String reason){
    if(isAnyPlaybackLockActive()||currentVodPanel!=null||!activityStarted)return;
    if(selected<0||selected>=channels.size()||channels.isEmpty())return;
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    cancelAudioTrackCheck();
    // The cached remote order is built off the main thread and matches the
    // current province/city/country/search scope. Never scan the full catalogue here.
    RemoteChannelOrder scoped=currentRemoteChannelOrder();
    int next=ChannelFailoverPolicy.nextScopedChannel(scoped==null?null:scoped.indexes,
        scoped==null?null:scoped.positions.get(selected),automaticChannelSwitchCount);
    if(next<0||next>=channels.size()){
      stopFailedLiveSession(reason+"；已停止本轮自动尝试，请手动选择频道");
      saveLiveDiagnostic("AUTO_SCAN_EXHAUSTED",
          "bounded scoped recovery stopped; automatic channel switches="+automaticChannelSwitchCount);
      return;
    }
    automaticChannelSwitchCount++;
    resetChannelSourceRecoveryBudget();
    manualSourceSwitch=false;
    int previouslySelected=selected;
    selected=next;
    updateLiveChannelSelection(previouslySelected);
    Channel target=channels.get(selected);
    // Playlist refreshes may reorder backups; never trust a persisted numeric index.
    if(!prepareAutomaticChannelSource(target))return;
    info.setText(reason+"，自动切换到下一频道："+displayChannelName(target));
    startCurrentSource();
  }
  private void play(int ix){
    play(ix,null);
  }
  // An explicit source request must not be overwritten by the remembered last-good route.
  private void play(int ix,Integer requestedSourceIndex){
    pauseStableDiscovery();
    clearPendingRemoteZap();
    if(ix<0||ix>=channels.size())return;
    if(isChildLockCurrentlyActive()){enforceChildLock();return;}
    if(isRemotePlaybackLockActive())return;
    boolean explicit=requestedSourceIndex!=null;
    Channel target=channels.get(ix);
    if(explicit&&(target.backups==null||target.backups.isEmpty()))return;
    recentDepartedLiveSource=null;
    resetLiveRecoveryBudget();
    manualSourceSwitch=explicit;
    int previouslySelected=selected;
    selected=ix;
    if(explicit)target.activeSource=Math.max(0,Math.min(requestedSourceIndex,target.backups.size()-1));
    updateLiveChannelSelection(previouslySelected);
    if(fullscreenFavoriteButton!=null)fullscreenFavoriteButton.setText(isCurrentLiveFavorite()?"★ 已收藏":"☆ 收藏");
    if(explicit){
      queueActiveSourceMutation(target.key(),target.backups.get(target.activeSource));
    }else{
      // Backup indices can change when playlists refresh; restore by URL identity instead.
      if(!prepareAutomaticChannelSource(target)){
        closeFullscreenChannelMenu();
        return;
      }
    }
    // Close the selector as soon as a valid manual request is accepted,
    // rather than waiting for asynchronous native decoder release.
    closeFullscreenChannelMenu();
    startCurrentSource();
  }
  private void startCurrentSource(){
    clearPendingRemoteSource();
    pauseStableDiscovery();
    if(!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive()||currentVodPanel!=null)return;
    if(decoderReleaseUnconfirmed){toast("播放器资源未确认释放，请强制停止应用后重开");return;}
    if(vodPlayer!=null||vodFallbackBackend!=null)return;
    if(!DECODER_OWNERSHIP.availableTo(decoderOwnerToken)
        ||(!libVlcReleaseInProgress&&PROCESS_VLC_TEARDOWNS.get()>0)){
      toast("旧播放器仍在使用资源，请稍后重试");return;
    }
    if(selected<0||selected>=channels.size())return;
    cancelSingleChannelRefresh();
    ++liveTuneRequestSerial;
    lastOutputMediaId="";
    Channel requested=channels.get(selected);
    saveCriticalEngineStage("TUNE_REQUEST","channel="+requested.name+" index="+selected+" backups="+(requested.backups==null?0:requested.backups.size()));
    try{
      startCurrentSourceInternal();
    }catch(Throwable tuneFailure){
      if(tuneFailure instanceof ThreadDeath)throw (ThreadDeath)tuneFailure;
      if(tuneFailure instanceof VirtualMachineError)throw (VirtualMachineError)tuneFailure;
      android.util.Log.e("MyTVPlayback","uncaught tune setup failure",tuneFailure);
      saveCriticalEngineStage("TUNE_SETUP_FATAL",tuneFailure.getClass().getSimpleName()+": "+tuneFailure.getMessage());
      // A tune can fail before startCurrentSourceInternal reaches its normal
      // watchdog reset. Cancel every old live timer before releasing engines so
      // a stale timeout cannot run against the next tune or a recovered screen.
      cancelBufferingFallback();
      cancelPlaybackWatchdog();
      cancelAudioTrackCheck();
      try{releaseLivePlayerSafely();}catch(Throwable ignored){}
      try{releaseLibVlcEngine();}catch(Throwable ignored){}
      setSourceUpdatePlaybackActive(false);
      try{exitFullScreenPlayback();}catch(Throwable uiFailure){
        saveLiveDiagnostic("TUNE_UI_RECOVERY_ERROR",uiFailure.getClass().getSimpleName()+": "+uiFailure.getMessage());
      }
      if(info!=null)info.setText("频道启动遇到异常，已停止播放器保护主界面；请打开“直播诊断记录”查看原因。");
    }
  }
  private boolean isCurrentLiveAnalyticsEvent(AnalyticsListener.EventTime eventTime){
    if(currentMediaId.isEmpty()||currentVodPanel!=null||!activityStarted)return false;
    if(eventTime==null||eventTime.timeline==null||eventTime.windowIndex<0
        ||eventTime.windowIndex>=eventTime.timeline.getWindowCount())return false;
    try{
      androidx.media3.common.Timeline.Window window=eventTime.timeline.getWindow(
          eventTime.windowIndex,new androidx.media3.common.Timeline.Window());
      return window.mediaItem!=null&&currentMediaId.equals(window.mediaItem.mediaId);
    }catch(RuntimeException ignored){return false;}
  }
  private void startCurrentSourceInternal(){
    if(selected<0||selected>=channels.size())return;
    // Cover the outgoing picture before either surface is shown or released.
    coverLivePicture();
    // LibVLC teardown can block on Android 9 vendor decoders. Never start a
    // Media3 tune while that native audio/video session is still being released.
    // Multiple rapid tune requests queue behind the same release; only the latest
    // matching playback generation is allowed to continue.
    if(libVlcEngine!=null||libVlcReleaseInProgress){
      final long requestSerial=liveTuneRequestSerial;
      final int expectedChannel=selected;
      setSourceUpdatePlaybackActive(true);
      revokePendingLiveTune();
      liveVlcReleaseContinuation=()->{
        liveVlcReleaseContinuation=null;
        if(activityStarted&&!isFinishing()&&!isDestroyed()&&currentVodPanel==null
            &&selected==expectedChannel&&requestSerial==liveTuneRequestSerial)
          startCurrentSource();
      };
      releaseLibVlcEngine(liveVlcReleaseContinuation);
      return;
    }
    usingLibVlc=false;
    libVlcAttemptedForSource=false;
    if(video!=null)video.setVisibility(View.VISIBLE);
    if(isChildLockCurrentlyActive()){enforceChildLock();return;}
    if(isRemotePlaybackLockActive())return;
    setSourceUpdatePlaybackActive(true);
    closeFullscreenChannelMenu();
    saveCriticalEngineStage("TUNE_STAGE","fullscreen layout enter begin");
    enterFullScreenPlayback();
    saveCriticalEngineStage("TUNE_STAGE","fullscreen layout entered");
    Channel c=channels.get(selected);
    if(c.backups==null||c.backups.isEmpty()){
      saveLiveDiagnostic("SOURCE_SKIP","channel="+c.name+" has no playable URL entries");
      info.setText(displayChannelName(c)+" 没有可用播放地址，正在跳过…");
      advanceToNextChannelAutomatically("当前频道没有可用播放地址");
      return;
    }
    if(c.activeSource<0||c.activeSource>=c.backups.size())c.activeSource=0;
    String requestedUrl=c.backups.get(c.activeSource);
    if(!SourceSelectionPolicy.hasSourceAddress(requestedUrl)||isSourceBlocked(c,requestedUrl)){
      int next=findNextUnblockedSource(c,c.activeSource,1);
      if(next<0){stopFailedLiveSession("本频道没有有效的未屏蔽线路，请在换源菜单中检查");return;}
      c.activeSource=next;
    }
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    cancelAudioTrackCheck();
    audioTrackPresent=false;audioTrackSupported=false;audioTrackSelected=false;audioPositionAdvancing=false;videoTrackPresent=false;videoTrackSupported=false;videoTrackSelected=false;
    videoTrackSelectedAt=0L;
    watchdogLastPosition=-1L;
    watchdogLastAdvanceAt=android.os.SystemClock.elapsedRealtime();
    sourceAttemptStartedAt=android.os.SystemClock.elapsedRealtime();
    sourceFirstLoadAt=0L;
    sourceReadyAt=0L;
    lastRecoveryBudgetRefreshAt=0L;
    lastVideoPipelineActivityAt=0L;
    resetLiveRenderedEvidence();lastLiveAudioWarningAt=0L;
    sourceReadyRecorded=false;
    sourceStableRecorded=false;
    sourceFirstFrameRecorded=false;
    sourceFirstLoadCompleted=false;
    sourceFailureRecorded=false;
    sourceFailureCooldownMs=120000L;
    liveWindowRecoveryAttempted=false;
    sourceWasBuffering=false;
    recordSourceMetric(c.backups.get(c.activeSource),"attempt",0L);
    saveLastChannel();
    final long generation=++playbackGeneration;
    currentMediaId=generation+":"+selected+":"+c.activeSource;
    info.setText("正在尝试："+displayChannelName(c)+"（源 "+(c.activeSource+1)+"/"+c.backups.size()+"）");
    updateLiveChannelSelection(selected);
    saveLiveDiagnostic("SOURCE_START","channel="+c.name+" sourceIndex="+(c.activeSource+1)+" total="+c.backups.size()+" route="+StableKey.url(SourceSelectionPolicy.endpointKey(c.backups.get(c.activeSource))).substring(0,19));
    try{
    if(player==null){
      recordDecoderInventoryOnce();
      saveCriticalEngineStage("TUNE_STAGE","Media3 player construction begin");
      if(!acquireDecoderOwnership())throw new IllegalStateException("decoder owner unavailable");
      player=PlaybackPlayerFactory.createLivePlayer(this,isDecoderFallbackEnabled());
      saveCriticalEngineStage("TUNE_STAGE","Media3 player constructed");
      saveCriticalEngineStage("TUNE_STAGE","Media3 PlayerView attach begin");
      video.setPlayer(player);normalizeFrameLayoutChildren(liveStage);
      saveCriticalEngineStage("TUNE_STAGE","Media3 PlayerView attached");
      // Avoid per-frame reflection callbacks on older TV firmware; the playback
      // watchdog below monitors the player timeline without touching codec callbacks.
      final ExoPlayer listenerOwner=player;
      player.addAnalyticsListener(new AnalyticsListener(){
        @Override public void onLoadError(AnalyticsListener.EventTime eventTime,LoadEventInfo loadEventInfo,MediaLoadData mediaLoadData,IOException error,boolean wasCanceled){
          if(listenerOwner!=MainActivity.this.player||!isCurrentLiveAnalyticsEvent(eventTime))return;
          String detail="uriHost="+(loadEventInfo==null||loadEventInfo.uri==null?"?":String.valueOf(loadEventInfo.uri.getHost()))
              +" bytes="+(loadEventInfo==null?0:loadEventInfo.bytesLoaded)
              +" canceled="+wasCanceled+" exception="+error.getClass().getSimpleName()+": "+error.getMessage();
          if(error instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException){
            androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException http=(androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)error;
            detail+=" HTTP="+http.responseCode;
          }
          saveLiveDiagnostic("LOAD_ERROR",detail);
        }
        @Override public void onLoadCompleted(AnalyticsListener.EventTime eventTime,LoadEventInfo loadEventInfo,MediaLoadData mediaLoadData){
          // A single ExoPlayer instance is reused across channel changes. Ignore
          // analytics events whose timeline belongs to an earlier MediaItem;
          // otherwise an old source can falsely mark the newly selected source
          // as having completed its first load.
          if(listenerOwner!=MainActivity.this.player||loadEventInfo==null||sourceFirstLoadCompleted
              ||!isCurrentLiveAnalyticsEvent(eventTime))return;
          sourceFirstLoadCompleted=true;
          sourceFirstLoadAt=android.os.SystemClock.elapsedRealtime();
          saveLiveDiagnostic("LOAD_FIRST_OK","uriHost="+(loadEventInfo.uri==null?"?":String.valueOf(loadEventInfo.uri.getHost()))+" bytes="+loadEventInfo.bytesLoaded);
        }
        @Override public void onAudioPositionAdvancing(AnalyticsListener.EventTime eventTime,long playoutStartSystemTimeMs){
          if(listenerOwner==MainActivity.this.player&&isCurrentLiveAnalyticsEvent(eventTime))audioPositionAdvancing=true;
        }
        @Override public void onRenderedFirstFrame(AnalyticsListener.EventTime eventTime,Object output,long renderTimeMs){
          if(listenerOwner!=MainActivity.this.player||sourceFirstFrameRecorded
              ||!isCurrentLiveAnalyticsEvent(eventTime))return;
          // Reject even a matching timeline if the renderer's actual frame time
          // predates this media assignment; delivery can be delayed on the UI queue.
          if(!revealLivePicture(listenerOwner,currentMediaId,renderTimeMs))return;
          sourceFirstFrameRecorded=true;
          long firstFrameAt=android.os.SystemClock.elapsedRealtime();
          lastVideoPipelineActivityAt=firstFrameAt;
          String renderedUrl=currentSourceUrl();
          long tuneMs=Math.max(0L,firstFrameAt-sourceAttemptStartedAt);
          recordSourceMetric(renderedUrl,"firstFrame",tuneMs);
          // First completed load is not necessarily the first byte. Keep the
          // phases separate and never label this value as connection latency.
          long firstLoadMs=sourceFirstLoadAt>0L
              ?Math.max(0L,sourceFirstLoadAt-sourceAttemptStartedAt):-1L;
          long afterLoadMs=sourceFirstLoadAt>0L&&firstFrameAt>=sourceFirstLoadAt
              ?firstFrameAt-sourceFirstLoadAt:-1L;
          saveLiveDiagnostic("LIVE_TUNE_TIMING","firstLoadCompletedMs="+firstLoadMs
              +" firstFrameMs="+tuneMs+" postLoadMs="+afterLoadMs
              +" engine=Media3 sourceIndex="+(selected>=0&&selected<channels.size()
                  ?channels.get(selected).activeSource+1:0));
          // First frame is useful timing evidence, but not yet a healthy TV source.
          // Last-known-good is recorded only after sustained audio and video checks.
        }
        @Override public void onVideoFrameProcessingOffset(AnalyticsListener.EventTime eventTime,
            long totalProcessingOffsetUs,int frameCount){
          if(listenerOwner!=MainActivity.this.player||frameCount<=0
              ||!isCurrentLiveAnalyticsEvent(eventTime))return;
          lastVideoPipelineActivityAt=android.os.SystemClock.elapsedRealtime();
        }
        @Override public void onAudioCodecError(AnalyticsListener.EventTime eventTime,Exception error){
          // Codec callbacks can be recoverable. Record diagnostics, but let Player.Listener
          // and the sustained-output watchdog decide whether the source actually failed.
          if(listenerOwner==MainActivity.this.player&&isCurrentLiveAnalyticsEvent(eventTime)){
            lastLiveAudioWarningAt=android.os.SystemClock.elapsedRealtime();
            pauseStableDiscovery();
            recordSourceMetric(currentSourceUrl(),"audioCodecError",0L);
          }
        }
        @Override public void onAudioSinkError(AnalyticsListener.EventTime eventTime,Exception error){
          // A single sink warning is not proof of a dead stream; avoid premature failover.
          if(listenerOwner==MainActivity.this.player&&isCurrentLiveAnalyticsEvent(eventTime)){
            lastLiveAudioWarningAt=android.os.SystemClock.elapsedRealtime();
            pauseStableDiscovery();
            recordSourceMetric(currentSourceUrl(),"audioSinkError",0L);
          }
        }
        @Override public void onVideoCodecError(AnalyticsListener.EventTime eventTime,Exception error){
          // Keep video decoder diagnostics separate from confirmed source failures.
          if(listenerOwner==MainActivity.this.player&&isCurrentLiveAnalyticsEvent(eventTime))
            recordSourceMetric(currentSourceUrl(),"videoCodecError",0L);
        }
      });
      // Track sustained renderer output, not decoder metadata. Metadata callbacks can
      // continue while frames are dropped or never reach the output surface, which
      // previously let an audio-only/black-screen stream evade the video watchdog.
      // Media3 reports the accumulated processing offset for frames handled by the
      // video renderer; accept only current-item events with at least one frame.
      player.addListener(new Player.Listener(){
        public void onTracksChanged(Tracks tracks){
          if(!isCurrent())return;
          boolean audioPresent=false,audioSupported=false,audioSelected=false,videoPresent=false,videoSupported=false,videoSelected=false;
          for(Tracks.Group group:tracks.getGroups()){
            if(group.getType()==C.TRACK_TYPE_AUDIO){
              audioPresent=true;
              if(group.isSupported())audioSupported=true;
              if(group.isSelected())audioSelected=true;
            }else if(group.getType()==C.TRACK_TYPE_VIDEO){
              videoPresent=true;
              if(group.isSupported())videoSupported=true;
              if(group.isSelected())videoSelected=true;
            }
          }
          audioTrackPresent=audioPresent;
          audioTrackSupported=audioSupported;
          if(!audioSelected||!audioSupported)audioPositionAdvancing=false;
          audioTrackSelected=audioSelected;
          videoTrackPresent=videoPresent;
          videoTrackSupported=videoSupported;
          boolean videoWasSelected=videoTrackSelected;
          videoTrackSelected=videoSelected;
          if(!TrackHealthPolicy.hasConfirmedTvOutput(audioTrackPresent,audioTrackSupported,
              audioTrackSelected,audioPositionAdvancing,videoTrackPresent,videoTrackSupported,
              videoTrackSelected,sourceFirstFrameRecorded))pauseStableDiscovery();
          if(videoSelected&&(!videoWasSelected||videoTrackSelectedAt<=0L)){
            videoTrackSelectedAt=listenerOwner.getPlaybackState()==Player.STATE_READY
                ?android.os.SystemClock.elapsedRealtime():0L;
          }else if(!videoSelected)videoTrackSelectedAt=0L;
          // Start first-frame monitoring as soon as a video track is selected,
          // not only after the first frame arrives; this catches black/no-frame streams.
        }
        private boolean isCurrent(){
          if(listenerOwner!=MainActivity.this.player||currentMediaId.isEmpty()
              ||currentVodPanel!=null||!activityStarted)return false;
          MediaItem item=listenerOwner.getCurrentMediaItem();
          return item!=null&&currentMediaId.equals(item.mediaId);
        }
        public void onPlayerError(PlaybackException e){
          if(!isCurrent())return;
          pauseStableDiscovery();
          saveLiveDiagnostic("PLAYER_ERROR","code="+e.errorCode+" name="+e.getErrorCodeName()+" cause="+(e.getCause()==null?"":e.getCause().getClass().getSimpleName()+": "+e.getCause().getMessage()));
          // HLS live playback can fail only because its playback position fell
          // behind the sliding live window. Re-seek to the live edge once before
          // marking this URL failed; switching sources immediately would be wasteful.
          if(e.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW&&!liveWindowRecoveryAttempted){
            liveWindowRecoveryAttempted=true;
            cancelBufferingFallback();
            cancelPlaybackWatchdog();
            cancelAudioTrackCheck();
            info.setText("直播位置已过期，正在重新连接到直播现场…");
            player.seekToDefaultPosition();
            player.prepare();
            player.play();
            return;
          }
          String errorName=e.getErrorCodeName();
          int httpStatus=extractHttpStatusCode(e.getCause());
          PlaybackErrorPolicy.Disposition disposition=PlaybackErrorPolicy.classify(errorName,httpStatus);
          sourceFailureCooldownMs=disposition.cooldownMs;
          if(disposition.skipSameUrlVlcRetry){
            // A failed connection/timeout is endpoint availability, not a codec
            // compatibility problem. Trying the same URL through a second engine
            // can add another full connection timeout before we reach a backup.
            saveLiveDiagnostic("NETWORK_ERROR_DIRECT_FAILOVER","error="+errorName);
            advanceToNextSourceAutomatically("播放源网络连接失败，直接切换备用源（"+errorName+"）");
            return;
          }
          if(!tryStartLibVlcFallback("Media3 播放错误（代码 "+e.errorCode+"）")){
            advanceToNextSourceAutomatically("当前播放源失效（错误代码 "+e.errorCode+"）");
          }
        }
        @Override public void onIsPlayingChanged(boolean playing){
          if(isCurrent()&&!playing)pauseStableDiscovery();
        }
        public void onPlaybackStateChanged(int st){
          if(!isCurrent())return;
          if(st!=Player.STATE_READY)pauseStableDiscovery();
          saveLiveDiagnostic("STATE",st==Player.STATE_IDLE?"IDLE":st==Player.STATE_BUFFERING?"BUFFERING":st==Player.STATE_READY?"READY":st==Player.STATE_ENDED?"ENDED":"STATE_"+st);
          if(st==Player.STATE_BUFFERING){
            cancelPlaybackWatchdog();
            cancelAudioTrackCheck();
            resetLiveRenderedEvidence();
            boolean firstBuffering=!sourceWasBuffering;
            if(sourceReadyRecorded&&firstBuffering)recordSourceMetric(currentSourceUrl(),"buffer",0L);
            sourceWasBuffering=true;
            if(firstBuffering)showChannelZapMessage(sourceReadyRecorded?"网络波动，正在缓冲…":"正在连接播放源…");
            // Do not charge network buffering time against the decoder's first-frame deadline.
            if(!sourceFirstFrameRecorded)videoTrackSelectedAt=0L;
            scheduleBufferingFallback();
          }else if(st==Player.STATE_READY){
            sourceWasBuffering=false;
            if(!sourceReadyRecorded){
              sourceReadyRecorded=true;
              recordSourceMetric(currentSourceUrl(),"ready",android.os.SystemClock.elapsedRealtime()-sourceAttemptStartedAt);
            }
            cancelBufferingFallback();
            // Avoid automatic source switching based on a short audio-startup window on older TV SoCs.
            // Do not reset automaticSwitchCount here: a source that starts and then
            // freezes must not create an endless source-switch loop.
            watchdogLastPosition=player.getCurrentPosition();
            watchdogLastAdvanceAt=android.os.SystemClock.elapsedRealtime();
            sourceReadyAt=watchdogLastAdvanceAt;
            lastRecoveryBudgetRefreshAt=0L;
            // Begin the first-frame deadline only after READY; earlier BUFFERING time is excluded.
            if(videoTrackSelected&&!sourceFirstFrameRecorded)videoTrackSelectedAt=watchdogLastAdvanceAt;
            // Start the frame-stall timer only after the player reports READY.
            lastVideoPipelineActivityAt=watchdogLastAdvanceAt;
            resetLiveRenderedEvidence();sampleLiveRenderedEvidence(watchdogLastAdvanceAt);
            schedulePlaybackWatchdog();
            // Run the audio-track/output check only after READY; buffering can
            // legitimately pause audio advancement and must not trigger a false failover.
            scheduleAudioTrackCheck();
            if(selected>=0&&selected<channels.size()){
              saveLastChannel();
              info.setText("正在播放："+displayChannelName(channels.get(selected))+"（源 "+(channels.get(selected).activeSource+1)+"/"+channels.get(selected).backups.size()+"）");
              if(manualSourceSwitch){
                showChannelZapMessage(displayChannelName(channels.get(selected))+"\n已连接第 "+(channels.get(selected).activeSource+1)+" / "+channels.get(selected).backups.size()+" 个源");
                manualSourceSwitch=false;
              }
            }
          }else if(st==Player.STATE_IDLE||st==Player.STATE_ENDED){
            cancelPlaybackWatchdog();
            if(st==Player.STATE_IDLE)cancelBufferingFallback();
            if(st==Player.STATE_ENDED){
              // A previously producing stream ending is primarily an endpoint
              // problem. Preserve a compatibility attempt for unproven output.
              if(!PlaybackErrorPolicy.shouldTryVlcForEndedLive(sourceFirstFrameRecorded,audioPositionAdvancing)
                  ||!tryStartLibVlcFallback("Media3 检测到直播流已结束")){
                advanceToNextSourceAutomatically("当前播放流已结束");
              }
            }
          }
        }
      });
    }
    // Explicitly drain the previous live item before assigning another source. This
    // prevents stale audio/renderers from surviving rapid channel/source changes.
    saveCriticalEngineStage("TUNE_STAGE","previous Media3 item replace begin");
    MediaItem item=PlaybackPlayerFactory.buildMediaItem(c.backups.get(c.activeSource),currentMediaId);
    saveCriticalEngineStage("TUNE_STAGE","media item assignment begin");
    // setMediaItem replaces the previous playlist atomically; a separate stop()+
    // clearMediaItems() adds two redundant synchronous player-thread round trips.
    livePictureGate.arm(player,currentMediaId,android.os.SystemClock.elapsedRealtime());
    player.setMediaItem(item);
    saveCriticalEngineStage("TUNE_STAGE","media item assigned");
    saveCriticalEngineStage("TUNE_STAGE","Media3 prepare begin");
    player.prepare();
    saveCriticalEngineStage("TUNE_STAGE","Media3 prepare returned");
    saveCriticalEngineStage("TUNE_STAGE","Media3 play begin");
    player.play();
    saveCriticalEngineStage("TUNE_STAGE","Media3 play returned");
    }catch(RuntimeException e){
      String failedMediaId=currentMediaId;
      saveCriticalEngineStage("TUNE_RUNTIME_ERROR",e.getClass().getSimpleName()+": "+e.getMessage());
      releaseLivePlayerSafely();
      info.setText("直播播放器启动异常，正在尝试备用源…");
      if(!failedMediaId.isEmpty())advanceToNextSourceAutomatically("直播播放器启动异常");
    }
  }
  private boolean tryStartLibVlcFallback(String reason){
    if(isAnyPlaybackLockActive()||currentVodPanel!=null||!activityStarted)return false;
    if(libVlcAttemptedForSource||selected<0||selected>=channels.size()||currentSourceUrl().isEmpty())return false;
    coverLivePicture();
    libVlcAttemptedForSource=true;
    libVlcVideoOutputReady=false;
    libVlcAudioTrackCheckAttempts=0;
    libVlcAudioTrackReady=false;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    final String url=currentSourceUrl();
    cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();
    releaseLivePlayerSafely();
    if(decoderReleaseUnconfirmed||libVlcReleaseInProgress||PROCESS_VLC_TEARDOWNS.get()>0){
      if(info!=null)info.setText("旧播放器资源未确认释放，已停止自动回退");
      return true;
    }
    if(video!=null)video.setVisibility(View.GONE);
    if(!isTouchDevice())restoreFullscreenInputFocus();
    if(vlcVideoLayout==null){if(video!=null)video.setVisibility(View.VISIBLE);return false;}
    vlcVideoLayout.setVisibility(View.VISIBLE);
    info.setText(reason+"，正在切换独立 VLC 播放器…");
    saveLiveDiagnostic("ENGINE_FALLBACK","Media3 -> LibVLC; "+reason);
    try{
      if(!acquireDecoderOwnership())return false;
      final LibVlcPlaybackEngine engine=new LibVlcPlaybackEngine(this,vlcVideoLayout);
      libVlcEngine=engine;
      livePictureGate.arm(engine,expectedMediaId);
      engine.setListener(new LibVlcPlaybackEngine.Listener(){
        // Native VLC events may already be queued on the UI thread when an engine is
        // released. Match both the channel generation and the exact engine instance so
        // a late callback from an old native player cannot revive or fail over a new tune.
        private boolean isCurrent(){return libVlcEngine==engine&&selected==expectedChannel
            &&expectedMediaId.equals(currentMediaId)&&libVlcAttemptedForSource
            &&activityStarted&&currentVodPanel==null&&!isAnyPlaybackLockActive();}
        public void onPlaying(){uiHandler.post(()->{
          if(!isCurrent())return;
          if(!libVlcVideoOutputReady)info.setText("VLC 已进入播放状态，正在确认视频输出…");
          saveLiveDiagnostic("ENGINE_PLAYING","LibVLC Playing event; waiting for Vout event");
        });}
        public void onVideoOutput(int count){uiHandler.post(()->{
          if(!isCurrent())return;
          if(count>0){
            // Duplicate positive Vout events must not restart the health timer.
            if(libVlcVideoOutputReady)return;
            libVlcVideoOutputReady=true;
            // VLC exposes Vout creation, not a rendered-first-frame callback.
            // Its current engine may now show the new output; device compositor
            // timing still needs verification. This is not playback health evidence.
            revealLivePicture(engine,expectedMediaId,0L);
            pauseStableDiscovery();
            resetLibVlcHealthEvidence();
            libVlcStableRecorded=false;
            cancelLibVlcStartTimeout();
            usingLibVlc=true;
            sourceReadyAt=android.os.SystemClock.elapsedRealtime();
            info.setText("检测到 VLC 视频输出："+displayChannelName(channels.get(selected))+"（画面尚待确认）");
            showChannelZapMessage(displayChannelName(channels.get(selected))+"\\n已切换独立 VLC 播放器");
            saveLiveDiagnostic("ENGINE_VOUT","LibVLC reports "+count+" active video output(s); rendered frame still requires device validation");
            scheduleLibVlcWatchdog(expectedMediaId,expectedChannel);
            // A visible VLC video output is not enough: a video-only stream can
            // still leave the viewer without sound. Inspect audio tracks off the UI thread.
            if(libVlcAudioTrackCheckAttempts==0){
              libVlcAudioTrackCheckAttempts=1;
              uiHandler.postDelayed(()->{
                if(!isCurrent()||libVlcEngine!=engine)return;
                libVlcWorker.execute(()->{
                  if(libVlcEngine==engine)engine.inspectAudioTrack();
                });
              },5000L);
            }
          }else if(libVlcVideoOutputReady){
            libVlcVideoOutputReady=false;
            // A lost output may leave its last native frame on the surface.
            // Reuse the same cover until this exact engine reports output again.
            coverLivePicture();
            livePictureGate.arm(engine,expectedMediaId);
            resetLibVlcHealthEvidence();
            pauseStableDiscovery();
            saveLiveDiagnostic("ENGINE_VOUT_LOST","LibVLC video output count returned to zero");
          }
        });}
        public void onAudioTrackState(int trackCount,int selectedTrackId){uiHandler.post(()->{
          if(!isCurrent())return;
          saveLiveDiagnostic("VLC_AUDIO_TRACK_CHECK","tracks="+trackCount+" selected="+selectedTrackId);
          libVlcAudioTrackReady=trackCount>0&&selectedTrackId>=0;
          if(!libVlcAudioTrackReady){
            resetLibVlcHealthEvidence();
            pauseStableDiscovery();
            if(libVlcAudioTrackCheckAttempts<2){
              // Track metadata can arrive after the first video-output event.
              // Recheck once before discarding a potentially healthy live source.
              libVlcAudioTrackCheckAttempts++;
              saveLiveDiagnostic("VLC_AUDIO_TRACK_PENDING",
                  "tracks="+trackCount+" selected="+selectedTrackId+"; retrying once");
              uiHandler.postDelayed(()->{
                if(!isCurrent()||libVlcEngine!=engine)return;
                libVlcWorker.execute(()->{
                  if(libVlcEngine==engine)engine.inspectAudioTrack();
                });
              },3000L);
              return;
            }
            saveLiveDiagnostic("VLC_AUDIO_TRACK_MISSING",
                "LibVLC produced video output but no selectable audio track after two checks");
            cancelLibVlcStartTimeout();cancelLibVlcWatchdog();
            releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
            advanceToNextSourceAutomatically("VLC 未检测到可用音轨，正在切换备用源");
          }else{
            saveLiveDiagnostic("VLC_AUDIO_TRACK_READY",
                "LibVLC selected audio track "+selectedTrackId+"; audible output still requires device validation");
          }
        });}
        public void onBuffering(float percent){uiHandler.post(()->{
          if(!isCurrent())return;
          libVlcRebuffering=percent<100f;
          if(percent>=100f){
            cancelLibVlcBufferingTimeout();
            saveLiveDiagnostic("VLC_BUFFER_RECOVERED","buffering_percent=100");
          }else{
            resetLibVlcHealthEvidence();
            pauseStableDiscovery();
            if(usingLibVlc)info.setText("VLC 正在缓冲："+Math.round(percent)+"%");
            scheduleLibVlcBufferingTimeout(expectedMediaId,expectedChannel,engine);
          }
        });}
        public void onFailure(String detail){uiHandler.post(()->{
          if(!isCurrent())return;
          cancelLibVlcStartTimeout();cancelLibVlcWatchdog();
          saveLiveDiagnostic("ENGINE_FAILURE","LibVLC "+detail);
          releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
          // advanceToNextSourceAutomatically records this failed source exactly once.
          advanceToNextSourceAutomatically("Media3 与 VLC 均无法播放当前源");
        });}
        public void onStopped(){uiHandler.post(()->{
          if(!isCurrent())return;
          saveLiveDiagnostic("ENGINE_STOPPED","LibVLC reported an unexpected stopped event; advancing to the next source");
          releaseLibVlcEngine();
          if(video!=null)video.setVisibility(View.VISIBLE);
          // Explicit release clears libVlcEngine before native stop(), so teardown
          // callbacks fail isCurrent(). A current-engine Stopped event is therefore
          // an unexpected playback termination and should use the shared failover guard.
          advanceToNextSourceAutomatically("VLC 播放已意外停止，正在尝试备用源");
        });}
        public void onEndReached(){uiHandler.post(()->{
          if(!isCurrent())return;
          saveLiveDiagnostic("ENGINE_END_REACHED","LibVLC stream reached end of media; advancing to next source");
          releaseLibVlcEngine();
          if(video!=null)video.setVisibility(View.VISIBLE);
          // A live endpoint that ends unexpectedly is a failed source too. Route
          // through the shared guard so simultaneous VLC/Media3 callbacks cannot
          // advance twice for the same playback generation.
          advanceToNextSourceAutomatically("VLC 播放源已结束，正在尝试备用源");
        });}
      });
      // Arm the deadline BEFORE native initialization. A blocked JNI call must never
      // leave the viewer indefinitely on the "switching VLC" screen.
      cancelLibVlcStartTimeout();
      libVlcStartTimeout=()->{
        libVlcStartTimeout=null;
        if(libVlcEngine==engine&&selected==expectedChannel
            &&expectedMediaId.equals(currentMediaId)&&!libVlcVideoOutputReady&&libVlcAttemptedForSource){
          saveLiveDiagnostic("ENGINE_TIMEOUT","LibVLC produced no video output within 18 seconds (startup ran off UI thread)");
          releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
          advanceToNextSourceAutomatically("VLC 连接超时，已释放播放器并尝试备用源");
        }
      };
      uiHandler.postDelayed(libVlcStartTimeout,18000L);
      libVlcWorker.execute(()->{
        try{
          // onStop(), a tune change, or the startup deadline may invalidate this
          // queued native operation before its worker turn begins.
          if(libVlcEngine!=engine){engine.release();return;}
          saveCriticalEngineStage("ENGINE_STAGE","LibVLC initialize begin (worker)");
          engine.initialize();
          saveCriticalEngineStage("ENGINE_STAGE","LibVLC initialize completed (worker)");
          if(libVlcEngine!=engine){engine.release();return;}
          saveCriticalEngineStage("ENGINE_STAGE","LibVLC play begin (worker)");
          engine.play(url);
          saveCriticalEngineStage("ENGINE_STAGE","LibVLC play returned (worker)");
          // A deadline can expire while a vendor JNI call is blocked. If that call
          // eventually returns, do not leave an obsolete player running invisibly.
          if(libVlcEngine!=engine)engine.release();
        }catch(LinkageError|RuntimeException failure){
          uiHandler.post(()->{
            if(libVlcEngine!=engine||selected!=expectedChannel
                ||!expectedMediaId.equals(currentMediaId))return;
            cancelLibVlcStartTimeout();
            saveLiveDiagnostic("ENGINE_INIT_ERROR",
                failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));
            releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
            advanceToNextSourceAutomatically("VLC 初始化失败，正在尝试备用源");
          });
        }
      });
      return true;
    }catch(LinkageError ex){
      // Missing/incompatible native ABI libraries must not terminate the TV app.
      // Treat this engine as unavailable for this source and continue Media3 failover.
      cancelLibVlcStartTimeout();releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
      saveLiveDiagnostic("ENGINE_LINKAGE_ERROR",ex.getClass().getSimpleName()+": "+ex.getMessage());
      return false;
    }catch(RuntimeException ex){
      cancelLibVlcStartTimeout();releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
      saveLiveDiagnostic("ENGINE_INIT_ERROR",ex.getClass().getSimpleName()+": "+ex.getMessage());
      return false;
    }
  }
  private void resetLibVlcHealthEvidence(){
    ++libVlcHealthEpoch;
    libVlcStableChecks=0;
    libVlcLastHealthClockMs=0L;
    libVlcLastHealthAt=0L;
    libVlcVideoProgress.reset();
    libVlcAudioProgress.reset();
  }
  private void scheduleLibVlcWatchdog(String expectedMediaId,int expectedChannel){
    cancelLibVlcWatchdog();
    libVlcWatchdog=()->{
      libVlcWatchdog=null;
      if(!usingLibVlc||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId)||libVlcEngine==null)return;
      final LibVlcPlaybackEngine expectedEngine=libVlcEngine;
      final long sampleEpoch=libVlcHealthEpoch;
      // Native health queries may block behind a vendor decoder. Run one
      // outstanding check on the serial lifecycle worker, with a UI deadline.
      final Runnable timeout=()->{
        if(libVlcEngine!=expectedEngine||!currentMediaId.equals(expectedMediaId)
            ||selected!=expectedChannel||!usingLibVlc)return;
        libVlcWatchdog=null;
        saveLiveDiagnostic("VLC_HEALTH_TIMEOUT","native health check exceeded 8 seconds");
        releaseLibVlcEngine();
        if(video!=null)video.setVisibility(View.VISIBLE);
        advanceToNextSourceAutomatically("VLC 状态检测超时");
      };
      libVlcWatchdog=timeout;
      uiHandler.postDelayed(timeout,8000L);
      libVlcWorker.execute(()->{
        if(libVlcEngine!=expectedEngine)return;
        boolean playing;
        long clock=0L;
        boolean audio=false;
        long[] output=null;
        try{playing=expectedEngine.isPlaying();}
        catch(RuntimeException|LinkageError failure){playing=false;}
        if(playing){
          try{clock=expectedEngine.getTimeMs();audio=expectedEngine.hasSelectedAudioTrack();}
          catch(RuntimeException|LinkageError failure){clock=0L;audio=false;}
          try{output=expectedEngine.outputCounters();}
          catch(RuntimeException|LinkageError failure){output=null;}
        }
        final boolean reportsPlaying=playing;
        final long playbackClock=clock;
        final boolean currentAudioSelected=audio;
        final long displayed=output==null?-1L:output[0];
        final long playedAudio=output==null?-1L:output[1];
        uiHandler.post(()->{
          if(libVlcWatchdog!=timeout||libVlcEngine!=expectedEngine
              ||!currentMediaId.equals(expectedMediaId)||selected!=expectedChannel)return;
          cancelLibVlcWatchdog();
          if(sampleEpoch!=libVlcHealthEpoch){
            // Buffering/Vout/audio changed while this native query was in flight.
            // Preserve the bounded native timeout, but never consume stale evidence.
            scheduleLibVlcWatchdog(expectedMediaId,expectedChannel);
            return;
          }
          finishLibVlcHealthCheck(expectedMediaId,expectedChannel,reportsPlaying,playbackClock,currentAudioSelected,displayed,playedAudio);
        });
      });
    };
    uiHandler.postDelayed(libVlcWatchdog,12000L);
  }
  private void finishLibVlcHealthCheck(String expectedMediaId,int expectedChannel,boolean reportsPlaying,long playbackClock,boolean currentAudioSelected,long displayed,long playedAudio){
      if(libVlcVideoOutputReady&&libVlcRebuffering){
        // Rebuffering follows the user's timeout setting, not this health poll.
        resetLibVlcHealthEvidence();
        pauseStableDiscovery();
        scheduleLibVlcWatchdog(expectedMediaId,expectedChannel);
        return;
      }
      if(!libVlcVideoOutputReady||!reportsPlaying){
        libVlcStableChecks=0;
        saveLiveDiagnostic("ENGINE_STALLED",!libVlcVideoOutputReady?"LibVLC video output is missing":"LibVLC no longer reports playing");
        releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
        // advanceToNextSourceAutomatically owns the single failure-score update.
        advanceToNextSourceAutomatically("VLC 播放中断");
        return;
      }
      // Vout plus isPlaying only proves that VLC still owns a video output and
      // reports a live session. It does NOT prove that a decoded frame reached the
      // TV surface. Never promote a VLC source to last-known-good or QoE "stable"
      // from these signals alone; otherwise a black-screen source can outrank a
      // source whose first frame was actually rendered by Media3.
      long now=android.os.SystemClock.elapsedRealtime();
      if(!TrackHealthPolicy.vlcHealthIntervalFresh(libVlcLastHealthAt,now)){
        // A delayed observation is a new baseline, not a continuation of
        // old health or stationary-counter evidence.
        resetLibVlcHealthEvidence();
        SourceUpdateActivityPolicy.revokeStableLive();
      }
      libVlcLastHealthAt=now;
      boolean advancing=TrackHealthPolicy.vlcClockAdvanced(libVlcLastHealthClockMs,playbackClock);
      libVlcLastHealthClockMs=playbackClock;
      boolean videoAdvanced=libVlcVideoProgress.observe(displayed);
      boolean audioAdvanced=libVlcAudioProgress.observe(playedAudio);
      boolean outputsAdvanced=videoAdvanced&&audioAdvanced;
      if(libVlcVideoProgress.stalled()||libVlcAudioProgress.stalled()){
        String detail="video_stalled="+libVlcVideoProgress.stalled()+" audio_stalled="+libVlcAudioProgress.stalled();
        pauseStableDiscovery();
        saveLiveDiagnostic("VLC_OUTPUT_STALLED",detail);
        releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
        advanceToNextSourceAutomatically("VLC 音视频输出持续停滞，正在尝试备用源");
        return;
      }
      if(!advancing||!currentAudioSelected||!outputsAdvanced){
        libVlcStableChecks=0;
        SourceUpdateActivityPolicy.revokeStableLive();
        saveLiveDiagnostic("VLC_HEALTH_UNCONFIRMED","clock_advancing="+advancing+" audio_selected="+currentAudioSelected+" counters_advanced="+outputsAdvanced+"; background discovery paused");
        scheduleLibVlcWatchdog(expectedMediaId,expectedChannel);
        return;
      }
      libVlcStableChecks=Math.min(2,libVlcStableChecks+1);
      if(TrackHealthPolicy.canGrantVlcDiscovery(libVlcVideoOutputReady,libVlcAudioTrackReady,
          libVlcRebuffering,reportsPlaying,libVlcStableChecks,lastStableDiscoveryInteraction,now)
          &&canGrantStableDiscoveryUi()){
        boolean alreadyPermitted=SourceUpdateActivityPolicy.permitsInitialPlaybackWork(now);
        SourceUpdateActivityPolicy.reportConfirmedVlcStableLive(now,libVlcStableChecks);
        if(!alreadyPermitted&&SourceUpdateActivityPolicy.permitsInitialPlaybackWork(now))
          saveLiveDiagnostic("VLC_DISCOVERY_PERMITTED","bounded low-priority discovery; actual output unverified");
      }else SourceUpdateActivityPolicy.revokeStableLive();
      if(!libVlcStableRecorded&&libVlcStableChecks>=2){
        libVlcStableRecorded=true;
        saveLiveDiagnostic("VLC_ENGINE_HEALTHY_UNVERIFIED",
            "LibVLC maintained Vout, selected audio, advancing engine clock and output counters across watchdog checks; "
            +"actual rendered-frame visibility is unverified, so this source was not "
            +"promoted to last-known-good and no stable-play success was recorded");
      }
      scheduleLibVlcWatchdog(expectedMediaId,expectedChannel);
  }
  private void scheduleLibVlcBufferingTimeout(String expectedMediaId,int expectedChannel,LibVlcPlaybackEngine expectedEngine){
    if(libVlcBufferingTimeout!=null||!libVlcVideoOutputReady)return;
    final int seconds=getSharedPreferences("mytv",0).getInt("buffer_switch_seconds",12);
    if(seconds<=0)return;
    libVlcBufferingTimeout=()->{
      libVlcBufferingTimeout=null;
      if(libVlcEngine!=expectedEngine||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId)||!usingLibVlc)return;
      if(!libVlcVideoOutputReady)return; // Initial connection remains governed by the startup deadline.
      if(!libVlcRebuffering)return;
      saveLiveDiagnostic("VLC_REBUFFER_TIMEOUT","buffering continued for "+seconds+"s after video output");
      releaseLibVlcEngine();if(video!=null)video.setVisibility(View.VISIBLE);
      advanceToNextSourceAutomatically("VLC 连续缓冲超过 "+seconds+" 秒");
    };
    uiHandler.postDelayed(libVlcBufferingTimeout,seconds*1000L);
  }
  private void cancelLibVlcBufferingTimeout(){if(libVlcBufferingTimeout!=null){uiHandler.removeCallbacks(libVlcBufferingTimeout);libVlcBufferingTimeout=null;}}
  private void cancelLibVlcStartTimeout(){if(libVlcStartTimeout!=null){uiHandler.removeCallbacks(libVlcStartTimeout);libVlcStartTimeout=null;}}
  private void cancelLibVlcWatchdog(){if(libVlcWatchdog!=null){uiHandler.removeCallbacks(libVlcWatchdog);libVlcWatchdog=null;}}
  private void releaseLibVlcEngine(){
    releaseLibVlcEngine(null);
  }
  private void finishLibVlcRelease(boolean released){
    if(pendingVlcTeardowns>0)--pendingVlcTeardowns;
    // A terminal failure is not an in-flight operation. Discard pending starts,
    // never execute them, and leave process-wide decoder quarantine closed.
    if(decoderReleaseUnconfirmed){
      cancelSingleChannelRefresh();
      ++liveTuneRequestSerial;
      revokePendingLiveTune();
      revokePendingVodStart();
      libVlcReleaseCallbacks.clear();
      if(!released&&activityStarted&&!isFinishing()&&!isDestroyed()){
        String message="播放器资源未确认释放，请强制停止应用后重开";
        if(info!=null)info.setText(message);
        toast(message);
      }
    }
    if(pendingVlcTeardowns!=0)return;
    libVlcReleaseInProgress=false;
    releaseDecoderOwnershipIfIdle();
    if(libVlcReleaseNotice!=null){uiHandler.removeCallbacks(libVlcReleaseNotice);libVlcReleaseNotice=null;}
    if(decoderReleaseUnconfirmed)return;
    // Keep playback admission closed across the release -> next tune handoff.
    // A worker on another thread must never observe a transient idle window.
    ArrayList<Runnable> callbacks=new ArrayList<>(libVlcReleaseCallbacks);
    libVlcReleaseCallbacks.clear();
    for(Runnable callback:callbacks){
      try{callback.run();}
      catch(RuntimeException failure){
        saveLiveDiagnostic("VLC_RELEASE_CALLBACK_ERROR",failure.getClass().getSimpleName());
      }
    }
    if(sourceUpdatePlaybackMayClear())setSourceUpdatePlaybackActive(false);
    reloadDeferredCatalogueIfIdle();
  }
  private void releaseLibVlcEngine(Runnable afterRelease){
    cancelLibVlcStartTimeout();cancelLibVlcWatchdog();cancelLibVlcBufferingTimeout();
    LibVlcPlaybackEngine old=libVlcEngine;
    if(old!=null||usingLibVlc){coverLivePicture();pauseStableDiscovery();}
    libVlcEngine=null;usingLibVlc=false;libVlcRebuffering=false;libVlcVideoOutputReady=false;libVlcAudioTrackReady=false;resetLibVlcHealthEvidence();libVlcStableRecorded=false;
    if(vlcVideoLayout!=null)vlcVideoLayout.setVisibility(View.GONE);
    if(afterRelease!=null&&!decoderReleaseUnconfirmed){
      if(libVlcReleaseInProgress)libVlcReleaseCallbacks.add(afterRelease);
      else if(old==null){afterRelease.run();return;}
      else libVlcReleaseCallbacks.add(afterRelease);
    }
    if(old==null){releaseDecoderOwnershipIfIdle();return;}
    queueVlcTeardown(old::release);
  }
  /** In-flight accounting is separate from terminal decoder quarantine. */
  private void queueVlcTeardown(java.util.function.BooleanSupplier release){
    ++pendingVlcTeardowns;
    PROCESS_VLC_TEARDOWNS.incrementAndGet();
    libVlcReleaseInProgress=true;
    setSourceUpdatePlaybackActive(true);
    if(libVlcReleaseNotice!=null)uiHandler.removeCallbacks(libVlcReleaseNotice);
    libVlcReleaseNotice=()->{
      libVlcReleaseNotice=null;
      if(!libVlcReleaseInProgress||isFinishing()||isDestroyed()||!activityStarted)return;
      saveLiveDiagnostic("VLC_RELEASE_SLOW","native decoder release still unconfirmed after 8 seconds");
      if(info!=null)info.setText("旧播放器仍在释放，暂不能启动新播放器；若持续不恢复，请强制停止应用后重开");
      toast("播放器资源仍在释放，请勿重复换台");
    };
    uiHandler.postDelayed(libVlcReleaseNotice,8000L);
    Runnable teardown=()->{
      boolean released=false;
      try{
        released=release.getAsBoolean();
      }catch(Throwable failure){
        saveLiveDiagnostic("VLC_ASYNC_RELEASE_ERROR",failure.getClass().getSimpleName());
      }
      if(!released){
        // Publish terminal quarantine before removing the in-flight count.
        // Native state is unknown: no new decoder even after Activity recreation.
        decoderReleaseUnconfirmed=true;
        DECODER_OWNERSHIP.quarantine(decoderOwnerToken);
        saveLiveDiagnostic("VLC_RELEASE_BARRIER_RETAINED","native teardown not confirmed");
      }
      final boolean confirmed=released;
      PROCESS_VLC_TEARDOWNS.decrementAndGet();
      uiHandler.post(()->finishLibVlcRelease(confirmed));
    };
    try{libVlcWorker.execute(teardown);}
    catch(RuntimeException rejected){
      saveLiveDiagnostic("VLC_RELEASE_QUEUE_ERROR",rejected.getClass().getSimpleName());
      // Never bypass the serial native queue: an earlier JNI operation may
      // still be running. Mark the rejected operation terminal, not pending.
      decoderReleaseUnconfirmed=true;
      DECODER_OWNERSHIP.quarantine(decoderOwnerToken);
      PROCESS_VLC_TEARDOWNS.decrementAndGet();
      finishLibVlcRelease(false);
    }
  }
  private String currentSourceUrl(){
    if(selected<0||selected>=channels.size())return "";
    Channel c=channels.get(selected);
    return c.activeSource>=0&&c.activeSource<c.backups.size()?c.backups.get(c.activeSource):c.url;
  }
  // Persistent playback observations. READY latency is not the same as a rendered first frame;
  // buffering/error events come from the actual ExoPlayer session, not a URL probe.
  private int extractHttpStatusCode(Throwable error){
    Throwable current=error;
    for(int depth=0;current!=null&&depth<12;depth++,current=current.getCause()){
      if(current instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException){
        return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)current).responseCode;
      }
    }
    return 0;
  }
  private void recordCurrentSourceFailure(){
    if(sourceFailureRecorded)return;
    sourceFailureRecorded=true;
    String failedUrl=currentSourceUrl();
    recordSourceMetric(failedUrl,"failure",0L);
    // Temporarily cool down a failed endpoint to prevent repeated dead-source loops.
    SourceMemoryStore.coolDown(this,failedUrl,sourceFailureCooldownMs);
  }
  private boolean prepareAutomaticChannelSource(Channel c){
    // Empty catalogues retain the bounded next-channel recovery in startCurrentSource.
    if(c==null||c.backups==null||c.backups.isEmpty())return true;
    sortSourcesByObservedQuality(c);
    String remembered=SourceMemoryStore.getLastGood(this,SourceMemoryStore.channelIdentityKey(identity(c)));
    int index=SourceSelectionPolicy.findInitialSource(c.backups,remembered,blockedSourceUrls(c),
        SourceMemoryStore::isEndpointCoolingDown);
    if(index<0){
      stopFailedLiveSession("本频道没有可自动播放的未屏蔽线路，请在换源菜单中检查");
      return false;
    }
    c.activeSource=index;
    return true;
  }
  private void recordSourceMetric(String url,String event,long readyMs){
    SourceMemoryStore.recordMetric(getApplicationContext(),url,event,readyMs);
  }
  private HashMap<String,Double> observedSourceScores(List<String> urls){
    HashMap<String,Double> scores=new HashMap<>();
    if(urls==null||urls.isEmpty())return scores;
    org.json.JSONObject measured=cachedProbeScoreJson;
    long now=System.currentTimeMillis();
    for(String url:urls){
      try{scores.put(url,SourceMemoryStore.rankedScore(url,measured,now));}
      catch(Exception malformedUrl){scores.put(url,SourceQualityScorer.labelScore(url)*0.01);}
    }
    return scores;
  }
  private void sortSourcesByObservedQuality(Channel c){
    if(c==null||c.backups==null||c.backups.size()<2)return;
    String active=c.activeSource>=0&&c.activeSource<c.backups.size()?c.backups.get(c.activeSource):c.url;
    ArrayList<String> ordered=new ArrayList<>(c.backups);
    HashMap<String,Double> scores=observedSourceScores(ordered);
    ordered.sort((a,b)->Double.compare(scores.get(b),scores.get(a)));
    if(ordered.equals(c.backups))return;
    c.backups.clear();c.backups.addAll(ordered);
    int ix=c.backups.indexOf(active);c.activeSource=ix>=0?ix:0;
    // Quality re-ranking is a transient playback preference, not a channel
    // catalogue edit. Persisting here rewrote the entire Room directory during
    // every channel zap whose source scores changed, causing I/O contention.
    // SourceMemoryStore and QoE scores preserve real success/failure history;
    // the order is reconstructed from those metrics when the channel is tuned.

  }
  private void toggleNetworkSpeed(){
    boolean enabled=!getSharedPreferences("mytv",0).getBoolean("show_network_speed",false);
    getSharedPreferences("mytv",0).edit().putBoolean("show_network_speed",enabled).apply();
    if(networkSpeedOverlay!=null)networkSpeedOverlay.setVisibility(enabled?View.VISIBLE:View.GONE);
    updateNetworkSpeedMonitor();
    toast(enabled?"已开启右下角网速显示（本应用接收速度）":"已关闭网速显示");
  }
  private void updateNetworkSpeedMonitor(){
    if(networkSpeedUpdater!=null){uiHandler.removeCallbacks(networkSpeedUpdater);networkSpeedUpdater=null;}
    if(networkSpeedOverlay==null)return;
    boolean enabled=getSharedPreferences("mytv",0).getBoolean("show_network_speed",false);
    if(!enabled){networkSpeedOverlay.setVisibility(View.GONE);return;}
    networkSpeedOverlay.setVisibility(View.VISIBLE);
    final long[] previousBytes={android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())};
    final long[] previousAt={android.os.SystemClock.elapsedRealtime()};
    networkSpeedUpdater=new Runnable(){public void run(){
      if(networkSpeedOverlay==null||!getSharedPreferences("mytv",0).getBoolean("show_network_speed",false))return;
      long now=android.os.SystemClock.elapsedRealtime();
      long bytes=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
      if(bytes<0||previousBytes[0]<0){networkSpeedOverlay.setText("网速暂不可用");}
      else{long elapsed=Math.max(1,now-previousAt[0]);long delta=Math.max(0,bytes-previousBytes[0]);double rate=delta*1000.0/elapsed;
        String label=rate>=1024*1024?String.format(Locale.ROOT,"接收 %.2f MB/s",rate/1024/1024):String.format(Locale.ROOT,"接收 %.0f KB/s",rate/1024);
        networkSpeedOverlay.setText(label);
      }
      previousBytes[0]=bytes;previousAt[0]=now;uiHandler.postDelayed(this,1000L);
    }};
    uiHandler.postDelayed(networkSpeedUpdater,1000L);
  }
  private void bufferingSwitchSettingsDialog(){
    int current=getSharedPreferences("mytv",0).getInt("buffer_switch_seconds",12);
    String[] labels={"3 秒后切换（灵敏）","5 秒后切换","8 秒后切换","12 秒后切换（推荐）","15 秒后切换","关闭自动卡顿切换"};
    int[] values={3,5,8,12,15,0};
    int checked=current==3?0:current==5?1:current==8?2:current==15?4:current==0?5:3;
    new AlertDialog.Builder(this).setTitle("卡顿自动切换备用源")
      .setSingleChoiceItems(labels,checked,(dialog,which)->{
        getSharedPreferences("mytv",0).edit().putInt("buffer_switch_seconds",values[which]).apply();
        cancelBufferingFallback();
        if(player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING)scheduleBufferingFallback();
        dialog.dismiss();
        toast(values[which]==0?"已关闭卡顿自动切换":"播放中连续缓冲 "+values[which]+" 秒后尝试下一个源；首次打开会多等待一会儿");
      }).setNegativeButton("关闭",null).show();
  }

  // ---------------- Local parental controls ----------------
  private android.content.SharedPreferences childPrefs(){return getSharedPreferences("mytv",0);}
  private String hashChildPin(String pin){
    android.content.SharedPreferences p=childPrefs();
    String salt=p.getString("child_pin_salt","");
    if(salt.isEmpty()){
      byte[] bytes=new byte[16];new java.security.SecureRandom().nextBytes(bytes);
      StringBuilder out=new StringBuilder(32);for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&0xff));
      salt=out.toString();p.edit().putString("child_pin_salt",salt).apply();
    }
    return ChildLockPolicy.hashPin(pin,salt);
  }
  private String childTodayKey(){
    Calendar cal=Calendar.getInstance();
    return cal.get(Calendar.YEAR)+"-"+cal.get(Calendar.DAY_OF_YEAR);
  }
  private boolean childQuietTimeNow(){
    android.content.SharedPreferences p=childPrefs();
    if(!p.getBoolean("child_block_enabled",false))return false;
    Calendar now=Calendar.getInstance();
    return ChildLockPolicy.isBlocked(now,p.getInt("child_block_start",22*60),
        p.getInt("child_block_end",3*60),p.getInt("child_block_days",0x7f));
  }
  private long childTodayUsage(){
    android.content.SharedPreferences p=childPrefs();
    String day=childTodayKey();
    // Reading the limit must not reset the stored date. updateChildUsage() owns
    // rollover accounting so it can split a foreground interval at midnight.
    return ChildLockPolicy.usageForDate(
        p.getString("child_usage_day",""),day,p.getLong("child_usage_ms",0L));
  }
  private void updateChildUsage(){
    long now=android.os.SystemClock.elapsedRealtime();
    long wall=System.currentTimeMillis();
    if(childLockLastElapsed<=0L){childLockLastElapsed=now;return;}
    long delta=Math.max(0L,Math.min(43200000L,now-childLockLastElapsed));
    childLockLastElapsed=now;
    if(delta==0L)return;
    android.content.SharedPreferences p=childPrefs();
    String day=childTodayKey();
    String saved=p.getString("child_usage_day","");
    long used;
    if(saved.equals(day)){
      used=Math.max(0L,p.getLong("child_usage_ms",0L))+delta;
    }else{
      Calendar midnight=Calendar.getInstance();
      midnight.set(Calendar.HOUR_OF_DAY,0);midnight.set(Calendar.MINUTE,0);
      midnight.set(Calendar.SECOND,0);midnight.set(Calendar.MILLISECOND,0);
      long sinceMidnight=Math.max(0L,wall-midnight.getTimeInMillis());
      // If the foreground session crossed midnight, attribute only the post-midnight
      // portion to the new day; the previous day's saved total remains separate.
      used=Math.min(delta,sinceMidnight);
    }
    p.edit().putString("child_usage_day",day).putLong("child_usage_ms",used).apply();
  }
  private boolean isChildLockCurrentlyActive(){
    android.content.SharedPreferences p=childPrefs();
    if(p.getString("child_pin_hash","").isEmpty())return false;
    if(p.getLong("child_unlock_until",0L)>System.currentTimeMillis())return false;
    if(childQuietTimeNow())return true;
    if(!p.getBoolean("child_daily_limit_enabled",false))return false;
    int limit=p.getInt("child_daily_limit_min",0);
    return childTodayUsage()>=limit*60000L;
  }
  private String childLockReason(){
    android.content.SharedPreferences p=childPrefs();
    if(childQuietTimeNow())return "当前处于家长设置的禁止使用时段";
    int limit=p.getInt("child_daily_limit_min",0);
    if(limit==0)return "家长已将每日观看时长设为 0，今天禁止观看";
    return "今天的观看时长已达到 "+formatChildDuration(limit);
  }
  private void refreshChildLockState(){
    if(isChildLockCurrentlyActive()){enforceChildLock();return;}
    if(childLockDialog!=null&&childLockDialog.isShowing()){
      childLockDialog.dismiss();childLockDialog=null;
      toast("限制时段已结束，可以继续使用");
      if(!resumeLiveAfterVod&&selected>=0&&selected<channels.size()){resumePlaybackOnStart=false;startCurrentSource();}
    }
  }
  private boolean isRemotePlaybackLockActive(){
    return remoteLockOverlayShowing
        ||(remoteParentLockManager!=null&&remoteParentLockManager.isLocked());
  }
  private boolean isAnyPlaybackLockActive(){
    return isRemotePlaybackLockActive()||isChildLockCurrentlyActive();
  }
  /** Revoke all pending playback before closing dialogs, whose dismissal can otherwise resume live. */
  private void stopAllPlayersForLock(){
    cancelSingleChannelRefresh();
    ++liveTuneRequestSerial;
    ++playbackGeneration;
    currentMediaId="";
    recentDepartedLiveSource=null;
    lastOutputMediaId="";
    ++vodSessionGeneration;
    resumeLiveAfterVod=false;
    resumePlaybackOnStart=false;
    startupAutoResumePending=false;
    startupResumeIndex=-1;
    startupResumeChannel=null;
    if(startupAutoResumeTask!=null){uiHandler.removeCallbacks(startupAutoResumeTask);startupAutoResumeTask=null;}
    revokePendingLiveTune();
    libVlcReleaseCallbacks.clear();
    revokePendingVodStart();
    clearPendingRemoteZap();
    cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();
    cancelLibVlcStartTimeout();cancelLibVlcWatchdog();cancelLibVlcBufferingTimeout();
    if(vodPlaybackWatchdog!=null){uiHandler.removeCallbacks(vodPlaybackWatchdog);vodPlaybackWatchdog=null;}
    cancelActiveVodCatalogue();
    if(player!=null)releaseLivePlayerSafely();
    if(libVlcEngine!=null)releaseLibVlcEngine();
    if(vodPlayerDialog!=null&&vodPlayerDialog.isShowing())vodPlayerDialog.dismiss();
    releaseVodFallback();
    if(vodPlayer!=null)releaseVodPlayerSafely(null);
    if(vodCatalogueDialog!=null&&vodCatalogueDialog.isShowing())vodCatalogueDialog.dismiss();
    if(fullScreenPlayback)exitFullScreenPlayback();
    setSourceUpdatePlaybackActive(libVlcReleaseInProgress);
  }
  private void enforceChildLock(){
    if(!isChildLockCurrentlyActive()||isFinishing()||isDestroyed())return;
    stopAllPlayersForLock();
    if(childLockDialog!=null&&childLockDialog.isShowing())return;
    EditText pin=new EditText(this);
    pin.setSingleLine(true);
    pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    pin.setHint("输入家长密码");
    pin.setPadding(dp(14),dp(10),dp(14),dp(10));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("儿童锁已启用")
        .setMessage(childLockReason()+"。需要家长密码才能临时解锁。")
        .setView(pin).setCancelable(false)
        .setPositiveButton("家长解锁 15 分钟",null).create();
    childLockDialog=dialog;
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
      String saved=childPrefs().getString("child_pin_hash","");
      if(saved.equals(hashChildPin(pin.getText().toString().trim()))){
        childPrefs().edit().putLong("child_unlock_until",System.currentTimeMillis()+15L*60L*1000L).apply();
        childLockDialog=null;dialog.dismiss();toast("已临时解锁 15 分钟");
        if(selected>=0&&selected<channels.size())startCurrentSource();
      }else{pin.setText("");pin.setError("密码不正确");}
    }));
    dialog.show();
    dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }
  private void childLockSettingsDialog(){
    String hash=childPrefs().getString("child_pin_hash","");
    if(hash.isEmpty()){setChildParentPin();return;}
    requireChildParentPin(this::showChildLockOptions);
  }
  private void setChildParentPin(){
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(20),dp(8),dp(20),dp(4));
    EditText first=new EditText(this);first.setSingleLine(true);first.setHint("设置 4–8 位数字密码");first.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    EditText second=new EditText(this);second.setSingleLine(true);second.setHint("再次输入密码");second.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    box.addView(first);box.addView(second);
    new AlertDialog.Builder(this).setTitle("设置家长密码").setMessage("首次使用儿童锁，请先设置家长密码。")
        .setView(box).setPositiveButton("保存", (d,w)->{
          String a=first.getText().toString().trim(),b=second.getText().toString().trim();
          if(!a.matches("[0-9]{4,8}")||!a.equals(b)){toast("密码需为相同的 4–8 位数字");return;}
          childPrefs().edit().putString("child_pin_hash",hashChildPin(a)).putLong("child_unlock_until",0L).apply();
          toast("家长密码已设置");showChildLockOptions();
        }).setNegativeButton("取消",null).show();
  }
  private void requireChildParentPin(Runnable accepted){
    EditText pin=new EditText(this);pin.setSingleLine(true);pin.setHint("输入家长密码");
    pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    new AlertDialog.Builder(this).setTitle("家长验证").setView(pin)
        .setPositiveButton("验证",(d,w)->{
          if(childPrefs().getString("child_pin_hash","").equals(hashChildPin(pin.getText().toString().trim())))accepted.run();
          else toast("家长密码不正确");
        }).setNegativeButton("取消",null).show();
  }
  private void showChildLockOptions(){
    android.content.SharedPreferences p=childPrefs();
    int start=p.getInt("child_block_start",22*60),end=p.getInt("child_block_end",3*60);
    int limit=p.getInt("child_daily_limit_min",0);
    String[] items={
      "禁止时段："+(p.getBoolean("child_block_enabled",false)?"已启用":"未启用"),
      "设置开始时间："+String.format(Locale.ROOT,"%02d:%02d",start/60,start%60),
      "设置结束时间："+String.format(Locale.ROOT,"%02d:%02d",end/60,end%60)+"（跨午夜自动算到次日）",
      "设置每周重复日",
      "每日观看限额："+(!p.getBoolean("child_daily_limit_enabled",false)?"不限制":formatChildDuration(limit)) ,
      "修改家长密码",
      "查看今日观看时长"
    };
    new AlertDialog.Builder(this).setTitle("儿童锁与时段管理").setItems(items,(d,which)->{
      if(which==0){
        boolean enabled=!p.getBoolean("child_block_enabled",false);
        p.edit().putBoolean("child_block_enabled",enabled).apply();
        toast(enabled?"禁止时段已启用":"禁止时段已停用");
        showChildLockOptions();
      }else if(which==1||which==2){
        int value=which==1?start:end;
        new TimePickerDialog(this,(view,hour,minute)->{
          p.edit().putInt(which==1?"child_block_start":"child_block_end",hour*60+minute).apply();
          showChildLockOptions();
        },value/60,value%60,true).show();
      }else if(which==3){
        String[] days={"星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
        int mask=p.getInt("child_block_days",0x7f);
        boolean[] checked=new boolean[7];
        for(int i=0;i<7;i++)checked[i]=(mask&(1<<i))!=0;
        new AlertDialog.Builder(this).setTitle("选择重复日期").setMultiChoiceItems(days,checked,(dd,index,isChecked)->checked[index]=isChecked)
            .setPositiveButton("保存",(dd,w)->{
              int result=0;for(int i=0;i<7;i++)if(checked[i])result|=1<<i;
              p.edit().putInt("child_block_days",result).apply();showChildLockOptions();
            }).setNegativeButton("取消",null).show();
      }else if(which==4){
        editChildDailyLimit();
      }else if(which==5){
        changeChildParentPin();
      }else{
        long used=childTodayUsage()/60000L;
        int max=p.getInt("child_daily_limit_min",0);
        toast("今日已使用约 "+formatChildDuration((int)used)+(p.getBoolean("child_daily_limit_enabled",false)?" / 限额 "+formatChildDuration(max):" / 不限制"));
      }
    }).setNegativeButton("关闭",null).show();
  }
  private String formatChildDuration(int minutes){
    if(minutes<=0)return "0 分钟";
    int hours=minutes/60,mins=minutes%60;
    if(hours==0)return mins+" 分钟";
    if(mins==0)return hours+" 小时";
    return hours+" 小时 "+mins+" 分钟";
  }
  private void editChildDailyLimit(){
    android.content.SharedPreferences p=childPrefs();
    boolean unlimited=!p.getBoolean("child_daily_limit_enabled",false);
    int current=p.getInt("child_daily_limit_min",120);
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(20),dp(8),dp(20),dp(4));
    CheckBox noLimit=new CheckBox(this);noLimit.setText("不限制每日使用时长");noLimit.setChecked(unlimited);
    LinearLayout fields=new LinearLayout(this);fields.setOrientation(0);
    EditText hours=new EditText(this);hours.setSingleLine(true);hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);hours.setHint("小时");hours.setText(unlimited?"2":String.valueOf(current/60));hours.setEms(4);
    EditText minutes=new EditText(this);minutes.setSingleLine(true);minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);minutes.setHint("分钟");minutes.setText(unlimited?"0":String.valueOf(current%60));minutes.setEms(4);
    TextView hourLabel=new TextView(this);hourLabel.setText(" 小时  ");
    TextView minuteLabel=new TextView(this);minuteLabel.setText(" 分钟");
    fields.addView(hours);fields.addView(hourLabel);fields.addView(minutes);fields.addView(minuteLabel);
    TextView hint=new TextView(this);hint.setText("可自定义 0 分钟至 24 小时。设为 0 分钟表示当天禁止观看。");
    hint.setPadding(0,dp(8),0,0);
    box.addView(noLimit);box.addView(fields);box.addView(hint);
    Runnable updateFields=()->{boolean enabled=!noLimit.isChecked();hours.setEnabled(enabled);minutes.setEnabled(enabled);};
    noLimit.setOnCheckedChangeListener((button,checked)->updateFields.run());updateFields.run();
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("自定义每日使用时长").setView(box)
        .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
      if(noLimit.isChecked()){
        p.edit().putBoolean("child_daily_limit_enabled",false).apply();
      }else{
        int h,m;
        try{h=Integer.parseInt(hours.getText().toString().trim());m=Integer.parseInt(minutes.getText().toString().trim());}
        catch(Exception e){toast("请输入小时和分钟");return;}
        if(h<0||h>24||m<0||m>59||(h==24&&m!=0)){toast("范围为 0–24 小时，分钟为 0–59；24 小时不能再加分钟");return;}
        p.edit().putInt("child_daily_limit_min",h*60+m).putBoolean("child_daily_limit_enabled",true).apply();
      }
      dialog.dismiss();showChildLockOptions();refreshChildLockState();
    }));
    dialog.show();
  }
  private void changeChildParentPin(){
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(20),dp(8),dp(20),dp(4));
    EditText first=new EditText(this);first.setSingleLine(true);first.setHint("新密码（4–8 位数字）");first.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    EditText second=new EditText(this);second.setSingleLine(true);second.setHint("再次输入新密码");second.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    box.addView(first);box.addView(second);
    new AlertDialog.Builder(this).setTitle("修改家长密码").setView(box).setPositiveButton("保存",(d,w)->{
      String a=first.getText().toString().trim(),b=second.getText().toString().trim();
      if(!a.matches("[0-9]{4,8}")||!a.equals(b)){toast("密码需为相同的 4–8 位数字");return;}
      childPrefs().edit().putString("child_pin_hash",hashChildPin(a)).apply();toast("家长密码已修改");
    }).setNegativeButton("取消",null).show();
  }

  private void configureRemoteParentLock(){
    LinearLayout fields=new LinearLayout(this);fields.setOrientation(1);fields.setPadding(dp(20),dp(8),dp(20),dp(4));
    EditText endpoint=new EditText(this);
    endpoint.setSingleLine(true);
    endpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setHint("HTTPS 接口地址");
    endpoint.setText(getSharedPreferences("mytv_remote_parent_lock",0).getString("endpoint",""));
    EditText token=new EditText(this);
    token.setSingleLine(true);
    token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setHint("可选：设备 API 令牌（加密保存）");
    if(remoteParentLockManager!=null)token.setText(remoteParentLockManager.getApiToken());
    fields.addView(endpoint);fields.addView(token);
    new AlertDialog.Builder(this).setTitle("云端家长锁接口")
      .setMessage("填写 HTTPS 配置接口地址和可选设备令牌。服务端需支持 GET 配置，建议同时支持 POST 离线时长上报。")
      .setView(fields).setPositiveButton("保存",(d,w)->{
        String url=endpoint.getText().toString().trim();
        if(!url.isEmpty()&&!url.toLowerCase(Locale.ROOT).startsWith("https://")){
          toast("为保护管理员密码，只允许 HTTPS 接口");return;
        }
        getSharedPreferences("mytv_remote_parent_lock",0).edit()
          .putString("endpoint",url).putBoolean("remote_enabled",!url.isEmpty()).apply();
        if(remoteParentLockManager!=null){
          remoteParentLockManager.setApiToken(token.getText().toString());
          remoteParentLockManager.requestPoll();
        }
        toast(url.isEmpty()?"已关闭云端家长锁":"云端家长锁接口已保存");
      }).setNegativeButton("取消",null).show();
  }

  private void enforceRemoteParentLock(String reason){
    if(isFinishing()||isDestroyed())return;
    remoteLockOverlayShowing=true;
    // If the device administrator has pre-authorized this package for kiosk mode,
    // enter Lock Task while locked. Ordinary installations simply skip this.
    if(!remoteLockTaskActive&&android.os.Build.VERSION.SDK_INT>=21){
      try{
        android.app.admin.DevicePolicyManager dpm=(android.app.admin.DevicePolicyManager)getSystemService(Context.DEVICE_POLICY_SERVICE);
        if(dpm!=null&&dpm.isLockTaskPermitted(getPackageName())){startLockTask();remoteLockTaskActive=true;}
      }catch(Exception e){android.util.Log.w("MyTVRemoteLock","Lock Task unavailable");}
    }
    stopAllPlayersForLock();
    if(remoteParentLockDialog!=null&&remoteParentLockDialog.isShowing())return;
    EditText pin=new EditText(this);
    pin.setSingleLine(true);
    pin.setGravity(Gravity.CENTER);
    pin.setTextColor(Color.WHITE);
    pin.setHintTextColor(Color.LTGRAY);
    pin.setHint("输入管理员数字密码");
    pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    pin.setTextSize(22);
    pin.setPadding(dp(24),dp(18),dp(24),dp(18));
    pin.setSelectAllOnFocus(false);
    AlertDialog dialog=new AlertDialog.Builder(this).setView(pin).setCancelable(false).create();
    remoteParentLockDialog=dialog;
    dialog.setCanceledOnTouchOutside(false);
    dialog.setOnShowListener(v->{
      android.view.Window win=dialog.getWindow();
      if(win!=null){
        win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        win.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.MATCH_PARENT);
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN|android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        android.view.WindowManager.LayoutParams lp=win.getAttributes();lp.dimAmount=1f;win.setAttributes(lp);
      }
      pin.requestFocus();
      pin.setOnEditorActionListener((view,action,event)->{
        submitRemoteParentPin(pin,dialog);return true;
      });
      pin.setOnKeyListener((view,key,event)->{
        if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER)){
          submitRemoteParentPin(pin,dialog);return true;
        }
        return false;
      });
    });
    dialog.show();
    android.view.Window win=dialog.getWindow();
    if(win!=null)win.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.MATCH_PARENT);
    android.util.Log.w("MyTVRemoteLock","show full-screen lock: "+safeDiagnosticDetail(reason));
  }

  private void submitRemoteParentPin(EditText pin, AlertDialog dialog){
    String entered=pin.getText().toString().trim();
    if(remoteParentLockManager!=null&&remoteParentLockManager.verifyAdminPassword(entered)){
      remoteParentLockManager.grantAdminUnlock(15L*60L*1000L);
      remoteLockOverlayShowing=false;remoteParentLockDialog=null;dialog.dismiss();
      toast("管理员验证成功，临时解锁 15 分钟");
      if(selected>=0&&selected<channels.size()&&!isChildLockCurrentlyActive())startCurrentSource();
    }else{
      pin.setText("");pin.setError("密码不正确");
    }
  }

  private void dismissRemoteParentLock(){
    boolean wasShowing=remoteLockOverlayShowing;
    if(remoteLockTaskActive){try{stopLockTask();}catch(Exception ignored){}remoteLockTaskActive=false;}
    remoteLockOverlayShowing=false;
    if(remoteParentLockDialog!=null){
      AlertDialog old=remoteParentLockDialog;remoteParentLockDialog=null;
      if(old.isShowing())old.dismiss();
    }
    if(wasShowing&&selected>=0&&selected<channels.size()&&!isChildLockCurrentlyActive()){
      resumePlaybackOnStart=false;startCurrentSource();
    }
  }

  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  protected void onStart(){
    super.onStart();
    activityStarted=true;
    startMainThreadAnrSampler();
    startLiveUiStallProbe();
    setSourceUpdateActivityActive(true);
    if(currentVodPanel!=null)setSourceUpdatePlaybackActive(true);
    if(startupDataReady){
      reloadChannelsAfterBackgroundUpdateIfNeeded();
    }
    remoteLockUiActive=true;
    if(remoteParentLockManager!=null){remoteParentLockManager.start();if(remoteParentLockManager.isLocked())enforceRemoteParentLock(remoteParentLockManager.getLockReason());}
    childLockLastElapsed=android.os.SystemClock.elapsedRealtime();
    if(childLockTicker!=null)uiHandler.removeCallbacks(childLockTicker);
    childLockTicker=()->{if(isFinishing())return;updateChildUsage();refreshChildLockState();uiHandler.postDelayed(childLockTicker,30000L);};
    uiHandler.postDelayed(childLockTicker,30000L);
    refreshChildLockState();
    replayPendingStartupCategory();
    updateNetworkSpeedMonitor();
    if(resumePlaybackOnStart&&currentVodPanel==null&&!resumeLiveAfterVod
        &&selected>=0&&selected<channels.size()&&!isChildLockCurrentlyActive()){
      resumePlaybackOnStart=false;
      startupAutoResumePending=false;
      startCurrentSource();
    }
    scheduleStartupAutoResume();
    startInitialDiscoveryProgressTicker();
  }
  private void scheduleStartupAutoResume(){
    if(!startupAutoResumePending||!activityStarted||isFinishing())return;
    if(startupAutoResumeTask!=null)uiHandler.removeCallbacks(startupAutoResumeTask);
    startupAutoResumeTask=()->{
      startupAutoResumeTask=null;
      if(!startupAutoResumePending||!activityStarted||isFinishing()||isDestroyed()||isAnyPlaybackLockActive())return;
      startupAutoResumePending=false;
      int index=startupResumeIndex;
      Channel expected=startupResumeChannel;
      startupResumeChannel=null;startupResumeIndex=-1;
      Channel current=index>=0&&index<channels.size()?channels.get(index):null;
      if(!RemoteChannelOrderPolicy.canResume(selected>=0,currentVodPanel!=null,expected,current))return;
      try{play(index);}
      catch(Throwable resumeFailure){
        android.util.Log.e("MyTVStartup","last-channel auto resume failed",resumeFailure);
        saveLiveDiagnostic("STARTUP_AUTO_RESUME_ERROR",resumeFailure.getClass().getSimpleName()+": "+resumeFailure.getMessage());
        if(info!=null)info.setText("自动恢复上次频道失败，主界面仍可使用，请手动选择频道。");
      }
    };
    uiHandler.postDelayed(startupAutoResumeTask,350L);
  }

  private void setSourceUpdateActivityActive(boolean active){
    SourceUpdateActivityPolicy.setActivityActive(active);
  }

  // A Worker may safely refresh the persisted catalogue only while this Activity is
  // stopped. On returning, reload its newer cache before any later persist() can
  // serialize an old in-memory list over the updated backup sources.
  private volatile long backgroundCatalogueReloadGeneration=0L;
  // Main-looper flag only. Room retains the new durable snapshot if viewing
  // prevents replacing the current Activity's positional catalogue.
  private boolean catalogueReloadDeferredForPlayback;
  private boolean hasPlaybackCatalogueOwner(){
    return player!=null||libVlcEngine!=null||vodPlayer!=null||vodFallbackBackend!=null
        ||currentVodPanel!=null||libVlcReleaseInProgress
        ||PROCESS_VLC_TEARDOWNS.get()>0||SourceUpdateActivityPolicy.isPlaybackActive();
  }
  private boolean mayAppendInitialLivePreview(){
    return roomFullRebuildCompletedAt<=0L&&catalogueSnapshotTrusted&&activityStarted
        &&currentVodPanel==null&&vodPlayer==null&&vodFallbackBackend==null
        &&!libVlcReleaseInProgress&&!decoderReleaseUnconfirmed&&PROCESS_VLC_TEARDOWNS.get()==0
        &&(player!=null||libVlcEngine!=null);
  }
  private void reloadDeferredCatalogueIfIdle(){
    if(catalogueReloadDeferredForPlayback&&activityStarted&&!isFinishing()&&!isDestroyed()
        &&!hasPlaybackCatalogueOwner())reloadChannelsAfterBackgroundUpdateIfNeeded();
  }
  // Incremented for every persistence request; a reload may publish only if no
  // catalogue edit/save was requested while its background parse was running.
  private volatile long catalogueMutationGeneration=0L;
  private volatile long catalogueMembershipGeneration=0L;
  private org.json.JSONObject readIndexedPlaybackScores(List<Channel> entries,long version,
      java.util.function.BooleanSupplier cancelled)throws org.json.JSONException{
    if(!com.wodedianshi.tv.db.ManualProbeQualityStore.hasPublished(getApplicationContext()))return null;
    LinkedHashSet<String> urls=new LinkedHashSet<>();
    for(Channel channel:entries){
      SourceProbeTimePolicy.checkPublicationPaused(cancelled);
      urls.add(channel.url);urls.addAll(channel.backups);
    }
    long started=android.os.SystemClock.elapsedRealtime();
    org.json.JSONObject scores=com.wodedianshi.tv.db.ManualProbeQualityStore.readPlaybackScores(
        getApplicationContext(),urls,version,cancelled);
    android.util.Log.i("MyTVStartup","indexed_quality_read_ms="+(android.os.SystemClock.elapsedRealtime()-started)
        +" sources="+urls.size()+" scores="+(scores==null?0:scores.length()));
    return scores;
  }
  private String readQualityOnlyRevision(){
    return com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),
        SourceUpdateSchedulePolicy.QUALITY_ONLY_REVISION_KEY,"");
  }

  // Do not spend a full Room catalogue normalization/deduplication on the UI thread.
  private static final class PreparedRoomCatalogue {
    final ArrayList<Channel> entries=new ArrayList<>();
    final HashMap<String,Channel> byUrl=new HashMap<>();
    final LinkedHashMap<String,Channel> byIdentity=new LinkedHashMap<>();
    final HashMap<String,Integer> positionByIdentity=new HashMap<>();
    final HashMap<String,Integer> positionByNameGroup=new HashMap<>();
    final HashSet<String> favorites=new HashSet<>();
    SnapshotCategoryIndex.LocalMembership localMembership;
  }
  private String roomChannelNameGroupKey(String name,String group){
    return (name==null?"":name)+"\u001f"+(group==null?"":group);
  }
  private PreparedRoomCatalogue prepareRoomCatalogue(ArrayList<Channel> restored,
      Set<String> savedFavorites,java.util.function.BooleanSupplier cancelled){
    PreparedRoomCatalogue prepared=new PreparedRoomCatalogue();
    if(savedFavorites!=null)prepared.favorites.addAll(savedFavorites);
    HashMap<String,HashSet<String>> sourcesByIdentity=new HashMap<>();
    for(Channel channel:restored){
      SourceProbeTimePolicy.checkPublicationPaused(cancelled);
      if(channel==null)continue;
      normalizeBroadcasterLabel(channel);
      if(channel.backups==null)channel.backups=new ArrayList<>();
      if(channel.backups.isEmpty()&&channel.url!=null&&!channel.url.isEmpty())
        channel.backups.add(channel.url);
      String identityKey=identity(channel);
      Channel keep=prepared.byIdentity.get(identityKey);
      if(keep==null){
        prepared.byIdentity.put(identityKey,channel);
        prepared.positionByIdentity.put(identityKey,prepared.entries.size());
        prepared.entries.add(channel);
      }else{
        HashSet<String> seen=sourcesByIdentity.get(identityKey);
        if(seen==null){
          seen=new HashSet<>(Math.max(4,keep.backups.size()*2));
          for(String url:keep.backups)if(url!=null&&!url.isEmpty())seen.add(url);
          sourcesByIdentity.put(identityKey,seen);
        }
        for(String url:channel.backups){
          if(url!=null&&!url.isEmpty()&&seen.add(url))keep.backups.add(url);
        }
        keep.mergeBackupOwnership(channel);
        if(prepared.favorites.contains(channel.key()))prepared.favorites.add(keep.key());
      }
    }
    for(int i=0;i<prepared.entries.size();i++){
      SourceProbeTimePolicy.checkPublicationPaused(cancelled);
      Channel channel=prepared.entries.get(i);
      for(String url:channel.backups)prepared.byUrl.put(url,channel);
      String nameGroup=roomChannelNameGroupKey(channel.name,channel.group);
      if(!prepared.positionByNameGroup.containsKey(nameGroup))
        prepared.positionByNameGroup.put(nameGroup,i);
      else prepared.positionByNameGroup.put(nameGroup,-1); // ambiguous label, never guess
    }
    long localIndexStarted=android.os.SystemClock.elapsedRealtime();
    try{
      prepared.localMembership=buildLocalMembership(prepared.entries,cancelled,true);
      android.util.Log.i("MyTVDirectory","replacement_local_index_ms="+
          (android.os.SystemClock.elapsedRealtime()-localIndexStarted)+" rows="+prepared.entries.size());
    }catch(SourceProbeTimePolicy.PublicationPausedException superseded){throw superseded;}
    catch(RuntimeException deferred){
      android.util.Log.w("MyTVDirectory","replacement_local_index_deferred",deferred);
    }
    return prepared;
  }

  private void reloadChannelsAfterBackgroundUpdateIfNeeded(){
    reloadChannelsAfterBackgroundUpdateIfNeeded(1);
  }
  // A concurrent favorite/source selection invalidates the prepared Room
  // catalogue before UI publication. Retry once only AFTER the narrow writer
  // queue drains, so a stale favorites/source snapshot cannot reappear.
  private void scheduleRoomReloadAfterWriteConflict(long invalidatedGeneration,
      int retriesRemaining){
    try{
      cataloguePersistWorker.execute(()->uiHandler.post(()->{
        if(activityStarted&&!isFinishing()&&!isDestroyed()
            &&invalidatedGeneration==backgroundCatalogueReloadGeneration)
          reloadChannelsAfterBackgroundUpdateIfNeeded(retriesRemaining);
      }));
    }catch(RuntimeException rejected){
      android.util.Log.w("MyTVStartup","Room reload retry queue rejected",rejected);
      saveLiveDiagnostic("BACKGROUND_CATALOGUE_RETRY_REJECTED",
          rejected.getClass().getSimpleName());
    }
  }
  // If the page-by-page Room read itself raced an independent transaction,
  // nothing may be published. Request at most one fresh read after the user's
  // narrow write queue has drained, without blocking the UI or playback.
  private void requestRoomReloadAfterReadConflict(long generation,int retriesRemaining){
    uiHandler.post(()->{
      boolean active=activityStarted&&!isFinishing()&&!isDestroyed();
      boolean latest=generation==backgroundCatalogueReloadGeneration;
      if(RoomCatalogueReloadPolicy.shouldRetryAfterReadConflict(
          active,latest,retriesRemaining)){
        android.util.Log.i("MyTVStartup","room_paged_read_conflict_retry_once");
        scheduleRoomReloadAfterWriteConflict(generation,retriesRemaining-1);
      }
    });
  }
  private void reloadChannelsAfterBackgroundUpdateIfNeeded(int retriesRemaining){
    if(!startupDataReady||isFinishing()||isDestroyed())return;
    final int requestedPreviewLevel=displayedInitialPreviewLevel,requestedPreviewMilestone=displayedInitialPreviewMilestone,
        requestedPreviewCoreMask=displayedInitialPreviewCoreMask;
    final boolean livePreviewRead=hasPlaybackCatalogueOwner()&&mayAppendInitialLivePreview();
    if(hasPlaybackCatalogueOwner()&&!livePreviewRead){
      catalogueReloadDeferredForPlayback=true;
      return;
    }
    catalogueReloadDeferredForPlayback=livePreviewRead;
    final boolean recoveryRead=!catalogueSnapshotTrusted;
    if(!RoomCatalogueReloadPolicy.canReadForRecovery(catalogueSnapshotTrusted,channels.size(),
        player!=null||libVlcEngine!=null||currentVodPanel!=null)){
      android.util.Log.w("MyTVStartup","Room reload skipped: startup catalogue restore was incomplete");
      saveLiveDiagnostic("BACKGROUND_CATALOGUE_PUBLISH_BLOCKED",
          "startup restore incomplete; existing in-memory catalogue retained");
      return;
    }
    final long generation=++backgroundCatalogueReloadGeneration;
    final long expectedMutationGeneration=catalogueMutationGeneration;
    final java.util.function.BooleanSupplier cancelled=()->
        generation!=backgroundCatalogueReloadGeneration||isFinishing()||isDestroyed();
    try{
      worker.execute(()->{
        try{
          SourceProbeTimePolicy.checkPublicationPaused(cancelled);
          if(generation!=backgroundCatalogueReloadGeneration)return;
          // The already trusted directory is keyed by the committed Room checkpoint.
          // Do not page, normalize and republish an identical snapshot on every onStart.
          com.wodedianshi.tv.db.CatalogueMigrationEntity checkpoint=
              com.wodedianshi.tv.db.RoomDatabaseProvider.get(getApplicationContext())
                  .channelDao().getMigration(com.wodedianshi.tv.db.MasterCatalogueRepository.MIGRATION_KEY);
          if(livePreviewRead&&(checkpoint==null
              ||checkpoint.channelsCount>FullDiscoveryProgressPolicy.MAX_VIEWING_PREVIEW_ROWS))return;
          if(livePreviewRead&&checkpoint.completedAt==appendedInitialPreviewVersion){
            uiHandler.post(()->{if(!cancelled.getAsBoolean())if(requestedPreviewLevel==displayedInitialPreviewLevel&&requestedPreviewMilestone==displayedInitialPreviewMilestone&&requestedPreviewCoreMask==displayedInitialPreviewCoreMask)initialPreviewReloadPending=false;});
            return;
          }
          if(!recoveryRead&&RoomCatalogueReloadPolicy.isUnchanged(loadedRoomCatalogueVersion,
              checkpoint==null?null:Long.valueOf(checkpoint.completedAt))){
            final String qualityRevision=readQualityOnlyRevision();
            if(SourceUpdateSchedulePolicy.needsIndexedQualityReload(indexedPlaybackScoreVersion,
                loadedRoomCatalogueVersion,indexedPlaybackQualityRevision,qualityRevision)&&
                com.wodedianshi.tv.db.ManualProbeQualityStore.hasPublished(getApplicationContext())){
              final long scoreVersion=loadedRoomCatalogueVersion;
              ArrayList<Channel> retained;
              synchronized(channels){retained=new ArrayList<>(channels);}
              org.json.JSONObject indexed=readIndexedPlaybackScores(retained,scoreVersion,cancelled);
              if(indexed!=null&&qualityRevision.equals(readQualityOnlyRevision()))uiHandler.post(()->{
                if(!cancelled.getAsBoolean()&&activityStarted
                    &&expectedMutationGeneration==catalogueMutationGeneration
                    &&scoreVersion==loadedRoomCatalogueVersion){
                  cachedProbeScoreJson=indexed;indexedPlaybackScoreVersion=scoreVersion;
                  indexedPlaybackQualityRevision=qualityRevision;
                }
              });
            }
            uiHandler.post(()->{if(!cancelled.getAsBoolean())if(requestedPreviewLevel==displayedInitialPreviewLevel&&requestedPreviewMilestone==displayedInitialPreviewMilestone&&requestedPreviewCoreMask==displayedInitialPreviewCoreMask)initialPreviewReloadPending=false;});
            android.util.Log.d("MyTVStartup","room_reload_unchanged_checkpoint_skip");
            return;
          }
          ArrayList<Channel> refreshed=new ArrayList<>();
          com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot roomSnapshot=
              com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext(),page->{
                for(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row:page){
                  refreshed.add(new Channel(row));
                }
              },cancelled);
          if(roomSnapshot==null){
            // A concurrent Room commit can transiently make separate
            // readiness-count queries disagree. Do not show partial data.
            android.util.Log.d("MyTVStartup",
                "Room reload snapshot not ready; retain visible channels");
            requestRoomReloadAfterReadConflict(generation,retriesRemaining);
            return;
          }
          if(generation!=backgroundCatalogueReloadGeneration)return;
          long preparationStarted=android.os.SystemClock.elapsedRealtime();
          final PreparedRoomCatalogue prepared=prepareRoomCatalogue(refreshed,roomSnapshot.favorites,cancelled);
          final String qualityRevision=readQualityOnlyRevision();
          org.json.JSONObject readScores=readIndexedPlaybackScores(prepared.entries,roomSnapshot.catalogueVersion,cancelled);
          final org.json.JSONObject indexedScores=qualityRevision.equals(readQualityOnlyRevision())?readScores:null;
          android.util.Log.i("MyTVStartup","room_reload_prepare_ms="
              +(android.os.SystemClock.elapsedRealtime()-preparationStarted)
              +" entries="+prepared.entries.size()+" sources="+prepared.byUrl.size());
          if(generation!=backgroundCatalogueReloadGeneration)return;
          // Room has atomically committed this snapshot. Only swap the prepared
          // array/maps in the UI callback; normalization and deduplication are done.
          uiHandler.post(()->{
            boolean activityActive=activityStarted&&!isFinishing()&&!isDestroyed();
            boolean currentRequest=generation==backgroundCatalogueReloadGeneration;
            // During initial live playback only append newly published identities.
            // Never reindex or replace an existing object or its source list.
            boolean appendOnly=activityActive&&currentRequest&&hasPlaybackCatalogueOwner()
                &&mayAppendInitialLivePreview()&&prepared.entries.size()<=FullDiscoveryProgressPolicy.MAX_VIEWING_PREVIEW_ROWS;
            if(activityActive&&currentRequest&&hasPlaybackCatalogueOwner()&&!appendOnly){
              catalogueReloadDeferredForPlayback=true;
              android.util.Log.i("MyTVStartup","room_reload_deferred_for_playback");
              return;
            }
            long currentMutation=catalogueMutationGeneration;
            if(!RoomCatalogueReloadPolicy.mayPublish(activityActive,currentRequest,
                expectedMutationGeneration,currentMutation)){
              if(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                  activityActive,currentRequest,expectedMutationGeneration,
                  currentMutation,retriesRemaining)){
                android.util.Log.i("MyTVStartup",
                    "room_reload_deferred_until_user_mutation_committed");
                scheduleRoomReloadAfterWriteConflict(generation,retriesRemaining-1);
              }
              return;
            }
            if(appendOnly){
              int added=0;
              synchronized(channels){
                synchronized(cataloguePersistLock){
                  if(catalogueMutationGeneration!=expectedMutationGeneration||cataloguePersistScheduled)return;
                  for(Map.Entry<String,Channel> indexed:prepared.byIdentity.entrySet()){
                    String key=indexed.getKey();
                    if(channelsByIdentity.containsKey(key))continue;
                    Channel entry=indexed.getValue();
                    channels.add(entry);
                    channelsByIdentity.put(key,entry);
                    for(String url:entry.backups)channelsByUrl.putIfAbsent(url,entry);
                    added++;
                  }
                  if(added>0){catalogueMutationGeneration++;catalogueMembershipGeneration++;}
                  // Do NOT advance loadedRoomCatalogueVersion: existing rows
                  // still belong to the older snapshot. A full save must fail
                  // closed rather than overwrite the newer Room catalogue.
                  appendedInitialPreviewVersion=roomSnapshot.catalogueVersion;
                }
              }
              if(requestedPreviewLevel==displayedInitialPreviewLevel&&requestedPreviewMilestone==displayedInitialPreviewMilestone&&requestedPreviewCoreMask==displayedInitialPreviewCoreMask)initialPreviewReloadPending=false;
              catalogueReloadDeferredForPlayback=true;
              if(added>0){cachedRemoteChannelOrder=null;refresh();}
              android.util.Log.i("MyTVStartup","initial_preview_live_append added="+added+" visible="+channels.size());
              return;
            }
            long publishStarted=android.os.SystemClock.elapsedRealtime();
            String selectedIdentity="";
            String selectedName="";
            String selectedGroup="";
            String selectedUrl="";
            synchronized(channels){
              if(!activityStarted||isFinishing()||isDestroyed()
                  ||generation!=backgroundCatalogueReloadGeneration)return;
              if(recoveryRead&&!RoomCatalogueReloadPolicy.canReadForRecovery(false,channels.size(),
                  player!=null||libVlcEngine!=null||currentVodPanel!=null))return;
              if(catalogueMutationGeneration!=expectedMutationGeneration){
                // Rare second race between the preceding UI gate and taking
                // the short channel lock. Do not publish the stale read.
                if(retriesRemaining>0)
                  scheduleRoomReloadAfterWriteConflict(generation,retriesRemaining-1);
                return;
              }
              if(selected>=0&&selected<channels.size()){
                Channel current=channels.get(selected);
                selectedIdentity=identity(current);
                selectedName=current.name;
                selectedGroup=current.group;
                selectedUrl=current.activeSource>=0&&current.activeSource<current.backups.size()
                    ?current.backups.get(current.activeSource):current.url;
              }
              // A new snapshot may arrive during the startup auto-resume delay.
              // Resolve the exact old channel against the prepared identity index;
              // never resume an unrelated station that inherited its old position.
              if(startupAutoResumePending){
                Channel oldAtIndex=startupResumeIndex>=0&&startupResumeIndex<channels.size()
                    ?channels.get(startupResumeIndex):null;
                int remapped=RemoteChannelOrderPolicy.remapResumeIndex(
                    selected>=0,currentVodPanel!=null,startupResumeChannel,oldAtIndex,
                    identity(startupResumeChannel),prepared.positionByIdentity,prepared.entries.size());
                startupResumeIndex=remapped;
                startupResumeChannel=remapped>=0?prepared.entries.get(remapped):null;
                startupAutoResumePending=remapped>=0;
              }
              channels.clear();
              channels.addAll(prepared.entries);
              channelsByUrl.clear();
              channelsByUrl.putAll(prepared.byUrl);
              channelsByIdentity.clear();
              channelsByIdentity.putAll(prepared.byIdentity);
              // A Room reload can replace channels between two bounded
              // snapshotChannelRows() batches. Invalidate those mixed copies
              // even if no caller explicitly requested a catalogue save.
              synchronized(cataloguePersistLock){
                catalogueMutationGeneration++;catalogueMembershipGeneration++;
                preparedLocalSnapshot=prepared.localMembership==null?null:
                    new SnapshotCategoryIndex.PreparedLocal(catalogueMembershipGeneration,prepared.localMembership);
              }
              loadedRoomCatalogueVersion=roomSnapshot.catalogueVersion;
              if(indexedScores!=null){cachedProbeScoreJson=indexedScores;indexedPlaybackScoreVersion=roomSnapshot.catalogueVersion;
                indexedPlaybackQualityRevision=qualityRevision;}
              if(!selectedIdentity.isEmpty()){
                Integer found=prepared.positionByIdentity.get(selectedIdentity);
                if(found==null)found=prepared.positionByNameGroup.get(
                    roomChannelNameGroupKey(selectedName,selectedGroup));
                selected=found==null?-1:found;
                if(selected>=0&&!selectedUrl.isEmpty()){
                  Channel candidate=channels.get(selected);
                  for(int i=0;i<candidate.backups.size();i++){
                    if(selectedUrl.equals(candidate.backups.get(i))){
                      candidate.activeSource=i;break;
                    }
                  }
                }
              }
            }
            synchronized(favorites){
              favorites.clear();
              favorites.addAll(prepared.favorites);
            }
            cachedRemoteChannelOrder=null;
            if(requestedPreviewLevel==displayedInitialPreviewLevel&&requestedPreviewMilestone==displayedInitialPreviewMilestone&&requestedPreviewCoreMask==displayedInitialPreviewCoreMask)initialPreviewReloadPending=false;appendedInitialPreviewVersion=-1L;
            catalogueReloadDeferredForPlayback=false;
            catalogueSnapshotTrusted=true;
            android.util.Log.i("MyTVStartup","room_reload_ui_publish_ms="
                +(android.os.SystemClock.elapsedRealtime()-publishStarted)
                +" channels="+prepared.entries.size()+" sources="+prepared.byUrl.size());
            refresh();
            android.util.Log.i("MyTVStartup","reloaded Room catalogue channels="
                +roomSnapshot.channelCount+" favorites="+prepared.favorites.size());
          });
        }catch(SourceProbeTimePolicy.PublicationPausedException superseded){
          // A newer request or stopped Activity already owns the next read.
          // Keep the visible complete catalogue and do not log a false DB failure.
          android.util.Log.d("MyTVStartup","room_reload_superseded");
        }catch(com.wodedianshi.tv.db.RoomCatalogueReader.StaleSnapshotException concurrentWrite){
          // SourceUpdateWorker can commit while a large directory is being
          // read, without changing the Activity's mutation generation. This
          // is an expected optimistic read conflict, not a broken catalogue.
          android.util.Log.i("MyTVStartup",
              "Room paged read superseded by concurrent catalogue write");
          requestRoomReloadAfterReadConflict(generation,retriesRemaining);
        }catch(Throwable reloadFailure){
          android.util.Log.e("MyTVStartup","Room catalogue reload failed",reloadFailure);
          saveLiveDiagnostic("BACKGROUND_CATALOGUE_RELOAD_ERROR",
              reloadFailure.getClass().getSimpleName()+": "+String.valueOf(reloadFailure.getMessage()));
        }
      });
    }catch(RuntimeException rejected){
      android.util.Log.e("MyTVStartup","Room catalogue reload was rejected",rejected);
    }
  }

  private long lastStableDiscoveryInteraction;
  private void pauseStableDiscovery(){
    lastStableDiscoveryInteraction=android.os.SystemClock.elapsedRealtime();
    SourceUpdateActivityPolicy.notePressure(lastStableDiscoveryInteraction);
  }
  @Override public void onWindowFocusChanged(boolean focused){
    super.onWindowFocusChanged(focused);
    if(!focused){
      pauseStableDiscovery();
      clearPendingRemoteZap();
      fullscreenControlGesture=false;
      if(fullscreenControlsVisible)scheduleFullscreenControlsHide();
    }
  }
  // One admission rule for normal stop, failed tune, VOD exit, lock and
  // lifecycle teardown. A caller's request to clear is not proof of idle.
  private boolean sourceUpdatePlaybackMayClear(){
    return SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(
        player!=null||libVlcEngine!=null,
        vodPlayer!=null||vodFallbackBackend!=null||(activityStarted&&currentVodPanel!=null),
        !libVlcReleaseCallbacks.isEmpty()||liveVlcReleaseContinuation!=null||pendingVodStartAfterRelease!=null,
        libVlcReleaseInProgress||pendingVlcTeardowns>0||PROCESS_VLC_TEARDOWNS.get()>0,
        decoderReleaseUnconfirmed,DECODER_OWNERSHIP.availableTo(decoderOwnerToken));
  }
  private void setSourceUpdatePlaybackActive(boolean active){
    SourceUpdateActivityPolicy.setPlaybackActive(active||!sourceUpdatePlaybackMayClear(),
        android.os.SystemClock.elapsedRealtime());
  }
  protected void onStop(){cancelSingleChannelRefresh();revokePendingVodStart();clearPendingRemoteZap();stopInitialDiscoveryProgressTicker();stopLiveUiStallProbe();backgroundCatalogueReloadGeneration++;activityStarted=false;closeFullscreenChannelMenu();clearLiveOverlayCallbacks();if(startupAutoResumeTask!=null){uiHandler.removeCallbacks(startupAutoResumeTask);startupAutoResumeTask=null;}remoteLockUiActive=false;if(remoteParentLockManager!=null)remoteParentLockManager.stop();updateChildUsage();childLockLastElapsed=0L;if(childLockTicker!=null)uiHandler.removeCallbacks(childLockTicker);if(video!=null)video.setKeepScreenOn(false);cancelActiveVodCatalogue();if(networkSpeedUpdater!=null){uiHandler.removeCallbacks(networkSpeedUpdater);networkSpeedUpdater=null;}if(vodPlayerDialog!=null&&vodPlayerDialog.isShowing()){checkpointVodHistory();vodPlayerDialog.dismiss();}super.onStop();saveLastChannel();resumePlaybackOnStart=FullDiscoveryProgressPolicy.shouldResumeAfterBackground(roomFullRebuildCompletedAt,currentVodPanel==null&&selected>=0&&selected<channels.size(),!decoderReleaseUnconfirmed&&(player!=null||libVlcEngine!=null||liveVlcReleaseContinuation!=null));++liveTuneRequestSerial;revokePendingLiveTune();cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();releaseLivePlayerSafely();releaseLibVlcEngine();releaseVodFallback();releaseVodPlayerSafely(null);setSourceUpdatePlaybackActive(false);setSourceUpdateActivityActive(false);stopMainThreadAnrSampler();}
  protected void onDestroy(){cancelSingleChannelRefresh();activityStarted=false;++liveTuneRequestSerial;revokePendingLiveTune();clearPendingRemoteZap();closeFullscreenChannelMenu();clearLiveOverlayCallbacks();invalidateVodPosterSession();vodPosterCache.evictAll();revokePendingVodStart();stopLiveUiStallProbe();cancelActiveVodCatalogue();cataloguePersistWorker.shutdown();cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();if(remoteLockTaskActive){try{stopLockTask();}catch(Exception ignored){}remoteLockTaskActive=false;}if(remoteParentLockManager!=null)remoteParentLockManager.shutdown();if(remoteParentLockDialog!=null&&remoteParentLockDialog.isShowing())remoteParentLockDialog.dismiss();if(childLockTicker!=null)uiHandler.removeCallbacks(childLockTicker);if(childLockDialog!=null&&childLockDialog.isShowing())childLockDialog.dismiss();if(video!=null)video.setKeepScreenOn(false);if(vodPlayerDialog!=null&&vodPlayerDialog.isShowing())vodPlayerDialog.dismiss();releaseVodPlayerSafely(null);releaseLivePlayerSafely();releaseLibVlcEngine();releaseVodFallback();if(pendingSearchRefresh!=null)uiHandler.removeCallbacks(pendingSearchRefresh);super.onDestroy();catalogueFilterGeneration++;catalogueFilterWorker.shutdownNow();worker.shutdownNow();posterWorker.shutdownNow();vodWorker.shutdownNow();vodMetadataWorker.shutdownNow();vodSourceWorker.shutdownNow();setSourceUpdatePlaybackActive(false);setSourceUpdateActivityActive(false);stopMainThreadAnrSampler();}
}