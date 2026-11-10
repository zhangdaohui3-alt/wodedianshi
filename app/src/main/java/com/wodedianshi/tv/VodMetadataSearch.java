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
 * Public encyclopaedia metadata fallback. It never treats an article URL as a
 * video stream and never attempts to access paid or DRM-protected catalogues.
 */
public final class VodMetadataSearch {
    private static final int CONNECT_TIMEOUT_MS = 2200;
    private static final int READ_TIMEOUT_MS = 2800;
    private static final int MAX_RESULTS_PER_WIKI = 8;

    private VodMetadataSearch() {}

    public static List<VodTitleRecord> search(String rawQuery, String region)
            throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty() || Thread.currentThread().isInterrupted()) {
            return new ArrayList<>();
        }
        LinkedHashMap<String, VodTitleRecord> merged = new LinkedHashMap<>();
        boolean china = "中国".equals(region);
        boolean abroad = "国外".equals(region);
        if (china || !abroad) collect("zh", "中国", query, merged);
        if (abroad || !china) collect("en", "国外/国际", query, merged);
        return new ArrayList<>(merged.values());
    }

    private static void collect(String language, String region, String query,
                                Map<String, VodTitleRecord> out) throws Exception {
        if (Thread.currentThread().isInterrupted()) return;
        String host = "zh".equals(language) ? "zh.wikipedia.org" : "en.wikipedia.org";
        String endpoint = "https://" + host + "/w/api.php?action=query&list=search"
                + "&srnamespace=0&srlimit=" + MAX_RESULTS_PER_WIKI
                + "&srprop=snippet&format=json&utf8=1&srsearch="
                + URLEncoder.encode(query, "UTF-8");
        JSONObject root = new JSONObject(get(endpoint));
        JSONObject queryObject = root.optJSONObject("query");
        JSONArray hits = queryObject == null ? null : queryObject.optJSONArray("search");
        if (hits == null) return;
        for (int i = 0; i < hits.length(); i++) {
            if (Thread.currentThread().isInterrupted()) return;
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            String title = clean(hit.optString("title", ""));
            int pageId = hit.optInt("pageid", 0);
            if (title.isEmpty() || pageId <= 0) continue;
            String key = language + ":" + pageId;
            String snippet = clean(hit.optString("snippet", "")
                    .replaceAll("<[^>]+>", " ")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&amp;", "&"));
            String page = "https://" + host + "/?curid=" + pageId;
            out.putIfAbsent(key, new VodTitleRecord(key, title, "", snippet,
                    region, "Wikipedia (" + language + ")", page, 0));
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
        connection.setRequestProperty("User-Agent", "MyTV/2.60 (public metadata lookup)");
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
                    if (output.size() > 1024 * 1024) {
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
        return value.replaceAll("\\s+", " ").trim();
    }
}
