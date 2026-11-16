package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SourceFeedScopePolicyTest {
    @Test public void recognizesDedicatedChinaFeeds() {
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8"));
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8"));
    }

    @Test public void recognizesIptvOrgChinaAndHmtCountryFeeds() {
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/countries/cn.m3u"));
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/countries/hk.m3u"));
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/countries/mo.m3u"));
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/countries/tw.m3u"));
    }

    @Test public void keepsGlobalAndCategoryFeedsGlobal() {
        assertEquals("global", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/countries/us.m3u"));
        assertEquals("global", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/categories/news.m3u"));
        assertEquals("global", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/index.m3u"));
        assertEquals("global", SourceFeedScopePolicy.scopeForFeed(
                "https://example.com/playlists/world_cn.m3u8"));
    }

    @Test public void toleratesFeedUrlsWithQueryAndFragment() {
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://example.com/cn_cctv.m3u8?token=abc#playlist"));
        assertEquals("china", SourceFeedScopePolicy.scopeForFeed(
                "https://iptv-org.github.io/iptv/countries/tw.m3u?x=1"));
        assertEquals("global", SourceFeedScopePolicy.scopeForFeed(null));
        assertEquals("global", SourceFeedScopePolicy.scopeForFeed("  "));
    }
}
