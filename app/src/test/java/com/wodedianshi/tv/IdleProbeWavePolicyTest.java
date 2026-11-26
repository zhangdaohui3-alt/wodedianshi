package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.FutureTask;

public class IdleProbeWavePolicyTest {
    @Test public void rollingWindowRefillsFreedSlotsWhileSlowHeadIsStillRunning(){
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(6,6,6,42));
        assertEquals(5,IdleProbeWavePolicy.rollingAdmissionSlots(6,1,6,42));
        assertEquals(1,IdleProbeWavePolicy.rollingAdmissionSlots(6,1,11,37));
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(6,1,12,36));
        assertEquals(2,IdleProbeWavePolicy.rollingAdmissionSlots(6,1,6,2));
    }
    @Test public void rollingWindowRespectsNetworkPressureAndRemainingBudget(){
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(1,0,1,20));
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(4,5,6,20));
        assertEquals(2,IdleProbeWavePolicy.rollingAdmissionSlots(4,2,6,20));
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(6,1,6,0));
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(6,3,2,20));
    }

    @Test public void bridgeOnlyRowsThatCannotIssueAnotherRequest(){
        assertTrue(IdleProbeWavePolicy.canBridgeKnownRow(true,true));
        assertTrue(IdleProbeWavePolicy.canBridgeKnownRow(false,false));
        assertTrue(IdleProbeWavePolicy.canBridgeKnownRow(false,true));
        assertFalse(IdleProbeWavePolicy.canBridgeKnownRow(true,false));
    }

    @Test public void localRegionRotationPreservesHostStationAndPriority(){
        java.util.List<String> input=Arrays.asList("0AaF1","0BaF1","0CaG1","0DbF1","0EbG1","0AaF2","1ZaG1");
        java.util.List<String> expected=Arrays.asList("0AaF1","0DbF1","0CaG1","0EbG1","0BaF1","0AaF2","1ZaG1");
        java.util.List<String> actual=IdleProbeWavePolicy.coverageFirst(input,
            v->v.substring(1,2),v->v.substring(2,3),v->v.charAt(0)-'0',v->v.substring(3,4));
        assertEquals(expected,actual);
        assertEquals(input.size(),actual.size());
        assertEquals(new java.util.HashSet<>(input),new java.util.HashSet<>(actual));
        assertEquals(actual,IdleProbeWavePolicy.coverageFirst(input,
            v->v.substring(1,2),v->v.substring(2,3),v->v.charAt(0)-'0',v->v.substring(3,4)));
    }

    @Test public void coverageBeforeBackupsKeepsEveryRouteAndPriority(){
        java.util.List<String> input=Arrays.asList("0Aa1","0Ab2","0Aa3","0Ba1","0Cc1","0Cb2","1Za1","1Za1");
        java.util.List<String> expected=Arrays.asList("0Aa1","0Cc1","0Ba1","0Ab2","0Cb2","0Aa3","1Za1","1Za1");
        java.util.List<String> actual=IdleProbeWavePolicy.coverageFirst(input,
                v->v.substring(1,2),v->v.substring(2,3),v->v.charAt(0)-'0');
        assertEquals(expected,actual);
        assertEquals(input.size(),actual.size());
        assertEquals(actual,IdleProbeWavePolicy.coverageFirst(input,
                v->v.substring(1,2),v->v.substring(2,3),v->v.charAt(0)-'0'));
    }

    @Test public void initialPageInterleavesHostsWithoutCrossingPriorityOrDroppingDuplicates(){
        java.util.List<String> input=Arrays.asList("0a1","0a2","0a3","0b1","0b2","0c1","1z1","1z1","1y1");
        java.util.List<String> expected=Arrays.asList("0a1","0b1","0c1","0a2","0b2","0a3","1z1","1y1","1z1");
        assertEquals(expected,IdleProbeWavePolicy.interleaveHosts(input,
                v->v.substring(1,2),v->v.charAt(0)-'0'));
        assertEquals(expected,IdleProbeWavePolicy.interleaveHosts(expected,
                v->v.substring(1,2),v->v.charAt(0)-'0'));
        assertEquals(9,input.size());
    }
    @Test public void sixInitialSlotsKeepPerHostLimit(){
        IdleProbeWavePolicy wave=new IdleProbeWavePolicy(6);
        wave.add("a1","a");wave.add("a2","a");
        assertFalse(wave.canAdd("a3","a"));
        wave.add("b1","b");wave.add("b2","b");
        wave.add("c1","c");wave.add("c2","c");
        assertFalse(wave.canAdd("d1","d"));
    }

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

    @Test public void completedHeadsCanRefillWithinExistingPendingAndRequestBounds(){
        assertEquals(6,IdleProbeWavePolicy.rollingAdmissionSlots(6,0,6,42));
        assertEquals(3,IdleProbeWavePolicy.rollingAdmissionSlots(6,3,6,42));
        assertEquals(1,IdleProbeWavePolicy.rollingAdmissionSlots(6,0,11,42));
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(6,0,12,42));
        assertEquals(2,IdleProbeWavePolicy.rollingAdmissionSlots(6,0,6,2));
        assertEquals(0,IdleProbeWavePolicy.rollingAdmissionSlots(1,0,6,42));
    }
}
