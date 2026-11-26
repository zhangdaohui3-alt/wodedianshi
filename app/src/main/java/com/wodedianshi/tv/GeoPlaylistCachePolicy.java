package com.wodedianshi.tv;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative lookup for country/state/city playlists already represented in
 * the Room channel catalogue. A TVG id's last country suffix can identify a
 * country; city/state membership requires explicit playlist provenance.
 */
final class GeoPlaylistCachePolicy {
    private static final Pattern KNOWN_FEED = Pattern.compile(
            "^https://iptv-org\\.github\\.io/iptv/(countries|subdivisions|cities)/([a-z0-9-]+)\\.m3u$",
            Pattern.CASE_INSENSITIVE);

    private GeoPlaylistCachePolicy() {}

    static String scopeForPlaylist(String url) {
        if (url == null) return "";
        Matcher match = KNOWN_FEED.matcher(url);
        return match.matches()
                ? "geo:" + match.group(1).toLowerCase(Locale.ROOT) + ":"
                    + match.group(2).toLowerCase(Locale.ROOT)
                : "";
    }

    static String countryForPlaylist(String url) {
        String scope = scopeForPlaylist(url);
        return scope.startsWith("geo:countries:") ? scope.substring("geo:countries:".length()) : "";
    }

    static boolean matchesCountryTvgId(String tvgId, String countryCode) {
        if (tvgId == null || countryCode == null || countryCode.length() != 2) return false;
        // iptv-org guide identifiers commonly end in a country code: ExampleTV.us.
        // Never match arbitrary parts of a channel name or a stream hostname.
        return tvgId.trim().toLowerCase(Locale.ROOT)
                .endsWith("." + countryCode.toLowerCase(Locale.ROOT));
    }
}
