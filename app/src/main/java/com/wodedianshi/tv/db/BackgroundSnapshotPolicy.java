package com.wodedianshi.tv.db;

/** Safety gate for background refreshes that must preserve the existing directory. */
public final class BackgroundSnapshotPolicy {
    private BackgroundSnapshotPolicy() {}

    /**
     * Source refreshes only enrich a complete Room snapshot; they are never allowed
     * to shrink it. A genuinely empty first-run database may accept its first seed.
     */
    public static boolean isComplete(int existingChannels, int incomingChannels) {
        return existingChannels <= 0 || incomingChannels >= existingChannels;
    }
}
