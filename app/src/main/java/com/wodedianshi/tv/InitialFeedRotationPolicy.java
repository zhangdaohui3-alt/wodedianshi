package com.wodedianshi.tv;

/** Keeps one temporarily unavailable feed from blocking every later discovery feed. */
public final class InitialFeedRotationPolicy {
    private InitialFeedRotationPolicy() {}

    public static int normalizeStart(int requested, int feedCount) {
        if (feedCount <= 0) return 0;
        int value = requested % feedCount;
        return value < 0 ? value + feedCount : value;
    }

    /**
     * A full page stays on the same feed for its next page. A short/empty page
     * means EOF, while a transient failure rotates to another feed and is retried
     * after the remaining feeds have had a turn.
     */
    public static int nextAfterAttempt(int current, int feedCount,
            boolean readSucceeded, boolean pageFull) {
        if (feedCount <= 0) return 0;
        int index = normalizeStart(current, feedCount);
        return readSucceeded && pageFull ? index : (index + 1) % feedCount;
    }
}
