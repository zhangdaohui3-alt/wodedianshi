package com.wodedianshi.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.ui.PlayerView;
import java.io.*;
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
  private PopupWindow fullscreenMenuPopup;
  private TextView fullscreenMenuButton;
  private TextView channelZapOverlay;
  private Runnable hideChannelZapOverlay;
  private boolean manualSourceSwitch = false;
  private LinearLayout list;
  private TextView info;
  private EditText search;
  private final ArrayList<Channel> channels = new ArrayList<>();
  private final HashSet<String> favorites = new HashSet<>();
  private final ArrayList<Button> menuButtons = new ArrayList<>();
  // Fast indexes prevent quadratic duplicate scans as channel lists grow.
  private final HashMap<String,Channel> channelsByUrl = new HashMap<>();
  private final HashMap<String,Channel> channelsByIdentity = new HashMap<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService posterWorker = Executors.newFixedThreadPool(3);
  // Keep catalogue orchestration separate from metadata I/O: nested tasks on one pool can starve each other.
  private final ExecutorService vodWorker = Executors.newFixedThreadPool(2);
  private final ExecutorService vodMetadataWorker = Executors.newFixedThreadPool(10);
  private int vodRequestId=0;
  private boolean adultUnlockedThisSession=false;
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
    restore();
    loadBundledHdBackups();
    scheduleAutomaticSourceUpdates();
    makeUi();
  }
  // Apply newly bundled, explicitly identified 1080p sources once on upgrade,
  // including for users whose channel list was already saved by an older build.
  private void loadBundledHdBackups(){
    android.content.SharedPreferences prefs=getSharedPreferences("mytv",0);
    try(InputStream in=getAssets().open("default_channels.m3u")){
      parse(read(in));
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
    prefs.edit().putBoolean("source_quality_order_v2",true).apply();
  }
  private int sourceQualityScore(String source){
    if(source==null)return 0;
    String u=source.toLowerCase(Locale.ROOT);
    // Prefer explicitly labelled resolution, then known high-bitrate public streams.
    if(u.matches(".*(?:1080p|1080i|fullhd|full-hd).*"))return 1200;
    if(u.contains("master_5000")||u.contains("5000k")||u.contains("5000kbps"))return 1150;
    if(u.matches(".*(?:720p|720i|hd720).*"))return 1000;
    if(u.contains("master_4000")||u.contains("4000k")||u.contains("4000kbps"))return 950;
    if(u.contains("master_3000")||u.contains("3000k")||u.contains("3000kbps"))return 900;
    if(u.contains("master_2300")||u.contains("2300k")||u.contains("2300kbps"))return 850;
    if(u.matches(".*(?:576p|576i|480p|480i).*"))return 600;
    if(u.matches(".*(?:360p|240p).*"))return 300;
    return 0;
  }
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  private void styleTvButton(Button b,int normalColor){
    b.setFocusable(true); b.setFocusableInTouchMode(true);
    b.setTextColor(Color.WHITE);
    b.setBackground(makeButtonBg(normalColor,false));
    b.setOnFocusChangeListener((v,focused)->{
      b.setBackground(makeButtonBg(normalColor,focused));
      b.setScaleX(focused?1.04f:1f); b.setScaleY(focused?1.04f:1f);
      b.setElevation(focused?dp(8):0);
    });
  }
  private GradientDrawable makeButtonBg(int color,boolean focused){
    GradientDrawable d=new GradientDrawable(); d.setColor(focused?Color.rgb(22,105,185):color);
    d.setCornerRadius(dp(7));
    d.setStroke(dp(focused?4:1),focused?Color.rgb(255,210,55):Color.rgb(76,91,112));
    return d;
  }
  private void restore(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    favorites.addAll(p.getStringSet("fav",new HashSet<>()));
    try {
      org.json.JSONArray a=new org.json.JSONArray(p.getString("channels","[]"));
      for(int i=0;i<a.length();i++){org.json.JSONObject o=a.getJSONObject(i);Channel c=new Channel(o.getString("name"),o.getString("url"),o.optString("group","")); org.json.JSONArray src=o.optJSONArray("sources"); if(src!=null){c.backups.clear();for(int j=0;j<src.length();j++){String u=src.optString(j);if(u.startsWith("http://")||u.startsWith("https://"))c.backups.add(u);}if(c.backups.isEmpty())c.backups.add(c.url);}channels.add(c);}
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
      // Collapse existing duplicates as well as newly imported entries. Preserve every
      // distinct URL as a backup source and carry favorites to the surviving row.
      LinkedHashMap<String,Channel> compact=new LinkedHashMap<>();
      for(Channel c:channels){
        if(c.backups==null)c.backups=new ArrayList<>();
        if(c.backups.isEmpty()&&c.url!=null&&!c.url.isEmpty())c.backups.add(c.url);
        String key=identity(c.name,c.group,c.url);
        Channel keep=compact.get(key);
        if(keep==null){compact.put(key,c);}
        else{
          for(String source:c.backups)if(source!=null&&!source.isEmpty()&&!keep.backups.contains(source))keep.backups.add(source);
          if(favorites.contains(c.key()))favorites.add(keep.key());
        }
      }
      channels.clear();channels.addAll(compact.values());
      channelsByUrl.clear();channelsByIdentity.clear();
      for(Channel c:channels){
        for(String u:c.backups)channelsByUrl.put(u,c);
        channelsByIdentity.put(identity(c.name,c.group,c.url),c);
      }
    }
  }
  private String canonicalChannelName(String name){
    String value=name==null?"":name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_—–]","");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)[-]?(\\d{1,2}\\+?|NEWS|4K|8K)").matcher(value);
    if(m.find())return "CCTV-"+m.group(1);
    String[] chinese={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
    for(int i=1;i<chinese.length;i++)if(value.contains("中央"+chinese[i]+"套"))return "CCTV-"+i;
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
  // For non-CCTV channels, require the same source host before merging entries.
  // Generic names such as News, Movies or Sports are not globally unique.
  private String identity(String name,String group,String url){
    String canonical=canonicalChannelName(name);
    if(canonical.startsWith("CCTV-"))return canonical+"|cctv";
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
      for(Channel c:channels){org.json.JSONObject o=new org.json.JSONObject();o.put("name",c.name);o.put("url",c.url);o.put("group",c.group);org.json.JSONArray src=new org.json.JSONArray();for(String u:c.backups)src.put(u);o.put("sources",src);a.put(o);}
      getSharedPreferences("mytv",0).edit().putString("channels",a.toString()).putStringSet("fav",new HashSet<>(favorites)).apply();
    } catch(Exception ignored) {}
  }
  private String activeCategory="全部";
  private void makeUi(){
    LinearLayout root=new LinearLayout(this);rootLayout=root;root.setOrientation(0);root.setPadding(dp(10),dp(8),dp(10),dp(8));root.setBackgroundColor(Color.rgb(8,13,24));
    LinearLayout cats=new LinearLayout(this);categoryPane=cats;cats.setOrientation(1);cats.setBackgroundColor(Color.rgb(16,24,40));
    TextView logo=new TextView(this);logo.setText("我的\\n电视");logo.setTextColor(Color.WHITE);logo.setTextSize(23);logo.setGravity(Gravity.CENTER);cats.addView(logo,new LinearLayout.LayoutParams(-1,dp(82)));
    String[] names={"点播","央视","卫视","地方","港澳台","全部","全球频道","国家地区","娱乐频道","国际新闻","体育","天气","教育","中国","收藏","成人频道"};
    LinearLayout catItems=new LinearLayout(this);catItems.setOrientation(1);
    for(String name:names){Button x=new Button(this);x.setText(name);x.setTextColor(Color.WHITE);x.setTextSize(16);x.setMinHeight(dp(48));styleTvButton(x,name.equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40));x.setOnClickListener(v->{if(name.equals("点播")){vodDialog();return;}if(name.equals("成人频道")){requestAdultUnlock();return;}activeCategory=name;favoriteMode=name.equals("收藏");if(name.equals("全球频道"))loadGlobalChannels();if(name.equals("国家地区"))showCountryDialog();if(name.equals("娱乐频道"))loadEntertainmentChannels();if(name.equals("港澳台"))loadRegionChannels();if(name.equals("央视")||name.equals("卫视")||name.equals("地方"))loadChinaChannels();if(name.equals("地方"))showProvinceDialog();for(int i=0;i<catItems.getChildCount();i++){View z=catItems.getChildAt(i);if(z instanceof Button)((Button)z).setBackground(makeButtonBg(((Button)z).getText().toString().equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40),z.hasFocus()));}refresh();focusFirstChannelInMenu();});catItems.addView(x,new LinearLayout.LayoutParams(-1,dp(50)));}
    ScrollView catScroll=new ScrollView(this);catScroll.setFillViewport(false);catScroll.addView(catItems);cats.addView(catScroll,new LinearLayout.LayoutParams(-1,0,1));
    Button settings=new Button(this);settings.setText("设置");settings.setTextColor(Color.WHITE);styleTvButton(settings,Color.rgb(35,45,62));settings.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("设置与管理").setItems(new String[]{"导入 M3U 文件","导入播放列表网址","手动添加频道","导出 M3U 配置","官方平台入口","全球正版电影点播","解码与声音设置","卡顿自动切换备用源","立即更新频道源","后台自动更新："+(getSharedPreferences("mytv",0).getBoolean("auto_source_update",true)?"开启":"关闭"),"查看更新状态"},(d,w)->{if(w==0)chooseFile();else if(w==1)urlDialog();else if(w==2)addDialog();else if(w==3)exportFile();else if(w==4)officialDialog();else if(w==5)vodDialog();else if(w==6)decoderSettingsDialog();else if(w==7)bufferingSwitchSettingsDialog();else if(w==8)startManualSourceUpdate();else if(w==9)toggleAutomaticSourceUpdates();else showSourceUpdateStatus();}).show());cats.addView(settings,new LinearLayout.LayoutParams(-1,dp(58)));
    LinearLayout channelPane=new LinearLayout(this);this.channelPane=channelPane;channelPane.setOrientation(1);channelPane.setPadding(dp(8),0,dp(8),0);channelPane.setBackgroundColor(Color.rgb(19,29,47));
    TextView title=new TextView(this);title.setText("频道列表");title.setTextColor(Color.WHITE);title.setTextSize(19);title.setPadding(dp(8),dp(10),0,dp(8));channelPane.addView(title);
    search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);search.setHintTextColor(Color.LTGRAY);search.setHint("搜索频道");channelPane.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
    list=new LinearLayout(this);list.setOrientation(1);channelScroll=new ScrollView(this);channelScroll.setFocusable(false);channelScroll.addView(list);channelPane.addView(channelScroll,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout main=new LinearLayout(this);mainPane=main;main.setOrientation(1);main.setPadding(dp(10),0,0,0);
    info=new TextView(this);playbackInfo=info;info.setText("欢迎使用我的电视\\n请从左侧选择栏目，或在设置中导入你有权使用的直播源。");info.setTextColor(Color.WHITE);info.setTextSize(16);info.setPadding(dp(8),dp(8),dp(8),dp(8));main.addView(info,new LinearLayout.LayoutParams(-1,dp(62)));
    video=new PlayerView(this);// 直播采用实时电视控制：不显示进度条、快退/快进和暂停控制；音量仍由电视遥控器系统音量键控制。
    video.setUseController(false);video.setFocusable(true);video.setFocusableInTouchMode(true);video.setBackgroundColor(Color.BLACK);fullscreenMenuButton=new TextView(this);fullscreenMenuButton.setText("☰ 频道选择");fullscreenMenuButton.setTextColor(Color.WHITE);fullscreenMenuButton.setTextSize(16);fullscreenMenuButton.setGravity(Gravity.CENTER);fullscreenMenuButton.setPadding(dp(14),dp(9),dp(14),dp(9));fullscreenMenuButton.setBackground(makeButtonBg(Color.rgb(20,35,58),false));fullscreenMenuButton.setFocusable(true);fullscreenMenuButton.setClickable(true);fullscreenMenuButton.setElevation(dp(12));fullscreenMenuButton.setOnClickListener(v->showFullscreenChannelMenu());video.addView(fullscreenMenuButton,new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.LEFT));fullscreenMenuButton.setVisibility(View.GONE);channelZapOverlay=new TextView(this);channelZapOverlay.setTextColor(Color.WHITE);channelZapOverlay.setTextSize(23);channelZapOverlay.setTypeface(null,android.graphics.Typeface.BOLD);channelZapOverlay.setPadding(dp(18),dp(10),dp(18),dp(10));GradientDrawable zapBg=new GradientDrawable();zapBg.setColor(0xDD101A2A);zapBg.setCornerRadius(dp(9));zapBg.setStroke(dp(1),0xFF6EA8E8);channelZapOverlay.setBackground(zapBg);channelZapOverlay.setElevation(dp(16));channelZapOverlay.setVisibility(View.GONE);FrameLayout.LayoutParams zapParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);zapParams.topMargin=dp(24);video.addView(channelZapOverlay,zapParams);main.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(cats,new LinearLayout.LayoutParams(dp(104),-1));root.addView(channelPane,new LinearLayout.LayoutParams(dp(250),-1));root.addView(main,new LinearLayout.LayoutParams(0,-1,1));setContentView(root);
    search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pendingSearchRefresh!=null)uiHandler.removeCallbacks(pendingSearchRefresh);pendingSearchRefresh=()->refresh();uiHandler.postDelayed(pendingSearchRefresh,220);}public void afterTextChanged(android.text.Editable e){}});
    refresh();if(!channels.isEmpty())info.setText("已载入 "+channels.size()+" 个频道 · 选择频道开始播放");restoreLastChannel();
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
    int current=prefs.getInt("decoder_mode",1);
    String[] modes={"自动解码（系统选择）","兼容解码（启用解码器回退，推荐无声时尝试）"};
    new AlertDialog.Builder(this).setTitle("解码与声音设置").setSingleChoiceItems(modes,current,(dialog,which)->{
      prefs.edit().putInt("decoder_mode",which).apply();
      dialog.dismiss();
      if(selected>=0&&selected<channels.size()){
        int channel=selected;
        if(player!=null){player.release();player=null;video.setPlayer(null);}
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
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    if(player!=null)player.pause();
    LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(dp(16),dp(10),dp(16),dp(10));panel.setBackgroundColor(Color.rgb(12,20,34));
    TextView intro=new TextView(this);intro.setText("开放授权点播 · 电影与纪录片\n仅收录可识别开放授权声明且具有 MP4 文件的内容；实际可播放性受原始档案、地区和网络影响。");intro.setTextColor(Color.WHITE);intro.setTextSize(14);intro.setPadding(dp(4),dp(4),dp(4),dp(8));panel.addView(intro);
    LinearLayout filters=new LinearLayout(this);filters.setOrientation(0);filters.setGravity(Gravity.CENTER_VERTICAL);
    String[] categories={"播放记录","精选电影","中国电影","中国经典影像","华语经典","中国动画","中国纪录片","世界公版电影","欧美经典","默片经典","纪录片","科学纪录片","NASA影像","动画","喜剧","科幻","剧情","短片"};
    HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);categoryScroll.addView(filters);
    for(String category:categories){
      Button b=new Button(this);b.setText(category);b.setTextSize(13);b.setAllCaps(false);styleTvButton(b,Color.rgb(35,55,78));
      filters.addView(b,new LinearLayout.LayoutParams(dp(112),dp(48)));
      b.setOnClickListener(v->{if("播放记录".equals(category))loadVodHistory(panel);else loadVodCatalogue(category,panel);});
    }
    panel.addView(categoryScroll,new LinearLayout.LayoutParams(-1,dp(50)));
    LinearLayout searchRow=new LinearLayout(this);searchRow.setOrientation(0);
    EditText query=new EditText(this);query.setSingleLine(true);query.setTextColor(Color.WHITE);query.setHintTextColor(Color.LTGRAY);query.setHint("搜索中文片名、英文片名或原名");searchRow.addView(query,new LinearLayout.LayoutParams(0,dp(48),1));
    Button searchButton=new Button(this);searchButton.setText("搜索");searchButton.setAllCaps(false);styleTvButton(searchButton,Color.rgb(26,100,160));searchRow.addView(searchButton,new LinearLayout.LayoutParams(dp(100),dp(48)));
    panel.addView(searchRow);
    TextView status=new TextView(this);status.setText("正在载入精选影片…");status.setTextColor(Color.LTGRAY);status.setTextSize(13);status.setPadding(dp(4),dp(8),dp(4),dp(8));panel.addView(status);
    LinearLayout results=new LinearLayout(this);results.setOrientation(1);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(results);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("我的电视 · 点播").setView(panel).setNegativeButton("关闭",null).create();
    searchButton.setOnClickListener(v->{String q=query.getText().toString().trim();if(!q.isEmpty())loadVodCatalogue(q,panel,status,results);});
    loadVodCatalogue("精选电影",panel,status,results);
    dialog.setOnDismissListener(d->{
      if(resumeLiveAfterVod&&player!=null){
        player.play();
        if(player.getPlaybackState()==Player.STATE_BUFFERING)scheduleBufferingFallback();
        else if(player.getPlaybackState()==Player.STATE_READY){watchdogLastPosition=player.getCurrentPosition();watchdogLastAdvanceAt=android.os.SystemClock.elapsedRealtime();schedulePlaybackWatchdog();}
      }
      resumeLiveAfterVod=false;
    });
    dialog.setOnShowListener(d->{
      if(dialog.getWindow()!=null){
        dialog.getWindow().setLayout(-1,-1);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.rgb(12,20,34)));
        dialog.getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
      }
    });
    dialog.show();
  }
  private void loadVodCatalogue(String category,LinearLayout panel){
    if(panel.getChildCount()<5)return;
    View statusView=panel.getChildAt(2);
    View scrollView=panel.getChildAt(3);
    // Current layout uses intro, category row, search row, status, results scroll.
    statusView=panel.getChildAt(3);scrollView=panel.getChildAt(4);
    if(statusView instanceof TextView && scrollView instanceof ScrollView){
      View child=((ScrollView)scrollView).getChildAt(0);
      if(child instanceof LinearLayout)loadVodCatalogue(category,panel,(TextView)statusView,(LinearLayout)child);
    }
  }
  private String vodCacheKey(String category){
    return "vod_cache_v2_"+Integer.toHexString(category.hashCode());
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
  private void writeVodCache(String category,ArrayList<VodItem> items){
    try{
      org.json.JSONArray a=new org.json.JSONArray();
      for(VodItem item:items){
        org.json.JSONObject o=new org.json.JSONObject();o.put("title",item.title);o.put("id",item.identifier);o.put("url",item.url);o.put("license",item.license);a.put(o);
      }
      getSharedPreferences("mytv",0).edit().putString(vodCacheKey(category),a.toString()).putLong(vodCacheTimeKey(category),System.currentTimeMillis()).apply();
    }catch(Exception ignored){}
  }
  private void renderVodItems(String category,ArrayList<VodItem> items,TextView status,LinearLayout results,boolean cached){
    results.removeAllViews();
    status.setText("「"+category+"」 · "+items.size()+" 部"+(cached?"（缓存秒开，正在后台检查更新）":"符合授权筛选的影片"));
    LinearLayout row=null;int count=0;
    for(VodItem item:items){
      if(count%3==0){row=new LinearLayout(this);row.setOrientation(0);row.setGravity(Gravity.TOP);results.addView(row,new LinearLayout.LayoutParams(-1,-2));}
      LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(6),dp(6),dp(6),dp(8));card.setBackground(makeButtonBg(Color.rgb(25,39,59),false));card.setFocusable(true);card.setClickable(true);
      card.setOnFocusChangeListener((v,focused)->card.setBackground(makeButtonBg(focused?Color.rgb(34,83,128):Color.rgb(25,39,59),focused)));
      ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackgroundColor(Color.rgb(43,61,82));poster.setImageResource(android.R.drawable.ic_media_play);card.addView(poster,new LinearLayout.LayoutParams(-1,dp(112)));loadVodPoster(poster,item.identifier);
      TextView title=new TextView(this);title.setText(item.title);title.setTextColor(Color.WHITE);title.setTextSize(14);title.setMaxLines(2);title.setPadding(dp(4),dp(7),dp(4),dp(2));card.addView(title);
      TextView meta=new TextView(this);meta.setText("开放授权 · 可尝试播放");meta.setTextColor(Color.rgb(154,190,220));meta.setTextSize(11);meta.setPadding(dp(4),0,dp(4),0);card.addView(meta);
      row.addView(card,new LinearLayout.LayoutParams(0,-2,1));card.setOnClickListener(v->playVodInside(item));count++;
    }
    if(row!=null&&count%3!=0){for(int i=count%3;i<3;i++)row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(10),1));}
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
    if(panel==null||panel.getChildCount()<5)return;
    View statusView=panel.getChildAt(3),scrollView=panel.getChildAt(4);
    if(statusView instanceof TextView&&scrollView instanceof ScrollView){View child=((ScrollView)scrollView).getChildAt(0);if(child instanceof LinearLayout)loadVodHistory((TextView)statusView,(LinearLayout)child);}
  }
  private void loadVodHistory(TextView status,LinearLayout results){
    ArrayList<org.json.JSONObject> history=readVodHistory();results.removeAllViews();
    LinearLayout tools=new LinearLayout(this);tools.setOrientation(0);
    Button clear=new Button(this);clear.setText("清空播放记录");clear.setAllCaps(false);styleTvButton(clear,Color.rgb(125,48,48));tools.addView(clear,new LinearLayout.LayoutParams(-2,dp(44)));
    clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空播放记录？").setMessage("将删除所有点播记录和续播进度。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{getSharedPreferences("mytv",0).edit().remove(VOD_HISTORY_KEY).apply();loadVodHistory(status,results);}).show());
    results.addView(tools);
    status.setText(history.isEmpty()?"还没有点播记录。观看过的影片会自动保存在这里。":"最近观看 · "+history.size()+" 部（点击继续观看，长按删除）");
    if(history.isEmpty())return;
    for(org.json.JSONObject o:history){
      String title=o.optString("title","未命名影片"),id=o.optString("id",""),url=o.optString("url",""),license=o.optString("license","");
      long pos=o.optLong("position",0),duration=o.optLong("duration",0);
      LinearLayout itemRow=new LinearLayout(this);itemRow.setOrientation(1);itemRow.setPadding(dp(12),dp(10),dp(12),dp(10));itemRow.setFocusable(true);itemRow.setFocusableInTouchMode(true);itemRow.setClickable(true);itemRow.setBackground(makeButtonBg(Color.rgb(25,39,59),false));
      TextView name=new TextView(this);name.setText(title);name.setTextColor(Color.WHITE);name.setTextSize(16);itemRow.addView(name);
      TextView detail=new TextView(this);detail.setText(pos>0?"上次看到 "+formatVodTime(pos)+(duration>0?" / "+formatVodTime(duration):"")+" · 点击续播":"已观看 · 点击播放");detail.setTextColor(Color.rgb(154,190,220));detail.setTextSize(12);itemRow.addView(detail);
      // Strong D-pad focus feedback: blue fill, yellow outline, slight scale and elevation.
      itemRow.setOnFocusChangeListener((v,focused)->{
        itemRow.setBackground(makeButtonBg(Color.rgb(25,39,59),focused));
        itemRow.setScaleX(focused?1.025f:1f);itemRow.setScaleY(focused?1.025f:1f);
        itemRow.setElevation(focused?dp(7):0);
        name.setTextColor(focused?Color.rgb(255,235,170):Color.WHITE);
        detail.setTextColor(focused?Color.WHITE:Color.rgb(154,190,220));
      });
      itemRow.setOnClickListener(v->playVodInside(new VodItem(title,id,url,license)));
      itemRow.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("删除播放记录").setMessage(title).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{ArrayList<org.json.JSONObject> keep=readVodHistory();org.json.JSONArray a=new org.json.JSONArray();for(org.json.JSONObject x:keep)if(!id.equals(x.optString("id","")))a.put(x);getSharedPreferences("mytv",0).edit().putString(VOD_HISTORY_KEY,a.toString()).apply();loadVodHistory(status,results);}).show();return true;});
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);results.addView(itemRow,lp);
    }
  }
  private String formatVodTime(long ms){
    long seconds=Math.max(0,ms/1000),hours=seconds/3600,minutes=(seconds%3600)/60,remain=seconds%60;
    return hours>0?String.format(Locale.US,"%d:%02d:%02d",hours,minutes,remain):String.format(Locale.US,"%02d:%02d",minutes,remain);
  }
  private VodItem fetchAuthorizedVod(org.json.JSONObject doc){
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
      for(int j=0;j<files.length();j++){
        org.json.JSONObject file=files.optJSONObject(j);if(file==null)continue;
        String name=file.optString("name",""),format=file.optString("format","").toLowerCase(Locale.US);
        if(name.toLowerCase(Locale.US).endsWith(".mp4")&&(format.contains("mpeg4")||format.contains("h.264")||format.contains("mpeg"))){fileName=name;break;}
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
      while(keys.hasNext()&&items.size()<36){
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
  private void loadVodCatalogue(String category,LinearLayout panel,TextView status,LinearLayout results){
    final int request=++vodRequestId;
    ArrayList<VodItem> cached=readVodCache(category);
    if(!cached.isEmpty())renderVodItems(category,cached,status,results,true);
    else{status.setText("正在快速检索「"+category+"」…");results.removeAllViews();}
    // A fresh cache is already a complete result: show it immediately and avoid
    // repeating dozens of metadata requests every time the user reopens this category.
    if(!cached.isEmpty()&&isVodCacheFresh(category)){
      status.setText("「"+category+"」 · "+cached.size()+" 部（高速缓存，稍后可重新检索）");
      return;
    }
    vodWorker.execute(()->{
      ArrayList<VodItem> found=new ArrayList<>();String error=null;
      try{
        String q="mediatype:movies";
        if("精选电影".equals(category))q="collection:feature_films AND mediatype:movies";
        else if("中国电影".equals(category)||"中国经典影像".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:中国 OR title:中文 OR title:华语 OR subject:Chinese OR subject:China)";
        else if("华语经典".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR subject:Chinese)";
        else if("中国动画".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:中国 OR title:中文) AND (subject:animation OR subject:cartoons OR title:动画)";
        else if("中国纪录片".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho OR title:中国 OR title:中文) AND (subject:documentary OR title:纪录片)";
        else if("国产短片".equals(category))q+=" AND (language:Chinese OR language:chi OR language:zho) AND (subject:short film OR title:短片)";
        else if("纪录片".equals(category)||"科学纪录片".equals(category))q+=" AND subject:documentary";
        else if("NASA影像".equals(category))q="mediatype:movies AND (collection:nasa OR creator:NASA)";
        else if("动画".equals(category))q+=" AND (subject:animation OR subject:cartoons)";
        else if("喜剧".equals(category))q+=" AND subject:comedy";
        else if("科幻".equals(category))q+=" AND (subject:science_fiction OR subject:science-fiction)";
        else if("剧情".equals(category))q+=" AND subject:drama";
        else if("欧美经典".equals(category))q+=" AND (language:English OR language:eng) AND (collection:feature_films OR subject:classic)";
        else if("默片经典".equals(category))q+=" AND (subject:silent film OR title:silent)";
        else if("短片".equals(category))q+=" AND (subject:short film OR subject:shorts)";
        else if(!category.equals("世界公版电影")&&!category.equals("精选电影"))q+=" AND title:("+category.replace(" "," AND ")+")";
        String api="https://archive.org/advancedsearch.php?q="+URLEncoder.encode(q,"UTF-8")+"&fl[]=identifier&fl[]=title&rows=64&page=1&output=json&sort[]=downloads+desc";
        org.json.JSONObject root=new org.json.JSONObject(httpGetFast(api));
        org.json.JSONArray docs=root.getJSONObject("response").getJSONArray("docs");
        // Metadata lookups run on their own bounded pool (10 parallel requests).
        // The UI/catalogue workers never wait on tasks queued behind themselves.
        ExecutorCompletionService<VodItem> completion=new ExecutorCompletionService<>(vodMetadataWorker);
        int submitted=0,completed=0;
        ArrayList<Future<VodItem>> pendingVodChecks=new ArrayList<>();
        for(int i=0;i<docs.length()&&submitted<64;i++){
          final org.json.JSONObject doc=docs.getJSONObject(i);
          pendingVodChecks.add(completion.submit(()->fetchAuthorizedVod(doc)));submitted++;
        }
        long deadline=android.os.SystemClock.elapsedRealtime()+22000L;
        while(completed<submitted&&found.size()<30){
          long remaining=deadline-android.os.SystemClock.elapsedRealtime();
          if(remaining<=0)break;
          Future<VodItem> future=completion.poll(Math.min(1200L,remaining),TimeUnit.MILLISECONDS);
          if(future==null)continue;
          completed++;
          try{VodItem item=future.get();if(item!=null)found.add(item);}catch(Exception ignored){}
        }
        for(Future<VodItem> future:pendingVodChecks)if(!future.isDone())future.cancel(true);
        if(("世界公版电影".equals(category)||"中国电影".equals(category)||"中国经典影像".equals(category))&&found.size()<8){
          ArrayList<VodItem> commons=fetchCommonsPublicDomainVod(!"世界公版电影".equals(category));
          HashSet<String> seen=new HashSet<>();for(VodItem item:found)seen.add(item.identifier);
          for(VodItem item:commons)if(seen.add(item.identifier)&&found.size()<48)found.add(item);
        }
      }catch(Exception e){error=e.getMessage();}
      final String err=error;
      final ArrayList<VodItem> result=new ArrayList<>(found);
      uiHandler.post(()->{
        if(isFinishing()||request!=vodRequestId)return;
        if(!result.isEmpty()){
          writeVodCache(category,result);
          renderVodItems(category,result,status,results,false);
        }else if(cached.isEmpty()){
          status.setText(err==null?"暂时没有检索到符合授权条件且有 MP4 文件的影片。可稍后重试。":"点播目录暂时无法连接："+err);
        }else{
          status.setText("显示上次缓存的 "+cached.size()+" 部影片；本次网络更新暂不可用。");
        }
      });
    });
  }
  private String httpGetFast(String address)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setConnectTimeout(4500);c.setReadTimeout(7000);c.setRequestProperty("User-Agent","MyTV/2.0 Android TV");
    c.setRequestProperty("Accept","application/json,*/*");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toString("UTF-8");
    }finally{c.disconnect();}
  }
  private void loadVodPoster(ImageView target,String identifier){
    target.setTag(identifier);
    posterWorker.execute(()->{
      Bitmap bitmap=null;HttpURLConnection connection=null;
      try{
        connection=(HttpURLConnection)new URL("https://archive.org/services/img/"+URLEncoder.encode(identifier,"UTF-8")).openConnection();
        connection.setConnectTimeout(2500);connection.setReadTimeout(3500);connection.setInstanceFollowRedirects(true);
        try(InputStream in=connection.getInputStream()){bitmap=BitmapFactory.decodeStream(in);}
      }catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();}
      final Bitmap image=bitmap;
      if(image!=null)uiHandler.post(()->{if(identifier.equals(target.getTag()))target.setImageBitmap(image);});
    });
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
  private void startVodPlayback(VodItem item,long startPosition){
    activeVodItem=item;saveVodHistory(item,startPosition,0);
    Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);vodPlayerDialog=d;
    final SeekBar[] vodSeekHolder=new SeekBar[1];
    final LinearLayout[] vodControlsHolder=new LinearLayout[1];
    final Runnable[] hideVodSeekTask=new Runnable[1];
    FrameLayout stage=new FrameLayout(this){
      private void revealVodSeekBar(){
        boolean controlsWereHidden=vodControlsHolder[0]!=null&&vodControlsHolder[0].getVisibility()!=View.VISIBLE;
        if(vodControlsHolder[0]!=null){vodControlsHolder[0].setVisibility(View.VISIBLE);vodControlsHolder[0].bringToFront();}
        if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.VISIBLE);
        if(hideVodSeekTask[0]!=null)removeCallbacks(hideVodSeekTask[0]);
        hideVodSeekTask[0]=()->{
          if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);
          if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);
        };
        postDelayed(hideVodSeekTask[0],5000);
        if(controlsWereHidden&&vodSeekHolder[0]!=null)vodSeekHolder[0].requestFocus();
      }
      @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN)revealVodSeekBar();
        return super.dispatchKeyEvent(event);
      }
      @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(event.getAction()==android.view.MotionEvent.ACTION_DOWN)revealVodSeekBar();
        return super.dispatchTouchEvent(event);
      }
    };stage.setBackgroundColor(Color.BLACK);stage.setFocusable(true);stage.setFocusableInTouchMode(true);stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    PlayerView pv=new PlayerView(this);pv.setUseController(false);pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);pv.setFocusable(false);pv.setFocusableInTouchMode(false);pv.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
    stage.addView(pv,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(10),dp(4),dp(10),dp(4));controls.setBackgroundColor(0xDD101820);
    TextView playPauseIndicator=new TextView(this);playPauseIndicator.setText("Ⅱ");playPauseIndicator.setTextColor(Color.WHITE);playPauseIndicator.setTextSize(14);playPauseIndicator.setGravity(Gravity.CENTER);playPauseIndicator.setPadding(dp(7),dp(2),dp(7),dp(2));playPauseIndicator.setBackgroundColor(0x99000000);playPauseIndicator.setFocusable(false);
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
    LinearLayout seekContainer=new LinearLayout(this);seekContainer.setOrientation(LinearLayout.HORIZONTAL);seekContainer.setGravity(Gravity.CENTER_VERTICAL);seekContainer.addView(playPauseIndicator,new LinearLayout.LayoutParams(dp(38),dp(44)));seekContainer.addView(vodSeekBar,new LinearLayout.LayoutParams(0,dp(48),1));
    controls.addView(vodPositionLabel,new LinearLayout.LayoutParams(dp(52),-2));
    controls.addView(seekContainer,new LinearLayout.LayoutParams(0,dp(48),1));
    controls.addView(vodDurationLabel,new LinearLayout.LayoutParams(dp(52),-2));
    vodControlsHolder[0]=controls;
    FrameLayout.LayoutParams controlsParams=new FrameLayout.LayoutParams(-1,dp(62),Gravity.BOTTOM);controlsParams.setMargins(dp(12),0,dp(12),dp(12));stage.addView(controls,controlsParams);vodSeekHolder[0]=vodSeekBar;
    hideVodSeekTask[0]=()->{if(vodSeekHolder[0]!=null)vodSeekHolder[0].setVisibility(View.GONE);if(vodControlsHolder[0]!=null)vodControlsHolder[0].setVisibility(View.GONE);};stage.postDelayed(hideVodSeekTask[0],5000);
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
    FrameLayout.LayoutParams headingParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);stage.addView(heading,headingParams);
    Button back=new Button(this);back.setText("返回目录");back.setAllCaps(false);back.setTextSize(14);back.setOnClickListener(v->d.dismiss());FrameLayout.LayoutParams backParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);backParams.topMargin=dp(4);backParams.rightMargin=dp(8);stage.addView(back,backParams);
    d.setContentView(stage);d.setOnDismissListener(x->{if(hideVodSeekTask[0]!=null)stage.removeCallbacks(hideVodSeekTask[0]);vodSeekHolder[0]=null;vodControlsHolder[0]=null;if(vodProgressSaver!=null){uiHandler.removeCallbacks(vodProgressSaver);vodProgressSaver=null;}if(vodUiUpdater!=null){uiHandler.removeCallbacks(vodUiUpdater);vodUiUpdater=null;}vodSeekBar=null;vodPositionLabel=null;vodDurationLabel=null;if(vodPlayer!=null){if(activeVodItem!=null)saveVodHistory(activeVodItem,vodPlayer.getCurrentPosition(),vodPlayer.getDuration());vodPlayer.release();vodPlayer=null;}activeVodItem=null;getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(vodPlayerDialog==d)vodPlayerDialog=null;});
    try{
      DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 MyTV/1.17").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(30000);
      vodPlayer=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build();
      pv.setPlayer(vodPlayer);MediaItem media=MediaItem.fromUri(item.url);vodPlayer.setMediaItem(media);vodPlayer.prepare();if(startPosition>0)vodPlayer.seekTo(startPosition);vodPlayer.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED&&activeVodItem!=null)saveVodHistory(activeVodItem,0,vodPlayer==null?0:vodPlayer.getDuration());}});vodPlayer.play();vodProgressSaver=new Runnable(){@Override public void run(){if(vodPlayer!=null&&activeVodItem!=null){saveVodHistory(activeVodItem,vodPlayer.getCurrentPosition(),vodPlayer.getDuration());uiHandler.postDelayed(this,5000);}}};uiHandler.postDelayed(vodProgressSaver,5000);
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
  private void showProvinceDialog(){
    String[] provinces={"全部地方台","北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    new AlertDialog.Builder(this).setTitle("选择省级地区").setItems(provinces,(dialog,which)->{
      favoriteMode=false;
      if(which==0){activeCategory="地方";refresh();info.setText("显示全部地方台");}
      else showCityDialog(provinces[which]);
    }).setNegativeButton("取消",null).show();
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
    new AlertDialog.Builder(this).setTitle(province+" · 选择城市").setItems(choices,(d,w)->{
      favoriteMode=false;
      if(w==0){activeCategory=province;info.setText("正在显示："+province+" 地方频道");}
      else{activeCategory="城市:"+cities[w-1];info.setText("正在筛选："+province+" · "+cities[w-1]+" 频道");}
      refresh();
    }).setNegativeButton("返回",null).show();
  }
  private String adultPasswordHash(String value){
    try{
      java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
      byte[] bytes=md.digest(value.getBytes("UTF-8"));StringBuilder out=new StringBuilder();
      for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&0xff));
      return out.toString();
    }catch(Exception e){return value;}
  }
  private boolean adultPasswordMatches(String value){
    String saved=getSharedPreferences("mytv",0).getString("adult_password_hash","");
    return saved.isEmpty()?"0000".equals(value):saved.equals(adultPasswordHash(value));
  }
  private void requestAdultUnlock(){
    if(adultUnlockedThisSession){showAdultCategories();return;}
    EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);input.setHint("请输入成人频道密码");
    new AlertDialog.Builder(this).setTitle("成人频道 · 密码验证").setMessage("默认密码：0000").setView(input)
      .setPositiveButton("进入",(d,w)->{String p=input.getText().toString();if(adultPasswordMatches(p)){adultUnlockedThisSession=true;showAdultCategories();}else new AlertDialog.Builder(this).setTitle("密码错误").setMessage("密码不正确，请重试。").setPositiveButton("确定",null).show();})
      .setNegativeButton("取消",null).show();
  }
  private void showAdultCategories(){
    String[] categories={"全部成人频道","成人电影","午夜频道","成人纪录片","成人动画","其他成人内容"};
    new AlertDialog.Builder(this).setTitle("成人频道分类").setItems(categories,(d,w)->{
      activeCategory=categories[w];favoriteMode=false;
      if(search!=null)search.setText("");
      refresh();focusFirstChannelInMenu();
    }).setNeutralButton("修改密码",(d,w)->changeAdultPassword()).setNegativeButton("关闭",null).show();
  }
  private void changeAdultPassword(){
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(20),dp(8),dp(20),dp(4));
    EditText oldPass=new EditText(this);oldPass.setSingleLine(true);oldPass.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);oldPass.setHint("当前密码");box.addView(oldPass);
    EditText newPass=new EditText(this);newPass.setSingleLine(true);newPass.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);newPass.setHint("新密码（4-12位数字）");box.addView(newPass);
    EditText confirm=new EditText(this);confirm.setSingleLine(true);confirm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);confirm.setHint("再次输入新密码");box.addView(confirm);
    new AlertDialog.Builder(this).setTitle("修改成人频道密码").setView(box).setPositiveButton("保存", (d,w)->{
      String old=oldPass.getText().toString(),next=newPass.getText().toString();
      if(!adultPasswordMatches(old)){toast("当前密码错误");return;}
      if(!next.matches("[0-9]{4,12}")){toast("新密码必须为4至12位数字");return;}
      if(!next.equals(confirm.getText().toString())){toast("两次输入的新密码不一致");return;}
      getSharedPreferences("mytv",0).edit().putString("adult_password_hash",adultPasswordHash(next)).apply();toast("成人频道密码已修改");
    }).setNegativeButton("取消",null).show();
  }
  private boolean isAdultChannel(Channel c){
    String x=(c.name+" "+c.group).toLowerCase(Locale.ROOT).replace(" ","").replace("_","").replace("-","");
    // Imported playlist labels only; never fabricate channel entries or URLs.
    String[] markers={"成人","成人电影","成人频道","成人台","成人专区","限制级","情色","午夜","深夜",
        "18+","18plus","r18","nc17","tvma","ratedr","xxx","adult","mature","erotic","成人向","大人向"};
    for(String marker:markers)if(x.contains(marker.replace(" ","")))return true;
    return false;
  }
  private boolean categoryMatch(Channel c,String cat){
    String x=(c.name+" "+c.group).toLowerCase(Locale.ROOT);
    String g=c.group.toLowerCase(Locale.ROOT);
    if(cat.equals("成人频道")||cat.equals("全部成人频道"))return isAdultChannel(c);
    if(cat.equals("午夜频道"))return isAdultChannel(c)&&(x.contains("午夜")||x.contains("深夜")||x.contains("midnight")||x.contains("late night")||x.contains("after dark"));
    if(cat.equals("成人电影"))return isAdultChannel(c)&&(x.contains("电影")||x.contains("movie")||x.contains("film"));
    if(cat.equals("成人纪录片"))return isAdultChannel(c)&&(x.contains("纪录")||x.contains("documentary"));
    if(cat.equals("成人动画"))return isAdultChannel(c)&&(x.contains("动画")||x.contains("anime")||x.contains("animation"));
    if(cat.equals("其他成人内容"))return isAdultChannel(c)&&!(x.contains("电影")||x.contains("movie")||x.contains("film")||x.contains("午夜")||x.contains("深夜")||x.contains("midnight")||x.contains("late night")||x.contains("after dark")||x.contains("纪录")||x.contains("documentary")||x.contains("动画")||x.contains("anime")||x.contains("animation"));
    if(cat.startsWith("城市:"))return x.contains(cat.substring(3).toLowerCase(Locale.ROOT));
    String[] provinceNames={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    for(String province:provinceNames)if(cat.equals(province))return x.contains(province.toLowerCase(Locale.ROOT));
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
    if(cat.equals("地方")){
      if(x.contains("卫视")||g.contains("卫视")||g.contains("satellite"))return false;
      String[] regions={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆","beijing","tianjin","hebei","shanxi","inner mongolia","liaoning","jilin","heilongjiang","shanghai","jiangsu","zhejiang","anhui","fujian","jiangxi","shandong","henan","hubei","hunan","guangdong","guangxi","hainan","chongqing","sichuan","guizhou","yunnan","tibet","shaanxi","gansu","qinghai","ningxia","xinjiang"};
      if(g.contains("地方")||g.contains("local"))return true;
      for(String region:regions)if(x.contains(region))return true;
      return false;
    }
    return true;
  }
  private void refresh(){
    if(list==null||search==null)return;
    int previousFocusedIndex=-1;
    View currentFocus=rootLayout==null?null:rootLayout.findFocus();
    if(currentFocus!=null&&currentFocus.getTag() instanceof Integer)previousFocusedIndex=(Integer)currentFocus.getTag();
    list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);
    final boolean adultCategory=activeCategory.startsWith("成人");
    if(adultCategory){
      TextView adultHeader=new TextView(this);
      adultHeader.setText("成人频道 · "+activeCategory+"\\n只显示你导入且有权使用的播放源；可在这里直接添加或更新列表。");
      adultHeader.setTextColor(Color.WHITE);adultHeader.setTextSize(14);
      adultHeader.setPadding(dp(10),dp(10),dp(10),dp(10));
      list.addView(adultHeader,new LinearLayout.LayoutParams(-1,-2));
      LinearLayout adultActions=new LinearLayout(this);adultActions.setOrientation(0);
      Button adultUrl=new Button(this);adultUrl.setText("添加播放列表网址");adultUrl.setAllCaps(false);styleTvButton(adultUrl,Color.rgb(26,100,160));adultUrl.setOnClickListener(v->urlDialog());
      Button adultFile=new Button(this);adultFile.setText("导入 M3U 文件");adultFile.setAllCaps(false);styleTvButton(adultFile,Color.rgb(35,55,78));adultFile.setOnClickListener(v->chooseFile());
      adultActions.addView(adultUrl,new LinearLayout.LayoutParams(0,dp(52),1));
      adultActions.addView(adultFile,new LinearLayout.LayoutParams(0,dp(52),1));
      list.addView(adultActions,new LinearLayout.LayoutParams(-1,dp(56)));
    }
    final int maxRows=120;int shown=0,totalMatches=0;
    ArrayList<Integer> visibleIndexes=new ArrayList<>();
    synchronized(channels){
      for(int i=0;i<channels.size();i++){
        Channel c=channels.get(i);
        if(favoriteMode&&!favorites.contains(c.key()))continue;
        if(!activeCategory.equals("全部")&&!activeCategory.equals("收藏")&&!categoryMatch(c,activeCategory))continue;
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
        b.setTextSize(16);b.setMinHeight(dp(52));styleTvButton(b,Color.rgb(35,45,62));b.setOnClickListener(v->play(ix));
        b.setOnLongClickListener(v->{new AlertDialog.Builder(this).setItems(new String[]{favorites.contains(c.key())?"取消收藏":"加入收藏","查看/选择备用源（"+c.backups.size()+"个）","删除频道"},(d,w)->{if(w==0){if(!favorites.add(c.key()))favorites.remove(c.key());}else if(w==1){String[] opts=new String[c.backups.size()];for(int j=0;j<opts.length;j++)opts[j]="播放源 "+(j+1)+(j==0?"（主源）":"（备用）");new AlertDialog.Builder(this).setTitle(title+" · 播放源").setItems(opts,(dd,j)->{c.activeSource=j;play(ix);}).setNegativeButton("关闭",null).show();}else{channels.remove(ix);favorites.remove(c.key());rebuildChannelIndexes();}persist();refresh();}).show();return true;});
        list.addView(b);
      }
    }
    if(totalMatches==0){
      TextView empty=new TextView(this);
      empty.setText(adultCategory
        ?"当前分类还没有匹配到已导入的频道。\\n\\n上方可以直接添加播放列表网址或导入 M3U 文件。导入后会按频道名称和分组自动识别成人分类。"
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
  private void loadChinaChannels(){
    if(chinaLoading){info.setText("国内频道列表正在载入，请稍候…");return;}
    chinaLoading=true;info.setText("正在从多个公开频道目录补充央视、卫视和各省地方台…");
    worker.execute(()->{
      int added=0;String[] feeds={
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
      for(String address:feeds){HttpURLConnection c=null;try{
        c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","MyTV/1.0 Android");c.setRequestProperty("Accept","*/*");
        int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
        String data;try(InputStream in=c.getInputStream()){data=read(in);}
        synchronized(channels){added+=parse(data);}
      }catch(Exception e){errors.append(e.getMessage()).append("; ");}finally{if(c!=null)c.disconnect();}}
      final int total=added;final String err=errors.toString();
      runOnUiThread(()->{persist();refresh();chinaLoading=false;info.setText("国内频道补充完成：新增 "+total+" 个条目，来源包含央视、卫视、地方台及多源检测列表。频道可用性受地区、版权和网络影响。"+(err.isEmpty()?"":"部分来源暂不可用："+err));});
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
      final int count; synchronized(channels){count=parse(data);}
      runOnUiThread(()->{activeCategory="全部";favoriteMode=false;persist();refresh();info.setText(label+" 载入完成：新增 "+count+" 个频道条目。部分频道受地区、网络或播出授权限制。");});
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
      for(String address:feeds){HttpURLConnection c=null;try{
        c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","MyTV/1.0 Android");c.setRequestProperty("Accept","*/*");
        int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
        String data;try(InputStream in=c.getInputStream()){data=read(in);}
        synchronized(channels){added+=parse(data);}
      }catch(Exception e){errors.append(address.substring(address.lastIndexOf('/')+1)).append(": ").append(e.getMessage()).append("; ");}finally{if(c!=null)c.disconnect();}}
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
      for(String address:feeds){HttpURLConnection c=null;try{
        c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","MyTV/1.0 Android");c.setRequestProperty("Accept","*/*");
        int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
        String data;try(InputStream in=c.getInputStream()){data=read(in);}
        synchronized(channels){added+=parse(data);}
      }catch(Exception e){errors.append(e.getMessage()).append("; ");}finally{if(c!=null)c.disconnect();}}
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
  private void urlDialog(){EditText e=new EditText(this);e.setSingleLine(true);e.setHint("https://.../playlist.m3u");new AlertDialog.Builder(this).setTitle("播放列表网址").setView(e).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->download(e.getText().toString().trim())).show();}
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
  private void addDialog(){LinearLayout f=new LinearLayout(this);f.setOrientation(1);EditText n=new EditText(this);n.setHint("频道名称");EditText u=new EditText(this);u.setHint("http(s)://...m3u8");f.addView(n);f.addView(u);new AlertDialog.Builder(this).setTitle("添加频道").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String url=u.getText().toString().trim();if(url.startsWith("http://")||url.startsWith("https://")){channels.add(new Channel(n.getText().toString().trim(),url,"自定义"));rebuildChannelIndexes();persist();refresh();play(channels.size()-1);}else toast("请输入有效网址");}).show();}
  private void showFullscreenChannelMenu(){
    if(!fullScreenPlayback||fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing())return;
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
      if(fullscreenMenuButton!=null)fullscreenMenuButton.setVisibility(fullScreenPlayback&&isTouchDevice()?View.VISIBLE:View.GONE);
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
    fullscreenMenuButton.setVisibility(isTouchDevice()?View.VISIBLE:View.GONE);
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
    fullscreenMenuButton.setVisibility(View.GONE);
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
  // 全屏播放时的遥控器快捷键；频道菜单打开后，方向键和确认键交给菜单控件。
  @Override public boolean dispatchKeyEvent(KeyEvent event){
    if(fullScreenPlayback && (fullscreenMenuPopup==null || !fullscreenMenuPopup.isShowing())){
      int key=event.getKeyCode();
      boolean ok=key==KeyEvent.KEYCODE_DPAD_CENTER
          ||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER;
      boolean direction=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN
          ||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
      if(ok||direction){
        if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0){
          if(ok)showFullscreenChannelMenu();
          else if(key==KeyEvent.KEYCODE_DPAD_UP)stepChannel(-1);
          else if(key==KeyEvent.KEYCODE_DPAD_DOWN)stepChannel(1);
          else if(key==KeyEvent.KEYCODE_DPAD_LEFT)stepSource(-1);
          else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)stepSource(1);
        }
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
  }
  private void stepChannel(int delta){
    if(channels.isEmpty()){toast("当前没有可播放频道");return;}
    int next=selected<0?(delta>0?0:channels.size()-1):(selected+delta+channels.size())%channels.size();
    play(next);
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
    uiHandler.postDelayed(hideChannelZapOverlay,3000L);
  }
  private void stepSource(int delta){
    if(selected<0||selected>=channels.size()){toast("请先选择频道");return;}
    Channel c=channels.get(selected);
    if(c.backups.size()<2){toast("当前频道只有 1 个播放源");return;}
    c.activeSource=(c.activeSource+delta+c.backups.size())%c.backups.size();
    automaticSwitchCount=0;
    sameSourceRecoveryCount=0;
    manualSourceSwitch=true;
    startCurrentSource();
    showChannelZapMessage(c.name+"\n正在尝试第 "+(c.activeSource+1)+" / "+c.backups.size()+" 个源");
  }
  @Override public void onBackPressed(){
    if(fullscreenMenuPopup!=null&&fullscreenMenuPopup.isShowing()){closeFullscreenChannelMenu();return;}
    if(fullScreenPlayback){exitFullScreenPlayback();return;}
    super.onBackPressed();
  }
  private int automaticSwitchCount=0;
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
  private long sourceAttemptStartedAt=0L;
  private long sourceReadyAt=0L;
  private boolean resumePlaybackOnStart=false;
  private boolean sourceReadyRecorded=false;
  private boolean sourceWasBuffering=false;
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
      if(state==Player.STATE_BUFFERING){scheduleBufferingFallback();return;}
      if(state!=Player.STATE_READY)return;
      long now=android.os.SystemClock.elapsedRealtime();
      long position=player.getCurrentPosition();
      if(watchdogLastPosition<0||position>watchdogLastPosition+250){
        watchdogLastPosition=position;
        watchdogLastAdvanceAt=now;
      }else if(now-watchdogLastAdvanceAt>=25000L){
        recoverFromSilentFreeze("播放时间轴连续 25 秒没有推进");
        return;
      }
      // A live source can leave ExoPlayer in READY while its audio/video output has
      // silently stopped. Monitor actual video-frame callbacks, not only position.
      if(videoFrameMonitoringActive && player.getVideoFormat()!=null){
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
  private void cancelBufferingFallback(){
    if(bufferingFallback!=null){uiHandler.removeCallbacks(bufferingFallback);bufferingFallback=null;}
  }
  private void scheduleBufferingFallback(){
    cancelBufferingFallback();
    int configured=getSharedPreferences("mytv",0).getInt("buffer_switch_seconds",8);
    if(configured<=0)return;
    // Initial live startup can legitimately take longer than a mid-stream rebuffer.
    // Do not abandon a healthy but slow-to-start source after only 3-5 seconds.
    int seconds=sourceReadyRecorded?configured:18;
    final String expectedMediaId=currentMediaId;
    final int expectedChannel=selected;
    bufferingFallback=()->{
      bufferingFallback=null;
      if(player!=null&&selected==expectedChannel&&currentMediaId.equals(expectedMediaId)
          &&player.getPlaybackState()==Player.STATE_BUFFERING){
        advanceToNextSourceAutomatically("连续缓冲超过 "+seconds+" 秒");
      }
    };
    uiHandler.postDelayed(bufferingFallback,seconds*1000L);
  }
  private void advanceToNextSourceAutomatically(String reason){
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    if(selected<0||selected>=channels.size())return;
    Channel c=channels.get(selected);
    if(c.backups.size()<2){info.setText(reason+"；当前频道只有 1 个播放源，没有备用源可切换");return;}
    if(automaticSwitchCount>=c.backups.size()-1){
      info.setText(reason+"；当前频道的播放源已全部尝试，请手动选择其他频道或稍后重试");
      return;
    }
    automaticSwitchCount++;
    c.activeSource=(c.activeSource+1)%c.backups.size();
    info.setText(reason+"，正在自动切换备用源 "+(c.activeSource+1)+"/"+c.backups.size());
    startCurrentSource();
  }
  private void play(int ix){
    if(ix<0||ix>=channels.size())return;
    automaticSwitchCount=0;
    sameSourceRecoveryCount=0;
    manualSourceSwitch=false;
    selected=ix;
    sortSourcesByObservedQuality(channels.get(ix));
    startCurrentSource();
  }
  private void startCurrentSource(){
    if(selected<0||selected>=channels.size())return;
    closeFullscreenChannelMenu();
    enterFullScreenPlayback();
    Channel c=channels.get(selected);
    if(c.activeSource<0||c.activeSource>=c.backups.size())c.activeSource=0;
    cancelBufferingFallback();
    cancelPlaybackWatchdog();
    watchdogLastPosition=-1L;
    watchdogLastAdvanceAt=android.os.SystemClock.elapsedRealtime();
    sourceAttemptStartedAt=android.os.SystemClock.elapsedRealtime();
    sourceReadyAt=0L;
    lastVideoFrameAt=0L;
    hasReceivedVideoFrame=false;
    videoFrameMonitoringActive=false;
    sourceReadyRecorded=false;
    sourceWasBuffering=false;
    recordSourceMetric(c.backups.get(c.activeSource),"attempt",0L);
    saveLastChannel();
    final long generation=++playbackGeneration;
    currentMediaId=generation+":"+selected+":"+c.activeSource;
    info.setText("正在尝试："+c.name+"（源 "+(c.activeSource+1)+"/"+c.backups.size()+"）");
    if(player==null){
      DefaultHttpDataSource.Factory httpFactory=new DefaultHttpDataSource.Factory()
          .setUserAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 MyTV/1.3")
          .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
      DefaultRenderersFactory renderersFactory=new DefaultRenderersFactory(this)
          .setEnableDecoderFallback(getSharedPreferences("mytv",0).getInt("decoder_mode",1)==1);
      DefaultLoadControl loadControl=new DefaultLoadControl.Builder()
          .setBufferDurationsMs(20000,50000,1500,3500)
          .setPrioritizeTimeOverSizeThresholds(true)
          .build();
      player=new ExoPlayer.Builder(this,renderersFactory)
          .setLoadControl(loadControl)
          .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)).build();
      video.setPlayer(player);
      VideoFrameMetadataListener frameListener=(presentationTimeUs,releaseTimeNs,format,mediaFormat)->{
          lastVideoFrameAt=android.os.SystemClock.elapsedRealtime();
          hasReceivedVideoFrame=true;
        };
      // Use reflection to remain compatible with the Media3 API surface shipped by
      // the app while still enabling per-frame monitoring when the runtime exposes it.
      try{
        java.lang.reflect.Method setFrameListener=player.getClass().getMethod("setVideoFrameMetadataListener",VideoFrameMetadataListener.class);
        setFrameListener.invoke(player,frameListener);
        videoFrameMonitoringActive=true;
      }catch(Exception unavailable){
        try{
          java.lang.reflect.Method addFrameListener=player.getClass().getMethod("addVideoFrameMetadataListener",VideoFrameMetadataListener.class);
          addFrameListener.invoke(player,frameListener);
          videoFrameMonitoringActive=true;
        }catch(Exception ignored){}
      }
      player.addListener(new Player.Listener(){
        private boolean isCurrent(){
          MediaItem item=player==null?null:player.getCurrentMediaItem();
          return item!=null&&currentMediaId.equals(item.mediaId);
        }
        public void onPlayerError(PlaybackException e){
          if(!isCurrent())return;
          if(!sourceReadyRecorded)recordSourceMetric(currentSourceUrl(),"failure",0L);
          advanceToNextSourceAutomatically("当前播放源失效（错误代码 "+e.errorCode+"）");
        }
        public void onPlaybackStateChanged(int st){
          if(!isCurrent())return;
          if(st==Player.STATE_BUFFERING){
            if(sourceReadyRecorded&&!sourceWasBuffering)recordSourceMetric(currentSourceUrl(),"buffer",0L);
            sourceWasBuffering=true;
            scheduleBufferingFallback();
          }else if(st==Player.STATE_READY){
            sourceWasBuffering=false;
            if(!sourceReadyRecorded){
              sourceReadyRecorded=true;
              recordSourceMetric(currentSourceUrl(),"ready",android.os.SystemClock.elapsedRealtime()-sourceAttemptStartedAt);
            }
            cancelBufferingFallback();
            // Do not reset automaticSwitchCount here: a source that starts and then
            // freezes must not create an endless source-switch loop.
            watchdogLastPosition=player.getCurrentPosition();
            watchdogLastAdvanceAt=android.os.SystemClock.elapsedRealtime();
            sourceReadyAt=watchdogLastAdvanceAt;
            // Start the frame-stall timer only after the player reports READY.
            lastVideoFrameAt=watchdogLastAdvanceAt;
            schedulePlaybackWatchdog();
            if(selected>=0&&selected<channels.size()){
              saveLastChannel();
              info.setText("正在播放："+channels.get(selected).name+"（源 "+(channels.get(selected).activeSource+1)+"/"+channels.get(selected).backups.size()+"）");
              if(manualSourceSwitch){
                showChannelZapMessage(channels.get(selected).name+"\n已连接第 "+(channels.get(selected).activeSource+1)+" / "+channels.get(selected).backups.size()+" 个源");
                manualSourceSwitch=false;
              }
            }
          }else if(st==Player.STATE_IDLE||st==Player.STATE_BUFFERING||st==Player.STATE_ENDED){
            cancelPlaybackWatchdog();
            if(st==Player.STATE_IDLE)cancelBufferingFallback();
            if(st==Player.STATE_ENDED)advanceToNextSourceAutomatically("当前播放流已结束");
          }
        }
      });
    }
    MediaItem item=new MediaItem.Builder().setUri(c.backups.get(c.activeSource)).setMediaId(currentMediaId).build();
    player.setMediaItem(item);
    player.prepare();
    player.play();
  }
  private String currentSourceUrl(){
    if(selected<0||selected>=channels.size())return "";
    Channel c=channels.get(selected);
    return c.activeSource>=0&&c.activeSource<c.backups.size()?c.backups.get(c.activeSource):c.url;
  }
  // Persistent playback observations. READY latency is not the same as a rendered first frame;
  // buffering/error events come from the actual ExoPlayer session, not a URL probe.
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
      else if("ready".equals(event)){
        int count=m.optInt("ready",0);long avg=m.optLong("avgReadyMs",0L);
        m.put("ready",count+1);m.put("avgReadyMs",count==0?readyMs:((avg*count)+readyMs)/(count+1));
        m.put("lastSuccess",System.currentTimeMillis());
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
      double readyPenalty=Math.min(250.0,m.optLong("avgReadyMs",0L)/100.0);
      return probeScore+successRate*1000.0-fail*90.0-buffers*55.0-readyPenalty+label*0.01;
    }catch(Exception ignored){return label*0.01;}
  }
  private void sortSourcesByObservedQuality(Channel c){
    if(c==null||c.backups==null||c.backups.size()<2)return;
    String active=c.activeSource>=0&&c.activeSource<c.backups.size()?c.backups.get(c.activeSource):c.url;
    ArrayList<String> ordered=new ArrayList<>(c.backups);
    ordered.sort((a,b)->Double.compare(observedSourceScore(b),observedSourceScore(a)));
    c.backups.clear();c.backups.addAll(ordered);
    int ix=c.backups.indexOf(active);c.activeSource=ix>=0?ix:0;
    persist();
  }
  private void bufferingSwitchSettingsDialog(){
    int current=getSharedPreferences("mytv",0).getInt("buffer_switch_seconds",8);
    String[] labels={"3 秒后切换（灵敏）","5 秒后切换","8 秒后切换（推荐）","12 秒后切换","关闭自动卡顿切换"};
    int[] values={3,5,8,12,0};
    int checked=current==3?0:current==5?1:current==12?3:current==0?4:2;
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
  protected void onStart(){super.onStart();if(resumePlaybackOnStart&&selected>=0&&selected<channels.size()){resumePlaybackOnStart=false;startCurrentSource();}}
  protected void onStop(){super.onStop();saveLastChannel();resumePlaybackOnStart=selected>=0&&selected<channels.size();cancelBufferingFallback();cancelPlaybackWatchdog();if(player!=null){player.release();player=null;}}
  protected void onDestroy(){super.onDestroy();if(pendingSearchRefresh!=null)uiHandler.removeCallbacks(pendingSearchRefresh);cancelPlaybackWatchdog();worker.shutdownNow();posterWorker.shutdownNow();vodWorker.shutdownNow();vodMetadataWorker.shutdownNow();}
}