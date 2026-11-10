package com.wodedianshi.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Broad public movie metadata lookup. Metadata and poster art are kept separate
 * from playback URLs: Apple/Wikipedia records never become playable streams.
 */
public final class VodMetadataSearch {
    private static final int CONNECT_TIMEOUT_MS = 2400;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_RESULTS_PER_WIKI = 40;
    private static final int MAX_RESULTS_APPLE = 40;
    // Search multiple Apple storefronts regardless of the selected region. The
    // region switch changes ordering/labels, not whether a title is discoverable.
    private static final String[] APPLE_STOREFRONTS =
            {"cn", "hk", "tw", "us", "gb", "jp", "kr", "au", "ca", "fr", "de", "in"};

    private VodMetadataSearch() {}

    public static List<VodTitleRecord> search(String rawQuery, String region)
            throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty() || Thread.currentThread().isInterrupted()) {
            return new ArrayList<>();
        }
        boolean china = "中国".equals(region);
        ExecutorService providers = Executors.newFixedThreadPool(6);
        CompletionService<List<VodTitleRecord>> completion =
                new ExecutorCompletionService<>(providers);
        ArrayList<Future<List<VodTitleRecord>>> tasks = new ArrayList<>();
        LinkedHashMap<String, VodTitleRecord> merged = new LinkedHashMap<>();
        try {
            // Fan out across regional storefronts and both language editions of
            // Wikipedia. A selected region is a ranking hint, never a hard filter.
            for (String storefront : APPLE_STOREFRONTS) {
                final String country = storefront;
                tasks.add(completion.submit(() -> {
                    LinkedHashMap<String, VodTitleRecord> local = new LinkedHashMap<>();
                    String label = "cn".equals(country) || "hk".equals(country)
                            || "tw".equals(country) ? "中国/华语" : "国外/国际";
                    collectApple(query, country, label, local);
                    return new ArrayList<>(local.values());
                }));
            }
            tasks.add(completion.submit(() -> {
                LinkedHashMap<String, VodTitleRecord> local = new LinkedHashMap<>();
                collectWiki("zh", "中国/华语", query, local);
                return new ArrayList<>(local.values());
            }));
            tasks.add(completion.submit(() -> {
                LinkedHashMap<String, VodTitleRecord> local = new LinkedHashMap<>();
                collectWiki("en", "国外/国际", query, local);
                return new ArrayList<>(local.values());
            }));
            long deadline = android.os.SystemClock.elapsedRealtime() + 7600L;
            for (int i = 0; i < tasks.size() && !Thread.currentThread().isInterrupted(); i++) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                Future<List<VodTitleRecord>> ready = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (ready == null) break;
                try {
                    List<VodTitleRecord> records = ready.get();
                    for (VodTitleRecord record : records) {
                        if (record == null || record.id.isEmpty() || record.title.isEmpty()) continue;
                        merged.putIfAbsent(record.id, record);
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            for (Future<?> task : tasks) if (!task.isDone()) task.cancel(true);
            providers.shutdownNow();
        }
        ArrayList<VodTitleRecord> result = new ArrayList<>(merged.values());
        // Keep the chosen region near the top without discarding other regions.
        result.sort((a, b) -> {
            boolean aPreferred = china
                    ? a.region.contains("中国") : a.region.contains("国外");
            boolean bPreferred = china
                    ? b.region.contains("中国") : b.region.contains("国外");
            return Boolean.compare(bPreferred, aPreferred);
        });
        return result;
    }

    private static void collectApple(String query, String country, String region,
                                     Map<String, VodTitleRecord> out) throws Exception {
        String endpoint = "https://itunes.apple.com/search?media=movie&entity=movie&limit="
                + MAX_RESULTS_APPLE + "&country=" + country + "&term="
                + URLEncoder.encode(query, "UTF-8");
        JSONObject root = new JSONObject(get(endpoint));
        JSONArray results = root.optJSONArray("results");
        if (results == null) return;
        for (int i = 0; i < results.length(); i++) {
            if (Thread.currentThread().isInterrupted()) return;
            JSONObject hit = results.optJSONObject(i);
            if (hit == null) continue;
            String title = clean(hit.optString("trackName", ""));
            String id = hit.optString("trackId", "");
            if (title.isEmpty() || id.isEmpty()) continue;
            String poster = hit.optString("artworkUrl100", "");
            String page = hit.optString("trackViewUrl", "");
            String synopsis = clean(hit.optString("longDescription",
                    hit.optString("shortDescription", "")));
            String alternate = clean(hit.optString("collectionName", ""));
            int year = 0;
            String release = hit.optString("releaseDate", "");
            if (release.length() >= 4) {
                try { year = Integer.parseInt(release.substring(0, 4)); }
                catch (NumberFormatException ignored) {}
            }
            String key = "apple:" + id;
            if (!out.containsKey(key)) {
                out.put(key, new VodTitleRecord(key, title, alternate, synopsis, region,
                        "Apple影视资料 (" + country.toUpperCase(Locale.ROOT) + ")", page, year, poster));
            }
        }
    }

    private static void collectWiki(String language, String region, String query,
                                    Map<String, VodTitleRecord> out) throws Exception {
        if (Thread.currentThread().isInterrupted()) return;
        String host = "zh".equals(language) ? "zh.wikipedia.org" : "en.wikipedia.org";
        String endpoint = "https://" + host + "/w/api.php?action=query&generator=search"
                + "&gsrnamespace=0&gsrlimit=" + MAX_RESULTS_PER_WIKI
                + "&gsrsearch=" + URLEncoder.encode(query, "UTF-8")
                + "&prop=pageimages|extracts&piprop=thumbnail&pithumbsize=480"
                + "&exintro=1&explaintext=1&exsentences=2&format=json&utf8=1";
        JSONObject root = new JSONObject(get(endpoint));
        JSONObject queryObject = root.optJSONObject("query");
        JSONObject pages = queryObject == null ? null : queryObject.optJSONObject("pages");
        if (pages == null) return;
        java.util.Iterator<String> keys = pages.keys();
        while (keys.hasNext()) {
            if (Thread.currentThread().isInterrupted()) return;
            JSONObject hit = pages.optJSONObject(keys.next());
            if (hit == null) continue;
            String title = clean(hit.optString("title", ""));
            int pageId = hit.optInt("pageid", 0);
            if (title.isEmpty() || pageId <= 0) continue;
            String key = language + ":" + pageId;
            String snippet = clean(hit.optString("extract", ""));
            JSONObject thumbnail = hit.optJSONObject("thumbnail");
            String poster = thumbnail == null ? "" : thumbnail.optString("source", "");
            String page = "https://" + host + "/?curid=" + pageId;
            if (!out.containsKey(key)) {
                out.put(key, new VodTitleRecord(key, title, "", snippet, region,
                        "Wikipedia (" + language + ")", page, 0, poster));
            }
        }
    }

    private static String get(String address) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("metadata search cancelled");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "MyTV/2.62 (public metadata lookup)");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("metadata HTTP " + status);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.io.InterruptedIOException("metadata search cancelled");
                    }
                    output.write(buffer, 0, count);
                    if (output.size() > 2 * 1024 * 1024) {
                        throw new java.io.IOException("metadata response too large");
                    }
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
