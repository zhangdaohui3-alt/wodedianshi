package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceProbeTimePolicyTest {
    @Test public void idleResumeAllowsLargerButStillBoundedRanges() {
        org.junit.Assert.assertArrayEquals(new long[]{2097152L,4194304L},
            SourceProbeTimePolicy.directoryRange("bytes 262144-2359295/4194304",262144L,4194304L,2097152L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 262144-2359296/4194304",262144L,4194304L,2097152L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 262144-2359295/4194305",262144L,4194304L,2097152L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 0-1/2",0L,-1L,0L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.directoryRange("bytes 0-1/2",0L,-1L,8388609L));
    }

    @Test public void rangeResumeRejectsWeakValidatorAndInexactContentRanges() {
        assertTrue(SourceProbeTimePolicy.strongDirectoryValidator("\"a\""));
        assertFalse(SourceProbeTimePolicy.strongDirectoryValidator("W/\"a\""));
        assertFalse(SourceProbeTimePolicy.strongDirectoryValidator(null));
        assertFalse(SourceProbeTimePolicy.strongDirectoryValidator("\"a\nb\""));
        org.junit.Assert.assertArrayEquals(new long[]{262144L,524288L},
            SourceProbeTimePolicy.liveDirectoryRange("bytes 262144-524287/524288",262144L,524288L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-262143/524288",262144L,524288L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 262144-524287/524289",262144L,524288L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-262144/524288",0L,-1L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-1/*",0L,-1L));
        org.junit.Assert.assertNull(SourceProbeTimePolicy.liveDirectoryRange("bytes 0-1/8388609",0L,-1L));
    }

    @Test public void viewingDirectoryUsesBoundedSizeAndPacing() {
        assertTrue(SourceProbeTimePolicy.fitsLiveDirectory(-1L));
        assertTrue(SourceProbeTimePolicy.fitsLiveDirectory(262144L));
        assertFalse(SourceProbeTimePolicy.fitsLiveDirectory(262145L));
        assertEquals(125L,SourceProbeTimePolicy.liveDirectoryPacingMillis(4096L,0L));
        assertEquals(75L,SourceProbeTimePolicy.liveDirectoryPacingMillis(4096L,50L));
        assertEquals(0L,SourceProbeTimePolicy.liveDirectoryPacingMillis(4096L,200L));
        assertEquals(8000L,SourceProbeTimePolicy.liveDirectoryPacingMillis(Long.MAX_VALUE,0L));
    }

    @Test public void stableViewingLeaseExpiresAndRevokesOnNewPlaybackOrStop() {
        SourceUpdateActivityPolicy.setActivityActive(true);
        SourceUpdateActivityPolicy.setPlaybackActive(true);
        try {
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(16000L));
            SourceUpdateActivityPolicy.reportStableLive(15000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(16000L));
            long generation=SourceUpdateActivityPolicy.stableRevocationGeneration();
            SourceUpdateActivityPolicy.revokeStableLive();
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(16001L));
            assertEquals(generation+1,SourceUpdateActivityPolicy.stableRevocationGeneration());
            SourceUpdateActivityPolicy.reportStableLive(17000L);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(17001L));
            SourceUpdateActivityPolicy.reportStableLive(18000L);
            SourceUpdateActivityPolicy.setActivityActive(false);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(18001L));
        } finally {
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }

    @Test public void initialAddressRequiresFullBudgetBeforeStarting() {
        assertEquals(16000L, SourceProbeTimePolicy.initialAddressDeadline(91000L,1000L));
        assertEquals(16000L, SourceProbeTimePolicy.initialAddressDeadline(16000L,1000L));
        assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(15999L,1000L));
        assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(1000L,1001L));
        assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(1000L,-1L));
    }

    @Test public void observedPauseRemainsLatchedAfterViewerLeaves() {
        java.util.concurrent.atomic.AtomicBoolean viewing = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.BooleanSupplier pause = SourceProbeTimePolicy.latchPause(viewing::get);
        assertFalse(pause.getAsBoolean());
        viewing.set(true);
        assertTrue(pause.getAsBoolean());
        viewing.set(false);
        assertTrue(pause.getAsBoolean());
        assertFalse(SourceProbeTimePolicy.latchPause(viewing::get).getAsBoolean());
    }

    @Test public void shortPlaybackStartIsVisibleEvenBetweenProbeChecks() {
        SourceUpdateActivityPolicy.setPlaybackActive(false);
        long start = SourceUpdateActivityPolicy.playbackStartGeneration();
        java.util.function.BooleanSupplier pause = SourceProbeTimePolicy.latchPause(
            () -> SourceUpdateActivityPolicy.playbackStartGeneration() != start);
        try {
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            assertTrue(pause.getAsBoolean());
        } finally { SourceUpdateActivityPolicy.setPlaybackActive(false); }
    }

    @Test public void repeatedActiveSignalsDoNotGenerateNewStarts() {
        SourceUpdateActivityPolicy.setActivityActive(false);
        long start = SourceUpdateActivityPolicy.activityStartGeneration();
        try {
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setActivityActive(true);
            assertEquals(start + 1, SourceUpdateActivityPolicy.activityStartGeneration());
        } finally { SourceUpdateActivityPolicy.setActivityActive(false); }
    }

    @Test public void cooperativePauseIsDistinctFromNetworkFailure() throws Exception {
        SourceProbeTimePolicy.checkPaused(() -> false);
        try {
            SourceProbeTimePolicy.checkPaused(() -> true);
            fail("Expected a cooperative pause");
        } catch (SourceProbeTimePolicy.PausedException expected) {
            assertTrue(expected instanceof java.io.IOException);
        }
    }

    @Test public void playlistParserStopsWithoutCompletingThePage() throws Exception {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        String playlist = "#EXTM3U\n#EXTINF:-1,First\nhttps://example.org/a\n#EXTINF:-1,Second\nhttps://example.org/b\n";
        try {
            PlaylistParser.parseEach(new java.io.StringReader(playlist), 0, 10,
                item -> count.incrementAndGet(), () -> count.get() > 0);
            fail("Expected a cooperative pause");
        } catch (SourceProbeTimePolicy.PausedException expected) {
            assertEquals(1, count.get());
        }
    }

    @Test public void publicationPauseThrowsUncheckedToTriggerRollback() {
        SourceProbeTimePolicy.checkPublicationPaused(() -> false);
        try {
            SourceProbeTimePolicy.checkPublicationPaused(() -> true);
            fail("Expected publication rollback signal");
        } catch (SourceProbeTimePolicy.PublicationPausedException expected) {
            assertTrue(expected instanceof RuntimeException);
        }
    }

    @Test public void usesPerRequestMaximumWhenDeadlineIsFarAway() {
        assertEquals(2500, SourceProbeTimePolicy.boundedTimeoutMillis(20_000L, 1_000L, 2500));
    }

    @Test public void clipsTimeoutToRemainingDeadline() {
        assertEquals(400, SourceProbeTimePolicy.boundedTimeoutMillis(1_400L, 1_000L, 2500));
    }

    @Test public void returnsZeroWhenDeadlineHasExpired() {
        assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(1_000L, 1_000L, 3000));
        assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(900L, 1_000L, 3000));
    }

    @Test public void rejectsNonPositiveRequestMaximum() {
        assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(5_000L, 1_000L, 0));
        assertEquals(0, SourceProbeTimePolicy.boundedTimeoutMillis(5_000L, 1_000L, -10));
    }
}
