package com.wodedianshi.tv;

import java.util.Locale;

/** Stable duplicate key for movie metadata returned by several regional catalogues. */
public final class VodTitleDeduplication {
    private VodTitleDeduplication() {}

    /**
     * Apple storefronts can return the same film under different regional IDs.
     * Merge those records by normalized title and release year, but retain each
     * Wikipedia page independently and avoid guessing when an Apple year is absent.
     */
    public static String key(VodTitleRecord record) {
        if (record == null) return "";
        String id = record.id == null ? "" : record.id.trim();
        String provider = record.metadataProvider == null
                ? "" : record.metadataProvider.toLowerCase(Locale.ROOT);
        if (!provider.startsWith("apple")) return id;
        String title = normalizeTitle(record.title);
        if (title.isEmpty() || record.year <= 0) return id;
        return "apple-title:" + title + ":" + record.year;
    }

    /**
     * Prefer the selected region's storefront record; when both are equivalent,
     * retain the richer poster/synopsis record and use ID as a stable tie-breaker.
     */
    public static boolean prefer(VodTitleRecord candidate, VodTitleRecord current,
                                 String preferredRegion) {
        if (candidate == null) return false;
        if (current == null) return true;
        String preferred = preferredRegion == null ? "" : preferredRegion.trim();
        boolean candidateRegion = !preferred.isEmpty()
                && candidate.region != null && candidate.region.contains(preferred);
        boolean currentRegion = !preferred.isEmpty()
                && current.region != null && current.region.contains(preferred);
        if (candidateRegion != currentRegion) return candidateRegion;
        boolean candidatePoster = candidate.posterUrl != null && !candidate.posterUrl.isEmpty();
        boolean currentPoster = current.posterUrl != null && !current.posterUrl.isEmpty();
        if (candidatePoster != currentPoster) return candidatePoster;
        int candidateSynopsis = candidate.synopsis == null ? 0 : candidate.synopsis.length();
        int currentSynopsis = current.synopsis == null ? 0 : current.synopsis.length();
        if (candidateSynopsis != currentSynopsis) return candidateSynopsis > currentSynopsis;
        String candidateId = candidate.id == null ? "" : candidate.id;
        String currentId = current.id == null ? "" : current.id;
        return candidateId.compareTo(currentId) < 0;
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Z}\\s]+", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
