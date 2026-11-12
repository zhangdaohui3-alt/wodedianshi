package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceQoeScoringPolicyTest {
    @Test public void oneOldFailureIsNormalizedByTotalAttempts() {
        double sparse = SourceQoeScoringPolicy.adjustment(2, 1, 1, 0, 1, 0, 0L, 0L, 0);
        double established = SourceQoeScoringPolicy.adjustment(20, 19, 19, 10, 1, 0, 0L, 0L, 0);
        assertTrue(established > sparse);
    }

    @Test public void bufferingPenaltyIsNormalizedByAttempts() {
        double frequent = SourceQoeScoringPolicy.adjustment(2, 2, 2, 1, 0, 1, 0L, 0L, 0);
        double occasional = SourceQoeScoringPolicy.adjustment(20, 20, 20, 10, 0, 1, 0L, 0L, 0);
        assertTrue(occasional > frequent);
    }

    @Test public void renderedFramesAndStablePlaybackRewardRealPlayback() {
        double readyOnly = SourceQoeScoringPolicy.adjustment(10, 10, 0, 0, 0, 0, 1000L, 0L, 0);
        double renderedAndStable = SourceQoeScoringPolicy.adjustment(10, 10, 10, 8, 0, 0, 1000L, 500L, 0);
        assertTrue(renderedAndStable > readyOnly);
    }

    @Test public void codecWarningsRemainSmallAndNeverBecomeFailures() {
        double clean = SourceQoeScoringPolicy.adjustment(4, 4, 4, 2, 0, 0, 1000L, 500L, 0);
        double warning = SourceQoeScoringPolicy.adjustment(4, 4, 4, 2, 0, 0, 1000L, 500L, 1);
        assertTrue(clean > warning);
        assertTrue(clean - warning <= 2.01d);
    }

    @Test public void invalidCountsAreBoundedAndEmptyHistoryIsNeutral() {
        assertEquals(0.0d, SourceQoeScoringPolicy.adjustment(0, 5, 5, 5, 5, 5, 0L, 0L, 0), 0.0d);
        double bounded = SourceQoeScoringPolicy.adjustment(2, 20, 20, 20, -1, -1, -1L, -1L, -1);
        double maximum = SourceQoeScoringPolicy.adjustment(2, 2, 2, 2, 0, 0, 0L, 0L, 0);
        assertEquals(maximum, bounded, 0.0d);
    }
}
