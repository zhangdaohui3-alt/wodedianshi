package com.wodedianshi.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class VodEpisodeRecordTest {
    @Test public void separatesMetadataPageFromDirectPlayableSource() {
        VodEpisodeRecord metadataOnly = new VodEpisodeRecord(
                "ep-1", "series-1", 1, 2, "第二集", "简介", "https://img.example/p.jpg",
                1800000L, "catalogue", "https://catalogue.example/ep-1", "");
        assertTrue(metadataOnly.hasMetadataPage());
        assertFalse(metadataOnly.hasPlayableSource());

        VodEpisodeRecord playable = new VodEpisodeRecord(
                "ep-2", "series-1", 1, 3, "第三集", "", "", 0,
                "public-domain", "", "https://media.example/episode.mp4");
        assertTrue(playable.hasPlayableSource());
        assertFalse(playable.hasMetadataPage());
    }

    @Test public void metadataWebPageCannotMasqueradeAsPlayableMedia() {
        VodEpisodeRecord record = new VodEpisodeRecord(
                "ep", "series", 1, 1, "第一集", "", "", 0,
                "catalogue", "https://catalogue.example/watch", "javascript:alert(1)");
        assertTrue(record.hasMetadataPage());
        assertFalse(record.hasPlayableSource());
    }

    @Test public void progressKeySurvivesChangingMediaUrl() {
        VodEpisodeRecord first = new VodEpisodeRecord(
                "provider-episode-42", "series", 2, 5, "第五集", "", "", 0,
                "", "", "https://cdn-a.example/a.mp4");
        VodEpisodeRecord second = new VodEpisodeRecord(
                "provider-episode-42", "series", 2, 5, "第五集", "", "", 0,
                "", "", "https://cdn-b.example/b.mp4");
        assertEquals(first.progressKey(), second.progressKey());
    }

    @Test public void buildsFallbackProgressKeyFromSeriesSeasonAndEpisode() {
        VodEpisodeRecord record = new VodEpisodeRecord(
                "", "series-9", 0, 3, "特别篇", "", "", 0, "", "", "");
        assertEquals("episode:series-9:s0:e3", record.progressKey());
    }

    @Test public void handlesMissingIdentityAndClampsNegativeValues() {
        VodEpisodeRecord record = new VodEpisodeRecord(
                null, null, -1, -1, null, null, null, -1, null, null, null);
        assertEquals("", record.progressKey());
        assertFalse(record.isNumberedEpisode());
        assertEquals(0, record.seasonNumber);
        assertEquals(0, record.episodeNumber);
        assertEquals(0L, record.durationMillis);
    }

    @Test public void seasonMetadataIsSafeAndAllowsSpecialsSeasonZero() {
        VodSeasonRecord season = new VodSeasonRecord(
                "series-9", 0, "特别篇", "", "", 4);
        assertEquals(0, season.seasonNumber);
        assertEquals(4, season.episodeCount);
    }
}
