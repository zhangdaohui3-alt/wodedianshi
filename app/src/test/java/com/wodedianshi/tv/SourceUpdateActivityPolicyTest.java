package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateActivityPolicyTest {
    @Test public void freshHeartbeatKeepsBackgroundWorkerAway() {
        assertTrue(SourceUpdateActivityPolicy.isLeaseFresh(true, 100_000L, 160_000L));
    }

    @Test public void expiredHeartbeatAllowsBackgroundMaintenance() {
        assertFalse(SourceUpdateActivityPolicy.isLeaseFresh(true, 100_000L, 190_001L));
    }

    @Test public void inactiveActivityDoesNotBlockWorker() {
        assertFalse(SourceUpdateActivityPolicy.isLeaseFresh(false, 100_000L, 110_000L));
    }

    @Test public void futureHeartbeatIsTreatedAsInvalidClockState() {
        assertFalse(SourceUpdateActivityPolicy.isLeaseFresh(true, 120_000L, 110_000L));
    }

    @Test public void exactLeaseBoundaryIsStillFresh() {
        assertTrue(SourceUpdateActivityPolicy.isLeaseFresh(true, 100_000L, 190_000L));
    }
}
