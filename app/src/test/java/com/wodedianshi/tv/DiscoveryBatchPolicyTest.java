package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DiscoveryBatchPolicyTest {
    @org.junit.Test public void independentlyResumesInterleavedFeedPages() {
        String first=DiscoveryBatchPolicy.initialBatchPrefix(0);
        String second=DiscoveryBatchPolicy.initialBatchPrefix(1);
        org.junit.Assert.assertNotEquals(first,second);
        java.util.Map<String,Integer> offsets=new java.util.HashMap<>();
        offsets.put(first+"scanned",7);
        offsets.put(second+"scanned",2);
        org.junit.Assert.assertEquals(7,DiscoveryBatchPolicy.scannedForBatch("a","a",offsets.get(first+"scanned"),24));
        org.junit.Assert.assertEquals(2,DiscoveryBatchPolicy.scannedForBatch("b","b",offsets.get(second+"scanned"),24));
        org.junit.Assert.assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("new-a","a",offsets.get(first+"scanned"),24));
        offsets.remove(first+"scanned");
        org.junit.Assert.assertEquals(Integer.valueOf(2),offsets.get(second+"scanned"));
    }

    @Test public void cursorResumesOnlyForIdenticalKnownFeedVersion() {
        org.junit.Assert.assertEquals(24, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a","feed:a"));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a","feed:b"));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"","feed:b"));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"",""));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(24,"feed:a",null));
        org.junit.Assert.assertEquals(0, DiscoveryBatchPolicy.cursorForVersion(-1,"feed:a","feed:a"));
    }

    @Test public void resumesByPositionEvenWhenSeveralChannelsShareTheSameUrl() {
        String fingerprint = "same-batch";
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                fingerprint, fingerprint, 2, 5);
        assertEquals(2, DiscoveryBatchPolicy.startIndex(scanned, 5));
        assertEquals(3, DiscoveryBatchPolicy.remaining(scanned, 5));
    }

    @Test public void duplicateEndpointDoesNotSkipUnscannedChannelAfterIt() {
        java.util.List<String> channelEndpoints = java.util.Arrays.asList(
                "https://shared.example/live.m3u8",
                "https://other.example/live.m3u8",
                "https://shared.example/live.m3u8",
                "https://last.example/live.m3u8");
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                "batch", "batch", 1, channelEndpoints.size());
        assertEquals("https://other.example/live.m3u8",
                channelEndpoints.get(DiscoveryBatchPolicy.startIndex(
                        scanned, channelEndpoints.size())));
    }

    @Test public void changedBatchResetsPositionInsteadOfReusingOldCursor() {
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                "new-batch", "old-batch", 4, 7);
        assertEquals(0, scanned);
        assertEquals(0, DiscoveryBatchPolicy.startIndex(scanned, 7));
        assertEquals(7, DiscoveryBatchPolicy.remaining(scanned, 7));
    }

    @Test public void clampsProgressAndHandlesAnExhaustedBatch() {
        int scanned = DiscoveryBatchPolicy.scannedForBatch(
                "batch", "batch", 99, 3);
        assertEquals(0, DiscoveryBatchPolicy.startIndex(scanned, 3));
        assertEquals(0, DiscoveryBatchPolicy.remaining(scanned, 3));
        assertEquals(0, DiscoveryBatchPolicy.scannedForBatch(
                "batch", "batch", 2, 0));
    }
}
