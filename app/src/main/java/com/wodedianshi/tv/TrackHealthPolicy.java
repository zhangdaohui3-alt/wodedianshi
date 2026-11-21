package com.wodedianshi.tv;

/** Rejects live sources that cannot provide both expected audio and video tracks. */
public final class TrackHealthPolicy {
    private TrackHealthPolicy() {}

    /** A television source without either usable audio or usable video is not healthy. */
    public static boolean shouldFailoverForTrackHealth(boolean missingAudio, boolean missingVideo) {
        return missingVideo;
    }
}
