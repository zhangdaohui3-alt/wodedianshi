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
  private static final int MAX_PROBES_PER_RUN = 24;
  private static final int MAX_SOURCES_PER_CHANNEL = 5;
  // Bound memory while ensuring later regional feeds are not starved by the global index.
  private static final int MAX_DISCOVERY_CANDIDATES = 24000;
  // Give every configured directory its own quota so an early large playlist
  // cannot consume the entire discovery budget and starve later regions/categories.
  private static final int MAX_DISCOVERY_PER_FEED = 1200;
  public SourceUpdateWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
  @NonNull @Override public Result doWork(){
    SharedPreferences prefs=getApplicationContext().getSharedPreferences("mytv",0);
    if(!getInputData().getBoolean("manual",false)&&!prefs.getBoolean("auto_source_update",true))return Result.success();
    if(prefs.getBoolean("source_update_playback_active",false)){
      saveStatus(prefs,"正在播放节目，暂缓抓源检测以避免占用带宽；退出播放后自动重试",0);
      return Result.retry();
    }
    int added=0,feedsOk=0,feedsFailed=0,checked=0;
    StringBuilder errors=new StringBuilder();
     String[] feeds={
       // China-first discovery: domestic and regional channels are read before the global index.
       "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
       "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
       "https://iptv-org.github.io/iptv/countries/cn.m3u",
       "https://iptv-org.github.io/iptv/countries/hk.m3u",
       "https://iptv-org.github.io/iptv/countries/mo.m3u",
       "https://iptv-org.github.io/iptv/countries/tw.m3u",
       // The current test region is included after China and its neighboring regions.
       "https://iptv-org.github.io/iptv/countries/us.m3u",
       "https://iptv-org.github.io/iptv/categories/news.m3u",
       "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
       "https://iptv-org.github.io/iptv/categories/movies.m3u",
       "https://iptv-org.github.io/iptv/categories/music.m3u",
       "https://iptv-org.github.io/iptv/categories/kids.m3u",
       "https://iptv-org.github.io/iptv/categories/documentary.m3u",
       "https://iptv-org.github.io/iptv/categories/weather.m3u",
       "https://iptv-org.github.io/iptv/categories/education.m3u",
       // These directories explicitly describe their entries as free/official/public.
       // Every URL must still pass the real playlist and media-segment probe.
       "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
       "https://biskyt.github.io/legalFreeIPTVplaylist/live.m3u",
       "https://raw.githubusercontent.com/freecasthub/public-iptv/main/playlist.m3u",
       // The large worldwide index is last so it cannot starve regional/category feeds.
       "https://iptv-org.github.io/iptv/index.m3u"
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
      // Include the user's already-saved backup URLs in the rotating health-check
      // pool. Previously only URLs rediscovered in public playlists were probed,
      // so a saved backup could silently die without ever losing its old score.
      ArrayList<Entry> savedPool=new ArrayList<>();
      HashSet<String> savedPoolUrls=new HashSet<>();
      for(int i=0;i<saved.length()&&savedPool.size()<20000;i++){
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
          if(savedPool.size()>=20000)break;
          // A shared URL only needs one network probe, even if several channel
          // entries reference it. Keep the first channel identity for diagnostics.
          if(savedPoolUrls.add(u))savedPool.add(new Entry(channelName,u,channelGroup,true));
        }
      }
      for(String feed:feeds){
        if(isStopped())return Result.retry();
        if(prefs.getBoolean("source_update_playback_active",false)){
          saveStatus(prefs,"检测到节目正在播放，已暂停抓源任务以保护播放带宽",0);
          return Result.retry();
        }
        HttpURLConnection c=null;
        try{
          c=(HttpURLConnection)new URL(feed).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(12000);
          c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.19 Android TV");c.setRequestProperty("Accept","*/*");
          int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
          String data;try(InputStream in=c.getInputStream()){data=read(in,15*1024*1024);}
          String normalized=data.replace("\uFEFF","").trim();
          if(!normalized.startsWith("#EXTM3U"))throw new IOException("目录格式无效");
          ArrayList<Entry> feedCandidates=new ArrayList<>();
          parsePlaylist(data,feedCandidates);
          int remaining=MAX_DISCOVERY_CANDIDATES-candidates.size();
          for(int i=0;i<feedCandidates.size()&&i<remaining;i++)candidates.add(feedCandidates.get(i));
          feedsOk++;
        }catch(Exception e){feedsFailed++;if(errors.length()<350)errors.append(feed.substring(feed.lastIndexOf('/')+1)).append("；");}
        finally{if(c!=null)c.disconnect();}
      }
      int savedScanStart=SourceSelectionPolicy.nextScanStart(savedPool, prefs.getString("source_saved_scan_after_key",""), prefs.getInt("source_saved_scan_cursor",0));
      int savedWindow=Math.min(MAX_PROBES_PER_RUN/2,savedPool.size());
      int discoveryScanStart=SourceSelectionPolicy.nextScanStart(candidates, prefs.getString("source_scan_after_key",""), prefs.getInt("source_scan_cursor",0));
      int discoveryWindow=Math.min(candidates.size(),MAX_PROBES_PER_RUN-savedWindow);
      ArrayList<Entry> probeCandidates=new ArrayList<>(savedWindow+discoveryWindow);
      int savedIndex=0,discoveryIndex=0;
      while(savedIndex<savedWindow||discoveryIndex<discoveryWindow){
        if(discoveryIndex<discoveryWindow){
          Entry e=candidates.get((discoveryScanStart+discoveryIndex)%candidates.size());
          probeCandidates.add(e);discoveryIndex++;
        }
        if(savedIndex<savedWindow){
          Entry e=savedPool.get((savedScanStart+savedIndex)%savedPool.size());
          probeCandidates.add(e);savedIndex++;
        }
      }
      // Use independent rotating cursors and a fixed per-run budget for discovery
      // and saved-source health checks. A moving 5,000-item window combined with one
      // cursor could skip most saved URLs forever as the window changed between runs.
      long probeDeadline=android.os.SystemClock.elapsedRealtime()+90L*1000L;
      int savedScanned=0,discoveryScanned=0;
      HashSet<String> visitedUrls=new HashSet<>();
      for(int offset=0;offset<probeCandidates.size();offset++){
        if(prefs.getBoolean("source_update_playback_active",false)){
          saveStatus(prefs,"检测到节目正在播放，已暂停后续线路检测以保护播放带宽",0);
          return Result.retry();
        }
        if(checked>=MAX_PROBES_PER_RUN||isStopped()||android.os.SystemClock.elapsedRealtime()>=probeDeadline)break;
        Entry e=probeCandidates.get(offset);
        if(e.savedSource)savedScanned++;else discoveryScanned++;
        if(!e.url.startsWith("http://")&&!e.url.startsWith("https://"))continue;
        if(!visitedUrls.add(e.url))continue;
        checked++;
        ProbeResult measured=probe(e.url);
        // A previously good source that now fails its probe must lose its old high score.
        // Keep the URL as a fallback, but rank it below sources that still pass validation.
        measuredScores.put(scoreKey(e.url),measured.valid?measured.score:-500);
        measuredScores.remove(legacyScoreKey(e.url));
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
      // Even if every discovery directory is temporarily unavailable, continue
      // rotating through already-saved source URLs. Existing channels still need
      // health checks while public catalogue services are down; retry discovery
      // separately after persisting these local probe results.
      boolean discoveryUnavailable=feedsOk==0;
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
      prefs.edit().putInt("source_scan_cursor",candidates.isEmpty()?0:(discoveryScanStart+discoveryScanned)%candidates.size())
          .putInt("source_saved_scan_cursor",savedPool.isEmpty()?0:(savedScanStart+savedScanned)%savedPool.size())
          .putString("source_scan_after_key",discoveryScanned>0?SourceSelectionPolicy.lastScannedKey(candidates,discoveryScanStart,discoveryScanned):prefs.getString("source_scan_after_key",""))
          .putString("source_saved_scan_after_key",savedScanned>0?SourceSelectionPolicy.lastScannedKey(savedPool,savedScanStart,savedScanned):prefs.getString("source_saved_scan_after_key",""))
          .putString("source_probe_scores_v1",measuredScores.toString())
          .putString("channels",saved.toString()).putString("source_update_time",now())
          .putString("source_update_result",(discoveryUnavailable?"所有公开目录暂不可用；仍完成已保存线路的轮检；":"成功读取 "+feedsOk+" 个目录；")+"初筛 "+checked+" 个候选地址；新增/替换 "+added+" 个优质备用地址；每频道最多保留 "+MAX_SOURCES_PER_CHANNEL+" 条线路；评分结合连续分片、清晰度、响应速度和本机播放反馈；仍需播放器验证画面与声音；"+(errors.length()>0?"部分目录失败。":""))
          .apply();
      return discoveryUnavailable?Result.retry():Result.success();
    }catch(Exception e){saveStatus(prefs,"更新异常："+e.getMessage()+"（原频道源保留）",0);return Result.retry();}
  }
  private static class Entry{
    String name,url,group;boolean savedSource;
    Entry(String n,String u,String g){this(n,u,g,false);}
    Entry(String n,String u,String g,boolean saved){name=n;url=u;group=g;savedSource=saved;}
  }
  private void parsePlaylist(String data,List<Entry> out){
    for(PlaylistParser.ChannelSource source:PlaylistParser.parse(data,MAX_DISCOVERY_PER_FEED)){
      out.add(new Entry(source.name,source.url,source.group));
    }
  }
  private int sourceQualityScore(String address){
    return SourceQualityScorer.labelScore(address);
  }
  private static class ProbeResult {
    boolean valid;
    int score;
    int width,height;
    long latencyMs;
    long bitrate;
  }
  private String scoreKey(String url){return StableKey.url(url);}
  private String legacyScoreKey(String url){return StableKey.legacyUrl(url);}
  // Match MainActivity's QoE aging so background ranking and playback ranking
  // do not disagree about the same source history.
  private double sourceHistoryWeight(long updatedAt){
    if(updatedAt<=0L)return 0.12d;
    long ageMs=Math.max(0L,System.currentTimeMillis()-updatedAt);
    double halfLifeMs=30.0d*24.0d*60.0d*60.0d*1000.0d;
    return Math.max(0.12d,Math.pow(0.5d,ageMs/halfLifeMs));
  }
  private double combinedScore(String url,JSONObject probes,JSONObject qoe){
    String key=scoreKey(url),legacyKey=legacyScoreKey(url);
    double probeScore=probes.has(key)?probes.optDouble(key,0.0):probes.optDouble(legacyKey,0.0);
    double score=probeScore+sourceQualityScore(url)*0.01;
    JSONObject m=qoe.optJSONObject(key);
    if(m==null)m=qoe.optJSONObject(legacyKey);
    if(m!=null&&m.optInt("attempts",0)>=2){
      int attempts=Math.max(1,m.optInt("attempts",0)),ready=m.optInt("ready",0),fail=m.optInt("failures",0),buffers=m.optInt("buffers",0);
      double firstFrameRate=(double)m.optInt("firstFrames",0)/attempts;
      double stableRate=(double)m.optInt("stablePlays",0)/attempts;
      double qoeAdjustment=(double)ready/attempts*450.0+firstFrameRate*450.0+stableRate*700.0;
      qoeAdjustment-=Math.min(120.0,m.optLong("avgReadyMs",0L)/200.0);
      qoeAdjustment-=Math.min(180.0,m.optLong("avgFirstFrameMs",0L)/180.0);
      int codecWarnings=m.optInt("audioCodecErrors",0)+m.optInt("audioSinkErrors",0)+m.optInt("videoCodecErrors",0);
      qoeAdjustment-=Math.min(60.0,codecWarnings*8.0);
      qoeAdjustment-=fail*90.0+buffers*55.0;
      score+=qoeAdjustment*sourceHistoryWeight(m.optLong("updated",0L));
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
        // Resolve relative HLS paths against the final URL after HTTP redirects.
        // CDN front doors commonly redirect playlists to a different host/path.
        SampleResponse manifestResponse=fetchSampleResponse(address,65536);
        byte[] manifestBytes=manifestResponse==null?null:manifestResponse.bytes;
        if(manifestBytes==null||manifestBytes.length==0)return result;
        String manifest=new String(manifestBytes,"UTF-8").replace("\uFEFF","");
        if(!manifest.contains("#EXTM3U"))return result;
        String manifestUrl=manifestResponse.finalUrl;
        String mediaUrl=manifestUrl;
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
            mediaUrl=new URL(new URL(manifestUrl),child).toString();
          }
        }
        if(!mediaUrl.equals(manifestUrl)){
          SampleResponse variantResponse=fetchSampleResponse(mediaUrl,65536);
          byte[] variant=variantResponse==null?null:variantResponse.bytes;
          if(variant==null||variant.length==0)return result;
          String variantText=new String(variant,"UTF-8").replace("\uFEFF","");
          if(!variantText.contains("#EXTM3U"))return result;
          manifest=variantText;
          mediaUrl=variantResponse.finalUrl;
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
        if(!looksLikeHlsSegment(sample))return result;
        long segmentMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-segmentStart);
        boolean secondSegmentOk=true;
        if(segments.size()>1){
          String secondUrl=new URL(new URL(mediaUrl),segments.get(1)).toString();
          byte[] second=fetchSample(secondUrl,8192);
          secondSegmentOk=looksLikeHlsSegment(second);
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
  // HLS media may be MPEG-TS or fragmented MP4 (CMAF/fMP4).
  // Do not reject valid fMP4 segments merely because they are shorter than 188 bytes.
  private boolean looksLikeHlsSegment(byte[] b){
    return MediaPayloadValidator.looksLikeHlsSegment(b);
  }
  private boolean hasIsoBmffBox(byte[] b){
    return MediaPayloadValidator.hasIsoBmffBox(b);
  }
  private boolean looksLikeMediaPayload(byte[] b){
    return MediaPayloadValidator.looksLikeMediaPayload(b);
  }
  private static class SampleResponse{
    final byte[] bytes;
    final String finalUrl;
    SampleResponse(byte[] data,String url){bytes=data;finalUrl=url;}
  }
  private byte[] fetchSample(String address,int maxBytes)throws Exception{
    SampleResponse response=fetchSampleResponse(address,maxBytes);
    return response==null?null:response.bytes;
  }
  private SampleResponse fetchSampleResponse(String address,int maxBytes)throws Exception{
    HttpURLConnection c=null;
    try{
      c=(HttpURLConnection)new URL(address).openConnection();
      c.setConnectTimeout(2500);c.setReadTimeout(3000);c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36 MyTV/1.24");
      c.setRequestProperty("Range","bytes=0-"+(maxBytes-1));
      int code=c.getResponseCode();if(code<200||code>=400)return null;
      String type=c.getContentType();
      if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))return null;
      String finalUrl=c.getURL().toString();
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[4096];int n,total=0;
        while((n=in.read(buffer))!=-1&&total<maxBytes){
          int take=Math.min(n,maxBytes-total);out.write(buffer,0,take);total+=take;
          if(total>=maxBytes)break;
        }
        return new SampleResponse(out.toByteArray(),finalUrl);
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
