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

    @Test public void rememberedSourceMatchesHostCaseAndDefaultPortAliases() {
        assertEquals(1, SourceSelectionPolicy.findEquivalentSource(
                Arrays.asList("http://other/live", "HTTP://Example.COM:80/live.m3u8"),
                "http://example.com/live.m3u8"));
    }

    @Test public void rememberedSourceDoesNotIgnoreSignedPathOrQueryDifferences() {
        assertEquals(-1, SourceSelectionPolicy.findEquivalentSource(
                Collections.singletonList("https://example.com/live?token=old"),
                "https://example.com/live?token=new"));
        assertEquals(-1, SourceSelectionPolicy.findEquivalentSource(
                Collections.singletonList("https://example.com/Live"),
                "https://example.com/live"));
    }

    @Test public void missingRememberedSourceDoesNotInventAnIndex() {
        assertEquals(-1, SourceSelectionPolicy.findEquivalentSource(
                Collections.singletonList("https://example.com/current"),
                "https://example.com/removed"));
    }

    @Test public void scanCursorFollowsLastUrlWhenPlaylistEntriesAreInserted() {
        java.util.List<String> oldList = Arrays.asList("a", "b", "c");
        String last = SourceSelectionPolicy.lastScannedKey(oldList, 0, 2);
        java.util.List<String> changed = Arrays.asList("new", "a", "b", "c");
        assertEquals(3, SourceSelectionPolicy.nextScanStart(changed, last, 2));
    }

    @Test public void scanCursorUsesLegacyIndexWhenLastUrlDisappears() {
        assertEquals(2, SourceSelectionPolicy.nextScanStart(
                Arrays.asList("a", "b", "c"), "missing-key", 5));
    }

    @Test public void scanCursorWrapsAtEndOfPlaylist() {
        java.util.List<String> urls = Arrays.asList("a", "b", "c");
        String last = SourceSelectionPolicy.lastScannedKey(urls, 1, 2);
        assertEquals(0, SourceSelectionPolicy.nextScanStart(urls, last, 0));
    }

    @Test public void emptyScanDoesNotCreateCursorKey() {
        assertEquals("", SourceSelectionPolicy.lastScannedKey(
                Arrays.asList("a", "b"), 0, 0));
    }

    @Test public void channelFailoverStopsWhenThereIsOnlyOneChannel() {
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(1, 0, 0));
    }

    @Test public void channelFailoverStopsAfterEveryOtherChannelWasTried() {
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(4, 2, 3));
    }

    @Test public void channelFailoverAdvancesAndWrapsAround() {
        assertEquals(0, ChannelFailoverPolicy.nextChannelIndex(4, 3, 1));
        assertEquals(2, ChannelFailoverPolicy.nextChannelIndex(4, 1, 0));
    }

    @Test public void channelFailoverRejectsEmptyListAndInvalidIndex() {
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(0, 0, 0));
        assertEquals(-1, ChannelFailoverPolicy.nextChannelIndex(3, -1, 0));
    }
}
