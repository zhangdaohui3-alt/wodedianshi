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
}
