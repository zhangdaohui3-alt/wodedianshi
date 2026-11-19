package com.wodedianshi.tv;

import android.content.Context;
import com.wodedianshi.tv.db.AppStateStore;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-memory source memory backed durably by the single Room app-state table. */
public final class SourceMemoryStore {
    private static final String LAST_GOOD = "source_last_good_v1";
    private static final String COOLDOWNS = "source_cooldowns_v1";
    private static final long LAST_GOOD_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRIES = 500;
    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static JSONObject lastGood = new JSONObject();
    private static JSONObject cooldowns = new JSONObject();
    private static volatile boolean initialized = false;
    private static volatile Context appContext;

    private SourceMemoryStore() {}

    /** Must be called on a background thread before playback begins. */
    public static void initialize(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        Context application = context.getApplicationContext();
        String savedGood = AppStateStore.get(application, LAST_GOOD, "{}");
        String savedCooldowns = AppStateStore.get(application, COOLDOWNS, "{}");
        synchronized (LOCK) {
            appContext = application;
            try { lastGood = new JSONObject(savedGood); }
            catch (Exception malformed) { lastGood = new JSONObject(); }
            try { cooldowns = new JSONObject(savedCooldowns); }
            catch (Exception malformed) { cooldowns = new JSONObject(); }
            initialized = true;
        }
    }

    public static String channelKey(String name, String group, String primaryUrl) {
        return StableKey.channel(name, group, primaryUrl);
    }

    public static String legacyChannelKey(String name, String group, String primaryUrl) {
        return StableKey.legacyChannel(name, group, primaryUrl);
    }

    public static String channelIdentityKey(String identity) {
        if (identity == null || identity.isEmpty()) return "";
        return StableKey.channelIdentity(identity);
    }

    public static void saveLastGood(Context context, String channelKey, String url) {
        if (context == null || channelKey == null || channelKey.isEmpty()
                || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        String snapshot;
        synchronized (LOCK) {
            if (!initialized) initialize(context);
            try {
                JSONObject entry = new JSONObject();
                entry.put("url", url);
                entry.put("at", System.currentTimeMillis());
                lastGood.put(channelKey, entry);
                trimOldest(lastGood);
                snapshot = lastGood.toString();
            } catch (Exception ignored) { return; }
        }
        persist(context, LAST_GOOD, snapshot);
    }

    public static String getLastGood(Context context, String channelKey) {
        return getLastGood(context, channelKey, null);
    }

    public static String getLastGood(Context context, String channelKey, String legacyChannelKey) {
        return getLastGood(context, channelKey, null, legacyChannelKey);
    }

    public static String getLastGood(Context context, String channelKey,
                                     String previousChannelKey, String legacyChannelKey) {
        if (context == null || channelKey == null || channelKey.isEmpty()) return null;
        synchronized (LOCK) {
            if (!initialized) return null;
            try {
                JSONObject entry = lastGood.optJSONObject(channelKey);
                if (entry == null && previousChannelKey != null) entry = lastGood.optJSONObject(previousChannelKey);
                if (entry == null && legacyChannelKey != null) entry = lastGood.optJSONObject(legacyChannelKey);
                if (entry == null) return null;
                long age = System.currentTimeMillis() - entry.optLong("at", 0L);
                if (age < 0L || age > LAST_GOOD_MAX_AGE_MS) return null;
                String url = entry.optString("url", "");
                return url.startsWith("http://") || url.startsWith("https://") ? url : null;
            } catch (Exception ignored) { return null; }
        }
    }

    public static void coolDown(Context context, String url, long durationMs) {
        if (context == null || url == null || url.isEmpty() || durationMs <= 0L) return;
        String snapshot;
        synchronized (LOCK) {
            if (!initialized) initialize(context);
            try {
                long now = System.currentTimeMillis();
                Iterator<String> it = cooldowns.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    if (cooldowns.optLong(key, 0L) <= now) it.remove();
                }
                String canonicalKey = StableKey.url(SourceSelectionPolicy.endpointKey(url));
                String originalKey = StableKey.url(url);
                cooldowns.put(canonicalKey, now + durationMs);
                if (!canonicalKey.equals(originalKey)) cooldowns.remove(originalKey);
                cooldowns.remove(StableKey.legacyUrl(url));
                trimOldest(cooldowns);
                snapshot = cooldowns.toString();
            } catch (Exception ignored) { return; }
        }
        persist(context, COOLDOWNS, snapshot);
    }

    public static boolean isCoolingDown(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return false;
        synchronized (LOCK) {
            if (!initialized) return false;
            long until = cooldowns.optLong(StableKey.url(SourceSelectionPolicy.endpointKey(url)), 0L);
            if (until <= 0L) until = cooldowns.optLong(StableKey.url(url), 0L);
            if (until <= 0L) until = cooldowns.optLong(StableKey.legacyUrl(url), 0L);
            return until > System.currentTimeMillis();
        }
    }

    private static void persist(Context context, String key, String value) {
        Context application = context.getApplicationContext();
        try {
            WRITER.execute(() -> {
                try { AppStateStore.put(application, key, value); }
                catch (Throwable failure) {
                    android.util.Log.e("MyTVSourceMemory", "Room source-memory write failed: " + key, failure);
                }
            });
        } catch (RuntimeException rejected) {
            android.util.Log.e("MyTVSourceMemory", "source-memory writer rejected key=" + key, rejected);
        }
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
