package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateActivityPolicyTest {
    @Test public void recoveryRequiresSeparateHealthSamplesAndExpiredLeaseCannotRevive(){
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
            SourceUpdateActivityPolicy.reportStableLive(1001L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1001L));
            SourceUpdateActivityPolicy.reportStableLive(11000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(11000L));
            long generation=SourceUpdateActivityPolicy.stableRevocationGeneration();
            SourceUpdateActivityPolicy.reportStableLive(27000L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(27000L));
            assertTrue(SourceUpdateActivityPolicy.stableRevocationGeneration()>generation);
            SourceUpdateActivityPolicy.reportStableLive(37000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(37000L));
            SourceUpdateActivityPolicy.notePressure(37001L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(37001L));
            SourceUpdateActivityPolicy.reportStableLive(38000L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(38000L));
        }finally{
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }
    @Test public void media3HeadroomAdjustsRateOnlyWithFreshHealthyLease(){
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(1000L,20000L);
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(1000L));
            SourceUpdateActivityPolicy.reportStableLive(11000L);
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(11000L,12000L);
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(11000L));
            SourceUpdateActivityPolicy.reportStableLive(21000L);
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(21000L,12000L);
            assertEquals(49152L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(21000L));
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(22000L,500L);
            assertEquals(16384L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(22000L));
            SourceUpdateActivityPolicy.notePressure(23000L);
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(23000L));
            SourceUpdateActivityPolicy.reportConfirmedVlcStableLive(35000L,2);
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(35000L));
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(50000L));
        }finally{
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }
    @Test public void frequentHeadroomSamplesDoNotResetSustainedWindow(){
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            SourceUpdateActivityPolicy.reportStableLive(11000L);
            for(long now=11000L;now<=19000L;now+=1000L){
                SourceUpdateActivityPolicy.reportStableLive(now);
                SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(now,12000L);
                assertEquals(now<19000L?32768L:49152L,
                    SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(now));
            }
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(20000L,500L);
            assertEquals(16384L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(20000L));
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(21000L,12000L);
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(28000L,12000L);
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(28000L));
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(29000L,12000L);
            assertEquals(49152L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(29000L));
            // A fresh lease alone is not evidence of uninterrupted ample samples.
            SourceUpdateActivityPolicy.reportStableLive(30000L);
            SourceUpdateActivityPolicy.reportStableLive(40000L);
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(45000L,12000L);
            assertEquals(32768L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(45000L));
        }finally{
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }
    @Test public void lowBufferRateRecoversWithHysteresisAndNoDirectJumpToHigh(){
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            SourceUpdateActivityPolicy.reportStableLive(11000L);
            long now=11000L;
            long[] samples={1000L,999L,1000L,1200L,900L,2999L,3000L,12000L,999L,12000L};
            long[] rates={32768L,16384L,16384L,16384L,16384L,16384L,32768L,32768L,16384L,32768L};
            for(int i=0;i<samples.length;i++){
                SourceUpdateActivityPolicy.reportStableLive(now);
                SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(now,samples[i]);
                assertEquals("buffer sample "+i,rates[i],SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(now));
                now+=1000L;
            }
            SourceUpdateActivityPolicy.reportStableLive(now+7000L);
            SourceUpdateActivityPolicy.reportMedia3DiscoveryBuffer(now+7000L,12000L);
            assertEquals(49152L,SourceUpdateActivityPolicy.viewingSampleBytesPerSecond(now+7000L));
            SourceUpdateActivityPolicy.notePressure(now+8000L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(now+8000L));
        }finally{
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }

    @Test public void headroomPacingBoundsAndFallback(){
        assertEquals(250L,SourceProbeTimePolicy.viewingSampleRemainingDelayMillis(4096,0L,16384L));
        assertEquals(84L,SourceProbeTimePolicy.viewingSampleRemainingDelayMillis(4096,0L,49152L));
        assertEquals(0L,SourceProbeTimePolicy.viewingSampleRemainingDelayMillis(4096,100L,49152L));
        assertEquals(125L,SourceProbeTimePolicy.viewingSampleRemainingDelayMillis(4096,0L,Long.MAX_VALUE));
    }
    @Test public void confirmedVlcPairGrantsBoundedLeaseButNeverRevivesExpiredOwner(){
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportConfirmedVlcStableLive(1000L,1);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
            SourceUpdateActivityPolicy.reportConfirmedVlcStableLive(13000L,2);
            long old=SourceUpdateActivityPolicy.stableRevocationGeneration();
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(27999L,old));
            SourceUpdateActivityPolicy.reportConfirmedVlcStableLive(28000L,2);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(28000L,old));
            long replacement=SourceUpdateActivityPolicy.stableRevocationGeneration();
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(28000L,replacement));
            SourceUpdateActivityPolicy.notePressure(28001L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(28001L,replacement));
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.reportConfirmedVlcStableLive(49000L,2);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(49000L));
        }finally{
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }
    @Test public void handoffClearsViewingOnlyWhenNoOwnerOrContinuationRemains(){
        assertTrue(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(true,false,false,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,true,false,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,true,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,true,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,false,true,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,false,false,false));
    }
    @Test public void expiredViewingLeaseCannotAuthorizeOldWorkerAfterReplacement(){
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            SourceUpdateActivityPolicy.reportStableLive(11000L);
            long old=SourceUpdateActivityPolicy.stableRevocationGeneration();
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(25999L,old));
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(26000L,old));
            assertTrue(SourceUpdateActivityPolicy.stableRevocationGeneration()>old);
            SourceUpdateActivityPolicy.reportStableLive(27000L);
            SourceUpdateActivityPolicy.reportStableLive(37000L);
            long replacement=SourceUpdateActivityPolicy.stableRevocationGeneration();
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(37000L,replacement));
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(37000L,old));
            SourceUpdateActivityPolicy.reportStableLive(47000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(47000L,replacement));
        }finally{
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }
    @Test public void inputInvalidatesOnlyAdmittedIdleWaveGeneration(){
        long admitted=SourceUpdateActivityPolicy.pressureGeneration();
        assertFalse(SourceUpdateActivityPolicy.idleWavePressureChanged(admitted));
        SourceUpdateActivityPolicy.notePressure(100000L);
        assertTrue(SourceUpdateActivityPolicy.idleWavePressureChanged(admitted));
        assertFalse(SourceUpdateActivityPolicy.idleWavePressureChanged(-1L));
        assertFalse(SourceUpdateActivityPolicy.idleWavePressureChanged(SourceUpdateActivityPolicy.pressureGeneration()));
    }
    @Test public void idleConcurrencyRecoversInSteps(){
        try{
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.notePressure(100000L);
            assertEquals(1,SourceUpdateActivityPolicy.idleProbeConcurrency(150000L));
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.notePressure(150000L);
            assertEquals(1,SourceUpdateActivityPolicy.idleProbeConcurrency(154999L));
            assertEquals(2,SourceUpdateActivityPolicy.idleProbeConcurrency(155000L));
            assertEquals(2,SourceUpdateActivityPolicy.idleProbeConcurrency(169999L));
            assertEquals(4,SourceUpdateActivityPolicy.idleProbeConcurrency(170000L));
            assertEquals(6,SourceUpdateActivityPolicy.idleProbeConcurrency(170000L,true));
            assertEquals(2,SourceUpdateActivityPolicy.idleProbeConcurrency(169999L,true));
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            assertEquals(1,SourceUpdateActivityPolicy.idleProbeConcurrency(180000L,true));
        }finally{SourceUpdateActivityPolicy.setPlaybackActive(false);SourceUpdateActivityPolicy.setActivityActive(false);}
    }
    @Test public void viewingFlagsAreIndependentAndCanBeCleared() {
        try {
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            assertTrue(SourceUpdateActivityPolicy.isActivityActive());
            assertFalse(SourceUpdateActivityPolicy.isPlaybackActive());
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.setActivityActive(false);
            assertFalse(SourceUpdateActivityPolicy.isActivityActive());
            assertTrue(SourceUpdateActivityPolicy.isPlaybackActive());
        } finally {
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
        assertFalse(SourceUpdateActivityPolicy.isPlaybackActive());
        assertFalse(SourceUpdateActivityPolicy.isActivityActive());
    }
}
