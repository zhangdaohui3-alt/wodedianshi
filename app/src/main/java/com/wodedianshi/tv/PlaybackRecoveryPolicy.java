package com.wodedianshi.tv;

/** Pure timing decisions used by the live playback watchdog. */
public final class PlaybackRecoveryPolicy {
    private PlaybackRecoveryPolicy() {}

    public static boolean isTimelineStalled(long nowMs, long lastAdvanceAtMs, long timeoutMs) {
        return timeoutMs > 0L && lastAdvanceAtMs > 0L
                && nowMs >= lastAdvanceAtMs
                && nowMs - lastAdvanceAtMs >= timeoutMs;
    }

    public static boolean isVideoOutputStalled(boolean videoTrackSelected,
                                                boolean hasReceivedVideoFrame,
                                                long sourceReadyAtMs,
                                                long lastVideoFrameAtMs,
                                                long nowMs,
                                                long timeoutMs) {
        if (!videoTrackSelected || timeoutMs <= 0L || nowMs < 0L) return false;
        long reference = hasReceivedVideoFrame ? lastVideoFrameAtMs : sourceReadyAtMs;
        return reference > 0L && nowMs >= reference && nowMs - reference >= timeoutMs;
    }
    public static boolean isFirstRenderedFrameMissing(boolean videoTrackSelected,
                                                      boolean hasRenderedFirstFrame,
                                                      long readyAtMs, long nowMs,
                                                      long timeoutMs) {
        return videoTrackSelected && !hasRenderedFirstFrame && timeoutMs > 0L
                && readyAtMs > 0L && nowMs >= readyAtMs
                && nowMs - readyAtMs >= timeoutMs;
    }
}
