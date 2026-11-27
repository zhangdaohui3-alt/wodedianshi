package com.wodedianshi.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * Dedicated LibVLC backend for one VOD dialog. It owns its own LibVLC engine
 * and VLCVideoLayout and must never share the live-TV LibVLC instance.
 */
public final class LibVlcVodBackend implements VodPlaybackBackend {
    private final LibVlcPlaybackEngine engine;
    // VLC callbacks can race dialog dismissal; publish listener changes across threads.
    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean released = true;
    private volatile boolean revoked;
    private volatile boolean playing;
    private volatile long positionMs, durationMs;
    private final java.util.concurrent.ExecutorService worker;
    private final Runnable poll = this::pollTimeline;
    // Serialize posting the next tick with revocation, so a native poll that
    // finishes after dismissal cannot reinsert a removed callback.
    private synchronized void schedulePoll(long delayMs) {
        if (revoked || released) return;
        mainHandler.removeCallbacks(poll);
        mainHandler.postDelayed(poll, delayMs);
    }
    // Main-handler-owned; at most two inspections per backend lifetime.
    private int audioTrackChecks;

    private void scheduleAudioTrackInspection() {
        if (revoked || released || audioTrackChecks >= 2) return;
        ++audioTrackChecks;
        mainHandler.postDelayed(() -> {
            if (revoked || released) return;
            try { submit(engine::inspectAudioTrack); }
            catch (RuntimeException error) {
                Listener current = listener;
                if (!revoked && current != null) current.onAudioTrackState(-1, -1);
            }
        }, 3000L);
    }

    public LibVlcVodBackend(Context context, VLCVideoLayout videoLayout,
                            java.util.concurrent.ExecutorService worker) {
        engine = new LibVlcPlaybackEngine(context, videoLayout);
        this.worker = worker;
    }

    @Override public void setListener(Listener value) {
        listener = value;
        engine.setListener(new LibVlcPlaybackEngine.Listener() {
            @Override public void onPlaying() {
                Listener current = listener;
                if (!revoked && !released && current != null) mainHandler.post(() -> {
                    if (!revoked && !released && listener == current) {
                        current.onReady();
                        if (audioTrackChecks == 0) scheduleAudioTrackInspection();
                    }
                });
            }
            @Override public void onVideoOutput(int count) {
                Listener current = listener;
                if (!revoked && !released && current != null) mainHandler.post(() -> { if (!revoked && !released && listener == current) current.onVideoOutput(count > 0); });
            }
            @Override public void onAudioTrackState(int count, int selected) {
                Listener current = listener;
                if (!revoked && !released && current != null) mainHandler.post(() -> {
                    if (revoked || released || listener != current) return;
                    current.onAudioTrackState(count, selected);
                    if (count <= 0 || selected < 0) scheduleAudioTrackInspection();
                });
            }
            @Override public void onBuffering(float percent) { }
            @Override public void onFailure(String reason) {
                Listener current = listener;
                if (!revoked && !released && current != null) mainHandler.post(() -> { if (!revoked && !released && listener == current) current.onFailure(reason); });
            }
            @Override public void onStopped() { }
            @Override public void onEndReached() {
                Listener current = listener;
                if (!revoked && !released && current != null) mainHandler.post(() -> { if (!revoked && !released && listener == current) current.onEnded(); });
            }
        });
    }

    // All JNI calls use the same serial worker as live VLC. The main thread
    // only consumes the latest timeline snapshot and enqueues user commands.
    private void submit(Runnable action) {
        if (revoked) return;
        worker.execute(() -> {
            if (revoked) return;
            try { action.run(); }
            catch (RuntimeException | LinkageError error) {
                Listener current = listener;
                if (current != null) mainHandler.post(() -> {
                    if (!revoked && listener == current)
                        current.onFailure(error.getClass().getSimpleName());
                });
            }
        });
    }

    @Override public void play(String url) {
        submit(() -> {
            if (released) {
                engine.initialize();
                if (revoked) return;
                released = false;
            }
            if (revoked) return;
            engine.play(url);
            schedulePoll(0L);
        });
    }

    private void pollTimeline() {
        if (revoked || released) return;
        try {
            submit(() -> {
                playing = engine.isPlaying();
                positionMs = engine.getTimeMs();
                durationMs = engine.getLengthMs();
                // Schedule only after the previous poll completes; a slow
                // decoder cannot accumulate periodic JNI work in the queue.
                schedulePoll(500L);
            });
        } catch (RuntimeException ignored) { }
    }

    @Override public void pause() {
        submit(() -> { if (!released) engine.pause(); });
    }

    @Override public void resume() {
        submit(() -> { if (!released) engine.resume(); });
    }

    @Override public boolean isPlaying() {
        return !revoked && !released && playing;
    }

    @Override public long getPositionMs() { return positionMs; }
    @Override public long getDurationMs() { return durationMs; }

    @Override public boolean seekToMs(long target) {
        if (revoked || released || durationMs <= 0L) return false;
        submit(() -> { if (!released) engine.seekToMs(target); });
        return true; // request accepted, not a synchronous native seek result
    }

    public void setVideoScaleMode(int mode) {
        submit(() -> { if (!released) engine.setVideoScaleMode(mode); });
    }

    /** Main-thread revocation precedes posting teardown to the shared worker. */
    synchronized void revoke() {
        revoked = true;
        released = true;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        engine.setListener(null);
    }

    /** Called only by the shared lifecycle worker, never by the UI. */
    boolean releaseAndConfirm() {
        revoke();
        return engine.release();
    }

    @Override public void release() {
        revoke();
        worker.execute(this::releaseAndConfirm);
    }
}
