package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class InitialFeedRotationPolicyTest {
    @Test public void transientFailureRotatesToNextFeed() {
        assertEquals(1, InitialFeedRotationPolicy.nextAfterAttempt(
                0, 21, false, false));
    }

    @Test public void largeFeedYieldsAfterEveryCompletedPage() {
        assertEquals(5, InitialFeedRotationPolicy.nextAfterAttempt(
                4, 21, true, true));
        assertEquals(6, InitialFeedRotationPolicy.nextAfterAttempt(
                5, 21, true, true));
    }

    @Test public void shortPageMovesToNextFeed() {
        assertEquals(5, InitialFeedRotationPolicy.nextAfterAttempt(
                4, 21, true, false));
    }

    @Test public void incompleteProbeBatchYieldsWithoutLosingItsOwnCheckpoint() {
        assertEquals(5, InitialFeedRotationPolicy.nextAfterProbeBatch(
                4, 5, 21, false));
        assertEquals(5, InitialFeedRotationPolicy.nextAfterProbeBatch(
                4, 5, 21, true));
        assertEquals(0, InitialFeedRotationPolicy.nextAfterProbeBatch(
                20, 0, 21, false));
        org.junit.Assert.assertNotEquals(DiscoveryBatchPolicy.initialBatchPrefix(4),
                DiscoveryBatchPolicy.initialBatchPrefix(5));
        assertEquals(12, DiscoveryBatchPolicy.scannedForBatch("page4", "page4", 12, 48));
        assertEquals(0, DiscoveryBatchPolicy.scannedForBatch("changed", "page4", 12, 48));
        boolean[] done={false,false,false,false};
        boolean[] attempted={false,false,false,false};
        int next=InitialFeedRotationPolicy.nextAfterProbeBatch(1,2,4,false);
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextFeed(next,done,attempted,2,3));
    }

    @Test public void wrapsAndNormalizesNegativeIndexes() {
        assertEquals(0, InitialFeedRotationPolicy.nextAfterAttempt(
                20, 21, false, false));
        assertEquals(20, InitialFeedRotationPolicy.normalizeStart(-1, 21));
        assertEquals(0, InitialFeedRotationPolicy.nextAfterProbeBatch(
                -1, 0, 21, false));
        assertEquals(0, InitialFeedRotationPolicy.nextAfterAttempt(0, 0, true, true));
    }
}
