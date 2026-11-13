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
    private Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean released = true;

    public LibVlcVodBackend(Context context, VLCVideoLayout videoLayout) {
        engine = new LibVlcPlaybackEngine(context, videoLayout);
    }

    @Override public void setListener(Listener value) {
        listener = value;
        engine.setListener(new LibVlcPlaybackEngine.Listener() {
            @Override public void onPlaying() {
                Listener current = listener;
                if (!released && current != null) mainHandler.post(() -> { if (!released && listener == current) current.onReady(); });
            }
            @Override public void onVideoOutput(int count) {
                Listener current = listener;
                if (!released && current != null) mainHandler.post(() -> { if (!released && listener == current) current.onVideoOutput(count > 0); });
            }
            @Override public void onBuffering(float percent) { }
            @Override public void onFailure(String reason) {
                Listener current = listener;
                if (!released && current != null) mainHandler.post(() -> { if (!released && listener == current) current.onFailure(reason); });
            }
            @Override public void onStopped() { }
            @Override public void onEndReached() {
                Listener current = listener;
                if (!released && current != null) mainHandler.post(() -> { if (!released && listener == current) current.onEnded(); });
            }
        });
    }

    @Override public void play(String url) {
        if (released) {
            engine.initialize();
            released = false;
        }
        engine.play(url);
    }

    @Override public void pause() {
        if (!released) engine.pause();
    }

    @Override public void resume() {
        if (!released) engine.resume();
    }

    @Override public boolean isPlaying() {
        return !released && engine.isPlaying();
    }

    @Override public long getPositionMs() {
        return released ? 0L : engine.getTimeMs();
    }

    @Override public long getDurationMs() {
        return released ? 0L : engine.getLengthMs();
    }

    @Override public boolean seekToMs(long positionMs) {
        return !released && engine.seekToMs(positionMs);
    }

    public void setVideoScaleMode(int mode) {
        if (!released) engine.setVideoScaleMode(mode);
    }

    @Override public void release() {
        released = true;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        engine.setListener(null);
        engine.release();
    }
}
