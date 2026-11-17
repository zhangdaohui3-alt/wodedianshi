package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackRecoveryPolicyTest {
    @Test public void timelineRequiresFullTimeout() {
        assertFalse(PlaybackRecoveryPolicy.isTimelineStalled(44999L, 0L, 45000L));
        assertFalse(PlaybackRecoveryPolicy.isTimelineStalled(54999L, 10000L, 45000L));
        assertTrue(PlaybackRecoveryPolicy.isTimelineStalled(55000L, 10000L, 45000L));
    }

    @Test public void timelineRejectsInvalidOrBackwardsClockValues() {
        assertFalse(PlaybackRecoveryPolicy.isTimelineStalled(1000L, 0L, 100L));
        assertFalse(PlaybackRecoveryPolicy.isTimelineStalled(999L, 1000L, 100L));
        assertFalse(PlaybackRecoveryPolicy.isTimelineStalled(2000L, 1000L, 0L));
    }

    @Test public void selectedVideoTrackWithoutFirstFrameHasStartupDeadline() {
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false, 10000L, 0L, 29999L, 20000L));
        assertTrue(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false, 10000L, 0L, 30000L, 20000L));
    }

    @Test public void metadataBeforeRenderedFirstFrameDoesNotResetStartupDeadline() {
        // Decoder metadata may arrive while the output surface is still black.
        // Only AnalyticsListener.onRenderedFirstFrame ends the startup deadline.
        assertTrue(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false,
                10000L, 29000L, 30000L, 20000L));
    }

    @Test public void renderedFirstFrameSwitchesToFrameStallDeadline() {
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, true,
                10000L, 25000L, 44999L, 20000L));
        assertTrue(PlaybackRecoveryPolicy.isVideoOutputStalled(true, true,
                10000L, 25000L, 45000L, 20000L));
    }

    @Test public void firstFrameDeadlineStartsWhenVideoTrackBecomesSelected() {
        // The stream may be READY for a while before track selection completes.
        // That earlier wait must not consume the decoder's first-frame budget.
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false, 30000L, 0L, 49000L, 20000L));
        assertTrue(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false, 30000L, 0L, 50000L, 20000L));
    }

    @Test public void renderedFirstFrameThenUsesVideoPipelineActivityTime() {
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, true, 10000L, 25000L, 44999L, 20000L));
        assertTrue(PlaybackRecoveryPolicy.isVideoOutputStalled(true, true, 10000L, 25000L, 45000L, 20000L));
    }

    @Test public void audioOnlyOrUnselectedVideoDoesNotTriggerVideoStall() {
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(false, false, 10000L, 0L, 90000L, 20000L));
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false, 0L, 0L, 90000L, 20000L));
    }

    @Test public void invalidTimesDoNotTriggerVideoStall() {
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, false, 10000L, 0L, 9999L, 20000L));
        assertFalse(PlaybackRecoveryPolicy.isVideoOutputStalled(true, true, 10000L, 0L, 90000L, 20000L));
    }
}
