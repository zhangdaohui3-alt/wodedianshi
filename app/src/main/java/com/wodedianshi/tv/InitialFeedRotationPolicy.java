package com.wodedianshi.tv;

/**
 * Fair, resumable first-build discovery across China and global playlist feeds.
 * A huge public playlist cannot monopolize all worker runs: after each fully
 * probed page, move to the next unfinished feed, retaining that feed's cursor.
 */
public final class InitialFeedRotationPolicy {
    private InitialFeedRotationPolicy() {}

    public static int normalizeStart(int requested, int feedCount) {
        if (feedCount <= 0) return 0;
        int value = requested % feedCount;
        return value < 0 ? value + feedCount : value;
    }

    /** One completely probed page is a fair turn even when the feed has more pages.
     * The caller skips already-completed feeds and uses their persisted cursors.
     * A failed read also yields so one inaccessible provider cannot block the others.
     */
    public static int nextAfterAttempt(int current, int feedCount,
            boolean readSucceeded, boolean pageFull) {
        if (feedCount <= 0) return 0;
        return (normalizeStart(current, feedCount) + 1) % feedCount;
    }

    /** Do not rotate midway through a partly probed page: the next WorkManager
     * attempt must find its exact fingerprint/cursor to finish the same batch.
     */
    public static int nextAfterProbeBatch(int attemptedFeed, int candidateNext,
            int feedCount, boolean fullyProbed) {
        return normalizeStart(fullyProbed ? candidateNext : attemptedFeed, feedCount);
    }
}
