package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ChannelCatalogueRefreshCyclePolicyTest {
    @Test public void onlyCompletesAfterLastBatchAndRequiredFeedIsAvailable() {
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(0, 12, 30, true));
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(24, 6, 30, false));
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(0, 0, 30, true));
        assertTrue(ChannelCatalogueRefreshCyclePolicy.isComplete(24, 6, 30, true));
    }

    @Test public void emptyOrInvalidRangesNeverComplete() {
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(0, 1, 0, true));
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(-1, 1, 10, true));
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(10, 1, 10, true));
        assertFalse(ChannelCatalogueRefreshCyclePolicy.isComplete(0, 11, 10, true));
    }

    @Test public void fingerprintIgnoresOrderAndDuplicates() {
        String a = ChannelCatalogueRefreshCyclePolicy.fingerprint(
                Arrays.asList("https://a/live", "https://b/live", "https://a/live"));
        String b = ChannelCatalogueRefreshCyclePolicy.fingerprint(
                Arrays.asList("https://b/live", "https://a/live"));
        assertEquals(a, b);
    }

    @Test public void fingerprintChangesWhenCandidateSetChanges() {
        String a = ChannelCatalogueRefreshCyclePolicy.fingerprint(
                Arrays.asList("https://a/live", "https://b/live"));
        String b = ChannelCatalogueRefreshCyclePolicy.fingerprint(
                Arrays.asList("https://a/live", "https://c/live"));
        assertNotEquals(a, b);
        assertEquals(ChannelCatalogueRefreshCyclePolicy.fingerprint(Collections.emptyList()),
                ChannelCatalogueRefreshCyclePolicy.fingerprint(null));
    }
}
