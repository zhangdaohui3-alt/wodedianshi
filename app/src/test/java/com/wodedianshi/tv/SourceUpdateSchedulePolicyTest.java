package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateSchedulePolicyTest {
    @Test public void defersAutomaticUpdatesWhileAppIsForegrounded() {
        assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(true, false));
    }

    @Test public void allowsAutomaticUpdatesWhenAppIsInBackground() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(false, false));
    }

    @Test public void explicitManualUpdateIsNotDeferredByForegroundPolicy() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundAutoUpdate(true, true));
    }

    @Test public void firstInstallMayDiscoverWithEmptyCatalogueInForeground() {
        assertFalse(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
                true, false, true, false));
        assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
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

    @Test public void busyRoutineUpdateSkipsRatherThanRetryingEveryFewMinutes() {
        assertFalse(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false, false));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true, false));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(false, true));
        assertTrue(SourceUpdateSchedulePolicy.shouldRetryWhenBusy(true, true));
    }

    @Test public void manualFirstRebuildMustDeferWhenSavedChannelsAlreadyExist() {
        assertTrue(SourceUpdateSchedulePolicy.shouldDeferForegroundDiscovery(
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
