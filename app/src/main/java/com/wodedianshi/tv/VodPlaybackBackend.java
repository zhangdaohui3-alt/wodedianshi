package com.wodedianshi.tv;

/**
 * Small transport contract shared by independent VOD playback engines.
 * All timeline values are milliseconds; zero duration means not seekable.
 */
public interface VodPlaybackBackend {
    interface Listener {
        void onReady();
        void onVideoOutput(boolean available);
        void onFailure(String reason);
        void onEnded();
    }

    void setListener(Listener listener);
    void play(String url);
    void pause();
    void resume();
    boolean isPlaying();
    long getPositionMs();
    long getDurationMs();
    boolean seekToMs(long positionMs);
    default void setVideoScaleMode(int mode) { }
    void release();
}
