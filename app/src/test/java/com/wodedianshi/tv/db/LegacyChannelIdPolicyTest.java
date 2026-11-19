package com.wodedianshi.tv.db;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LegacyChannelIdPolicyTest {
    @Test
    public void sameIdentityAndOccurrenceProduceStableId() {
        String first = LegacyChannelIdPolicy.idFor("CCTV-1", "https://example.test/live.m3u8", 0);
        String second = LegacyChannelIdPolicy.idFor("CCTV-1", "https://example.test/live.m3u8", 0);
        assertEquals(first, second);
        assertTrue(first.startsWith("legacy:"));
    }

    @Test
    public void duplicateLegacyRowsReceiveDistinctIds() {
        String first = LegacyChannelIdPolicy.idFor("CCTV-1", "https://example.test/live.m3u8", 0);
        String second = LegacyChannelIdPolicy.idFor("CCTV-1", "https://example.test/live.m3u8", 1);
        assertNotEquals(first, second);
    }

    @Test
    public void differentChannelsReceiveDifferentIds() {
        assertNotEquals(
                LegacyChannelIdPolicy.idFor("CCTV-1", "https://example.test/live.m3u8", 0),
                LegacyChannelIdPolicy.idFor("CCTV-2", "https://example.test/live.m3u8", 0));
    }

    @Test
    public void verifiedTvgIdKeepsChannelIdWhenPrimarySourceChanges() {
        String firstKey = LegacyChannelIdPolicy.stableIdentityKey(
                "福建综合", "https://old.example/live.m3u8", "FJTV.FUZHOU");
        String secondKey = LegacyChannelIdPolicy.stableIdentityKey(
                "福建综合", "https://new.example/live.m3u8", " fjtv.fuzhou ");
        assertEquals(firstKey, secondKey);
        assertEquals(
                LegacyChannelIdPolicy.idForStableIdentity(firstKey, 0),
                LegacyChannelIdPolicy.idForStableIdentity(secondKey, 0));
    }

    @Test
    public void cctvAndSatelliteIdsDoNotDependOnSourceHost() {
        assertEquals(
                LegacyChannelIdPolicy.idForStableIdentity(
                        LegacyChannelIdPolicy.stableIdentityKey("CCTV-1", "https://a.test/live", ""), 0),
                LegacyChannelIdPolicy.idForStableIdentity(
                        LegacyChannelIdPolicy.stableIdentityKey("CCTV-1", "https://b.test/live", ""), 0));
        assertEquals(
                LegacyChannelIdPolicy.idForStableIdentity(
                        LegacyChannelIdPolicy.stableIdentityKey("湖南卫视", "https://a.test/live", ""), 0),
                LegacyChannelIdPolicy.idForStableIdentity(
                        LegacyChannelIdPolicy.stableIdentityKey("湖南卫视", "https://b.test/live", ""), 0));
    }

    @Test
    public void genericChannelWithoutTvgIdRemainsSourceScoped() {
        assertNotEquals(
                LegacyChannelIdPolicy.stableIdentityKey("福建综合", "https://a.test/live", ""),
                LegacyChannelIdPolicy.stableIdentityKey("福建综合", "https://b.test/live", ""));
    }

    @Test
    public void delimiterInNameCannotCollideWithDifferentNameAndUrl() {
        assertNotEquals(
                LegacyChannelIdPolicy.identityKey("A|B", "C"),
                LegacyChannelIdPolicy.identityKey("A", "B|C"));
    }

    @Test
    public void unicodeIdentityIsDeterministic() {
        assertEquals(
                LegacyChannelIdPolicy.idFor("中央电视台", "https://example.test/频道", 0),
                LegacyChannelIdPolicy.idFor("中央电视台", "https://example.test/频道", 0));
    }
}
