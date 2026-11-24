package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoomCatalogueReloadPolicyTest {
    @Test public void stableRoomSnapshotCanReplaceUi() {
        assertTrue(RoomCatalogueReloadPolicy.mayPublish(true, true, 10L, 10L));
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                true, true, 10L, 10L, 1));
    }

    @Test public void concurrentFavoriteOrSourceChangeCannotPublishOldRoomRead() {
        // A user action is already shown locally but its one-row Room
        // mutation may still be queued when the catalogue reader finishes.
        assertFalse(RoomCatalogueReloadPolicy.mayPublish(true, true, 10L, 11L));
        assertTrue(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                true, true, 10L, 11L, 1));
    }

    @Test public void mutationRetryIsBoundedToOneAttempt() {
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                true, true, 10L, 11L, 0));
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                true, true, 10L, 11L, -1));
    }

    @Test public void concurrentWorkerPublishDuringPagedReadCanRetryOnce() {
        // No Activity mutation need occur: the independent source worker can
        // change Room while the reader is walking a large channel directory.
        assertTrue(RoomCatalogueReloadPolicy.shouldRetryAfterReadConflict(
                true, true, 1));
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterReadConflict(
                true, true, 0));
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterReadConflict(
                true, true, -1));
    }

    @Test public void staleReadNeverRestartsAfterNewRequestOrStop() {
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterReadConflict(
                false, true, 1));
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterReadConflict(
                true, false, 1));
    }

    @Test public void newerReloadOrStoppedActivityPreventsStaleRetry() {
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                false, true, 10L, 11L, 1));
        assertFalse(RoomCatalogueReloadPolicy.shouldRetryAfterUserMutation(
                true, false, 10L, 11L, 1));
        assertFalse(RoomCatalogueReloadPolicy.mayPublish(false, true, 10L, 10L));
        assertFalse(RoomCatalogueReloadPolicy.mayPublish(true, false, 10L, 10L));
    }
}
