package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodTimelinePolicyTest {
    @Test public void progressRoundTripsTypicalTimeline() {
        long duration = 7_200_000L;
        assertEquals(0, VodTimelinePolicy.toProgress(0, duration));
        assertEquals(500, VodTimelinePolicy.toProgress(3_600_000L, duration));
        assertEquals(duration, VodTimelinePolicy.fromProgress(1000, duration));
    }

    @Test public void clampsOutOfRangePositionsAndProgress() {
        assertEquals(0L, VodTimelinePolicy.clampPosition(-10, 100));
        assertEquals(100L, VodTimelinePolicy.clampPosition(150, 100));
        assertEquals(0, VodTimelinePolicy.toProgress(50, 0));
        assertEquals(0L, VodTimelinePolicy.fromProgress(50, 0));
        assertEquals(0L, VodTimelinePolicy.fromProgress(-20, 100));
        assertEquals(100L, VodTimelinePolicy.fromProgress(2000, 100));
    }

    @Test public void seekDeltaCannotOverflowOrEscapeDuration() {
        assertEquals(100L, VodTimelinePolicy.addAndClamp(90, Long.MAX_VALUE, 100));
        assertEquals(0L, VodTimelinePolicy.addAndClamp(10, Long.MIN_VALUE, 100));
        assertEquals(70L, VodTimelinePolicy.addAndClamp(50, 20, 100));
        assertEquals(0L, VodTimelinePolicy.addAndClamp(50, 20, 0));
    }

    @Test public void handlesVeryLargeDurations() {
        long duration = Long.MAX_VALUE;
        assertEquals(1000, VodTimelinePolicy.toProgress(duration, duration));
        assertEquals(0, VodTimelinePolicy.toProgress(0, duration));
        assertTrue(VodTimelinePolicy.fromProgress(999, duration) <= duration);
    }
}
