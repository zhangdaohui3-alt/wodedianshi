package com.wodedianshi.tv;

/**
 * Overflow-safe conversion between millisecond playback timelines and the
 * 0..1000 progress scale used by the Android TV seek bar.
 */
public final class VodTimelinePolicy {
    private static final int PROGRESS_MAX = 1000;

    private VodTimelinePolicy() {}

    public static long clampPosition(long positionMs, long durationMs) {
        if (durationMs <= 0L) return 0L;
        return Math.max(0L, Math.min(durationMs, positionMs));
    }

    public static long addAndClamp(long positionMs, long deltaMs, long durationMs) {
        if (durationMs <= 0L) return 0L;
        long base = clampPosition(positionMs, durationMs);
        long target;
        if (deltaMs > 0L && base > Long.MAX_VALUE - deltaMs) {
            target = Long.MAX_VALUE;
        } else if (deltaMs < 0L && base < Long.MIN_VALUE - deltaMs) {
            target = Long.MIN_VALUE;
        } else {
            target = base + deltaMs;
        }
        return clampPosition(target, durationMs);
    }

    public static int toProgress(long positionMs, long durationMs) {
        if (durationMs <= 0L) return 0;
        long position = clampPosition(positionMs, durationMs);
        // Split quotient/remainder to avoid positionMs * 1000 overflow.
        long scaled = (position / PROGRESS_MAX) * PROGRESS_MAX
                + ((position % PROGRESS_MAX) * PROGRESS_MAX) / durationMs;
        // The expression above is intentionally corrected below using safe
        // double scaling for the final ratio, whose operands are bounded.
        return (int) Math.max(0L, Math.min(PROGRESS_MAX,
                Math.round(((double) position / (double) durationMs) * PROGRESS_MAX)));
    }

    public static long fromProgress(int progress, long durationMs) {
        if (durationMs <= 0L) return 0L;
        int bounded = Math.max(0, Math.min(PROGRESS_MAX, progress));
        // Decompose duration to avoid durationMs * progress overflow.
        return (durationMs / PROGRESS_MAX) * bounded
                + ((durationMs % PROGRESS_MAX) * bounded) / PROGRESS_MAX;
    }
}
