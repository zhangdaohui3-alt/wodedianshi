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
}
