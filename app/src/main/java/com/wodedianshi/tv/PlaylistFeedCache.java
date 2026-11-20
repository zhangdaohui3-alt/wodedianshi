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
    private static final long MAX_AGE_MS = 30L * 60L * 1000L;
    private static final long MAX_BYTES = 96L * 1024L * 1024L;
    private static final long MAX_CACHE_BYTES = 192L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;

    private PlaylistFeedCache() { }

    public static File obtain(Context context, String feedUrl) throws IOException {
        if (context == null || feedUrl == null || feedUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("context/feedUrl is empty");
        }
        File directory = new File(context.getCacheDir(), "playlist-feed-cache");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create playlist cache directory");
        }
        String key = sha256(feedUrl);
        File cached = new File(directory, key + ".m3u");
        File metadata = new File(directory, key + ".properties");
        Properties old = readProperties(metadata);
        long cachedAt = parseLong(old.getProperty("cachedAt"), 0L);
        boolean hasValidCache = cached.isFile() && cached.length() > 0L
                && isPlaylistFile(cached);

        if (hasValidCache && System.currentTimeMillis() - cachedAt >= 0L
                && System.currentTimeMillis() - cachedAt < MAX_AGE_MS) {
            cached.setLastModified(System.currentTimeMillis());
            pruneOldEntries(directory, cached);
            return cached;
        }

        HttpURLConnection connection = null;
        File temporary = new File(directory, key + ".m3u.tmp");
        File metadataTemporary = new File(directory, key + ".properties.tmp");
        try {
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
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && hasValidCache) {
                old.setProperty("cachedAt", Long.toString(System.currentTimeMillis()));
                writePropertiesAtomically(metadata, metadataTemporary, old);
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
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > MAX_BYTES) {
                        throw new IOException("Playlist exceeds cache size limit");
                    }
                    output.write(buffer, 0, count);
                }
            }
            if (copied == 0L || !isPlaylistFile(temporary)) {
                throw new IOException("Downloaded resource is not a valid M3U playlist");
            }

            // Publish the new snapshot only after the complete response passed validation.
            if (cached.exists() && !cached.delete()) {
                throw new IOException("Cannot replace old playlist cache");
            }
            if (!temporary.renameTo(cached)) {
                throw new IOException("Cannot publish playlist cache snapshot");
            }

            Properties fresh = new Properties();
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

    /** Keep the app's private cache bounded even when many feeds are configured. */
    private static void pruneOldEntries(File directory, File keep) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".m3u"));
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File file : files) total += Math.max(0L, file.length());
        if (total <= MAX_CACHE_BYTES) return;
        java.util.Arrays.sort(files, (left, right) ->
                Long.compare(left.lastModified(), right.lastModified()));
        for (File file : files) {
            if (total <= MAX_CACHE_BYTES) break;
            if (file.equals(keep)) continue;
            long size = Math.max(0L, file.length());
            if (!file.delete()) continue;
            total -= size;
            String name = file.getName();
            File metadata = new File(directory,
                    name.substring(0, name.length() - ".m3u".length()) + ".properties");
            if (metadata.exists()) metadata.delete();
        }
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
        try (OutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, "My TV playlist cache metadata");
            output.flush();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace playlist cache metadata");
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Cannot publish playlist cache metadata");
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
