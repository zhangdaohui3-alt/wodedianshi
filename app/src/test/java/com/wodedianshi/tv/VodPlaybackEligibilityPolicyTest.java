package com.wodedianshi.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class VodPlaybackEligibilityPolicyTest {
    @Test public void acceptsDirectHttpAndHttpsMediaLocations() {
        assertTrue(VodPlaybackEligibilityPolicy.canPlay(
                "commons:film.mp4", "https://cdn.example/film.mp4?token=abc"));
        assertTrue(VodPlaybackEligibilityPolicy.canPlay(
                "loc:12345", "http://media.example/archive/video"));
    }

    @Test public void rejectsMetadataOnlyResultsEvenIfMetadataPageIsPresent() {
        assertFalse(VodPlaybackEligibilityPolicy.canPlay(
                "meta:tmdb-123", "https://www.example.com/movie/123"));
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("meta:tmdb-123", ""));
    }

    @Test public void rejectsMissingMalformedAndNonHttpLocations() {
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("film", null));
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("film", ""));
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("film", "javascript:alert(1)"));
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("film", "file:///sdcard/movie.mp4"));
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("film", "https:///missing-host"));
        assertFalse(VodPlaybackEligibilityPolicy.canPlay("film", "not a url"));
    }
}
