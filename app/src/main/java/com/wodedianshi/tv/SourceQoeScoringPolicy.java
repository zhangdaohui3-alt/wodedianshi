package com.wodedianshi.tv;

public final class SourceQoeScoringPolicy {
    private SourceQoeScoringPolicy() {}

    /** Returns the shared foreground/background QoE adjustment. */
    public static double adjustment(int attempts, int ready, int firstFrames,
                                    int stablePlays, int failures, int buffers,
                                    long averageReadyMs, long averageFirstFrameMs,
                                    int codecWarnings) {
        if (attempts <= 0) return 0.0d;
        double readyRate = rate(ready, attempts);
        double firstFrameRate = rate(firstFrames, attempts);
        double stableRate = rate(stablePlays, attempts);
        double failureRate = rate(failures, attempts);
        double bufferRate = rate(buffers, attempts);
        double codecWarningRate = Math.max(0.0d, codecWarnings) / (double) attempts;
        double readyPenalty = Math.min(120.0d, Math.max(0L, averageReadyMs) / 200.0d);
        double firstFramePenalty = Math.min(180.0d, Math.max(0L, averageFirstFrameMs) / 180.0d);
        double codecPenalty = Math.min(60.0d, codecWarningRate * 8.0d);
        double failurePenalty = Math.min(180.0d, failureRate * 180.0d);
        double bufferPenalty = Math.min(110.0d, bufferRate * 110.0d);
        return readyRate * 450.0d + firstFrameRate * 450.0d + stableRate * 700.0d
                - readyPenalty - firstFramePenalty - codecPenalty
                - failurePenalty - bufferPenalty;
    }

    private static double rate(int count, int attempts) {
        return Math.min(1.0d, Math.max(0, count) / (double) attempts);
    }
}
