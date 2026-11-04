package com.wodedianshi.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
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
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private boolean favoriteMode = false;
  private int selected = -1;
  private static class Channel {
    String name, url, group;
    Channel(String n,String u,String g){name=n;url=u;group=g;}
    String key(){return name+"|"+url;}
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    restore();
    makeUi();
  }
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  private void restore(){
    android.content.SharedPreferences p=getSharedPreferences("mytv",0);
    favorites.addAll(p.getStringSet("fav",new HashSet<>()));
    try {
      org.json.JSONArray a=new org.json.JSONArray(p.getString("channels","[]"));
      for(int i=0;i<a.length();i++){org.json.JSONObject o=a.getJSONObject(i);channels.add(new Channel(o.getString("name"),o.getString("url"),o.optString("group","")));}
    } catch(Exception ignored) {}
  }
  private void persist(){
    try {
      org.json.JSONArray a=new org.json.JSONArray();
      for(Channel c:channels){org.json.JSONObject o=new org.json.JSONObject();o.put("name",c.name);o.put("url",c.url);o.put("group",c.group);a.put(o);}
      getSharedPreferences("mytv",0).edit().putString("channels",a.toString()).putStringSet("fav",new HashSet<>(favorites)).apply();
    } catch(Exception ignored) {}
  }
  private void makeUi(){
    LinearLayout root=new LinearLayout(this);root.setOrientation(0);root.setPadding(dp(12),dp(8),dp(12),dp(8));root.setBackgroundColor(Color.rgb(12,18,32));
    LinearLayout side=new LinearLayout(this);side.setOrientation(1);
    TextView heading=new TextView(this);heading.setText("我的电视");heading.setTextColor(-1);heading.setTextSize(26);side.addView(heading);
    addButton(side,"导入 M3U 文件",v->chooseFile());
    addButton(side,"导入播放列表网址",v->urlDialog());
    addButton(side,"全部频道 / 收藏",v->{favoriteMode=!favoriteMode;refresh();});
    addButton(side,"导出 M3U 配置",v->exportFile());
    addButton(side,"手动添加频道",v->addDialog());
    addButton(side,"国内官方观看平台",v->officialDialog());
    search=new EditText(this);search.setSingleLine(true);search.setHint("搜索频道");side.addView(search);
    search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh();}public void afterTextChanged(android.text.Editable e){}});
    list=new LinearLayout(this);list.setOrientation(1);ScrollView sc=new ScrollView(this);sc.addView(list);side.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout main=new LinearLayout(this);main.setOrientation(1);
    info=new TextView(this);info.setText("国内频道请导入授权播放源，或从左侧打开国内官方平台");info.setTextColor(-1);main.addView(info);
    video=new PlayerView(this);video.setUseController(true);main.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(side,new LinearLayout.LayoutParams(dp(290),-1));root.addView(main,new LinearLayout.LayoutParams(0,-1,1));setContentView(root);refresh();
  }
  private void addButton(LinearLayout p,String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);p.addView(b);}
  private void officialDialog(){
    String[] categories={"央视频道（CCTV）","省级卫视","各省市地方频道","港澳台频道","国内影视与节目"};
    new AlertDialog.Builder(this).setTitle("国内直播频道分类")
      .setItems(categories,(dialog,which)->{
        if(which==0) openOfficialList("央视频道",new String[]{"央视网直播","央视网节目官网"},new String[]{"https://tv.cctv.com/live/","https://cctv.cntv.cn/"});
        else if(which==1) openOfficialList("省级卫视",new String[]{"北京卫视","东方卫视","江苏卫视","浙江卫视","湖南卫视","广东卫视","深圳卫视","四川卫视"},new String[]{"https://www.btime.com/","https://www.kankanews.com/","https://live.jstv.com/","https://tv.cztv.com/live1","https://www.mgtv.com/","https://www.gdtv.com.cn/","https://www.cutv.com/","https://www.sctv.com/"});
        else if(which==2) new AlertDialog.Builder(this).setTitle("选择省份/地区").setItems(new String[]{"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆","香港","澳门","台湾"},(d,p)->{String region=new String[]{"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆","香港","澳门","台湾"}[p];new AlertDialog.Builder(this).setTitle(region+"地方频道").setMessage("将打开央视网地方频道入口。该入口提供地方内容导航；具体频道是否直播、是否可在当地网络播放，以官方页面实际提供为准。").setPositiveButton("打开官方入口",(x,y)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://local.cctv.cn/")));}catch(Exception e){toast("无法打开，请检查浏览器");}}).setNegativeButton("取消",null).show();}).setNegativeButton("返回",null).show();
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
  private void refresh(){
    if(list==null)return;list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);
    for(int i=0;i<channels.size();i++){final int ix=i;Channel c=channels.get(i);if(favoriteMode&&!favorites.contains(c.key()))continue;if(!c.name.toLowerCase(Locale.ROOT).contains(q)&&!c.group.toLowerCase(Locale.ROOT).contains(q))continue;
      Button b=new Button(this);b.setText((favorites.contains(c.key())?"★ ":"")+c.name+(c.group.isEmpty()?"":" · "+c.group));b.setOnClickListener(v->play(ix));
      b.setOnLongClickListener(v->{new AlertDialog.Builder(this).setItems(new String[]{favorites.contains(c.key())?"取消收藏":"加入收藏","删除频道"},(d,w)->{if(w==0){if(!favorites.add(c.key()))favorites.remove(c.key());}else{channels.remove(ix);favorites.remove(c.key());}persist();refresh();}).show();return true;});list.addView(b);
    }
  }
  private void chooseFile(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
  private void urlDialog(){EditText e=new EditText(this);e.setSingleLine(true);e.setHint("https://.../playlist.m3u");new AlertDialog.Builder(this).setTitle("播放列表网址").setView(e).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->download(e.getText().toString().trim())).show();}
  private void download(String address){
    if(!address.startsWith("https://")&&!address.startsWith("http://")){toast("网址格式不正确");return;}info.setText("正在下载播放列表…");
    worker.execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.2 Android");c.setRequestProperty("Accept","*/*");int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);try(InputStream in=c.getInputStream()){String data=read(in);runOnUiThread(()->finishImport(data));}finally{c.disconnect();}}catch(Exception e){runOnUiThread(()->info.setText("导入失败："+e.getMessage()));}});
  }
  private String read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>8*1024*1024)throw new IOException("播放列表超过 8 MB，已停止导入");b.write(buf,0,n);}return b.toString("UTF-8");}
  private void finishImport(String data){int n=parse(data);persist();refresh();info.setText("导入完成，新增 "+n+" 个频道");}
  private int parse(String data){
    String name=null,group="";int added=0;
    for(String raw:data.split("\\r?\\n")){String line=raw.trim();if(line.startsWith("#EXTINF")){int comma=line.lastIndexOf(',');name=comma>=0?line.substring(comma+1).trim():"频道";int g=line.indexOf("group-title=\"");if(g>=0){int end=line.indexOf('"',g+13);if(end>g)group=line.substring(g+13,end);}}
      else if((line.startsWith("http://")||line.startsWith("https://"))&&!line.startsWith("#")){if(name==null)name="频道 "+(channels.size()+1);boolean exists=false;for(Channel c:channels)if(c.url.equals(line)){exists=true;break;}if(!exists){channels.add(new Channel(name,line,group));added++;}name=null;group="";}}
    return added;
  }
  protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==10&&result==RESULT_OK&&data!=null){Uri u=data.getData();worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(u)){String s=read(in);runOnUiThread(()->finishImport(s));}catch(Exception e){runOnUiThread(()->toast("读取失败"));}});}if(req==11&&result==RESULT_OK&&data!=null){worker.execute(()->{try(OutputStream o=getContentResolver().openOutputStream(data.getData())){StringBuilder b=new StringBuilder("#EXTM3U\n");for(Channel c:channels)b.append("#EXTINF:-1 group-title=\"").append(c.group).append("\",").append(c.name).append("\n").append(c.url).append("\n");o.write(b.toString().getBytes("UTF-8"));runOnUiThread(()->toast("已导出"));}catch(Exception e){runOnUiThread(()->toast("导出失败"));}});}}
  private void exportFile(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_TITLE,"mytv.m3u");startActivityForResult(i,11);}
  private void addDialog(){LinearLayout f=new LinearLayout(this);f.setOrientation(1);EditText n=new EditText(this);n.setHint("频道名称");EditText u=new EditText(this);u.setHint("http(s)://...m3u8");f.addView(n);f.addView(u);new AlertDialog.Builder(this).setTitle("添加频道").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String url=u.getText().toString().trim();if(url.startsWith("http://")||url.startsWith("https://")){channels.add(new Channel(n.getText().toString().trim(),url,"自定义"));persist();refresh();play(channels.size()-1);}else toast("请输入有效网址");}).show();}
  private void play(int ix){selected=ix;Channel c=channels.get(ix);info.setText("正在加载："+c.name);if(player==null){player=new ExoPlayer.Builder(this).build();video.setPlayer(player);player.addListener(new Player.Listener(){public void onPlayerError(PlaybackException e){info.setText("播放失败："+e.getErrorCodeName()+"；请检查链接、网络及播放权限");}public void onPlaybackStateChanged(int s){if(s==Player.STATE_READY&&selected>=0&&selected<channels.size())info.setText("正在播放："+channels.get(selected).name);}});}player.setMediaItem(MediaItem.fromUri(c.url));player.prepare();player.play();}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  protected void onStop(){super.onStop();if(player!=null){player.release();player=null;}}
  protected void onDestroy(){super.onDestroy();worker.shutdown();}
}