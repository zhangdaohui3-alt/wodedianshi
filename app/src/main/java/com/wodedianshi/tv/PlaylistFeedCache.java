package com.wodedianshi.tv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * Small, bounded disk cache for remote M3U feeds.
 *
 * A feed page is resumed by parsing the cached snapshot rather than downloading
 * the same playlist prefix on every WorkManager continuation. Only a completely
 * downloaded and validated playlist replaces the previous cache file.
 */
public final class PlaylistFeedCache {
    // Keep a stable feed snapshot long enough for multi-run full-catalogue pagination.
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_BYTES = 96L * 1024L * 1024L;
    private static final long MAX_CACHE_BYTES = 256L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final long DOWNLOAD_BUDGET_MS = 60_000L;

    private PlaylistFeedCache() { }

    /**
     * Keep routine refreshes and the full-rebuild snapshot in separate cache slots.
     * This prevents a routine update from replacing a file under an active rebuild cursor.
     */
    public static File obtain(Context context, String feedUrl, boolean initialDiscovery,
                              boolean preserveExistingSnapshot) throws IOException {
        return obtain(context, feedUrl, initialDiscovery, preserveExistingSnapshot, () -> false);
    }

    public static File obtain(Context context, String feedUrl, boolean initialDiscovery,
                              boolean preserveExistingSnapshot,
                              java.util.function.BooleanSupplier paused) throws IOException {
        return obtainBounded(context,feedUrl,initialDiscovery,preserveExistingSnapshot,paused,false);
    }

    /** Explicit refresh bypasses the age shortcut at a new directory, but
     * retains conditional HTTP validators and the old body on download failure. */
    static File obtainManual(Context context,String feedUrl,boolean revalidate,
            java.util.function.BooleanSupplier paused) throws IOException {
        return obtainBounded(context,feedUrl,false,!revalidate,paused,false,revalidate);
    }

    /** Reuse a successful manual acquisition only while the body identity matches. */
    static File findPreparedManual(Context context,String feedUrl,String preparedVersion) throws IOException {
        if(preparedVersion==null||preparedVersion.isEmpty())return null;
        File directory=new File(context.getFilesDir(),"playlist-feed-cache");
        File cached=new File(directory,sha256("routine|"+feedUrl)+".m3u");
        Properties metadata=readProperties(new File(cached.getPath()+".properties"));
        if(!matchesBody(metadata,cached))return null;
        return DiscoveryBatchPolicy.isPreparedInitialFeed(preparedVersion,contentVersion(cached))?cached:null;
    }

    public static File obtainSmallInitialDuringViewing(Context context,String feedUrl,
            boolean preserveExistingSnapshot,java.util.function.BooleanSupplier paused) throws IOException {
        return obtainBounded(context,feedUrl,true,preserveExistingSnapshot,paused,true);
    }

    /**
     * One bounded HTTP range per stable-viewing slice. A null result means a
     * durable partial download, never a usable directory. Strong ETag and
     * matching total/offset are mandatory for combining separate responses.
     */
    public static File obtainInitialViewingChunk(Context context,String feedUrl,
            boolean preserveExistingSnapshot,java.util.function.BooleanSupplier paused) throws IOException {
        return obtainInitialRange(context,feedUrl,preserveExistingSnapshot,paused,true);
    }

