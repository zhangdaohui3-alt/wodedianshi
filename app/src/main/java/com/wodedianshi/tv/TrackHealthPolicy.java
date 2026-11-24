package com.wodedianshi.tv;

/** Rejects live sources that cannot provide both expected audio and video tracks. */
public final class TrackHealthPolicy {
    private TrackHealthPolicy() {}

    /** TV success requires both output paths, not merely a decoded first frame.
     * Audio advancement is an engine signal, not proof of audible speaker output. */
    public static boolean hasConfirmedTvOutput(boolean audioPresent, boolean audioSupported,
            boolean audioSelected, boolean audioAdvancing, boolean videoPresent,
            boolean videoSupported, boolean videoSelected, boolean firstFrameRendered) {
        return audioPresent && audioSupported && audioSelected && audioAdvancing
                && videoPresent && videoSupported && videoSelected && firstFrameRendered;
    }

    public static boolean canRefreshRecoveryBudget(boolean confirmedOutput,
            long readyAt, long lastAdvanceAt, long lastVideoAt, long now) {
        return confirmedOutput && readyAt > 0L && now - readyAt >= 120000L
                && lastAdvanceAt > 0L && now >= lastAdvanceAt && now - lastAdvanceAt < 15000L
                && lastVideoAt > 0L && now >= lastVideoAt && now - lastVideoAt < 20000L;
    }

    /** A television source without either usable audio or usable video is not healthy. */
    public static boolean shouldFailoverForTrackHealth(boolean missingAudio, boolean missingVideo) {
        return missingAudio || missingVideo;
    }
}
