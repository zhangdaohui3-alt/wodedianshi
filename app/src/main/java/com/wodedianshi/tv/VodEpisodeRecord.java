package com.wodedianshi.tv;

/**
 * One episode entry. Metadata pages and direct playable URLs are deliberately
 * separate so a catalogue result cannot be mistaken for a playable stream.
 */
public final class VodEpisodeRecord {
    public final String id;
    public final String seriesId;
    public final int seasonNumber;
    public final int episodeNumber;
    public final String title;
    public final String synopsis;
    public final String posterUrl;
    public final long durationMillis;
    public final String metadataProvider;
    public final String metadataUrl;
    public final String playableUrl;

    public VodEpisodeRecord(String id, String seriesId, int seasonNumber,
                            int episodeNumber, String title, String synopsis,
                            String posterUrl, long durationMillis,
                            String metadataProvider, String metadataUrl,
                            String playableUrl) {
        this.id = safe(id);
        this.seriesId = safe(seriesId);
        this.seasonNumber = Math.max(0, seasonNumber);
        this.episodeNumber = Math.max(0, episodeNumber);
        this.title = safe(title);
        this.synopsis = safe(synopsis);
        this.posterUrl = safe(posterUrl);
        this.durationMillis = Math.max(0L, durationMillis);
        this.metadataProvider = safe(metadataProvider);
        this.metadataUrl = safe(metadataUrl);
        this.playableUrl = safe(playableUrl);
    }

    /** Stable across changing stream URLs when a provider episode ID exists. */
    public String progressKey() {
        if (!id.isEmpty()) return "episode:" + id;
        if (seriesId.isEmpty() || episodeNumber <= 0) return "";
        return "episode:" + seriesId + ":s" + seasonNumber + ":e" + episodeNumber;
    }

    public boolean hasMetadataPage() {
        return metadataUrl.startsWith("https://");
    }

    /** A direct HTTP(S) media URL only; a metadata webpage never qualifies. */
    public boolean hasPlayableSource() {
        return playableUrl.startsWith("https://") || playableUrl.startsWith("http://");
    }

    public boolean isNumberedEpisode() {
        return episodeNumber > 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
