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

    @Test public void missingAudioAloneDoesNotDiscardVideo() {
        assertFalse(TrackHealthPolicy.shouldFailoverForTrackHealth(true, false));
    }

    @Test public void healthyTracksDoNotFailover() {
        assertFalse(TrackHealthPolicy.shouldFailoverForTrackHealth(false, false));
    }
}
