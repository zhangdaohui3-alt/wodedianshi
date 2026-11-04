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
    search=new EditText(this);search.setSingleLine(true);search.setHint("搜索频道");side.addView(search);
    search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh();}public void afterTextChanged(android.text.Editable e){}});
    list=new LinearLayout(this);list.setOrientation(1);ScrollView sc=new ScrollView(this);sc.addView(list);side.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout main=new LinearLayout(this);main.setOrientation(1);
    info=new TextView(this);info.setText("请导入你有权使用的 M3U / M3U8 播放列表");info.setTextColor(-1);main.addView(info);
    video=new PlayerView(this);video.setUseController(true);main.addView(video,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(side,new LinearLayout.LayoutParams(dp(290),-1));root.addView(main,new LinearLayout.LayoutParams(0,-1,1));setContentView(root);refresh();
  }
  private void addButton(LinearLayout p,String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);p.addView(b);}
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
    worker.execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);InputStream in=c.getInputStream();String data=read(in);c.disconnect();runOnUiThread(()->finishImport(data));}catch(Exception e){runOnUiThread(()->info.setText("导入失败："+e.getMessage()));}});
  }
  private String read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))>0&&total<8388608){b.write(buf,0,n);total+=n;}return b.toString("UTF-8");}
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