package com.wodedianshi.tv;

/** Metadata for one season in a series catalogue. */
public final class VodSeasonRecord {
    public final String seriesId;
    public final int seasonNumber;
    public final String title;
    public final String synopsis;
    public final String posterUrl;
    public final int episodeCount;

    public VodSeasonRecord(String seriesId, int seasonNumber, String title,
                           String synopsis, String posterUrl, int episodeCount) {
        this.seriesId = safe(seriesId);
        this.seasonNumber = Math.max(0, seasonNumber);
        this.title = safe(title);
        this.synopsis = safe(synopsis);
        this.posterUrl = safe(posterUrl);
        this.episodeCount = Math.max(0, episodeCount);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
