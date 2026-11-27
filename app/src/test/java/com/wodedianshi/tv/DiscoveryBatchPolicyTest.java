package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscoveryBatchPolicyTest {
    @org.junit.Test public void pacedForwardScanKeepsEarliestUnresolvedPositionUntilIdleReplay(){
        int marker=DiscoveryBatchPolicy.noteTransientRecheck(DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK,2);
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.deferInitialReplayForIdle(true,marker));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.deferInitialReplayForIdle(true,DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK));
        marker=DiscoveryBatchPolicy.resumeTransientRecheck(true,marker,4);
        marker=DiscoveryBatchPolicy.noteTransientRecheck(marker,5);
        org.junit.Assert.assertEquals(2,marker);
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldReplayTransientSuffix(marker,6,48));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.shouldReplayTransientSuffix(marker,48,48));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.deferInitialReplayForIdle(true,DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.deferInitialReplayForIdle(false,DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldReplayTransientSuffix(DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED,48,48));
    }

    @Test public void coolingStageResumesAtEarliestEligibleFeedWithoutSkippingPriority() {
        assertEquals(30000L,DiscoveryBatchPolicy.transientRecheckRemainingMs(100000L,100000L));
        assertEquals(1L,DiscoveryBatchPolicy.transientRecheckRemainingMs(100000L,129999L));
        assertEquals(0L,DiscoveryBatchPolicy.transientRecheckRemainingMs(100000L,130000L));
        assertEquals(0L,DiscoveryBatchPolicy.transientRecheckRemainingMs(100000L,99999L));
        assertEquals(3L,DiscoveryBatchPolicy.earliestStageCooldownSeconds(
            new boolean[]{false,false,false,false},new long[]{2101,12000,1,1},2,3));
        assertEquals(12L,DiscoveryBatchPolicy.earliestStageCooldownSeconds(
            new boolean[]{true,false,false,false},new long[]{1,12000,1,1},2,3));
        assertEquals(1L,DiscoveryBatchPolicy.earliestStageCooldownSeconds(
            new boolean[]{true,true,false,false},new long[]{0,0,1,0},2,3));
        assertEquals(30L,DiscoveryBatchPolicy.earliestStageCooldownSeconds(
            new boolean[]{false,false,false,false},new long[]{0,12000,1,1},2,3));
    }

    @Test public void coolingAllRegionsResumesAtEarliestEligibleFeed() {
        assertEquals(1L,DiscoveryBatchPolicy.earliestEligibleCooldownSeconds(
            new boolean[]{false,false,false,false},new long[]{21000,12000,1000,4000}));
        assertEquals(2L,DiscoveryBatchPolicy.earliestEligibleCooldownSeconds(
            new boolean[]{true,false,false},new long[]{1,12000,1001}));
        assertEquals(30L,DiscoveryBatchPolicy.earliestEligibleCooldownSeconds(
            new boolean[]{true,false,false},new long[]{0,0,1000}));
        assertEquals(30L,DiscoveryBatchPolicy.earliestEligibleCooldownSeconds(
            new boolean[]{true,true},new long[]{0,0}));
    }

    @Test public void idleInitialNetworkCapDoesNotDiscardReusablePrefix() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.canConsumeProbePosition(true,false,6,6,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canConsumeProbePosition(true,false,6,6,true));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.canConsumeProbePosition(true,false,5,6,true));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canConsumeProbePosition(true,true,2,2,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canConsumeProbePosition(false,false,24,24,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canConsumeProbePosition(false,false,24,24,true));
    }

    @Test public void earlyTimeoutReplayTargetsMissingCoreOnly() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,13,40,3,4));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,13,40,-1,4));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,95,143,3,4));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(false,13,40,3,4));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,13,40,3,1));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,13,40,3,0));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,13,40,7,4));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,96,143,3,4));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,95,144,3,4));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(true,16,48,-1,4));
    }

    @org.junit.Test public void transientReplayCooldownIsBoundedAndClockRollbackSafe() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.transientRecheckCooling(100000L,100000L));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.transientRecheckCooling(100000L,129999L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.transientRecheckCooling(100000L,130000L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.transientRecheckCooling(100000L,99999L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.transientRecheckCooling(0L,100000L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.transientRecheckCooling(-1L,100000L));
    }

    @org.junit.Test public void temporaryNetworkFailureAllowsOnlyOneExactPageSuffixReplay() {
        int marker=DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK;
        marker=DiscoveryBatchPolicy.noteTransientRecheck(marker,3);
        marker=DiscoveryBatchPolicy.noteTransientRecheck(marker,7);
        org.junit.Assert.assertEquals(3,marker);
        org.junit.Assert.assertEquals(3,DiscoveryBatchPolicy.resumeTransientRecheck(true,marker,8));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldReplayTransientSuffix(marker,8,48));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.shouldReplayTransientSuffix(marker,48,48));
        int used=DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED;
        org.junit.Assert.assertEquals(used,DiscoveryBatchPolicy.resumeTransientRecheck(true,used,3));
        org.junit.Assert.assertEquals(used,DiscoveryBatchPolicy.noteTransientRecheck(used,3));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.shouldReplayTransientSuffix(used,48,48));
        org.junit.Assert.assertEquals(-1,DiscoveryBatchPolicy.resumeTransientRecheck(false,marker,8));
        org.junit.Assert.assertEquals(-1,DiscoveryBatchPolicy.resumeTransientRecheck(false,used,8));
        org.junit.Assert.assertEquals(-1,DiscoveryBatchPolicy.resumeTransientRecheck(true,8,8));
    }

    @org.junit.Test public void newCoreCategoryYieldsOnlyAtDrainedSmallIdlePreviewBoundary() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.yieldForNewCorePreview(false,17,3,7,1,false));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.yieldForNewCorePreview(false,512,1,3,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(false,513,1,3,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(false,17,3,7,1,true));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(true,17,3,7,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(false,17,3,3,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(false,17,3,7,0,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(false,17,-1,7,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForNewCorePreview(false,0,3,7,1,false));
    }

    @org.junit.Test public void missingCoreCategoryExtendsRotationButNotSmallProbeWindow() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.preferCoreCoverage(5,20,-1));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.preferCoreCoverage(16,48,3));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.preferCoreCoverage(95,143,3));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.preferCoreCoverage(96,143,3));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.preferCoreCoverage(95,144,3));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.preferCoreCoverage(16,48,7));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.preferCoreCoverage(16,48,-1));
        org.junit.Assert.assertEquals(48,DiscoveryBatchPolicy.firstWatchProbeLimit(false,16,48));
        org.junit.Assert.assertEquals(2,DiscoveryBatchPolicy.firstWatchProbeLimit(true,16,48));
    }

    @org.junit.Test public void exactPageDedupPreservesDistinctStationMetadataAndAddresses() {
        java.util.List<String> first=java.util.Arrays.asList("CCTV-1","https://example.invalid/a","CCTV","","","china");
        java.util.List<String> duplicate=new java.util.ArrayList<>(first);
        java.util.List<String> otherStation=java.util.Arrays.asList("CCTV-2","https://example.invalid/a","CCTV","","","china");
        java.util.List<String> otherUrl=java.util.Arrays.asList("CCTV-1","https://example.invalid/a?token=b","CCTV","","","china");
        java.util.List<String> otherScope=java.util.Arrays.asList("CCTV-1","https://example.invalid/a","CCTV","","","global");
        java.util.List<String> otherMetadata=java.util.Arrays.asList("CCTV-1","https://example.invalid/a","CCTV","cctv1.cn","","china");
        java.util.List<java.util.List<String>> raw=java.util.Arrays.asList(first,duplicate,otherStation,otherUrl,otherScope,otherMetadata);
        java.util.List<java.util.List<String>> distinct=DiscoveryBatchPolicy.distinctExactPageRecords(raw,row->row);
        org.junit.Assert.assertEquals(java.util.Arrays.asList(first,otherStation,otherUrl,otherScope,otherMetadata),distinct);
        org.junit.Assert.assertEquals(6,raw.size());
        org.junit.Assert.assertSame(first,distinct.get(0));
        org.junit.Assert.assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("new","old",3,distinct.size()));
    }

    @org.junit.Test public void earlyIdlePositionWindowDoesNotIncreaseNetworkBudget() {
        org.junit.Assert.assertEquals(24,DiscoveryBatchPolicy.discoveryPositionWindow(true,false,48,6));
        org.junit.Assert.assertEquals(5,DiscoveryBatchPolicy.discoveryPositionWindow(true,false,5,6));
        org.junit.Assert.assertEquals(2,DiscoveryBatchPolicy.discoveryPositionWindow(true,true,48,2));
        org.junit.Assert.assertEquals(1,DiscoveryBatchPolicy.discoveryPositionWindow(false,true,1,2));
        org.junit.Assert.assertEquals(12,DiscoveryBatchPolicy.discoveryPositionWindow(false,false,48,12));
        org.junit.Assert.assertEquals(0,DiscoveryBatchPolicy.discoveryPositionWindow(true,false,0,6));
        org.junit.Assert.assertEquals(6,DiscoveryBatchPolicy.waveRequestAllowance(6,1));
        org.junit.Assert.assertEquals(2,DiscoveryBatchPolicy.waveRequestAllowance(6,5));
        org.junit.Assert.assertEquals(1,DiscoveryBatchPolicy.waveRequestAllowance(6,6));
        org.junit.Assert.assertEquals(0,DiscoveryBatchPolicy.waveRequestAllowance(6,7));
    }

    @org.junit.Test public void firstMainlandPreviewYieldsOnlyAtDrainedEarlyIdleBoundary() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(true,false,0,1,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(true,false,0,1,1,true));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(true,false,0,0,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(true,false,0,1,0,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(true,false,1,1,2,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(true,true,0,1,1,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.yieldForFirstMainlandPreview(false,false,0,1,1,false));
    }

    @org.junit.Test public void quickAcquisitionCanEnterBoundedIdleProbeInSameSlice() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(false,48,10000L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(false,48,10001L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(true,48,1L));
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(false,48,1L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(false,0,1L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(false,-1,1L));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.canProbeAfterQuickAcquisition(false,48,-1L));
    }

    @org.junit.Test public void acquisitionMarkerRequiresExactNonemptyBodyVersion() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:hash","feed:hash"));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:old","feed:new"));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("",""));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed(null,"feed:hash"));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:hash",null));
    }

    @org.junit.Test public void incrementalStagingRequiresEveryAppendedRowAndValidPositions() {
        com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(5,5,java.util.Collections.emptySet());
        com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(5,7,
            new java.util.HashSet<>(java.util.Arrays.asList(0,5,6)));
        int[][] invalid={{5,4,0},{5,7,5},{5,5,5},{5,5,-1}};
        for(int[] item:invalid){
            try{
                com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(
                    item[0],item[1],java.util.Collections.singleton(item[2]));
                org.junit.Assert.fail("Invalid staging mutation accepted");
            }catch(IllegalArgumentException expected){}
        }
    }

    @org.junit.Test public void independentlyResumesInterleavedFeedPages() {
        String first=DiscoveryBatchPolicy.initialBatchPrefix(0);
        String second=DiscoveryBatchPolicy.initialBatchPrefix(1);
        org.junit.Assert.assertNotEquals(first,second);
        java.util.Map<String,Integer> offsets=new java.util.HashMap<>();
        offsets.put(first+"scanned",7);
        offsets.put(second+"scanned",2);
        org.junit.Assert.assertEquals(7,DiscoveryBatchPolicy.scannedForBatch("a","a",offsets.get(first+"scanned"),24));
        org.junit.Assert.assertEquals(2,DiscoveryBatchPolicy.scannedForBatch("b","b",offsets.get(second+"scanned"),24));
        org.junit.Assert.assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("new-a","a",offsets.get(first+"scanned"),24));
        offsets.remove(first+"scanned");
        org.junit.Assert.assertEquals(Integer.valueOf(2),offsets.get(second+"scanned"));
    }

    @Test public void pendingViewingPageStaysIncompleteAndIdleCanResumeIt() {
        boolean[] completed={false,false,false};
        boolean[] viewingAttempts={
            DiscoveryBatchPolicy.deferPageForIdle(true,true,true),false,false};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextFeed(0,completed,viewingAttempts,2,2));
        completed[1]=true;
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,completed,viewingAttempts,2,2));
        assertEquals(0,SourceDiscoveryPriorityPolicy.activeStage(completed,2,2));
        boolean[] idleAttempts={
            DiscoveryBatchPolicy.deferPageForIdle(true,false,true),false,false};
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextFeed(0,completed,idleAttempts,2,2));
        int scanned=DiscoveryBatchPolicy.scannedForBatch("page","page",7,48);
        assertEquals(7,DiscoveryBatchPolicy.startIndex(scanned,48));
        assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("changed","page",7,48));
        org.junit.Assert.assertNotEquals(DiscoveryBatchPolicy.initialIdleRecheckKey(0),
                DiscoveryBatchPolicy.initialIdleRecheckKey(1));
    }

    @Test public void holdOnlyInitialViewingDiscoveryCandidates() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.holdInconclusiveCandidate(true,true,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.holdInconclusiveCandidate(true,false,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.holdInconclusiveCandidate(false,true,false));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.holdInconclusiveCandidate(true,true,true));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.deferPageForIdle(false,true,true));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.deferPageForIdle(true,true,false));
    }

    @Test public void cursorResumesOnlyForIdenticalKnownFeedVersion() {
        org.junit.Assert.assertEquals(24, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a","feed:a"));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a","feed:b"));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"","feed:b"));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"",""));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a",null));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(-1,"feed:a","feed:a"));
    }

    @Test public void resumesByPositionEvenWhenSeveralChannelsShareTheSameUrl() {
        String fingerprint = "same-batch";
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                fingerprint, fingerprint, 2, 5);
        assertEquals(2, DiscoveryBatchPolicy.startIndex(scanned, 5));
        assertEquals(3, DiscoveryBatchPolicy.remaining(scanned, 5));
    }

    @Test public void duplicateEndpointDoesNotSkipUnscannedChannelAfterIt() {
        java.util.List<String> channelEndpoints = java.util.Arrays.asList(
                "https://shared.example/live.m3u8",
                "https://other.example/live.m3u8",
                "https://shared.example/live.m3u8",
                "https://last.example/live.m3u8");
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                "batch", "batch", 1, channelEndpoints.size());
        assertEquals("https://other.example/live.m3u8",
                channelEndpoints.get(DiscoveryBatchPolicy.startIndex(
                        scanned, channelEndpoints.size())));
    }

    @Test public void shortFirstWatchSlicesAreBoundedAndPreserveViewingLimit() {
        assertEquals(6,DiscoveryBatchPolicy.firstWatchProbeLimit(false,0,0));
        assertEquals(6,DiscoveryBatchPolicy.firstWatchProbeLimit(false,15,47));
        assertEquals(48,DiscoveryBatchPolicy.firstWatchProbeLimit(false,16,0));
        assertEquals(48,DiscoveryBatchPolicy.firstWatchProbeLimit(false,0,48));
        assertEquals(2,DiscoveryBatchPolicy.firstWatchProbeLimit(true,0,0));
        assertEquals(2,DiscoveryBatchPolicy.firstWatchProbeLimit(true,200,500));
        assertEquals(48,DiscoveryBatchPolicy.INITIAL_PAGE_SIZE);
    }

    @Test public void largerInitialPageKeepsOrdinaryAndViewingProbeLimits() {
        assertEquals(48,DiscoveryBatchPolicy.INITIAL_PAGE_SIZE);
        assertEquals(48,DiscoveryBatchPolicy.probeLimit(true,false,24));
        assertEquals(2,DiscoveryBatchPolicy.probeLimit(true,true,24));
        assertEquals(5L,DiscoveryBatchPolicy.VIEWING_SUCCESS_DELAY_SECONDS);
        org.junit.Assert.assertFalse(IdleProbeWavePolicy.permitsParallel(true,false,true));
        assertEquals(24,DiscoveryBatchPolicy.probeLimit(false,false,24));
        assertEquals(24,DiscoveryBatchPolicy.probeLimit(false,true,24));
    }

    @Test public void expandedPageRechecksPartialOldPageWithoutSkippingRawCursor() {
        assertEquals(240,DiscoveryBatchPolicy.cursorForVersion(240,"feed:a","feed:a"));
        assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("page:48","page:24",18,48));
        assertEquals(18,DiscoveryBatchPolicy.scannedForBatch("page:48","page:48",18,48));
        assertEquals(30,DiscoveryBatchPolicy.remaining(18,48));
    }

    @Test public void changedBatchResetsPositionInsteadOfReusingOldCursor() {
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                "new-batch", "old-batch", 4, 7);
        assertEquals(0, scanned);
        assertEquals(0, DiscoveryBatchPolicy.startIndex(scanned, 7));
        assertEquals(7, DiscoveryBatchPolicy.remaining(scanned, 7));
    }

    @Test public void clampsProgressAndHandlesAnExhaustedBatch() {
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                "batch", "batch", 99, 3);
        assertEquals(0, DiscoveryBatchPolicy.startIndex(scanned, 3));
        assertEquals(0, DiscoveryBatchPolicy.remaining(scanned, 3));
        assertEquals(0, DiscoveryBatchPolicy.scannedForBatch(
                "batch", "batch", 2, 0));
    }

    @Test public void rollingAdmissionProtectsFirstWatchAndMissingCorePreviews(){
        int all=FullDiscoveryProgressPolicy.ALL_CORE;
        assertFalse(DiscoveryBatchPolicy.permitsRollingInitial(false,false,false,1000,all));
        assertFalse(DiscoveryBatchPolicy.permitsRollingInitial(true,true,false,16,all));
        assertFalse(DiscoveryBatchPolicy.permitsRollingInitial(true,false,true,100,all));
        assertFalse(DiscoveryBatchPolicy.permitsRollingInitial(true,false,false,100,0));
        assertFalse(DiscoveryBatchPolicy.permitsRollingInitial(true,false,false,100,-1));
        assertTrue(DiscoveryBatchPolicy.permitsRollingInitial(true,false,false,100,all));
        assertTrue(DiscoveryBatchPolicy.permitsRollingInitial(true,false,false,513,-1));
    }
}
