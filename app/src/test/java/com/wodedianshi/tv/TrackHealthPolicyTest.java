package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrackHealthPolicyTest {
    @Test public void automaticChannelRecoveryStaysInScopeAndStopsAfterThreeSwitches() {
        java.util.List<Integer> scope=java.util.Arrays.asList(3,10,28,47,99);
        assertEquals(10,ChannelFailoverPolicy.nextScopedChannel(scope,0,0));
        assertEquals(47,ChannelFailoverPolicy.nextScopedChannel(scope,2,2));
        assertEquals(-1,ChannelFailoverPolicy.nextScopedChannel(scope,3,3));
        assertEquals(-1,ChannelFailoverPolicy.nextScopedChannel(scope,null,0));
        assertEquals(-1,ChannelFailoverPolicy.nextScopedChannel(null,0,0));
        assertEquals(-1,ChannelFailoverPolicy.nextScopedChannel(java.util.Arrays.asList(3,10),1,1));
    }

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

    @Test public void earlyTrackCheckPreservesBoundedAudioGrace() {
        assertEquals(8000L,TrackHealthPolicy.initialTrackCheckDelayMs(false,true));
        assertEquals(8000L,TrackHealthPolicy.initialTrackCheckDelayMs(true,false));
        assertEquals(12000L,TrackHealthPolicy.initialTrackCheckDelayMs(true,true));
        assertEquals(4000L,TrackHealthPolicy.remainingAudioGraceMs(true,true,true,false));
        assertEquals(0L,TrackHealthPolicy.remainingAudioGraceMs(false,true,true,false));
        assertEquals(0L,TrackHealthPolicy.remainingAudioGraceMs(true,false,true,false));
        assertEquals(0L,TrackHealthPolicy.remainingAudioGraceMs(true,true,false,false));
        assertEquals(0L,TrackHealthPolicy.remainingAudioGraceMs(true,true,true,true));
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
