package com.wodedianshi.tv.db;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Normalizes legacy source arrays without changing the URL that was selected
 * by activeSource. Room stores source priority explicitly, so this mapping
 * must happen before duplicate URLs are removed.
 */
public final class LegacySourceOrderPolicy {
    private LegacySourceOrderPolicy() {}

    public static final class Result {
        public final List<String> urls;
        public final int activeIndex;

        Result(List<String> urls, int activeIndex) {
            this.urls = urls;
            this.activeIndex = activeIndex;
        }
    }

    public static Result normalize(List<String> legacySources, String primaryUrl,
            int requestedActiveIndex) {
        ArrayList<String> original = new ArrayList<>();
        if (legacySources != null) original.addAll(legacySources);

        if (original.isEmpty() && isUsable(primaryUrl)) original.add(primaryUrl);

        String activeUrl = requestedActiveIndex >= 0 && requestedActiveIndex < original.size()
                ? original.get(requestedActiveIndex) : primaryUrl;

        // Some old catalogues kept the primary URL separately and omitted it
        // from the optional backup array. It remains a valid source in Room.
        if (isUsable(primaryUrl) && !original.contains(primaryUrl)) {
            original.add(primaryUrl);
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String url : original) {
            if (isUsable(url)) unique.add(url);
        }
        if (unique.isEmpty() && isUsable(primaryUrl)) unique.add(primaryUrl);

        ArrayList<String> ordered = new ArrayList<>(unique);
        int activeIndex = isUsable(activeUrl) ? ordered.indexOf(activeUrl) : -1;
        if (activeIndex < 0) activeIndex = 0;
        return new Result(ordered, activeIndex);
    }

    private static boolean isUsable(String url) {
        return url != null && !url.trim().isEmpty();
    }
}
