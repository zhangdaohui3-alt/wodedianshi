package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateSchedulePolicyTest {
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
        assertEquals(7L, SourceUpdateSchedulePolicy.ROUTINE_REFRESH_DAYS);
    }

    @Test public void weeklyDeadlineSurvivesBusyDailyChecks() {
        long start=1_700_000_000_000L;
        long day=24L*60L*60L*1000L;
        assertEquals(1L, SourceUpdateSchedulePolicy.ROUTINE_ELIGIBILITY_CHECK_DAYS);
        assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+6*day));
        assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+7*day));
        assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,0,start+8*day));
        assertFalse(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,start+8*day,start+9*day));
        assertTrue(SourceUpdateSchedulePolicy.isRoutineMaintenanceDue(start,start+8*day,start+15*day));
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
