package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.*;

public class SourceSelectionPolicyTest {
    private final java.util.List<String> sources = Arrays.asList("a", "b", "c", "d");

    @Test public void wrapIndexHandlesNegativeAndOversizedValues() {
        assertEquals(3, SourceSelectionPolicy.wrapIndex(-1, 4));
        assertEquals(2, SourceSelectionPolicy.wrapIndex(6, 4));
        assertEquals(-1, SourceSelectionPolicy.wrapIndex(0, 0));
    }

    @Test public void prefersFirstUnblockedSourceOutsideCooldown() {
        assertEquals(3, SourceSelectionPolicy.findNext(
                sources, 1, Collections.singleton("c"), Collections.emptySet()));
    }

    @Test public void skipsCooldownWhenAnotherSourceIsHealthy() {
        assertEquals(3, SourceSelectionPolicy.findNext(
                sources, 1, Collections.emptySet(), new HashSet<>(Arrays.asList("c"))));
    }

    @Test public void triesFirstUnblockedSourceWhenAllAreCoolingDown() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                sources, 1, Collections.emptySet(), new HashSet<>(Arrays.asList("c", "d", "a"))));
    }

    @Test public void returnsMinusOneWhenAllAlternatesAreBlocked() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                sources, 1, new HashSet<>(Arrays.asList("a", "c", "d")), Collections.emptySet()));
    }

    @Test public void requiresAtLeastTwoSources() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                Collections.singletonList("only"), 0, Collections.emptySet(), Collections.emptySet()));
        assertEquals(-1, SourceSelectionPolicy.findNext(
                null, 0, Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void skipsNullAndBlankBackupEntries() {
        assertEquals(4, SourceSelectionPolicy.findNext(
                Arrays.asList("a", "b", null, " ", "d"), 0,
                Collections.singleton("b"), Collections.emptySet()));
    }

    @Test public void doesNotTreatDuplicateActiveUrlAsARealBackup() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                Arrays.asList("a", "b", "a"), 0,
                Collections.singleton("b"), Collections.emptySet()));
    }

    @Test public void treatsSchemeHostCaseAndDefaultPortAsSameEndpoint() {
        assertEquals(-1, SourceSelectionPolicy.findNext(
                Arrays.asList("HTTP://Example.COM:80/live.m3u8", "http://example.com/live.m3u8"),
                0, Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void skipsEquivalentEndpointAndContinuesToDistinctBackup() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                Arrays.asList("https://EXAMPLE.com:443/live.m3u8",
                        "https://example.com/live.m3u8", "https://backup.example/live.m3u8"),
                0, Collections.emptySet(), Collections.emptySet()));
    }

    @Test public void canonicalEndpointKeyMatchesSchemeHostAndDefaultPortAliases() {
        assertEquals(SourceSelectionPolicy.endpointKey("HTTP://Example.COM:80/live.m3u8"),
                SourceSelectionPolicy.endpointKey("http://example.com/live.m3u8"));
    }

    @Test public void canonicalEndpointKeyPreservesCaseSensitivePathAndQuery() {
        assertNotEquals(SourceSelectionPolicy.endpointKey("http://example.com/Live?token=AbC"),
                SourceSelectionPolicy.endpointKey("http://example.com/live?token=AbC"));
        assertNotEquals(SourceSelectionPolicy.endpointKey("http://example.com/live?token=AbC"),
                SourceSelectionPolicy.endpointKey("http://example.com/live?token=abc"));
    }

    @Test public void blockedEndpointAliasesAreAlsoBlocked() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                Arrays.asList("http://active/live", "HTTP://Blocked.Example:80/live",
                        "http://backup.example/live"),
                0, Collections.singleton("http://blocked.example/live"), Collections.emptySet()));
    }

    @Test public void cooldownEndpointAliasesAreSkippedWhenHealthyBackupExists() {
        assertEquals(2, SourceSelectionPolicy.findNext(
                Arrays.asList("http://active/live", "HTTP://Slow.Example:80/live",
                        "http://backup.example/live"),
                0, Collections.emptySet(), Collections.singleton("http://slow.example/live")));
    }

    @Test public void skipsDuplicateActiveUrlAndContinuesToDistinctBackup() {
        assertEquals(3, SourceSelectionPolicy.findNext(
                Arrays.asList("a", "b", "a", "c"), 0,
                Collections.singleton("b"), Collections.emptySet()));
    }
}
