package com.wodedianshi.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeoPlaylistCachePolicyTest {
    @Test public void knownCountryFeedHasDurableScopeAndCountry() {
        String url = "https://iptv-org.github.io/iptv/countries/us.m3u";
        assertEquals("geo:countries:us", GeoPlaylistCachePolicy.scopeForPlaylist(url));
        assertEquals("us", GeoPlaylistCachePolicy.countryForPlaylist(url));
    }

    @Test public void subdivisionsAndCitiesMustUseExactSavedMembership() {
        assertEquals("geo:subdivisions:us-az", GeoPlaylistCachePolicy.scopeForPlaylist(
                "https://iptv-org.github.io/iptv/subdivisions/us-az.m3u"));
        assertEquals("geo:cities:usphx", GeoPlaylistCachePolicy.scopeForPlaylist(
                "https://iptv-org.github.io/iptv/cities/usphx.m3u"));
        assertEquals("", GeoPlaylistCachePolicy.countryForPlaylist(
                "https://iptv-org.github.io/iptv/cities/usphx.m3u"));
    }

    @Test public void rejectsUnrelatedOrMalformedFeeds() {
        assertEquals("", GeoPlaylistCachePolicy.scopeForPlaylist("https://example.org/iptv/countries/us.m3u"));
        assertEquals("", GeoPlaylistCachePolicy.scopeForPlaylist("https://iptv-org.github.io/iptv/countries/us.txt"));
    }

    @Test public void countryGuideIdMatchingIsStrict() {
        assertTrue(GeoPlaylistCachePolicy.matchesCountryTvgId("ExampleTV.us", "us"));
        assertTrue(GeoPlaylistCachePolicy.matchesCountryTvgId("ExampleTV.GB", "gb"));
        assertFalse(GeoPlaylistCachePolicy.matchesCountryTvgId("ExampleTV.ca", "us"));
        assertFalse(GeoPlaylistCachePolicy.matchesCountryTvgId("ExampleUSNews", "us"));
        assertFalse(GeoPlaylistCachePolicy.matchesCountryTvgId("", "us"));
    }
}
