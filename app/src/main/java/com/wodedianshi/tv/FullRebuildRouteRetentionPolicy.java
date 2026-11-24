package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A first complete catalogue must not erase routes a user could already play.
 * Preserve the last selected and stable primary URLs, then the other existing
 * sources; newly discovered routes fill unused capacity only. Daily/weekly
 * maintenance can replace stale backups later through independent evidence.
 */
public final class FullRebuildRouteRetentionPolicy {
    private FullRebuildRouteRetentionPolicy() {}

    public static final class Result {
        public final List<String> urls;
        public final int activeIndex;

        private Result(List<String> urls, int activeIndex) {
            this.urls = urls;
            this.activeIndex = activeIndex;
        }
    }

    public static Result merge(List<String> existing, int previousActiveIndex,
            String stablePrimary, List<String> discovered, int maximum) {
        ArrayList<String> urls = new ArrayList<>();
        if (maximum <= 0) return new Result(urls, 0);
        HashSet<String> endpointKeys = new HashSet<>();
        String active = existing != null && previousActiveIndex >= 0
                && previousActiveIndex < existing.size()
                ? existing.get(previousActiveIndex) : stablePrimary;
        // An exact signed active URL is protected even if it points at the same
        // endpoint as the separately saved primary.
        addPinned(urls, endpointKeys, stablePrimary, maximum);
        addPinned(urls, endpointKeys, active, maximum);
        if (existing != null) {
            for (String url : existing) addBackup(urls, endpointKeys, url, maximum);
        }
        if (discovered != null) {
            for (String url : discovered) addBackup(urls, endpointKeys, url, maximum);
        }
        int activeIndex = urls.indexOf(active);
        if (activeIndex < 0 && active != null) {
            String endpoint = SourceSelectionPolicy.endpointKey(active);
            for (int i = 0; i < urls.size(); i++) {
                if (SourceSelectionPolicy.endpointKey(urls.get(i)).equals(endpoint)) {
                    activeIndex = i;
                    break;
                }
            }
        }
        return new Result(urls, Math.max(0, activeIndex));
    }

    private static boolean http(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static void addPinned(List<String> urls, HashSet<String> keys,
            String url, int maximum) {
        if (!http(url) || urls.size() >= maximum || urls.contains(url)) return;
        urls.add(url);
        keys.add(SourceSelectionPolicy.endpointKey(url));
    }

    private static void addBackup(List<String> urls, HashSet<String> keys,
            String url, int maximum) {
        if (!http(url) || urls.size() >= maximum) return;
        String key = SourceSelectionPolicy.endpointKey(url);
        if (keys.add(key)) urls.add(url);
    }
}
