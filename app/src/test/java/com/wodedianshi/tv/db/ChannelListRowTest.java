package com.wodedianshi.tv.db;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChannelListRowTest {
    @Test public void recoversOriginalPrimaryUrlWhenScopedPrimaryDiffers() {
        ChannelListRow row = new ChannelListRow("id", "CCTV-1|https://global.example/live.m3u8",
                "CCTV-1", "https://china.example/live.m3u8", "央视频道",
                "央视频道", "", "", 0, 0);
        assertEquals("https://global.example/live.m3u8", row.legacyPrimaryUrl());
        assertEquals("https://china.example/live.m3u8", row.primaryUrl);
    }

    @Test public void preservesNamesContainingIdentitySeparator() {
        ChannelListRow row = new ChannelListRow("id", "CCTV|新闻|https://example/live.m3u8",
                "CCTV|新闻", "https://example/live.m3u8", "央视频道",
                "央视频道", "", "", 0, 0);
        assertEquals("https://example/live.m3u8", row.legacyPrimaryUrl());
    }

    @Test public void fallsBackToScopedPrimaryForMalformedLegacyKey() {
        ChannelListRow row = new ChannelListRow("id", "unrelated-key",
                "CCTV-1", "https://china.example/live.m3u8", "央视频道",
                "央视频道", "", "", 0, 0);
        assertEquals("https://china.example/live.m3u8", row.legacyPrimaryUrl());
    }
}
