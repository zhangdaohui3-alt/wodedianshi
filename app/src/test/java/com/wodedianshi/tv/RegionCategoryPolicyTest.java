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
        assertEquals("china", RegionCategoryPolicy.catalogueScope("CCTV-1", "News", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("湖南卫视", "Entertainment", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("Xiamen News", "Local", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("综合频道", "福建地方", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("深圳都市频道", "地方", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("襄阳新闻综合", "地方", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("喀什电视台", "地方", "global"));
        assertEquals("global", RegionCategoryPolicy.catalogueScope("台州新闻频道", "地方", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope("浙江台州新闻频道", "地方", "global"));
        assertEquals("custom", RegionCategoryPolicy.catalogueScope("自定义频道", "自建频道", "custom"));
    }

    @Test public void mainlandAggregatorGroupsKeepCountyStationsInChinaCatalogue() {
        assertEquals("china", RegionCategoryPolicy.catalogueScope(
                "上虞新闻综合", "中国大陆 · 新闻", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope(
                "固镇新闻", "中国大陆 · 新闻", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope(
                "旺苍新闻综合", "Mainland China · Local", "global"));
        assertEquals("global", RegionCategoryPolicy.catalogueScope(
                "BBC News", "International · News", "global"));
    }

    @Test public void globalFeedUsesEpgNameAndIdToRecognizeLocalStations() {
        assertEquals("china", RegionCategoryPolicy.catalogueScope(
                "新闻综合", "News", "福建福州新闻综合", "", "global"));
        assertEquals("china", RegionCategoryPolicy.catalogueScope(
                "HD Channel", "News", "", "Chengdu.News", "global"));
        assertEquals("global", RegionCategoryPolicy.catalogueScope(
                "News", "International", "", "BBCNews", "global"));
        assertEquals("custom", RegionCategoryPolicy.catalogueScope(
                "新闻综合", "News", "福建福州新闻综合", "", "custom"));
    }

    @Test public void separatesHongKongMacauAndTaiwanSubcategories() {
        assertEquals("香港", RegionCategoryPolicy.subcategory("RTHK", "Hong Kong"));
        assertEquals("澳门", RegionCategoryPolicy.subcategory("TDM", "MO"));
        assertEquals("台湾", RegionCategoryPolicy.subcategory("PTS", "Taiwan"));
        assertEquals("港澳台", RegionCategoryPolicy.subcategory("区域综合频道", "港澳台"));
        assertEquals("", RegionCategoryPolicy.subcategory("福州电视台", "福建"));
    }
}
