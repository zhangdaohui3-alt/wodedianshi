package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrackHealthPolicyTest {
    @Test public void firstFrameWithoutAudioIsNotConfirmedTvOutput() {
        assertFalse(TrackHealthPolicy.hasConfirmedTvOutput(true,true,true,false,true,true,true,true));
        assertFalse(TrackHealthPolicy.hasConfirmedTvOutput(true,true,true,true,true,true,true,false));
        assertFalse(TrackHealthPolicy.hasConfirmedTvOutput(true,true,true,true,false,false,false,false));
        assertTrue(TrackHealthPolicy.hasConfirmedTvOutput(true,true,true,true,true,true,true,true));
    }

    @Test public void retryBudgetRequiresConfirmedRecentOutput() {
        assertTrue(TrackHealthPolicy.canRefreshRecoveryBudget(true,1000L,120000L,120000L,121000L));
        assertFalse(TrackHealthPolicy.canRefreshRecoveryBudget(false,1000L,120000L,120000L,121000L));
        assertFalse(TrackHealthPolicy.canRefreshRecoveryBudget(true,1000L,100000L,120000L,121000L));
        assertFalse(TrackHealthPolicy.canRefreshRecoveryBudget(true,1000L,120000L,100000L,121000L));
    }

    @Test public void missingVideoTriggersFailover() {
        assertTrue(TrackHealthPolicy.shouldFailoverForTrackHealth(false, true));
    }

    @Test public void missingAudioAndVideoTriggersFailover() {
        assertTrue(TrackHealthPolicy.shouldFailoverForTrackHealth(true, true));
    }

    @Test public void missingAudioAloneTriggersFailoverEvenWhenVideoIsPresent() {
        assertTrue(TrackHealthPolicy.shouldFailoverForTrackHealth(true, false));
    }

    @Test public void healthyTracksDoNotFailover() {
        assertFalse(TrackHealthPolicy.shouldFailoverForTrackHealth(false, false));
    }
}
