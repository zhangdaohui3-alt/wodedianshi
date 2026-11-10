package com.wodedianshi.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Independent cloud/local parental time-lock manager.
 *
 * Endpoint contract:
 * GET  <configured HTTPS URL> -> JSON config (see README_REMOTE_PARENT_LOCK.md)
 * POST <same URL>             -> offline usage batch (optional server support)
 *
 * The admin password is only held in memory after a successful HTTPS response.
 * The encrypted cached JSON is stored in app-private SharedPreferences.
 */
public final class ParentRemoteLockManager {
    public interface Listener {
        void onLockStateChanged(boolean locked, String reason);
        void onStatus(String message);
    }

    private static final String TAG = "MyTVRemoteLock";
    private static final String PREF = "mytv_remote_parent_lock";
    private static final String KEY_ALIAS = "mytv_remote_parent_lock_cache_v1";
    private static final long POLL_MS = 40_000L;
    private static final long TICK_MS = 10_000L;
    private static final long CLOCK_TOLERANCE_MS = 120_000L;

    private final Context app;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile Config config;
    private volatile boolean locked;
    private volatile boolean running;
    private volatile boolean lastOnline;
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> pollFuture;
    private volatile String lockReason = "";
    private long lastElapsed;
    private long lastWall;
    private String lastUsageDay = "";

    private static final class Config {
        boolean lockEnable;
        boolean forceLock;
        String startTime = "08:00";
        String endTime = "21:30";
        int maxDayMinute;
        boolean offlineStrict;
        String adminPassword = "";
        long serverTimeMs;
        JSONObject raw;
    }

