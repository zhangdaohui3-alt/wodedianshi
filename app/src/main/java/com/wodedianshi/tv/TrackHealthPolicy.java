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

    /** Keep continuous READY time independent from the recurring recovery-budget refresh. */
    public static boolean canGrantStableDiscovery(boolean confirmedOutput,
            long readyAt, long lastInteractionAt, long lastAdvanceAt, long lastVideoAt, long now) {
        return confirmedOutput && readyAt > 0L && now - readyAt >= 30000L
                && now - lastInteractionAt >= 20000L
                && lastAdvanceAt > 0L && now >= lastAdvanceAt && now - lastAdvanceAt < 5000L
                && lastVideoAt > 0L && now >= lastVideoAt && now - lastVideoAt < 5000L;
    }

    /** A new or reset counter is a baseline, not evidence for the current tune. */
    static boolean renderedCounterAdvanced(boolean sameCounter,int previous,int current) {
        return sameCounter&&previous>=0&&current>previous;
    }
    static boolean recentRenderedCounter(long lastAdvanceAt,long now) {
        return lastAdvanceAt>0L&&now>=lastAdvanceAt&&now-lastAdvanceAt<15000L;
    }
    static boolean audioWarningQuiet(long warningAt,long now) {
        return warningAt==0L||(warningAt>0L&&now>=warningAt&&now-warningAt>=30000L);
    }

    /** An advancing engine clock strengthens the discovery gate, but is not
     * proof of physical output. Unknown, reset or stationary clocks yield. */
    static boolean vlcClockAdvanced(long previousMs,long currentMs) {
        return previousMs>0L&&currentMs>previousMs&&currentMs-previousMs>=250L;
    }

    /** Permission for bounded discovery only, not proof of rendered pixels or audible sound. */
    public static boolean canGrantVlcDiscovery(boolean videoOutput, boolean audioSelected,
            boolean buffering, boolean reportsPlaying, int consecutiveChecks,
            long lastInteractionAt, long now) {
        return videoOutput && audioSelected && !buffering && reportsPlaying
                && consecutiveChecks >= 2 && now >= lastInteractionAt
                && now - lastInteractionAt >= 20000L;
    }

    public static boolean canRefreshRecoveryBudget(boolean confirmedOutput,
            long readyAt, long lastAdvanceAt, long lastVideoAt, long now) {
        return confirmedOutput && readyAt > 0L && now - readyAt >= 120000L
                && lastAdvanceAt > 0L && now >= lastAdvanceAt && now - lastAdvanceAt < 15000L
                && lastVideoAt > 0L && now >= lastVideoAt && now - lastVideoAt < 20000L;
    }

    public static long initialTrackCheckDelayMs(boolean usableAudioTrack, boolean usableVideoTrack) {
        return usableAudioTrack && usableVideoTrack ? 12000L : 8000L;
    }

    /** If track selection recovered during the early metadata check, keep the
     * original advancement grace. This extra check is allowed only once. */
    public static long remainingAudioGraceMs(boolean earlyCheck, boolean usableAudioTrack,
            boolean usableVideoTrack, boolean audioAdvancing) {
        return earlyCheck && usableAudioTrack && usableVideoTrack && !audioAdvancing
                ? 4000L : 0L;
    }

    /** A television source without either usable audio or usable video is not healthy. */
    public static boolean shouldFailoverForTrackHealth(boolean missingAudio, boolean missingVideo) {
        return missingAudio || missingVideo;
    }
}
