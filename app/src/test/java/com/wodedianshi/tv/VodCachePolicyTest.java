package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodCachePolicyTest {
    private static final long SIX_HOURS = 6L * 60L * 60L * 1000L;

    @Test public void sameSearchHasIndependentRegionKeys() {
        String category="搜索：霸王别姬";
        String china=VodCachePolicy.cacheKey(category,"中国");
        String foreign=VodCachePolicy.cacheKey(category,"国外");
        assertNotEquals(china,foreign);
        assertEquals(china,VodCachePolicy.cacheKey(category,"中国"));
        assertTrue(china.startsWith("vod_cache_v10_"));
    }

    @Test public void differentSearchesWithSameLegacyHashDoNotCollide() {
        String first="搜索：Aa",second="搜索：BB";
        assertEquals(first.hashCode(),second.hashCode());
        assertNotEquals(VodCachePolicy.cacheKey(first,"中国"),
                VodCachePolicy.cacheKey(second,"中国"));
    }

    @Test public void fixedCatalogueRetainsExistingNamespaceAndIgnoresRegion() {
        assertEquals("vod_cache_v9_"+Integer.toHexString("精选电影".hashCode()),
                VodCachePolicy.cacheKey("精选电影","国外"));
        assertEquals(VodCachePolicy.cacheKey("电影","中国"),
                VodCachePolicy.cacheKey("电影","国外"));
    }

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
