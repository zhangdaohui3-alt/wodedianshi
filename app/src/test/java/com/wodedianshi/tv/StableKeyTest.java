package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class StableKeyTest {
    @Test public void shaKeysSeparateKnownJavaHashCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertEquals(StableKey.legacyUrl("Aa"), StableKey.legacyUrl("BB"));
        assertNotEquals(StableKey.url("Aa"), StableKey.url("BB"));
    }

    @Test public void channelKeyIncludesNameGroupAndPrimaryUrl() {
        String first = StableKey.channel("CCTV-1", "央视", "https://a.example/live");
        assertEquals(first, StableKey.channel("CCTV-1", "央视", "https://a.example/live"));
        assertNotEquals(first, StableKey.channel("CCTV-1", "卫视", "https://a.example/live"));
        assertNotEquals(first, StableKey.channel("CCTV-1", "央视", "https://b.example/live"));
    }

    @Test public void keysAreNamespacedAndVersioned() {
        assertTrue(StableKey.url("https://example.com/live").startsWith("v2:"));
        assertNotEquals(StableKey.url("https://example.com/live"),
                StableKey.channel("https://example.com/live", "", ""));
    }
}
