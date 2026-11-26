package com.wodedianshi.tv;

import java.util.Locale;

/** Classifies the configured playlist feed itself, before channel metadata refinement. */
final class SourceFeedScopePolicy {
    private SourceFeedScopePolicy() {}

    static String scopeForFeed(String feed) {
        if (feed == null || feed.trim().isEmpty()) return "global";
        String value = feed.trim().toLowerCase(Locale.ROOT);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);

        int slash = value.lastIndexOf('/');
        String fileName = slash >= 0 ? value.substring(slash + 1) : value;

        // The app's China-first feeds use cn_cctv.m3u8 and cn_province.m3u8.
        // Do not require the iptv-org /countries/ path for these repository feeds.
        if (fileName.matches("cn(?:[_-][a-z0-9][a-z0-9._-]*)?\\.(?:m3u8?|txt)")) {
            return "china";
        }

        // iptv-org country feeds for mainland China, Hong Kong, Macau and Taiwan.
        if (value.matches(".*/countries/(?:cn|hk|mo|tw)\\.(?:m3u8?|txt)")) {
            return "china";
        }
        return "global";
    }
}
