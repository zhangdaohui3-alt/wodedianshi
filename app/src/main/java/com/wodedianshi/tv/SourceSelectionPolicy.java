package com.wodedianshi.tv;

import java.util.List;
import java.util.Set;

/** Deterministic, side-effect-free policy for selecting a live backup source. */
public final class SourceSelectionPolicy {
    private SourceSelectionPolicy() {}

    /** Positive modulo that works on every Android API level supported by the app. */
    public static int wrapIndex(int index, int size) {
        if (size <= 0) return -1;
        int wrapped = index % size;
        return wrapped < 0 ? wrapped + size : wrapped;
    }

    /**
     * Walks forward from the active source, skipping duplicate URLs, blocked entries
     * and preferring sources outside cooldown. If every distinct unblocked source is
     * cooling down, returns the first distinct unblocked source as a last-resort attempt.
     */
    public static int findNext(List<String> sources, int currentIndex,
                               Set<String> blocked, Set<String> coolingDown) {
        if (sources == null || sources.size() < 2) return -1;
        int fallback = -1;
        int size = sources.size();
        int start = wrapIndex(currentIndex, size);
        String activeUrl = sources.get(start);
        String activeKey = activeUrl == null ? "" : activeUrl.trim();
        for (int step = 1; step < size; step++) {
            int index = (start + step) % size;
            String url = sources.get(index);
            if (url == null || url.trim().isEmpty()
                    || url.trim().equals(activeKey)
                    || (blocked != null && blocked.contains(url))) continue;
            if (fallback < 0) fallback = index;
            if (coolingDown == null || !coolingDown.contains(url)) return index;
        }
        return fallback;
    }
}
