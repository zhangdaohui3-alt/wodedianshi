package com.wodedianshi.tv.db;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LegacyChannelIdPolicyTest {
    @Test
    public void sameIdentityAndOccurrenceProduceStableId() {
        String first = LegacyChannelIdPolicy.idFor("CCTV-1|https://example.test/live.m3u8", 0);
        String second = LegacyChannelIdPolicy.idFor("CCTV-1|https://example.test/live.m3u8", 0);
        assertEquals(first, second);
        assertTrue(first.startsWith("legacy:"));
    }

    @Test
    public void duplicateLegacyRowsReceiveDistinctIds() {
        String first = LegacyChannelIdPolicy.idFor("CCTV-1|https://example.test/live.m3u8", 0);
        String second = LegacyChannelIdPolicy.idFor("CCTV-1|https://example.test/live.m3u8", 1);
        assertNotEquals(first, second);
    }

    @Test
    public void differentChannelsReceiveDifferentIds() {
        assertNotEquals(
                LegacyChannelIdPolicy.idFor("CCTV-1|https://example.test/live.m3u8", 0),
                LegacyChannelIdPolicy.idFor("CCTV-2|https://example.test/live.m3u8", 0));
    }

    @Test
    public void unicodeIdentityIsDeterministic() {
        assertEquals(
                LegacyChannelIdPolicy.idFor("中央电视台|https://example.test/频道", 0),
                LegacyChannelIdPolicy.idFor("中央电视台|https://example.test/频道", 0));
    }
}
