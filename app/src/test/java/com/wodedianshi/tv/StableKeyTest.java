package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class StableKeyTest {
    private static String originalEncoding(String input) throws Exception {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder("v2:");
        for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }

    @Test public void optimizedEncodingPreservesEveryPersistentKeyNamespace() throws Exception {
        String[] values={"", "央视地方台", "https://example.invalid/live?token=Ab%20C", "Aa", "BB", "\uD83D\uDCFA"};
        for(String v:values){
            assertEquals(originalEncoding("url\n"+v),StableKey.url(v));
            assertEquals(originalEncoding("channel-identity\n"+v),StableKey.channelIdentity(v));
            assertEquals(originalEncoding("channel\n"+v+"\n央视\nhttps://example.invalid/live"),
                    StableKey.channel(v,"央视","https://example.invalid/live"));
        }
        assertEquals(originalEncoding("url\n"),StableKey.url(null));
        assertEquals(originalEncoding("channel\n\n\n"),StableKey.channel(null,null,null));
    }

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

    @Test public void channelIdentityDoesNotChangeWhenPrimarySourceChanges() {
        String identity = "CCTV-1|cctv";
        assertEquals(StableKey.channelIdentity(identity), StableKey.channelIdentity(identity));
        assertNotEquals(StableKey.channelIdentity(identity),
                StableKey.channelIdentity("CCTV-5|cctv"));
    }

    @Test public void keysAreNamespacedAndVersioned() {
        assertTrue(StableKey.url("https://example.com/live").startsWith("v2:"));
        assertNotEquals(StableKey.url("https://example.com/live"),
                StableKey.channel("https://example.com/live", "", ""));
    }
}
