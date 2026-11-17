package com.wodedianshi.tv;

import java.net.URI;
import java.util.List;
import java.util.Locale;
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
        String activeKey = endpointKey(activeUrl);
        for (int step = 1; step < size; step++) {
            int index = (start + step) % size;
            String url = sources.get(index);
            if (url == null || url.trim().isEmpty()
                    || endpointKey(url).equals(activeKey)
                    || containsEquivalent(blocked, url)) continue;
            if (fallback < 0) fallback = index;
            if (!containsEquivalent(coolingDown, url)) return index;
        }
        return fallback;
    }

    private static boolean containsEquivalent(Set<String> values, String candidate) {
        if (values == null || values.isEmpty()) return false;
        String key = endpointKey(candidate);
        for (String value : values) {
            if (endpointKey(value).equals(key)) return true;
        }
        return false;
    }

    /**
     * Compares endpoint identity without changing the URL sent to the player.
     * Scheme and DNS host are case-insensitive; default HTTP(S) ports are
     * equivalent. Path and query remain case-sensitive and byte-preserving.
     */
    public static String endpointKey(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return trimmed;
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443)) port = -1;
            StringBuilder key = new StringBuilder(scheme).append("://");
            if (uri.getRawUserInfo() != null) {
                key.append(uri.getRawUserInfo()).append('@');
            }
            key.append(host);
            if (port >= 0) key.append(':').append(port);
            if (uri.getRawPath() != null) key.append(uri.getRawPath());
            if (uri.getRawQuery() != null) key.append('?').append(uri.getRawQuery());
            if (uri.getRawFragment() != null) key.append('#').append(uri.getRawFragment());
            return key.toString();
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    /** Finds a remembered URL in the current list without rewriting its signed path/query. */
    public static int findEquivalentSource(List<String> sources, String rememberedUrl) {
        if (sources == null || sources.isEmpty() || rememberedUrl == null || rememberedUrl.trim().isEmpty()) return -1;
        String rememberedKey = endpointKey(rememberedUrl);
        for (int i = 0; i < sources.size(); i++) {
            if (rememberedKey.equals(endpointKey(sources.get(i)))) return i;
        }
        return -1;
    }

    /**
     * Resumes a rotating scan after the last URL identity rather than relying only
     * on a list index. This keeps progress stable when a playlist inserts or removes
     * entries between scheduled runs.
     */
    public static int nextScanStart(List<String> urls, String lastScannedKey, int fallbackIndex) {
        if (urls == null || urls.isEmpty()) return -1;
        if (lastScannedKey != null && !lastScannedKey.isEmpty()) {
            // Choose the last occurrence: duplicate feed entries for one URL should
            // not make the scanner repeatedly restart before the same endpoint.
            int lastMatch = -1;
            for (int i = 0; i < urls.size(); i++) {
                if (StableKey.url(urls.get(i)).equals(lastScannedKey)) lastMatch = i;
            }
            if (lastMatch >= 0) return (lastMatch + 1) % urls.size();
        }
        return wrapIndex(fallbackIndex, urls.size());
    }

    /** Returns the stable key of the last item consumed by a scan window. */
    public static String lastScannedKey(List<String> urls, int startIndex, int scannedCount) {
        if (urls == null || urls.isEmpty() || scannedCount <= 0) return "";
        int start = wrapIndex(startIndex, urls.size());
        if (start < 0) return "";
        int last = (start + scannedCount - 1) % urls.size();
        return StableKey.url(urls.get(last));
    }
}
