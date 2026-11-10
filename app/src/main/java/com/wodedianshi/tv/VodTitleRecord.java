package com.wodedianshi.tv;

/**
 * Search catalogue record, deliberately independent from a playable media URL.
 * A title may be discoverable even when no direct stream is known.
 */
public final class VodTitleRecord {
    public final String id;
    public final String title;
    public final String alternateTitle;
    public final String synopsis;
    public final String region;
    public final String metadataProvider;
    public final String metadataUrl;
    public final int year;
    public final String posterUrl;

    public VodTitleRecord(String id, String title, String alternateTitle,
                          String synopsis, String region, String metadataProvider,
                          String metadataUrl, int year) {
        this(id, title, alternateTitle, synopsis, region, metadataProvider,
                metadataUrl, year, "");
    }

    public VodTitleRecord(String id, String title, String alternateTitle,
                          String synopsis, String region, String metadataProvider,
                          String metadataUrl, int year, String posterUrl) {
        this.id = safe(id);
        this.title = safe(title);
        this.alternateTitle = safe(alternateTitle);
        this.synopsis = safe(synopsis);
        this.region = safe(region);
        this.metadataProvider = safe(metadataProvider);
        this.metadataUrl = safe(metadataUrl);
        this.year = Math.max(0, year);
        this.posterUrl = safe(posterUrl);
    }

    public boolean hasMetadataPage() {
        return metadataUrl.startsWith("https://");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
