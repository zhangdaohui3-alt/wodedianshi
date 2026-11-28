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
        String activeUrl = legacySources != null && requestedActiveIndex >= 0
                && requestedActiveIndex < legacySources.size()
                ? legacySources.get(requestedActiveIndex) : primaryUrl;

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (legacySources != null) {
            for (String url : legacySources) {
                if (isUsable(url)) unique.add(url);
            }
        }
        // Append a separately stored primary once, without copying or scanning
        // the original list. LinkedHashSet preserves the ranked order.
        if (isUsable(primaryUrl)) unique.add(primaryUrl);

        ArrayList<String> ordered = new ArrayList<>(unique);
        int activeIndex = isUsable(activeUrl) ? ordered.indexOf(activeUrl) : -1;
        if (activeIndex < 0) activeIndex = 0;
        return new Result(ordered, activeIndex);
    }

    private static boolean isUsable(String url) {
        return url != null && !url.trim().isEmpty();
    }
}
