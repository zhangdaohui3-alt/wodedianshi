package com.wodedianshi.tv;

import android.content.Context;
import android.net.Uri;
import android.view.View;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * Independent LibVLC engine adapter.
 *
 * This owns its native LibVLC instance, MediaPlayer and video surface. It does
 * not wrap Media3 and must be released before another engine takes ownership
 * of the shared playback stage.
 */
public final class LibVlcPlaybackEngine {
    public interface Listener {
        void onPlaying();
        void onVideoOutput(int count);
        void onBuffering(float percent);
        void onFailure(String reason);
        void onStopped();
        default void onEndReached() { }
    }

    private final Context appContext;
    private final VLCVideoLayout videoLayout;
    private LibVLC libVlc;
    private MediaPlayer mediaPlayer;
    // Native VLC events arrive off the UI thread; lifecycle writes must be visible to callbacks.
    private volatile Listener listener;
    private volatile boolean released = true;

    public LibVlcPlaybackEngine(Context context, VLCVideoLayout layout) {
        appContext = context.getApplicationContext();
        videoLayout = layout;
    }

    public boolean isInitialized() {
        return !released && libVlc != null && mediaPlayer != null;
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void initialize() {
        release();
        // release() hides the layout while tearing down any previous native session.
        // Make it visible before attaching VLC's native video surfaces.
        videoLayout.setVisibility(View.VISIBLE);
        ArrayList<String> options = new ArrayList<>();
        options.add("--no-video-title-show");
        options.add("--network-caching=1500");
        options.add("--live-caching=1500");
        options.add("--avcodec-hw=any");
        libVlc = new LibVLC(appContext, options);
        mediaPlayer = new MediaPlayer(libVlc);
        mediaPlayer.setEventListener(event -> {
            if (released || listener == null) return;
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    listener.onPlaying();
                    break;
                case MediaPlayer.Event.Vout:
                    listener.onVideoOutput(event.getVoutCount());
                    break;
                case MediaPlayer.Event.Buffering:
                    listener.onBuffering(event.getBuffering());
                    break;
                case MediaPlayer.Event.EncounteredError:
                    listener.onFailure("LibVLC 遇到播放或解码错误");
                    break;
                case MediaPlayer.Event.Stopped:
                    listener.onStopped();
                    break;
                case MediaPlayer.Event.EndReached:
                    listener.onEndReached();
                    break;
                default:
                    break;
            }
        });
        mediaPlayer.attachViews(videoLayout, null, false, false);
        released = false;
    }

    public void play(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("rtsp://"))) {
            if (listener != null) listener.onFailure("不支持的播放地址");
            return;
        }
        if (!isInitialized()) initialize();
        Media media = new Media(libVlc, Uri.parse(url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=1500");
        media.addOption(":live-caching=1500");
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();
    }

    public void pause() {
        if (isInitialized()) mediaPlayer.pause();
    }

    public void resume() {
        if (isInitialized()) mediaPlayer.play();
    }

    public boolean isPlaying() {
        return isInitialized() && mediaPlayer.isPlaying();
    }

    /**
     * Current playback position in milliseconds. Returns zero when the engine
     * is not ready or the native player has no valid timeline.
     */
    public long getTimeMs() {
        if (!isInitialized()) return 0L;
        try {
            long value = mediaPlayer.getTime();
            return Math.max(0L, value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * Media duration in milliseconds. Live streams and unprepared media may
     * report zero or a negative value; callers must not treat that as seekable.
     */
    public long getLengthMs() {
        if (!isInitialized()) return 0L;
        try {
            long value = mediaPlayer.getLength();
            return Math.max(0L, value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** Seek only when the native engine exposes a positive duration. */
    public boolean seekToMs(long positionMs) {
        if (!isInitialized()) return false;
        long duration = getLengthMs();
        if (duration <= 0L) return false;
        long target = Math.max(0L, Math.min(duration, positionMs));
        try {
            mediaPlayer.setTime(target);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 0=best fit, 1=fit screen/crop, 2=fill. */
    public void setVideoScaleMode(int mode) {
        if (!isInitialized()) return;
        try {
            MediaPlayer.ScaleType type = mode == 1
                    ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    : mode == 2
                    ? MediaPlayer.ScaleType.SURFACE_FILL
                    : MediaPlayer.ScaleType.SURFACE_BEST_FIT;
            mediaPlayer.setVideoScale(type);
        } catch (Exception ignored) { }
    }

    public void stop() {
        if (isInitialized()) mediaPlayer.stop();
    }

    /** Detach native video surfaces before hiding/removing the VLC view. */
    public void detachViews() {
        if (isInitialized() && mediaPlayer.getVLCVout().areViewsAttached()) {
            mediaPlayer.detachViews();
        }
        videoLayout.setVisibility(View.GONE);
    }

    /** Reattach only after the VLC layout is attached to the visible window. */
    public void attachViews() {
        if (!isInitialized()) return;
        videoLayout.setVisibility(View.VISIBLE);
        if (!mediaPlayer.getVLCVout().areViewsAttached()) {
            mediaPlayer.attachViews(videoLayout, null, false, false);
        }
    }

    public void release() {
        released = true;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                if (mediaPlayer.getVLCVout().areViewsAttached()) mediaPlayer.detachViews();
                mediaPlayer.setEventListener(null);
                mediaPlayer.release();
            } catch (Exception ignored) { }
            mediaPlayer = null;
        }
        if (libVlc != null) {
            try { libVlc.release(); } catch (Exception ignored) { }
            libVlc = null;
        }
        if (videoLayout != null) videoLayout.setVisibility(View.GONE);
    }
}
