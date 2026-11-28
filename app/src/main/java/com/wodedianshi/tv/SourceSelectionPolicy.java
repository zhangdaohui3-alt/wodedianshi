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
            if (url == null || url.trim().isEmpty()) continue;
            String key = endpointKey(url);
            if (key.equals(activeKey) || containsEquivalentKey(blocked, key)) continue;
            if (fallback < 0) fallback = index;
            if (!containsEquivalentKey(coolingDown, key)) return index;
        }
        return fallback;
    }

    private static boolean containsEquivalentKey(Set<String> values, String key) {
        if (values == null || values.isEmpty()) return false;
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

    /** Explicit user feedback concerns this station/endpoint association, not
     * the endpoint's transport quality or another station's use of the URL. */
    static String channelBlockKey(String identity,String url) {
        if(identity==null||identity.isEmpty()||url==null||url.trim().isEmpty())return "";
        return "station:"+StableKey.channelIdentity(identity)+":"+StableKey.url(endpointKey(url));
    }

    static boolean isUserBlocked(Set<String> records,String identity,String url) {
        if(records==null||url==null||url.isEmpty())return false;
        String key=channelBlockKey(identity,url);
        // Preserve old explicit URL blocks without expanding them to other aliases.
        return records.contains(url)||(!key.isEmpty()&&records.contains(key));
    }

    static Set<String> blockedUrls(Set<String> records,String identity,List<String> sources) {
        Set<String> result=new java.util.HashSet<>();
        if(sources!=null)for(String url:sources)
            if(isUserBlocked(records,identity,url))result.add(url);
        return result;
    }

    /** A stale menu must not modify another channel or silently select a changed URL. */
    public static int resolveManualSelection(Object menuChannel, Object selectedChannel,
            List<String> currentSources, String requestedUrl) {
        if (menuChannel == null || menuChannel != selectedChannel || currentSources == null
                || requestedUrl == null || requestedUrl.trim().isEmpty()) return -1;
        return currentSources.indexOf(requestedUrl);
    }

    /** A delayed manual request belongs to the same channel and playback
     * generation. Resolve by exact URL, never reuse a stale list position. */
    static int resolveQueuedManualSelection(Object expectedChannel,Object currentChannel,
            long expectedSerial,long currentSerial,List<String> sources,
            String expectedActive,String currentActive,String target,Set<String> blocked) {
        if(expectedSerial!=currentSerial||expectedActive==null
                ||!expectedActive.equals(currentActive)||expectedActive.equals(target)
                ||(blocked!=null&&blocked.contains(target)))return -1;
        return resolveManualSelection(expectedChannel,currentChannel,sources,target);
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
        return urls == null ? -1 : nextScanStart(urls.size(), urls::get, lastScannedKey, fallbackIndex);
    }

    /** Reverse search preserves the last-occurrence rule and avoids hashing
     * earlier entries once the exact persisted cursor has been located. */
    static int nextScanStart(int size, java.util.function.IntFunction<String> urlAt,
                             String lastScannedKey, int fallbackIndex) {
        if (size <= 0) return -1;
        if (lastScannedKey != null && !lastScannedKey.isEmpty()) {
            for (int i = size - 1; i >= 0; i--) {
                if (StableKey.url(urlAt.apply(i)).equals(lastScannedKey)) return (i + 1) % size;
            }
        }
        return wrapIndex(fallbackIndex, size);
    }

    /** Returns the stable key of the last item consumed by a scan window. */
    public static String lastScannedKey(List<String> urls, int startIndex, int scannedCount) {
        return urls == null ? "" : lastScannedKey(urls.size(), urls::get, startIndex, scannedCount);
    }

    static String lastScannedKey(int size, java.util.function.IntFunction<String> urlAt,
                                 int startIndex, int scannedCount) {
        if (size <= 0 || scannedCount <= 0) return "";
        int start = wrapIndex(startIndex, size);
        int last = (int)(((long)start + scannedCount - 1) % size);
        return StableKey.url(urlAt.apply(last));
    }
}
