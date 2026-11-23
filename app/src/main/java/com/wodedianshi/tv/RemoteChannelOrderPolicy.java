package com.wodedianshi.tv;

/**
 * Rejects incomplete or outdated fullscreen channel orders.
 * The order contains positional channel indexes, so an equal row count alone
 * never proves it can be reused after a catalogue replacement.
 */
public final class RemoteChannelOrderPolicy {
    private RemoteChannelOrderPolicy() {}

    public static boolean complete(boolean interrupted,
            long expectedMutation, long currentMutation) {
        return !interrupted && expectedMutation == currentMutation;
    }

    public static boolean mayPublish(boolean interrupted,
            long expectedMutation, long currentMutation,
            long expectedFilter, long currentFilter,
            int expectedCount, int currentCount,
            boolean expectedFavoriteMode, boolean currentFavoriteMode,
            int expectedFavoriteHash, int currentFavoriteHash) {
        return complete(interrupted, expectedMutation, currentMutation)
                && expectedFilter == currentFilter
                && expectedCount == currentCount
                && expectedFavoriteMode == currentFavoriteMode
                && expectedFavoriteHash == currentFavoriteHash;
    }
}
