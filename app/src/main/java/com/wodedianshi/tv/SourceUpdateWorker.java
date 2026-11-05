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
  private static final int MAX_NEW_SOURCES = 100;
  public SourceUpdateWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
  @NonNull @Override public Result doWork(){
    SharedPreferences prefs=getApplicationContext().getSharedPreferences("mytv",0);
    if(!getInputData().getBoolean("manual",false)&&!prefs.getBoolean("auto_source_update",true))return Result.success();
    int added=0,feedsOk=0,feedsFailed=0,checked=0;
    StringBuilder errors=new StringBuilder();
    String[] feeds={
      "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
      "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
      "https://iptv-org.github.io/iptv/countries/cn.m3u",
      "https://iptv-org.github.io/iptv/index.m3u",
      "https://iptv-org.github.io/iptv/countries/hk.m3u",
      "https://iptv-org.github.io/iptv/countries/mo.m3u",
      "https://iptv-org.github.io/iptv/countries/tw.m3u",
      "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
      "https://iptv-org.github.io/iptv/categories/movies.m3u",
      "https://iptv-org.github.io/iptv/categories/music.m3u",
      "https://iptv-org.github.io/iptv/categories/kids.m3u",
      "https://iptv-org.github.io/iptv/categories/documentary.m3u"
    };
    try{
      JSONArray saved=new JSONArray(prefs.getString("channels","[]"));
      LinkedHashMap<String,JSONObject> byIdentity=new LinkedHashMap<>();
      HashSet<String> knownUrls=new HashSet<>();
      JSONObject measuredScores=new JSONObject(prefs.getString("source_probe_scores_v1","{}"));
      for(int i=0;i<saved.length();i++){
        JSONObject c=saved.getJSONObject(i);byIdentity.put(identity(c.optString("name"),c.optString("group")),c);
        JSONArray src=c.optJSONArray("sources");
        if(src!=null)for(int j=0;j<src.length();j++)knownUrls.add(src.optString(j));
        knownUrls.add(c.optString("url"));
      }
      ArrayList<Entry> candidates=new ArrayList<>();
      for(String feed:feeds){
        if(isStopped())return Result.retry();
        HttpURLConnection c=null;
        try{
          c=(HttpURLConnection)new URL(feed).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(12000);
          c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.19 Android TV");c.setRequestProperty("Accept","*/*");
          int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
          String data;try(InputStream in=c.getInputStream()){data=read(in,15*1024*1024);}
          parsePlaylist(data,candidates);feedsOk++;
        }catch(Exception e){feedsFailed++;if(errors.length()<350)errors.append(feed.substring(feed.lastIndexOf('/')+1)).append("；");}
        finally{if(c!=null)c.disconnect();}
      }
      // Examine explicitly advertised HD/high-bitrate candidates first; stable order keeps feed priority for ties.
      candidates.sort((a,b)->Integer.compare(sourceQualityScore(b.url),sourceQualityScore(a.url)));
      // Bound each update run so a slow public directory cannot keep WorkManager busy indefinitely.
      long probeDeadline=android.os.SystemClock.elapsedRealtime()+6L*60L*1000L;
      for(Entry e:candidates){
        if(added>=MAX_NEW_SOURCES||isStopped()||android.os.SystemClock.elapsedRealtime()>=probeDeadline)break;
        if(!e.url.startsWith("http://")&&!e.url.startsWith("https://"))continue;
        if(knownUrls.contains(e.url))continue;
        if(checked>=MAX_NEW_SOURCES)break;
        checked++;
        ProbeResult measured=probe(e.url);
        if(!measured.valid)continue;
        measuredScores.put(scoreKey(e.url),measured.score);
        String key=identity(e.name,e.group);JSONObject target=byIdentity.get(key);
        if(target==null){
          target=new JSONObject();JSONArray sources=new JSONArray();sources.put(e.url);
          target.put("name",e.name);target.put("url",e.url);target.put("group",e.group);target.put("sources",sources);
          saved.put(target);byIdentity.put(key,target);added++;
        }else{
          JSONArray sources=target.optJSONArray("sources");if(sources==null){sources=new JSONArray();String primary=target.optString("url");if(!primary.isEmpty())sources.put(primary);target.put("sources",sources);}
          boolean duplicate=false;for(int i=0;i<sources.length();i++)if(e.url.equals(sources.optString(i))){duplicate=true;break;}
          if(!duplicate){sources.put(e.url);added++;}
        }
        knownUrls.add(e.url);
      }
      if(feedsOk==0){saveStatus(prefs,"检查失败：所有频道目录均无法连接（保留原频道源）",0);return Result.retry();}
      // Persist measured quality independently from URL labels so the player can rank
      // candidates by real HLS/segment reachability, resolution and response speed.
      prefs.edit().putString("source_probe_scores_v1",measuredScores.toString())
          .putString("channels",saved.toString()).putString("source_update_time",now())
          .putString("source_update_result","成功读取 "+feedsOk+" 个目录；实测 "+checked+" 个新地址；新增 "+added+" 个备用/频道地址；目录失败 "+feedsFailed+" 个。评分包含清单、分片、清晰度和响应速度；"+(errors.length()>0?"部分目录失败。":""))
          .apply();
      return Result.success();
    }catch(Exception e){saveStatus(prefs,"更新异常："+e.getMessage()+"（原频道源保留）",0);return Result.retry();}
  }
  private static class Entry{String name,url,group;Entry(String n,String u,String g){name=n;url=u;group=g;}}
  private void parsePlaylist(String data,List<Entry> out){
    String name=null,group="",pendingGroup="";
    for(String raw:data.replace("\uFEFF","").replace('\r','\n').split("\n")){
      String line=raw.trim();if(line.isEmpty())continue;
      if(line.startsWith("#EXTGRP:")){pendingGroup=line.substring(8).trim();continue;}
      if(line.startsWith("#EXTINF")){
        int comma=-1;boolean quote=false;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(ch=='"')quote=!quote;else if(ch==','&&!quote){comma=i;break;}}
        name=comma>=0?line.substring(comma+1).trim():"";
        if(name.isEmpty()){java.util.regex.Matcher m=java.util.regex.Pattern.compile("tvg-name=\"([^\"]+)\"").matcher(line);if(m.find())name=m.group(1).trim();}
        if(name.isEmpty())name="未命名频道";
        group=pendingGroup;pendingGroup="";
        java.util.regex.Matcher gm=java.util.regex.Pattern.compile("group-title=\"([^\"]*)\"").matcher(line);if(gm.find()&&!gm.group(1).trim().isEmpty())group=gm.group(1).trim();
      }else if(!line.startsWith("#")&&(line.startsWith("http://")||line.startsWith("https://"))){
        if(name!=null&&!name.trim().isEmpty())out.add(new Entry(name,line,group));
        name=null;group="";
        if(out.size()>5000)break;
      }
    }
  }
  private int sourceQualityScore(String address){
    if(address==null)return 0;
    String u=address.toLowerCase(Locale.ROOT);
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
  private static class ProbeResult {
    boolean valid;
    int score;
    int width,height;
    long latencyMs;
    long bitrate;
  }
  private String scoreKey(String url){return Integer.toHexString(url.hashCode())+":"+url.length();}
  // GET rather than HEAD: many public IPTV hosts reject HEAD. HLS is accepted only
  // after its media playlist and a real media segment can both be reached.
  private ProbeResult probe(String address){
    ProbeResult result=new ProbeResult();
    long started=android.os.SystemClock.elapsedRealtime();
    try{
      String lower=address.toLowerCase(Locale.ROOT);
      if(lower.contains(".m3u8")||lower.contains("m3u8?")){
        byte[] manifestBytes=fetchSample(address,65536);
        if(manifestBytes==null||manifestBytes.length==0)return result;
        String manifest=new String(manifestBytes,"UTF-8").replace("\uFEFF","");
        if(!manifest.contains("#EXTM3U"))return result;
        String mediaUrl=address;
        long bestBandwidth=0L;
        int bestWidth=0,bestHeight=0;
        String[] lines=manifest.replace("\r","").split("\n");
        for(int i=0;i<lines.length;i++){
          String line=lines[i].trim();
          if(!line.startsWith("#EXT-X-STREAM-INF:"))continue;
          long bw=0L;int w=0,h=0;
          java.util.regex.Matcher bm=java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);
          if(bm.find())try{bw=Long.parseLong(bm.group(1));}catch(Exception ignored){}
          java.util.regex.Matcher rm=java.util.regex.Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(line);
          if(rm.find())try{w=Integer.parseInt(rm.group(1));h=Integer.parseInt(rm.group(2));}catch(Exception ignored){}
          String child="";
          for(int j=i+1;j<lines.length;j++){String candidate=lines[j].trim();if(!candidate.isEmpty()&&!candidate.startsWith("#")){child=candidate;break;}}
          if(!child.isEmpty()&&bw>=bestBandwidth){
            bestBandwidth=bw;bestWidth=w;bestHeight=h;
            mediaUrl=new URL(new URL(address),child).toString();
          }
        }
        if(!mediaUrl.equals(address)){
          byte[] variant=fetchSample(mediaUrl,65536);
          if(variant==null||variant.length==0)return result;
          String variantText=new String(variant,"UTF-8").replace("\uFEFF","");
          if(!variantText.contains("#EXTM3U"))return result;
          manifest=variantText;
        }
        String segment="";
        String[] mediaLines=manifest.replace("\r","").split("\n");
        for(int i=0;i<mediaLines.length;i++){
          if(mediaLines[i].trim().startsWith("#EXTINF:")){
            for(int j=i+1;j<mediaLines.length;j++){
              String candidate=mediaLines[j].trim();
              if(!candidate.isEmpty()&&!candidate.startsWith("#")){segment=candidate;break;}
            }
            if(!segment.isEmpty())break;
          }
        }
        if(segment.isEmpty())return result;
        String segmentUrl=new URL(new URL(mediaUrl),segment).toString();
        long segmentStart=android.os.SystemClock.elapsedRealtime();
        byte[] sample=fetchSample(segmentUrl,32768);
        if(sample==null||sample.length<188)return result;
        long segmentMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-segmentStart);
        result.valid=true;result.width=bestWidth;result.height=bestHeight;
        result.bitrate=bestBandwidth;result.latencyMs=Math.max(1L,segmentStart-started);
        int resolution=(bestWidth>=1920||bestHeight>=1080)?900:(bestWidth>=1280||bestHeight>=720)?700:(bestWidth>=854||bestHeight>=480)?500:300;
        int bandwidth=(int)Math.min(500L,bestBandwidth/12000L);
        int speed=(int)Math.min(300L,(sample.length*1000L/segmentMs)/2048L);
        int latencyPenalty=(int)Math.min(250L,result.latencyMs/20L);
        result.score=500+resolution+bandwidth+speed-latencyPenalty+300;
        return result;
      }
      long before=android.os.SystemClock.elapsedRealtime();
      byte[] sample=fetchSample(address,32768);
      if(sample==null||sample.length<188)return result;
      result.latencyMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-before);
      URL url=new URL(address);
      String ext=url.getPath().toLowerCase(Locale.ROOT);
      boolean knownExtension=ext.endsWith(".ts")||ext.endsWith(".mp4")||ext.endsWith(".m4v")||ext.endsWith(".flv")||ext.endsWith(".aac");
      if(!knownExtension&&!looksLikeMediaPayload(sample))return result;
      result.valid=true;
      result.score=350+(int)Math.min(300L,(sample.length*1000L/result.latencyMs)/2048L);
      return result;
    }catch(Exception e){return result;}
  }
  private boolean looksLikeMediaPayload(byte[] b){
    if(b==null||b.length<12)return false;
    if(b.length>188&&(b[0]&255)==0x47&&(b[188]&255)==0x47)return true; // MPEG-TS sync
    if(b.length>8&&b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p')return true; // MP4
    if(b[0]=='F'&&b[1]=='L'&&b[2]=='V')return true; // FLV
    if((b[0]&255)==0xFF&&((b[1]&0xF6)==0xF0))return true; // ADTS AAC
    return false;
  }
  private byte[] fetchSample(String address,int maxBytes)throws Exception{
    HttpURLConnection c=null;
    try{
      c=(HttpURLConnection)new URL(address).openConnection();
      c.setConnectTimeout(2500);c.setReadTimeout(3000);c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36 MyTV/1.24");
      c.setRequestProperty("Range","bytes=0-"+(maxBytes-1));
      int code=c.getResponseCode();if(code<200||code>=400)return null;
      String type=c.getContentType();
      if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))return null;
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[4096];int n,total=0;
        while((n=in.read(buffer))!=-1&&total<maxBytes){
          int take=Math.min(n,maxBytes-total);out.write(buffer,0,take);total+=take;
          if(total>=maxBytes)break;
        }
        return out.toByteArray();
      }
    }finally{if(c!=null)c.disconnect();}
  }
  private String read(InputStream in,int max)throws Exception{
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;
    while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("频道目录过大");out.write(b,0,n);}return out.toString("UTF-8");
  }
  private String identity(String name,String group){
    String v=name==null?"":name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_—–]","");
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:CCTV|中央电视台|央视)-?(\\d{1,2}\\+?|NEWS|4K|8K)").matcher(v);
    if(m.find())v="CCTV-"+m.group(1);
    String[] cn={"","一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六"};
    for(int i=1;i<cn.length;i++)if(v.contains("中央"+cn[i]+"套"))v="CCTV-"+i;
    return v.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
  }
  private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
  private void saveStatus(SharedPreferences p,String result,int added){p.edit().putString("source_update_time",now()).putString("source_update_result",result).apply();}
}
