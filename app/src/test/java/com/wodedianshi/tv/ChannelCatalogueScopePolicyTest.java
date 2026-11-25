package com.wodedianshi.tv;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelCatalogueScopePolicyTest {
    @Test public void internationalDirectoryDoesNotIncludeChinaOnlyChannels() {
        assertFalse(ChannelCatalogueScopePolicy.matches(
                new HashSet<>(Collections.singletonList("china")), "global"));
    }

    @Test public void multiScopeChannelCanAppearInBothDirectories() {
        HashSet<String> scopes = new HashSet<>(Arrays.asList("china", "global"));
        assertTrue(ChannelCatalogueScopePolicy.matches(scopes, "china"));
        assertTrue(ChannelCatalogueScopePolicy.matches(scopes, "global"));
    }

    @Test public void unknownOrMissingScopeNeverMatches() {
        assertFalse(ChannelCatalogueScopePolicy.matches(null, "global"));
        assertFalse(ChannelCatalogueScopePolicy.matches(Collections.emptySet(), "global"));
        assertFalse(ChannelCatalogueScopePolicy.matches(
                Collections.singleton("china"), null));
    }

    @Test public void droppingLastRegionalBackupDropsItsChannelScope() {
        java.util.Map<String, java.util.Set<String>> ownership = new java.util.HashMap<>();
        ownership.put("china-url", new HashSet<>(Collections.singletonList("china")));
        ownership.put("global-url", new HashSet<>(Collections.singletonList("global")));
        java.util.Set<String> scopes = ChannelCatalogueScopePolicy.retainedScopes(
                ownership, new HashSet<>(Collections.singletonList("china-url")));
        assertTrue(scopes.contains("china"));
        assertFalse(scopes.contains("global"));
    }

    @Test public void oneRetainedUrlCanPreserveMultipleScopes() {
        java.util.Map<String, java.util.Set<String>> ownership = new java.util.HashMap<>();
        ownership.put("shared-url", new HashSet<>(Arrays.asList("china", "global")));
        java.util.Set<String> scopes = ChannelCatalogueScopePolicy.retainedScopes(
                ownership, new HashSet<>(Collections.singletonList("shared-url")));
        assertTrue(scopes.contains("china"));
        assertTrue(scopes.contains("global"));
    }
    @Test public void retainedRegionalOwnershipSurvivesForSavedCountryAndStateUrl() {
        java.util.Map<String, java.util.Set<String>> ownership = new java.util.HashMap<>();
        ownership.put("country-url", new HashSet<>(Arrays.asList(
                "global", "geo:countries:us")));
        ownership.put("state-url", new HashSet<>(Arrays.asList(
                "global", "geo:subdivisions:us-az")));
        java.util.Set<String> scopes = ChannelCatalogueScopePolicy.retainedScopes(
                ownership, new HashSet<>(Arrays.asList("country-url", "state-url")));
        assertTrue(scopes.contains("geo:countries:us"));
        assertTrue(scopes.contains("geo:subdivisions:us-az"));
        assertTrue(scopes.contains("global"));
    }

    @Test public void removedLastRegionalSourceRemovesOnlyThatRegionMembership() {
        java.util.Map<String, java.util.Set<String>> ownership = new java.util.HashMap<>();
        ownership.put("country-url", new HashSet<>(Arrays.asList(
                "global", "geo:countries:us")));
        ownership.put("state-url", new HashSet<>(Arrays.asList(
                "global", "geo:subdivisions:us-az")));
        java.util.Set<String> scopes = ChannelCatalogueScopePolicy.retainedScopes(
                ownership, new HashSet<>(Collections.singletonList("country-url")));
        assertTrue(scopes.contains("geo:countries:us"));
        assertFalse(scopes.contains("geo:subdivisions:us-az"));
    }

    @Test public void persistedCountryAndCityTagsAreValidAtReaderBoundary() {
        assertTrue(ChannelCatalogueScopePolicy.isRetainableScope("geo:countries:us"));
        assertTrue(ChannelCatalogueScopePolicy.isRetainableScope("geo:subdivisions:us-az"));
        assertTrue(ChannelCatalogueScopePolicy.isRetainableScope("geo:cities:usphx"));
        assertFalse(ChannelCatalogueScopePolicy.isRetainableScope("geo:invalid:us"));
        assertFalse(ChannelCatalogueScopePolicy.isRetainableScope("geo:cities:../../secret"));
        assertFalse(ChannelCatalogueScopePolicy.isRetainableScope(null));
    }

    @Test public void aSurvivingLocalCityBackupKeepsItsDirectoryMembership() {
        java.util.Map<String, java.util.Set<String>> ownership = new java.util.HashMap<>();
        ownership.put("one", new HashSet<>(Arrays.asList("china", "geo:cities:cn-fuzhou")));
        ownership.put("two", new HashSet<>(Arrays.asList("global", "geo:cities:cn-xiamen")));
        java.util.Set<String> scopes = ChannelCatalogueScopePolicy.retainedScopes(
                ownership, new HashSet<>(Collections.singletonList("one")));
        assertTrue(scopes.contains("china"));
        assertTrue(scopes.contains("geo:cities:cn-fuzhou"));
        assertFalse(scopes.contains("geo:cities:cn-xiamen"));
        assertFalse(scopes.contains("global"));
    }

    @Test public void unknownGeographicScopeIsNotPromoted() {
        java.util.Map<String, java.util.Set<String>> ownership = new java.util.HashMap<>();
        ownership.put("one", new HashSet<>(Arrays.asList(
                "global", "geo:countries:../../private", "geo:unknown:us")));
        java.util.Set<String> scopes = ChannelCatalogueScopePolicy.retainedScopes(
                ownership, new HashSet<>(Collections.singletonList("one")));
        assertTrue(scopes.contains("global"));
        assertFalse(scopes.contains("geo:countries:../../private"));
        assertFalse(scopes.contains("geo:unknown:us"));
    }

}
