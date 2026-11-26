package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.FutureTask;

public class IdleProbeWavePolicyTest {
    @Test public void recoveryWaveCannotExceedItsAdmissionBudget(){
        IdleProbeWavePolicy wave=new IdleProbeWavePolicy(2);
        wave.add("a","one");wave.add("b","two");
        assertFalse(wave.canAdd("c","three"));
    }
    @Test public void manualAndInitialIdleUseSameParallelPolicy() {
        assertTrue(IdleProbeWavePolicy.permitsParallel(true,false,false));
        assertTrue(IdleProbeWavePolicy.permitsParallel(false,true,false));
        assertFalse(IdleProbeWavePolicy.permitsParallel(false,false,false));
        assertFalse(IdleProbeWavePolicy.permitsParallel(true,false,true));
        assertFalse(IdleProbeWavePolicy.permitsParallel(false,true,true));
    }
    @Test public void manualWaveKeepsFullStageDeadlineButDoesNotStartNearEnd() {
        assertTrue(IdleProbeWavePolicy.canStart(false,73000L,90000L,90000L));
        assertFalse(IdleProbeWavePolicy.canStart(false,73001L,90000L,90000L));
        assertFalse(IdleProbeWavePolicy.canStart(false,91000L,90000L,90000L));
        assertTrue(IdleProbeWavePolicy.canStart(true,1000L,16000L,90000L));
        assertFalse(IdleProbeWavePolicy.canStart(true,1000L,89000L,90000L));
    }
    @Test public void hostLimitAndGlobalLimitAreIndependent() {
        IdleProbeWavePolicy wave=new IdleProbeWavePolicy();
        wave.add("a","one"); wave.add("b","one");
        assertFalse(wave.canAdd("c","one"));
        wave.add("c","two"); wave.add("d","three");
        assertFalse(wave.canAdd("e","four"));
    }
    @Test public void duplicateEndpointAndInvalidHostNeverAdmitted() {
        IdleProbeWavePolicy wave=new IdleProbeWavePolicy();
        wave.add("a","one");
        assertFalse(wave.canAdd("a","two"));
        assertFalse(wave.canAdd("b",""));
        assertFalse(wave.canAdd("","two"));
        assertTrue(wave.canAdd("b","one"));
    }
    @Test public void incompleteSuffixCannotBeDiscardedForCommit() {
        FutureTask<Integer> first=new FutureTask<>(()->1);
        FutureTask<Integer> last=new FutureTask<>(()->2);
        last.run();
        assertFalse(IdleProbeWavePolicy.allFinished(Arrays.asList(first,last)));
        first.run();
        assertTrue(IdleProbeWavePolicy.allFinished(Arrays.asList(first,last)));
    }
    @Test public void cancelledFutureIsNotProofOfNetworkRelease() {
        FutureTask<Integer> cancelled=new FutureTask<>(()->1);
        cancelled.cancel(true);
        assertFalse(IdleProbeWavePolicy.allFinished(Collections.singletonList(cancelled)));
        assertTrue(IdleProbeWavePolicy.allFinished(Collections.emptyList()));
    }
}
