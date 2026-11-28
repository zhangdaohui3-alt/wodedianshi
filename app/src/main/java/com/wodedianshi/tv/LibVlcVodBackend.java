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
    private enum State { NEW, ACTIVE, REVOKED }
    private volatile State state = State.NEW;
    private volatile boolean playing;
    private volatile long positionMs, durationMs;
    private final java.util.concurrent.ExecutorService worker;
    private final Runnable poll = this::pollTimeline;
    // Serialize posting the next tick with revocation, so a native poll that
    // finishes after dismissal cannot reinsert a removed callback.
    private synchronized void schedulePoll(long delayMs) {
        if (state != State.ACTIVE) return;
        mainHandler.removeCallbacks(poll);
        mainHandler.postDelayed(poll, delayMs);
    }
    // Main-handler-owned; at most two inspections per backend lifetime.
    private int audioTrackChecks;

    private synchronized void scheduleAudioTrackInspection() {
        if (state != State.ACTIVE || audioTrackChecks >= 2) return;
        ++audioTrackChecks;
        mainHandler.postDelayed(() -> {
            if (state != State.ACTIVE) return;
            try { submit(engine::inspectAudioTrack); }
            catch (RuntimeException error) {
                Listener current = listener;
                if (state != State.REVOKED && current != null) current.onAudioTrackState(-1, -1);
            }
        }, 3000L);
    }

    public LibVlcVodBackend(Context context, VLCVideoLayout videoLayout,
                            java.util.concurrent.ExecutorService worker) {
        engine = new LibVlcPlaybackEngine(context, videoLayout);
        this.worker = worker;
    }

    @Override public synchronized void setListener(Listener value) {
        if (state == State.REVOKED) return;
        listener = value;
        engine.setListener(new LibVlcPlaybackEngine.Listener() {
            @Override public void onPlaying() {
                postCallback(true,current -> {
                    current.onReady();
                    if (audioTrackChecks == 0) scheduleAudioTrackInspection();
                });
            }
            @Override public void onVideoOutput(int count) {
                postCallback(true,current -> current.onVideoOutput(count > 0));
            }
            @Override public void onAudioTrackState(int count, int selected) {
                postCallback(true,current -> {
                    current.onAudioTrackState(count, selected);
                    if (count <= 0 || selected < 0) scheduleAudioTrackInspection();
                });
            }
            @Override public void onBuffering(float percent) { }
            @Override public void onFailure(String reason) {
                postCallback(true,current -> current.onFailure(reason));
            }
            @Override public void onStopped() { }
            @Override public void onEndReached() {
                postCallback(true,Listener::onEnded);
            }
        });
    }

    // Posting shares the revocation monitor: a retired backend cannot enqueue
    // a new callback after revoke() has removed pending messages.
    private synchronized void postCallback(boolean requireActive,
            java.util.function.Consumer<Listener> action) {
        Listener current = listener;
        if (current == null || state == State.REVOKED
                || (requireActive && state != State.ACTIVE)) return;
        mainHandler.post(() -> {
            if (state != State.REVOKED && (!requireActive || state == State.ACTIVE)
                    && listener == current) action.accept(current);
        });
    }

    // All JNI calls use the same serial worker as live VLC. The main thread
    // only consumes the latest timeline snapshot and enqueues user commands.
    private void submit(Runnable action) {
        if (state == State.REVOKED) return;
        worker.execute(() -> {
            if (state == State.REVOKED) return;
            try { action.run(); }
            catch (RuntimeException | LinkageError error) {
                postCallback(false,current -> current.onFailure(error.getClass().getSimpleName()));
            }
        });
    }

    @Override public void play(String url) {
        submit(() -> {
            if (state == State.NEW) {
                engine.initialize();
                synchronized (this) {
                    if (state == State.REVOKED) return;
                    state = State.ACTIVE;
                }
            }
            if (state == State.REVOKED) return;
            engine.play(url);
            schedulePoll(0L);
        });
    }

    private void pollTimeline() {
        if (state != State.ACTIVE) return;
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
        submit(() -> { if (state == State.ACTIVE) engine.pause(); });
    }

    @Override public void resume() {
        submit(() -> { if (state == State.ACTIVE) engine.resume(); });
    }

    @Override public boolean isPlaying() {
        return state == State.ACTIVE && playing;
    }

    @Override public long getPositionMs() { return positionMs; }
    @Override public long getDurationMs() { return durationMs; }

    @Override public boolean seekToMs(long target) {
        if (state != State.ACTIVE || durationMs <= 0L) return false;
        submit(() -> { if (state == State.ACTIVE) engine.seekToMs(target); });
        return true; // request accepted, not a synchronous native seek result
    }

    public void setVideoScaleMode(int mode) {
        submit(() -> { if (state == State.ACTIVE) engine.setVideoScaleMode(mode); });
    }

    /** Main-thread revocation precedes posting teardown to the shared worker. */
    synchronized void revoke() {
        state = State.REVOKED;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        engine.setListener(null);
    }

    /** Called only by the shared lifecycle worker, never by the UI. */
    boolean releaseAndConfirm() {
        revoke();
        return engine.release();
    }
}
