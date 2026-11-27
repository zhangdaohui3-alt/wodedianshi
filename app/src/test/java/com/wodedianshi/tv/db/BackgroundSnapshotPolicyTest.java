package com.wodedianshi.tv.db;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundSnapshotPolicyTest {
    @Test public void firstRunMaySeedNonEmptyDatabase() {
        assertTrue(BackgroundSnapshotPolicy.isComplete(0, 3800));
    }

    @Test public void completeRefreshMayKeepOrGrowDirectory() {
        assertTrue(BackgroundSnapshotPolicy.isComplete(3800, 3800));
        assertTrue(BackgroundSnapshotPolicy.isComplete(3800, 3810));
    }

    @Test public void completeRefreshMayShrinkAfterRemovingDeadOrDuplicateChannels() {
        assertTrue(BackgroundSnapshotPolicy.isComplete(3800, 3799));
        assertTrue(BackgroundSnapshotPolicy.isComplete(3800, 120));
    }

    @Test public void emptySnapshotIsNeverACompleteCatalogue() {
        assertFalse(BackgroundSnapshotPolicy.isComplete(3800, 0));
        assertFalse(BackgroundSnapshotPolicy.isComplete(0, 0));
    }
}
