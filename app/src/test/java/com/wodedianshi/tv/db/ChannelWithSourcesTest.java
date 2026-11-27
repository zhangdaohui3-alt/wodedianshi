package com.wodedianshi.tv.db;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ChannelWithSourcesTest {
    @Test
    public void sourceOrderIsStableAndActiveIndexKeepsItsMeaning() {
        ChannelWithSources item = new ChannelWithSources();
        item.channel = new ChannelEntity(
                "channel-1", "News|https://primary.example/live.m3u8",
                "News", "https://primary.example/live.m3u8", "News",
                "News", "", "", "[]", 1, 0);

        // Simulate Room returning relation rows in an arbitrary order.
        item.sources = Arrays.asList(
                new BackupSourceEntity("channel-1", "https://third.example/live.m3u8", 2, "[]"),
                new BackupSourceEntity("channel-1", "https://first.example/live.m3u8", 0, "[]"),
                new BackupSourceEntity("channel-1", "https://second.example/live.m3u8", 1, "[]"));

        List<BackupSourceEntity> ordered = item.getSourcesInPriorityOrder();
        assertEquals("https://first.example/live.m3u8", ordered.get(0).url);
        assertEquals("https://second.example/live.m3u8", ordered.get(1).url);
        assertEquals("https://third.example/live.m3u8", ordered.get(2).url);
        assertEquals("https://second.example/live.m3u8", item.getActiveSourceUrl());
    }

    @Test
    public void primaryUrlIsFallbackWhenNoRelationRowsExist() {
        ChannelWithSources item = new ChannelWithSources();
        item.channel = new ChannelEntity(
                "channel-2", "News|https://primary.example/live.m3u8",
                "News", "https://primary.example/live.m3u8", "News",
                "News", "", "", "[]", 0, 0);
        item.sources = Collections.emptyList();

        assertEquals("https://primary.example/live.m3u8", item.getActiveSourceUrl());
    }

    @Test
    public void activeIndexIsClampedToAvailableSources() {
        ChannelWithSources item = new ChannelWithSources();
        item.channel = new ChannelEntity(
                "channel-3", "News|https://primary.example/live.m3u8",
                "News", "https://primary.example/live.m3u8", "News",
                "News", "", "", "[]", 99, 0);
        item.sources = Arrays.asList(
                new BackupSourceEntity("channel-3", "https://primary.example/live.m3u8", 0, "[]"),
                new BackupSourceEntity("channel-3", "https://backup.example/live.m3u8", 1, "[]"));

        assertEquals("https://backup.example/live.m3u8", item.getActiveSourceUrl());
    }
}
