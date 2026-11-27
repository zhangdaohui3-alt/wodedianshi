package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateSchedulePolicyTest {
    @Test public void expiredRecentCacheEntryDoesNotEvictReusableOlderResult() {
        java.util.LinkedHashMap<String,Long> cache=new java.util.LinkedHashMap<>(8,0.75f,true);
        cache.put("older-success",290000L);
        cache.put("expired-failure",10000L);
        cache.put("new-success",300000L);
        SourceUpdateSchedulePolicy.trimInitialProbeCache(cache,2,
            at->SourceUpdateSchedulePolicy.canReuseInitialProbe(true,at,320000L));
        assertEquals(java.util.Arrays.asList("older-success","new-success"),new java.util.ArrayList<>(cache.keySet()));
        cache.put("another-success",310000L);
        cache.get("older-success");
        SourceUpdateSchedulePolicy.trimInitialProbeCache(cache,2,
            at->SourceUpdateSchedulePolicy.canReuseInitialProbe(true,at,320000L));
        assertEquals(java.util.Arrays.asList("another-success","older-success"),new java.util.ArrayList<>(cache.keySet()));
        SourceUpdateSchedulePolicy.trimInitialProbeCache(cache,0,at->true);
        assertTrue(cache.isEmpty());
    }

    @org.junit.Test public void onlyTimelyCompletedPositiveObservationsSurviveCancellation() {
        org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,false,100,101,200));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(false,false,100,101,200));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,true,100,101,200));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,false,101,101,200));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,false,100,200,100));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,false,-1,101,200));
    }

    @Test public void failedDirectoryIsNotEofEvenIfParsedCountLooksComplete() {
        assertFalse(SourceUpdateSchedulePolicy.initialFeedInspected(false,false,19952,48,20000,48));
        assertFalse(SourceUpdateSchedulePolicy.initialFeedInspected(false,false,0,0,-1,48));
        assertFalse(SourceUpdateSchedulePolicy.initialFeedInspected(false,true,0,0,-1,48));
        assertFalse(SourceUpdateSchedulePolicy.initialFeedInspected(true,false,19904,48,20000,48));
        assertTrue(SourceUpdateSchedulePolicy.initialFeedInspected(true,false,19952,48,20000,48));
        assertTrue(SourceUpdateSchedulePolicy.initialFeedInspected(true,true,19904,48,20000,48));
        assertTrue(SourceUpdateSchedulePolicy.initialFeedInspected(true,true,19904,0,-1,48));
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,true,19952,48,20000));
    }

    @Test public void recoveryWarningRequiresSuccessfulNonemptyIndexedEof() {
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,false,0,4,20000));
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,true,19996,4,20000));
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(false,false,19996,4,20000));
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,false,0,0,0));
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,false,0,3,-1));
        assertFalse(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,false,20001,0,20000));
        assertTrue(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,false,19996,4,20000));
        assertTrue(SourceUpdateSchedulePolicy.recoveredDirectoryScanned(true,false,20000,0,20000));
    }

    @Test public void retriedPredecessorReusesExistingUnfinishedSuccessor() {
        java.util.UUID current=java.util.UUID.randomUUID(),next=java.util.UUID.randomUUID();
        assertFalse(SourceUpdateSchedulePolicy.hasOtherUnfinishedWork(current,java.util.Collections.emptyList()));
        assertFalse(SourceUpdateSchedulePolicy.hasOtherUnfinishedWork(current,java.util.Collections.singletonList(current)));
        assertTrue(SourceUpdateSchedulePolicy.hasOtherUnfinishedWork(current,java.util.Arrays.asList(current,next)));
        assertTrue(SourceUpdateSchedulePolicy.hasOtherUnfinishedWork(current,java.util.Collections.singletonList(next)));
        assertFalse(SourceUpdateSchedulePolicy.hasOtherUnfinishedWork(current,java.util.Arrays.asList(current,null)));
    }

    @Test public void manualCompletionRequiresBothDirectoryAndSavedPoolCoverage() {
        assertFalse(SourceUpdateSchedulePolicy.manualRoundComplete(false,25,26,100,100));
        // Last directory can introduce routes absent from the pre-merge pool.
        assertFalse(SourceUpdateSchedulePolicy.manualRoundComplete(false,26,26,100,100));
        assertFalse(SourceUpdateSchedulePolicy.manualRoundComplete(true,26,26,99,100));
        assertTrue(SourceUpdateSchedulePolicy.manualRoundComplete(true,26,26,100,100));
        assertTrue(SourceUpdateSchedulePolicy.manualRoundComplete(true,26,26,0,0));
        assertEquals(12,SourceUpdateSchedulePolicy.manualSavedWindow(100,0,false));
        assertEquals(48,SourceUpdateSchedulePolicy.manualSavedWindow(100,0,true));
        assertEquals(5,SourceUpdateSchedulePolicy.manualSavedWindow(100,95,true));
        assertEquals(0,SourceUpdateSchedulePolicy.manualSavedWindow(100,100,true));
        assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("changed","old",90,100));
    }

    @Test public void manualRefreshChecksSavedRoutesEvenWhileBrowsing() {
        assertTrue(SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(false,true,true));
        assertTrue(SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(false,true,false));
        assertFalse(SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(false,false,true));
        assertTrue(SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(false,false,false));
        assertFalse(SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(true,true,true));
        assertFalse(SourceUpdateSchedulePolicy.shouldBuildSavedProbePool(true,true,false));
    }

    @Test public void initialCacheReusesOnlyEquivalentEndpoints() {
        String base=SourceUpdateSchedulePolicy.initialProbeCacheKey("https://EXAMPLE.test:443/live?a=1");
        assertEquals(base,SourceUpdateSchedulePolicy.initialProbeCacheKey("https://example.test/live?a=1"));
        assertNotEquals(base,SourceUpdateSchedulePolicy.initialProbeCacheKey("https://example.test/live?a=2"));
        assertNotEquals(base,SourceUpdateSchedulePolicy.initialProbeCacheKey("https://example.test:8443/live?a=1"));
        assertNotEquals(base,SourceUpdateSchedulePolicy.initialProbeCacheKey("https://example.test/Live?a=1"));
    }

    @Test public void idleFailureDoesNotDelayDifferentUnfailedDirectory() {
        assertEquals(0L,SourceUpdateSchedulePolicy.initialContinuationDelaySeconds(false,false,true));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialContinuationDelaySeconds(false,false,false));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialContinuationDelaySeconds(true,false,true));
        assertEquals(0L,SourceUpdateSchedulePolicy.initialContinuationDelaySeconds(false,true,false));
        assertEquals(5L,SourceUpdateSchedulePolicy.initialContinuationDelaySeconds(true,true,true));
    }

    @Test public void committedInitialContinuationAvoidsEmptyProgressSpin() {
        assertEquals(30L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(false,true,false,false,0,true));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(false,true,false,false,0,false));
        assertEquals(0L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(false,true,false,false,5,true));
        assertEquals(0L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(false,true,false,true,0,false));
        assertEquals(0L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(false,false,true,true,0,false));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(false,false,false,true,0,false));
        assertEquals(5L,SourceUpdateSchedulePolicy.initialCommittedContinuationDelay(true,true,false,false,0,false));
    }

    @Test public void idleSliceDeadlineIsMonotonicAndDoesNotInterruptPacedViewing() {
        assertFalse(SourceUpdateSchedulePolicy.initialIdleSliceExpired(true,false,1000,240999));
        assertTrue(SourceUpdateSchedulePolicy.initialIdleSliceExpired(true,false,1000,241000));
        assertFalse(SourceUpdateSchedulePolicy.initialIdleSliceExpired(true,true,1000,301000));
        assertFalse(SourceUpdateSchedulePolicy.initialIdleSliceExpired(false,false,1000,301000));
        assertFalse(SourceUpdateSchedulePolicy.initialIdleSliceExpired(true,false,1000,999));
        assertFalse(SourceUpdateSchedulePolicy.initialIdleSliceExpired(true,false,-1,300000));
    }

    @Test public void missingInitialChainRecoveryOnlyForStaleActiveIdleBuild() {
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(179999,true,false,false,false,true,true,false));
        assertTrue(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,false,false,false,true,true,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,false,false,false,false,true,true,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,true,false,false,true,true,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,false,true,false,true,true,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,false,false,true,true,true,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,false,false,false,false,true,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,false,false,false,true,false,false));
        assertFalse(SourceUpdateSchedulePolicy.mayRecoverMissingInitialChain(180000,true,false,false,false,true,true,true));
    }

    @Test public void inlineInitialWorkRequiresCommittedProgressAndIdleBudget() {
        assertTrue(SourceUpdateSchedulePolicy.canContinueInitialInline(1,1000,false,false,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(0,1000,false,false,false,true,0));
        assertTrue(SourceUpdateSchedulePolicy.canContinueInitialInline(2,60000,false,false,false,true,0));
        assertTrue(SourceUpdateSchedulePolicy.canContinueInitialInline(3,119999,false,false,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(4,1000,false,false,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(1,120000,false,false,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(3,-1,false,false,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(3,119999,false,true,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(3,119999,false,false,true,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(1,1000,true,false,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(1,1000,false,true,false,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(1,1000,false,false,true,true,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(1,1000,false,false,false,false,0));
        assertFalse(SourceUpdateSchedulePolicy.canContinueInitialInline(1,1000,false,false,false,true,5));
    }

    @Test public void idleInputYieldUsesShortDelayWithoutRelaxingPlaybackGate() {
        assertEquals(5L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(false,true));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(true,true));
        assertEquals(10L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(false,false));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(true,false));
    }

    @Test public void normalInitialViewingDeferralUsesFixedDelay() {
        assertEquals(30L,SourceUpdateSchedulePolicy.initialViewingDelaySeconds(false));
        assertEquals(60L,SourceUpdateSchedulePolicy.initialViewingDelaySeconds(true));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true,false));
        assertFalse(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false,false));
    }

    @Test public void firstDiscoveryProbeReuseIsShortLivedAndClockSafe() {
        assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, 1000, 301000));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, 1000, 301001));
        assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(false, 1000, 16000));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(false, 1000, 16001));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, 1000, 999));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true, -1, 999));
    }

    @Test public void manualRefreshLargerPageAndContinuationDelay() {
        assertEquals(DiscoveryBatchPolicy.INITIAL_PAGE_SIZE,DiscoveryBatchPolicy.MANUAL_PAGE_SIZE);
        assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(0,48,49,48));
        assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(48,1,49,48));
        assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(0,48,-1,48));
        assertTrue(DiscoveryBatchPolicy.manualRevalidateDirectory(0,""));
        assertFalse(DiscoveryBatchPolicy.manualRevalidateDirectory(0,"partial-page"));
        assertFalse(DiscoveryBatchPolicy.manualRevalidateDirectory(48,""));
        assertEquals(0L,SourceUpdateSchedulePolicy.manualContinuationDelaySeconds(true,1,false));
        assertEquals(0L,SourceUpdateSchedulePolicy.manualContinuationDelaySeconds(true,0,true));
        assertEquals(10L,SourceUpdateSchedulePolicy.manualContinuationDelaySeconds(true,0,false));
        assertEquals(10L,SourceUpdateSchedulePolicy.manualContinuationDelaySeconds(false,5,true));
    }

    @org.junit.Test public void manualRefreshProcessesAllPagesBeforeAdvancing() {
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(0,12,25,12));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(12,12,25,12));
        org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(24,1,25,12));
        org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(0,12,12,12));
        org.junit.Assert.assertFalse(SourceUpdateSchedulePolicy.manualFeedFinished(0,12,-1,12));
        org.junit.Assert.assertTrue(SourceUpdateSchedulePolicy.manualFeedFinished(12,0,-1,12));
    }

    @Test public void routineRotationCoversEveryFeedWithoutWrappingMidSlice() {
        int start=0, visited=0;
        do {
            int end=SourceUpdateSchedulePolicy.routineSliceEnd(start,26);
            assertTrue(end>start && end-start<=3);
            visited+=end-start;
            assertEquals(start,SourceUpdateSchedulePolicy.routineNextFeed(start,26,false));
            start=SourceUpdateSchedulePolicy.routineNextFeed(start,26,true);
        } while(start!=0);
        assertEquals(26,visited);
        assertEquals(26,SourceUpdateSchedulePolicy.routineSliceEnd(24,26));
    }

    @Test public void defersAutomaticUpdatesWhileAppIsForegrounded() {
        assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(true, false));
    }

    @Test public void allowsAutomaticUpdatesWhenAppIsInBackground() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(false, false));
    }

    @Test public void explicitManualUpdateIsNotDeferredByForegroundPolicy() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(true, true));
    }

    @Test public void firstInstallMayDiscoverWithEmptyOrSeededCatalogueInForeground() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, false, true, false));
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, false, true, true));
        assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, false, false, false));
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                false, false, true, false));
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, true, true, false));
    }

    @Test public void schedulesDiscoveryForNewInstall() {
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, 0L));
    }

    @Test public void doesNotScheduleWhenAutomaticUpdatesAreDisabled() {
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(false, 0L));
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(false, 1_700_000_000_000L));
    }

    @Test public void doesNotRepeatDiscoveryAfterAValidatedInitialDiscovery() {
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, 1_700_000_000_000L));
    }

    @Test public void failedAttemptsRemainEligibleForAnotherInitialDiscovery() {
        // A failed attempt must not write source_update_last_success_at.
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, 0L));
    }

    @Test public void negativeTimestampIsTreatedAsMissing() {
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, -1L));
    }

    @Test public void emptyOrUnvalidatedDiscoveryDoesNotCountAsSuccessful() {
        assertFalse(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(0));
        assertFalse(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(-1));
    }

    @Test public void initialDiscoveryRequiresEveryCandidateAndEveryFeed() {
        assertFalse(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(144, 24, 0));
        assertFalse(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(144, 144, 1));
        assertFalse(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(0, 0, 0));
        assertTrue(SourceUpdateSchedulePolicy.isInitialDiscoveryComplete(144, 144, 0));
    }

    @Test public void routineMaintenanceOnlyFollowsCompleteFirstCatalogue() {
        assertFalse(SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true, 0L));
        assertFalse(SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(false, 1234L));
        assertTrue(SourceUpdateSchedulePolicy.shouldScheduleRoutineMaintenance(true, 1234L));
        assertEquals(30L, SourceUpdateSchedulePolicy.ROUTINE_REFRESH_DAYS);
    }

    @Test public void monthlyDeadlineSurvivesBusyDailyChecks() {
        long start=1_700_000_000_000L;
        long day=24L*60L*60L*1000L;
        assertEquals(1L, SourceUpdateSchedulePolicy.ROUTINE_ELIGIBILITY_CHECK_DAYS);
        assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+29*day));
        assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+30*day));
        assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+31*day));
        assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,start+31*day,start+32*day));
        assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,start+31*day,start+61*day));
        assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(0,0,start));
    }

    @Test public void busyRoutineUpdateSkipsRatherThanRetryingEveryFewMinutes() {
        assertFalse(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false, false));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true, false));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false, true));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true, true));
    }

    @Test public void manualFirstRebuildMayProceedWhenSavedChannelsAlreadyExist() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, true, true, true));
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, true, true, false));
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, true, false, true));
    }

    @Test public void validatedDiscoverySourceCountsAsSuccessful() {
        assertTrue(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(1));
        assertTrue(SourceUpdateSchedulePolicy.hasValidatedDiscoverySources(24));
    }

    @Test public void provisionalEvidenceExpiresBeforeFullPreflight(){
        assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(true,true,1000L,61000L));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true,true,1000L,61001L));
        assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(true,false,1000L,61001L));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(false,true,1000L,16001L));
    }

    @Test public void provisionalRechecksReserveOnlyOutsideNormalWindowAndKeepFifo(){
        java.util.ArrayList<String> saved=new java.util.ArrayList<>();
        for(int i=0;i<20;i++)saved.add("k"+i);
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>(
                java.util.Arrays.asList("k19","k5","k7","missing","k9","k10"));
        assertEquals(java.util.Arrays.asList(5,7),
                SourceUpdateSchedulePolicy.provisionalRecheckPositions(saved,15,8,pending));
        // With eight ordinary slots, at most two can be reserved.
    }

    @Test public void provisionalEvidenceIsBoundedAndRetriesMoveToTail(){
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        for(int i=0;i<2050;i++)SourceUpdateSchedulePolicy.rememberProvisional(pending,"k"+i);
        assertEquals(2048,pending.size());
        assertFalse(pending.contains("k0"));
        assertEquals("k2",pending.iterator().next());
        SourceUpdateSchedulePolicy.rememberProvisional(pending,"k2");
        assertEquals("k3",pending.iterator().next());
        assertTrue(SourceUpdateSchedulePolicy.provisionalRecheckPositions(
                java.util.Arrays.asList("a","b"),0,2,pending).isEmpty());
    }

    @Test public void provisionalDailyEligibilityIsIndependentOfMonthlyMaintenance(){
        long now=1700000000000L,day=86400000L;
        assertFalse(SourceUpdateSchedulePolicy.provisionalRecheckDue(false,0L,now));
        assertTrue(SourceUpdateSchedulePolicy.provisionalRecheckDue(true,0L,now));
        assertFalse(SourceUpdateSchedulePolicy.provisionalRecheckDue(true,now,now+day-1));
        assertTrue(SourceUpdateSchedulePolicy.provisionalRecheckDue(true,now,now+day));
        assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(now,0L,now+day));
    }

    @Test public void qualityRevisionInvalidatesScoreCacheWithoutCatalogueChange(){
        assertFalse(SourceUpdateSchedulePolicy.needsIndexedQualityReload(42L,42L,"a","a"));
        assertTrue(SourceUpdateSchedulePolicy.needsIndexedQualityReload(42L,42L,"a","b"));
        assertTrue(SourceUpdateSchedulePolicy.needsIndexedQualityReload(42L,43L,"a","a"));
        assertTrue(SourceUpdateSchedulePolicy.needsIndexedQualityReload(-1L,42L,"",""));
        assertFalse(SourceUpdateSchedulePolicy.needsIndexedQualityReload(42L,42L,"",""));
    }

    @Test public void oneReuseRuleRespectsEvidenceGradeAgeAndViewingMode(){
        assertTrue(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,false,false,1000L,100000L));
        assertFalse(SourceUpdateSchedulePolicy.reusableInitialObservation(true,true,false,false,1000L,100000L));
        assertFalse(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,true,false,1000L,2000L));
        assertTrue(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,true,true,1000L,2000L));
        assertFalse(SourceUpdateSchedulePolicy.reusableInitialObservation(false,false,false,false,1000L,16001L));
        assertFalse(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,false,false,2000L,1000L));
    }

    @Test public void onlyRecentExplicitStableOutputDefersExtraQualityProbe(){
        long at=100000L,grace=SourceUpdateSchedulePolicy.STABLE_RECHECK_GRACE_MS;
        assertTrue(SourceUpdateSchedulePolicy.deferRecentlyStableRecheck("stable",at,at,at+grace-1));
        assertFalse(SourceUpdateSchedulePolicy.deferRecentlyStableRecheck("stable",at,at,at+grace));
        assertFalse(SourceUpdateSchedulePolicy.deferRecentlyStableRecheck("stable",at,at,at-1));
        assertFalse(SourceUpdateSchedulePolicy.deferRecentlyStableRecheck("stable",0L,0L,at));
        assertFalse(SourceUpdateSchedulePolicy.deferRecentlyStableRecheck("stable",at,at+1,at+2));
        for(String event:java.util.Arrays.asList("","ready","firstFrame","attempt","failure","buffer","audioCodecError"))
            assertFalse(SourceUpdateSchedulePolicy.deferRecentlyStableRecheck(event,at,at,at));
    }

    @Test public void cooledCandidatesCanBeReplacedWithoutIncreasingProbeBudget(){
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        java.util.HashMap<String,String> current=new java.util.HashMap<>();
        for(int i=0;i<100;i++){
            pending.add("k"+i);current.put("k"+i,"https://example.invalid/"+i);
        }
        java.util.List<String> ordered=SourceUpdateSchedulePolicy.provisionalRecheckOrder(
                pending,current,key->false,SourceUpdateSchedulePolicy.PROVISIONAL_RECHECK_CAP);
        assertEquals(100,ordered.size());
        int checked=0,skipped=0;
        String last="";
        for(String url:ordered){
            if(checked>=SourceUpdateSchedulePolicy.PROVISIONAL_DAILY_LIMIT)break;
            if(skipped<20){skipped++;continue;}
            checked++;last=url;
        }
        assertEquals(48,checked);
        assertEquals("https://example.invalid/67",last);
        assertEquals(100,pending.size());
        assertTrue(SourceUpdateSchedulePolicy.provisionalRecheckOrder(
                pending,current,key->false,0).isEmpty());
    }

    @Test public void freshStableDeferralDoesNotConsumeProbeSlotsOrDeletePendingKeys(){
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        java.util.HashMap<String,String> current=new java.util.HashMap<>();
        for(int i=0;i<100;i++){
            pending.add("k"+i);current.put("k"+i,"https://example.invalid/"+i);
        }
        java.util.List<String> original=new java.util.ArrayList<>(pending);
        java.util.List<String> selected=SourceUpdateSchedulePolicy.selectProvisionalRechecks(
                pending,current,key->Integer.parseInt(key.substring(1))<50);
        assertEquals(48,selected.size());
        assertEquals("https://example.invalid/50",selected.get(0));
        assertEquals("https://example.invalid/97",selected.get(47));
        assertEquals(original,new java.util.ArrayList<>(pending));
        assertEquals("https://example.invalid/0",
                SourceUpdateSchedulePolicy.selectProvisionalRechecks(pending,current).get(0));
    }

    @Test public void qualityRecheckSpreadsOriginsWithoutLosingOldestOrReorderingStoredQueue(){
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        java.util.HashMap<String,String> current=new java.util.HashMap<>();
        for(int i=0;i<60;i++){
            pending.add("slow"+i);current.put("slow"+i,"https://slow.invalid/"+i);
        }
        pending.add("missing");
        pending.add("fast0");current.put("fast0","https://fast.invalid/0");
        pending.add("fast1");current.put("fast1","https://fast.invalid/1");
        pending.add("other");current.put("other","https://other.invalid/0");
        java.util.List<String> original=new java.util.ArrayList<>(pending);
        java.util.List<String> selected=SourceUpdateSchedulePolicy.selectProvisionalRechecks(pending,current);
        assertEquals(48,selected.size());
        assertEquals(java.util.Arrays.asList("https://slow.invalid/0","https://fast.invalid/0",
                "https://other.invalid/0","https://slow.invalid/1","https://fast.invalid/1"),
                selected.subList(0,5));
        assertEquals(48,new java.util.HashSet<>(selected).size());
        assertEquals(original,new java.util.ArrayList<>(pending));
        // An attempted inconclusive oldest key rotates normally; an unattempted
        // cooled sibling remains eligible for the next bounded slice.
        SourceUpdateSchedulePolicy.rememberProvisional(pending,"slow0");
        assertEquals("https://slow.invalid/1",
                SourceUpdateSchedulePolicy.selectProvisionalRechecks(pending,current).get(0));
    }

    @Test public void qualityRecheckUsesRemainingIdleBudgetWithoutExceedingPageLimit(){
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        java.util.HashMap<String,String> current=new java.util.HashMap<>();
        pending.add("removed");
        for(int i=0;i<100;i++){
            pending.add("k"+i);current.put("k"+i,"https://example.invalid/"+i);
        }
        java.util.List<String> selected=SourceUpdateSchedulePolicy.selectProvisionalRechecks(pending,current);
        assertEquals(48,selected.size());
        assertEquals("https://example.invalid/0",selected.get(0));
        assertEquals("https://example.invalid/47",selected.get(47));
        int fast=0,slow=0;
        long now=1000L,end=91000L;
        for(String url:selected){
            if(SourceProbeTimePolicy.initialAddressDeadline(end,now)==0L)break;
            fast++;now+=1000L;
        }
        now=1000L;
        for(String url:selected){
            if(SourceProbeTimePolicy.initialAddressDeadline(end,now)==0L)break;
            slow++;now+=15000L;
        }
        assertEquals(48,fast);assertEquals(6,slow);
        assertEquals(101,pending.size());
    }

    @Test public void freshCacheDoesNotScanAllEntriesForEachOfTwentyThousandInsertions(){
        java.util.LinkedHashMap<Integer,Long> cache=new java.util.LinkedHashMap<>(16,0.75f,true);
        java.util.concurrent.atomic.AtomicInteger scans=new java.util.concurrent.atomic.AtomicInteger();
        long earliest=Long.MAX_VALUE;
        for(int i=0;i<20000;i++){
            long expires=300000L+i;
            cache.put(i,expires);earliest=Math.min(earliest,expires);
            earliest=SourceUpdateSchedulePolicy.trimInitialProbeCacheByExpiry(
                cache,1024,earliest,i,v->{scans.incrementAndGet();return v;});
        }
        assertEquals(0,scans.get());
        assertEquals(1024,cache.size());
        assertEquals(Integer.valueOf(18976),cache.keySet().iterator().next());
        assertTrue(SourceUpdateSchedulePolicy.canReuseInitialProbe(true,false,0L,300000L));
        assertFalse(SourceUpdateSchedulePolicy.canReuseInitialProbe(true,false,0L,300001L));
    }

    @Test public void expirySweepProtectsFreshLruBeforeEvicting(){
        java.util.LinkedHashMap<String,Long> cache=new java.util.LinkedHashMap<>(8,0.75f,true);
        cache.put("fresh-oldest",300000L);
        cache.put("expired",15000L);
        cache.put("fresh-newest",310000L);
        long hint=SourceUpdateSchedulePolicy.trimInitialProbeCacheByExpiry(cache,2,15000L,15001L,v->v);
        assertEquals(java.util.Arrays.asList("fresh-oldest","fresh-newest"),new java.util.ArrayList<>(cache.keySet()));
        assertEquals(300000L,hint);
        cache.put("new",320000L);
        hint=SourceUpdateSchedulePolicy.trimInitialProbeCacheByExpiry(cache,2,hint,15002L,v->v);
        assertEquals(java.util.Arrays.asList("fresh-newest","new"),new java.util.ArrayList<>(cache.keySet()));
        assertEquals(300000L,hint); // conservative lower bound after eviction
        cache.put("next",330000L);
        hint=SourceUpdateSchedulePolicy.trimInitialProbeCacheByExpiry(cache,2,hint,310001L,v->v);
        assertEquals(java.util.Arrays.asList("new","next"),new java.util.ArrayList<>(cache.keySet()));
        assertEquals(320000L,hint);
    }

    @Test public void manualEvidenceReuseRequiresSameResumableSessionAndFreshResult(){
        assertTrue(SourceUpdateSchedulePolicy.manualProbeCacheMatches(true,"manual-a","manual-a"));
        assertFalse(SourceUpdateSchedulePolicy.manualProbeCacheMatches(false,"manual-a","manual-a"));
        assertFalse(SourceUpdateSchedulePolicy.manualProbeCacheMatches(true,"manual-b","manual-a"));
        assertFalse(SourceUpdateSchedulePolicy.manualProbeCacheMatches(true,"",""));
        assertFalse(SourceUpdateSchedulePolicy.manualProbeCacheMatches(true,null,null));
        assertTrue(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,false,false,1000L,301000L));
        assertFalse(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,false,false,1000L,301001L));
        assertFalse(SourceUpdateSchedulePolicy.reusableInitialObservation(true,false,true,false,1000L,2000L));
        assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(false,false,2000L,3000L,4000L));
        assertFalse(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,true,2000L,3000L,4000L));
        assertTrue(SourceUpdateSchedulePolicy.mayRetainCompletedInitialSuccess(true,false,2000L,3000L,4000L));
    }

    @Test public void provisionalAdmissionReservesEveryPositionWithoutDroppingExistingPending(){
        assertTrue(SourceUpdateSchedulePolicy.permitsProvisionalScreen(2000,48));
        assertFalse(SourceUpdateSchedulePolicy.permitsProvisionalScreen(2001,48));
        assertFalse(SourceUpdateSchedulePolicy.permitsProvisionalScreen(2048,1));
        assertFalse(SourceUpdateSchedulePolicy.permitsProvisionalScreen(0,0));
        assertFalse(SourceUpdateSchedulePolicy.permitsProvisionalScreen(-1,48));
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        for(int i=0;i<2000;i++)SourceUpdateSchedulePolicy.rememberProvisional(pending,"old"+i);
        for(int i=0;i<48;i++)SourceUpdateSchedulePolicy.rememberProvisional(pending,"new"+i);
        assertEquals(2048,pending.size());
        assertTrue(pending.contains("old0"));
        assertFalse(SourceUpdateSchedulePolicy.permitsProvisionalScreen(pending.size(),1));
        pending.remove("old0");
        assertTrue(SourceUpdateSchedulePolicy.permitsProvisionalScreen(pending.size(),1));
    }

    @Test public void completePublicationPrunesOnlyUnretainedPendingKeysAndPreservesOrder(){
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>(
                java.util.Arrays.asList("old-fallback","removed","shared","new-source","removed-again"));
        java.util.HashSet<String> retained=new java.util.HashSet<>(
                java.util.Arrays.asList("new-source","shared","old-fallback","other"));
        assertEquals(0,SourceUpdateSchedulePolicy.pruneUnretainedProvisional(pending,retained,false));
        assertEquals(5,pending.size());
        assertEquals(2,SourceUpdateSchedulePolicy.pruneUnretainedProvisional(pending,retained,true));
        assertEquals(java.util.Arrays.asList("old-fallback","shared","new-source"),new java.util.ArrayList<>(pending));
        assertEquals(0,SourceUpdateSchedulePolicy.pruneUnretainedProvisional(pending,retained,true));
        assertTrue(SourceUpdateSchedulePolicy.permitsProvisionalScreen(pending.size(),48));
    }
}
