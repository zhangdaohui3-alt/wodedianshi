package com.wodedianshi.tv;

import java.util.Arrays;

/**
 * Ordered public *discovery* directories, not an authorization or playback
 * guarantee. China and regional feeds are visited before worldwide indexes.
 * Round-robin paging keeps the large local aggregator from blocking later feeds.
 */
public final class SourceDiscoveryFeedCatalog {
    private SourceDiscoveryFeedCatalog() {}

    private static final String[] FEEDS = {
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8",
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv_status.m3u8",
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8",
        "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province_status.m3u8",
        // An independently curated feed groups county and provincial stations.
        // Put it before the larger global feeds; it was previously last.
        "https://raw.githubusercontent.com/cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u",
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cn.m3u",
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
        "https://iptv-org.github.io/iptv/languages/zho.m3u",
        "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u",
        "https://live.zbds.top/tv/iptv4.m3u",
        "https://iptv-org.github.io/iptv/countries/hk.m3u",
        "https://iptv-org.github.io/iptv/countries/mo.m3u",
        "https://iptv-org.github.io/iptv/countries/tw.m3u",
        "https://iptv-org.github.io/iptv/countries/us.m3u",
        "https://iptv-org.github.io/iptv/categories/news.m3u",
        "https://iptv-org.github.io/iptv/categories/entertainment.m3u",
        "https://iptv-org.github.io/iptv/categories/movies.m3u",
        "https://iptv-org.github.io/iptv/categories/music.m3u",
        "https://iptv-org.github.io/iptv/categories/kids.m3u",
        "https://iptv-org.github.io/iptv/categories/documentary.m3u",
        "https://iptv-org.github.io/iptv/categories/weather.m3u",
        "https://iptv-org.github.io/iptv/categories/education.m3u",
        // Public discovery feeds must still be screened for reachability and
        // appropriate playback rights. Do not promote addresses blindly.
        "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
        "https://biskyt.github.io/legalFreeIPTVplaylist/live.m3u",
        "https://raw.githubusercontent.com/freecasthub/public-iptv/main/playlist.m3u",
        "https://iptv-org.github.io/iptv/index.m3u"
    };

    public static String[] feeds() {
        return Arrays.copyOf(FEEDS, FEEDS.length);
    }
}
