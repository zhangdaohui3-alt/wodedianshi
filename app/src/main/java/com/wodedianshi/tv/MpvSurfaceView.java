package com.wodedianshi.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.util.regex.Pattern;

import is.xyz.mpv.MPVLib;

/**
 * A single-owner MPV Surface view. It destroys libmpv synchronously before
 * Android releases the Surface, matching the hardened pinned JNI lifecycle.
 *
 * This class is not attached to MainActivity until the native audit artifact
 * is packaged and the complete adapter is verified.
 */
public final class MpvSurfaceView extends SurfaceView implements
        SurfaceHolder.Callback, MPVLib.EventObserver, MPVLib.LogObserver,
        PlaybackEngineCoordinator.Session {
    private static final String TAG = "MyTV-MPV";
    private static final Pattern URL_QUERY =
            Pattern.compile("(https?://[^\\s?#]+)\\?[^\\s#]*");

    public interface Listener {
        void onEvent(int eventId);
        void onProperty(String property, Object value);
        void onLog(String prefix, int level, String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private PlaybackEnginePolicy.Candidate candidate;
    private MpvOptionPolicy.Options options;
    private File configDir;
    private File cacheDir;
    private String mediaUrl;
    private boolean started;
    private boolean created;
    private boolean initialized;
    private boolean surfaceReady;
    private boolean callbackRegistered;
    private boolean observersRegistered;
    private boolean released;

    public MpvSurfaceView(Context context) {
        this(context, null, null);
    }

    public MpvSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, null);
    }

    public MpvSurfaceView(Context context, AttributeSet attrs, Listener listener) {
        super(context, attrs);
        this.listener = listener;
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    /** Start the native engine and attach to this view's Surface. Must run on the UI thread. */
    public void start(File configDirectory, File cacheDirectory,
                      PlaybackEnginePolicy.Candidate playbackCandidate, String url) {
        requireMainThread();
        if (released) throw new IllegalStateException("MPV session has already been released");
        if (started) throw new IllegalStateException("MPV session is already started");
        if (configDirectory == null || cacheDirectory == null || playbackCandidate == null) {
            throw new IllegalArgumentException("MPV directories and candidate are required");
        }
        candidate = playbackCandidate;
        options = MpvOptionPolicy.forCandidate(playbackCandidate);
        configDir = configDirectory;
        cacheDir = cacheDirectory;
        mediaUrl = url;
        started = true;
        registerObservers();
        createNativePlayer();
        holder.addCallback(this);
        callbackRegistered = true;
    }

    /** Replace the media without replacing the native player or Surface. */
    public void play(String url) {
        requireMainThread();
        if (!started || released) throw new IllegalStateException("MPV session is not active");
        mediaUrl = url;
        if (initialized && surfaceReady && url != null && !url.trim().isEmpty()) {
            MPVLib.INSTANCE.command(new String[]{"loadfile", url, "replace"});
        }
    }

    private void createNativePlayer() {
        if (initialized) return;
        if (created) throw new IllegalStateException("MPV native context was created but not initialized");
        MPVLib.INSTANCE.create(getContext().getApplicationContext());
        created = true;

        setOption("config", "no");
        setOption("config-dir", configDir.getAbsolutePath());
        setOption("gpu-shader-cache-dir", cacheDir.getAbsolutePath());
        setOption("icc-cache-dir", cacheDir.getAbsolutePath());
        setOption("profile", "fast");
        setOption("vo", options.videoOutput);
        if (options.gpuContext != null) setOption("gpu-context", options.gpuContext);
        setOption("hwdec", options.hardwareDecoder);
        setOption("cache", "yes");
        setOption("demuxer-readahead-secs", "5");
        setOption("demuxer-max-bytes", "32MiB");
        setOption("demuxer-max-back-bytes", "16MiB");
        setOption("force-window", "no");
        setOption("idle", "once");
        setOption("input-default-bindings", "no");
        setOption("input-vo-keyboard", "no");
        setOption("osd-level", "0");
        setOption("msg-level", "all=warn");

        MPVLib.INSTANCE.init();
        initialized = true;
        MPVLib.INSTANCE.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.INSTANCE.observeProperty("vo", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        MPVLib.INSTANCE.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("drop-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.INSTANCE.observeProperty("vo-delayed-frame-count", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.INSTANCE.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
    }

    private void setOption(String name, String value) {
        int result = MPVLib.INSTANCE.setOptionString(name, value);
        if (result < 0) {
            Log.w(TAG, "MPV option rejected: " + name + " (" + result + ")");
        }
    }

    private void registerObservers() {
        if (observersRegistered) return;
        MPVLib.addObserver(this);
        MPVLib.addLogObserver(this);
        observersRegistered = true;
    }

    private void unregisterObservers() {
        if (!observersRegistered) return;
        MPVLib.removeObserver(this);
        MPVLib.removeLogObserver(this);
        observersRegistered = false;
    }

    private void destroyNativePlayer() {
        if (!initialized) return;
        // MPVLib.destroy joins its event thread, terminates libmpv, then the
        // patched JNI releases the global Surface reference.
        MPVLib.INSTANCE.destroy();
        initialized = false;
        created = false;
        surfaceReady = false;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (released || !started) return;
        if (!initialized) createNativePlayer();
        MPVLib.INSTANCE.attachSurface(holder.getSurface());
        MPVLib.INSTANCE.setOptionString("force-window", "yes");
        MPVLib.INSTANCE.setPropertyString("vo", options.videoOutput);
        surfaceReady = true;
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()) {
            MPVLib.INSTANCE.command(new String[]{"loadfile", mediaUrl, "replace"});
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (initialized) {
            MPVLib.INSTANCE.setPropertyString("android-surface-size", width + "x" + height);
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        destroyNativePlayer();
    }

    @Override public void stopAndRelease() {
        requireMainThread();
        if (released) return;
        released = true;
        if (callbackRegistered) {
            holder.removeCallback(this);
            callbackRegistered = false;
        }
        destroyNativePlayer();
        unregisterObservers();
        started = false;
    }

    @Override public void eventProperty(String property) {
        postProperty(property, null);
    }

    @Override public void eventProperty(String property, long value) {
        postProperty(property, value);
    }

    @Override public void eventProperty(String property, boolean value) {
        postProperty(property, value);
    }

    @Override public void eventProperty(String property, String value) {
        postProperty(property, value);
    }

    @Override public void eventProperty(String property, double value) {
        postProperty(property, value);
    }

    @Override public void event(int eventId) {
        if (listener != null) mainHandler.post(() -> listener.onEvent(eventId));
    }

    @Override public void logMessage(String prefix, int level, String text) {
        if (listener == null) return;
        String safeText = URL_QUERY.matcher(text == null ? "" : text).replaceAll("$1?[redacted]");
        mainHandler.post(() -> listener.onLog(prefix, level, safeText));
    }

    private void postProperty(String property, Object value) {
        if (listener != null) mainHandler.post(() -> listener.onProperty(property, value));
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("MPV Surface lifecycle must run on the main thread");
        }
    }
}
