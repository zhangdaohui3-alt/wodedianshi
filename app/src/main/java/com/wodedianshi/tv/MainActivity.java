package com.wodedianshi.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.ui.PlayerView;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  private ExoPlayer player;
  private PlayerView video;
  private LinearLayout list;
  private TextView info;
  private EditText search;
  private final ArrayList<Channel> channels = new ArrayList<>();
  private final HashSet<String> favorites = new HashSet<>();
  private final ArrayList<Button> menuButtons = new ArrayList<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
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
    makeUi();
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
  private void persist(){
    try {
      org.json.JSONArray a=new org.json.JSONArray();
      for(Channel c:channels){org.json.JSONObject o=new org.json.JSONObject();o.put("name",c.name);o.put("url",c.url);o.put("group",c.group);org.json.JSONArray src=new org.json.JSONArray();for(String u:c.backups)src.put(u);o.put("sources",src);a.put(o);}
      getSharedPreferences("mytv",0).edit().putString("channels",a.toString()).putStringSet("fav",new HashSet<>(favorites)).apply();
    } catch(Exception ignored) {}
  }
  private String activeCategory="全部";
  private void makeUi(){
    LinearLayout root=new LinearLayout(this);root.setOrientation(0);root.setPadding(dp(10),dp(8),dp(10),dp(8));root.setBackgroundColor(Color.rgb(8,13,24));
    LinearLayout cats=new LinearLayout(this);cats.setOrientation(1);cats.setBackgroundColor(Color.rgb(16,24,40));
    TextView logo=new TextView(this);logo.setText("我的\\n电视");logo.setTextColor(Color.WHITE);logo.setTextSize(23);logo.setGravity(Gravity.CENTER);cats.addView(logo,new LinearLayout.LayoutParams(-1,dp(82)));
    String[] names={"央视","卫视","地方","港澳台","全部","全球频道","国家地区","娱乐频道","国际新闻","体育","天气","教育","中国","收藏"};
    LinearLayout catItems=new LinearLayout(this);catItems.setOrientation(1);
    for(String name:names){Button x=new Button(this);x.setText(name);x.setTextColor(Color.WHITE);x.setTextSize(16);x.setMinHeight(dp(48));styleTvButton(x,name.equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40));x.setOnClickListener(v->{activeCategory=name;favoriteMode=name.equals("收藏");if(name.equals("全球频道"))loadGlobalChannels();if(name.equals("国家地区"))showCountryDialog();if(name.equals("娱乐频道"))loadEntertainmentChannels();if(name.equals("港澳台"))loadRegionChannels();if(name.equals("央视")||name.equals("卫视")||name.equals("地方"))loadChinaChannels();if(name.equals("地方"))showProvinceDialog();for(int i=0;i<catItems.getChildCount();i++){View z=catItems.getChildAt(i);if(z instanceof Button)((Button)z).setBackground(makeButtonBg(((Button)z).getText().toString().equals(activeCategory)?Color.rgb(26,112,190):Color.rgb(16,24,40),z.hasFocus()));}refresh();});catItems.addView(x,new LinearLayout.LayoutParams(-1,dp(50)));}
    ScrollView catScroll=new ScrollView(this);catScroll.setFillViewport(false);catScroll.addView(catItems);cats.addView(catScroll,new LinearLayout.LayoutParams(-1,0,1));
    Button settings=new Button(this);settings.setText("设置");settings.setTextColor(Color.WHITE);styleTvButton(settings,Color.rgb(35,45,62));settings.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("设置与管理").setItems(new String[]{"导入 M3U 文件","导入播放列表网址","手动添加频道","导出 M3U 配置","官方平台入口","全球正版电影点播"},(d,w)->{if(w==0)chooseFile();else if(w==1)urlDialog();else if(w==2)addDialog();else if(w==3)exportFile();else if(w==4)officialDialog();else vodDialog();}).show());cats.addView(settings,new LinearLayout.LayoutParams(-1,dp(58)));
    LinearLayout channelPane=new LinearLayout(this);channelPane.setOrientation(1);channelPane.setPadding(dp(8),0,dp(8),0);channelPane.setBackgroundColor(Color.rgb(19,29,47));
    TextView title=new TextView(this);title.setText("频道列表");title.setTextColor(Color.WHITE);title.setTextSize(19);title.setPadding(dp(8),dp(10),0,dp(8));channelPane.addView(title);
    search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);search.setHintTextColor(Color.LTGRAY);search.setHint("搜索频道");channelPane.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
    list=new LinearLayout(this);list.setOrientation(1);ScrollView sc=new ScrollView(this);sc.addView(list);channelPane.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout main=new LinearLayout(this);main.setOrientation(1);main.setPadding(dp(10),0,0,0);
    info=new TextView(this);info.setText("欢迎使用我的电视\\n请从左侧选择栏目，或在设置中导入你有权使用的直播源。");info.setTextColor(Color.WHITE);info.setTextSize(16);info.setPadding(dp(8),dp(8),dp(8),dp(8));main.addView(info,new LinearLayout.LayoutParams(-1,dp(62)));
    video=new PlayerView(this);video.setUseController(true);video.setFocusable(false);video.setBackgroundColor(Color.BLACK);main.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(cats,new LinearLayout.LayoutParams(dp(104),-1));root.addView(channelPane,new LinearLayout.LayoutParams(dp(250),-1));root.addView(main,new LinearLayout.LayoutParams(0,-1,1));setContentView(root);
    search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh();}public void afterTextChanged(android.text.Editable e){}});
    refresh();if(!channels.isEmpty())info.setText("已载入 "+channels.size()+" 个频道 · 选择频道开始播放");
  }
  private void addButton(LinearLayout p,String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setTextSize(17);b.setMinHeight(dp(54));b.setFocusable(true);b.setFocusableInTouchMode(true);b.setOnFocusChangeListener((v,has)->{v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(has?Color.rgb(38,110,190):Color.rgb(35,45,62)));});b.setOnClickListener(l);b.setId(View.generateViewId());b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35,45,62)));b.setTextColor(Color.WHITE);menuButtons.add(b);p.addView(b,new LinearLayout.LayoutParams(-1,dp(58)));}
  // Do not forward every remote-control key to PlayerView.
  // Doing so steals DPAD focus from AlertDialog lists on Android TV.
  private void vodDialog(){
    String[] names={"Tubi · 免费电影/电视剧","Pluto TV · 直播电影台+点播","Plex · 免费电影与直播","The Roku Channel · 电影/剧集","YouTube Movies · 免费电影专区","Internet Archive · 公版经典电影","Kanopy · 电影/纪录片（需图书馆资格）","Hoopla · 电影/纪录片（需图书馆资格）","Rakuten TV · 免费广告电影（地区有限）","Xumo Play · 电影直播与点播（地区有限）"};
    String[] urls={"https://tubitv.com/","https://pluto.tv/","https://www.plex.tv/watch-free/","https://therokuchannel.roku.com/","https://www.youtube.com/movies","https://archive.org/details/feature_films","https://www.kanopy.com/","https://www.hoopladigital.com/","https://www.rakuten.tv/","https://play.xumo.com/"};
    new AlertDialog.Builder(this).setTitle("全球正版免费电影点播入口").setMessage("以下为官方平台入口，点击后在浏览器或官方应用打开。可用片库、语言、账号要求和地区限制以平台当地规则为准；这些入口不是内置破解源。")
      .setItems(names,(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(urls[w])));}catch(Exception e){toast("无法打开，请检查浏览器或官方应用");}})
      .setNegativeButton("关闭",null).show();
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
  private boolean categoryMatch(Channel c,String cat){
    String x=(c.name+" "+c.group).toLowerCase(Locale.ROOT);
    String g=c.group.toLowerCase(Locale.ROOT);
    if(cat.startsWith("城市:"))return x.contains(cat.substring(3).toLowerCase(Locale.ROOT));
    String[] provinceNames={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    for(String province:provinceNames)if(cat.equals(province))return x.contains(province.toLowerCase(Locale.ROOT));
    if(cat.equals("全球频道"))return true;
    if(cat.equals("娱乐频道"))return g.contains("entertainment")||g.contains("comedy")||g.contains("music")||g.contains("lifestyle")||g.contains("movies")||g.contains("animation")||g.contains("series")||g.contains("culture")||g.contains("family")||g.contains("kids")||g.contains("children")||g.contains("documentary")||g.contains("cartoon")||g.contains("娱乐")||g.contains("综艺")||g.contains("音乐")||g.contains("电影")||g.contains("喜剧")||g.contains("生活")||g.contains("少儿")||g.contains("动画")||g.contains("纪录");
    if(cat.equals("央视"))return x.contains("cctv")||x.contains("央视")||g.equals("cctv");
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
    if(list==null)return;list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);
    for(int i=0;i<channels.size();i++){final int ix=i;Channel c=channels.get(i);if(favoriteMode&&!favorites.contains(c.key()))continue;if(!activeCategory.equals("全部")&&!activeCategory.equals("收藏")&&!categoryMatch(c,activeCategory))continue;if(!c.name.toLowerCase(Locale.ROOT).contains(q)&&!c.group.toLowerCase(Locale.ROOT).contains(q))continue;
      Button b=new Button(this);b.setText((favorites.contains(c.key())?"★ ":"")+c.name+(c.group.isEmpty()?"":" · "+c.group));b.setTextSize(16);b.setMinHeight(dp(52));styleTvButton(b,Color.rgb(35,45,62));b.setOnClickListener(v->play(ix));
      b.setOnLongClickListener(v->{new AlertDialog.Builder(this).setItems(new String[]{favorites.contains(c.key())?"取消收藏":"加入收藏","查看/选择备用源（"+c.backups.size()+"个）","删除频道"},(d,w)->{if(w==0){if(!favorites.add(c.key()))favorites.remove(c.key());}else if(w==1){String[] opts=new String[c.backups.size()];for(int j=0;j<opts.length;j++)opts[j]="播放源 "+(j+1)+(j==0?"（主源）":"（备用）");new AlertDialog.Builder(this).setTitle(c.name+" · 播放源").setItems(opts,(dd,j)->{c.activeSource=j;play(ix);}).setNegativeButton("关闭",null).show();}else{channels.remove(ix);favorites.remove(c.key());}persist();refresh();}).show();return true;});list.addView(b);
    }
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
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setItems(items,listener).setNegativeButton("返回",null).create();
    dialog.setOnShowListener(d->{
      ListView choices=dialog.getListView();
      if(choices!=null){choices.setFocusable(true);choices.setFocusableInTouchMode(true);choices.setItemsCanFocus(false);choices.requestFocus();choices.setSelection(0);}
    });
    dialog.show();
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
  private String read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>50*1024*1024)throw new IOException("播放列表超过 8 MB，已停止导入");b.write(buf,0,n);}return b.toString("UTF-8");}
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
        boolean exists=false;
        for(Channel c:channels)if(c.url.equals(line)){exists=true;break;}
        if(!exists){
          Channel same=null;
          for(Channel c:channels)if(c.name.equalsIgnoreCase(name)&&c.group.equalsIgnoreCase(group)){same=c;break;}
          if(same!=null){if(!same.backups.contains(line)){same.backups.add(line);added++;}}
          else{channels.add(new Channel(name,line,group));added++;}
        }
        name=null;group="";
      }
    }
    return added;
  }
  protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==10&&result==RESULT_OK&&data!=null){Uri u=data.getData();worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(u)){String s=read(in);runOnUiThread(()->finishImport(s));}catch(Exception e){runOnUiThread(()->toast("读取失败"));}});}if(req==11&&result==RESULT_OK&&data!=null){worker.execute(()->{try(OutputStream o=getContentResolver().openOutputStream(data.getData())){StringBuilder b=new StringBuilder("#EXTM3U\n");for(Channel c:channels)b.append("#EXTINF:-1 group-title=\"").append(c.group).append("\",").append(c.name).append("\n").append(c.url).append("\n");o.write(b.toString().getBytes("UTF-8"));runOnUiThread(()->toast("已导出"));}catch(Exception e){runOnUiThread(()->toast("导出失败"));}});}}
  private void exportFile(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_TITLE,"mytv.m3u");startActivityForResult(i,11);}
  private void addDialog(){LinearLayout f=new LinearLayout(this);f.setOrientation(1);EditText n=new EditText(this);n.setHint("频道名称");EditText u=new EditText(this);u.setHint("http(s)://...m3u8");f.addView(n);f.addView(u);new AlertDialog.Builder(this).setTitle("添加频道").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String url=u.getText().toString().trim();if(url.startsWith("http://")||url.startsWith("https://")){channels.add(new Channel(n.getText().toString().trim(),url,"自定义"));persist();refresh();play(channels.size()-1);}else toast("请输入有效网址");}).show();}
  private void play(int ix){if(ix<0||ix>=channels.size())return;selected=ix;Channel c=channels.get(ix);if(c.activeSource<0||c.activeSource>=c.backups.size())c.activeSource=0;final long generation=++playbackGeneration;currentMediaId=generation+":"+ix+":"+c.activeSource;info.setText("正在尝试："+c.name+"（源 "+(c.activeSource+1)+"/"+c.backups.size()+"）");if(player==null){DefaultHttpDataSource.Factory httpFactory=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 MyTV/1.3").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000);player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)).build();video.setPlayer(player);player.addListener(new Player.Listener(){private boolean isCurrent(){MediaItem item=player==null?null:player.getCurrentMediaItem();return item!=null&&currentMediaId.equals(item.mediaId);}public void onPlayerError(PlaybackException e){if(!isCurrent())return;if(selected>=0&&selected<channels.size()){Channel ch=channels.get(selected);if(ch.activeSource+1<ch.backups.size()){ch.activeSource++;info.setText("当前源失败，自动切换备用源 "+(ch.activeSource+1)+"/"+ch.backups.size());play(selected);}else info.setText("所有已配置播放源均失败，请稍后重试或检查网络");}}public void onPlaybackStateChanged(int st){if(st==Player.STATE_READY&&isCurrent()&&selected>=0&&selected<channels.size())info.setText("正在播放："+channels.get(selected).name+"（源 "+(channels.get(selected).activeSource+1)+"/"+channels.get(selected).backups.size()+"）");}});}MediaItem item=new MediaItem.Builder().setUri(c.backups.get(c.activeSource)).setMediaId(currentMediaId).build();player.setMediaItem(item);player.prepare();player.play();}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  protected void onStop(){super.onStop();if(player!=null){player.release();player=null;}}
  protected void onDestroy(){super.onDestroy();worker.shutdown();}
}