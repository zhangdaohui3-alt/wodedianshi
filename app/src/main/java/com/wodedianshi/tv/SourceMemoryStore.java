package com.wodedianshi.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.Iterator;

/** Persistent memory for proven live sources and temporary automatic-failover cooldowns. */
public final class SourceMemoryStore {
    private static final String PREFS = "mytv";
    private static final String LAST_GOOD = "source_last_good_v1";
    private static final String COOLDOWNS = "source_cooldowns_v1";
    private static final long LAST_GOOD_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRIES = 500;

    private SourceMemoryStore() {}

    public static String channelKey(String name, String group, String primaryUrl) {
        return StableKey.channel(name, group, primaryUrl);
    }

    public static String legacyChannelKey(String name, String group, String primaryUrl) {
        return StableKey.legacyChannel(name, group, primaryUrl);
    }

    public static void saveLastGood(Context context, String channelKey, String url) {
        if (context == null || channelKey == null || channelKey.isEmpty()
                || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(prefs.getString(LAST_GOOD, "{}"));
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("at", System.currentTimeMillis());
            all.put(channelKey, entry);
            trimOldest(all);
            prefs.edit().putString(LAST_GOOD, all.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String getLastGood(Context context, String channelKey) {
        return getLastGood(context, channelKey, null);
    }

    public static String getLastGood(Context context, String channelKey, String legacyChannelKey) {
        if (context == null || channelKey == null || channelKey.isEmpty()) return null;
        try {
            JSONObject all = new JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(LAST_GOOD, "{}"));
            JSONObject entry = all.optJSONObject(channelKey);
            if (entry == null && legacyChannelKey != null) entry = all.optJSONObject(legacyChannelKey);
            if (entry == null) return null;
            long age = System.currentTimeMillis() - entry.optLong("at", 0L);
            if (age < 0L || age > LAST_GOOD_MAX_AGE_MS) return null;
            String url = entry.optString("url", "");
            return url.startsWith("http://") || url.startsWith("https://") ? url : null;
        } catch (Exception ignored) { return null; }
    }

    public static void coolDown(Context context, String url, long durationMs) {
        if (context == null || url == null || url.isEmpty() || durationMs <= 0L) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(prefs.getString(COOLDOWNS, "{}"));
            long now = System.currentTimeMillis();
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (all.optLong(key, 0L) <= now) it.remove();
            }
            all.put(StableKey.url(url), now + durationMs);
            all.remove(StableKey.legacyUrl(url));
            trimOldest(all);
            prefs.edit().putString(COOLDOWNS, all.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static boolean isCoolingDown(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return false;
        try {
            JSONObject all = new JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(COOLDOWNS, "{}"));
            long until = all.optLong(StableKey.url(url), 0L);
            if (until <= 0L) until = all.optLong(StableKey.legacyUrl(url), 0L);
            return until > System.currentTimeMillis();
        } catch (Exception ignored) { return false; }
    }

    private static void trimOldest(JSONObject object) throws Exception {
        while (object.length() > MAX_ENTRIES) {
            String oldestKey = null;
            long oldestValue = Long.MAX_VALUE;
            Iterator<String> it = object.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject entry = object.optJSONObject(key);
                long value = entry == null ? object.optLong(key, 0L) : entry.optLong("at", 0L);
                if (value < oldestValue) { oldestValue = value; oldestKey = key; }
            }
            if (oldestKey == null) break;
            object.remove(oldestKey);
        }
    }
}
