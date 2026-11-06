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
  private static final int MAX_SOURCES_PER_CHANNEL = 5;
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
      "https://iptv-org.github.io/iptv/categories/documentary.m3u",
      // Additional curated public/free directories; candidates must still pass
      // the existing playlist and real media-segment probe before being added.
      "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
      "https://biskyt.github.io/legalFreeIPTVplaylist/live.m3u"
    };
    try{
      JSONArray saved=new JSONArray(prefs.getString("channels","[]"));
      LinkedHashMap<String,JSONObject> byIdentity=new LinkedHashMap<>();
      HashSet<String> knownUrls=new HashSet<>();
      JSONObject measuredScores=new JSONObject(prefs.getString("source_probe_scores_v1","{}"));
      JSONObject observedQoe=new JSONObject(prefs.getString("source_qoe_v1","{}"));
      for(int i=0;i<saved.length();i++){
        JSONObject c=saved.getJSONObject(i);byIdentity.put(identity(c.optString("name"),c.optString("group"),c.optString("url")),c);
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
      // Rotate through the complete candidate pool across update runs. This avoids permanently
      // favoring URLs with HD labels and ensures older known sources are periodically re-probed.
      // Bound each update run so a slow public directory cannot keep WorkManager busy indefinitely.
      long probeDeadline=android.os.SystemClock.elapsedRealtime()+6L*60L*1000L;
      int scanStart=candidates.isEmpty()?0:Math.floorMod(prefs.getInt("source_scan_cursor",0),candidates.size());
      int scanned=0;
      HashSet<String> visitedUrls=new HashSet<>();
      for(int offset=0;offset<candidates.size();offset++){
        if(checked>=MAX_NEW_SOURCES||isStopped()||android.os.SystemClock.elapsedRealtime()>=probeDeadline)break;
        scanned=offset+1;
        Entry e=candidates.get((scanStart+offset)%candidates.size());
        if(!e.url.startsWith("http://")&&!e.url.startsWith("https://"))continue;
        if(!visitedUrls.add(e.url))continue;
        checked++;
        ProbeResult measured=probe(e.url);
        // A previously good source that now fails its probe must lose its old high score.
        // Keep the URL as a fallback, but rank it below sources that still pass validation.
        measuredScores.put(scoreKey(e.url),measured.valid?measured.score:-500);
        if(!measured.valid)continue;
        // Known sources are re-tested and re-scored, but never inserted twice.
        if(knownUrls.contains(e.url))continue;
        String key=identity(e.name,e.group,e.url);JSONObject target=byIdentity.get(key);
        if(target==null){
          target=new JSONObject();JSONArray sources=new JSONArray();sources.put(e.url);
          target.put("name",e.name);target.put("url",e.url);target.put("group",e.group);target.put("sources",sources);
          saved.put(target);byIdentity.put(key,target);added++;
        }else{
          JSONArray sources=target.optJSONArray("sources");
          if(sources==null){sources=new JSONArray();String primary=target.optString("url");if(!primary.isEmpty())sources.put(primary);target.put("sources",sources);}
          boolean duplicate=false;for(int i=0;i<sources.length();i++)if(e.url.equals(sources.optString(i))){duplicate=true;break;}
          if(!duplicate){
            if(sources.length()<MAX_SOURCES_PER_CHANNEL){sources.put(e.url);added++;}
            else{
              int worstIndex=-1,worstScore=Integer.MAX_VALUE;
              for(int i=0;i<sources.length();i++){
                String old=sources.optString(i);
                int oldScore=(int)Math.round(combinedScore(old,measuredScores,observedQoe));
                if(oldScore<worstScore){worstScore=oldScore;worstIndex=i;}
              }
              int candidateScore=(int)Math.round(combinedScore(e.url,measuredScores,observedQoe));
              if(worstIndex>=0&&candidateScore>worstScore){sources.put(worstIndex,e.url);added++;}
            }
          }
        }
        knownUrls.add(e.url);
      }
      if(feedsOk==0){saveStatus(prefs,"检查失败：所有频道目录均无法连接（保留原频道源）",0);return Result.retry();}
      // Keep each channel's best few distinct sources together, ranked by measured
      // reachability/speed/resolution. Generic names remain host-separated; recognized
      // CCTV and satellite channels merge across source hosts.
      for(int i=0;i<saved.length();i++){
        JSONObject channel=saved.optJSONObject(i);if(channel==null)continue;
        JSONArray sourceArray=channel.optJSONArray("sources");
        if(sourceArray==null||sourceArray.length()==0){String primary=channel.optString("url","");if(primary.isEmpty())continue;sourceArray=new JSONArray();sourceArray.put(primary);}
        ArrayList<String> ordered=new ArrayList<>();
        for(int j=0;j<sourceArray.length();j++){String u=sourceArray.optString(j,"");if((u.startsWith("http://")||u.startsWith("https://"))&&!ordered.contains(u))ordered.add(u);}
        ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
        while(ordered.size()>MAX_SOURCES_PER_CHANNEL)ordered.remove(ordered.size()-1);
        JSONArray ranked=new JSONArray();for(String u:ordered)ranked.put(u);
        if(ranked.length()>0){channel.put("sources",ranked);channel.put("url",ranked.optString(0));channel.put("activeSource",0);}
      }
      // Persist measured quality independently from URL labels so the player can rank
      // candidates by reachability, estimated speed and manifest resolution. This is
      // a preflight score, not a guarantee of audio/video playback.
      prefs.edit().putInt("source_scan_cursor",candidates.isEmpty()?0:(scanStart+scanned)%candidates.size())
          .putString("source_probe_scores_v1",measuredScores.toString())
          .putString("channels",saved.toString()).putString("source_update_time",now())
          .putString("source_update_result","成功读取 "+feedsOk+" 个目录；初筛 "+checked+" 个候选地址；新增/替换 "+added+" 个优质备用地址；每频道最多保留 "+MAX_SOURCES_PER_CHANNEL+" 条线路；评分结合连续分片、清晰度、响应速度和本机播放反馈；仍需播放器验证画面与声音；"+(errors.length()>0?"部分目录失败。":""))
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
  private double combinedScore(String url,JSONObject probes,JSONObject qoe){
    double score=probes.optDouble(scoreKey(url),0.0)+sourceQualityScore(url)*0.01;
    JSONObject m=qoe.optJSONObject(scoreKey(url));
    if(m!=null&&m.optInt("attempts",0)>=2){
      int attempts=Math.max(1,m.optInt("attempts",0)),ready=m.optInt("ready",0),fail=m.optInt("failures",0),buffers=m.optInt("buffers",0);
      score+=(double)ready/attempts*1000.0;
      score-=Math.min(250.0,m.optLong("avgReadyMs",0L)/100.0);
      score-=fail*90.0+buffers*55.0;
    }
    return score;
  }
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
        ArrayList<String> segments=new ArrayList<>();
        String[] mediaLines=manifest.replace("\r","").split("\n");
        for(int i=0;i<mediaLines.length&&segments.size()<2;i++){
          if(mediaLines[i].trim().startsWith("#EXTINF:")){
            for(int j=i+1;j<mediaLines.length;j++){
              String candidate=mediaLines[j].trim();
              if(!candidate.isEmpty()&&!candidate.startsWith("#")){segments.add(candidate);break;}
            }
          }
        }
        if(segments.isEmpty())return result;
        String segmentUrl=new URL(new URL(mediaUrl),segments.get(0)).toString();
        long segmentStart=android.os.SystemClock.elapsedRealtime();
        byte[] sample=fetchSample(segmentUrl,32768);
        if(sample==null||sample.length<188)return result;
        long segmentMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-segmentStart);
        boolean secondSegmentOk=true;
        if(segments.size()>1){
          String secondUrl=new URL(new URL(mediaUrl),segments.get(1)).toString();
          byte[] second=fetchSample(secondUrl,8192);
          secondSegmentOk=second!=null&&second.length>=188;
        }
        result.valid=true;result.width=bestWidth;result.height=bestHeight;
        result.bitrate=bestBandwidth;result.latencyMs=Math.max(1L,segmentStart-started);
        int resolution=(bestWidth>=1920||bestHeight>=1080)?900:(bestWidth>=1280||bestHeight>=720)?700:(bestWidth>=854||bestHeight>=480)?500:300;
        int bandwidth=(int)Math.min(500L,bestBandwidth/12000L);
        int speed=(int)Math.min(300L,(sample.length*1000L/segmentMs)/2048L);
        int latencyPenalty=(int)Math.min(250L,result.latencyMs/20L);
        result.score=500+resolution+bandwidth+speed-latencyPenalty+300+(secondSegmentOk?120:(segments.size()>1?-180:0));
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
  private String identity(String name,String group,String url){
    String v=canonicalName(name);
    if(v.startsWith("CCTV-"))return v.toLowerCase(Locale.ROOT)+"|cctv";
    if(v.contains("卫视"))return v.toLowerCase(Locale.ROOT)+"|satellite";
    String base=v.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
    try{java.net.URI u=java.net.URI.create(url==null?"":url);String host=u.getHost();if(host!=null&&!host.isEmpty())return base+"|host:"+host.toLowerCase(Locale.ROOT);}catch(Exception ignored){}
    return base+"|url:"+(url==null?"":url.trim());
  }
  private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
  private void saveStatus(SharedPreferences p,String result,int added){p.edit().putString("source_update_time",now()).putString("source_update_result",result).apply();}
}
