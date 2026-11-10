package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ParentRemoteLockPolicyTest {
    @Test public void daytimeAllowedWindowUsesInclusiveStartExclusiveEnd() {
        assertFalse(ParentRemoteLockPolicy.isInsideWindow(479, 480, 1290));
        assertTrue(ParentRemoteLockPolicy.isInsideWindow(480, 480, 1290));
        assertTrue(ParentRemoteLockPolicy.isInsideWindow(1289, 480, 1290));
        assertFalse(ParentRemoteLockPolicy.isInsideWindow(1290, 480, 1290));
    }

    @Test public void overnightAllowedWindowCrossesMidnight() {
        assertTrue(ParentRemoteLockPolicy.isInsideWindow(23 * 60, 22 * 60, 6 * 60));
        assertTrue(ParentRemoteLockPolicy.isInsideWindow(5 * 60 + 59, 22 * 60, 6 * 60));
        assertFalse(ParentRemoteLockPolicy.isInsideWindow(6 * 60, 22 * 60, 6 * 60));
        assertFalse(ParentRemoteLockPolicy.isInsideWindow(12 * 60, 22 * 60, 6 * 60));
    }

    @Test public void equalEndpointsMeanAllDay() {
        assertTrue(ParentRemoteLockPolicy.isInsideWindow(0, 0, 0));
        assertTrue(ParentRemoteLockPolicy.isInsideWindow(1439, 0, 0));
    }

    @Test public void offlinePolicyFailsClosedWithoutCache() {
        assertFalse(ParentRemoteLockPolicy.shouldLockOffline(true, true, false));
        assertTrue(ParentRemoteLockPolicy.shouldLockOffline(true, true, true));
        assertFalse(ParentRemoteLockPolicy.shouldLockOffline(false, true, true));
        assertTrue(ParentRemoteLockPolicy.shouldLockOffline(false, false, true));
    }

    @Test public void clockJumpRequiresToleranceExceeded() {
        assertFalse(ParentRemoteLockPolicy.clockJumped(10000, 10000, 120000));
        assertTrue(ParentRemoteLockPolicy.clockJumped(200000, 10000, 120000));
    }

    @Test public void usageDoesNotBecomeNegativeOrOverflow() {
        assertEquals(10L, ParentRemoteLockPolicy.safeUsage(-4L, 10L));
        assertEquals(20L, ParentRemoteLockPolicy.safeUsage(20L, -5L));
        assertEquals(Long.MAX_VALUE, ParentRemoteLockPolicy.safeUsage(Long.MAX_VALUE - 1, 10));
    }
}
