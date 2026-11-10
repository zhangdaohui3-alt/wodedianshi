package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class RegionCategoryPolicyTest {
    @Test public void doesNotTreatOrdinaryTvStationSuffixAsTaiwan() {
        assertFalse(RegionCategoryPolicy.isHongKongMacauTaiwan("福州电视台", "福建地方"));
        assertFalse(RegionCategoryPolicy.isHongKongMacauTaiwan("广东电视台", "广东"));
        assertFalse(RegionCategoryPolicy.isHongKongMacauTaiwan("四川电视台", "四川"));
    }

    @Test public void recognizesExplicitChineseRegions() {
        assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("香港电台", ""));
        assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("澳门广播电视", ""));
        assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("臺灣公共電視", ""));
    }

    @Test public void recognizesRegionGroupsAndBoundedCodes() {
        assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("TVB News", "Hong Kong"));
        assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("RTHK", "HK"));
        assertTrue(RegionCategoryPolicy.isHongKongMacauTaiwan("PTS", "Taiwan"));
        assertFalse(RegionCategoryPolicy.isHongKongMacauTaiwan("Talk TV", "Local"));
    }
}
