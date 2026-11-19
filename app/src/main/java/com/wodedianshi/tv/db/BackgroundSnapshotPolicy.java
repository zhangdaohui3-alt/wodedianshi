package com.wodedianshi.tv.db;

/**
 * Validates the shape of a fully collected catalogue snapshot.
 *
 * The number of rows may legitimately grow or shrink after deduplication,
 * channel validation and removal of dead entries. A positive row count alone
 * is not the completeness check; RoomCatalogueWriter validates every row and
 * commits the exact snapshot atomically.
 */
public final class BackgroundSnapshotPolicy {
    private BackgroundSnapshotPolicy() {}

    public static boolean isComplete(int existingChannels, int incomingChannels) {
        return incomingChannels > 0;
    }
}
