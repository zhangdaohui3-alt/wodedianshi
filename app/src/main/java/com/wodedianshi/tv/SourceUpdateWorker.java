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
  private static final long PROBE_STAGE_DEADLINE_MS = 90_000L;
  private static final int MAX_SOURCES_PER_CHANNEL = 8;
  // Bound memory while ensuring later regional feeds are not starved by the global index.
  private static final int MAX_DISCOVERY_CANDIDATES = 24000;
  // Four rotating entries per feed cover the eight China/HMT feeds in two 24-probe
  // batches. The cursor advances only after the whole batch has been probed.
  private static final int MAX_DISCOVERY_PER_FEED = 4;
  public SourceUpdateWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
  @NonNull @Override public Result doWork(){
    SharedPreferences prefs=getApplicationContext().getSharedPreferences("mytv",0);
    final boolean manual=getInputData().getBoolean("manual",false);
    final boolean initialDiscoveryChain=getInputData().getBoolean("initial_discovery_chain",false);
    if(!manual&&!prefs.getBoolean("auto_source_update",true))return Result.success();
    if(prefs.getBoolean("source_update_playback_active",false)){
      saveStatus(prefs,"正在播放节目，暂缓抓源检测以避免占用带宽；退出播放后自动重试",0);
      return Result.retry();
    }
    final boolean foregroundAtStart=isActivityActive(prefs);
    // Automatic discovery parses the saved catalogue and builds sizeable indexes.
    // Defer that work while the TV app is active so it cannot compete with playback,
    // channel rendering, or remote-control input. Explicit manual updates remain available.
    if(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(foregroundAtStart,manual)){
      saveStatus(prefs,"应用正在前台使用，自动频道更新已延后；退出应用后会在后台继续",0);
      return Result.retry();
    }
    int added=0,feedsOk=0,feedsWithEntries=0,discoveryProbesValid=0,feedsFailed=0,checked=0;
    StringBuilder errors=new StringBuilder();
     String[] feeds={
       // China-first discovery: domestic and regional channels are read before the global index.
       "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
       "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv_status.m3u8",
       "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
       "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province_status.m3u8",
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
      // Snapshot the user's current catalogue before discovery/probing. The Activity
      // may save favorites, edits, imports or playback state while this worker runs.
      // Never let a stale worker snapshot overwrite those newer user changes.
      com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot roomSnapshot =
          com.wodedianshi.tv.db.RoomCatalogueReader.read(getApplicationContext());
      // Room is the only catalogue authority. If first-install seeding has not
      // completed yet, retry rather than resurrecting stale SharedPreferences JSON.
      if(roomSnapshot==null) {
        saveStatus(prefs,"Room频道目录尚未完成初始化，后台更新稍后重试",0);
        return Result.retry();
      }
      String channelsSnapshot=roomSnapshotToArray(roomSnapshot).toString();
      String savedChannelsJson=channelsSnapshot;
      JSONArray saved=new JSONArray(savedChannelsJson);
      LinkedHashMap<String,JSONObject> byIdentity=new LinkedHashMap<>();
      HashSet<String> knownUrls=new HashSet<>();
      JSONObject measuredScores=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_probe_scores_v1","{}"));
      JSONObject observedQoe=new JSONObject(com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),"source_qoe_v1","{}"));
      for(int i=0;i<saved.length();i++){
        JSONObject c=saved.getJSONObject(i);byIdentity.put(identity(c.optString("name"),c.optString("group"),
            c.optString("url"),c.optString("tvgId"),c.optString("tvgName")),c);
        JSONArray src=c.optJSONArray("sources");
        if(src!=null)for(int j=0;j<src.length();j++)knownUrls.add(SourceSelectionPolicy.endpointKey(src.optString(j)));
        knownUrls.add(SourceSelectionPolicy.endpointKey(c.optString("url")));
      }
      ArrayList<Entry> candidates=new ArrayList<>();
      // Include the user's already-saved backup URLs in the rotating health-check
      // pool. Previously only URLs rediscovered in public playlists were probed,
      // so a saved backup could silently die without ever losing its old score.
      ArrayList<Entry> savedPool=new ArrayList<>();
      HashSet<String> savedPoolUrls=new HashSet<>();
      // A foreground China-first pass validates newly discovered domestic candidates only.
      // Avoid allocating up to 20,000 additional saved-source Entry objects while the
      // TV UI is open; the full rotating health-check pool is retained for background runs.
      if(!foregroundAtStart&&!initialDiscoveryChain){
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
            if(savedPoolUrls.add(SourceSelectionPolicy.endpointKey(u)))savedPool.add(new Entry(
                channelName,u,channelGroup,true,"",channel.optString("tvgId",""),channel.optString("tvgName","")));
          }
        }
      }
      ArrayList<FeedBatch> feedBatches=new ArrayList<>();
      for(int feedIndex=0;feedIndex<feeds.length;feedIndex++){
        if((foregroundAtStart||initialDiscoveryChain)&&feedIndex>=8)break;
        if(!foregroundAtStart&&feedIndex>=6&&isActivityActive(prefs))break;
        if(initialDiscoveryChain&&roomBoolean(initialFeedDoneKey(feedIndex),false))continue;
        String feed=feeds[feedIndex];
        int feedCursor=roomInt(feedCursorKey(feedIndex),0);
        int[] parsedCount={0};
        boolean feedReadSucceeded=false;
        if(isStopped())return Result.retry();
        if(prefs.getBoolean("source_update_playback_active",false)){
          saveStatus(prefs,"检测到节目正在播放，已暂停抓源任务以保护播放带宽",0);
          return Result.retry();
        }
        HttpURLConnection c=null;
        ArrayList<Entry> feedCandidates=new ArrayList<>();
        boolean feedCandidatesAdded=false;
        try{
          c=(HttpURLConnection)new URL(feed).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(12000);
          c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","MyTV/1.19 Android TV");c.setRequestProperty("Accept","*/*");
          int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("HTTP "+code);
          // Parse directly from the network stream. The parser stops at this feed's
          // quota, avoiding a full 15 MiB String allocation for large global indexes.
          try(InputStream in=c.getInputStream();
              BufferedReader reader=new BufferedReader(
                  new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8),8192)){
            String header=reader.readLine();
            if(header==null||!header.replace("\uFEFF","").trim().startsWith("#EXTM3U"))
              throw new IOException("目录格式无效");
            parsedCount[0]=parsePlaylist(reader,feedCandidates,scopeForFeed(feed),
                feedCursor,MAX_DISCOVERY_PER_FEED);
          }
          if(!feedCandidates.isEmpty())feedsWithEntries++;
          int remaining=MAX_DISCOVERY_CANDIDATES-candidates.size();
          for(int i=0;i<feedCandidates.size()&&i<remaining;i++)candidates.add(feedCandidates.get(i));
          feedCandidatesAdded=true;
          feedReadSucceeded=true;
          feedsOk++;
        }catch(Exception e){
          // Keep complete entries parsed before a transient stream read failure;
          // an incomplete feed is still reported as failed and will be retried later.
          if(!feedCandidatesAdded){
            int remaining=MAX_DISCOVERY_CANDIDATES-candidates.size();
            for(int i=0;i<feedCandidates.size()&&i<remaining;i++)candidates.add(feedCandidates.get(i));
            if(!feedCandidates.isEmpty())feedsWithEntries++;
          }
          feedsFailed++;if(errors.length()<350)errors.append(feed.substring(feed.lastIndexOf('/')+1)).append("；");
        }finally{
          if(c!=null)c.disconnect();
          feedBatches.add(new FeedBatch(feedIndex,feedCursor,parsedCount[0],feedReadSucceeded));
        }
      }
      ArrayList<String> savedScanUrls=new ArrayList<>(savedPool.size());
      for(Entry entry:savedPool)savedScanUrls.add(entry.url);
      ArrayList<String> discoveryScanUrls=new ArrayList<>(candidates.size());
      for(Entry entry:candidates)discoveryScanUrls.add(entry.url);
      int savedScanStart=SourceSelectionPolicy.nextScanStart(savedScanUrls, roomString("source_saved_scan_after_key",""), roomInt("source_saved_scan_cursor",0));
      int savedWindow=Math.min(MAX_PROBES_PER_RUN/2,savedPool.size());
      int discoveryScanStart=SourceSelectionPolicy.nextScanStart(discoveryScanUrls, roomString("source_scan_after_key",""), roomInt("source_scan_cursor",0));
      int discoveryWindow=Math.min(candidates.size(),MAX_PROBES_PER_RUN-savedWindow);
      String discoveryFingerprint=discoveryFingerprint(candidates);
      String previousDiscoveryFingerprint=roomString("source_feed_batch_fingerprint","");
      int initialDiscoveryScanned=discoveryFingerprint.equals(previousDiscoveryFingerprint)
          ?roomInt("source_feed_batch_scanned",0):0;
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
      long probeDeadline=android.os.SystemClock.elapsedRealtime()+PROBE_STAGE_DEADLINE_MS;
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
        if(!visitedUrls.add(SourceSelectionPolicy.endpointKey(e.url)))continue;
        checked++;
        ProbeResult measured=probe(e.url,probeDeadline);
        if(probeDeadlineReached(probeDeadline)){
          // A source interrupted by the global deadline is inconclusive, not a failed source.
          if(e.savedSource)savedScanned--;else discoveryScanned--;
          break;
        }
        // A previously good source that now fails its probe must lose its old high score.
        // Keep the URL as a fallback, but rank it below sources that still pass validation.
        measuredScores.put(scoreKey(e.url),measured.valid?measured.score:-500);
        measuredScores.remove(legacyScoreKey(e.url));
        if(!measured.valid)continue;
        if(!e.savedSource)discoveryProbesValid++;
        String key=identity(e.name,e.group,e.url,e.tvgId,e.tvgName);JSONObject target=byIdentity.get(key);
        if(target!=null){
          String canonical=ChannelNamePolicy.canonicalBroadcasterName(
              e.name,e.tvgName,e.tvgId,e.group);
          if(!canonical.isEmpty())target.put("name",canonical);
          if(target.optString("tvgId","").isEmpty()&&!e.tvgId.isEmpty())target.put("tvgId",e.tvgId);
          if(target.optString("tvgName","").isEmpty()&&!e.tvgName.isEmpty())target.put("tvgName",e.tvgName);
        }
        // A known URL may appear in a second directory. Record that provenance
        // without duplicating the backup or probing the same address twice.
        if(knownUrls.contains(SourceSelectionPolicy.endpointKey(e.url))){
          if(target!=null)addBackupSourceScope(target,e.url,e.scope);
          continue;
        }
        if(target==null){
          target=new JSONObject();JSONArray sources=new JSONArray();sources.put(e.url);
          target.put("name",canonicalDisplayName(e));target.put("url",e.url);target.put("group",e.group);
          target.put("tvgId",e.tvgId);target.put("tvgName",e.tvgName);target.put("sources",sources);
          addBackupSourceScope(target,e.url,e.scope);
          saved.put(target);byIdentity.put(key,target);added++;
        }else{
          JSONArray sources=target.optJSONArray("sources");
          if(sources==null){sources=new JSONArray();String primary=target.optString("url");if(!primary.isEmpty())sources.put(primary);target.put("sources",sources);}
          String candidateEndpoint=SourceSelectionPolicy.endpointKey(e.url);
          boolean duplicate=false;for(int i=0;i<sources.length();i++)if(candidateEndpoint.equals(SourceSelectionPolicy.endpointKey(sources.optString(i)))){duplicate=true;break;}
          if(!duplicate){
            if(sources.length()<MAX_SOURCES_PER_CHANNEL){sources.put(e.url);addBackupSourceScope(target,e.url,e.scope);added++;}
            else{
              int worstIndex=-1,worstScore=Integer.MAX_VALUE;
              for(int i=0;i<sources.length();i++){
                String old=sources.optString(i);
                int oldScore=(int)Math.round(combinedScore(old,measuredScores,observedQoe));
                if(oldScore<worstScore){worstScore=oldScore;worstIndex=i;}
              }
              int candidateScore=(int)Math.round(combinedScore(e.url,measuredScores,observedQoe));
              if(worstIndex>=0&&candidateScore>worstScore){sources.put(worstIndex,e.url);addBackupSourceScope(target,e.url,e.scope);added++;}
            }
          }
        }
        knownUrls.add(SourceSelectionPolicy.endpointKey(e.url));
      }
      // Even if every discovery directory is temporarily unavailable, continue
      // rotating through already-saved source URLs. Existing channels still need
      // health checks while public catalogue services are down; retry discovery
      // separately after persisting these local probe results.
      boolean discoveryUnavailable=feedsOk==0;
      // Keep each channel's best distinct sources together, ranked by measured
      // reachability/speed/resolution. Generic names remain host-separated; recognized
      // CCTV and satellite channels merge across source hosts.
      for(int i=0;i<saved.length();i++){
        JSONObject channel=saved.optJSONObject(i);if(channel==null)continue;
        JSONArray sourceArray=channel.optJSONArray("sources");
        if(sourceArray==null||sourceArray.length()==0){String primary=channel.optString("url","");if(primary.isEmpty())continue;sourceArray=new JSONArray();sourceArray.put(primary);}
        // Preserve the user's last selected source across background re-ranking.
        // Ranking may change the preferred primary URL, but must not silently
        // replace the source that the user explicitly selected for this channel.
        int previousActiveIndex=Math.max(0,channel.optInt("activeSource",0));
        String previousActiveUrl=previousActiveIndex<sourceArray.length()?sourceArray.optString(previousActiveIndex,""):channel.optString("url","");
        ArrayList<String> ordered=new ArrayList<>();
        HashSet<String> orderedEndpoints=new HashSet<>();
        for(int j=0;j<sourceArray.length();j++){
          String u=sourceArray.optString(j,"");
          if((u.startsWith("http://")||u.startsWith("https://"))
              &&orderedEndpoints.add(SourceSelectionPolicy.endpointKey(u)))ordered.add(u);
        }
        String stablePrimaryUrl=channel.optString("url","");
        // Preserve both the stable channel identity and the user's last selected
        // source even when the ranked list is trimmed to the five-source cap.
        ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
        ordered=new ArrayList<>(SourceRankingRetentionPolicy.retainRequiredAndBest(
            ordered,stablePrimaryUrl,previousActiveUrl,MAX_SOURCES_PER_CHANNEL));
        ordered.sort((a,b)->Double.compare(combinedScore(b,measuredScores,observedQoe),combinedScore(a,measuredScores,observedQoe)));
        JSONArray ranked=new JSONArray();int restoredActiveIndex=0;
        for(int j=0;j<ordered.size();j++){String u=ordered.get(j);ranked.put(u);if(u.equals(previousActiveUrl))restoredActiveIndex=j;}
        if(ranked.length()>0){
          // Keep url as the stable channel identity. The ranked source list and
          // activeSource carry playback preference independently.
          channel.put("sources",ranked);channel.put("activeSource",restoredActiveIndex);
          retainBackupSourceScopes(channel,ranked);
        }
      }
      // Persist measured quality independently from URL labels so the player can rank
      // candidates by reachability, estimated speed and manifest resolution. This is
      // a preflight score, not a guarantee of audio/video playback.
      trimProbeScores(measuredScores,savedPool,probeCandidates);
      // The database snapshot is checked again at commit time by Room's transaction;
      // no SharedPreferences catalogue comparison participates in this update.
      // Playback can begin after the last probe-loop check but before persistence.
      // Re-check immediately before the catalogue commit so a just-started session
      // is not overlapped by the worker's final state write.
      if(prefs.getBoolean("source_update_playback_active",false)){
        saveStatus(prefs,"检测到节目刚开始播放，已放弃本轮写入并安排稍后重试",0);
        return Result.retry();
      }
      if(isStopped())return Result.retry();
      // Write the validated snapshot directly to the single Room catalogue.
      // The JSON object above is only this worker's in-memory discovery model;
      // Room receives normalized channel/source rows and remains the durable store.
      int roomRowsWritten = com.wodedianshi.tv.db.RoomCatalogueWriter.writeSnapshot(
          getApplicationContext(), saved);
      if (saved.length() > 0 && roomRowsWritten == 0) {
        throw new IllegalStateException("Room catalogue update produced no channel rows");
      }
      // No JSON hand-off: the validated directory is committed directly to Room.
      int nextBatchScanned=Math.min(candidates.size(),initialDiscoveryScanned+discoveryScanned);
      boolean feedBatchComplete=nextBatchScanned>=candidates.size();
      boolean initialDiscoveryComplete=initialDiscoveryChain&&feedBatchComplete
          &&areInitialChinaFeedsComplete(prefs,feedBatches);
      com.wodedianshi.tv.db.AppStateEditor update=new com.wodedianshi.tv.db.AppStateEditor(getApplicationContext())
          .putInt("source_scan_cursor",candidates.isEmpty()?0:(discoveryScanStart+discoveryScanned)%candidates.size())
          .putInt("source_saved_scan_cursor",savedPool.isEmpty()?0:(savedScanStart+savedScanned)%savedPool.size())
          .putString("source_scan_after_key",discoveryScanned>0?SourceSelectionPolicy.lastScannedKey(discoveryScanUrls,discoveryScanStart,discoveryScanned):roomString("source_scan_after_key",""))
          .putString("source_saved_scan_after_key",savedScanned>0?SourceSelectionPolicy.lastScannedKey(savedScanUrls,savedScanStart,savedScanned):roomString("source_saved_scan_after_key",""))
          .putString("source_probe_scores_v1",measuredScores.toString())
          // The exact catalogue snapshot is represented by a compact fingerprint.
          .putString("source_update_time",now())
          .putString("source_update_result","已安全暂存候选目录；"+(foregroundAtStart||isActivityActive(prefs)?"本轮优先读取中国及港澳台目录，全球目录留待后台运行时补充；":"本轮已检查中国及全球公开目录；")+(discoveryUnavailable?"公开目录暂不可用，仍完成已保存线路的轮检；":(feedsWithEntries==0?"目录可访问，但没有解析出有效频道条目；":(discoveryProbesValid==0?"已解析频道条目，但候选线路均未通过媒体预检，本次不计为首次发现成功；":"成功读取 "+feedsOk+" 个目录，"+feedsWithEntries+" 个含频道条目，"+discoveryProbesValid+" 条候选通过媒体预检；")))+"初筛 "+checked+" 个候选地址；新增/替换 "+added+" 个备用地址；每频道最多保留 "+MAX_SOURCES_PER_CHANNEL+" 条线路；网络初筛不能保证实际画面与声音，仍需播放器验证；"+(errors.length()>0?"部分目录失败。":""));
      // Advance a feed only after every candidate in the current multi-feed batch
      // has had its probe turn. This prevents losing the tail when the probe deadline
      // interrupts a run, and ensures later playlist pages are eventually discovered.
      if(feedBatchComplete){
        for(FeedBatch batch:feedBatches){
          if(!batch.success)continue;
          if(initialDiscoveryChain&&batch.parsed<MAX_DISCOVERY_PER_FEED){
            update.putBoolean(initialFeedDoneKey(batch.index),true)
                .remove(feedCursorKey(batch.index));
          }else if(batch.parsed>0){
            update.putInt(feedCursorKey(batch.index),batch.cursor+batch.parsed);
          }else if(initialDiscoveryChain){
            update.putBoolean(initialFeedDoneKey(batch.index),true)
                .remove(feedCursorKey(batch.index));
          }else{
            update.remove(feedCursorKey(batch.index));
          }
        }
        update.remove("source_feed_batch_fingerprint").remove("source_feed_batch_scanned");
      }else if(candidates.size()>0){
        update.putString("source_feed_batch_fingerprint",discoveryFingerprint)
            .putInt("source_feed_batch_scanned",nextBatchScanned);
      }
      if(initialDiscoveryChain&&initialDiscoveryComplete){
        update.remove("source_initial_discovery_fingerprint").remove("source_initial_discovery_scanned");
        if(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(discoveryProbesValid))
          update.putLong("source_update_last_success_at",System.currentTimeMillis());
      }else if(!initialDiscoveryChain&&SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(discoveryProbesValid)){
        update.putLong("source_update_last_success_at",System.currentTimeMillis());
      }
      update.remove("source_update_pending_base_fingerprint")
          .remove("source_update_pending_base")
          .remove("source_update_pending_channels");
      update.apply();
      if(initialDiscoveryChain&&!initialDiscoveryComplete
          &&(candidates.size()>0||feedsFailed>0)){
        androidx.work.OneTimeWorkRequest continuation=
            new androidx.work.OneTimeWorkRequest.Builder(SourceUpdateWorker.class)
                .setConstraints(new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
                    .putBoolean("initial_discovery_chain",true).build())
                .build();
        androidx.work.WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
            "mytv_startup_source_refresh",androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,continuation);
      }
      return (discoveryUnavailable&&!(initialDiscoveryChain&&candidates.size()>0))
          ||(initialDiscoveryChain&&!initialDiscoveryComplete&&candidates.isEmpty()&&feedsFailed>0)
          ?Result.retry():Result.success();
    }catch(Exception e){saveStatus(prefs,"更新异常："+e.getMessage()+"（原频道源保留）",0);return Result.retry();}
  }
  private boolean areInitialChinaFeedsComplete(SharedPreferences prefs,List<FeedBatch> batches){
    for(int i=0;i<8;i++){
      boolean done=roomBoolean(initialFeedDoneKey(i),false);
      if(!done){
        for(FeedBatch batch:batches){
          if(batch.index==i&&batch.success&&batch.parsed<MAX_DISCOVERY_PER_FEED){done=true;break;}
        }
      }
      if(!done)return false;
    }
    return true;
  }
  private String discoveryFingerprint(List<Entry> entries){
    StringBuilder value=new StringBuilder(entries.size()*48);
    for(Entry entry:entries)value.append(SourceSelectionPolicy.endpointKey(entry.url)).append('\n');
    return Integer.toHexString(value.toString().hashCode())+":"+entries.size();
  }
  private JSONArray roomSnapshotToArray(com.wodedianshi.tv.db.RoomCatalogueReader.Snapshot snapshot)
      throws JSONException {
    JSONArray result=new JSONArray();
    for(com.wodedianshi.tv.db.RoomCatalogueReader.ChannelRecord row:snapshot.channels){
      JSONObject channel=new JSONObject();
      channel.put("name",row.name);channel.put("url",row.url);channel.put("group",row.group);
      channel.put("tvgId",row.tvgId);channel.put("tvgName",row.tvgName);
      channel.put("activeSource",row.activeSource);
      JSONArray sources=new JSONArray();
      for(String source:row.sources)sources.put(source);
      channel.put("sources",sources);
      JSONArray channelScopes=new JSONArray();
      for(String scope:row.sourceScopes)channelScopes.put(scope);
      channel.put("sourceScopes",channelScopes);
      JSONArray ownership=new JSONArray();
      for(java.util.Map.Entry<String,java.util.HashSet<String>> entry:row.backupSourceScopes.entrySet()){
        JSONObject item=new JSONObject();item.put("url",entry.getKey());
        JSONArray scopes=new JSONArray();
        for(String scope:entry.getValue())scopes.put(scope);
        item.put("scopes",scopes);ownership.put(item);
      }
      channel.put("backupSourceScopes",ownership);
      result.put(channel);
    }
    return result;
  }

  private boolean isActivityActive(SharedPreferences prefs){
    boolean active=prefs.getBoolean("source_update_activity_active",false);
    long heartbeat=prefs.getLong("source_update_activity_heartbeat",0L);
    // An expired lease is treated as inactive without writing back a stale value.
    // This avoids racing a just-started Activity that has refreshed the heartbeat.
    return SourceUpdateActivityPolicy.isLeaseFresh(active,heartbeat,System.currentTimeMillis());
  }
  private static class Entry{
    String name,url,group,scope,tvgId,tvgName;boolean savedSource;
    // Unscoped saved URLs have unknown provenance; only discovered feeds carry an explicit scope.
    Entry(String n,String u,String g){this(n,u,g,false,"","","");}
    Entry(String n,String u,String g,boolean saved){this(n,u,g,saved,"","","");}
    Entry(String n,String u,String g,boolean saved,String sourceScope){
      this(n,u,g,saved,sourceScope,"","");
    }
    Entry(String n,String u,String g,boolean saved,String sourceScope,String id,String guideName){
      name=n;url=u;group=g;savedSource=saved;scope=sourceScope;
      tvgId=id==null?"":id;tvgName=guideName==null?"":guideName;
    }
  }
  private static class FeedBatch{
    final int index,cursor,parsed;final boolean success;
    FeedBatch(int i,int c,int p,boolean ok){index=i;cursor=c;parsed=p;success=ok;}
  }
  private String scopeForFeed(String feed){
    return SourceFeedScopePolicy.scopeForFeed(feed);
  }
  private int parsePlaylist(Reader reader,List<Entry> out,String sourceScope,
                            int skipEntries,int maxEntries)throws IOException{
    return PlaylistParser.parseEach(reader,skipEntries,maxEntries,source->{
      String scope=RegionCategoryPolicy.catalogueScope(source.name,source.group,sourceScope);
      out.add(new Entry(source.name,source.url,source.group,false,scope,
          source.tvgId,source.tvgName));
    });
  }
  private String feedCursorKey(int index){return "source_feed_cursor_"+index;}
  private String initialFeedDoneKey(int index){return "source_initial_feed_done_"+index;}
  private String canonicalDisplayName(Entry entry){
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(
        entry.name,entry.tvgName,entry.tvgId,entry.group);
    if(!broadcaster.isEmpty())return broadcaster;
    String canonical=canonicalName(entry.name);
    return canonical.startsWith("CCTV-")||canonical.endsWith("卫视")?canonical:entry.name;
  }
  private void addBackupSourceScope(JSONObject channel,String url,String scope)throws JSONException{
    if(channel==null||url==null||url.isEmpty())return;
    if(!"china".equals(scope)&&!"global".equals(scope)&&!"custom".equals(scope))return;
    JSONArray channelScopes=channel.optJSONArray("sourceScopes");
    if(channelScopes==null){channelScopes=new JSONArray();channel.put("sourceScopes",channelScopes);}
    boolean channelHasScope=false;
    for(int i=0;i<channelScopes.length();i++)if(scope.equals(channelScopes.optString(i))){channelHasScope=true;break;}
    if(!channelHasScope)channelScopes.put(scope);
    JSONArray ownership=channel.optJSONArray("backupSourceScopes");
    if(ownership==null){ownership=new JSONArray();channel.put("backupSourceScopes",ownership);}
    for(int i=0;i<ownership.length();i++){
      JSONObject item=ownership.optJSONObject(i);
      if(item==null||!url.equals(item.optString("url","")))continue;
      JSONArray scopes=item.optJSONArray("scopes");
      if(scopes==null){scopes=new JSONArray();item.put("scopes",scopes);}
      for(int j=0;j<scopes.length();j++)if(scope.equals(scopes.optString(j)))return;
      scopes.put(scope);return;
    }
    JSONObject item=new JSONObject();JSONArray scopes=new JSONArray();
    scopes.put(scope);item.put("url",url);item.put("scopes",scopes);ownership.put(item);
  }
  private void retainBackupSourceScopes(JSONObject channel,JSONArray retainedSources)throws JSONException{
    JSONArray previous=channel.optJSONArray("backupSourceScopes");
    if(previous==null)return;
    HashSet<String> retainedUrls=new HashSet<>();
    for(int i=0;i<retainedSources.length();i++)retainedUrls.add(retainedSources.optString(i,""));
    JSONArray filtered=new JSONArray();
    HashMap<String,Set<String>> ownershipByUrl=new HashMap<>();
    for(int i=0;i<previous.length();i++){
      JSONObject item=previous.optJSONObject(i);
      if(item==null)continue;
      String url=item.optString("url","");
      if(!retainedUrls.contains(url))continue;
      filtered.put(item);
      JSONArray scopes=item.optJSONArray("scopes");
      HashSet<String> owned=new HashSet<>();
      if(scopes!=null)for(int j=0;j<scopes.length();j++)owned.add(scopes.optString(j,""));
      ownershipByUrl.put(url,owned);
    }
    channel.put("backupSourceScopes",filtered);
    // Channel-level tags are a derived union of the URLs that survived the
    // eight-source retention cap. Do not leave stale China/global membership.
    java.util.TreeSet<String> sortedScopes=new java.util.TreeSet<>(
        ChannelCatalogueScopePolicy.retainedScopes(ownershipByUrl,retainedUrls));
    JSONArray channelScopes=new JSONArray();
    for(String scope:sortedScopes)channelScopes.put(scope);
    channel.put("sourceScopes",channelScopes);
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
  // Keep the persisted preflight-score index bounded. Discovery feeds can rotate
  // thousands of URLs over time; an unbounded JSON object would slow every update
  // and gradually consume device storage. The current scan rewrites recent entries.
  private static final int MAX_PERSISTED_PROBE_SCORES = 8000;
  private void trimProbeScores(JSONObject scores,List<Entry> savedPool,List<Entry> recentCandidates){
    if(scores==null||scores.length()<=MAX_PERSISTED_PROBE_SCORES)return;
    // JSONObject key iteration order is not an LRU contract. Preserve scores for
    // currently saved channel URLs first, then this run's recent candidates, before
    // filling remaining capacity with older entries.
    LinkedHashSet<String> keep=new LinkedHashSet<>();
    if(savedPool!=null)for(Entry entry:savedPool){
      if(keep.size()>=MAX_PERSISTED_PROBE_SCORES)break;
      String key=scoreKey(entry.url);if(scores.has(key))keep.add(key);
    }
    if(recentCandidates!=null)for(int i=recentCandidates.size()-1;i>=0&&keep.size()<MAX_PERSISTED_PROBE_SCORES;i--){
      String key=scoreKey(recentCandidates.get(i).url);if(scores.has(key))keep.add(key);
    }
    Iterator<String> keys=scores.keys();
    while(keys.hasNext()&&keep.size()<MAX_PERSISTED_PROBE_SCORES)keep.add(keys.next());
    JSONObject retained=new JSONObject();
    for(String key:keep)try{retained.put(key,scores.get(key));}catch(JSONException ignored){}
    Iterator<String> remove=scores.keys();ArrayList<String> oldKeys=new ArrayList<>();
    while(remove.hasNext())oldKeys.add(remove.next());
    for(String key:oldKeys)scores.remove(key);
    Iterator<String> retainedKeys=retained.keys();
    while(retainedKeys.hasNext()){
      String key=retainedKeys.next();
      try{scores.put(key,retained.get(key));}catch(JSONException ignored){}
    }
  }
  private String scoreKey(String url){return StableKey.url(url);}
  private String legacyScoreKey(String url){return StableKey.legacyUrl(url);}
  private double combinedScore(String url,JSONObject probes,JSONObject qoe){
    String key=scoreKey(url),legacyKey=legacyScoreKey(url);
    double probeScore=probes.has(key)?probes.optDouble(key,0.0):probes.optDouble(legacyKey,0.0);
    double score=probeScore+sourceQualityScore(url)*0.01;
    // Keep a currently failed preflight below unprobed/valid sources even if its old QoE was excellent.
    if(SourceQualityScorer.isFailedProbeScore(probeScore))return score;
    JSONObject m=qoe.optJSONObject(key);
    if(m==null)m=qoe.optJSONObject(legacyKey);
    if(m!=null&&m.optInt("attempts",0)>=2){
      int attempts=Math.max(1,m.optInt("attempts",0)),ready=m.optInt("ready",0),fail=m.optInt("failures",0),buffers=m.optInt("buffers",0);
      int codecWarnings=m.optInt("audioCodecErrors",0)+m.optInt("audioSinkErrors",0)+m.optInt("videoCodecErrors",0);
      // Use exactly the same attempt-normalized QoE policy as live playback ranking.
      double qoeAdjustment=SourceQoeScoringPolicy.adjustment(
          attempts,ready,m.optInt("firstFrames",0),m.optInt("stablePlays",0),
          fail,buffers,m.optLong("avgReadyMs",0L),m.optLong("avgFirstFrameMs",0L),
          codecWarnings);
      long now=System.currentTimeMillis();
      score+=qoeAdjustment*SourceQoeScoringPolicy.historyWeight(m.optLong("updated",0L),now);
      score+=SourceQoeScoringPolicy.recentSuccessBonus(
          m.optLong("lastFirstFrame",0L),m.optLong("lastStable",0L),now);
    }
    return score;
  }
  // Sniff the response body as well as the URL: some IPTV gateways expose
  // HLS manifests through extensionless or tokenized endpoint URLs.
  private ProbeResult probe(String address,long deadline){
    ProbeResult result=new ProbeResult();
    long started=android.os.SystemClock.elapsedRealtime();
    try{
      if(probeDeadlineReached(deadline))return result;
      SampleResponse initialResponse=fetchSampleResponse(address,32768,deadline);
      byte[] initialBytes=initialResponse==null?null:initialResponse.bytes;
      if(initialBytes==null||initialBytes.length<7)return result;
      String initialText=new String(initialBytes,"UTF-8").replace("\\uFEFF","");
      String lower=address.toLowerCase(Locale.ROOT);
      boolean playlistUrl=lower.contains(".m3u8")||lower.contains("m3u8?");
      boolean hlsPlaylist=HlsVariantPolicy.isPlaylist(initialText);
      if(playlistUrl||hlsPlaylist){
        if(!hlsPlaylist)return result;
        SampleResponse manifestResponse=initialResponse;
        String manifest=initialText;
        String manifestUrl=manifestResponse.finalUrl;
        ArrayList<HlsVariantPolicy.Variant> variants=HlsVariantPolicy.parse(manifest,manifestUrl);
        if(variants.isEmpty())variants.add(new HlsVariantPolicy.Variant(manifestUrl,0L,0,0));
        String mediaUrl=manifestUrl;
        long bestBandwidth=0L;
        int bestWidth=0,bestHeight=0;
        boolean selectedAes128=false;
        HlsMediaPlaylistPolicy.Resources selectedResources=null;
        byte[] selectedAesKey=null;
        ArrayList<String> segments=null;
        byte[] sample=null;
        long segmentStart=0L;
        for(HlsVariantPolicy.Variant candidate:variants){
          if(probeDeadlineReached(deadline))return result;
          String candidateManifestUrl=candidate.url;
          String candidateManifest=manifest;
          if(!candidate.url.equals(manifestUrl)){
            SampleResponse variantResponse=fetchSampleResponse(candidate.url,65536,deadline);
            byte[] variant=variantResponse==null?null:variantResponse.bytes;
            if(variant==null||variant.length==0)continue;
            candidateManifest=new String(variant,"UTF-8").replace("\\uFEFF","");
            if(!HlsVariantPolicy.isPlaylist(candidateManifest))continue;
            candidateManifestUrl=variantResponse.finalUrl;
          }
          HlsMediaPlaylistPolicy.Resources resources=
              HlsMediaPlaylistPolicy.parse(candidateManifest,candidateManifestUrl);
          ArrayList<String> candidateSegments=resources.mediaUrls;
          if(candidateSegments.isEmpty()||resources.unsupportedInitializationByteRange||resources.unsupportedInitializationMap||resources.unsupportedEncryption)continue;
          // Fetch identity AES-128 key only for in-memory preflight; never log or persist key bytes.
          byte[] aesKey=null;
          if(resources.aes128Encrypted){
            aesKey=fetchSample(resources.encryptionKeyUrl,32,deadline);
            if(aesKey==null||aesKey.length!=16)continue;
          }
          // fMP4/CMAF playlists require the EXT-X-MAP initialization resource.
          // AES-128 maps require their explicit IV and must validate after decryption.
          if(resources.initializationUrl!=null){
            SampleResponse initializationResponse=fetchSampleResponse(resources.initializationUrl,32768,deadline);
            byte[] initialization=initializationResponse==null?null:initializationResponse.bytes;
            if(initialization==null)continue;
            if(resources.aes128Encrypted){
              byte[] mapIv=HlsAes128Preflight.parseExplicitIv(resources.encryptionIv);
              if(mapIv==null||!HlsAes128Preflight.validatesInitializationSegment(
                  initialization,aesKey,mapIv,HlsProbeValidationPolicy.isCompleteSample(initializationResponse.truncated)))continue;
            }else if(!MediaPayloadValidator.hasIsoBmffInitializationSegment(initialization))continue;
          }
          String candidateSegmentUrl=candidateSegments.get(0);
          long candidateSegmentStart=android.os.SystemClock.elapsedRealtime();
          SampleResponse candidateSampleResponse=fetchSampleResponse(candidateSegmentUrl,32768,deadline);
          byte[] candidateSample=candidateSampleResponse==null?null:candidateSampleResponse.bytes;
          if(candidateSample==null)continue;
          if(resources.aes128Encrypted){
            byte[] segmentIv=resources.encryptionIv!=null
                ?HlsAes128Preflight.parseExplicitIv(resources.encryptionIv)
                :HlsAes128Preflight.ivForMediaSequence(resources.firstMediaSequenceNumber);
            if(!HlsAes128Preflight.validatesMediaSegment(
                candidateSample,aesKey,segmentIv,HlsProbeValidationPolicy.isCompleteSample(candidateSampleResponse.truncated)))continue;
          }else if(!looksLikeHlsSegment(candidateSample))continue;
          mediaUrl=candidateManifestUrl;
          bestBandwidth=candidate.bandwidth;
          bestWidth=candidate.width;
          bestHeight=candidate.height;
          segments=candidateSegments;
          selectedAes128=resources.aes128Encrypted;
          selectedResources=resources;
          selectedAesKey=aesKey;
          sample=candidateSample;
          segmentStart=candidateSegmentStart;
          break;
        }
        if(segments==null||sample==null)return result;
        long segmentMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-segmentStart);
        boolean secondSegmentOk=true;
        if(segments.size()>1){
          String secondUrl=new URL(new URL(mediaUrl),segments.get(1)).toString();
          SampleResponse secondResponse=fetchSampleResponse(secondUrl,8192,deadline);
          byte[] second=secondResponse==null?null:secondResponse.bytes;
          if(selectedAes128&&second!=null){
            byte[] secondIv=selectedResources.encryptionIv!=null
                ?HlsAes128Preflight.parseExplicitIv(selectedResources.encryptionIv)
                :HlsAes128Preflight.ivForMediaSequence(selectedResources.firstMediaSequenceNumber+1L);
            secondSegmentOk=HlsAes128Preflight.validatesMediaSegment(
                second,selectedAesKey,secondIv,HlsProbeValidationPolicy.isCompleteSample(secondResponse.truncated));
          }else secondSegmentOk=second!=null&&looksLikeHlsSegment(second);
        }
        // A playlist exposing a second segment must prove that it is retrievable
        // and structurally valid too. A valid first segment alone can be a stale
        // edge/cache fragment and must not promote a dead live endpoint.
        if(segments.size()>1&&!HlsProbeValidationPolicy.acceptsSecondSegment(secondSegmentOk))
          return result;
        result.valid=true;result.width=bestWidth;result.height=bestHeight;
        result.bitrate=bestBandwidth;result.latencyMs=Math.max(1L,segmentStart-started);
        int resolution=(bestWidth>=1920||bestHeight>=1080)?900:(bestWidth>=1280||bestHeight>=720)?700:(bestWidth>=854||bestHeight>=480)?500:300;
        int bandwidth=(int)Math.min(500L,bestBandwidth/12000L);
        int speed=(int)Math.min(300L,(sample.length*1000L/segmentMs)/2048L);
        int latencyPenalty=(int)Math.min(250L,result.latencyMs/20L);
        result.score=500+resolution+bandwidth+speed-latencyPenalty+300+(secondSegmentOk?120:(segments.size()>1?-180:0));
        return result;
      }
      result.latencyMs=Math.max(1L,android.os.SystemClock.elapsedRealtime()-started);
      URL url=new URL(address);
      String ext=url.getPath().toLowerCase(Locale.ROOT);
      if(!MediaPayloadValidator.looksLikeMediaPayload(initialBytes,ext))return result;
      result.valid=true;
      result.score=350+(int)Math.min(300L,(initialBytes.length*1000L/result.latencyMs)/2048L);
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
    // True only when the bounded read observed at least one byte beyond its cap.
    // Reaching the cap alone does not prove that the HTTP resource ended there.
    final boolean truncated;
    SampleResponse(byte[] data,String url,boolean wasTruncated){bytes=data;finalUrl=url;truncated=wasTruncated;}
  }
  private boolean probeDeadlineReached(long deadline){
    return android.os.SystemClock.elapsedRealtime()>=deadline;
  }
  private byte[] fetchSample(String address,int maxBytes,long deadline)throws Exception{
    SampleResponse response=fetchSampleResponse(address,maxBytes,deadline);
    return response==null?null:response.bytes;
  }
  private SampleResponse fetchSampleResponse(String address,int maxBytes,long deadline)throws Exception{
    HttpURLConnection c=null;
    try{
      c=(HttpURLConnection)new URL(address).openConnection();
      // URLConnection options must be configured before the first operation that connects it.
      // Reserve half the remaining budget for connection setup and the rest for response reads.
      long now=android.os.SystemClock.elapsedRealtime();
      long remaining=deadline-now;
      if(remaining<2L)return null;
      int connectTimeout=(int)Math.min(2500L,Math.max(1L,remaining/2L));
      int readTimeout=SourceProbeTimePolicy.boundedTimeoutMillis(
          deadline,now+connectTimeout,3000);
      if(readTimeout<=0)return null;
      c.setConnectTimeout(connectTimeout);
      c.setReadTimeout(readTimeout);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Safari/537.36 MyTV/1.24");
      // Read one sentinel byte beyond the retained sample to distinguish an
      // exact-length resource from a longer segment truncated at the sample cap.
      c.setRequestProperty("Range","bytes=0-"+maxBytes);
      int code=c.getResponseCode();if(code<200||code>=400)return null;
      boolean rangeIncomplete=code==HttpURLConnection.HTTP_PARTIAL
          &&HlsProbeValidationPolicy.isIncompleteContentRange(c.getHeaderField("Content-Range"),maxBytes);
      String type=c.getContentType();
      if(type!=null&&type.toLowerCase(Locale.ROOT).contains("text/html"))return null;
      String finalUrl=c.getURL().toString();
      try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[4096];int n,total=0;
        while(total<=maxBytes){
          if(probeDeadlineReached(deadline))return null;
          n=in.read(buffer);
          if(n==-1)break;
          int take=Math.min(n,maxBytes+1-total);out.write(buffer,0,take);total+=take;
          if(total>maxBytes)break;
        }
        if(probeDeadlineReached(deadline))return null;
        byte[] received=out.toByteArray();
        boolean truncated=rangeIncomplete
            ||HlsProbeValidationPolicy.isTruncatedSample(received.length,maxBytes);
        byte[] bounded=truncated?Arrays.copyOf(received,maxBytes):received;
        return new SampleResponse(bounded,finalUrl,truncated);
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
  private String identity(String name,String group,String url,String tvgId,String tvgName){
    String broadcaster=ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
    String v=broadcaster.isEmpty()?canonicalName(name):broadcaster;
    String base=v.toLowerCase(Locale.ROOT)+"|"+(group==null?"":group.trim().toLowerCase(Locale.ROOT));
    String fallback=base;
    try{
      java.net.URI u=java.net.URI.create(url==null?"":url);
      String host=u.getHost();
      if(host!=null&&!host.isEmpty())fallback=base+"|host:"+host.toLowerCase(Locale.ROOT);
    }catch(Exception ignored){fallback=base+"|url:"+(url==null?"":url.trim());}
    return ChannelIdentityPolicy.identity(v,tvgId,fallback);
  }
  private String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
  private String roomString(String key,String fallback){
    return com.wodedianshi.tv.db.AppStateStore.get(getApplicationContext(),key,fallback);
  }
  private int roomInt(String key,int fallback){
    try{return Integer.parseInt(roomString(key,Integer.toString(fallback)));}
    catch(Exception ignored){return fallback;}
  }
  private boolean roomBoolean(String key,boolean fallback){
    String value=roomString(key,Boolean.toString(fallback));
    return Boolean.parseBoolean(value);
  }
  private void saveStatus(SharedPreferences p,String result,int added){
    com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_update_time",now());
    com.wodedianshi.tv.db.AppStateStore.put(getApplicationContext(),"source_update_result",result);
  }
}
