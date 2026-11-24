package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FullDiscoveryProgressPolicyTest {
    @Test public void countsCompletedFeedsAndIndexedCursor() {
        assertEquals(0, FullDiscoveryProgressPolicy.percent(0, 20, 0, 500, false));
        assertEquals(12, FullDiscoveryProgressPolicy.percent(2, 20, 250, 500, false));
        assertEquals(25, FullDiscoveryProgressPolicy.percent(5, 20, 0, 0, false));
    }

    @Test public void aUnpublishedSnapshotNeverShowsOneHundredPercent() {
        assertEquals(99, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, false));
        assertEquals(100, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, true));
    }

    @Test public void missingOrInvalidIndexNeverInventsFractionalProgress() {
        assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, 50, -1, false));
        assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, -5, 100, false));
        assertEquals(0, FullDiscoveryProgressPolicy.percent(3, 0, 50, 100, false));
    }

    @Test public void corruptOrOverflowingCursorIsClamped() {
        assertEquals(20, FullDiscoveryProgressPolicy.percent(3, 20, 5000, 100, false));
        assertEquals(99, FullDiscoveryProgressPolicy.percent(Integer.MAX_VALUE, 20,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false));
    }

    @Test public void completedPercentRequiresActualPublication() {
        assertEquals(100, FullDiscoveryProgressPolicy.percent(0, 20, 0, 0, true));
    }
}
