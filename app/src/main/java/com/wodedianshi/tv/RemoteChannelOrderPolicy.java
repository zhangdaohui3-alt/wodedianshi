package com.wodedianshi.tv;

/**
 * Rejects incomplete or outdated fullscreen channel orders.
 * The order contains positional channel indexes, so an equal row count alone
 * never proves it can be reused after a catalogue replacement.
 */
public final class RemoteChannelOrderPolicy {
    private RemoteChannelOrderPolicy() {}

    public static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean matchesQuery(String query, String name, String group, String display) {
        String q = normalizeQuery(query);
        return q.isEmpty() || contains(name, q) || contains(group, q) || contains(display, q);
    }

    private static boolean contains(String text, String query) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    public static boolean matchesMembership(String key, java.util.Set<String> geoKeys,
            boolean favoritesOnly, java.util.Set<String> favoriteKeys) {
        return (geoKeys == null || geoKeys.contains(key))
                && (!favoritesOnly || (favoriteKeys != null && favoriteKeys.contains(key)));
    }

    /** Positional auto-resume is valid only against the same restored object. */
    public static boolean canResume(boolean alreadySelected, boolean vodOpen,
            Object expected, Object current) {
        return !alreadySelected && !vodOpen && expected != null && expected == current;
    }

    public static boolean complete(boolean interrupted,
            long expectedMutation, long currentMutation) {
        return !interrupted && expectedMutation == currentMutation;
    }

    public static boolean mayPublish(boolean interrupted,
            long expectedMutation, long currentMutation,
            long expectedFilter, long currentFilter,
            int expectedCount, int currentCount,
            boolean expectedFavoriteMode, boolean currentFavoriteMode,
            int expectedFavoriteHash, int currentFavoriteHash) {
        return complete(interrupted, expectedMutation, currentMutation)
                && expectedFilter == currentFilter
                && expectedCount == currentCount
                && expectedFavoriteMode == currentFavoriteMode
                && expectedFavoriteHash == currentFavoriteHash;
    }
}
