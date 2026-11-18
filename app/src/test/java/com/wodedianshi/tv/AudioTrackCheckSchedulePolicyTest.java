package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioTrackCheckSchedulePolicyTest {
    @Test public void sinkErrorBeforeInitialReadyCheckKeepsOriginalDeadline() {
        assertFalse(AudioTrackCheckSchedulePolicy.shouldScheduleLateSinkRecheck(false));
    }

    @Test public void sinkErrorAfterInitialReadyCheckSchedulesGraceRecheck() {
        assertTrue(AudioTrackCheckSchedulePolicy.shouldScheduleLateSinkRecheck(true));
    }
}
