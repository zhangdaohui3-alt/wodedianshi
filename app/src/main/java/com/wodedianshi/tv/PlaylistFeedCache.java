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
        SourceProbeTimePolicy.checkPaused(paused);
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

        if (hasValidCache && (preserveExistingSnapshot
                || (System.currentTimeMillis() - cachedAt >= 0L
                && System.currentTimeMillis() - cachedAt < MAX_AGE_MS))) {
            metadata.setLastModified(System.currentTimeMillis());
            pruneOldEntries(directory, cached);
            return cached;
        }

        HttpURLConnection connection = null;
        File temporary = new File(directory, key + ".m3u.tmp");
        File metadataTemporary = new File(directory, key + ".m3u.properties.tmp");
        final long deadline = android.os.SystemClock.elapsedRealtime() + DOWNLOAD_BUDGET_MS;
        try {
            SourceProbeTimePolicy.checkPaused(paused);
            connection = (HttpURLConnection) new URL(feedUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "MyTV/1.19 Android TV");
            connection.setRequestProperty("Accept", "*/*");
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
                pruneOldEntries(directory, cached);
                return cached;
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Playlist HTTP " + status);
            }

            long declaredLength = connection.getContentLength();
            if (declaredLength > MAX_BYTES) {
                throw new IOException("Playlist exceeds cache size limit");
            }
            long copied = 0L;
            MessageDigest digest = newDigest();
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(temporary);
                 OutputStream output = new BufferedOutputStream(fileOutput)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while (true) {
                    checkDownloadBudget(paused, deadline);
                    count = input.read(buffer);
                    checkDownloadBudget(paused, deadline);
                    if (count == -1) break;
                    copied += count;
                    if (copied > MAX_BYTES) {
                        throw new IOException("Playlist exceeds cache size limit");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            if (copied == 0L || !isPlaylistFile(temporary)) {
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
            pruneOldEntries(directory, cached);
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
        File directory=new File(context.getFilesDir(),"playlist-feed-cache");
        File cached=new File(directory,sha256("initial|"+feedUrl)+".m3u");
        Properties metadata=readProperties(new File(cached.getPath()+".properties"));
        return matchesBody(metadata,cached)&&PlaylistFeedIndex.indexedEntryCount(cached)>=0
                ?cached:null;
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

    /** Keep the app's private cache bounded even when many feeds are configured. */
    private static void pruneOldEntries(File directory, File keep) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".m3u"));
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File file : files) total += cacheEntrySize(file);
        if (total <= MAX_CACHE_BYTES) return;
        java.util.Arrays.sort(files, (left, right) ->
                Long.compare(lastAccess(left), lastAccess(right)));
        for (File file : files) {
            if (total <= MAX_CACHE_BYTES) break;
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
        } catch (IOException ignored) {
            // A broken metadata sidecar only forces a safe revalidation/download.
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }
}
