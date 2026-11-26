package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceProbeTimePolicyTest { 
    @Test public void fullViewingBodyStaysBoundedAndPacedBeyondOneRange() {
        assertTrue(SourceProbeTimePolicy.fitsLiveFullDirectory(8388608L));
        assertFalse(SourceProbeTimePolicy.fitsLiveFullDirectory(8388609L));
        assertEquals(32000L,SourceProbeTimePolicy.liveFullDirectoryPacingMillis(1048576L,0L));
        assertEquals(256000L,SourceProbeTimePolicy.liveFullDirectoryPacingMillis(Long.MAX_VALUE,0L));
        assertEquals(0L,SourceProbeTimePolicy.liveFullDirectoryPacingMillis(1048576L,32000L));
        assertTrue(SourceProbeTimePolicy.LIVE_FULL_DIRECTORY_BUDGET_MS>256000L);
    }

    @Test public void completedRangeCheckpointIsValidButTruncatedOrOversizeIsNot() {
        String tag="\"v1\"";
        org.junit.Assert.assertTrue(SourceProbeTimePolicy.validDirectoryCheckpoint(256,512,256,tag,"location"));
        org.junit.Assert.assertTrue(SourceProbeTimePolicy.validDirectoryCheckpoint(512,512,512,tag,"location"));
        org.junit.Assert.assertTrue(SourceProbeTimePolicy.validDirectoryCheckpoint(512,512,520,tag,"location"));
        org.junit.Assert.assertFalse(SourceProbeTimePolicy.validDirectoryCheckpoint(512,512,511,tag,"location"));
        org.junit.Assert.assertFalse(SourceProbeTimePolicy.validDirectoryCheckpoint(513,512,513,tag,"location"));
        org.junit.Assert.assertFalse(SourceProbeTimePolicy.validDirectoryCheckpoint(0,512,512,tag,"location"));
        org.junit.Assert.assertFalse(SourceProbeTimePolicy.validDirectoryCheckpoint(512,512,512,"W/"+tag,"location"));
        org.junit.Assert.assertFalse(SourceProbeTimePolicy.validDirectoryCheckpoint(512,512,512,tag,""));
        org.junit.Assert.assertFalse(SourceProbeTimePolicy.validDirectoryCheckpoint(1,8388609,1,tag,"location"));
    }

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
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(1000L));
            SourceUpdateActivityPolicy.reportStableLive(8999L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(8999L));
            SourceUpdateActivityPolicy.reportStableLive(9000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(9000L));
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(23999L));
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(24000L));

            long expiredGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
            SourceUpdateActivityPolicy.reportStableLive(25000L);
            assertEquals(expiredGeneration+1,SourceUpdateActivityPolicy.stableRevocationGeneration());
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(25000L));
            SourceUpdateActivityPolicy.reportStableLive(33000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(33000L));

            long generation=SourceUpdateActivityPolicy.stableRevocationGeneration();
            SourceUpdateActivityPolicy.revokeStableLive();
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(33001L));
            assertEquals(generation+1,SourceUpdateActivityPolicy.stableRevocationGeneration());

            SourceUpdateActivityPolicy.reportStableLive(34000L);
            SourceUpdateActivityPolicy.reportStableLive(42000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(42000L));
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(42001L));
            SourceUpdateActivityPolicy.reportStableLive(43000L);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(43000L));
            SourceUpdateActivityPolicy.reportStableLive(51000L);
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(51000L));
            SourceUpdateActivityPolicy.setActivityActive(false);
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(51001L));
        } finally {
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }

    @Test public void viewingSampleTimeoutsAreTighterWithoutChangingIdleBudgets() {
        assertEquals(1500,SourceProbeTimePolicy.sampleConnectTimeoutCap(true));
        assertEquals(1000,SourceProbeTimePolicy.sampleReadTimeoutCap(true));
        assertEquals(2500,SourceProbeTimePolicy.sampleConnectTimeoutCap(false));
        assertEquals(3000,SourceProbeTimePolicy.sampleReadTimeoutCap(false));
        assertEquals(400,SourceProbeTimePolicy.boundedTimeoutMillis(1400L,1000L,
                SourceProbeTimePolicy.sampleReadTimeoutCap(true)));
    }

    @Test public void viewingSamplePacesEveryChunkWithoutUnusedCredit() {
        assertEquals(32768L,SourceProbeTimePolicy.VIEWING_SAMPLE_BYTES_PER_SECOND);
        assertEquals(4096,SourceProbeTimePolicy.VIEWING_SAMPLE_CHUNK_BYTES);
        assertEquals(125L,SourceProbeTimePolicy.viewingSampleDelayMillis(4096));
        assertEquals(1000L,8*SourceProbeTimePolicy.viewingSampleDelayMillis(4096));
        assertEquals(1L,SourceProbeTimePolicy.viewingSampleDelayMillis(1));
        assertEquals(0L,SourceProbeTimePolicy.viewingSampleDelayMillis(0));
        assertEquals(0L,SourceProbeTimePolicy.viewingSampleDelayMillis(-1));
        assertEquals(25L,SourceProbeTimePolicy.VIEWING_SAMPLE_PAUSE_POLL_MS);
    }

    @Test public void boundedSampleReadsOnlyOneSentinelAndDoesNotPadShortRanges() {
        assertEquals(4096,SourceProbeTimePolicy.sampleReadLength(8192,0,4096));
        assertEquals(2,SourceProbeTimePolicy.sampleReadLength(8192,8191,4096));
        assertEquals(1,SourceProbeTimePolicy.sampleReadLength(8192,8192,4096));
        assertEquals(0,SourceProbeTimePolicy.sampleReadLength(8192,8193,4096));
        assertEquals(0,SourceProbeTimePolicy.sampleReadLength(0,0,4096));
        assertEquals(4096,SourceProbeTimePolicy.sampleRetainedLength(4096,8192));
        assertEquals(8192,SourceProbeTimePolicy.sampleRetainedLength(8193,8192));
    }

    @Test public void initialAddressRequiresFullBudgetBeforeStarting() {
        assertEquals(16000L, SourceProbeTimePolicy.initialAddressDeadline(91000L,1000L));
        assertEquals(16000L, SourceProbeTimePolicy.initialAddressDeadline(16000L,1000L));
        assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(15999L,1000L));
        assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(1000L,1001L));
        assertEquals(0L, SourceProbeTimePolicy.initialAddressDeadline(1000L,-1L));
    }

    @Test public void socketTimeoutBeforeAddressDeadlineIsStillInconclusive() {
        assertTrue(SourceProbeTimePolicy.initialProbeInconclusive(true,3000L,15000L));
        assertTrue(SourceProbeTimePolicy.initialProbeInconclusive(false,15000L,15000L));
        assertFalse(SourceProbeTimePolicy.initialProbeInconclusive(false,14999L,15000L));
    }

    @Test public void repeatedInconclusiveEndpointHasOneExtraOpportunityPerSlice() {
        assertFalse(SourceProbeTimePolicy.retryInconclusiveWithinSlice(0));
        assertTrue(SourceProbeTimePolicy.retryInconclusiveWithinSlice(1));
        assertFalse(SourceProbeTimePolicy.retryInconclusiveWithinSlice(2));
        assertFalse(SourceProbeTimePolicy.retryInconclusiveWithinSlice(20));
    }

    @Test public void parallelWaveLeavesTimeForOrderedMerge() {
        assertTrue(SourceProbeTimePolicy.hasWaveDrainMargin(90000L,88000L));
        assertFalse(SourceProbeTimePolicy.hasWaveDrainMargin(90000L,88001L));
        assertFalse(SourceProbeTimePolicy.hasWaveDrainMargin(90000L,90001L));
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
