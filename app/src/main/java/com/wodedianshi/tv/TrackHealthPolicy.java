package com.wodedianshi.tv;

/** Rejects live sources that cannot provide both expected audio and video tracks. */
public final class TrackHealthPolicy {
    private TrackHealthPolicy() {}

    /** Missing video makes a live source unviewable; audio-only warnings are diagnostic. */
    public static boolean shouldFailoverForTrackHealth(boolean missingAudio, boolean missingVideo) {
        return missingVideo;
    }
}
