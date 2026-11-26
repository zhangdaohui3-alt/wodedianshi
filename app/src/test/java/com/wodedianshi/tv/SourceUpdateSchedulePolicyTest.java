package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateSchedulePolicyTest {
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

    @Test public void idleInputYieldUsesShortDelayWithoutRelaxingPlaybackGate() {
        assertEquals(5L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(false,true));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(true,true));
        assertEquals(30L,SourceUpdateSchedulePolicy.initialPressureDelaySeconds(false,false));
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
}
