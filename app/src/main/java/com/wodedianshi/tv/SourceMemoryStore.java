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
    private static final String QOE = "source_qoe_v1";
    private static final long LAST_GOOD_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRIES = 500;
    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static JSONObject lastGood = new JSONObject();
    private static JSONObject cooldowns = new JSONObject();
    private static volatile boolean initialized = false;
    // Each published value is private and never mutated after publication.
    private static final java.util.concurrent.ConcurrentHashMap<String, JSONObject> qoe =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean qoeFlushQueued;
    private static final java.util.HashSet<String> memoryFlushQueued = new java.util.HashSet<>();

    private SourceMemoryStore() {}

    /** Must be called on a background thread before playback begins. */
    public static void initialize(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        if (initialized) return;
        Context application = context.getApplicationContext();
        String savedQoe = AppStateStore.get(application, QOE, "{}");
        String savedGood = AppStateStore.get(application, LAST_GOOD, "{}");
        String savedCooldowns = AppStateStore.get(application, COOLDOWNS, "{}");
        synchronized (LOCK) {
            // Activity recreation must not overwrite unflushed process-owned memory.
            if (initialized) return;
            try {
                JSONObject persisted = new JSONObject(savedQoe);
                Iterator<String> keys = persisted.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject entry = persisted.optJSONObject(key);
                    if (entry != null) qoe.put(key, entry);
                }
            } catch (Exception malformed) {
                android.util.Log.w("MyTVSourceMemory", "Invalid stored QoE history", malformed);
            }
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
        synchronized (LOCK) {
            if (!initialized) return;
            try {
                JSONObject entry = new JSONObject();
                entry.put("url", url);
                entry.put("at", System.currentTimeMillis());
                lastGood.put(channelKey, entry);
                trimOldest(lastGood);
                scheduleMemoryFlush(context, LAST_GOOD);
            } catch (Exception ignored) { return; }
        }
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
        synchronized (LOCK) {
            if (!initialized) return;
            try {
                long now = System.currentTimeMillis();
                Iterator<String> it = cooldowns.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    if (cooldowns.optLong(key, 0L) <= now) it.remove();
                }
                cooldowns.put(StableKey.url(SourceSelectionPolicy.endpointKey(url)), now + durationMs);
                trimOldest(cooldowns);
                scheduleMemoryFlush(context, COOLDOWNS);
            } catch (Exception ignored) { return; }
        }
    }

    public static boolean isCoolingDown(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return false;
        synchronized (LOCK) {
            if (!initialized) return false;
            long until = cooldowns.optLong(StableKey.url(SourceSelectionPolicy.endpointKey(url)), 0L);
            return until > System.currentTimeMillis();
        }
    }

    // Called under LOCK. Snapshot selection and queue ordering are serialized;
    // the worker performs encoding and disk I/O after releasing the lock.
    private static void scheduleMemoryFlush(Context context, String key) {
        if (!memoryFlushQueued.add(key)) return;
        Context application = context.getApplicationContext();
        try {
            WRITER.execute(() -> {
                java.util.HashMap<String, Object> copy = new java.util.HashMap<>();
                synchronized (LOCK) {
                    JSONObject source = LAST_GOOD.equals(key) ? lastGood : cooldowns;
                    Iterator<String> keys = source.keys();
                    while (keys.hasNext()) {
                        String item = keys.next();
                        copy.put(item, source.opt(item));
                    }
                    memoryFlushQueued.remove(key);
                }
                write(application, key, new JSONObject(copy).toString());
            });
        } catch (RuntimeException rejected) {
            memoryFlushQueued.remove(key);
            android.util.Log.e("MyTVSourceMemory", "source-memory queue rejected", rejected);
        }
    }

    public static void recordMetric(Context context, String url, String event, long readyMs) {
        if (!initialized || context == null || url == null || url.isEmpty()) return;
        Context application = context.getApplicationContext();
        final long at = System.currentTimeMillis();
        try {
            WRITER.execute(() -> {
                try {
                    String key = StableKey.url(url);
                    JSONObject old = qoe.get(key);
                    // Only copy one endpoint, never the entire history on a tune.
                    JSONObject m = old == null ? new JSONObject() : new JSONObject(old.toString());
                    if ("attempt".equals(event)) m.put("attempts", m.optInt("attempts",0)+1);
                    else if ("failure".equals(event)) m.put("failures",m.optInt("failures",0)+1);
                    else if ("buffer".equals(event)) m.put("buffers",m.optInt("buffers",0)+1);
                    else if ("audioCodecError".equals(event)) m.put("audioCodecErrors",m.optInt("audioCodecErrors",0)+1);
                    else if ("audioSinkError".equals(event)) m.put("audioSinkErrors",m.optInt("audioSinkErrors",0)+1);
                    else if ("videoCodecError".equals(event)) m.put("videoCodecErrors",m.optInt("videoCodecErrors",0)+1);
                    else if ("ready".equals(event)) {
                        int count=m.optInt("ready",0); long avg=m.optLong("avgReadyMs",0L);
                        m.put("ready",count+1); m.put("avgReadyMs",count==0?readyMs:((avg*count)+readyMs)/(count+1));
                        m.put("lastReady",at);
                    } else if ("firstFrame".equals(event)) {
                        int count=m.optInt("firstFrames",0); long avg=m.optLong("avgFirstFrameMs",0L);
                        m.put("firstFrames",count+1); m.put("avgFirstFrameMs",count==0?readyMs:((avg*count)+readyMs)/(count+1));
                        m.put("lastFirstFrame",at);
                    } else if ("stable".equals(event)) {
                        m.put("stablePlays",m.optInt("stablePlays",0)+1); m.put("lastStable",at);
                    } else return;
                    m.put("updated",at);
                    // Explicitly record the last event; timestamp equality alone
                    // cannot order two callbacks in the same millisecond.
                    m.put("lastEvent",event);
                    qoe.put(key,m);
                    // A flush queued behind existing metric tasks combines a burst.
                    if (!qoeFlushQueued) {
                        qoeFlushQueued=true;
                        WRITER.execute(() -> {
                            qoeFlushQueued=false;
                            write(application,QOE,new JSONObject(qoe).toString());
                        });
                    }
                } catch (Exception failure) {
                    android.util.Log.e("MyTVSourceMemory","QoE event failed",failure);
                }
            });
        } catch (RuntimeException rejected) {
            android.util.Log.e("MyTVSourceMemory","QoE queue rejected",rejected);
        }
    }

    /** Published metrics are read here only; never expose mutable JSON to callers. */
    static double rankedScore(String url,JSONObject probes,long now){
        return SourceQualityScorer.rankScore(url,probes,qoe::get,now);
    }

    private static void write(Context context, String key, String value) {
        try { AppStateStore.put(context, key, value); }
        catch (Throwable failure) {
            android.util.Log.e("MyTVSourceMemory", "Room source-memory write failed: " + key, failure);
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
