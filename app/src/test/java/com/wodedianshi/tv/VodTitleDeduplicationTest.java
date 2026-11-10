package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodTitleDeduplicationTest {
    @Test public void mergesRegionalAppleIdsForSameTitleAndYear() {
        VodTitleRecord china = new VodTitleRecord("apple:100", "霸王别姬", "", "",
                "中国/华语", "Apple影视资料 (CN)", "https://example.cn/movie", 1993);
        VodTitleRecord us = new VodTitleRecord("apple:200", "霸王别姬", "", "",
                "国外/国际", "Apple影视资料 (US)", "https://example.com/movie", 1993);
        assertEquals(VodTitleDeduplication.key(china), VodTitleDeduplication.key(us));
    }

    @Test public void keepsDifferentReleaseYearsAsSeparateFilms() {
        VodTitleRecord first = new VodTitleRecord("apple:100", "The Thing", "", "",
                "国外/国际", "Apple影视资料 (US)", "https://example.com/1", 1982);
        VodTitleRecord remake = new VodTitleRecord("apple:200", "The Thing", "", "",
                "国外/国际", "Apple影视资料 (US)", "https://example.com/2", 2011);
        assertNotEquals(VodTitleDeduplication.key(first), VodTitleDeduplication.key(remake));
    }

    @Test public void normalizesPunctuationAndSpacing() {
        VodTitleRecord first = new VodTitleRecord("apple:100", "Spider-Man: Homecoming", "", "",
                "国外/国际", "Apple影视资料 (US)", "https://example.com/1", 2017);
        VodTitleRecord second = new VodTitleRecord("apple:200", "Spider Man Homecoming", "", "",
                "国外/国际", "Apple影视资料 (GB)", "https://example.com/2", 2017);
        assertEquals(VodTitleDeduplication.key(first), VodTitleDeduplication.key(second));
    }

    @Test public void doesNotMergeIndependentMetadataPages() {
        VodTitleRecord first = new VodTitleRecord("zh:123", "同名作品", "", "",
                "中国/华语", "Wikipedia (zh)", "https://zh.wikipedia.org/?curid=123", 0);
        VodTitleRecord second = new VodTitleRecord("en:456", "同名作品", "", "",
                "国外/国际", "Wikipedia (en)", "https://en.wikipedia.org/?curid=456", 0);
        assertNotEquals(VodTitleDeduplication.key(first), VodTitleDeduplication.key(second));
    }

    @Test public void fallsBackToProviderIdWhenYearIsUnknown() {
        VodTitleRecord first = new VodTitleRecord("apple:100", "Unknown", "", "",
                "国外/国际", "Apple影视资料 (US)", "https://example.com/1", 0);
        assertEquals("apple:100", VodTitleDeduplication.key(first));
    }
}
