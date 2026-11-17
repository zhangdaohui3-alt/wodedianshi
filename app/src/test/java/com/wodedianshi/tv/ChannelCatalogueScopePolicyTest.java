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
}
