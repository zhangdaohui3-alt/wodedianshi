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

    @Test public void globalMasterIndexRoutesHmtChannelsToChina() {
        assertEquals("china", RegionCategoryPolicy.catalogueScope("RTHK", "Hong Kong", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("TDM", "MO", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("PTS", "Taiwan", "global"));
        assertEquals("global", RegionCategoryPolicy.catalogueScope("BBC News", "United Kingdom", "global"));
        assertEquals("custom", RegionCategoryPolicy.catalogueScope("自定义频道", "自建频道", "custom"));
    }

    @Test public void separatesHongKongMacauAndTaiwanSubcategories() {
        assertEquals("香港", RegionCategoryPolicy.subcategory("RTHK", "Hong Kong"));
        assertEquals("澳门", RegionCategoryPolicy.subcategory("TDM", "MO"));
        assertEquals("台湾", RegionCategoryPolicy.subcategory("PTS", "Taiwan"));
        assertEquals("港澳台", RegionCategoryPolicy.subcategory("区域综合频道", "港澳台"));
        assertEquals("", RegionCategoryPolicy.subcategory("福州电视台", "福建"));
    }
}
