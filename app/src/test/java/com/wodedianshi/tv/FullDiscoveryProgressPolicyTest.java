package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FullDiscoveryProgressPolicyTest {
    @Test public void coverageSeparatesMainlandCategoriesAndDoesNotCountForeignLocal() {
        FullDiscoveryProgressPolicy.Coverage c = new FullDiscoveryProgressPolicy.Coverage();
        c.add("CCTV-2","央视","","");
        c.add("福建东南卫视","卫视","","");
        c.add("厦门新闻","福建","","");
        c.add("Shenzhen News","广东","","");
        c.add("Local News","US","","");
        c.add("香港新闻","香港","","");
        assertEquals(1,c.cctvCount());
        assertEquals(1,c.satelliteCount());
        assertEquals(2,c.localCount());
        assertEquals(2,c.provinceCount());
        assertTrue(c.summary().contains("覆盖有缺口"));
        assertTrue(c.summary().contains("非实播验证"));
    }
    @Test public void emptyCoverageNeverClaimsSuccess() {
        FullDiscoveryProgressPolicy.Coverage c = new FullDiscoveryProgressPolicy.Coverage();
        assertEquals(0,c.provinceCount());
        assertTrue(c.summary().contains("0/31"));
        assertTrue(c.summary().contains("覆盖有缺口"));
    }

    @Test public void countsCompletedFeedsAndIndexedCursor() {
        assertEquals(0, FullDiscoveryProgressPolicy.percent(0, 20, 0, 500, false));
        assertEquals(12, FullDiscoveryProgressPolicy.percent(2, 20, 250, 500, false));
        assertEquals(25, FullDiscoveryProgressPolicy.percent(5, 20, 0, 0, false));
    }

    @Test public void aUnpublishedSnapshotNeverShowsOneHundredPercent() {
        assertEquals(99, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, false));
        assertEquals(100, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, true));
    }

    @Test public void missingOrInvalidIndexNeverInventsFractionalProgress() {
        assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, 50, -1, false));
        assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, -5, 100, false));
        assertEquals(0, FullDiscoveryProgressPolicy.percent(3, 0, 50, 100, false));
    }

    @Test public void corruptOrOverflowingCursorIsClamped() {
        assertEquals(20, FullDiscoveryProgressPolicy.percent(3, 20, 5000, 100, false));
        assertEquals(99, FullDiscoveryProgressPolicy.percent(Integer.MAX_VALUE, 20,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false));
    }

    @Test public void completedPercentRequiresActualPublication() {
        assertEquals(100, FullDiscoveryProgressPolicy.percent(0, 20, 0, 0, true));
    }
}
