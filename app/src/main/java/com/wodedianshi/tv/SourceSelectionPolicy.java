package com.wodedianshi.tv;

import java.util.List;
import java.util.Set;

/** Deterministic, side-effect-free policy for selecting a live backup source. */
public final class SourceSelectionPolicy {
    private SourceSelectionPolicy() {}

    /**
     * Walks forward from the active source, skipping blocked entries and preferring
     * sources outside cooldown. If every unblocked source is cooling down, returns
     * the first unblocked source as a last-resort recovery attempt.
     */
    public static int findNext(List<String> sources, int currentIndex,
                               Set<String> blocked, Set<String> coolingDown) {
        if (sources == null || sources.size() < 2) return -1;
        int fallback = -1;
        int size = sources.size();
        int start = Math.floorMod(currentIndex, size);
        for (int step = 1; step < size; step++) {
            int index = (start + step) % size;
            String url = sources.get(index);
            if (url == null || (blocked != null && blocked.contains(url))) continue;
            if (fallback < 0) fallback = index;
            if (coolingDown == null || !coolingDown.contains(url)) return index;
        }
        return fallback;
    }
}
