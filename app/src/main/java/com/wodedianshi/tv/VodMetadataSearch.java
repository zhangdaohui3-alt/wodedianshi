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

/**
 * Broad public movie metadata lookup. Metadata and poster art are kept separate
 * from playback URLs: Apple/Wikipedia records never become playable streams.
 */
public final class VodMetadataSearch {
    private static final int CONNECT_TIMEOUT_MS = 3200;
    private static final int READ_TIMEOUT_MS = 4200;
    private static final int MAX_RESULTS_PER_WIKI = 25;
    private static final int MAX_RESULTS_APPLE = 25;

    private VodMetadataSearch() {}

    public static List<VodTitleRecord> search(String rawQuery, String region)
            throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty() || Thread.currentThread().isInterrupted()) {
            return new ArrayList<>();
        }
        LinkedHashMap<String, VodTitleRecord> merged = new LinkedHashMap<>();
        boolean china = "中国".equals(region);
        // Apple Search provides artwork and store metadata without requiring an API key.
        // It does not prove that a full film is available to stream.
        try { collectApple(query, china ? "cn" : "us", china ? "中国" : "国外/国际", merged); }
        catch (Exception ignored) {}
        // Search both language indexes even in a region-specific mode. Region changes
        // ranking/labels, not recall: Chinese films may have English catalogue entries.
        if (china) {
            try { collectWiki("zh", "中国", query, merged); } catch (Exception ignored) {}
            try { collectWiki("en", "国外/国际", query, merged); } catch (Exception ignored) {}
        } else {
            try { collectWiki("en", "国外/国际", query, merged); } catch (Exception ignored) {}
            try { collectWiki("zh", "中国", query, merged); } catch (Exception ignored) {}
        }
        return new ArrayList<>(merged.values());
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
            String key = "apple:" + country + ":" + id;
            if (!out.containsKey(key)) {
                out.put(key, new VodTitleRecord(key, title, alternate, synopsis, region,
                        "Apple影视资料", page, year, poster));
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
