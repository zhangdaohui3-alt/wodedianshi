package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodCachePolicyTest {
    private static final long SIX_HOURS = 6L * 60L * 60L * 1000L;

    @Test public void completeRecentCacheIsFresh() {
        assertTrue(VodCachePolicy.isFresh(1000L, 1000L + SIX_HOURS - 1L, SIX_HOURS));
    }

    @Test public void partialOrMissingCacheIsNeverFresh() {
        assertFalse(VodCachePolicy.isFresh(0L, 10000L, SIX_HOURS));
        assertFalse(VodCachePolicy.isFresh(-1L, 10000L, SIX_HOURS));
    }

    @Test public void cacheExpiresAtSixHourBoundary() {
        assertFalse(VodCachePolicy.isFresh(1000L, 1000L + SIX_HOURS, SIX_HOURS));
    }

    @Test public void futureTimestampAndInvalidTtlAreRejected() {
        assertFalse(VodCachePolicy.isFresh(2000L, 1000L, SIX_HOURS));
        assertFalse(VodCachePolicy.isFresh(1000L, 2000L, 0L));
    }
}
