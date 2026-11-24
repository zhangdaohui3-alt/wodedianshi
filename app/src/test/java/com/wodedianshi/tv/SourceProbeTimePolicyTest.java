package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceProbeTimePolicyTest {
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
