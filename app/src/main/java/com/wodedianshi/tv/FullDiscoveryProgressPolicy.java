package com.wodedianshi.tv;

/**
 * Honest first-build progress: each configured feed is one unit of work,
 * with fractional progress from the indexed cursor of the active feed.
 * A percentage of work completed is not a prediction of remaining time,
 * and 100% is reserved for a successfully published complete Room snapshot.
 */
public final class FullDiscoveryProgressPolicy {
    private FullDiscoveryProgressPolicy() {}

    public static int percent(int completedFeeds, int totalFeeds,
            int activeCursor, int activeFeedEntries, boolean published) {
        if (published) return 100;
        if (totalFeeds <= 0) return 0;
        int done = Math.max(0, Math.min(completedFeeds, totalFeeds));
        double current = 0d;
        if (done < totalFeeds && activeFeedEntries > 0) {
            current = Math.min(1d, Math.max(0d,
                    (double) Math.max(0, activeCursor) / activeFeedEntries));
        }
        int measured = (int)Math.floor(100d * (done + current) / totalFeeds);
        return Math.max(0, Math.min(99, measured));
    }
}
