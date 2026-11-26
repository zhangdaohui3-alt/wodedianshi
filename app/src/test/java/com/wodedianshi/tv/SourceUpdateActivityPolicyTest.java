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
    @Test public void handoffClearsViewingOnlyWhenNoOwnerOrContinuationRemains(){
        assertTrue(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(true,false,false,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,true,false,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,true,false,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,true,false,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,false,true,true));
        assertFalse(SourceUpdateActivityPolicy.mayClearAfterDecoderHandoff(false,false,false,false,false,false));
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
