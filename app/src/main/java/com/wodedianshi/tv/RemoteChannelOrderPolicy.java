package com.wodedianshi.tv;

/**
 * Rejects incomplete or outdated fullscreen channel orders.
 * The order contains positional channel indexes, so an equal row count alone
 * never proves it can be reused after a catalogue replacement.
 */
public final class RemoteChannelOrderPolicy {
    private RemoteChannelOrderPolicy() {}

    // Fixed, non-sliding window: a rapid burst can share one tune, while
    // a held key (300 ms cadence) cannot postpone playback indefinitely.
    static final long ZAP_BURST_WINDOW_MS=80L;

    static boolean canDispatchQueuedZap(boolean collecting,long queuedGeneration,
            long currentGeneration,int steps) {
        return !collecting&&queuedGeneration==currentGeneration&&steps!=0;
    }

    public static int mergeZapSteps(int pending, int delta) {
        return (int)Math.max(-12L, Math.min(12L, (long)pending + delta));
    }

    public static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The directory worker normalizes the search term once before scanning. */
    static boolean matchesNormalizedQuery(String q, String name, String group, String display) {
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

    private static final int NO_MATCH=-1, AMBIGUOUS_MATCH=-2;
    /** Distinguish missing evidence from contradictory evidence. */
    private static int matchState(int count,java.util.function.IntPredicate matches) {
        int found=NO_MATCH;
        for(int i=0;i<count;i++){
            if(!matches.test(i))continue;
            if(found>=0)return AMBIGUOUS_MATCH;
            found=i;
        }
        return found;
    }

    static int uniqueMatch(int count,java.util.function.IntPredicate matches) {
        return Math.max(NO_MATCH,matchState(count,matches));
    }

    /** Use weaker evidence only if stronger evidence is absent, never ambiguous. */
    static int resolveSavedChannel(int count,java.util.function.IntPredicate identity,
            java.util.function.IntPredicate metadata,
            java.util.function.IntPredicate endpoint) {
        int found=matchState(count,identity);
        if(found!=NO_MATCH)return Math.max(NO_MATCH,found);
        found=matchState(count,metadata);
        if(found!=NO_MATCH)return Math.max(NO_MATCH,found);
        return uniqueMatch(count,endpoint);
    }

    /** Positional auto-resume is valid only against the same restored object. */
    public static boolean canResume(boolean alreadySelected, boolean vodOpen,
            Object expected, Object current) {
        return !alreadySelected && !vodOpen && expected != null && expected == current;
    }

    /** Remap only an outstanding resume of the exact old object by its stable identity. */
    public static int remapResumeIndex(boolean alreadySelected, boolean vodOpen,
            Object expected, Object oldAtIndex, String identity,
            java.util.Map<String, Integer> newPositions, int newCount) {
        if (!canResume(alreadySelected, vodOpen, expected, oldAtIndex)
                || identity == null || identity.isEmpty() || newPositions == null) return -1;
        Integer position = newPositions.get(identity);
        return position != null && position >= 0 && position < newCount ? position : -1;
    }

    /** A delayed zap belongs only to unobstructed, foreground live playback. */
    public static boolean canApplyQueuedZap(boolean activityActive, boolean fullscreen,
            boolean menuOpen, boolean dialogOpen, boolean vodOpen, boolean locked) {
        return activityActive && fullscreen && !menuOpen && !dialogOpen && !vodOpen && !locked;
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