    public ParentRemoteLockManager(Context context, Listener callback) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        listener = callback;
        loadEncryptedCache();
    }

    public boolean isLocked() { return locked; }
    public String getLockReason() { return lockReason; }

    public synchronized void start() {
        if (running) return;
        running = true;
        lastElapsed = SystemClock.elapsedRealtime();
        lastWall = System.currentTimeMillis();
        lastUsageDay = dayKey(lastWall);
        log("start; cached=" + (config != null));
        executor.execute(this::pollNow);
        tickFuture = executor.scheduleAtFixedRate(this::tickSafely, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        pollFuture = executor.scheduleAtFixedRate(this::pollSafely, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running) return;
        tickSafely();
        running = false;
        if (tickFuture != null) tickFuture.cancel(false);
        if (pollFuture != null) pollFuture.cancel(false);
        tickFuture = null; pollFuture = null;
        log("stop");
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    public void requestPoll() { if (running) executor.execute(this::pollSafely); }

    public void pollNow() {
        if (!running) return;
        String endpoint = prefs.getString("endpoint", "").trim();
        if (endpoint.isEmpty()) {
            config = null;
            setLocked(false, "");
            status("云端家长锁未配置接口地址");
            return;
        }
        if (config == null) loadEncryptedCache();
        if (!endpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
            status("拒绝非 HTTPS 家长锁接口；请配置 https:// 地址");
            applyCachedOrFailClosed();
            return;
        }
        if (!isOnline()) {
            log("network offline");
            lastOnline = false;
            applyOfflinePolicy();
            return;
        }
        HttpURLConnection c = null;
        try {
            log("network online; requesting config");
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            String body = readAll(c.getInputStream());
            JSONObject json = new JSONObject(body);
            Config next = parseConfig(json);
            long serverTime = json.optLong("server_time_ms", 0L);
            if (serverTime <= 0L && c.getDate() > 0L) serverTime = c.getDate();
            if (serverTime <= 0L) serverTime = System.currentTimeMillis();
            next.serverTimeMs = serverTime;
            if (detectClockTamper(serverTime)) {
                config = next;
                cacheConfig(json);
                setLocked(true, "检测到系统时间异常变更，请输入管理员密码");
                return;
            }
            config = next;
            prefs.edit().putLong("anchor_server_ms", serverTime)
                    .putLong("anchor_wall_ms", System.currentTimeMillis())
                    .putLong("anchor_elapsed_ms", SystemClock.elapsedRealtime())
                    .putLong("anchor_boot_ms", System.currentTimeMillis() - SystemClock.elapsedRealtime())
                    .apply();
            cacheConfig(json);
            lastOnline = true;
            log("config fetched and encrypted cache saved");
            flushPendingUsage(endpoint);
            evaluate();
        } catch (Exception e) {
            status("云端配置获取失败：" + e.getMessage());
            log("config fetch failed: " + e.getClass().getSimpleName());
            if (!isOnline()) applyOfflinePolicy(); else applyCachedOrFailClosed();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void pollSafely() {
        try { pollNow(); } catch (Throwable t) { log("poll error " + t.getClass().getSimpleName()); }
    }

    private void tickSafely() {
        try { tick(); } catch (Throwable t) { log("tick error " + t.getClass().getSimpleName()); }
    }

    private synchronized void tick() {
        if (!running) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        if (lastElapsed <= 0L) {
            lastElapsed = nowElapsed;
            lastWall = nowWall;
            lastUsageDay = dayKey(nowWall);
            return;
        }
        long elapsedDelta = Math.max(0L, Math.min(TICK_MS * 3, nowElapsed - lastElapsed));
        long wallDelta = nowWall - lastWall;
        lastElapsed = nowElapsed;
        lastWall = nowWall;
        if (ParentRemoteLockPolicy.clockJumped(wallDelta, elapsedDelta, CLOCK_TOLERANCE_MS)) {
            setLocked(true, "检测到系统时间被修改，已触发安全锁定");
            log("clock jump detected wallDelta=" + wallDelta + " elapsedDelta=" + elapsedDelta);
        }
        if (!locked && elapsedDelta > 0L) addUsage(elapsedDelta, nowWall);
        if (!isOnline()) applyOfflinePolicy(); else evaluate();
    }

    private void addUsage(long delta, long wallNow) {
        String today = dayKey(wallNow);
        long used = prefs.getLong("usage_ms", 0L);
        if (!today.equals(prefs.getString("usage_day", ""))) used = 0L;
        used = ParentRemoteLockPolicy.safeUsage(used, delta);
        SharedPreferences.Editor edit = prefs.edit().putString("usage_day", today).putLong("usage_ms", used);
        if (!isOnline()) {
            edit.putLong("pending_upload_ms", prefs.getLong("pending_upload_ms", 0L) + delta);
            if (prefs.getString("pending_batch_id", "").isEmpty())
                edit.putString("pending_batch_id", UUID.randomUUID().toString());
        }
        edit.apply();
        if (!today.equals(lastUsageDay)) {
            lastUsageDay = today;
            log("daily usage date rolled over");
        }
        log("usage +" + delta + "ms total=" + used);
    }

    private void evaluate() {
        Config c = config;
        if (System.currentTimeMillis() < prefs.getLong("remote_unlock_until", 0L)) {
            setLocked(false, "");
            return;
        }
        if (c == null) {
            if (prefs.getBoolean("remote_enabled", false)) applyOfflinePolicy();
            return;
        }
        if (!c.lockEnable) {
            setLocked(false, "");
            return;
        }
        if (c.forceLock) {
            setLocked(true, "家长已从云端发出立即锁定指令");
            return;
        }
        if (isClockAnchorInvalid()) {
            setLocked(true, "系统时间与最近联网时间不一致");
            return;
        }
        long now = System.currentTimeMillis();
        if (!isInsideAllowedWindow(now, c.startTime, c.endTime)) {
            setLocked(true, "当前不在家长允许的使用时段");
            return;
        }
        long used = currentDayUsage(now);
        if (c.maxDayMinute > 0 && used >= c.maxDayMinute * 60_000L) {
            setLocked(true, "今日观看时长已达到 " + c.maxDayMinute + " 分钟");
            return;
        }
        setLocked(false, "");
    }

    private void applyCachedOrFailClosed() {
        if (config != null) evaluate();
        else if (prefs.getBoolean("remote_enabled", false)) setLocked(true, "无法取得有效云端配置且没有可用缓存");
    }

    private void applyOfflinePolicy() {
        if (System.currentTimeMillis() < prefs.getLong("remote_unlock_until", 0L)) { setLocked(false, ""); return; }
        Config c = config;
        boolean enabled = prefs.getBoolean("remote_enabled", false);
        if (!enabled) { setLocked(false, ""); return; }
        if (ParentRemoteLockPolicy.shouldLockOffline(c != null && c.lockEnable && c.offlineStrict, c != null, enabled)) {
            setLocked(true, c == null ? "尚无有效云端缓存，无法确认家长规则" : "网络已断开，离线严格策略要求管理员解锁");
        } else if (c != null) {
            evaluate();
        }
    }

    private long currentDayUsage(long now) {
        String day = dayKey(now);
        if (!day.equals(prefs.getString("usage_day", ""))) return 0L;
        return Math.max(0L, prefs.getLong("usage_ms", 0L));
    }

    private boolean isInsideAllowedWindow(long now, String start, String end) {
        int s = minuteOfDay(start), e = minuteOfDay(end);
        if (s < 0 || e < 0) return false;
        if (s == e) return true; // equal endpoints mean all-day access
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return ParentRemoteLockPolicy.isInsideWindow(m, s, e);
    }

    private int minuteOfDay(String value) {
        try {
            String[] p = value.split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h >= 0 && h <= 23 && m >= 0 && m <= 59 ? h * 60 + m : -1;
        } catch (Exception e) { return -1; }
    }

    private boolean detectClockTamper(long serverTime) {
        long oldServer = prefs.getLong("anchor_server_ms", 0L);
        long oldElapsed = prefs.getLong("anchor_elapsed_ms", 0L);
        long oldWall = prefs.getLong("anchor_wall_ms", 0L);
        long oldBoot = prefs.getLong("anchor_boot_ms", 0L);
        long elapsed = SystemClock.elapsedRealtime();
        long currentBoot = System.currentTimeMillis() - elapsed;
        if (oldServer <= 0L) return false;
        if (oldBoot > 0L && Math.abs(currentBoot - oldBoot) > CLOCK_TOLERANCE_MS)
            return Math.abs(System.currentTimeMillis() - serverTime) > CLOCK_TOLERANCE_MS;
        if (oldElapsed <= 0L || elapsed < oldElapsed)
            return Math.abs(System.currentTimeMillis() - serverTime) > CLOCK_TOLERANCE_MS;
        long expectedServer = oldServer + (elapsed - oldElapsed);
        long expectedWall = oldWall + (elapsed - oldElapsed);
        long wallError = Math.abs(System.currentTimeMillis() - expectedWall);
        long serverError = Math.abs(serverTime - expectedServer);
        return wallError > CLOCK_TOLERANCE_MS || serverError > CLOCK_TOLERANCE_MS;
    }

    private boolean isClockAnchorInvalid() {
        long oldServer = prefs.getLong("anchor_server_ms", 0L);
        long oldElapsed = prefs.getLong("anchor_elapsed_ms", 0L);
        long oldBoot = prefs.getLong("anchor_boot_ms", 0L);
        if (oldServer <= 0L || oldElapsed <= 0L) return false;
        long elapsed = SystemClock.elapsedRealtime();
        long currentBoot = System.currentTimeMillis() - elapsed;
        if (oldBoot > 0L && Math.abs(currentBoot - oldBoot) > CLOCK_TOLERANCE_MS)
            return false; // reboot: wait for next trusted network timestamp
        if (elapsed < oldElapsed) return false;
        long expected = oldServer + elapsed - oldElapsed;
        return Math.abs(System.currentTimeMillis() - expected) > CLOCK_TOLERANCE_MS;
    }

    private Config parseConfig(JSONObject j) throws Exception {
        Config c = new Config();
        c.lockEnable = j.getBoolean("lock_enable");
        c.forceLock = j.optBoolean("force_lock", false);
        c.startTime = j.optString("start_time", "00:00");
        c.endTime = j.optString("end_time", "23:59");
        c.maxDayMinute = Math.max(0, j.optInt("max_day_minute", 0));
        c.offlineStrict = j.optBoolean("offline_strict", false);
        c.adminPassword = j.optString("admin_pwd", "");
        if (!c.adminPassword.matches("[0-9]{4,32}"))
            throw new IllegalArgumentException("admin_pwd 必须为 4–32 位数字");
        if (minuteOfDay(c.startTime) < 0 || minuteOfDay(c.endTime) < 0)
            throw new IllegalArgumentException("start_time/end_time 格式必须为 HH:mm");
        c.raw = j;
        return c;
    }

    private void loadEncryptedCache() {
        try {
            String encoded = prefs.getString("encrypted_config", "");
            if (encoded.isEmpty()) return;
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, iv.length);
            byte[] encrypted = new byte[all.length - iv.length];
            System.arraycopy(all, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getCacheKey(), new GCMParameterSpec(128, iv));
            String json = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            config = parseConfig(new JSONObject(json));
            log("encrypted config cache loaded");
        } catch (Exception e) {
            config = null;
            log("encrypted cache unavailable/invalid");
        }
    }

    private void cacheConfig(JSONObject json) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getCacheKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(iv); out.write(encrypted);
            prefs.edit().putString("encrypted_config", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).apply();
        } catch (Exception e) { log("cache encryption failed"); }
    }

    private SecretKey getCacheKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }

    public void grantAdminUnlock(long durationMs) {
        prefs.edit().putLong("remote_unlock_until", System.currentTimeMillis() + Math.max(60_000L, durationMs)).apply();
        setLocked(false, "");
        log("administrator temporary unlock granted");
    }

    public boolean verifyAdminPassword(String input) {
        Config c = config;
        return c != null && c.adminPassword != null && c.adminPassword.equals(input == null ? "" : input.trim());
    }

    private void flushPendingUsage(String endpoint) {
        long pending = prefs.getLong("pending_upload_ms", 0L);
        if (pending <= 0L) return;
        HttpURLConnection c = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId());
            payload.put("batch_id", prefs.getString("pending_batch_id", ""));
            payload.put("offline_usage_ms", pending);
            payload.put("usage_day", prefs.getString("usage_day", ""));
            payload.put("client_time_ms", System.currentTimeMillis());
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(bytes);
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                 prefs.edit().putLong("pending_upload_ms", 0L).remove("pending_batch_id").apply();
                log("offline usage uploaded " + pending + "ms");
            } else log("usage upload HTTP " + code);
        } catch (Exception e) { log("usage upload failed"); }
        finally { if (c != null) c.disconnect(); }
    }

    private String deviceId() {
        String id = prefs.getString("device_id", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device_id", id).apply(); }
        return id;
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return false; }
    }

    private void setLocked(boolean value, String reason) {
        boolean changed = locked != value || !String.valueOf(lockReason).equals(String.valueOf(reason));
        locked = value; lockReason = reason == null ? "" : reason;
        if (changed) {
            log(value ? "LOCK: " + reason : "UNLOCK by policy");
            main.post(() -> { if (listener != null) listener.onLockStateChanged(value, lockReason); });
        }
    }

    private void status(String value) {
        Log.w(TAG, value);
        main.post(() -> { if (listener != null) listener.onStatus(value); });
    }

    private void log(String value) { Log.i(TAG, value); }

    private String dayKey(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        return String.format(Locale.ROOT, "%04d-%03d", c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR));
    }

    private String readAll(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; int total = 0;
            while ((n = input.read(b)) != -1) { total += n; if (total > 65536) throw new IllegalStateException("配置响应超过 64KB"); out.write(b, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
