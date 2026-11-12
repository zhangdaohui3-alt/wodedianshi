package com.wodedianshi.tv;

/** Separates missing video output from audio-only warnings during live playback. */
public final class TrackHealthPolicy {
    private TrackHealthPolicy() {}

    /**
     * A missing video track means the channel cannot be viewed. Missing audio
     * alone is diagnosed but does not force a source switch.
     */
    public static boolean shouldFailoverForTrackHealth(boolean missingAudio, boolean missingVideo) {
        return missingVideo;
    }
}
