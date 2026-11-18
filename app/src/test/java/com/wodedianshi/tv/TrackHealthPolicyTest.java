package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrackHealthPolicyTest {
    @Test public void missingVideoTriggersFailover() {
        assertTrue(TrackHealthPolicy.shouldFailoverForTrackHealth(false, true));
    }

    @Test public void missingAudioAndVideoTriggersFailover() {
        assertTrue(TrackHealthPolicy.shouldFailoverForTrackHealth(true, true));
    }

    @Test public void missingAudioTrackTriggersFailoverEvenWhenVideoExists() {
        assertTrue(TrackHealthPolicy.shouldFailoverForTrackHealth(true, false));
    }

    @Test public void healthyTracksDoNotFailover() {
        assertFalse(TrackHealthPolicy.shouldFailoverForTrackHealth(false, false));
    }

    @Test public void missingAudioTrackFailsEvenWithoutSinkError() {
        assertTrue(TrackHealthPolicy.shouldFailoverForAudioOutput(true, true, false));
    }

    @Test public void stalledAudioWithSinkErrorFailsOver() {
        assertTrue(TrackHealthPolicy.shouldFailoverForAudioOutput(false, true, true));
    }

    @Test public void silentProgramWithoutSinkErrorDoesNotFailOver() {
        assertFalse(TrackHealthPolicy.shouldFailoverForAudioOutput(false, true, false));
    }

    @Test public void advancingAudioDoesNotFailOverForHistoricalSinkWarning() {
        assertFalse(TrackHealthPolicy.shouldFailoverForAudioOutput(false, false, true));
    }
}
