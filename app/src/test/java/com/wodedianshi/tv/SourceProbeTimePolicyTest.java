package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceProbeTimePolicyTest {
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
