package com.wodedianshi.tv;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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
        default void onAudioTrackState(int trackCount, int selectedTrackId) { }
        void onStopped();
        default void onEndReached() { }
    }

    private final Context appContext;
    private final VLCVideoLayout videoLayout;
    private volatile LibVLC libVlc;
    private volatile MediaPlayer mediaPlayer;
    // Native VLC events arrive off the UI thread; lifecycle writes must be visible to callbacks.
    private volatile Listener listener;
    private volatile boolean released = true;
    private final NativeReleaseConfirmation releaseConfirmation = new NativeReleaseConfirmation();
    private volatile boolean viewOperationUnconfirmed;

    public LibVlcPlaybackEngine(Context context, VLCVideoLayout layout) {
        appContext = context.getApplicationContext();
        videoLayout = layout;
    }

    private void runViewOperationOnMain(Runnable operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            operation.run();
            return;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 0=pending, 1=running, 2=finished, 3=cancelled before start.
        // If JNI has entered the UI operation, a Java timeout cannot stop it.
        final AtomicInteger phase = new AtomicInteger();
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable dispatched = () -> {
            try {
                if (!phase.compareAndSet(0, 1)) return;
                try { operation.run(); }
                catch (RuntimeException | LinkageError error) { failure.set(error); }
                finally { phase.set(2); }
            } finally {
                completed.countDown();
            }
        };
        final long started = android.os.SystemClock.elapsedRealtime();
        if (!handler.post(dispatched)) {
            throw new IllegalStateException("Could not schedule VLC video surface on UI thread");
        }
        try {
            if (!completed.await(8, TimeUnit.SECONDS)) {
                if (!phase.compareAndSet(0, 3) && phase.get() != 2)
                    viewOperationUnconfirmed = true;
                handler.removeCallbacks(dispatched);
                throw new IllegalStateException("Timed out waiting for VLC video surface on UI thread");
            }
        } catch (InterruptedException interrupted) {
            if (!phase.compareAndSet(0, 3) && phase.get() != 2)
                viewOperationUnconfirmed = true;
            handler.removeCallbacks(dispatched);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while attaching VLC video surface", interrupted);
        }
        long waited = android.os.SystemClock.elapsedRealtime() - started;
        if (waited >= 150L) {
            android.util.Log.w("MyTVVLC", "VLC_VIEW_DISPATCH_SLOW waitMs=" + waited);
        }
        Throwable error = failure.get();
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof LinkageError) throw (LinkageError) error;
    }

    public boolean isInitialized() {
        return !released && libVlc != null && mediaPlayer != null;
    }

    public void setListener(Listener value) {
        listener = value;
    }

    public void initialize() {
        recordNativeStage("VLC_INIT_BEGIN");
        // Each adapter owns one native lifetime. A retired or uncertain
        // adapter must never allocate another decoder.
        if (!releaseConfirmation.mayInitialize()) {
            throw new IllegalStateException("LibVLC engine has been retired");
        }
        if (isInitialized()) return;
        if (mediaPlayer != null || libVlc != null) {
            throw new IllegalStateException("LibVLC initialization already in progress");
        }
        released = true;
        try {
            // release() hides the layout while tearing down any previous native session.
            // Make it visible before attaching VLC's native video surfaces.
            recordNativeStage("VLC_LAYOUT_VISIBLE_BEGIN");
            runViewOperationOnMain(() -> videoLayout.setVisibility(View.VISIBLE));
            ArrayList<String> options = new ArrayList<>();
            options.add("--no-video-title-show");
            options.add("--network-caching=1500");
            options.add("--live-caching=1500");
            options.add("--avcodec-hw=any");
            recordNativeStage("VLC_LIBVLC_CONSTRUCTOR_BEGIN");
            libVlc = new LibVLC(appContext, options);
            recordNativeStage("VLC_LIBVLC_CONSTRUCTOR_RETURNED");
            recordNativeStage("VLC_MEDIAPLAYER_CONSTRUCTOR_BEGIN");
            mediaPlayer = new MediaPlayer(libVlc);
            recordNativeStage("VLC_MEDIAPLAYER_CONSTRUCTOR_RETURNED");
            mediaPlayer.setEventListener(event -> {
                // Snapshot the volatile callback once. Reading the field again in
                // each branch could race release() clearing it and cause a native
                // event thread to throw NullPointerException during teardown.
                Listener callback = listener;
                if (released || callback == null) return;
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        callback.onPlaying();
                        break;
                    case MediaPlayer.Event.Vout:
                        callback.onVideoOutput(event.getVoutCount());
                        break;
                    case MediaPlayer.Event.Buffering:
                        callback.onBuffering(event.getBuffering());
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        callback.onFailure("LibVLC 遇到播放或解码错误");
                        break;
                    case MediaPlayer.Event.Stopped:
                        callback.onStopped();
                        break;
                    case MediaPlayer.Event.EndReached:
                        callback.onEndReached();
                        break;
                    default:
                        break;
                }
            });
            recordNativeStage("VLC_ATTACH_VIEWS_BEGIN");
            runViewOperationOnMain(() -> mediaPlayer.attachViews(videoLayout, null, false, false));
            recordNativeStage("VLC_ATTACH_VIEWS_RETURNED");
            released = false;
            recordNativeStage("VLC_INIT_COMPLETED");
        } catch (RuntimeException | LinkageError error) {
            recordNativeStage("VLC_INIT_FAILED_" + error.getClass().getSimpleName());
            // Constructors and attachViews can fail independently. Tear down whichever
            // native objects were successfully created before reporting the failure.
            if (!release()) recordNativeStage("VLC_INIT_CLEANUP_UNCONFIRMED");
            throw error;
        }
    }

    public void play(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("rtsp://"))) {
            if (listener != null) listener.onFailure("不支持的播放地址");
            return;
        }
        if (!isInitialized()) initialize();
        recordNativeStage("VLC_MEDIA_CONSTRUCTOR_BEGIN");
        Media media = new Media(libVlc, Uri.parse(url));
        recordNativeStage("VLC_MEDIA_CONSTRUCTOR_RETURNED");
        try {
            recordNativeStage("VLC_MEDIA_CONFIGURE_BEGIN");
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=1500");
            media.addOption(":live-caching=1500");
            recordNativeStage("VLC_SET_MEDIA_BEGIN");
            mediaPlayer.setMedia(media);
            recordNativeStage("VLC_SET_MEDIA_RETURNED");
        } catch (RuntimeException | LinkageError error) {
            recordNativeStage("VLC_MEDIA_SETUP_FAILED_" + error.getClass().getSimpleName());
            throw error;
        } finally {
            // Media is a native-backed object. Always release our local reference,
            // even when configuration or setMedia fails before play() is reached.
            recordNativeStage("VLC_MEDIA_RELEASE_BEGIN");
            try {
                media.release();
                recordNativeStage("VLC_MEDIA_RELEASE_RETURNED");
            } catch (RuntimeException | LinkageError error) {
                recordNativeStage("VLC_MEDIA_RELEASE_FAILED_" + error.getClass().getSimpleName());
                throw error;
            }
        }
        recordNativeStage("VLC_NATIVE_PLAY_BEGIN");
        try {
            mediaPlayer.play();
            recordNativeStage("VLC_NATIVE_PLAY_RETURNED");
        } catch (RuntimeException | LinkageError error) {
            recordNativeStage("VLC_NATIVE_PLAY_FAILED_" + error.getClass().getSimpleName());
            throw error;
        }
    }

    public void pause() {
        if (isInitialized()) mediaPlayer.pause();
    }

    public void resume() {
        if (isInitialized()) mediaPlayer.play();
    }

    /**
     * Inspect and, when necessary, select the first available audio track.
     * Call from the serialized LibVLC worker, never from the Android UI thread.
     */
    public void inspectAudioTrack() {
        MediaPlayer current = mediaPlayer;
        Listener callback = listener;
        if (released || current == null || callback == null) return;
        int trackCount = -1;
        int selectedTrackId = -1;
        try {
            trackCount = current.getAudioTracksCount();
            selectedTrackId = current.getAudioTrack();
            if (trackCount > 0 && selectedTrackId < 0) {
                MediaPlayer.TrackDescription[] tracks = current.getAudioTracks();
                if (tracks != null) {
                    for (MediaPlayer.TrackDescription track : tracks) {
                        if (track != null && track.id != -1) {
                            current.setAudioTrack(track.id);
                            break;
                        }
                    }
                }
                selectedTrackId = current.getAudioTrack();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Report unknown as unavailable; the caller will fail over safely.
        }
        callback = listener;
        if (!released && callback != null) callback.onAudioTrackState(trackCount, selectedTrackId);
    }

    /** Read-only health sample on the existing serialized native worker.
     * Track selection and timeline movement are not proof of audible PCM or
     * rendered pixels. Unknown native values must not authorize discovery. */
    boolean hasSelectedAudioTrack() {
        MediaPlayer current=mediaPlayer;
        if(released||current==null)return false;
        try {
            return current.getAudioTracksCount()>0&&current.getAudioTrack()>=0;
        } catch(RuntimeException|LinkageError failure) {
            return false;
        }
    }

    /** Snapshot only on the existing serialized native worker. The retained
     * Media returned by getMedia must be released on every path. */
    long[] outputCounters() {
        MediaPlayer current=mediaPlayer;
        if(released||current==null)return null;
        org.videolan.libvlc.interfaces.IMedia media=current.getMedia();
        if(media==null)return null;
        try {
            org.videolan.libvlc.interfaces.IMedia.Stats stats=media.getStats();
            if(stats==null)return null;
            return new long[]{stats.displayedPictures,stats.playedAbuffers};
        } finally {
            media.release();
        }
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
        runViewOperationOnMain(() -> videoLayout.setVisibility(View.GONE));
    }

    /** Reattach only after the VLC layout is attached to the visible window. */
    public void attachViews() {
        if (!isInitialized()) return;
        runViewOperationOnMain(() -> {
            videoLayout.setVisibility(View.VISIBLE);
            if (!mediaPlayer.getVLCVout().areViewsAttached()) {
                mediaPlayer.attachViews(videoLayout, null, false, false);
            }
        });
    }

    public boolean release() {
        // Only the first caller owns teardown. In-flight and failed releases
        // are never reported as success by a second, empty release.
        if (!releaseConfirmation.beginRelease()) return releaseConfirmation.isConfirmed();
        // Mark the engine dead and detach the app callback before touching JNI.
        boolean nativeReleaseSucceeded = true;
        // A callback already in flight may finish, but MainActivity also checks
        // the engine instance and playback generation before acting on it.
        released = true;
        listener = null;
        recordNativeStage("VLC_RELEASE_BEGIN");
        if (viewOperationUnconfirmed) {
            // A UI/JNI operation may still own the native player. Do not
            // touch it concurrently. Keep the process release barrier closed.
            releaseConfirmation.finishRelease(false);
            recordNativeStage("VLC_VIEW_OPERATION_UNCONFIRMED");
            return false;
        }

        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) {
            // Never let a failed stop prevent detach/release. Vendor VLC builds can
            // throw from one teardown call while still requiring the remaining cleanup.
            try {
                recordNativeStage("VLC_STOP_BEGIN");
                player.stop();
            } catch (RuntimeException | LinkageError error) {
                recordNativeStage("VLC_STOP_FAILED_" + error.getClass().getSimpleName());
            }
            try {
                recordNativeStage("VLC_DETACH_CHECK_BEGIN");
                runViewOperationOnMain(() -> {
                    if (player.getVLCVout().areViewsAttached()) {
                        recordNativeStage("VLC_DETACH_VIEWS_BEGIN");
                        player.detachViews();
                    }
                });
            } catch (RuntimeException | LinkageError error) {
                nativeReleaseSucceeded = false;
                recordNativeStage("VLC_DETACH_FAILED_" + error.getClass().getSimpleName());
            }
            if (viewOperationUnconfirmed) {
                releaseConfirmation.finishRelease(false);
                recordNativeStage("VLC_DETACH_OPERATION_UNCONFIRMED");
                return false;
            }
            try {
                player.setEventListener(null);
            } catch (RuntimeException | LinkageError error) {
                recordNativeStage("VLC_CLEAR_LISTENER_FAILED_" + error.getClass().getSimpleName());
            }
            try {
                recordNativeStage("VLC_MEDIAPLAYER_RELEASE_BEGIN");
                player.release();
            } catch (RuntimeException | LinkageError error) {
                nativeReleaseSucceeded = false;
                recordNativeStage("VLC_MEDIAPLAYER_RELEASE_FAILED_" + error.getClass().getSimpleName());
            }
        }

        LibVLC instance = libVlc;
        libVlc = null;
        if (instance != null) {
            try {
                recordNativeStage("VLC_LIBVLC_RELEASE_BEGIN");
                instance.release();
            } catch (RuntimeException | LinkageError error) {
                nativeReleaseSucceeded = false;
                recordNativeStage("VLC_LIBVLC_RELEASE_FAILED_" + error.getClass().getSimpleName());
            }
        }
        try {
            if (videoLayout != null) runViewOperationOnMain(() -> videoLayout.setVisibility(View.GONE));
        } catch (RuntimeException ignored) { }
        releaseConfirmation.finishRelease(nativeReleaseSucceeded);
        recordNativeStage(nativeReleaseSucceeded ? "VLC_RELEASE_COMPLETED" : "VLC_RELEASE_INCOMPLETE");
        return releaseConfirmation.isConfirmed();
    }

    // Persist immediately before JNI/native calls so SIGSEGV or abort still leaves
    // the last attempted VLC operation available after the app is relaunched.
    private void recordNativeStage(String stage) {
        try {
            android.content.SharedPreferences.Editor editor =
                    appContext.getSharedPreferences("mytv", Context.MODE_PRIVATE).edit()
                    .putString("last_native_engine_stage_v1",
                            System.currentTimeMillis() + " | " + stage)
                    .putLong("last_native_engine_stage_time_v1", System.currentTimeMillis());
            // Persist immediately before crash-prone JNI boundaries. Completion
            // markers can be applied asynchronously to avoid repeated fsyncs on
            // the UI thread during normal playback and teardown.
            boolean crashCriticalBoundary = "VLC_LIBVLC_CONSTRUCTOR_BEGIN".equals(stage)
                    || "VLC_MEDIAPLAYER_CONSTRUCTOR_BEGIN".equals(stage)
                    || "VLC_ATTACH_VIEWS_BEGIN".equals(stage)
                    || "VLC_NATIVE_PLAY_BEGIN".equals(stage)
                    || "VLC_MEDIAPLAYER_RELEASE_BEGIN".equals(stage)
                    || "VLC_LIBVLC_RELEASE_BEGIN".equals(stage);
            // Only fsync at native boundaries most likely to terminate the process.
            // UI/layout/stop/diagnostic markers use apply() to avoid repeated main-thread disk stalls.
            // Native-stage breadcrumbs are diagnostic only. commit() forced a
            // synchronous disk flush on the playback/UI thread immediately before
            // JNI calls; on slow TV storage that can trigger input-dispatch ANRs.
            // apply() publishes the latest value in memory now and persists it off-thread.
            editor.apply();
        } catch (Throwable ignored) { }
    }
}
