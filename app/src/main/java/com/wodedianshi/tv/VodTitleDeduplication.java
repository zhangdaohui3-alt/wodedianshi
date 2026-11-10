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

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Z}\\s]+", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
