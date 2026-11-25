package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DiscoveryBatchPolicyTest {
    @org.junit.Test public void acquisitionMarkerRequiresExactNonemptyBodyVersion() {
        org.junit.Assert.assertTrue(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:hash","feed:hash"));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:old","feed:new"));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("",""));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed(null,"feed:hash"));
        org.junit.Assert.assertFalse(DiscoveryBatchPolicy.isPreparedInitialFeed("feed:hash",null));
    }

    @org.junit.Test public void incrementalStagingRequiresEveryAppendedRowAndValidPositions() {
        com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(5,5,java.util.Collections.emptySet());
        com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(5,7,
            new java.util.HashSet<>(java.util.Arrays.asList(0,5,6)));
        int[][] invalid={{5,4,0},{5,7,5},{5,5,5},{5,5,-1}};
        for(int[] item:invalid){
            try{
                com.wodedianshi.tv.db.RoomCatalogueBuildStore.validateTouchedRows(
                    item[0],item[1],java.util.Collections.singleton(item[2]));
                org.junit.Assert.fail("Invalid staging mutation accepted");
            }catch(IllegalArgumentException expected){}
        }
    }

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

    @Test public void largerInitialPageKeepsOrdinaryAndViewingProbeLimits() {
        assertEquals(48,DiscoveryBatchPolicy.INITIAL_PAGE_SIZE);
        assertEquals(48,DiscoveryBatchPolicy.probeLimit(true,false,24));
        assertEquals(1,DiscoveryBatchPolicy.probeLimit(true,true,24));
        assertEquals(24,DiscoveryBatchPolicy.probeLimit(false,false,24));
        assertEquals(24,DiscoveryBatchPolicy.probeLimit(false,true,24));
    }

    @Test public void expandedPageRechecksPartialOldPageWithoutSkippingRawCursor() {
        assertEquals(240,DiscoveryBatchPolicy.cursorForVersion(240,"feed:a","feed:a"));
        assertEquals(0,DiscoveryBatchPolicy.scannedForBatch("page:48","page:24",18,48));
        assertEquals(18,DiscoveryBatchPolicy.scannedForBatch("page:48","page:48",18,48));
        assertEquals(30,DiscoveryBatchPolicy.remaining(18,48));
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