    /** Resume an existing range checkpoint at idle speed, otherwise use the
     * normal full download. Null means a committed, incomplete body. */
    public static File obtainInitialDuringIdle(Context context,String feedUrl,
            boolean preserveExistingSnapshot,java.util.function.BooleanSupplier paused) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        File directory=new File(context.getFilesDir(),"playlist-feed-cache");
        String key=sha256("initial|"+feedUrl);
        File cached=new File(directory,key+".m3u");
        File partial=new File(directory,key+".range-part");
        File checkpoint=new File(directory,key+".range-state");
        if(!cached.isFile()&&partial.isFile()&&checkpoint.isFile()){
            try{
                return obtainInitialRange(context,feedUrl,preserveExistingSnapshot,paused,false);
            }catch(SourceProbeTimePolicy.PausedException deferred){throw deferred;}
            catch(IOException failure){
                SourceProbeTimePolicy.checkPaused(paused);
                // A temporary transport failure is not evidence that Range is
                // unsupported. Keep the durable prefix and let normal feed
                // rotation retry it instead of immediately downloading twice.
                Properties retained=readProperties(checkpoint);
                boolean checkpointRetained=partial.isFile()&&SourceProbeTimePolicy.validDirectoryCheckpoint(
                    parseLong(retained.getProperty("length"),0L),
                    parseLong(retained.getProperty("total"),-1L),partial.length(),
                    retained.getProperty("etag",""),retained.getProperty("location",""));
                if(SourceProbeTimePolicy.deferRangeFallback(failure,checkpointRetained))throw failure;
                // Other failures retain the existing full-download fallback,
                // including servers that no longer honor Range.
            }
        }
        File result=obtain(context,feedUrl,true,preserveExistingSnapshot,paused);
        if(result!=null){checkpoint.delete();partial.delete();}
        return result;
    }

    private static File obtainInitialRange(Context context,String feedUrl,
            boolean preserveExistingSnapshot,java.util.function.BooleanSupplier paused,
            boolean lowRate) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        final long chunkLimit=lowRate?SourceProbeTimePolicy.LIVE_DIRECTORY_MAX_BYTES:2L*1024L*1024L;
        File directory=new File(context.getFilesDir(),"playlist-feed-cache");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cache directory unavailable");
        String key=sha256("initial|"+feedUrl);
        File cached=new File(directory,key+".m3u");
        if(cached.isFile()){
            if(lowRate&&!SourceProbeTimePolicy.fitsLiveFullDirectory(cached.length()))
                throw new IOException("Directory exceeds stable-viewing size limit");
            return obtainBounded(context,feedUrl,true,preserveExistingSnapshot,paused,lowRate);
        }
        File partial=new File(directory,key+".range-part");
        File checkpoint=new File(directory,key+".range-state");
        File checkpointTemp=new File(directory,key+".range-state.tmp");
        // Bound partial-file storage globally. Eviction never advances a feed cursor.
        File[] parts=directory.listFiles((dir,name)->name.endsWith(".range-part"));
        if(parts!=null){
            java.util.Arrays.sort(parts,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            int retained=0;
            for(File other:parts){
                if(other.equals(partial))continue;
                if(retained++>=2){
                    String prefix=other.getName().substring(0,other.getName().length()-".range-part".length());
                    if(other.delete())new File(directory,prefix+".range-state").delete();
                }
            }
        }
        Properties previous=readProperties(checkpoint);
        String oldTag=previous.getProperty("etag","");
        String oldLocation=previous.getProperty("location","");
        long offset=parseLong(previous.getProperty("length"),0L);
        long total=parseLong(previous.getProperty("total"),-1L);
        if(!partial.isFile()||!SourceProbeTimePolicy.validDirectoryCheckpoint(
                offset,total,partial.length(),oldTag,oldLocation)){
            clearRangeCheckpoint(checkpoint,partial);offset=0L;total=-1L;oldTag="";oldLocation="";
        }else if(partial.length()!=offset){
            try(java.io.RandomAccessFile out=new java.io.RandomAccessFile(partial,"rw")){out.setLength(offset);}
        }
        HttpURLConnection connection=null;
        long deadline=android.os.SystemClock.elapsedRealtime()+(lowRate?SourceProbeTimePolicy.LIVE_DIRECTORY_BUDGET_MS:DOWNLOAD_BUDGET_MS);
        try{
            if(offset>0L&&offset==total){
                // The bytes and strong validator are complete. Digest in a
                // separate bounded slice, even during confirmed stable playback.
                return publishRangeBody(directory,cached,partial,checkpoint,oldTag,paused,deadline);
            }
            connection=(HttpURLConnection)new URL(feedUrl).openConnection();
            connection.setConnectTimeout(lowRate?2000:CONNECT_TIMEOUT_MS);connection.setReadTimeout(lowRate?2000:READ_TIMEOUT_MS);
            connection.setUseCaches(false);connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent","MyTV/1.19 Android TV");
            connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("Range","bytes="+offset+"-"+(offset+chunkLimit-1L));
            if(offset>0L)connection.setRequestProperty("If-Range",oldTag);
            int status=connection.getResponseCode();
            checkDownloadBudget(paused,deadline);
            String encoding=connection.getHeaderField("Content-Encoding");
            if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("Encoded range response");
            String tag=connection.getHeaderField("ETag");
            String location=sha256(connection.getURL().toExternalForm());
            long expected;
            long transferLimit=chunkLimit;
            if(status==206){
                long[] range=SourceProbeTimePolicy.directoryRange(connection.getHeaderField("Content-Range"),offset,total,chunkLimit);
                if(range==null||!SourceProbeTimePolicy.strongDirectoryValidator(tag)
                        ||(offset>0L&&(!oldTag.equals(tag)||!oldLocation.equals(location)))){
                    clearRangeCheckpoint(checkpoint,partial);
                    // A server may support Range without a strong validator.
                    // Never concatenate such responses; use one bounded full body.
                    if(lowRate){
                        connection.disconnect();connection=null;
                        return obtainBounded(context,feedUrl,true,preserveExistingSnapshot,paused,true);
                    }
                    throw new IOException("Directory range identity changed or unsupported");
                }
                expected=range[0];total=range[1];
            }else if(status==200){
                // Range ignored or representation changed. Never append a 200.
                clearRangeCheckpoint(checkpoint,partial);offset=0L;
                expected=connection.getContentLength();total=expected;
                if(lowRate){
                    transferLimit=SourceProbeTimePolicy.LIVE_RESUMABLE_MAX_BYTES;
                    deadline=android.os.SystemClock.elapsedRealtime()+SourceProbeTimePolicy.LIVE_FULL_DIRECTORY_BUDGET_MS;
                }
                if(expected>transferLimit)
                    throw new IOException("Directory exceeds bounded full transfer size");
            }else{
                if(status==416){clearRangeCheckpoint(checkpoint,partial);}
                throw new IOException("Directory range HTTP "+status);
            }
            long copied=0L;
            long started=android.os.SystemClock.elapsedRealtime();
            try(InputStream input=connection.getInputStream();
                FileOutputStream out=new FileOutputStream(partial,offset>0L)){
                byte[] buffer=new byte[lowRate?4096:32768];
                while(true){
                    checkDownloadBudget(paused,deadline);
                    int n=input.read(buffer);
                    checkDownloadBudget(paused,deadline);
                    if(n<0)break;
                    copied+=n;
                    if(copied>transferLimit||(expected>=0L&&copied>expected))
                        throw new IOException("Directory response exceeded range budget");
                    out.write(buffer,0,n);
                    long delay=lowRate?SourceProbeTimePolicy.liveFullDirectoryPacingMillis(copied,android.os.SystemClock.elapsedRealtime()-started):0L;
                    while(delay>0L){
                        checkDownloadBudget(paused,deadline);
                        long step=Math.min(50L,delay);android.os.SystemClock.sleep(step);delay-=step;
                    }
                }
                if(copied<=0L||(expected>=0L&&copied!=expected))throw new IOException("Incomplete directory range");
                out.getFD().sync();
            }
            checkDownloadBudget(paused,deadline);
            long length=offset+copied;
            if(status==206){
                Properties next=new Properties();
                next.setProperty("etag",tag);next.setProperty("location",location);
                next.setProperty("length",Long.toString(length));next.setProperty("total",Long.toString(total));
                writePropertiesAtomically(checkpoint,checkpointTemp,next);
                if(length<total)return null;
                // Preserve the final bytes before any digest/index work.
                // Give a large completed range its own fresh validation slice.
                if(!SourceProbeTimePolicy.fitsLiveDirectory(length))return null;
            }
            return publishRangeBody(directory,cached,partial,checkpoint,tag,paused,deadline);
        }finally{
            if(connection!=null)connection.disconnect();
            checkpointTemp.delete();
            // Uncommitted tail is truncated on next attempt, never trusted.
            if(!checkpoint.isFile())partial.delete();
        }
    }

    private static File publishRangeBody(File directory,File cached,File partial,File checkpoint,
            String tag,java.util.function.BooleanSupplier paused,long deadline) throws IOException {
        checkDownloadBudget(paused,deadline);
        // No partial body is ever parsed or published.
        if(!isPlaylistFile(partial)){
            clearRangeCheckpoint(checkpoint,partial);
            throw new IOException("Downloaded resource is not M3U");
        }
        String digest=contentDigest(partial,paused);
        checkDownloadBudget(paused,deadline);
        invalidateIndex(cached);
        File metadata=new File(cached.getPath()+".properties");
        if(metadata.exists()&&!metadata.delete())throw new IOException("Cannot invalidate old metadata");
        atomicReplace(partial,cached);
        Properties fresh=new Properties();
        describeBody(fresh,cached,digest);
        fresh.setProperty("cachedAt",Long.toString(System.currentTimeMillis()));
        if(tag!=null)fresh.setProperty("etag",tag);
        writePropertiesAtomically(metadata,new File(metadata.getPath()+".tmp"),fresh);
        checkpoint.delete();
        pruneOldEntries(context,directory,cached,true);
        return cached;
    }

    private static void clearRangeCheckpoint(File checkpoint,File partial) throws IOException {
        // Remove the old authority before changing bytes. If removal fails,
        // do not overwrite the body under a still-valid old checkpoint.
        if(checkpoint.exists()&&!checkpoint.delete())throw new IOException("Cannot invalidate range checkpoint");
        if(partial.exists()&&!partial.delete())throw new IOException("Cannot remove old range body");
    }

    private static File obtainBounded(Context context,String feedUrl,boolean initialDiscovery,
            boolean preserveExistingSnapshot,java.util.function.BooleanSupplier paused,
            boolean lowRate) throws IOException {
        return obtainBounded(context,feedUrl,initialDiscovery,preserveExistingSnapshot,paused,lowRate,false);
    }

    private static File obtainBounded(Context context,String feedUrl,boolean initialDiscovery,
            boolean preserveExistingSnapshot,java.util.function.BooleanSupplier paused,
            boolean lowRate,boolean forceRevalidate) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        long maxBytes=lowRate?SourceProbeTimePolicy.LIVE_RESUMABLE_MAX_BYTES:MAX_BYTES;
        if (context == null || feedUrl == null || feedUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("context/feedUrl is empty");
        }
        File directory = new File(context.getFilesDir(), "playlist-feed-cache");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create playlist cache directory");
        }
        String key = sha256((initialDiscovery ? "initial|" : "routine|") + feedUrl);
        File cached = new File(directory, key + ".m3u");
        File metadata = new File(directory, key + ".m3u.properties");
        Properties old = readProperties(metadata);
        if(lowRate&&cached.isFile()&&!SourceProbeTimePolicy.fitsLiveFullDirectory(cached.length()))
            throw new IOException("Directory exceeds stable-viewing size limit");
        boolean hasValidCache = cached.isFile() && cached.length() > 0L
                && isPlaylistFile(cached);
        // Do not trust validators or index files after a body replacement crash,
        // eviction, or migration from an older cache format.
        if (hasValidCache && !matchesBody(old, cached)) {
            invalidateIndex(cached);
            old = new Properties();
            describeBody(old, cached, contentDigest(cached, paused));
            writePropertiesAtomically(metadata, new File(metadata.getPath()+".tmp"), old);
        } else if (!hasValidCache) {
            old = new Properties();
        }
        long cachedAt = parseLong(old.getProperty("cachedAt"), 0L);

        if (hasValidCache && !forceRevalidate && (preserveExistingSnapshot
                || (System.currentTimeMillis() - cachedAt >= 0L
                && System.currentTimeMillis() - cachedAt < MAX_AGE_MS))) {
            metadata.setLastModified(System.currentTimeMillis());
            pruneOldEntries(context,directory,cached,initialDiscovery);
            return cached;
        }

        HttpURLConnection connection = null;
        File temporary = new File(directory, key + ".m3u.tmp");
        File metadataTemporary = new File(directory, key + ".m3u.properties.tmp");
        final long deadline = android.os.SystemClock.elapsedRealtime() + (lowRate?SourceProbeTimePolicy.LIVE_FULL_DIRECTORY_BUDGET_MS:DOWNLOAD_BUDGET_MS);
        try {
            SourceProbeTimePolicy.checkPaused(paused);
            connection = (HttpURLConnection) new URL(feedUrl).openConnection();
            connection.setConnectTimeout(lowRate?2000:CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(lowRate?2000:READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "MyTV/1.19 Android TV");
            connection.setRequestProperty("Accept", "*/*");
            if(forceRevalidate)connection.setRequestProperty("Cache-Control","no-cache");
            String etag = old.getProperty("etag", "");
            String modified = old.getProperty("lastModified", "");
            // A validator is useful only when its matching cached body still exists.
            // Android may evict cache files independently of the metadata sidecar.
            if (hasValidCache && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            if (hasValidCache && !modified.isEmpty()) {
                connection.setRequestProperty("If-Modified-Since", modified);
            }

            int status = connection.getResponseCode();
            checkDownloadBudget(paused, deadline);
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && hasValidCache) {
                old.setProperty("cachedAt", Long.toString(System.currentTimeMillis()));
                writePropertiesAtomically(metadata, metadataTemporary, old);
                metadata.setLastModified(System.currentTimeMillis());
                pruneOldEntries(context,directory,cached,initialDiscovery);
                return cached;
            }
            if (!SourceProbeTimePolicy.fullDirectoryResponse(status,connection.getHeaderField("Content-Range"))) {
                throw new IOException("Playlist HTTP " + status + " is not a complete response");
            }

            long declaredLength = connection.getContentLength();
            String responseEncoding=connection.getHeaderField("Content-Encoding");
            if (declaredLength > maxBytes) {
                throw new IOException("Playlist exceeds cache size limit");
            }
            long copied = 0L;
            MessageDigest digest = newDigest();
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(temporary);
                 OutputStream output = new BufferedOutputStream(fileOutput)) {
                byte[] buffer = new byte[lowRate?4096:32 * 1024];
                final long readStarted=android.os.SystemClock.elapsedRealtime();
                int count;
                while (true) {
                    checkDownloadBudget(paused, deadline);
                    count = input.read(buffer);
                    checkDownloadBudget(paused, deadline);
                    if (count == -1) break;
                    copied += count;
                    if (copied > maxBytes) {
                        throw new IOException("Playlist exceeds cache size limit");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    if(lowRate){
                        long delay=SourceProbeTimePolicy.liveFullDirectoryPacingMillis(
                                copied,android.os.SystemClock.elapsedRealtime()-readStarted);
                        while(delay>0L){
                            checkDownloadBudget(paused,deadline);
                            long step=Math.min(50L,delay);
                            android.os.SystemClock.sleep(step);
                            delay-=step;
                        }
                    }
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            if (!SourceProbeTimePolicy.completeFullDirectoryBody(copied,declaredLength,responseEncoding)) {
                throw new IOException("Playlist body length mismatch");
            }
            if (!isPlaylistFile(temporary)) {
                throw new IOException("Downloaded resource is not a valid M3U playlist");
            }

            // The index is tied to the exact cached body. Remove it before replacing
            // that body so a later page can never seek into records from an older feed.
            checkDownloadBudget(paused, deadline);
            invalidateIndex(cached);
            // Remove the old body's metadata BEFORE the atomic replacement.
            // A crash leaves either the old or new body, with no stale validator.
            // The next obtain can reconstruct its content identity safely.
            if (metadata.exists() && !metadata.delete()) {
                throw new IOException("Cannot invalidate old playlist metadata");
            }
            atomicReplace(temporary, cached);

            Properties fresh = new Properties();
            describeBody(fresh, cached, hex(digest.digest()));
            fresh.setProperty("cachedAt", Long.toString(System.currentTimeMillis()));
            String responseEtag = connection.getHeaderField("ETag");
            String responseModified = connection.getHeaderField("Last-Modified");
            if (responseEtag != null) fresh.setProperty("etag", responseEtag);
            if (responseModified != null) fresh.setProperty("lastModified", responseModified);
            writePropertiesAtomically(metadata, metadataTemporary, fresh);
            pruneOldEntries(context,directory,cached,initialDiscovery);
            return cached;
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary.exists()) temporary.delete();
            if (metadataTemporary.exists()) metadataTemporary.delete();
        }
    }

    /** Stable viewing may read an already indexed, matching snapshot only.
     * No network, pruning, hash rebuild or index construction on this path. */
    public static File findIndexedInitial(Context context,String feedUrl) throws IOException {
        File cached=initialBody(context,feedUrl);
        return matchesBody(readProperties(new File(cached.getPath()+".properties")),cached)
                &&PlaylistFeedIndex.indexedEntryCount(cached)>=0?cached:null;
    }

    private static File initialBody(Context context,String feedUrl)throws IOException {
        return new File(new File(context.getFilesDir(),"playlist-feed-cache"),
                sha256("initial|"+feedUrl)+".m3u");
    }

    /** A committed acquisition identifies the full body independently of its
     * derived index. Repair a missing index from that exact body at idle speed;
     * do not revalidate a previously acquired, still matching body over HTTP. */
    static File findAcquiredInitialBody(Context context,String feedUrl,String acquiredVersion)throws IOException {
        return matchingAcquiredBody(initialBody(context,feedUrl),acquiredVersion);
    }

    static File matchingAcquiredBody(File cached,String acquiredVersion)throws IOException {
        if(cached==null||acquiredVersion==null||acquiredVersion.isEmpty())return null;
        Properties metadata=readProperties(new File(cached.getPath()+".properties"));
        if(!matchesBody(metadata,cached))return null;
        String version=cached.getName()+":"+metadata.getProperty("bodySha256");
        return DiscoveryBatchPolicy.isPreparedInitialFeed(acquiredVersion,version)?cached:null;
    }

    /** Idle initial discovery has consumed a validated indexed page. Refresh only
     * metadata access time, not the body timestamp or cachedAt revalidation age.
     * Otherwise an actively scanned directory may be evicted as an old entry.
     * Best effort: a timestamp failure must not invalidate a successful page. */
    static void noteIndexedInitialAccess(File playlist,long now) {
        if(playlist==null||now<=0L)return;
        File metadata=new File(playlist.getPath()+".properties");
        if(matchesBody(readProperties(metadata),playlist)&&now>metadata.lastModified())
            metadata.setLastModified(now);
    }

    /** Read-only lookup for an explicit single-channel search. Prefer the
     * newest validated body across the two existing cache slots; never
     * download, prune, build indexes or change a full-build checkpoint here. */
    static File findCachedForSingleChannel(Context context,String feedUrl)throws IOException {
        File directory=new File(context.getFilesDir(),"playlist-feed-cache");
        File newest=null;
        for(String slot:new String[]{"initial|","routine|"}){
            File candidate=new File(directory,sha256(slot+feedUrl)+".m3u");
            Properties metadata=readProperties(new File(candidate.getPath()+".properties"));
            if(matchesBody(metadata,candidate)
                    &&(newest==null||candidate.lastModified()>newest.lastModified()))
                newest=candidate;
        }
        return newest;
    }

    private static void checkDownloadBudget(java.util.function.BooleanSupplier paused,
                                            long deadline) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        if (android.os.SystemClock.elapsedRealtime() >= deadline)
            throw new IOException("Playlist download budget exceeded");
    }

    private static void invalidateIndex(File playlist) throws IOException {
        File records = new File(playlist.getAbsolutePath() + ".index-data");
        File offsets = new File(playlist.getAbsolutePath() + ".index-offsets");
        if (records.exists() && !records.delete()) {
            throw new IOException("Cannot invalidate stale playlist record index");
        }
        if (offsets.exists() && !offsets.delete()) {
            throw new IOException("Cannot invalidate stale playlist offset index");
        }
    }

    /** Return currently usable unfinished initial indexes only when their
     * disk footprint leaves insufficient headroom for one maximum body.
     * This is admission guidance, not a persistent inventory or validity
     * certificate. Completed directories do not consume this reserve. */
    static boolean[] initialCachePressure(Context context,String[] feeds,boolean[] completed,
            String[] acquiredVersions)throws IOException {
        if(feeds==null||completed==null||acquiredVersions==null
                ||feeds.length!=completed.length||feeds.length!=acquiredVersions.length)return null;
        File[] bodies=new File[feeds.length];
        long bytes=0L;
        for(int i=0;i<feeds.length;i++){
            if(completed[i])continue;
            File body=initialBody(context,feeds[i]);
            if(body.isFile()){
                bodies[i]=body;
                bytes+=cacheEntrySize(body);
            }
        }
        if(!initialCacheNeedsDrain(bytes))return null;
        boolean[] available=new boolean[feeds.length];
        for(int i=0;i<bodies.length;i++){
            File body=bodies[i];
            available[i]=hasAcquiredInitialIndex(body,acquiredVersions[i]);
        }
        return available;
    }

    static boolean hasAcquiredInitialIndex(File body,String acquiredVersion)throws IOException {
        return matchingAcquiredBody(body,acquiredVersion)!=null
                &&PlaylistFeedIndex.indexedEntryCount(body)>=0;
    }

    static boolean initialCacheNeedsDrain(long unfinishedBytes){
        return unfinishedBytes>=MAX_CACHE_BYTES-MAX_BYTES;
    }

    /** Index construction adds bytes after download pruning. Recheck the same
     * bounded cache after a successful initial index, keeping its current body. */
    static void trimPreparedInitial(Context context,File playlist) throws IOException {
        if(playlist!=null)pruneOldEntries(context,playlist.getParentFile(),playlist,true);
    }

    private static void pruneOldEntries(Context context,File directory,File keep,boolean initial) {
        pruneWithPreference(directory,keep,MAX_CACHE_BYTES,()->{
            if(!initial)return java.util.Collections.emptySet();
            String[] feeds=SourceDiscoveryFeedCatalog.feeds();
            java.util.ArrayList<String> keys=new java.util.ArrayList<>(feeds.length);
            for(int i=0;i<feeds.length;i++)keys.add(SourceUpdateWorker.initialFeedDoneKey(i));
            java.util.Set<String> completed=new java.util.HashSet<>();
            try {
                for(com.wodedianshi.tv.db.AppStateEntity state:
                    com.wodedianshi.tv.db.RoomDatabaseProvider.get(context).appStateDao().getValues(keys))
                    if(Boolean.parseBoolean(state.stateValue))completed.add(state.key);
            } catch(RuntimeException unavailable) {
                // Missing completion evidence must not weaken candidate retention.
                completed.clear();
            }
            return unfinishedInitialCacheNames(feeds,completed);
        });
    }

    static java.util.Set<String> unfinishedInitialCacheNames(String[] feeds,java.util.Set<String> completed){
        java.util.Set<String> preferred=new java.util.HashSet<>();
        for(int i=0;i<feeds.length;i++)
            if(!completed.contains(SourceUpdateWorker.initialFeedDoneKey(i)))
                preferred.add(sha256("initial|"+feeds[i])+".m3u");
        return preferred;
    }

    /** Keep unfinished initial directories ahead of disposable cache entries.
     * Read completion state only if eviction is actually necessary. */
    static void pruneOldEntries(File directory,File keep,java.util.Set<String> preferred,long budget) {
        pruneWithPreference(directory,keep,budget,()->preferred);
    }

    private static void pruneWithPreference(File directory,File keep,long budget,
            java.util.function.Supplier<java.util.Set<String>> preference) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".m3u"));
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File file : files) total += cacheEntrySize(file);
        if (total <= budget) return;
        java.util.Set<String> preferred=preference.get();
        java.util.Arrays.sort(files, (left, right) -> {
            int preference=Boolean.compare(preferred.contains(left.getName()),preferred.contains(right.getName()));
            return preference!=0?preference:Long.compare(lastAccess(left),lastAccess(right));
        });
        for (File file : files) {
            if (total <= budget) break;
            if (file.equals(keep)) continue;
            long size = cacheEntrySize(file);
            if (!file.delete()) continue;
            deleteSidecar(file, ".index-data");
            deleteSidecar(file, ".index-offsets");
            deleteSidecar(file, ".index-data.tmp");
            deleteSidecar(file, ".index-offsets.tmp");
            deleteSidecar(file, ".properties");
            deleteSidecar(file, ".properties.tmp");
            total = Math.max(0L, total - size);
        }
    }

    private static long cacheEntrySize(File playlist) {
        long total = Math.max(0L, playlist.length());
        total += sidecarLength(playlist, ".index-data");
        total += sidecarLength(playlist, ".index-offsets");
        total += sidecarLength(playlist, ".properties");
        total += sidecarLength(playlist, ".index-data.tmp");
        total += sidecarLength(playlist, ".index-offsets.tmp");
        total += sidecarLength(playlist, ".properties.tmp");
        return total;
    }

    private static long sidecarLength(File playlist, String suffix) {
        File sidecar = new File(playlist.getAbsolutePath() + suffix);
        return sidecar.isFile() ? Math.max(0L, sidecar.length()) : 0L;
    }

    private static void deleteSidecar(File playlist, String suffix) {
        File sidecar = new File(playlist.getAbsolutePath() + suffix);
        if (sidecar.exists()) sidecar.delete();
    }

    private static boolean isPlaylistFile(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            return first != null && first.replace("\uFEFF", "").trim().startsWith("#EXTM3U");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Properties readProperties(File file) {
        Properties properties = new Properties();
        if (!file.isFile()) return properties;
        try (InputStream input = new FileInputStream(file)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException ignored) {
            // Never trust even the successfully parsed prefix of corrupt metadata.
            properties.clear();
        }
        return properties;
    }

    private static void writePropertiesAtomically(File target, File temporary,
                                                   Properties properties) throws IOException {
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, "My TV playlist cache metadata");
            output.flush();
            output.getFD().sync();
        }
        atomicReplace(temporary, target);
    }

    /** Version identifies the exact cached feed body and its URL-derived slot. */
    public static String contentVersion(File playlist) throws IOException {
        Properties properties = readProperties(new File(playlist.getPath()+".properties"));
        if (!matchesBody(properties, playlist))
            throw new IOException("Playlist content identity is unavailable");
        return playlist.getName()+":"+properties.getProperty("bodySha256");
    }

    private static boolean matchesBody(Properties p, File body) {
        String digest=p.getProperty("bodySha256","");
        return body.isFile() && digest.matches("[0-9a-f]{64}")
            && parseLong(p.getProperty("bodyLength"),-1L)==body.length()
            && parseLong(p.getProperty("bodyModified"),-1L)==body.lastModified();
    }

    private static void describeBody(Properties p, File body, String digest) {
        p.setProperty("bodySha256",digest);
        p.setProperty("bodyLength",Long.toString(body.length()));
        p.setProperty("bodyModified",Long.toString(body.lastModified()));
    }

    private static long lastAccess(File body) {
        File metadata=new File(body.getPath()+".properties");
        return Math.max(body.lastModified(),metadata.lastModified());
    }

    private static String contentDigest(File body, java.util.function.BooleanSupplier paused)
            throws IOException {
        MessageDigest digest=newDigest();
        try (InputStream input=new BufferedInputStream(new FileInputStream(body))) {
            byte[] buffer=new byte[32*1024];
            while(true) {
                SourceProbeTimePolicy.checkPaused(paused);
                int n=input.read(buffer);
                if(n<0)break;
                digest.update(buffer,0,n);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest newDigest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(java.security.NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable",failure);
        }
    }

    private static String hex(byte[] digest) {
        char[] alphabet="0123456789abcdef".toCharArray();
        char[] result=new char[digest.length*2];
        for(int i=0;i<digest.length;i++) {
            int value=digest[i]&255;
            result[i*2]=alphabet[value>>>4];
            result[i*2+1]=alphabet[value&15];
        }
        return new String(result);
    }

    private static void atomicReplace(File temporary,File target) throws IOException {
        // Both paths are in the same private directory; POSIX rename replaces
        // the destination atomically, without an unlink-first failure window.
        try { android.system.Os.rename(temporary.getAbsolutePath(),target.getAbsolutePath()); }
        catch(android.system.ErrnoException failure) {
            throw new IOException("Cannot atomically replace playlist cache file",failure);
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sha256(String value) throws IOException {
        return hex(newDigest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
