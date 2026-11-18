package com.wodedianshi.tv.db;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundSnapshotPolicyTest {
    @Test public void firstRunMaySeedEmptyDatabase() {
        assertTrue(BackgroundSnapshotPolicy.isComplete(0, 3800));
    }

    @Test public void backgroundRefreshMayKeepOrEnrichDirectory() {
        assertTrue(BackgroundSnapshotPolicy.isComplete(3800, 3800));
        assertTrue(BackgroundSnapshotPolicy.isComplete(3800, 3810));
    }

    @Test public void backgroundRefreshCannotSilentlyDropSavedChannels() {
        assertFalse(BackgroundSnapshotPolicy.isComplete(3800, 3799));
        assertFalse(BackgroundSnapshotPolicy.isComplete(3800, 120));
    }
}
