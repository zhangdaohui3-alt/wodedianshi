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
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
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
import androidx.work.WorkInfo;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class MainActivity extends Activity {
  private ExoPlayer player;
  private PlayerView video;
  private LinearLayout rootLayout, categoryPane, channelPane, mainPane;
  private ScrollView channelScroll;
  private TextView playbackInfo;
  private boolean fullScreenPlayback = false;
  private boolean fullscreenRemoteDialogOpen = false;
  private PopupWindow fullscreenMenuPopup;
  private TextView fullscreenMenuButton;
  private TextView fullscreenSourceButton;
  private TextView fullscreenFavoriteButton;
  private LinearLayout categoryMenuItems;
  private Button fullscreenAspectMenuItem;
  private Runnable hideFullscreenControls;
  private boolean fullscreenControlsVisible=false;
  private TextView channelZapOverlay;
  private TextView networkSpeedOverlay;
  private Runnable networkSpeedUpdater;
  private Runnable hideChannelZapOverlay;
  private boolean manualSourceSwitch = false;
  private Button hoveredLiveChannelButton;
  private Button hoveredVodCategoryButton;
  private volatile boolean startupDataReady=false;
  private View hoveredVodCard;
  private final java.util.WeakHashMap<Button,Integer> liveChannelBaseColors=new java.util.WeakHashMap<>();
  private LinearLayout list;
  private TextView info;
  private EditText search;
  private final ArrayList<Channel> channels = new ArrayList<>();
  // Keep country/state/city playlists scoped to the destination currently selected.
  private volatile Set<String> activeGeoChannelKeys=null;
  // Dedicated local-area filter; keep the sidebar category as 地方 so a label
  // such as “全部河北频道” can never fall through to the global category.
  private boolean localRegionFilterActive=false;
  private String activeLocalProvince=null;
  private String activeLocalCity=null;
  private Set<String> parsingGeoChannelKeys=null;
  private final HashSet<String> favorites = new HashSet<>();
  private final ArrayList<Button> menuButtons = new ArrayList<>();
  // Fast indexes prevent quadratic duplicate scans as channel lists grow.
  private final HashMap<String,Channel> channelsByUrl = new HashMap<>();
  private final HashMap<String,Channel> channelsByIdentity = new HashMap<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  // Bound queued thumbnail work: fast scrolling must not build an unbounded
  // network/decode backlog on Android TV. Rejected cards use the local fallback poster.
  private final ExecutorService posterWorker = new ThreadPoolExecutor(
      2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(48),
      new ThreadPoolExecutor.AbortPolicy());
  // Keep catalogue orchestration separate from metadata I/O: nested tasks on one pool can starve each other.
  private final ExecutorService vodWorker = Executors.newSingleThreadExecutor();
  private final ExecutorService vodMetadataWorker = Executors.newFixedThreadPool(5);
  private final ExecutorService vodSourceWorker = Executors.newFixedThreadPool(4);
  private volatile Future<?> activeVodCatalogueTask;
  // Bound poster memory on low-RAM TV boxes. Cache is measured in KiB and
  // capped independently of large heaps so image browsing cannot crowd playback.
  private final LruCache<String,Bitmap> vodPosterCache = new LruCache<String,Bitmap>(
      Math.max(2048,Math.min(8192,(int)(Runtime.getRuntime().maxMemory()/1024/32)))) {
    protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
  };
  private final Set<String> vodPosterRequests = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
  private int vodRequestId=0;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private Runnable pendingSearchRefresh;
  private boolean favoriteMode = false;
  private int selected = -1;
  private long playbackGeneration = 0;
  private String currentMediaId = "";
  private static class Channel {
    String name, url, group;
    ArrayList<String> backups = new ArrayList<>();
    int activeSource = 0;
    Channel(String n,String u,String g){name=n;url=u;group=g;backups.add(u);}
    String key(){return name+"|"+url;}
  }
  public void onCreate(Bundle b) {
    super.onCreate(b);
    setSourceUpdatePlaybackActive(false); // Clear stale state if Android previously killed the process.
    // Render the shell immediately. Parsing a large saved playlist and rebuilding
    // channel indexes can be expensive on older TV boxes, so do it off the UI thread.
    makeUi();
    worker.execute(()->{
      long restoreStarted=android.os.SystemClock.elapsedRealtime();
      restore();
      long restoreElapsed=android.os.SystemClock.elapsedRealtime()-restoreStarted;
      runOnUiThread(()->{
        if(isFinishing())return;
        startupDataReady=true;
        if(search!=null)search.setEnabled(true);
        if(categoryMenuItems!=null)for(int i=0;i<categoryMenuItems.getChildCount();i++)categoryMenuItems.getChildAt(i).setEnabled(true);
        refresh();
        if(info!=null)info.setText(channels.isEmpty()?"频道列表已就绪，请选择栏目或导入频道源":"已载入 "+channels.size()+" 个频道 · 选择频道开始播放");
        // Keep auto-resume, but only after the restored catalogue is ready.
        uiHandler.postDelayed(()->{if(!isFinishing()&&selected<0)restoreLastChannel();},350L);
        android.util.Log.i("MyTVStartup","restore_ms="+restoreElapsed+" channels="+channels.size());
      });
    });
    uiHandler.postDelayed(()->{
      if(!isFinishing())worker.execute(()->{
        loadBundledHdBackups();
        runOnUiThread(()->{if(!isFinishing()){persist();refresh();}});
      });
    },900L);
    uiHandler.postDelayed(()->{if(!isFinishing())scheduleAutomaticSourceUpdates();},1600L);
  }
  // Apply newly bundled, explicitly identified 1080p sources once on upgrade,
  // including for users whose channel list was already saved by an older build.
  private void loadBundledHdBackups(){
    android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
    // The bundled playlist is an upgrade migration, not startup work. The old
    // implementation reparsed the entire M3U and resorted every channel on
    // every launch, making app entry progressively slower as the list grew.
    final int bundledVersion=3;
    if(prefs.getInt("bundled_channels_version",0)>=bundledVersion)return;
    boolean loaded=false;
    try(InputStream in=getAssets().open("default_channels.m3u")){
      parse(read(in));
      loaded=true;
    }catch(Exception ignored){}
    boolean changed=false;
    synchronized(channels){
      for(Channel c:channels){
        if(c.backups==null||c.backups.size()<2)continue;
        String active=(c.activeSource>=0&&c.activeSource<c.backups.size())?c.backups.get(c.activeSource):c.url;
        ArrayList<String> ordered=new ArrayList<>(c.backups);
        // Stable sort: known HD/bitrate sources first; unknown sources keep their relative order.
        ordered.sort((a,b)->Double.compare(observedSourceScore(b),observedSourceScore(a)));
        if(!ordered.equals(c.backups)){
          c.backups.clear();c.backups.addAll(ordered);
          int index=c.backups.indexOf(active);c.activeSource=index>=0?index:0;
          changed=true;
        }
      }
    }
    if(changed)persist();
    if(loaded)prefs.edit().putBoolean("source_quality_order_v2",true).putInt("bundled_channels_version",bundledVersion).apply();
  }
  private int sourceQualityScore(String source){
    return SourceQualityScorer.labelScore(source);
  }
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  // Mouse hover and D-pad focus are independent states. Hover never steals or clears focus.
  private void styleLiveChannelButton(Button b,int normalColor){
    liveChannelBaseColors.put(b,normalColor);
    b.setFocusable(true);b.setFocusableInTouchMode(true);
    b.setTextColor(Color.WHITE);
    b.setBackground(makeButtonBg(normalColor,false));
    b.setOnFocusChangeListener((v,focused)->{
      if(hoveredLiveChannelButton!=b)b.setBackground(makeButtonBg(focused?Color.rgb(38,82,125):normalColor,focused));
      b.setScaleX(focused?1.04f:1f);b.setScaleY(focused?1.04f:1f);
      b.setElevation(focused?dp(8):0);
    });
    b.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
        if(hoveredLiveChannelButton!=null&&hoveredLiveChannelButton!=b){
          Button old=hoveredLiveChannelButton;
          int oldBase=liveChannelBaseColors.containsKey(old)?liveChannelBaseColors.get(old):Color.rgb(35,45,62);
          old.setBackground(makeButtonBg(oldBase,old.hasFocus()));
          old.setScaleX(old.hasFocus()?1.04f:1f);old.setScaleY(old.hasFocus()?1.04f:1f);old.setElevation(old.hasFocus()?dp(8):0);
        }
        hoveredLiveChannelButton=b;
        GradientDrawable hover=new GradientDrawable();hover.setColor(Color.rgb(32,78,118));hover.setCornerRadius(dp(7));
        hover.setStroke(dp(2),Color.rgb(105,185,245));b.setBackground(hover);
        b.setScaleX(1.04f);b.setScaleY(1.04f);b.setElevation(dp(8));
        return true;
      }
      if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
        if(hoveredLiveChannelButton==b)hoveredLiveChannelButton=null;
        b.setBackground(makeButtonBg(normalColor,b.hasFocus()));
        b.setScaleX(b.hasFocus()?1.04f:1f);b.setScaleY(b.hasFocus()?1.04f:1f);b.setElevation(b.hasFocus()?dp(8):0);
        return true;
      }
      return false;
    });
  }
  private Button hoveredVodPageButton;
  private final java.util.WeakHashMap<Button,Integer> vodPageBaseColors=new java.util.WeakHashMap<>();
  private void styleVodPageButton(Button b,int normalColor){
    vodPageBaseColors.put(b,normalColor);
    b.setFocusable(true);b.setFocusableInTouchMode(true);b.setTextColor(Color.WHITE);
    b.setBackground(makeButtonBg(normalColor,false));
    b.setOnFocusChangeListener((v,focused)->{
      if(hoveredVodPageButton!=b)b.setBackground(makeButtonBg(normalColor,focused));
      b.setScaleX(focused?1.04f:1f);b.setScaleY(focused?1.04f:1f);
      b.setElevation(focused?dp(8):0);
    });
    b.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
        if(hoveredVodPageButton!=null&&hoveredVodPageButton!=b){
          Button old=hoveredVodPageButton;
          int base=vodPageBaseColors.containsKey(old)?vodPageBaseColors.get(old):Color.rgb(43,68,94);
          old.setBackground(makeButtonBg(base,old.hasFocus()));
          old.setScaleX(old.hasFocus()?1.04f:1f);old.setScaleY(old.hasFocus()?1.04f:1f);old.setElevation(old.hasFocus()?dp(8):0);
        }
        hoveredVodPageButton=b;
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(32,92,145));
        bg.setCornerRadius(dp(7));bg.setStroke(dp(2),Color.rgb(120,195,250));
        b.setBackground(bg);b.setScaleX(1.04f);b.setScaleY(1.04f);b.setElevation(dp(8));
        return false;
      }
      if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
        if(hoveredVodPageButton==b)hoveredVodPageButton=null;
        b.setBackground(makeButtonBg(normalColor,b.hasFocus()));
        b.setScaleX(b.hasFocus()?1.04f:1f);b.setScaleY(b.hasFocus()?1.04f:1f);b.setElevation(b.hasFocus()?dp(8):0);
        return false;
      }
      return false;
    });
  }
  private Button hoveredTvButton;
  private final java.util.WeakHashMap<Button,Integer> tvButtonBaseColors=new java.util.WeakHashMap<>();
  private void styleTvButton(Button b,int normalColor){
    tvButtonBaseColors.put(b,normalColor);
    b.setFocusable(true); b.setFocusableInTouchMode(true);
    b.setTextColor(Color.WHITE);
    b.setBackground(makeButtonBg(normalColor,false));
    b.setOnFocusChangeListener((v,focused)->{
      if(hoveredTvButton!=b)b.setBackground(makeButtonBg(normalColor,focused));
      b.setScaleX(focused?1.025f:1f); b.setScaleY(focused?1.025f:1f);
      b.setElevation(focused?dp(6):0);
    });
    b.setOnHoverListener((v,event)->{
      int action=event.getActionMasked();
      if(action==android.view.MotionEvent.ACTION_HOVER_ENTER||action==android.view.MotionEvent.ACTION_HOVER_MOVE){
        if(hoveredTvButton!=null&&hoveredTvButton!=b){
          Button old=hoveredTvButton;
          int base=tvButtonBaseColors.containsKey(old)?tvButtonBaseColors.get(old):Color.rgb(16,24,40);
          old.setBackground(makeButtonBg(base,old.hasFocus()));
          old.setScaleX(old.hasFocus()?1.025f:1f);old.setScaleY(old.hasFocus()?1.025f:1f);
          old.setElevation(old.hasFocus()?dp(6):0);
        }
        hoveredTvButton=b;
        GradientDrawable hover=new GradientDrawable();hover.setColor(Color.rgb(35,91,139));
        hover.setCornerRadius(dp(7));hover.setStroke(dp(2),Color.rgb(105,185,245));
        b.setBackground(hover);b.setScaleX(1.025f);b.setScaleY(1.025f);b.setElevation(dp(6));
        return true;
      }
      if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
        if(hoveredTvButton==b)hoveredTvButton=null;
        b.setBackground(makeButtonBg(normalColor,b.hasFocus()));
        b.setScaleX(b.hasFocus()?1.025f:1f);b.setScaleY(b.hasFocus()?1.025f:1f);
        b.setElevation(b.hasFocus()?dp(6):0);
        return true;
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
    return getSharedPreferences("mytv",0).getInt("decoder_mode",0)==1;
  }
  private void restore(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    favorites.addAll(p.getStringSet("fav",new HashSet<>()));
    try {
      org.json.JSONArray a=new org.json.JSONArray(p.getString("channels","[]"));
      for(int i=0;i<a.length();i++){org.json.JSONObject o=a.getJSONObject(i);Channel c=new Channel(o.getString("name"),o.getString("url"),o.optString("group","")); org.json.JSONArray src=o.optJSONArray("sources"); if(src!=null){c.backups.clear();for(int j=0;j<src.length();j++){String u=src.optString(j);if(u.startsWith("http://")||u.startsWith("https://"))c.backups.add(u);}if(c.backups.isEmpty())c.backups.add(c.url);}c.activeSource=Math.max(0,Math.min(o.optInt("activeSource",0),c.backups.size()-1));channels.add(c);}
    } catch(Exception ignored) {}
    rebuildChannelIndexes();
    if(!p.getBoolean("defaults_loaded",false)){
      if(channels.isEmpty()){
        try(InputStream in=getAssets().open("default_channels.m3u")){
          String bundled=read(in);
          parse(bundled);
          persist();
        } catch(Exception ignored) {}
      }
      p.edit().putBoolean("defaults_loaded",true).apply();
    }
  }
  private void rebuildChannelIndexes(){
    synchronized(channels){
      // Preserve display order while using a plain hash index for duplicate lookup.
      // This avoids allocating a LinkedHashMap entry for every restored channel.
      HashMap<String,Channel> firstByIdentity=new HashMap<>(Math.max(16,channels.size()*2));
      ArrayList<Channel> compact=new ArrayList<>(channels.size());
      for(Channel c:channels){
        if(c.backups==null)c.backups=new ArrayList<>();
        if(c.backups.isEmpty()&&c.url!=null&&!c.url.isEmpty())c.backups.add(c.url);
        String key=identity(c.name,c.group,c.url);
        Channel keep=firstByIdentity.get(key);
        if(keep==null){firstByIdentity.put(key,c);compact.add(c);}
        else{
          for(String source:c.backups)if(source!=null&&!source.isEmpty()&&!keep.backups.contains(source))keep.backups.add(source);
          if(favorites.contains(c.key()))favorites.add(keep.key());
        }
      }
      channels.clear();channels.addAll(compact);
      channelsByUrl.clear();channelsByIdentity.clear();
      for(Channel c:channels){
        for(String u:c.backups)channelsByUrl.put(u,c);
        channelsByIdentity.put(identity(c.name,c.group,c.url),c);
      }
    }
  }
  private String canonicalChannelName(String name){
    String value=name==null?"":name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_—–·•]","");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)[-]?(\\d{1,2}\\+?|NEWS|4K|8K)").matcher(value);
    if(m.find())return "CCTV-"+m.group(1);
    String[] chinese={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
    for(int i=1;i<chinese.length;i++)if(value.matches(".*中央(?:电视台)?"+chinese[i]+"套.*"))return "CCTV-"+i;
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
    return canonicalChannelName(c==null?"":c.name).startsWith("CCTV-")
        ||(c!=null&&((c.name+" "+c.group).toUpperCase(Locale.ROOT).contains("CCTV")
        ||(c.name+" "+c.group).contains("央视")||(c.name+" "+c.group).contains("中央电视台")));
  }
  private int cctvSortOrder(Channel c){
    String key=canonicalChannelName(c==null?"":c.name);
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
    if(!key.startsWith("CCTV-"))return name==null?"":name;
    String suffix=key.substring(5);
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
  private String identity(String name,String group){
    String canonical=canonicalChannelName(name);
    if(canonical.startsWith("CCTV-"))return canonical+"|cctv";
    return canonical.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
  }
  // Satellite channels have a stable canonical identity across different source hosts.
  // Generic/local names retain host separation to avoid merging unrelated "News" channels.
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
    String source=(c.activeSource>=0&&c.activeSource<c.backups.size())?c.backups.get(c.activeSource):c.url;
    getSharedPreferences("mytv",0).edit().putString("last_channel_identity",identity(c.name,c.group,c.url)).putString("last_channel_url",c.url).putString("last_source_url",source).putInt("last_source_index",c.activeSource).apply();
  }
  private void restoreLastChannel(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    String savedIdentity=p.getString("last_channel_identity","");
    if(savedIdentity.isEmpty())return;
    String savedUrl=p.getString("last_channel_url","");
    String savedSource=p.getString("last_source_url","");
    int found=-1;
    for(int i=0;i<channels.size();i++){Channel c=channels.get(i);if(identity(c.name,c.group,c.url).equals(savedIdentity)&&(savedUrl.isEmpty()||c.url.equals(savedUrl))){found=i;break;}}
    if(found<0){for(int i=0;i<channels.size();i++){Channel c=channels.get(i);if(identity(c.name,c.group,c.url).equals(savedIdentity)){found=i;break;}}}
    // Older builds stored the pre-normalized identity; use the saved primary URL
    // as a migration fallback after duplicate channels have been consolidated.
    if(found<0&&!savedUrl.isEmpty()){for(int i=0;i<channels.size();i++){Channel c=channels.get(i);if(c.url.equals(savedUrl)||c.backups.contains(savedUrl)){found=i;break;}}}
    if(found<0)return;
    Channel c=channels.get(found);
    if(!savedSource.isEmpty()){int source=c.backups.indexOf(savedSource);if(source>=0)c.activeSource=(sourceQualityScore(c.backups.get(0))>sourceQualityScore(savedSource))?0:source;else c.activeSource=Math.max(0,Math.min(p.getInt("last_source_index",0),c.backups.size()-1));}
    else c.activeSource=Math.max(0,Math.min(p.getInt("last_source_index",0),c.backups.size()-1));
    play(found);
  }
  private void persist(){
    try {
      org.json.JSONArray a=new org.json.JSONArray();
      for(Channel c:channels){org.json.JSONObject o=new org.json.JSONObject();o.put("name",c.name);o.put("url",c.url);o.put("group",c.group);o.put("activeSource",c.activeSource);org.json.JSONArray src=new org.json.JSONArray();for(String u:c.backups)src.put(u);o.put("sources",src);a.put(o);}
      getSharedPreferences("mytv",0).edit().putString("channels",a.toString()).putStringSet("fav",new HashSet<>(favorites)).apply();
    } catch(Exception ignored) {}
  }
  private String activeCategory="全部";
  private void makeUi(){
    LinearLayout root=new LinearLayout(this);rootLayout=root;root.setOrientation(0);root.setPadding(dp(10),dp(8),dp(10),dp(8));root.setBackgroundColor(Color.rgb(8,13,24));
    LinearLayout cats=new LinearLayout(this);categoryPane=cats;cats.setOrientation(1);cats.setBackgroundColor(Color.rgb(16,24,40));
    TextView logo=new TextView(this);logo.setText("我的\\n电视");logo.setTextColor(Color.WHITE);logo.setTextSize(23);logo.setGravity(Gravity.CENTER);cats.addView(logo,new LinearLayout.LayoutParams(-1,dp(82)));
    // "中国" was an empty duplicate entry; China channels are already grouped by CCTV,
    // satellite and local areas. Keep one clear "地方" entry.
    String[] names={"点播","央视","卫视","地方","港澳台","全部","全球频道","国家地区","娱乐频道","国际新闻","体育","天气","教育","收藏","自建频道"};
    LinearLayout catItems=new LinearLayout(this);categoryMenuItems=catItems;catItems.setOrientation(1);
    for(String name:names){Button x=new Button(this);x.setText(name);x.setTextColor(Color.WHITE);x.setTextSize(16);x.setMinHeight(dp(48));styleTvButton(x,name.equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40));x.setOnClickListener(v->{if(!startupDataReady){toast("正在恢复频道数据，请稍候");return;}if(name.equals("点播")){vodDialog();return;}activeGeoChannelKeys=null;localRegionFilterActive=false;activeLocalProvince=null;activeLocalCity=null;activeCategory=name;favoriteMode=name.equals("收藏");if(name.equals("全球频道"))loadGlobalChannels();if(name.equals("国家地区"))showCountryDialog();if(name.equals("娱乐频道"))loadEntertainmentChannels();if(name.equals("港澳台"))loadRegionChannels();if(name.equals("央视")||name.equals("卫视")||name.equals("地方"))loadChinaChannels();if(name.equals("地方"))showProvinceDialog();for(int i=0;i<catItems.getChildCount();i++){View z=catItems.getChildAt(i);if(z instanceof Button)((Button)z).setBackground(makeButtonBg(((Button)z).getText().toString().equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40),z.hasFocus()));}refresh();focusFirstChannelInMenu();});catItems.addView(x,new LinearLayout.LayoutParams(-1,dp(50)));}
    ScrollView catScroll=new ScrollView(this);catScroll.setFillViewport(false);catScroll.addView(catItems);cats.addView(catScroll,new LinearLayout.LayoutParams(-1,0,1));
    Button settings=new Button(this);settings.setText("设置");settings.setTextColor(Color.WHITE);styleTvButton(settings,Color.rgb(35,45,62));settings.setOnClickListener(v->{if(!startupDataReady){toast("正在恢复频道数据，请稍候");return;}new AlertDialog.Builder(this).setTitle("设置与管理").setItems(new String[]{"导入 M3U 文件","导入播放列表网址","手动添加频道","导出 M3U 配置","官方平台入口","全球正版电影点播","解码与声音设置","卡顿自动切换备用源","立即更新频道源","后台自动更新："+(getSharedPreferences("mytv",0).getBoolean("auto_source_update",true)?"开启":"关闭"),"查看更新状态","右下角显示网速："+(getSharedPreferences("mytv",0).getBoolean("show_network_speed",false)?"开启":"关闭")},(d,w)->{if(w==0)chooseFile();else if(w==1)urlDialog();else if(w==2)addDialog();else if(w==3)exportFile();else if(w==4)officialDialog();else if(w==5)vodDialog();else if(w==6)decoderSettingsDialog();else if(w==7)bufferingSwitchSettingsDialog();else if(w==8)startManualSourceUpdate();else if(w==9)toggleAutomaticSourceUpdates();else if(w==10)showSourceUpdateStatus();else toggleNetworkSpeed();}).show();});cats.addView(settings,new LinearLayout.LayoutParams(-1,dp(58)));
    LinearLayout channelPane=new LinearLayout(this);this.channelPane=channelPane;channelPane.setOrientation(1);channelPane.setPadding(dp(8),0,dp(8),0);channelPane.setBackgroundColor(Color.rgb(19,29,47));
    TextView title=new TextView(this);title.setText("频道列表");title.setTextColor(Color.WHITE);title.setTextSize(19);title.setPadding(dp(8),dp(10),0,dp(8));channelPane.addView(title);
    search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);search.setHintTextColor(Color.LTGRAY);search.setHint("搜索频道");search.setEnabled(false);channelPane.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
    list=new LinearLayout(this);list.setOrientation(1);channelScroll=new ScrollView(this);channelScroll.setFocusable(false);channelScroll.addView(list);channelPane.addView(channelScroll,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout main=new LinearLayout(this);mainPane=main;main.setOrientation(1);main.setPadding(dp(10),0,0,0);
    info=new TextView(this);playbackInfo=info;info.setText("欢迎使用我的电视\\n请从左侧选择栏目，或在设置中导入你有权使用的直播源。");info.setTextColor(Color.WHITE);info.setTextSize(16);info.setPadding(dp(8),dp(8),dp(8),dp(8));info.setOnClickListener(v->showLiveDiagnostics());info.setContentDescription("点击查看直播诊断信息");main.addView(info,new LinearLayout.LayoutParams(-1,dp(62)));
    video=new PlayerView(this);int liveAspect=getSharedPreferences("mytv",0).getInt("live_aspect_mode",0);video.setResizeMode(liveAspect==1?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM:liveAspect==2?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL:androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);// 按原始比例完整显示，避免部分直播源全屏时被放大裁切。直播采用实时电视控制：不显示进度条、快退/快进和暂停控制；音量仍由电视遥控器系统音量键控制。
    video.setUseController(false);video.setFocusable(true);video.setFocusableInTouchMode(true);video.setBackgroundColor(Color.BLACK);video.setOnClickListener(v->{if(fullScreenPlayback){if(fullscreenControlsVisible)hideFullscreenControlsNow();else showFullscreenControls();}});fullscreenMenuButton=new TextView(this);fullscreenMenuButton.setText("☰ 频道选择");fullscreenMenuButton.setTextColor(Color.WHITE);fullscreenMenuButton.setTextSize(16);fullscreenMenuButton.setGravity(Gravity.CENTER);fullscreenMenuButton.setPadding(dp(14),dp(9),dp(14),dp(9));fullscreenMenuButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenMenuButton.setFocusable(true);fullscreenMenuButton.setClickable(true);fullscreenMenuButton.setElevation(dp(12));fullscreenMenuButton.setOnClickListener(v->showFullscreenChannelMenu());video.addView(fullscreenMenuButton,new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.LEFT));fullscreenMenuButton.setVisibility(View.GONE);channelZapOverlay=new TextView(this);channelZapOverlay.setTextColor(Color.WHITE);channelZapOverlay.setTextSize(17);channelZapOverlay.setTypeface(null,android.graphics.Typeface.BOLD);channelZapOverlay.setMaxWidth(dp(420));channelZapOverlay.setMaxLines(2);channelZapOverlay.setEllipsize(android.text.TextUtils.TruncateAt.END);channelZapOverlay.setPadding(dp(12),dp(7),dp(12),dp(7));GradientDrawable zapBg=new GradientDrawable();zapBg.setColor(0xDD101A2A);zapBg.setCornerRadius(dp(9));zapBg.setStroke(dp(1),0xFF6EA8E8);channelZapOverlay.setBackground(zapBg);channelZapOverlay.setElevation(dp(16));channelZapOverlay.setClickable(true);channelZapOverlay.setContentDescription("点击此状态框查看直播诊断记录");channelZapOverlay.setOnClickListener(v->showLiveDiagnostics());channelZapOverlay.setVisibility(View.GONE);FrameLayout.LayoutParams zapParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);zapParams.topMargin=dp(16);video.addView(channelZapOverlay,zapParams);fullscreenSourceButton=new TextView(this);fullscreenSourceButton.setText("换源");fullscreenSourceButton.setTextColor(Color.WHITE);fullscreenSourceButton.setTextSize(15);fullscreenSourceButton.setGravity(Gravity.CENTER);fullscreenSourceButton.setPadding(dp(14),dp(9),dp(14),dp(9));fullscreenSourceButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenSourceButton.setElevation(dp(12));fullscreenSourceButton.setFocusable(true);fullscreenSourceButton.setClickable(true);fullscreenSourceButton.setOnClickListener(v->showSourcePicker());FrameLayout.LayoutParams sourceParams=new FrameLayout.LayoutParams(-2,dp(46),Gravity.TOP|Gravity.RIGHT);sourceParams.topMargin=dp(8);sourceParams.rightMargin=dp(8);video.addView(fullscreenSourceButton,sourceParams);fullscreenSourceButton.setVisibility(View.GONE);fullscreenFavoriteButton=new TextView(this);fullscreenFavoriteButton.setText("☆ 收藏");fullscreenFavoriteButton.setTextColor(Color.WHITE);fullscreenFavoriteButton.setTextSize(15);fullscreenFavoriteButton.setGravity(Gravity.CENTER);fullscreenFavoriteButton.setPadding(dp(12),dp(9),dp(12),dp(9));fullscreenFavoriteButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenFavoriteButton.setElevation(dp(12));fullscreenFavoriteButton.setFocusable(true);fullscreenFavoriteButton.setClickable(true);fullscreenFavoriteButton.setContentDescription("收藏当前频道");fullscreenFavoriteButton.setOnClickListener(v->toggleCurrentLiveFavorite());FrameLayout.LayoutParams favoriteParams=new FrameLayout.LayoutParams(-2,dp(46),Gravity.TOP|Gravity.RIGHT);favoriteParams.topMargin=dp(8);favoriteParams.rightMargin=dp(76);video.addView(fullscreenFavoriteButton,favoriteParams);fullscreenFavoriteButton.setVisibility(View.GONE);networkSpeedOverlay=new TextView(this);networkSpeedOverlay.setTextColor(Color.WHITE);networkSpeedOverlay.setTextSize(13);networkSpeedOverlay.setTypeface(null,android.graphics.Typeface.BOLD);networkSpeedOverlay.setPadding(dp(10),dp(6),dp(10),dp(6));GradientDrawable speedBg=new GradientDrawable();speedBg.setColor(0x99000000);speedBg.setCornerRadius(dp(7));networkSpeedOverlay.setBackground(speedBg);networkSpeedOverlay.setText("接收 0 KB/s");networkSpeedOverlay.setVisibility(getSharedPreferences("mytv",0).getBoolean("show_network_speed",false)?View.VISIBLE:View.GONE);FrameLayout.LayoutParams speedParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);speedParams.rightMargin=dp(12);speedParams.bottomMargin=dp(12);video.addView(networkSpeedOverlay,speedParams);main.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(cats,new LinearLayout.LayoutParams(dp(104),-1));root.addView(channelPane,new LinearLayout.LayoutParams(dp(250),-1));root.addView(main,new LinearLayout.LayoutParams(0,-1,1));setContentView(root);
    search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pendingSearchRefresh!=null)uiHandler.removeCallbacks(pendingSearchRefresh);pendingSearchRefresh=()->refresh();uiHandler.postDelayed(pendingSearchRefresh,220);}public void afterTextChanged(android.text.Editable e){}});
    refresh();
    root.post(()->{if(!fullScreenPlayback&&root.findFocus()==null&&catItems.getChildCount()>0)catItems.getChildAt(0).requestFocus();});
    if(!channels.isEmpty())info.setText("已载入 "+channels.size()+" 个频道 · 选择频道开始播放");
    // Restore the last channel only after the first frame is on screen. Player creation
    // can be expensive on older Android TV boxes and should not block app entry.
    // Last-channel restoration is scheduled after asynchronous catalogue recovery in onCreate.
    updateNetworkSpeedMonitor();
  }
  private void addButton(LinearLayout p,String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setTextSize(17);b.setMinHeight(dp(54));b.setFocusable(true);b.setFocusableInTouchMode(true);b.setOnFocusChangeListener((v,has)->{v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(has?Color.rgb(38,110,190):Color.rgb(35,45,62)));});b.setOnClickListener(l);b.setId(View.generateViewId());b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35,45,62)));b.setTextColor(Color.WHITE);menuButtons.add(b);p.addView(b,new LinearLayout.LayoutParams(-1,dp(58)));}
  // Do not forward every remote-control key to PlayerView.
  // Doing so steals DPAD focus from AlertDialog lists on Android TV.
  private void scheduleAutomaticSourceUpdates(){
    WorkManager wm=WorkManager.getInstance(this);
    boolean enabled=getSharedPreferences("mytv",0).getBoolean("auto_source_update",true);
    if(!enabled){wm.cancelUniqueWork("mytv_periodic_source_update");return;}
    Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(SourceUpdateWorker.class,24,TimeUnit.HOURS)
        .setConstraints(constraints).build();
    wm.enqueueUniquePeriodicWork("mytv_periodic_source_update",ExistingPeriodicWorkPolicy.KEEP,request);
  }
  private void toggleAutomaticSourceUpdates(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    boolean enabled=!p.getBoolean("auto_source_update",true);
    p.edit().putBoolean("auto_source_update",enabled).apply();
    if(enabled)scheduleAutomaticSourceUpdates();else WorkManager.getInstance(this).cancelUniqueWork("mytv_periodic_source_update");
    toast(enabled?"后台自动更新已开启（有网络时约每 24 小时检查）":"后台自动更新已关闭；仍可在设置中手动更新");
  }
  private void startManualSourceUpdate(){
    Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SourceUpdateWorker.class).setConstraints(constraints).setInputData(new Data.Builder().putBoolean("manual",true).build()).build();
    WorkManager.getInstance(this).enqueueUniqueWork("mytv_manual_source_update",ExistingWorkPolicy.REPLACE,request);
    toast("已提交频道源更新；更新完成后会自动合并，不会打断当前播放");
    WorkManager.getInstance(this).getWorkInfoById(request.getId()).addListener(()->{
      try{
        WorkInfo result=WorkManager.getInstance(this).getWorkInfoById(request.getId()).get();
        if(result!=null&&result.getState()==WorkInfo.State.SUCCEEDED){
          runOnUiThread(()->{reloadChannelsAfterSourceUpdate();showSourceUpdateStatus();});
        }else if(result!=null&&result.getState()==WorkInfo.State.FAILED){
          runOnUiThread(()->toast("频道源更新失败，请检查网络后重试"));
        }
      }catch(Exception ignored){}
    },worker);
  }
  private void reloadChannelsAfterSourceUpdate(){
    String keepIdentity="";
    if(selected>=0&&selected<channels.size())keepIdentity=identity(channels.get(selected).name,channels.get(selected).group,channels.get(selected).url);
    try{
      android.content.SharedPreferences p=getSharedPreferences("mytv",0);
      org.json.JSONArray a=new org.json.JSONArray(p.getString("channels","[]"));
      ArrayList<Channel> updated=new ArrayList<>();
      for(int i=0;i<a.length();i++){
        org.json.JSONObject o=a.getJSONObject(i);
        Channel c=new Channel(o.getString("name"),o.getString("url"),o.optString("group",""));
        org.json.JSONArray src=o.optJSONArray("sources");
        if(src!=null){c.backups.clear();for(int j=0;j<src.length();j++){String u=src.optString(j);if(u.startsWith("http://")||u.startsWith("https://"))c.backups.add(u);}if(c.backups.isEmpty())c.backups.add(c.url);}
        updated.add(c);
      }
      synchronized(channels){channels.clear();channels.addAll(updated);}
      favorites.clear();favorites.addAll(p.getStringSet("fav",new HashSet<>()));
      rebuildChannelIndexes();
      selected=-1;
      if(!keepIdentity.isEmpty())for(int i=0;i<channels.size();i++)if(identity(channels.get(i).name,channels.get(i).group,channels.get(i).url).equals(keepIdentity)){selected=i;break;}
      refresh();
      if(selected>=0)info.setText("频道源更新完成，当前仍保留原播放画面："+channels.get(selected).name+"。如需使用新备用源，可在卡顿时自动切换或手动切换。");
    }catch(Exception e){info.setText("更新已完成，但刷新频道列表失败："+e.getMessage());}
  }
  private void showSourceUpdateStatus(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    String time=p.getString("source_update_time","尚未更新");
    String result=p.getString("source_update_result","尚无更新记录");
    new AlertDialog.Builder(this).setTitle("频道源更新状态")
      .setMessage("后台自动更新："+(p.getBoolean("auto_source_update",true)?"开启":"关闭")+"（有网络时约每 24 小时检查一次）\\n上次检查："+time+"\\n更新结果："+result+"\\n\\n说明：更新会保留已有频道和备用源；新地址会先做连通性检查。直播是否能播放仍可能受地区、版权和源站限制。")
      .setPositiveButton("立即更新",(d,w)->startManualSourceUpdate()).setNegativeButton("关闭",null).show();
  }
  private void decoderSettingsDialog(){
    android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
    int current=prefs.getInt("decoder_mode",0);
    String[] modes={"自动解码（系统选择）","兼容解码（启用解码器回退，推荐无声时尝试）"};
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
    String title,identifier,url,license;
    VodItem(String t,String id,String u,String l){title=t;identifier=id;url=u;license=l;}
  }
  private Dialog vodPlayerDialog;
  // Keep explicit references to the active VOD view model; never infer controls by child index.
  private LinearLayout currentVodPanel,currentVodResults;
  private TextView currentVodStatus;
  private String activeVodCategory="多源精选";
  private String activeVodQuery="";
  // Keep the user scroll position when the same category finishes background refresh.
  private String currentVodRenderedCategory="";
  private ExoPlayer vodPlayer;
  private Runnable vodUiUpdater;
  private SeekBar vodSeekBar;
  private TextView vodPositionLabel,vodDurationLabel;
  private VodItem activeVodItem;
  private Runnable vodProgressSaver;
  private boolean resumeLiveAfterVod=false;
  private void vodDialog(){
    // 点播期间暂停直播，避免目录和电影播放时主频道仍在后台发声/占用解码器。
    resumeLiveAfterVod=player!=null&&player.isPlaying();
    setSourceUpdatePlaybackActive(true);
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    releaseLivePlayerSafely();
    final java.util.ArrayList<Button> vodCategoryHoverTargets=new java.util.ArrayList<>();
    // Track pointer movement at the dialog content root, not only inside the
    // category strip. Android 9 TV firmware can omit the strip's HOVER_EXIT
    // when the pointer crosses directly into the poster GridView.
    LinearLayout panel=new LinearLayout(this){
      // Observe hover before child dispatch. Returning false keeps GridView and
      // category buttons fully interactive while allowing direct reverse travel.
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
        }else if(action==android.view.MotionEvent.ACTION_HOVER_EXIT){
          setVodCategoryHover(null);
        }
        return super.onInterceptHoverEvent(event);
      }
    };
    panel.setOrientation(1);panel.setPadding(dp(8),0,dp(8),dp(2));panel.setBackgroundColor(Color.rgb(12,20,34));
    LinearLayout titleBar=new LinearLayout(this);titleBar.setOrientation(0);titleBar.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
    Button refreshVodButton=new Button(this);refreshVodButton.setText("重新抓取");refreshVodButton.setAllCaps(false);refreshVodButton.setTextSize(12);refreshVodButton.setMinWidth(0);refreshVodButton.setMinHeight(0);refreshVodButton.setPadding(dp(6),0,dp(6),0);styleTvButton(refreshVodButton,Color.rgb(26,100,160));titleBar.addView(refreshVodButton,new LinearLayout.LayoutParams(dp(88),dp(38)));
    Button closeButton=new Button(this);closeButton.setText("关闭");closeButton.setAllCaps(false);closeButton.setTextSize(13);closeButton.setMinWidth(0);closeButton.setMinHeight(0);closeButton.setIncludeFontPadding(false);closeButton.setPadding(dp(8),0,dp(8),0);styleTvButton(closeButton,Color.rgb(65,78,96));titleBar.setPadding(dp(4),dp(3),dp(4),dp(3));titleBar.addView(closeButton,new LinearLayout.LayoutParams(dp(82),dp(38)));panel.addView(titleBar,new LinearLayout.LayoutParams(-1,dp(38)));
    LinearLayout filters=new LinearLayout(this);filters.setOrientation(0);filters.setGravity(Gravity.CENTER_VERTICAL);
    java.util.HashMap<String,Button> categoryButtons=new java.util.HashMap<>();
    String[] categories={"我的收藏","播放记录","电影","公开电视剧","公开综艺","多源精选","中国电影","中国经典影像","中国历史影像","戏曲","1080P高清电影","中国动画","中国纪录片","世界公版电影","纪录片·历史","纪录片·自然","纪录片·科学","战争片","恐怖片","动作片","欧美经典","纪录片","动画","喜剧","科幻","剧情"};
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
        activeVodCategory=chosen;activeVodQuery="";query.setText("");setVodCategoryHover(null);
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
    updateVodCategoryHighlight(categoryButtons,"多源精选");
    query.setSingleLine(true);query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);query.setTextColor(Color.WHITE);query.setHintTextColor(Color.LTGRAY);query.setHint("输入片名/演员/关键词，支持中英文");searchRow.addView(query,new LinearLayout.LayoutParams(0,dp(40),1));
    Button searchButton=new Button(this);searchButton.setText("搜索");searchButton.setAllCaps(false);styleTvButton(searchButton,Color.rgb(26,100,160));searchRow.addView(searchButton,new LinearLayout.LayoutParams(dp(84),dp(40)));
    panel.addView(searchRow,new LinearLayout.LayoutParams(-1,dp(42)));searchRow.setVisibility(View.GONE);
    panel.addView(suggestionPanel,new LinearLayout.LayoutParams(-1,dp(166)));suggestionPanel.setVisibility(View.GONE);
    TextView status=new TextView(this);status.setText("正在载入精选影片…");status.setTextColor(Color.LTGRAY);status.setTextSize(12);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setPadding(dp(4),0,dp(4),0);panel.addView(status,new LinearLayout.LayoutParams(-1,dp(20)));
    // No visible previous/next buttons: poster pages are navigated by
    // native GridView scrolling, mouse wheel/gesture and TV D-pad.
    // GridView recycles off-screen cards while retaining native touch and D-pad navigation.
    LinearLayout results=new LinearLayout(this);results.setOrientation(1);results.setFocusable(false);
    GridView vodGrid=ensureVodGrid(results,3);
    vodGrid.setId(View.generateViewId());
    panel.addView(results,new LinearLayout.LayoutParams(-1,0,1));
    currentVodPanel=panel;currentVodStatus=status;currentVodResults=results;
    panel.setLayoutParams(new ViewGroup.LayoutParams(-1,-1));
    Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    dialog.setContentView(panel);
    closeButton.setOnClickListener(v->dialog.dismiss());
    refreshVodButton.setOnClickListener(v->loadVodCatalogue(activeVodQuery.isEmpty()?activeVodCategory:"搜索："+activeVodQuery,panel,status,results,true));
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
      {"内容类型","电影","公开电视剧","公开综艺","纪录片","动画","戏曲","短片","央视公开节目"},
      {"题材分类","动作","战争","悬疑","犯罪","喜剧","科幻","恐怖","爱情","家庭","历史"},
      {"地区与片库","中国","华语","香港","台湾","欧美","日本","公版经典","历史影像","自然纪录片"}
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
    loadVodCatalogue("多源精选",panel,status,results);
    dialog.setOnDismissListener(d->{
      cancelActiveVodCatalogue();
      if(resumeLiveAfterVod&&selected>=0&&selected<channels.size()){
        // Recreate the live player only after VOD releases its decoder resources.
        startCurrentSource();
      }
      resumeLiveAfterVod=false;
      setSourceUpdatePlaybackActive(player!=null&&player.isPlaying());
      if(currentVodPanel==panel){currentVodPanel=null;currentVodStatus=null;currentVodResults=null;}
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
    dialog.show();
  }
  private void loadVodCatalogue(String category,LinearLayout panel){
    if(panel==null||panel!=currentVodPanel||currentVodStatus==null||currentVodResults==null)return;
    loadVodCatalogue(category,panel,currentVodStatus,currentVodResults);
  }
  private String vodCacheKey(String category){
    return "vod_cache_v8_"+Integer.toHexString(category.hashCode());
  }
  private String vodCacheTimeKey(String category){return vodCacheKey(category)+"_saved_at";}
  private boolean isVodCacheFresh(String category){
    long saved=getSharedPreferences("mytv",0).getLong(vodCacheTimeKey(category),0L);
    return saved>0L&&System.currentTimeMillis()-saved<6L*60L*60L*1000L;
  }
  private ArrayList<VodItem> readVodCache(String category){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      String raw=getSharedPreferences("mytv",0).getString(vodCacheKey(category),"");
      if(raw.isEmpty())return items;
      org.json.JSONArray a=new org.json.JSONArray(raw);
      for(int i=0;i<a.length();i++){
        org.json.JSONObject o=a.optJSONObject(i);if(o==null)continue;
        String title=o.optString("title",""),id=o.optString("id",""),url=o.optString("url",""),license=o.optString("license","");
        if(!title.isEmpty()&&!id.isEmpty()&&url.startsWith("https://"))items.add(new VodItem(title,id,url,license));
      }
    }catch(Exception ignored){}
    return items;
  }
  private String vodTitleIdentity(String title){
    if(title==null)return "";
    String key=title.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|（.*?）|\\[.*?\\]|【.*?】","");
    key=key.replaceAll("(19|20)\\d{2}","").replaceAll("(?:1080p|720p|4k|高清|超清|国语|粤语|中字)","");
    return key.replaceAll("[^\\p{L}\\p{N}]","");
  }
  private boolean addUniqueVodItem(ArrayList<VodItem> items,Set<String> ids,Set<String> titles,VodItem item){
    if(item==null||item.identifier==null||item.identifier.isEmpty()||!ids.add(item.identifier))return false;
    String titleKey=vodTitleIdentity(item.title);
    // Very short names are common labels rather than reliable film identities.
    if(titleKey.length()>=6&&!titles.add(titleKey))return false;
    items.add(item);
    if(titleKey.length()<6)titles.add(item.identifier);
    return true;
  }
  private void writeVodCache(String category,ArrayList<VodItem> items){
    try{
      org.json.JSONArray a=new org.json.JSONArray();
      for(VodItem item:items){
        org.json.JSONObject o=new org.json.JSONObject();o.put("title",item.title);o.put("id",item.identifier);o.put("url",item.url);o.put("license",item.license);a.put(o);
      }
      getSharedPreferences("mytv",0).edit().putString(vodCacheKey(category),a.toString()).putLong(vodCacheTimeKey(category),System.currentTimeMillis()).apply();
    }catch(Exception ignored){}
  }
  // Mouse-wheel events are often dispatched to the card/image under the pointer,
  // not to the GridView. Forward them so users can scroll without moving to the edge.
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
  private void scrollVodGridPage(GridView grid,int direction){
    if(grid==null||grid.getAdapter()==null||grid.getAdapter().getCount()==0||direction==0)return;
    final int count=grid.getAdapter().getCount();
    final int first=grid.getFirstVisiblePosition();
    final int last=grid.getLastVisiblePosition();
    if(direction>0&&last>=count-1)return;
    if(direction<0&&first<=0)return;
    final int columns=Math.max(1,grid.getNumColumns());
    final int firstRow=first/columns;
    final int lastRow=Math.max(firstRow,last/columns);
    final int visibleRows=Math.max(1,lastRow-firstRow+1);
    // Keep one row of context on screen while advancing by the actual visible rows.
    final int pageRows=Math.max(1,visibleRows-1);
    final int targetRow=direction>0?firstRow+pageRows:firstRow-pageRows;
    final int targetPosition=Math.max(0,Math.min(count-1,targetRow*columns));
    if(targetPosition==first)return;
    // Position-based paging is deterministic on Android 9 GridView; pixel scrolling
    // could stop early when only a partial row had been laid out by the device.
    grid.post(()->grid.smoothScrollToPositionFromTop(targetPosition,0,260));
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
      }
      old.setBackground(makeButtonBg(Color.rgb(25,39,59),old.hasFocus()));
      old.setScaleX(1f);old.setScaleY(1f);old.setElevation(old.hasFocus()?dp(4):0);
    }
  }
  private void showVodMouseHover(View card){
    if(card==null)return;
    if(hoveredVodCard!=card){
      clearVodMouseHover();
      hoveredVodCard=card;
    }
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
      holder.title.setText((favoriteIds.contains(item.identifier)?"★ ":"☆ ")+item.title);holder.meta.setText(item.identifier.startsWith("cctv:")?"央视网官方节目 · HLS":"开放授权 · 可尝试播放");
      card.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.poster.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.title.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.meta.setOnGenericMotionListener((v,event)->handleVodGridScroll(v,event));
      holder.poster.setImageBitmap(createVodFallbackPoster(item.title,item.identifier));
      loadVodPoster(holder.poster,item.identifier,item.title);
      // Mouse hover highlights exactly one card but never changes D-pad focus.
      // Explicit arrow pointer avoids an invisible/default cursor on Android TV.
      configureVodMouseTarget(card,holder.poster,holder.title,holder.meta);
      // Make every visible poster/text surface an explicit mouse click target.
      // Forward child clicks to the card so Android 9 TV mouse dispatch cannot
      // leave the poster visually hovered but functionally unclickable.
      holder.poster.setClickable(true);
      holder.title.setClickable(true);
      holder.meta.setClickable(true);
      View.OnClickListener openItem=v->card.performClick();
      holder.poster.setOnClickListener(openItem);
      holder.title.setOnClickListener(openItem);
      holder.meta.setOnClickListener(openItem);
      card.setOnClickListener(v->playVodInside(item));
      // Deterministic D-pad navigation: up/down preserve the current column.
      card.setOnKeyListener((v,keyCode,event)->{
        if(event.getAction()!=android.view.KeyEvent.ACTION_DOWN||!(parent instanceof GridView))return false;
        GridView owner=(GridView)parent;int current=owner.getPositionForView(card);
        if(current<0)return false;int target=current;int count=owner.getAdapter()==null?0:owner.getAdapter().getCount();int columns=Math.max(1,owner.getNumColumns());
        if(keyCode==android.view.KeyEvent.KEYCODE_DPAD_UP)target=current-columns;
        else if(keyCode==android.view.KeyEvent.KEYCODE_DPAD_DOWN)target=current+columns;
        else if(keyCode==android.view.KeyEvent.KEYCODE_DPAD_LEFT){if(current%columns==0)return false;target=current-1;}
        else if(keyCode==android.view.KeyEvent.KEYCODE_DPAD_RIGHT){if(current%columns==columns-1||current+1>=count)return false;target=current+1;}
        else return false;
        if(target<0||target>=count)return true;final int destination=target;owner.setSelection(destination);
        owner.post(()->{int childIndex=destination-owner.getFirstVisiblePosition();if(childIndex>=0&&childIndex<owner.getChildCount()){View next=owner.getChildAt(childIndex);if(next!=null)next.requestFocus();}else owner.smoothScrollToPosition(destination);});
        return true;
      });
      card.setOnLongClickListener(v->{toggleVodFavorite(item);boolean nowFavorite=isVodFavorite(item);if(nowFavorite)favoriteIds.add(item.identifier);else favoriteIds.remove(item.identifier);holder.title.setText((nowFavorite?"★ ":"☆ ")+item.title);toast(nowFavorite?"已加入我的收藏":"已取消收藏");return true;});
      return card;
    }
  }
  private void renderVodItems(String category,ArrayList<VodItem> items,TextView status,LinearLayout results,boolean cached){
    clearVodMouseHover();
    status.setText("「"+category+"」 · "+items.size()+" 部"+(cached?"（缓存秒开，正在后台检查更新）":"符合授权筛选的影片"));
    GridView grid=resetVodGrid(results,4);
    int oldFirst=grid.getFirstVisiblePosition();
    View firstChild=grid.getChildAt(0);
    int oldTop=firstChild==null?0:firstChild.getTop();
    boolean preservePosition=category.equals(currentVodRenderedCategory)&&grid.getAdapter()!=null&&grid.getAdapter().getCount()>0;
    grid.setAdapter(new VodGridAdapter(items));
    if(preservePosition&&items!=null&&!items.isEmpty()){
      final int restorePosition=Math.min(oldFirst,items.size()-1);
      final int restoreTop=oldTop;
      grid.post(()->grid.setSelectionFromTop(restorePosition,restoreTop));
    }else grid.post(()->grid.setSelection(0));
    currentVodRenderedCategory=category;
  }
  private static final String VOD_FAVORITES_KEY="vod_favorites_v1";
  private ArrayList<VodItem> readVodFavorites(){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      org.json.JSONArray a=new org.json.JSONArray(getSharedPreferences("mytv",0).getString(VOD_FAVORITES_KEY,"[]"));
      for(int i=0;i<a.length();i++){org.json.JSONObject o=a.optJSONObject(i);if(o==null)continue;String title=o.optString("title",""),id=o.optString("id",""),url=o.optString("url",""),license=o.optString("license","");if(!id.isEmpty()&&url.startsWith("https://"))items.add(new VodItem(title,id,url,license));}
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
      for(VodItem saved:items){if(item.identifier.equals(saved.identifier)){removed=true;continue;}org.json.JSONObject o=new org.json.JSONObject();o.put("title",saved.title);o.put("id",saved.identifier);o.put("url",saved.url);o.put("license",saved.license);out.put(o);}
      if(!removed){org.json.JSONObject o=new org.json.JSONObject();o.put("title",item.title);o.put("id",item.identifier);o.put("url",item.url);o.put("license",item.license);out.put(o);}
      getSharedPreferences("mytv",0).edit().putString(VOD_FAVORITES_KEY,out.toString()).apply();
    }catch(Exception ignored){}
  }
  private void loadVodFavorites(LinearLayout panel){
    if(panel==null||panel!=currentVodPanel||currentVodStatus==null||currentVodResults==null)return;
    TextView status=currentVodStatus;LinearLayout results=currentVodResults;
    ArrayList<VodItem> items=readVodFavorites();renderVodItems("我的收藏",items,status,results,false);
    status.setText(items.isEmpty()?"还没有收藏影片。点播列表中长按影片即可收藏。":"我的收藏 · "+items.size()+" 部（长按可取消收藏）");
  }
  private static final String VOD_HISTORY_KEY="vod_history_v1";
  private ArrayList<org.json.JSONObject> readVodHistory(){
    ArrayList<org.json.JSONObject> list=new ArrayList<>();
    try{org.json.JSONArray a=new org.json.JSONArray(getSharedPreferences("mytv",0).getString(VOD_HISTORY_KEY,"[]"));for(int i=0;i<a.length();i++){org.json.JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("url","").isEmpty())list.add(o);}}catch(Exception ignored){}
    return list;
  }
  private void saveVodHistory(VodItem item,long position,long duration){
    if(item==null)return;
    try{
      ArrayList<org.json.JSONObject> old=readVodHistory();org.json.JSONArray out=new org.json.JSONArray();
      org.json.JSONObject entry=new org.json.JSONObject();entry.put("title",item.title);entry.put("id",item.identifier);entry.put("url",item.url);entry.put("license",item.license);entry.put("position",Math.max(0,position));entry.put("duration",Math.max(0,duration));entry.put("watchedAt",System.currentTimeMillis());out.put(entry);
      for(org.json.JSONObject o:old){if(!item.identifier.equals(o.optString("id","")))out.put(o);if(out.length()>=100)break;}
      getSharedPreferences("mytv",0).edit().putString(VOD_HISTORY_KEY,out.toString()).apply();
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
      String title=o.optString("title","未命名影片"),id=o.optString("id",""),url=o.optString("url",""),license=o.optString("license","");
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
      row.setOnClickListener(v->playVodInside(new VodItem(title,id,url,license)));
      row.setOnLongClickListener(v->{new AlertDialog.Builder(MainActivity.this).setTitle("删除播放记录").setMessage(title).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{ArrayList<org.json.JSONObject> keep=readVodHistory();org.json.JSONArray a=new org.json.JSONArray();for(org.json.JSONObject x:keep)if(!id.equals(x.optString("id","")))a.put(x);getSharedPreferences("mytv",0).edit().putString(VOD_HISTORY_KEY,a.toString()).apply();loadVodHistory(status,results);}).show();return true;});
      return row;
    }
  }
  private void loadVodHistory(TextView status,LinearLayout results){
    ArrayList<org.json.JSONObject> history=readVodHistory();results.removeAllViews();
    LinearLayout tools=new LinearLayout(this);tools.setOrientation(0);
    Button clear=new Button(this);clear.setText("清空播放记录");clear.setAllCaps(false);styleTvButton(clear,Color.rgb(125,48,48));tools.addView(clear,new LinearLayout.LayoutParams(-2,dp(44)));
    clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空播放记录？").setMessage("将删除所有点播记录和续播进度。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{getSharedPreferences("mytv",0).edit().remove(VOD_HISTORY_KEY).apply();loadVodHistory(status,results);}).show());
    results.addView(tools);
    status.setText(history.isEmpty()?"还没有点播记录。观看过的影片会自动保存在这里。":"最近观看 · "+history.size()+" 部（点击继续观看，长按删除）");
    GridView grid=ensureVodGrid(results,1);grid.setAdapter(new VodHistoryAdapter(history,status,results));grid.setSelection(0);
  }
  private String formatVodTime(long ms){
    long seconds=Math.max(0,ms/1000),hours=seconds/3600,minutes=(seconds%3600)/60,remain=seconds%60;
    return hours>0?String.format(Locale.US,"%d:%02d:%02d",hours,minutes,remain):String.format(Locale.US,"%02d:%02d",minutes,remain);
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
    String channelId=null;
    if("中国纪录片".equals(category)||"中文纪录片".equals(category))
      channelId="CHAL1460955924871139";
    else if("中国动画".equals(category)||"中文动画".equals(category))
      channelId="CHAL1460955899450127";
    else if("多源精选".equals(category)||"精选电影".equals(category))
      channelId="CHAL1460955924871139";
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
        if(!lowerName.endsWith(".mp4")||!(format.contains("mpeg4")||format.contains("h.264")||format.contains("mpeg")))continue;
        int width=file.optInt("width",0),height=file.optInt("height",0);
        boolean verified1080=width>=1920&&height>=1080;
        boolean explicit1080=lowerName.matches(".*(?:1080p|1080i|1920x1080).*");
        if(require1080p&&!verified1080&&!explicit1080)continue;
        // Prefer a broadly compatible 1080p MP4 when available; do not simply
        // take the first file in the archive (often a tiny preview or low-res copy).
        int qualityClass=(verified1080||explicit1080)?3:(height>=720||lowerName.matches(".*(?:720p|1280x720).*"))?2:1;
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
  private ArrayList<VodItem> fetchLocPublicDomainVod(String query){
    ArrayList<VodItem> items=new ArrayList<>();
    try{
      String api="https://www.loc.gov/film-and-videos/?q="+URLEncoder.encode(query,"UTF-8")+"&fo=json&c=18&sp=1";
      org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
      org.json.JSONArray docs=root.optJSONArray("results");if(docs==null)return items;
      ExecutorCompletionService<VodItem> completion=new ExecutorCompletionService<>(vodMetadataWorker);
      ArrayList<Future<VodItem>> tasks=new ArrayList<>();
      int submitted=0;
      for(int i=0;i<docs.length()&&submitted<12;i++){
        org.json.JSONObject doc=docs.optJSONObject(i);if(doc==null)continue;
        String id=doc.optString("id","");if(!id.startsWith("http"))continue;
        String title=doc.optString("title","公开影像");
        tasks.add(completion.submit(()->fetchLocVideoItem(id,title)));submitted++;
      }
      long deadline=android.os.SystemClock.elapsedRealtime()+7000L;
      while(items.size()<8&&submitted>0&&android.os.SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted()){
        Future<VodItem> f=completion.poll(350,TimeUnit.MILLISECONDS);
        if(f==null)continue;
        submitted--;
        try{VodItem v=f.get();if(v!=null)items.add(v);}catch(Exception ignored){}
      }
      for(Future<VodItem> task:tasks)if(!task.isDone())task.cancel(true);
    }catch(Exception ignored){}
    return items;
  }

  private void loadVodCatalogue(String category,LinearLayout panel,TextView status,LinearLayout results){
    loadVodCatalogue(category,panel,status,results,false);
  }
  private void loadVodCatalogue(String category,LinearLayout panel,TextView status,LinearLayout results,boolean forceRefresh){
    Future<?> previous=activeVodCatalogueTask;if(previous!=null)previous.cancel(true);
    final int request=++vodRequestId;
    ArrayList<VodItem> cached=readVodCache(category);
    if(!cached.isEmpty())renderVodItems(category,cached,status,results,true);
    else{status.setText("正在启动多页点播检索「"+category+"」…");GridView grid=resetVodGrid(results,4);grid.setAdapter(new VodGridAdapter(new ArrayList<>()));}
    // Show fresh cache immediately. A manual refresh is still possible by reopening
    // the category after its cache expires; stale cache never blocks a new harvest.
    if(!forceRefresh&&!cached.isEmpty()&&isVodCacheFresh(category)){
      status.setText("「"+category+"」 · 已缓存 "+cached.size()+" 部，6小时内自动复用");
      return;
    }
    activeVodCatalogueTask=vodWorker.submit(()->{
      ArrayList<VodItem> found=new ArrayList<>();String error=null;
      ArrayList<Future<VodItem>> pendingVodChecks=new ArrayList<>();
      ArrayList<Future<org.json.JSONArray>> pageTasks=new ArrayList<>();
      ArrayList<Future<ArrayList<VodItem>>> sourceFutures=new ArrayList<>();
      HashSet<String> submittedIds=new HashSet<>(),foundIds=new HashSet<>(),foundTitleKeys=new HashSet<>();
      try{

        // Prefer official CCTV catalogue for Chinese documentary/animation categories.
        if(Arrays.asList("中国纪录片","中国历史影像","中国经典影像","中国动画","中文动画").contains(category)){
          ArrayList<VodItem> cctvItems=fetchCctvVod(category);
          for(VodItem item:cctvItems)if(found.size()<80)addUniqueVodItem(found,foundIds,foundTitleKeys,item);
        }

        boolean titleSearch=category.startsWith("搜索：");
        String searchTerm=titleSearch?category.substring("搜索：".length()).trim():"";
        String q="mediatype:movies";
        if(titleSearch){
          String term=searchTerm.replace("\\"," ").replace("\""," ").replaceAll("[\\r\\n]+"," ").trim();
          String phrase=term.replaceAll("\\s+"," ");
          if(phrase.isEmpty())phrase="film";
          q="mediatype:movies AND (title:\""+phrase+"\" OR description:\""+phrase+"\" OR subject:\""+phrase+"\" OR creator:\""+phrase+"\")";
        }
        else if("电影".equals(category))q="mediatype:movies AND (subject:feature film OR subject:public domain OR collection:feature_films)";
        else if("公开电视剧".equals(category))q="mediatype:movies AND (subject:television OR title:television series OR title:TV series OR collection:television)";
        else if("公开综艺".equals(category))q="mediatype:movies AND (subject:variety show OR title:variety show OR title:talk show OR subject:talk show)";
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
        else if("动画".equals(category))q+=" AND (subject:animation OR subject:cartoons)";
        else if("喜剧".equals(category))q+=" AND subject:comedy";
        else if("科幻".equals(category))q+=" AND (subject:science_fiction OR subject:science-fiction)";
        else if("剧情".equals(category))q+=" AND subject:drama";
        else if("欧美经典".equals(category))q+=" AND (language:English OR language:eng) AND (collection:feature_films OR subject:classic)";
        else if("默片经典".equals(category))q+=" AND (subject:silent film OR title:silent)";
        else if("短片".equals(category))q+=" AND (subject:short film OR subject:shorts)";
        else if(!titleSearch&&!Arrays.asList("世界公版电影","精选电影","多源精选","电影","公开电视剧","公开综艺","纪录片·历史","纪录片·自然","纪录片·科学","中国电影","中国老电影","华语经典","中国经典影像","中国历史影像","中国动画","中文动画","中国纪录片","中文纪录片","戏曲","1080P高清电影","纪录片","科学纪录片","历史纪录片","自然纪录片","NASA影像","战争片","恐怖片","动作片","动画","喜剧","科幻","剧情","欧美经典","默片经典","短片","国产短片").contains(category))q+=" AND title:("+category.replace(" "," AND ")+")";

        ArrayList<String> queries=new ArrayList<>();queries.add(q);
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

        ExecutorCompletionService<VodItem> completion=new ExecutorCompletionService<>(vodMetadataWorker);
        ExecutorCompletionService<org.json.JSONArray> pageCompletion=new ExecutorCompletionService<>(vodSourceWorker);
        int submitted=0,completed=0;
        // Start independent catalogue pages together. The slowest page no longer blocks
        // the next query; only four HTTP page requests run at once on low-memory TV boxes.
        for(String query:queries){
          for(int page=1;page<=3;page++){
            if(Thread.currentThread().isInterrupted())break;
            final String pageQuery=query;final int pageNumber=page;
            pageTasks.add(pageCompletion.submit(()->{
              String api="https://archive.org/advancedsearch.php?q="+URLEncoder.encode(pageQuery,"UTF-8")+"&fl[]=identifier&fl[]=title&rows=100&page="+pageNumber+"&output=json&sort[]=downloads+desc";
              org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
              return root.getJSONObject("response").getJSONArray("docs");
            }));
          }
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
                  writeVodCache(category,partialBatch);
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
              writeVodCache(category,firstBatch);
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
        CompletionService<ArrayList<VodItem>> sourceCompletion=new ExecutorCompletionService<>(vodSourceWorker);
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
        if(titleSearch)commonsQuery=searchTerm;
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
          writeVodCache(category,result);
          renderVodItems(category,result,status,results,false);
          status.setText("「"+category+"」 · 多目录聚合 "+result.size()+" 部（含公开馆藏；首次播放会验证链接）");
        }else if(cached.isEmpty()){
          status.setText(err==null?"暂时没有检索到符合授权条件且包含 MP4 文件的影片。可稍后重试。":"点播目录暂时无法连接："+err);
        }else{
          status.setText("显示上次缓存的 "+cached.size()+" 部影片；本次多源检索暂不可用。");
        }
      });
    });
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
    purgeCancelledTasks(vodMetadataWorker);
    purgeCancelledTasks(vodSourceWorker);
  }
  private String httpGetFast(String address)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setConnectTimeout(2500);c.setReadTimeout(3500);c.setRequestProperty("User-Agent","MyTV/2.0 Android TV");
    c.setRequestProperty("Accept","application/json,*/*");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n,total=0;final int maxBytes=2*1024*1024;
      while((n=in.read(buf))!=-1){total+=n;if(total>maxBytes)throw new IOException("点播目录响应超过2MB，已停止读取以保护设备内存");out.write(buf,0,n);}
      return out.toString("UTF-8");
    }finally{c.disconnect();}
  }
  private Bitmap createVodFallbackPoster(String title,String identifier){
    int w=360,h=240;Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565);Canvas c=new Canvas(b);
    int seed=identifier==null?0:identifier.hashCode();int[] colors={
      Color.rgb(20,55,91),Color.rgb(72,42,84),Color.rgb(25,78,75),Color.rgb(86,54,32),Color.rgb(38,48,88)};
    int base=colors[Math.floorMod(seed,colors.length)];
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
        byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
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
  private void loadVodPoster(ImageView target,String identifier,String title){
    target.setTag(identifier);
    Bitmap cached=vodPosterCache.get(identifier);
    if(cached!=null){target.setImageBitmap(cached);return;}
    if(!vodPosterRequests.add(identifier))return;
    try{posterWorker.execute(()->{
      Bitmap bitmap=null;HttpURLConnection connection=null;
      try{
        String imageUrl="";
        if(identifier.startsWith("commons:")||identifier.startsWith("commons-search:"))imageUrl=fetchCommonsPosterUrl(identifier);
        else if(!identifier.startsWith("loc:")&&!identifier.startsWith("cctv:"))
          imageUrl="https://archive.org/services/img/"+URLEncoder.encode(identifier,"UTF-8");
        if(!imageUrl.isEmpty()){
          connection=(HttpURLConnection)new URL(imageUrl).openConnection();
          connection.setConnectTimeout(2500);connection.setReadTimeout(3500);connection.setInstanceFollowRedirects(true);
          connection.setRequestProperty("User-Agent","MyTV/1.81 Android TV");
          // Posters are displayed in a small TV card. Decode near display size
          // instead of allocating a full-resolution bitmap for every thumbnail.
          try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n,total=0;final int maxImageBytes=4*1024*1024;
            while((n=in.read(buffer))!=-1&&total<maxImageBytes){
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
      }catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();vodPosterRequests.remove(identifier);}
      final Bitmap image=bitmap;
      uiHandler.post(()->{
        if(identifier.equals(target.getTag())){
          if(image!=null){vodPosterCache.put(identifier,image);target.setImageBitmap(image);}
          else target.setImageBitmap(createVodFallbackPoster(title,identifier));
        }
      });
    });}catch(RejectedExecutionException ignored){vodPosterRequests.remove(identifier);}
  }
  private String encodeArchivePath(String path){
    try{String[] parts=path.split("/");StringBuilder out=new StringBuilder();for(String p:parts){if(out.length()>0)out.append("/");out.append(URLEncoder.encode(p,"UTF-8").replace("+","%20"));}return out.toString();}catch(Exception e){return path;}
  }
  private String httpGet(String address)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("User-Agent","MyTV/1.19 (Android TV)");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toString("UTF-8");}finally{c.disconnect();}
  }
  private void playVodInside(VodItem item){
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
    activeVodItem=item;saveVodHistory(item,startPosition,0);
    Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);vodPlayerDialog=d;
    final SeekBar[] vodSeekHolder=new SeekBar[1];
    final LinearLayout[] vodControlsHolder=new LinearLayout[1];
    final Runnable[] hideVodSeekTask=new Runnable[1];
    final View[] vodTopControls=new View[4];
    FrameLayout stage=new FrameLayout(this){
      private void revealVodSeekBar(){
        boolean controlsWereHidden=vodControlsHolder[0]!=null&&vodControlsHolder[0].getVisibility()!=View.VISIBLE;
        if(vodControlsHolder[0]!=null){vodControlsHolder[0].setVisibility(View.VISIBLE);vodControlsHolder[0].bringToFront();}
        if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.VISIBLE);
        for(View top:vodTopControls)if(top!=null)top.setVisibility(View.VISIBLE);
        if(hideVodSeekTask[0]!=null)removeCallbacks(hideVodSeekTask[0]);
        hideVodSeekTask[0]=()->{
          if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);
          if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);
          for(View top:vodTopControls)if(top!=null)top.setVisibility(View.GONE);
          this.requestFocus();
        };
        postDelayed(hideVodSeekTask[0],3500);
        if(controlsWereHidden&&vodSeekHolder[0]!=null)vodSeekHolder[0].requestFocus();
      }
      @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_MENU||event.getKeyCode()==KeyEvent.KEYCODE_SETTINGS){
          if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
            boolean visible=vodControlsHolder[0]!=null&&vodControlsHolder[0].getVisibility()==View.VISIBLE;
            if(visible){
              if(hideVodSeekTask[0]!=null)removeCallbacks(hideVodSeekTask[0]);
              if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);
              if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);
              for(View top:vodTopControls)if(top!=null)top.setVisibility(View.GONE);
            }else revealVodSeekBar();
          }
          return true;
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
          View focused=findFocus();
          boolean topFocused=false;
          int topIndex=-1;
          for(int i:new int[]{3,1,2})if(vodTopControls[i]!=null&&vodTopControls[i]==focused){topFocused=true;topIndex=i;break;}
          if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP&&focused==vodSeekHolder[0]&&vodTopControls[3]!=null){
            revealVodSeekBar();vodTopControls[3].requestFocus();return true;
          }
          if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN&&topFocused&&vodSeekHolder[0]!=null){
            revealVodSeekBar();vodSeekHolder[0].requestFocus();return true;
          }
          if(topFocused&&(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT)){
            int[] order={3,1,2};int pos=0;for(int i=0;i<order.length;i++)if(order[i]==topIndex)pos=i;
            int next=pos+(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);
            if(next>=0&&next<order.length&&vodTopControls[order[next]]!=null){revealVodSeekBar();vodTopControls[order[next]].requestFocus();return true;}
          }
        }
        if(event.getAction()==KeyEvent.ACTION_DOWN)revealVodSeekBar();
        return super.dispatchKeyEvent(event);
      }
      private float videoTouchX,videoTouchY;
      private boolean videoTapCandidate=false;
      private void hideVodControlsNow(){
        if(hideVodSeekTask[0]!=null)removeCallbacks(hideVodSeekTask[0]);
        if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);
        if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);
        for(View top:vodTopControls)if(top!=null)top.setVisibility(View.GONE);
      }
      private void toggleVodControls(){
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
          if(videoTapCandidate)return true;
        }else if(action==android.view.MotionEvent.ACTION_UP&&videoTapCandidate){
          float dx=Math.abs(event.getX()-videoTouchX),dy=Math.abs(event.getY()-videoTouchY);
          videoTapCandidate=false;
          if(dx<dp(28)&&dy<dp(28))toggleVodControls();
          return true;
        }else if(action==android.view.MotionEvent.ACTION_CANCEL&&videoTapCandidate){
          videoTapCandidate=false;
          return true;
        }
        return super.dispatchTouchEvent(event);
      }
    };stage.setBackgroundColor(Color.BLACK);stage.setFocusable(true);stage.setFocusableInTouchMode(true);stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    PlayerView pv=new PlayerView(this);pv.setUseController(false);pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);pv.setFocusable(false);pv.setFocusableInTouchMode(false);int vodAspect=getSharedPreferences("mytv",0).getInt("vod_aspect_mode",0);pv.setResizeMode(vodAspect==1?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM:vodAspect==2?androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL:androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
    stage.addView(pv,new FrameLayout.LayoutParams(-1,-1));
    // End-of-film overlay: keep the last video frame visible and offer clear
    // remote-friendly actions instead of leaving the viewer on a black screen.
    LinearLayout endedPanel=new LinearLayout(this);endedPanel.setOrientation(1);endedPanel.setGravity(Gravity.CENTER);
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
    replayEnded.setOnClickListener(v->{endedPanel.setVisibility(View.GONE);if(vodPlayer!=null){vodPlayer.seekTo(0);vodPlayer.play();saveVodHistory(item,0,vodPlayer.getDuration());}stage.requestFocus();});
    nextEnded.setOnClickListener(v->{VodItem next=findNextVodItem(item);if(next==null){toast("当前列表没有下一部影片");return;}d.dismiss();stage.postDelayed(()->startVodPlayback(next,0),250);});
    backEnded.setOnClickListener(v->d.dismiss());
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(10),dp(4),dp(10),dp(4));controls.setBackgroundColor(0xDD101820);
    TextView playPauseIndicator=new TextView(this);playPauseIndicator.setText("Ⅱ");playPauseIndicator.setTextColor(Color.WHITE);playPauseIndicator.setTextSize(15);playPauseIndicator.setGravity(Gravity.CENTER);playPauseIndicator.setPadding(0,0,0,0);android.graphics.drawable.GradientDrawable playPauseCircle=new android.graphics.drawable.GradientDrawable();playPauseCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);playPauseCircle.setColor(0xCC263746);playPauseCircle.setStroke(dp(1),0xFF8FA7BA);playPauseIndicator.setBackground(playPauseCircle);playPauseIndicator.setFocusable(false);playPauseIndicator.setClickable(true);playPauseIndicator.setOnClickListener(v->{if(vodPlayer!=null){if(vodPlayer.isPlaying())vodPlayer.pause();else vodPlayer.play();playPauseIndicator.setText(vodPlayer.isPlaying()?"Ⅱ":"▶");}});
    vodPositionLabel=new TextView(this);vodPositionLabel.setText("00:00");vodPositionLabel.setTextColor(Color.WHITE);vodPositionLabel.setTextSize(12);vodPositionLabel.setGravity(Gravity.CENTER);vodPositionLabel.setPadding(dp(6),0,dp(6),0);
    vodSeekBar=new SeekBar(this){
      private int remoteDirection=0;
      private Runnable remoteRepeat;
      private void seekBy(long delta){
        if(vodPlayer==null)return;
        long duration=vodPlayer.getDuration();
        if(duration<=0)return;
        long target=Math.max(0L,Math.min(duration,vodPlayer.getCurrentPosition()+delta));
        vodPlayer.seekTo(target);
        setProgress((int)Math.max(0,Math.min(1000,target*1000L/duration)));
        if(vodPositionLabel!=null)vodPositionLabel.setText(formatVodTime(target));
      }
      @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER||keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER){if(event.getRepeatCount()==0&&vodPlayer!=null){if(vodPlayer.isPlaying())vodPlayer.pause();else vodPlayer.play();playPauseIndicator.setText(vodPlayer.isPlaying()?"Ⅱ":"▶");}return true;}
        int direction=keyCode==KeyEvent.KEYCODE_DPAD_LEFT?-1:keyCode==KeyEvent.KEYCODE_DPAD_RIGHT?1:0;
        if(direction==0)return super.onKeyDown(keyCode,event);
        if(event.getRepeatCount()==0){
          remoteDirection=direction;
          seekBy(direction*5000L);
          if(remoteRepeat!=null)removeCallbacks(remoteRepeat);
          remoteRepeat=()->{
            if(remoteDirection!=0){
              seekBy(remoteDirection*15000L);
              postDelayed(remoteRepeat,220L);
            }
          };
          postDelayed(remoteRepeat,450L);
        }
        return true;
      }
      @Override public boolean onKeyUp(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){
          remoteDirection=0;
          if(remoteRepeat!=null){removeCallbacks(remoteRepeat);remoteRepeat=null;}
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
    hideVodSeekTask[0]=()->{if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);for(View top:vodTopControls)if(top!=null)top.setVisibility(View.GONE);stage.requestFocus();};stage.postDelayed(hideVodSeekTask[0],2500);
    vodSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){if(vodPlayer!=null&&vodPlayer.getDuration()>0)vodPlayer.seekTo(vodPlayer.getDuration()*bar.getProgress()/1000L);}
      public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser&&vodPlayer!=null&&vodPlayer.getDuration()>0)vodPositionLabel.setText(formatVodTime(vodPlayer.getDuration()*progress/1000L));}
    });
    vodUiUpdater=new Runnable(){public void run(){
      if(vodPlayer==null||vodPlayerDialog!=d||!d.isShowing())return;
      long duration=vodPlayer.getDuration(),position=vodPlayer.getCurrentPosition();
      if(duration>0){vodDurationLabel.setText(formatVodTime(duration));if(!vodSeekBar.isPressed())vodSeekBar.setProgress((int)Math.max(0,Math.min(1000,position*1000L/duration)));}else{vodDurationLabel.setText("--:--");vodSeekBar.setProgress(0);}
      if(!vodSeekBar.isPressed())vodPositionLabel.setText(formatVodTime(position));
      playPauseIndicator.setText(vodPlayer.isPlaying()?"Ⅱ":"▶");
      uiHandler.postDelayed(this,500);
    }};
    TextView heading=new TextView(this);heading.setText(item.title+" · 开放授权影片");heading.setTextColor(Color.WHITE);heading.setTextSize(15);heading.setPadding(dp(14),dp(10),dp(14),dp(10));heading.setBackgroundColor(0x99000000);
    FrameLayout.LayoutParams headingParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);stage.addView(heading,headingParams);vodTopControls[0]=heading;
    Button favorite=new Button(this);favorite.setText(isVodFavorite(item)?"★ 已收藏":"☆ 收藏");favorite.setAllCaps(false);favorite.setTextSize(14);favorite.setFocusable(true);favorite.setFocusableInTouchMode(true);styleVodTopControl(favorite);favorite.setOnClickListener(v->{toggleVodFavorite(item);favorite.setText(isVodFavorite(item)?"★ 已收藏":"☆ 收藏");toast(isVodFavorite(item)?"已加入我的收藏":"已取消收藏");});FrameLayout.LayoutParams favoriteParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);favoriteParams.topMargin=dp(4);favoriteParams.rightMargin=dp(116);stage.addView(favorite,favoriteParams);vodTopControls[1]=favorite;
    Button back=new Button(this);back.setText("返回目录");back.setAllCaps(false);back.setTextSize(14);back.setFocusable(true);back.setFocusableInTouchMode(true);styleVodTopControl(back);back.setOnClickListener(v->d.dismiss());FrameLayout.LayoutParams backParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);backParams.topMargin=dp(4);backParams.rightMargin=dp(8);stage.addView(back,backParams);vodTopControls[2]=back;
     Button aspect=new Button(this);aspect.setText("画面比例");aspect.setAllCaps(false);aspect.setTextSize(14);aspect.setFocusable(true);aspect.setFocusableInTouchMode(true);styleVodTopControl(aspect);aspect.setOnClickListener(v->showAspectRatioDialog(pv,"vod_aspect_mode"));FrameLayout.LayoutParams aspectParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);aspectParams.topMargin=dp(4);aspectParams.rightMargin=dp(220);stage.addView(aspect,aspectParams);vodTopControls[3]=aspect;
    d.setOnKeyListener((dialog,keyCode,event)->{if(keyCode==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP){d.dismiss();}return true;}return false;});
     d.setContentView(stage);d.setOnDismissListener(x->{if(hideVodSeekTask[0]!=null)stage.removeCallbacks(hideVodSeekTask[0]);vodSeekHolder[0]=null;vodControlsHolder[0]=null;if(vodProgressSaver!=null){uiHandler.removeCallbacks(vodProgressSaver);vodProgressSaver=null;}if(vodUiUpdater!=null){uiHandler.removeCallbacks(vodUiUpdater);vodUiUpdater=null;}vodSeekBar=null;vodPositionLabel=null;vodDurationLabel=null;if(vodPlayer!=null){if(activeVodItem!=null)saveVodHistory(activeVodItem,vodPlayer.getCurrentPosition(),vodPlayer.getDuration());pv.setKeepScreenOn(false);pv.setPlayer(null);vodPlayer.pause();vodPlayer.stop();vodPlayer.clearMediaItems();vodPlayer.release();vodPlayer=null;}activeVodItem=null;getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(vodPlayerDialog==d)vodPlayerDialog=null;});
    try{
      vodPlayer=PlaybackPlayerFactory.createVodPlayer(this,isDecoderFallbackEnabled());
      pv.setPlayer(vodPlayer);pv.setKeepScreenOn(true);MediaItem media=MediaItem.fromUri(item.url);vodPlayer.setMediaItem(media);vodPlayer.prepare();if(startPosition>0)vodPlayer.seekTo(startPosition);vodPlayer.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED&&activeVodItem!=null){saveVodHistory(activeVodItem,0,vodPlayer==null?0:vodPlayer.getDuration());endedPanel.setVisibility(View.VISIBLE);endedPanel.bringToFront();if(hideVodSeekTask[0]!=null)stage.removeCallbacks(hideVodSeekTask[0]);if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);for(View top:vodTopControls)if(top!=null)top.setVisibility(View.GONE);replayEnded.requestFocus();}}});vodPlayer.play();vodProgressSaver=new Runnable(){@Override public void run(){if(vodPlayer!=null&&activeVodItem!=null){saveVodHistory(activeVodItem,vodPlayer.getCurrentPosition(),vodPlayer.getDuration());uiHandler.postDelayed(this,5000);}}};uiHandler.postDelayed(vodProgressSaver,5000);
      getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      d.show();d.getWindow().setLayout(-1,-1);stage.post(()->{controls.setVisibility(View.VISIBLE);controls.bringToFront();vodSeekBar.requestFocus();});d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);d.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);uiHandler.post(vodUiUpdater);
    }catch(Exception e){toast("影片无法播放："+e.getMessage());d.dismiss();}
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
    android.widget.ListView listView=new android.widget.ListView(this);
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
            if(rowPosition>=0){activeChoice[0]=rowPosition;listView.setSelection(rowPosition);}
            GradientDrawable bg=new GradientDrawable();bg.setColor(hoverColor);bg.setCornerRadius(dp(6));
            bg.setStroke(dp(2),Color.rgb(125,205,255));v.setBackground(bg);
            return true;
          }else if(act==android.view.MotionEvent.ACTION_HOVER_EXIT){
            v.setBackground(makeButtonBg(normal,rowPosition==activeChoice[0]));
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
        for(int i=0;i<listView.getChildCount();i++){
          View child=listView.getChildAt(i);
          int childPosition=listView.getFirstVisiblePosition()+i;
          if(childPosition!=position)child.setBackground(makeButtonBg(normal,false));
          else child.setBackground(makeButtonBg(normal,true));
        }
      }
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
    });
    listView.setOnHoverListener((v,event)->{
      int act=event.getActionMasked();
      if(act==android.view.MotionEvent.ACTION_HOVER_MOVE||act==android.view.MotionEvent.ACTION_HOVER_ENTER){
        int position=listView.pointToPosition((int)event.getX(),(int)event.getY());
        if(position>=0){
          activeChoice[0]=position;listView.setSelection(position);
          for(int i=0;i<listView.getChildCount();i++){
            View child=listView.getChildAt(i);
            int childPosition=listView.getFirstVisiblePosition()+i;
            if(childPosition==position){
              GradientDrawable bg=new GradientDrawable();bg.setColor(hoverColor);bg.setCornerRadius(dp(6));
              bg.setStroke(dp(2),Color.rgb(125,205,255));child.setBackground(bg);
            }else child.setBackground(makeButtonBg(normal,childPosition==selectedIndex));
          }
        }
      }
      return true;
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
  private String[] citiesForProvince(String p){
    switch(p){
      case "北京":return new String[]{"北京"};
      case "天津":return new String[]{"天津"};
      case "河北":return new String[]{"石家庄","唐山","秦皇岛","邯郸","邢台","保定","张家口","承德","沧州","廊坊","衡水"};
      case "山西":return new String[]{"太原","大同","朔州","忻州","阳泉","长治","晋城","晋中","运城","临汾","吕梁"};
      case "内蒙古":return new String[]{"呼和浩特","包头","乌海","赤峰","通辽","鄂尔多斯","呼伦贝尔","巴彦淖尔","乌兰察布","兴安","锡林郭勒","阿拉善"};
      case "辽宁":return new String[]{"沈阳","大连","鞍山","抚顺","本溪","丹东","锦州","营口","阜新","辽阳","盘锦","铁岭","朝阳","葫芦岛"};
      case "吉林":return new String[]{"长春","吉林市","四平","辽源","通化","白山","松原","白城","延边"};
      case "黑龙江":return new String[]{"哈尔滨","齐齐哈尔","牡丹江","佳木斯","大庆","鸡西","鹤岗","双鸭山","伊春","七台河","黑河","绥化","大兴安岭"};
      case "上海":return new String[]{"上海"};
      case "江苏":return new String[]{"南京","苏州","无锡","常州","南通","镇江","扬州","淮安","连云港","盐城","宿迁","泰州","徐州"};
      case "浙江":return new String[]{"杭州","宁波","温州","嘉兴","湖州","绍兴","金华","衢州","舟山","台州","丽水"};
      case "安徽":return new String[]{"合肥","芜湖","蚌埠","淮南","马鞍山","淮北","铜陵","安庆","黄山","滁州","阜阳","宿州","六安","亳州","池州","宣城"};
      case "福建":return new String[]{"福州","厦门","莆田","三明","泉州","漳州","南平","龙岩","宁德"};
      case "江西":return new String[]{"南昌","景德镇","萍乡","九江","新余","鹰潭","赣州","吉安","宜春","抚州","上饶"};
      case "山东":return new String[]{"济南","青岛","淄博","枣庄","东营","烟台","潍坊","济宁","泰安","威海","日照","临沂","德州","聊城","滨州","菏泽"};
      case "河南":return new String[]{"郑州","开封","洛阳","平顶山","安阳","鹤壁","新乡","焦作","濮阳","许昌","漯河","三门峡","南阳","商丘","信阳","周口","驻马店"};
      case "湖北":return new String[]{"武汉","黄石","十堰","宜昌","襄阳","鄂州","荆门","孝感","荆州","黄冈","咸宁","随州","恩施","仙桃","潜江","天门"};
      case "湖南":return new String[]{"长沙","株洲","湘潭","衡阳","邵阳","岳阳","常德","张家界","益阳","郴州","永州","怀化","娄底","湘西"};
      case "广东":return new String[]{"广州","深圳","珠海","汕头","佛山","韶关","湛江","肇庆","江门","茂名","惠州","梅州","汕尾","河源","阳江","清远","东莞","中山","潮州","揭阳","云浮"};
      case "广西":return new String[]{"南宁","柳州","桂林","梧州","北海","防城港","钦州","贵港","玉林","百色","贺州","河池","来宾","崇左"};
      case "海南":return new String[]{"海口","三亚","三沙","儋州"};
      case "重庆":return new String[]{"重庆"};
      case "四川":return new String[]{"成都","自贡","攀枝花","泸州","德阳","绵阳","广元","遂宁","内江","乐山","南充","眉山","宜宾","广安","达州","雅安","巴中","资阳","阿坝","甘孜","凉山"};
      case "贵州":return new String[]{"贵阳","六盘水","遵义","安顺","毕节","铜仁","黔西南","黔东南","黔南"};
      case "云南":return new String[]{"昆明","曲靖","玉溪","保山","昭通","丽江","普洱","临沧","楚雄","红河","文山","西双版纳","大理","德宏","怒江","迪庆"};
      case "西藏":return new String[]{"拉萨","日喀则","昌都","林芝","山南","那曲","阿里"};
      case "陕西":return new String[]{"西安","铜川","宝鸡","咸阳","渭南","延安","汉中","榆林","安康","商洛"};
      case "甘肃":return new String[]{"兰州","嘉峪关","金昌","白银","天水","武威","张掖","平凉","酒泉","庆阳","定西","陇南","临夏","甘南"};
      case "青海":return new String[]{"西宁","海东","海北","黄南","海南州","果洛","玉树","海西"};
      case "宁夏":return new String[]{"银川","石嘴山","吴忠","固原","中卫"};
      case "新疆":return new String[]{"乌鲁木齐","克拉玛依","吐鲁番","哈密","昌吉","博尔塔拉","巴音郭楞","阿克苏","克孜勒苏","喀什","和田","伊犁","塔城","阿勒泰","兵团"};
      default:return new String[0];
    }
  }
  private void showCityDialog(String province){
    String[] cities=citiesForProvince(province);
    String[] choices=new String[cities.length+1];choices[0]="全部"+province+"频道";
    System.arraycopy(cities,0,choices,1,cities.length);
    showHoverChoiceDialog(province+" · 选择城市",choices,"返回",w->{
      favoriteMode=false;
      localRegionFilterActive=true;activeCategory="地方";activeLocalProvince=province;
      if(w==0){activeLocalCity=null;info.setText("正在显示："+province+" 地方频道");}
      else{activeLocalCity=cities[w-1];info.setText("正在筛选："+province+" · "+activeLocalCity+" 频道");}
      refresh();
    });
  }
  private String regionalMetadata(Channel c){
    if(c==null)return "";
    return (c.name+" "+c.group+" "+c.url).toLowerCase(Locale.ROOT)
        .replace("臺","台").replace("廣","广");
  }
  private boolean hasRegionalToken(String metadata,String token){
    return token!=null&&!token.isEmpty()&&metadata.contains(token.toLowerCase(Locale.ROOT));
  }
  private String explicitProvinceIn(String metadata){
    String[] names={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    for(String p:names)if(metadata.contains(p))return p;
    String[][] aliases={{"beijing","北京"},{"tianjin","天津"},{"hebei","河北"},{"shanxi","山西"},{"inner mongolia","内蒙古"},{"liaoning","辽宁"},{"jilin","吉林"},{"heilongjiang","黑龙江"},{"shanghai","上海"},{"jiangsu","江苏"},{"zhejiang","浙江"},{"anhui","安徽"},{"fujian","福建"},{"jiangxi","江西"},{"shandong","山东"},{"henan","河南"},{"hubei","湖北"},{"hunan","湖南"},{"guangdong","广东"},{"guangxi","广西"},{"hainan","海南"},{"chongqing","重庆"},{"sichuan","四川"},{"guizhou","贵州"},{"yunnan","云南"},{"tibet","西藏"},{"shaanxi","陕西"},{"gansu","甘肃"},{"qinghai","青海"},{"ningxia","宁夏"},{"xinjiang","新疆"}};
    for(String[] a:aliases)if(metadata.contains(a[0]))return a[1];
    return "";
  }
  private boolean isSatelliteChannel(Channel c){
    String metadata=regionalMetadata(c);
    String group=c==null||c.group==null?"":c.group.toLowerCase(Locale.ROOT);
    return metadata.contains("卫视")||group.equals("卫视")||group.contains("satellite");
  }
  private boolean matchesProvinceLocal(Channel c,String province){
    if(c==null||province==null||isSatelliteChannel(c))return false;
    String metadata=regionalMetadata(c);
    String explicit=explicitProvinceIn(metadata);
    if(!explicit.isEmpty()&&!explicit.equals(province))return false;
    if(hasRegionalToken(metadata,province))return true;
    for(String city:citiesForProvince(province)){
      if(hasRegionalToken(metadata,city))return true;
    }
    // Avoid assigning a city-only label to the wrong province when city names collide.
    if(!explicit.isEmpty())return false;
    return false;
  }
  private boolean isLocalChannel(Channel c){
    if(c==null||isSatelliteChannel(c))return false;
    String metadata=regionalMetadata(c);
    if(metadata.contains("地方")||metadata.contains("local"))return true;
    if(!explicitProvinceIn(metadata).isEmpty())return true;
    String[] provinces={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    for(String province:provinces)for(String city:citiesForProvince(province)){
      if(hasRegionalToken(metadata,city))return true;
    }
    return false;
  }
  private boolean matchesProvinceCity(Channel c,String province,String city){
    if(c==null||province==null||city==null||isSatelliteChannel(c))return false;
    String metadata=regionalMetadata(c);
    String explicit=explicitProvinceIn(metadata);
    if(!explicit.isEmpty()&&!explicit.equals(province))return false;
    if(!java.util.Arrays.asList(citiesForProvince(province)).contains(city))return false;
    return hasRegionalToken(metadata,city)||(hasRegionalToken(metadata,province)&&citiesForProvince(province).length==1);
  }
  private boolean matchesLocalRegion(Channel c){
    if(!localRegionFilterActive)return true;
    if(activeLocalProvince==null)return isLocalChannel(c);
    if(activeLocalCity==null)return matchesProvinceLocal(c,activeLocalProvince);
    return matchesProvinceCity(c,activeLocalProvince,activeLocalCity);
  }
  private boolean categoryMatch(Channel c,String cat){
    String x=(c.name+" "+c.group).toLowerCase(Locale.ROOT);
    String g=c.group.toLowerCase(Locale.ROOT);
    if(cat.equals("自建频道"))return "自建频道".equals(g)||"自定义".equals(g);
    if(cat.startsWith("城市:")){
      String city=cat.substring(3);
      String metadata=regionalMetadata(c);
      if(isSatelliteChannel(c))return false;
      String explicit=explicitProvinceIn(metadata);
      if(!explicit.isEmpty()){
        for(String province:new String[]{"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"}){
          if(explicit.equals(province)&&!java.util.Arrays.asList(citiesForProvince(province)).contains(city))return false;
        }
      }
      return hasRegionalToken(metadata,city);
    }
    String[] provinceNames={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    for(String province:provinceNames)if(cat.equals(province))return matchesProvinceLocal(c,province);
    if(cat.equals("全球频道"))return true;
    if(cat.equals("娱乐频道"))return g.contains("entertainment")||g.contains("comedy")||g.contains("music")||g.contains("lifestyle")||g.contains("movies")||g.contains("animation")||g.contains("series")||g.contains("culture")||g.contains("family")||g.contains("kids")||g.contains("children")||g.contains("documentary")||g.contains("cartoon")||g.contains("娱乐")||g.contains("综艺")||g.contains("音乐")||g.contains("电影")||g.contains("喜剧")||g.contains("生活")||g.contains("少儿")||g.contains("动画")||g.contains("纪录");
    if(cat.equals("央视"))return identity(c.name,c.group).startsWith("CCTV-")||x.contains("cctv")||x.contains("央视")||x.contains("中央电视台")||g.equals("cctv");
    if(cat.equals("卫视"))return x.contains("卫视")||g.equals("卫视")||g.contains("satellite");
    if(cat.equals("港澳台"))return x.contains("香港")||x.contains("澳门")||x.contains("台湾")||x.contains("港")||x.contains("澳")||x.contains("台")||g.equals("hong kong")||g.equals("macau")||g.equals("taiwan");
    if(cat.equals("国际新闻"))return g.contains("international news")||g.contains("国际新闻")||x.contains("al jazeera")||x.contains("france 24")||x.contains("dw ")||x.contains("abc news");
    if(cat.equals("体育"))return g.contains("sports")||g.contains("体育");
    if(cat.equals("天气"))return g.contains("weather")||g.contains("天气")||x.contains("气象");
    if(cat.equals("教育"))return g.contains("education")||g.contains("教育");
    if(cat.equals("中国"))return g.equals("中国")||g.equals("china")||x.contains("中国");
    if(cat.equals("地方"))return isLocalChannel(c);
    return false;
  }
  private void refresh(){
    if(list==null||search==null)return;
    int previousFocusedIndex=-1;
    View currentFocus=rootLayout==null?null:rootLayout.findFocus();
    if(currentFocus!=null&&currentFocus.getTag() instanceof Integer)previousFocusedIndex=(Integer)currentFocus.getTag();
    list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);
    final boolean customCategory=activeCategory.equals("自建频道");
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
    final int maxRows=120;int shown=0,totalMatches=0;
    ArrayList<Integer> visibleIndexes=new ArrayList<>();
    synchronized(channels){
      for(int i=0;i<channels.size();i++){
        Channel c=channels.get(i);
        if(activeGeoChannelKeys!=null&&!activeGeoChannelKeys.contains(c.key()))continue;
        if(favoriteMode&&!favorites.contains(c.key()))continue;
        if(localRegionFilterActive&&activeCategory.equals("地方")){
          if(!matchesLocalRegion(c))continue;
        }else if(!activeCategory.equals("全部")&&!activeCategory.equals("收藏")&&!categoryMatch(c,activeCategory))continue;
        if(!c.name.toLowerCase(Locale.ROOT).contains(q)&&!c.group.toLowerCase(Locale.ROOT).contains(q)
            &&!displayChannelName(c.name).toLowerCase(Locale.ROOT).contains(q))continue;
        visibleIndexes.add(i);
      }
      if(activeCategory.equals("央视")){
        visibleIndexes.sort((a,b)->{
          Channel ca=channels.get(a),cb=channels.get(b);
          int oa=cctvSortOrder(ca),ob=cctvSortOrder(cb);
          if(oa!=ob)return Integer.compare(oa,ob);
          return displayChannelName(ca.name).compareTo(displayChannelName(cb.name));
        });
      }
      totalMatches=visibleIndexes.size();
      for(int ix:visibleIndexes){
        if(shown>=maxRows)continue;
        Channel c=channels.get(ix);shown++;
        Button b=new Button(this);b.setTag(ix);
        String title=isCctvChannel(c)?displayChannelName(c.name):c.name;
        String group=isCctvChannel(c)?"":(c.group.isEmpty()?"":" · "+c.group);
        b.setText((favorites.contains(c.key())?"★ ":"")+title+group);
        b.setTextSize(16);b.setMinHeight(dp(52));styleLiveChannelButton(b,Color.rgb(35,45,62));b.setOnClickListener(v->play(ix));
        b.setOnLongClickListener(v->{new AlertDialog.Builder(this).setItems(new String[]{favorites.contains(c.key())?"取消收藏":"加入收藏","查看/选择备用源（"+c.backups.size()+"个）","删除频道"},(d,w)->{if(w==0){if(!favorites.add(c.key()))favorites.remove(c.key());}else if(w==1){String[] opts=new String[c.backups.size()];for(int j=0;j<opts.length;j++)opts[j]="播放源 "+(j+1)+(j==0?"（主源）":"（备用）");new AlertDialog.Builder(this).setTitle(title+" · 播放源").setItems(opts,(dd,j)->{c.activeSource=j;play(ix);}).setNegativeButton("关闭",null).show();}else{channels.remove(ix);favorites.remove(c.key());rebuildChannelIndexes();}persist();refresh();}).show();return true;});
        list.addView(b);
      }
    }
    if(totalMatches==0){
      TextView empty=new TextView(this);
      empty.setText(customCategory
        ?"还没有自建频道。点击上方“添加频道”或“输入网站入口”开始添加。"
        :"这个分类暂时没有匹配的频道。你可以导入播放列表，或更换分类/搜索关键词。");
      empty.setTextColor(Color.LTGRAY);empty.setTextSize(15);empty.setGravity(Gravity.CENTER);
      empty.setPadding(dp(18),dp(22),dp(18),dp(22));list.addView(empty,new LinearLayout.LayoutParams(-1,dp(180)));
    }
    if(totalMatches>maxRows){TextView more=new TextView(this);more.setText("共匹配 "+totalMatches+" 个频道，当前先显示 "+maxRows+" 个。请使用搜索缩小范围。");more.setTextColor(Color.LTGRAY);more.setPadding(dp(8),dp(12),dp(8),dp(12));list.addView(more);}
    final int restoreIndex=previousFocusedIndex;
    if(fullScreenPlayback&&fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())list.post(()->{
      if(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())return;
      View target=null;
      if(restoreIndex>=0){for(int i=0;i<list.getChildCount();i++){View child=list.getChildAt(i);if(child.getTag() instanceof Integer&&((Integer)child.getTag())==restoreIndex){target=child;break;}}}
      if(target==null&&selected>=0){for(int i=0;i<list.getChildCount();i++){View child=list.getChildAt(i);if(child.getTag() instanceof Integer&&((Integer)child.getTag())==selected){target=child;break;}}}
      if(target==null&&list.getChildCount()>0)target=list.getChildAt(0);
      if(target!=null)target.requestFocus();
    });
  }
  private View findChannelRow(int channelIndex){
    if(channelIndex<0)return null;
    for(int i=0;i<list.getChildCount();i++){
      View child=list.getChildAt(i);
      if(child.getTag() instanceof Integer&&((Integer)child.getTag())==channelIndex)return child;
    }
    return null;
  }
  private void focusSelectedChannelInMenu(){
    if(!fullScreenPlayback||fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())return;
    channelPane.post(()->{
      if(!fullScreenPlayback||fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())return;
      View target=findChannelRow(selected);
      if(target==null&&list.getChildCount()>0)target=list.getChildAt(0);
      if(target==null)return;
      final View focusTarget=target;
      channelScroll.post(()->{
        channelScroll.scrollTo(0,Math.max(0,focusTarget.getTop()-dp(72)));
        focusTarget.requestFocus();
      });
    });
  }
  private void focusFirstChannelInMenu(){
    focusSelectedChannelInMenu();
  }
  private void chooseFile(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
  private boolean globalLoading=false; private boolean entertainmentLoading=false; private boolean chinaLoading=false; private boolean regionLoading=false;
  private int fetchAndMergePlaylists(String[] feeds,int timeoutMs,StringBuilder errors){
    ExecutorCompletionService<PlaylistFetchResult> completion=new ExecutorCompletionService<>(vodSourceWorker);
    ArrayList<Future<PlaylistFetchResult>> tasks=new ArrayList<>();
    for(String address:feeds){
      tasks.add(completion.submit(()->{
        HttpURLConnection c=null;
        try{
          c=(HttpURLConnection)new URL(address).openConnection();
          c.setConnectTimeout(8000);c.setReadTimeout(timeoutMs);c.setInstanceFollowRedirects(true);
          c.setRequestProperty("User-Agent","MyTV/1.84 Android TV");c.setRequestProperty("Accept","*/*");
          int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
          try(InputStream in=c.getInputStream()){return new PlaylistFetchResult(address,read(in),null);}
        }catch(Exception e){return new PlaylistFetchResult(address,null,e.getMessage());}
        finally{if(c!=null)c.disconnect();}
      }));
    }
    int added=0,remaining=tasks.size();long deadline=android.os.SystemClock.elapsedRealtime()+Math.max(20000L,timeoutMs+12000L);
    while(remaining>0&&!Thread.currentThread().isInterrupted()){
      long wait=deadline-android.os.SystemClock.elapsedRealtime();if(wait<=0)break;
      try{
        Future<PlaylistFetchResult> done=completion.poll(Math.min(500L,wait),TimeUnit.MILLISECONDS);if(done==null)continue;
        remaining--;PlaylistFetchResult result=done.get();
        if(result.error!=null){if(errors!=null)errors.append(result.address.substring(result.address.lastIndexOf('/')+1)).append(": ").append(result.error).append("; ");continue;}
        if(result.data!=null){synchronized(channels){added+=parse(result.data);}}
      }catch(Exception e){if(errors!=null)errors.append(e.getMessage()).append("; ");}
    }
    for(Future<?> task:tasks)if(!task.isDone())task.cancel(true);
    return added;
  }
  private static class PlaylistFetchResult{
    final String address,data,error;
    PlaylistFetchResult(String a,String d,String e){address=a;data=d;error=e;}
  }
  private void loadChinaChannels(){
    if(chinaLoading){info.setText("国内频道列表正在整理，请稍候…");return;}
    android.content.SharedPreferences cachePrefs=getSharedPreferences("mytv",0);
    // Cache-first: opening CCTV/satellite/local categories must not redownload
    // twelve playlists on every visit. Refresh the catalogue at most every 12h.
    final long now=System.currentTimeMillis();
    final long last=cachePrefs.getLong("china_supplement_last_success",0L);
    final long refreshInterval=12L*60L*60L*1000L;
    if(last>0L&&now-last<refreshInterval){
      info.setText("已使用本地频道缓存 · 下次补源约 "+Math.max(1L,(refreshInterval-(now-last))/3600000L)+" 小时后");
      return;
    }
    chinaLoading=true;info.setText("正在轻量补充国内频道源（已有缓存可继续使用）…");
    worker.execute(()->{
      String[] feeds={
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
        "https://raw.githubusercontent.com/cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u",
        "https://raw.githubusercontent.com/mytv-android/China-TV-Live-M3U8/main/iptv.m3u",
        "https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV_AutoUpdate.m3u8",
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
        "https://iptv-cn.github.io/IPTV/countries/cn.m3u",
        "https://m3u.ibert.me/o_s_cn.m3u",
        "https://m3u.ibert.me/o_cn.m3u",
        "https://m3u.ibert.me/cn.m3u",
        "https://m3u.ibert.me/cn_p.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/cn.m3u"
      };
      StringBuilder errors=new StringBuilder();
      int added=fetchAndMergePlaylists(feeds,12000,errors);
      final int total=added;final String err=errors.toString();
      // Keep the previous cache if no new entries were merged; persist only
      // actual additions and avoid a full list rebuild when nothing changed.
      if(total>0)persist();
      if(err.split(";").length<feeds.length){
        cachePrefs.edit().putLong("china_supplement_last_success",System.currentTimeMillis()).apply();
      }
      runOnUiThread(()->{
        if(isFinishing())return;
        if(total>0)refresh();
        chinaLoading=false;
        info.setText("国内频道轻量补充完成：新增 "+total+" 个条目；本地缓存已保留。"+(err.isEmpty()?"":"部分来源暂不可用："+err));
      });
    });
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
      else loadGeoPlaylist(labels[w],"https://iptv-org.github.io/iptv/countries/"+codes[w]+".m3u");
    });
  }
  private void showUsStateDialog(){
    String[] names={"亚利桑那","加利福尼亚","内华达","纽约","新泽西","德克萨斯","佛罗里达","华盛顿州","伊利诺伊","马萨诸塞","夏威夷","科罗拉多","俄勒冈","宾夕法尼亚","佐治亚","北卡罗来纳","密歇根","俄亥俄","弗吉尼亚","犹他","新墨西哥","阿拉斯加"};
    String[] codes={"us-az","us-ca","us-nv","us-ny","us-nj","us-tx","us-fl","us-wa","us-il","us-ma","us-hi","us-co","us-or","us-pa","us-ga","us-nc","us-mi","us-oh","us-va","us-ut","us-nm","us-ak"};
    showSubdivisionDialog("美国州",names,codes);
  }
  private void showSubdivisionDialog(String title,String[] names,String[] codes){
    showChoiceDialog(title+" · 选择州/省",names,(d,w)->showCityPlaylistDialog(title,names[w],codes[w]));
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
  private void loadGeoPlaylist(String label,String address){
    info.setText("正在载入 "+label+" 频道目录…");
    worker.execute(()->{HttpURLConnection c=null;try{
      c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.0 Android");
      int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
      String data;try(InputStream in=c.getInputStream()){data=read(in);}
      final HashSet<String> destinationKeys=new HashSet<>();
      synchronized(channels){
        parsingGeoChannelKeys=destinationKeys;
        try{parse(data);}finally{parsingGeoChannelKeys=null;}
      }
      final int matched=destinationKeys.size();
      runOnUiThread(()->{
        activeGeoChannelKeys=new HashSet<>(destinationKeys);
        activeCategory="全部";favoriteMode=false;persist();refresh();
        info.setText(label+"：共匹配 "+matched+" 个目的地频道，当前最多显示 120 个。其他国家和地区频道不会混入。部分频道可能受地区、网络或播出授权限制。");
      });
    }catch(Exception e){runOnUiThread(()->info.setText(label+" 载入失败："+e.getMessage()));}finally{if(c!=null)c.disconnect();}});
  }
  private void loadGlobalChannels(){
    if(globalLoading){info.setText("全球频道列表正在载入，请稍候…");return;}
    globalLoading=true;info.setText("正在连接全球公开频道目录（含国家、地区及合规公开目录）…");
    worker.execute(()->{
      int added=0;StringBuilder errors=new StringBuilder();
      String[] feeds={
        "https://iptv-org.github.io/iptv/index.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/index.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/hk.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/mo.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/tw.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/jp.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/us.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/gb.m3u"
      };
      added=fetchAndMergePlaylists(feeds,22000,errors);
      final int total=added;final String err=errors.toString();
      runOnUiThread(()->{persist();refresh();globalLoading=false;info.setText("全球频道补充完成：新增 "+total+" 个频道条目（已去重并合并备用地址）。地区限制、版权和网络状况可能影响播放。"+(err.isEmpty()?"":"部分目录暂不可用："+err));});
    });
  }
  private void loadRegionChannels(){
    if(regionLoading){info.setText("港澳台频道正在载入，请稍候…");return;}
    regionLoading=true;info.setText("正在载入香港、澳门、台湾公开频道目录…");
    worker.execute(()->{
      int added=0;StringBuilder errors=new StringBuilder();
      String[] feeds={
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/hk.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/mo.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/countries/tw.m3u"
      };
      added=fetchAndMergePlaylists(feeds,22000,errors);
      final int total=added;final String err=errors.toString();
      runOnUiThread(()->{persist();refresh();regionLoading=false;info.setText("港澳台目录载入完成：新增 "+total+" 个条目。部分频道可能因地区或网络限制无法播放。"+(err.isEmpty()?"":"部分目录不可用："+err));});
    });
  }
  private void loadEntertainmentChannels(){
    if(entertainmentLoading){info.setText("全球娱乐频道列表正在载入，请稍候…");return;}
    entertainmentLoading=true;info.setText("正在载入全球娱乐频道（电影、音乐、动画、儿童、喜剧及生活）…");
    worker.execute(()->{
      int added=0;StringBuilder errors=new StringBuilder();
      String[] feeds={
        "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
        "https://iptv-org.github.io/iptv/categories/movies.m3u",
        "https://iptv-org.github.io/iptv/categories/series.m3u",
        "https://iptv-org.github.io/iptv/categories/music.m3u",
        "https://iptv-org.github.io/iptv/categories/kids.m3u",
        "https://iptv-org.github.io/iptv/categories/animation.m3u",
        "https://iptv-org.github.io/iptv/categories/comedy.m3u",
        "https://iptv-org.github.io/iptv/categories/documentary.m3u",
        "https://iptv-org.github.io/iptv/categories/family.m3u",
        "https://iptv-org.github.io/iptv/categories/lifestyle.m3u",
        "https://iptv-org.github.io/iptv/categories/culture.m3u",
        "https://iptv-org.github.io/iptv/languages/zho.m3u",
        "https://cdn.jsdelivr.net/gh/streamline-iptv/iptv-playlists@dist/index.m3u"
      };
      for(String address:feeds){HttpURLConnection c=null;try{
        c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","MyTV/1.0 Android");c.setRequestProperty("Accept","*/*");
        int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
        String data;try(InputStream in=c.getInputStream()){data=read(in);}
        synchronized(channels){added+=parse(data);}
      }catch(Exception e){errors.append(e.getMessage()).append("; ");}finally{if(c!=null)c.disconnect();}}
      final int total=added;final String err=errors.toString();
      runOnUiThread(()->{persist();refresh();entertainmentLoading=false;info.setText("娱乐频道目录载入完成：新增 "+total+" 个条目，覆盖电影、音乐、动画、儿童、喜剧、生活等类别。已合并重复地址；部分频道可能有地区限制。"+(err.isEmpty()?"":"部分目录不可用："+err));});
    });
  }
  private void urlDialog(){EditText e=new EditText(this);e.setSingleLine(true);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);e.setHint("https://网站或 /playlist.m3u");e.setText(getSharedPreferences("mytv",0).getString("last_playlist_url",""));e.setSelection(e.getText().length());new AlertDialog.Builder(this).setTitle("输入网站 / 播放列表入口").setMessage("支持可直接访问的 M3U/M3U8 播放列表网址；普通网页需提供实际播放列表或视频地址。").setView(e).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{String url=e.getText().toString().trim();getSharedPreferences("mytv",0).edit().putString("last_playlist_url",url).apply();download(url);}).show();}
  private void download(String address){
    if(!address.startsWith("https://")&&!address.startsWith("http://")){toast("网址格式不正确");return;}info.setText("正在下载播放列表…");
    worker.execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.2 Android");c.setRequestProperty("Accept","*/*");int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);try(InputStream in=c.getInputStream()){String data=read(in);runOnUiThread(()->finishImport(data));}finally{c.disconnect();}}catch(Exception e){runOnUiThread(()->info.setText("导入失败："+e.getMessage()));}});
  }
  private String read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>50*1024*1024)throw new IOException("播放列表超过 50 MB，已停止导入");b.write(buf,0,n);}return b.toString("UTF-8");}
  private void finishImport(String data){int n=parse(data);persist();refresh();info.setText("导入完成，新增 "+n+" 个频道");}
  private int extinfComma(String line){ boolean quoted=false; for(int i=0;i<line.length();i++){ char ch=line.charAt(i); if(ch=='"')quoted=!quoted; else if(ch==','&&!quoted)return i; } return -1; }
  private int parse(String data){
    String name=null,group="",pendingGroup="";int added=0;
    if(data==null)return 0;
    data=data.replace("\uFEFF","").replace("\r\n","\n").replace('\r','\n');
    for(String raw:data.split("\n")){
      String line=raw.trim();
      if(line.isEmpty())continue;
      if(line.startsWith("#EXTGRP:")){pendingGroup=line.substring(8).trim();continue;}
      if(line.startsWith("#EXTINF")){
        int comma=extinfComma(line);
        name=comma>=0?line.substring(comma+1).trim():"";
        java.util.regex.Matcher nm=java.util.regex.Pattern.compile("tvg-name=\"([^\"]+)\"").matcher(line);
        String tvgName=nm.find()?nm.group(1).trim():"";
        if(name.isEmpty())name=tvgName;
        if(name.isEmpty())name="频道 "+(channels.size()+1);
        group=pendingGroup;pendingGroup="";
        java.util.regex.Matcher gm=java.util.regex.Pattern.compile("group-title=\"([^\"]*)\"").matcher(line);
        if(gm.find()&&!gm.group(1).trim().isEmpty())group=gm.group(1).trim();
        continue;
      }
      if(line.startsWith("#"))continue;
      if(line.startsWith("http://")||line.startsWith("https://")){
        if(name==null||name.trim().isEmpty())name="频道 "+(channels.size()+1);
        synchronized(channels){
          if(!channelsByUrl.containsKey(line)){
            String key=identity(name,group,line);Channel same=channelsByIdentity.get(key);
            if(same!=null){if(!same.backups.contains(line)){same.backups.add(line);channelsByUrl.put(line,same);added++;}}
            else{Channel fresh=new Channel(name,line,group);channels.add(fresh);channelsByIdentity.put(key,fresh);channelsByUrl.put(line,fresh);added++;}
          }
        }
        if(parsingGeoChannelKeys!=null){
          Channel scoped=channelsByUrl.get(line);
          if(scoped!=null)parsingGeoChannelKeys.add(scoped.key());
        }
        name=null;group="";
      }
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
            String group=c.group.replace("\"","'");
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
  private void addDialog(){LinearLayout f=new LinearLayout(this);f.setOrientation(1);EditText n=new EditText(this);n.setHint("频道名称");EditText u=new EditText(this);u.setHint("播放网址（http/https，m3u8 等）");f.addView(n);f.addView(u);new AlertDialog.Builder(this).setTitle("添加自建频道").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String name=n.getText().toString().trim(),url=u.getText().toString().trim();if(!name.isEmpty()&&(url.startsWith("http://")||url.startsWith("https://"))){Channel c=new Channel(name,url,"自建频道");channels.add(c);rebuildChannelIndexes();persist();activeCategory="自建频道";favoriteMode=false;refresh();play(channels.indexOf(c));}else toast("请填写频道名称和有效网址");}).show();}
  private void showFullscreenChannelMenu(){
    if(!fullScreenPlayback||fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())return;
    if(fullscreenAspectMenuItem==null){fullscreenAspectMenuItem=new Button(this);fullscreenAspectMenuItem.setText("画面比例");fullscreenAspectMenuItem.setAllCaps(false);fullscreenAspectMenuItem.setTextColor(Color.WHITE);fullscreenAspectMenuItem.setOnClickListener(v->{closeFullscreenChannelMenu();showAspectRatioDialog(video,"live_aspect_mode");});}
    if(fullscreenAspectMenuItem.getParent()==null&&categoryMenuItems!=null)categoryMenuItems.addView(fullscreenAspectMenuItem,0,new LinearLayout.LayoutParams(-1,dp(48)));
    if(categoryPane.getParent()==rootLayout)rootLayout.removeView(categoryPane);
    if(channelPane.getParent()==rootLayout)rootLayout.removeView(channelPane);
    categoryPane.setVisibility(View.VISIBLE);channelPane.setVisibility(View.VISIBLE);
    LinearLayout menu=new LinearLayout(this);menu.setOrientation(0);menu.setBackgroundColor(Color.rgb(8,13,24));
    menu.addView(categoryPane,new LinearLayout.LayoutParams(dp(104),-1));
    menu.addView(channelPane,new LinearLayout.LayoutParams(dp(250),-1));
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
      categoryPane.setVisibility(View.GONE);channelPane.setVisibility(View.GONE);
      fullscreenMenuPopup=null;
      if(fullscreenAspectMenuItem!=null&&fullscreenAspectMenuItem.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)fullscreenAspectMenuItem.getParent()).removeView(fullscreenAspectMenuItem);
      if(fullScreenPlayback&&isTouchDevice())showFullscreenControls();else if(fullscreenMenuButton!=null)fullscreenMenuButton.setVisibility(View.GONE);
      if(video!=null&&!isTouchDevice())video.requestFocus();
    });
    fullscreenMenuPopup.showAtLocation(rootLayout,Gravity.LEFT|Gravity.TOP,0,0);
    fullscreenMenuButton.setVisibility(View.GONE);
    // 弹出层挂载后，直接滚动到当前正在播放的频道，并把遥控器焦点放在该行。
    focusSelectedChannelInMenu();
  }
  private boolean isTouchDevice(){
    return getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
  }
  private void closeFullscreenChannelMenu(){
    if(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())fullscreenMenuPopup.dismiss();
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
    LinearLayout.LayoutParams videoParams=new LinearLayout.LayoutParams(-1,0,1f);
    video.setLayoutParams(videoParams);
    fullscreenMenuButton.setVisibility(View.GONE);
    fullscreenSourceButton.setVisibility(View.GONE);
    fullscreenFavoriteButton.setText(isCurrentLiveFavorite()?"★ 已收藏":"☆ 收藏");
    fullscreenFavoriteButton.setVisibility(View.GONE);
    fullscreenControlsVisible=false;
    if(isTouchDevice())showFullscreenControls();
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    video.requestFocus();
  }
  private void exitFullScreenPlayback(){
    if(!fullScreenPlayback||rootLayout==null)return;
    closeFullscreenChannelMenu();
    fullScreenPlayback=false;
    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if(video!=null)video.setKeepScreenOn(false);
    if(hideFullscreenControls!=null)uiHandler.removeCallbacks(hideFullscreenControls);
    fullscreenControlsVisible=false;
    fullscreenMenuButton.setVisibility(View.GONE);
    fullscreenSourceButton.setVisibility(View.GONE);
    fullscreenFavoriteButton.setVisibility(View.GONE);
    if(categoryPane.getParent()!=rootLayout)rootLayout.addView(categoryPane,0,new LinearLayout.LayoutParams(dp(104),-1));
    if(channelPane.getParent()!=rootLayout)rootLayout.addView(channelPane,1,new LinearLayout.LayoutParams(dp(250),-1));
    categoryPane.setVisibility(View.VISIBLE);
    channelPane.setVisibility(View.VISIBLE);
    playbackInfo.setVisibility(View.VISIBLE);
    rootLayout.setPadding(dp(10),dp(8),dp(10),dp(8));
    mainPane.setPadding(dp(10),0,0,0);
    mainPane.setLayoutParams(new LinearLayout.LayoutParams(0,-1,1));
    video.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1f));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
        channelScroll.scrollTo(0,Math.max(0,target.getTop()-dp(72)));
        target.requestFocus();
      });
    }
  }
  private void showFullscreenControls(){
    if(!fullScreenPlayback||!isTouchDevice())return;
    fullscreenControlsVisible=true;
    TextView[] controls={fullscreenMenuButton,fullscreenSourceButton,fullscreenFavoriteButton};
    for(TextView control:controls){control.animate().cancel();control.setVisibility(View.VISIBLE);control.setAlpha(0f);control.animate().alpha(1f).setDuration(180).start();}
    if(hideFullscreenControls!=null)uiHandler.removeCallbacks(hideFullscreenControls);
    hideFullscreenControls=()->hideFullscreenControlsNow();
    uiHandler.postDelayed(hideFullscreenControls,3500);
  }
  private void hideFullscreenControlsNow(){
    if(!fullScreenPlayback)return;
    fullscreenControlsVisible=false;
    TextView[] controls={fullscreenMenuButton,fullscreenSourceButton,fullscreenFavoriteButton};
    for(TextView control:controls){control.animate().cancel();control.animate().alpha(0f).setDuration(220).withEndAction(()->{if(!fullscreenControlsVisible&&fullScreenPlayback)control.setVisibility(View.GONE);}).start();}
    if(hideFullscreenControls!=null)uiHandler.removeCallbacks(hideFullscreenControls);
  }
  private boolean isCurrentLiveFavorite(){return selected>=0&&selected<channels.size()&&favorites.contains(channels.get(selected).key());}
  private void toggleCurrentLiveFavorite(){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    if(favorites.contains(c.key())){favorites.remove(c.key());toast("已取消收藏："+c.name);}
    else{favorites.add(c.key());toast("已加入收藏："+c.name);}
    persist();
    if(fullscreenFavoriteButton!=null)fullscreenFavoriteButton.setText(isCurrentLiveFavorite()?"★ 已收藏":"☆ 收藏");
    if(fullScreenPlayback&&isTouchDevice())showFullscreenControls();
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
  private void showLivePlaybackActions(){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    boolean saved=favorites.contains(c.key());
    String[] actions={saved?"取消收藏当前频道":"收藏当前频道","选择播放源","打开频道列表"};
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(c.name+" · 播放操作")
      .setItems(actions,(d,which)->{
        d.dismiss();
        uiHandler.post(()->{
          if(which==0)toggleCurrentLiveFavorite();
          else if(which==1)showSourcePicker();
          else if(which==2)showFullscreenChannelMenu();
        });
      }).setNegativeButton("关闭",null).create();
    fullscreenRemoteDialogOpen=true;
    dialog.setOnDismissListener(d->fullscreenRemoteDialogOpen=false);
    dialog.show();
  }
  // 全屏播放时的遥控器快捷键；频道菜单打开后，方向键和确认键交给菜单控件。
  @Override public boolean dispatchKeyEvent(KeyEvent event){
    if(fullScreenPlayback&&event.getKeyCode()==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_DOWN){if(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())closeFullscreenChannelMenu();else exitFullScreenPlayback();}return true;}
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
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
          if(ok)showFullscreenChannelMenu();
          else if(key==KeyEvent.KEYCODE_DPAD_UP||channelUp)stepChannel(-1);
          else if(key==KeyEvent.KEYCODE_DPAD_DOWN||channelDown)stepChannel(1);
          else if(key==KeyEvent.KEYCODE_DPAD_LEFT)stepSource(-1);
          else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)stepSource(1);
        }
        return true;
      }
    }
    if(fullScreenPlayback&&!fullscreenRemoteDialogOpen&&(fullscreenMenuPopup==null||!fullscreenMenuPopup.isShowing())
        &&video!=null&&video.dispatchKeyEvent(event))return true;
    return super.dispatchKeyEvent(event);
  }
  private ArrayList<Integer> currentRemoteChannelOrder(){
    ArrayList<Integer> order=new ArrayList<>();
    String q=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);
    synchronized(channels){
      for(int i=0;i<channels.size();i++){
        Channel c=channels.get(i);
        if(favoriteMode&&!favorites.contains(c.key()))continue;
        if(!activeCategory.equals("全部")&&!activeCategory.equals("收藏")&&!categoryMatch(c,activeCategory))continue;
        if(!c.name.toLowerCase(Locale.ROOT).contains(q)&&!c.group.toLowerCase(Locale.ROOT).contains(q)
            &&!displayChannelName(c.name).toLowerCase(Locale.ROOT).contains(q))continue;
        order.add(i);
      }
      if(activeCategory.equals("央视")){
        order.sort((x,y)->{
          Channel cx=channels.get(x),cy=channels.get(y);
          int ox=cctvSortOrder(cx),oy=cctvSortOrder(cy);
          if(ox!=oy)return Integer.compare(ox,oy);
          return displayChannelName(cx.name).compareTo(displayChannelName(cy.name));
        });
      }
    }
    return order;
  }
  private void stepChannel(int delta){
    ArrayList<Integer> order=currentRemoteChannelOrder();
    if(order.isEmpty()){toast("当前栏目没有可切换的频道");return;}
    int current=order.indexOf(selected);
    int nextPosition=current<0?(delta>0?0:order.size()-1):Math.floorMod(current+delta,order.size());
    play(order.get(nextPosition));
    showChannelZapOverlay();
  }
  private void showChannelZapOverlay(){
    if(selected<0||selected>=channels.size())return;
    showChannelZapMessage(channels.get(selected).name);
  }
  private void showChannelZapMessage(String message){
    if(channelZapOverlay==null)return;
    if(hideChannelZapOverlay!=null)uiHandler.removeCallbacks(hideChannelZapOverlay);
    channelZapOverlay.setText(message);
    channelZapOverlay.setGravity(Gravity.CENTER);
    channelZapOverlay.setVisibility(View.VISIBLE);
    hideChannelZapOverlay=()->{if(channelZapOverlay!=null)channelZapOverlay.setVisibility(View.GONE);hideChannelZapOverlay=null;};
    uiHandler.postDelayed(hideChannelZapOverlay,1800L);
  }
  private boolean isSourceBlocked(String url){
    if(url==null||url.isEmpty())return false;
    return getSharedPreferences("mytv",0).getStringSet("blocked_ad_sources",new HashSet<>()).contains(url);
  }
  private boolean blockSource(String url){
    if(url==null||url.isEmpty())return false;
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    HashSet<String> blocked=new HashSet<>(p.getStringSet("blocked_ad_sources",new HashSet<>()));
    boolean added=blocked.add(url);
    p.edit().putStringSet("blocked_ad_sources",blocked).apply();
    return added;
  }
  private void showSourcePicker(){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    if(c.backups==null||c.backups.isEmpty()){toast("当前频道没有可用播放源");return;}
    // Re-rank whenever the picker opens so the latest playback results are reflected.
    // The sorter preserves the currently playing URL and only persists a real order change.
    sortSourcesByObservedQuality(c);
    ArrayList<String> labels=new ArrayList<>();
    ArrayList<Integer> indexes=new ArrayList<>();
    for(int i=0;i<c.backups.size();i++){
      String url=c.backups.get(i);
      boolean blocked=isSourceBlocked(url);
      labels.add("播放源 "+(i+1)+(i==c.activeSource?"（当前）":"")+(i==0?" · 主源":" · 备用源")+(blocked?" · 已屏蔽（点此恢复）":""));
      indexes.add(i);
    }
    labels.add("屏蔽当前播放源（广告、错台或异常内容）");
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(c.name+" · 选择播放源")
      .setItems(labels.toArray(new String[0]),(d,which)->{
        if(which==indexes.size()){
          String bad=currentSourceUrl();
          if(bad.isEmpty()){toast("当前播放源地址为空");return;}
          blockSource(bad);
          int next=findNextUnblockedSource(c,c.activeSource,1);
          if(next>=0){c.activeSource=next;automaticSwitchCount=0;sameSourceRecoveryCount=0;manualSourceSwitch=true;persist();toast("已屏蔽此源，正在切换到其他源");startCurrentSource();}
          else{persist();setSourceUpdatePlaybackActive(false);toast("已屏蔽此源，但本频道没有其他未屏蔽源");}
          return;
        }
        int index=indexes.get(which);
        String url=c.backups.get(index);
        if(isSourceBlocked(url)){
          android.content.SharedPreferences p=getSharedPreferences("mytv",0);
          HashSet<String> blocked=new HashSet<>(p.getStringSet("blocked_ad_sources",new HashSet<>()));
          blocked.remove(url);p.edit().putStringSet("blocked_ad_sources",blocked).apply();
          toast("已恢复该播放源");
        }
        c.activeSource=index;automaticSwitchCount=0;sameSourceRecoveryCount=0;manualSourceSwitch=true;persist();startCurrentSource();
      }).setNegativeButton("取消",null).create();
    if(fullScreenPlayback){
      fullscreenRemoteDialogOpen=true;
      dialog.setOnDismissListener(d->fullscreenRemoteDialogOpen=false);
    }
    dialog.show();
  }
  private int findNextUnblockedSource(Channel c,int from,int direction){
    if(c==null||c.backups==null||c.backups.size()<2)return -1;
    // Manual selection remains unrestricted except for sources explicitly blocked by the user.
    for(int step=1;step<c.backups.size();step++){
      int ix=(from+direction*step)%c.backups.size();
      if(ix<0)ix+=c.backups.size();
      if(!isSourceBlocked(c.backups.get(ix)))return ix;
    }
    return -1;
  }
  private int findNextAutomaticSource(Channel c,int from){
    if(c==null||c.backups==null||c.backups.size()<2)return -1;
    int fallback=-1;
    for(int step=1;step<c.backups.size();step++){
      int ix=(from+step)%c.backups.size();
      String url=c.backups.get(ix);
      if(isSourceBlocked(url))continue;
      if(fallback<0)fallback=ix;
      if(!SourceMemoryStore.isCoolingDown(this,url))return ix;
    }
    // If every remaining source is cooling down, still try an unblocked source.
    return fallback;
  }
  private void stepSource(int delta){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    if(c.backups.size()<2){toast("当前频道只有 1 个播放源");return;}
    int next=findNextUnblockedSource(c,c.activeSource,delta>=0?1:-1);
    if(next<0){toast("本频道备用源都已被屏蔽，请在换源菜单中恢复");return;}
    c.activeSource=next;
    automaticSwitchCount=0;
    sameSourceRecoveryCount=0;
    manualSourceSwitch=true;
    startCurrentSource();
    showChannelZapMessage(c.name+"\n正在尝试第 "+(c.activeSource+1)+" / "+c.backups.size()+" 个源");
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
    if(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()){closeFullscreenChannelMenu();return;}
    if(fullScreenPlayback){exitFullScreenPlayback();return;}
    // First Back from the channel pane returns focus to the left category menu.
    // A second Back while already in that menu exits the app as expected.
    View focused=rootLayout==null?null:rootLayout.findFocus();
    if(categoryPane!=null&&!isInsideView(focused,categoryPane)){
      focusActiveLeftMenuItem();
      return;
    }
    super.onBackPressed();
  }

  // Keep a small, user-shareable live diagnostic trail. Query strings and full
  // stream URLs are deliberately excluded so signed URLs/tokens are not exposed.
  private String safeDiagnosticDetail(String value){
    if(value==null)return "";
    String clean=value.replaceAll("(?i)https?://\\S+","[URL]");
    clean=clean.replaceAll("[\\r\\n\\t]+"," ");
    return clean.length()>260?clean.substring(0,260):clean;
  }
  private void saveLiveDiagnostic(String stage,String detail){
    String line=java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM,Locale.getDefault()).format(new Date())
        +" | "+stage+" | "+safeDiagnosticDetail(detail);
    android.util.Log.w("MyTVLiveDiag",line);
    try{
      android.content.SharedPreferences p=getSharedPreferences("mytv",0);
      String old=p.getString("live_diagnostics_v1","");
      ArrayList<String> entries=new ArrayList<>();
      if(old!=null&&!old.isEmpty())entries.addAll(Arrays.asList(old.split("\\n")));
      entries.add(line);
      while(entries.size()>30)entries.remove(0);
      p.edit().putString("live_diagnostics_v1",android.text.TextUtils.join("\n",entries)).apply();
    }catch(Exception ignored){}
  }
  private void showLiveDiagnostics(){
    String details=getSharedPreferences("mytv",0).getString("live_diagnostics_v1","暂无直播诊断记录。请打开一个直播频道并等待自动检测。");
    new AlertDialog.Builder(this).setTitle("直播诊断记录")
        .setMessage(details+"\n\n提示：点击“复制”后可把记录发给我；记录不包含完整播放地址。")
        .setPositiveButton("复制记录",(d,w)->{
          android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
          if(cm!=null){cm.setPrimaryClip(ClipData.newPlainText("我的电视直播诊断",details));toast("诊断记录已复制");}
        }).setNegativeButton("关闭",null).show();
  }

  private int automaticSwitchCount=0;
  // A complete source failure advances through channels at most once per manual play.
  private int automaticChannelSwitchCount=0;
  private Runnable bufferingFallback;
  private Runnable playbackWatchdog;
  private long watchdogLastPosition=-1L;
  private long watchdogLastAdvanceAt=0L;
  // Updated from the decoder playback thread whenever a video frame is about to render.
  private volatile long lastVideoFrameAt=0L;
  private volatile boolean videoFrameMonitoringActive=false;
  private volatile boolean hasReceivedVideoFrame=false;
  // One same-source recovery per selected channel before moving to its backup.
  private int sameSourceRecoveryCount=0;
  // A stale HLS live-window position gets one in-place recovery before source failover.
  private boolean liveWindowRecoveryAttempted=false;
  private long sourceAttemptStartedAt=0L;
  private long sourceReadyAt=0L;
  private boolean resumePlaybackOnStart=false;
  private boolean sourceReadyRecorded=false;
  private volatile boolean sourceFirstFrameRecorded=false;
  private volatile boolean sourceFirstLoadCompleted=false;
  private boolean sourceFailureRecorded=false;
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
    cancelAudioTrackCheck();
    if(player==null||player.getPlaybackState()!=Player.STATE_READY)return;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    audioTrackCheck=()->{
      audioTrackCheck=null;
      if(player==null||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId)
          ||player.getPlaybackState()!=Player.STATE_READY)return;
      boolean missingAudio=!audioTrackPresent||!audioTrackSupported||!audioTrackSelected||!audioPositionAdvancing;
      boolean missingVideo=!videoTrackPresent||!videoTrackSupported||!videoTrackSelected;
      if(missingAudio||missingVideo){
        recordCurrentSourceFailure();
        String reason=missingAudio&&missingVideo?"音视频轨道均不可用":missingAudio?"没有有效声音输出":"没有可用视频轨道";
        advanceToNextSourceAutomatically("检测到当前源"+reason);
      }
    };
    uiHandler.postDelayed(audioTrackCheck,12000L);
  }
  private void cancelPlaybackWatchdog(){
    if(playbackWatchdog!=null){uiHandler.removeCallbacks(playbackWatchdog);playbackWatchdog=null;}
  }
  private void schedulePlaybackWatchdog(){
    cancelPlaybackWatchdog();
    if(player==null||player.getPlaybackState()!=Player.STATE_READY||!player.getPlayWhenReady())return;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    playbackWatchdog=()->{
      playbackWatchdog=null;
      if(player==null||selected!=expectedChannel||!currentMediaId.equals(expectedMediaId))return;
      if(!player.getPlayWhenReady())return;
      int state=player.getPlaybackState();
      // 缓冲由独立计时器处理；READY 但没有真正推进时间轴也必须继续监测。
      if(state==Player.STATE_BUFFERING){if(bufferingFallback==null)scheduleBufferingFallback();return;}
      if(state!=Player.STATE_READY)return;
      long now=android.os.SystemClock.elapsedRealtime();
      long position=player.getCurrentPosition();
      if(watchdogLastPosition<0||position>watchdogLastPosition+250){
        watchdogLastPosition=position;
        watchdogLastAdvanceAt=now;
      }else if(now-watchdogLastAdvanceAt>=45000L){
        recoverFromSilentFreeze("播放时间轴连续 45 秒没有推进");
        return;
      }
      // A live source can leave ExoPlayer in READY while its audio/video output has
      // silently stopped. Monitor actual video-frame callbacks, not only position.
      // Monitor as soon as a video track is selected. Requiring
      // videoFrameMonitoringActive here would make the first-frame timeout
      // unreachable, because that flag is set only by the first frame callback.
      if(videoTrackSelected){
        if(!hasReceivedVideoFrame && sourceReadyAt>0L && now-sourceReadyAt>=20000L){
          recoverFromSilentFreeze("播放器已连接，但 20 秒内没有输出视频画面");
          return;
        }
        if(hasReceivedVideoFrame && now-lastVideoFrameAt>=20000L){
          recoverFromSilentFreeze("检测到视频帧连续 20 秒没有输出");
          return;
        }
      }
      // After two minutes of uninterrupted playback, replenish the failover budget.
      // A source that was healthy for a long time should not consume the startup retry budget forever.
      if(sourceReadyAt>0L && now-sourceReadyAt>=120000L){
        automaticSwitchCount=0;
        automaticChannelSwitchCount=0;
        sameSourceRecoveryCount=0;
        sourceReadyAt=now;
      }
      schedulePlaybackWatchdog();
    };
    uiHandler.postDelayed(playbackWatchdog,10000L);
  }
  private void recoverFromSilentFreeze(String reason){
    if(sameSourceRecoveryCount<1){
      sameSourceRecoveryCount++;
      info.setText(reason+"，正在重新连接当前频道…");
      startCurrentSource();
    }else{
      sameSourceRecoveryCount=0;
      advanceToNextSourceAutomatically(reason+"，当前源重连无效");
    }
  }
  // A given live stream may report error, buffering timeout and ENDED almost together.
  // Only allow one automatic failover for each playback generation.
  private String lastAutoFailoverMediaId="";
  private void releaseLivePlayerSafely(){
    ExoPlayer old=player;
    player=null;
    if(video!=null){try{video.setPlayer(null);}catch(RuntimeException ignored){}}
    if(old!=null){
      try{old.pause();old.stop();old.clearMediaItems();}catch(RuntimeException ignored){}
      try{old.release();}catch(RuntimeException ignored){}
    }
  }
  private void cancelBufferingFallback(){
    if(bufferingFallback!=null){uiHandler.removeCallbacks(bufferingFallback);bufferingFallback=null;}
  }
  private void scheduleBufferingFallback(){
    cancelBufferingFallback();
    int configured=getSharedPreferences("mytv",0).getInt("buffer_switch_seconds",20);
    if(configured<=0)return;
    // Initial live startup can legitimately take longer than a mid-stream rebuffer.
    // Do not abandon a healthy but slow-to-start source after only 3-5 seconds.
    int seconds=sourceReadyRecorded?configured:30;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    bufferingFallback=()->{
      bufferingFallback=null;
      if(player!=null&&selected==expectedChannel&&currentMediaId.equals(expectedMediaId)
          &&player.getPlaybackState()==Player.STATE_BUFFERING){
        saveLiveDiagnostic("BUFFER_TIMEOUT","still buffering after "+seconds+"s");
        advanceToNextSourceAutomatically("连续缓冲超过 "+seconds+" 秒");
      }
    };
    uiHandler.postDelayed(bufferingFallback,seconds*1000L);
  }
  private void advanceToNextSourceAutomatically(String reason){
    if(selected<0||selected>=channels.size())return;
    String failedMediaId=currentMediaId;
    if(failedMediaId.isEmpty()||failedMediaId.equals(lastAutoFailoverMediaId))return;
    // Every automatic failover path (buffer timeout, silent freeze, ended stream,
    // track failure or player error) must update the same failure memory. Some
    // paths arrive here without passing through onPlayerError.
    recordCurrentSourceFailure();
    lastAutoFailoverMediaId=failedMediaId;
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
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
    c.activeSource=next;
    info.setText(reason+"，正在自动切换备用源 "+(c.activeSource+1)+"/"+c.backups.size());
    startCurrentSource();
  }
  private void advanceToNextChannelAutomatically(String reason){
    if(selected<0||selected>=channels.size()||channels.isEmpty())return;
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    if(channels.size()<=1||automaticChannelSwitchCount>=channels.size()-1){
      setSourceUpdatePlaybackActive(false);
      info.setText(reason+"；已检查完本轮频道，没有可确认可播放的频道，请手动选择");
      return;
    }
    int next=(selected+1)%channels.size();
    automaticChannelSwitchCount++;
    automaticSwitchCount=0;
    sameSourceRecoveryCount=0;
    manualSourceSwitch=false;
    selected=next;
    Channel target=channels.get(selected);
    if(target.activeSource<0||target.activeSource>=target.backups.size())target.activeSource=0;
    info.setText(reason+"，自动切换到下一频道："+target.name);
    startCurrentSource();
  }
  private void play(int ix){
    if(ix<0||ix>=channels.size())return;
    automaticSwitchCount=0;
    automaticChannelSwitchCount=0;
    sameSourceRecoveryCount=0;
    manualSourceSwitch=false;
    selected=ix;
    if(fullscreenFavoriteButton!=null)fullscreenFavoriteButton.setText(isCurrentLiveFavorite()?"★ 已收藏":"☆ 收藏");
    sortSourcesByObservedQuality(channels.get(ix));
    applyLastKnownGoodSource(channels.get(ix));
    startCurrentSource();
  }
  private void startCurrentSource(){
    if(selected<0||selected>=channels.size())return;
    setSourceUpdatePlaybackActive(true);
    closeFullscreenChannelMenu();
    enterFullScreenPlayback();
    Channel c=channels.get(selected);
    if(c.activeSource<0||c.activeSource>=c.backups.size())c.activeSource=0;
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    cancelAudioTrackCheck();
    audioTrackPresent=false;audioTrackSupported=false;audioTrackSelected=false;audioPositionAdvancing=false;videoTrackPresent=false;videoTrackSupported=false;videoTrackSelected=false;
    watchdogLastPosition=-1L;
    watchdogLastAdvanceAt=android.os.SystemClock.elapsedRealtime();
    sourceAttemptStartedAt=android.os.SystemClock.elapsedRealtime();
    sourceReadyAt=0L;
    lastVideoFrameAt=0L;
    hasReceivedVideoFrame=false;
    videoFrameMonitoringActive=false;
    sourceReadyRecorded=false;
    sourceFirstFrameRecorded=false;
    sourceFirstLoadCompleted=false;
    sourceFailureRecorded=false;
    liveWindowRecoveryAttempted=false;
    sourceWasBuffering=false;
    recordSourceMetric(c.backups.get(c.activeSource),"attempt",0L);
    saveLastChannel();
    final long generation=++playbackGeneration;
    currentMediaId=generation+":"+selected+":"+c.activeSource;
    info.setText("正在尝试："+c.name+"（源 "+(c.activeSource+1)+"/"+c.backups.size()+"）");
    saveLiveDiagnostic("SOURCE_START","channel="+c.name+" sourceIndex="+(c.activeSource+1)+" total="+c.backups.size());
    try{
    if(player==null){
      player=PlaybackPlayerFactory.createLivePlayer(this,isDecoderFallbackEnabled());
      video.setPlayer(player);
      // Avoid per-frame reflection callbacks on older TV firmware; the playback
      // watchdog below monitors the player timeline without touching codec callbacks.
      final ExoPlayer listenerOwner=player;
      player.addAnalyticsListener(new AnalyticsListener(){
        @Override public void onLoadError(AnalyticsListener.EventTime eventTime,LoadEventInfo loadEventInfo,MediaLoadData mediaLoadData,IOException error,boolean wasCanceled){
          if(listenerOwner!=MainActivity.this.player)return;
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
          if(listenerOwner==MainActivity.this.player&&loadEventInfo!=null&&!sourceFirstLoadCompleted){
            sourceFirstLoadCompleted=true;
            saveLiveDiagnostic("LOAD_FIRST_OK","uriHost="+(loadEventInfo.uri==null?"?":String.valueOf(loadEventInfo.uri.getHost()))+" bytes="+loadEventInfo.bytesLoaded);
          }
        }
        @Override public void onAudioPositionAdvancing(AnalyticsListener.EventTime eventTime,long playoutStartSystemTimeMs){
          if(listenerOwner==MainActivity.this.player)audioPositionAdvancing=true;
        }
        @Override public void onAudioCodecError(AnalyticsListener.EventTime eventTime,Exception error){
          // Codec callbacks can be recoverable. Record diagnostics, but let Player.Listener
          // and the sustained-output watchdog decide whether the source actually failed.
          if(listenerOwner==MainActivity.this.player)
            recordSourceMetric(currentSourceUrl(),"audioCodecError",0L);
        }
        @Override public void onAudioSinkError(AnalyticsListener.EventTime eventTime,Exception error){
          // A single sink warning is not proof of a dead stream; avoid premature failover.
          if(listenerOwner==MainActivity.this.player)
            recordSourceMetric(currentSourceUrl(),"audioSinkError",0L);
        }
        @Override public void onVideoCodecError(AnalyticsListener.EventTime eventTime,Exception error){
          // Keep video decoder diagnostics separate from confirmed source failures.
          if(listenerOwner==MainActivity.this.player)
            recordSourceMetric(currentSourceUrl(),"videoCodecError",0L);
        }
      });
      // Real decoded-frame callbacks: the old watchdog fields existed but were never
      // activated, so READY could be mistaken for visible video on silent/black streams.
      player.setVideoFrameMetadataListener((presentationTimeUs,releaseTimeNs,format,mediaFormat)->{
        if(listenerOwner==MainActivity.this.player){
          MediaItem renderedItem=listenerOwner.getCurrentMediaItem();
          if(renderedItem!=null&&currentMediaId.equals(renderedItem.mediaId)){
            long frameAt=android.os.SystemClock.elapsedRealtime();
            lastVideoFrameAt=frameAt;
            hasReceivedVideoFrame=true;
            videoFrameMonitoringActive=true;
            if(!sourceFirstFrameRecorded){
              sourceFirstFrameRecorded=true;
              String renderedUrl=currentSourceUrl();
              recordSourceMetric(renderedUrl,"firstFrame",frameAt-sourceAttemptStartedAt);
              if(selected>=0&&selected<channels.size()){
                Channel renderedChannel=channels.get(selected);
                SourceMemoryStore.saveLastGood(MainActivity.this,
                    SourceMemoryStore.channelKey(renderedChannel.name,renderedChannel.group,renderedChannel.url),
                    renderedUrl);
              }
            }
          }
        }
      });
      player.addListener(new Player.Listener(){
        public void onTracksChanged(Tracks tracks){
          if(listenerOwner!=MainActivity.this.player)return;
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
          audioTrackSelected=audioSelected;
          videoTrackPresent=videoPresent;
          videoTrackSupported=videoSupported;
          videoTrackSelected=videoSelected;
          // Start first-frame monitoring as soon as a video track is selected,
          // not only after the first frame arrives; this catches black/no-frame streams.
          videoFrameMonitoringActive=videoSelected;
        }
        private boolean isCurrent(){
          if(listenerOwner!=MainActivity.this.player)return false;
          MediaItem item=listenerOwner.getCurrentMediaItem();
          return item!=null&&currentMediaId.equals(item.mediaId);
        }
        public void onPlayerError(PlaybackException e){
          if(!isCurrent())return;
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
          recordCurrentSourceFailure();
          advanceToNextSourceAutomatically("当前播放源失效（错误代码 "+e.errorCode+"）");
        }
        public void onPlaybackStateChanged(int st){
          if(!isCurrent())return;
          saveLiveDiagnostic("STATE",st==Player.STATE_IDLE?"IDLE":st==Player.STATE_BUFFERING?"BUFFERING":st==Player.STATE_READY?"READY":st==Player.STATE_ENDED?"ENDED":"STATE_"+st);
          if(st==Player.STATE_BUFFERING){
            cancelPlaybackWatchdog();
            cancelAudioTrackCheck();
            boolean firstBuffering=!sourceWasBuffering;
            if(sourceReadyRecorded&&firstBuffering)recordSourceMetric(currentSourceUrl(),"buffer",0L);
            sourceWasBuffering=true;
            if(firstBuffering)showChannelZapMessage(sourceReadyRecorded?"网络波动，正在缓冲…":"正在连接播放源…");
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
            // Start the frame-stall timer only after the player reports READY.
            lastVideoFrameAt=watchdogLastAdvanceAt;
            schedulePlaybackWatchdog();
            // Run the audio-track/output check only after READY; buffering can
            // legitimately pause audio advancement and must not trigger a false failover.
            scheduleAudioTrackCheck();
            if(selected>=0&&selected<channels.size()){
              saveLastChannel();
              info.setText("正在播放："+channels.get(selected).name+"（源 "+(channels.get(selected).activeSource+1)+"/"+channels.get(selected).backups.size()+"）");
              if(manualSourceSwitch){
                showChannelZapMessage(channels.get(selected).name+"\n已连接第 "+(channels.get(selected).activeSource+1)+" / "+channels.get(selected).backups.size()+" 个源");
                manualSourceSwitch=false;
              }
            }
          }else if(st==Player.STATE_IDLE||st==Player.STATE_ENDED){
            cancelPlaybackWatchdog();
            if(st==Player.STATE_IDLE)cancelBufferingFallback();
            if(st==Player.STATE_ENDED)advanceToNextSourceAutomatically("当前播放流已结束");
          }
        }
      });
    }
    // Explicitly drain the previous live item before assigning another source. This
    // prevents stale audio/renderers from surviving rapid channel/source changes.
    player.stop();
    player.clearMediaItems();
    MediaItem item=new MediaItem.Builder().setUri(c.backups.get(c.activeSource)).setMediaId(currentMediaId).build();
    player.setMediaItem(item);
    player.prepare();
    player.play();
    }catch(RuntimeException e){
      String failedMediaId=currentMediaId;
      recordCurrentSourceFailure();
      releaseLivePlayerSafely();
      info.setText("直播播放器启动异常，正在尝试备用源…");
      if(!failedMediaId.isEmpty())advanceToNextSourceAutomatically("直播播放器启动异常");
    }
  }
  private String currentSourceUrl(){
    if(selected<0||selected>=channels.size())return "";
    Channel c=channels.get(selected);
    return c.activeSource>=0&&c.activeSource<c.backups.size()?c.backups.get(c.activeSource):c.url;
  }
  // Persistent playback observations. READY latency is not the same as a rendered first frame;
  // buffering/error events come from the actual ExoPlayer session, not a URL probe.
  private void recordCurrentSourceFailure(){
    if(sourceFailureRecorded)return;
    sourceFailureRecorded=true;
    String failedUrl=currentSourceUrl();
    recordSourceMetric(failedUrl,"failure",0L);
    // Temporarily cool down a failed endpoint to prevent repeated dead-source loops.
    SourceMemoryStore.coolDown(this,failedUrl,120000L);
  }
  private void applyLastKnownGoodSource(Channel c){
    if(c==null||c.backups==null||c.backups.isEmpty())return;
    String remembered=SourceMemoryStore.getLastGood(this,
        SourceMemoryStore.channelKey(c.name,c.group,c.url));
    if(remembered==null)return;
    int index=c.backups.indexOf(remembered);
    if(index>=0)c.activeSource=index;
  }
  private void recordSourceMetric(String url,String event,long readyMs){
    if(url==null||url.isEmpty())return;
    try{
      android.content.SharedPreferences p=getSharedPreferences("mytv",0);
      org.json.JSONObject all=new org.json.JSONObject(p.getString("source_qoe_v1","{}"));
      String key=Integer.toHexString(url.hashCode())+":"+url.length();
      org.json.JSONObject m=all.optJSONObject(key);if(m==null)m=new org.json.JSONObject();
      if("attempt".equals(event))m.put("attempts",m.optInt("attempts",0)+1);
      else if("failure".equals(event))m.put("failures",m.optInt("failures",0)+1);
      else if("buffer".equals(event))m.put("buffers",m.optInt("buffers",0)+1);
      else if("audioCodecError".equals(event))m.put("audioCodecErrors",m.optInt("audioCodecErrors",0)+1);
      else if("audioSinkError".equals(event))m.put("audioSinkErrors",m.optInt("audioSinkErrors",0)+1);
      else if("videoCodecError".equals(event))m.put("videoCodecErrors",m.optInt("videoCodecErrors",0)+1);
      else if("ready".equals(event)){
        int count=m.optInt("ready",0);long avg=m.optLong("avgReadyMs",0L);
        m.put("ready",count+1);m.put("avgReadyMs",count==0?readyMs:((avg*count)+readyMs)/(count+1));
        m.put("lastSuccess",System.currentTimeMillis());
      }else if("firstFrame".equals(event)){
        int count=m.optInt("firstFrames",0);long avg=m.optLong("avgFirstFrameMs",0L);
        m.put("firstFrames",count+1);
        m.put("avgFirstFrameMs",count==0?readyMs:((avg*count)+readyMs)/(count+1));
        m.put("lastFirstFrame",System.currentTimeMillis());
      }
      m.put("updated",System.currentTimeMillis());all.put(key,m);
      p.edit().putString("source_qoe_v1",all.toString()).apply();
    }catch(Exception ignored){}
  }
  private double observedSourceScore(String url){
    int label=sourceQualityScore(url);
    try{
      String key=Integer.toHexString(url.hashCode())+":"+url.length();
      android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
      org.json.JSONObject measured=new org.json.JSONObject(prefs.getString("source_probe_scores_v1","{}"));
      double probeScore=measured.optDouble(key,0.0);
      org.json.JSONObject m=new org.json.JSONObject(prefs.getString("source_qoe_v1","{}")).optJSONObject(key);
      if(m==null||m.optInt("attempts",0)<2)return probeScore+label*0.01;
      int attempts=Math.max(1,m.optInt("attempts",0));
      int ready=m.optInt("ready",0),fail=m.optInt("failures",0),buffers=m.optInt("buffers",0);
      double successRate=(double)ready/attempts;
      double readyPenalty=Math.min(150.0,m.optLong("avgReadyMs",0L)/150.0);
      // Decoder/sink warnings are weaker evidence than failed playback. Apply only
      // a small capped ranking penalty; never convert them into an automatic failure.
      int codecWarnings=m.optInt("audioCodecErrors",0)+m.optInt("audioSinkErrors",0)+m.optInt("videoCodecErrors",0);
      double codecWarningPenalty=Math.min(60.0,codecWarnings*8.0);
      long lastSuccess=m.optLong("lastSuccess",0L);
      double recentSuccessBonus=0.0;
      if(lastSuccess>0L){
        double ageHours=Math.max(0.0,(System.currentTimeMillis()-lastSuccess)/3600000.0);
        recentSuccessBonus=Math.max(0.0,100.0-ageHours*4.0);
      }
      return probeScore+successRate*1000.0-fail*90.0-buffers*55.0-readyPenalty-codecWarningPenalty+recentSuccessBonus+label*0.01;
    }catch(Exception ignored){return label*0.01;}
  }
  private void sortSourcesByObservedQuality(Channel c){
    if(c==null||c.backups==null||c.backups.size()<2)return;
    String active=c.activeSource>=0&&c.activeSource<c.backups.size()?c.backups.get(c.activeSource):c.url;
    ArrayList<String> ordered=new ArrayList<>(c.backups);
    ordered.sort((a,b)->Double.compare(observedSourceScore(b),observedSourceScore(a)));
    if(ordered.equals(c.backups))return;
    c.backups.clear();c.backups.addAll(ordered);
    int ix=c.backups.indexOf(active);c.activeSource=ix>=0?ix:0;
    persist();
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
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  protected void onStart(){super.onStart();updateNetworkSpeedMonitor();if(resumePlaybackOnStart&&selected>=0&&selected<channels.size()){resumePlaybackOnStart=false;startCurrentSource();}}
  private void setSourceUpdatePlaybackActive(boolean active){
    getSharedPreferences("mytv",0).edit().putBoolean("source_update_playback_active",active).apply();
  }
  protected void onStop(){if(video!=null)video.setKeepScreenOn(false);cancelActiveVodCatalogue();if(networkSpeedUpdater!=null){uiHandler.removeCallbacks(networkSpeedUpdater);networkSpeedUpdater=null;}resumeLiveAfterVod=false;if(vodPlayerDialog!=null&&vodPlayerDialog.isShowing())vodPlayerDialog.dismiss();setSourceUpdatePlaybackActive(false);super.onStop();saveLastChannel();resumePlaybackOnStart=selected>=0&&selected<channels.size();cancelBufferingFallback();cancelPlaybackWatchdog();cancelAudioTrackCheck();if(video!=null)video.setPlayer(null);releaseLivePlayerSafely();if(vodPlayer!=null){vodPlayer.pause();vodPlayer.stop();vodPlayer.clearMediaItems();vodPlayer.release();vodPlayer=null;}}
  protected void onDestroy(){if(video!=null)video.setKeepScreenOn(false);if(vodPlayerDialog!=null&&vodPlayerDialog.isShowing())vodPlayerDialog.dismiss();if(video!=null)video.setPlayer(null);if(vodPlayer!=null){vodPlayer.pause();vodPlayer.stop();vodPlayer.clearMediaItems();vodPlayer.release();vodPlayer=null;}releaseLivePlayerSafely();super.onDestroy();if(pendingSearchRefresh!=null)uiHandler.removeCallbacks(pendingSearchRefresh);cancelPlaybackWatchdog();worker.shutdownNow();posterWorker.shutdownNow();vodWorker.shutdownNow();vodMetadataWorker.shutdownNow();vodSourceWorker.shutdownNow();}
}