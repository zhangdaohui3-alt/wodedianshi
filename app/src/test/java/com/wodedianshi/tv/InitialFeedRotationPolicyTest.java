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

    @Test public void incompleteProbeBatchMustResumeSameFeed() {
        assertEquals(4, InitialFeedRotationPolicy.nextAfterProbeBatch(
                4, 5, 21, false));
        assertEquals(5, InitialFeedRotationPolicy.nextAfterProbeBatch(
                4, 5, 21, true));
    }

    @Test public void wrapsAndNormalizesNegativeIndexes() {
        assertEquals(0, InitialFeedRotationPolicy.nextAfterAttempt(
                20, 21, false, false));
        assertEquals(20, InitialFeedRotationPolicy.normalizeStart(-1, 21));
        assertEquals(20, InitialFeedRotationPolicy.nextAfterProbeBatch(
                -1, 0, 21, false));
        assertEquals(0, InitialFeedRotationPolicy.nextAfterAttempt(0, 0, true, true));
    }
}
