package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class InitialFeedFailurePolicyTest {
    @Test public void temporaryFailureDoesNotBlockFullBuildForever() {
        assertFalse(InitialFeedFailurePolicy.isTerminalUnavailable("timed out",1));
        assertFalse(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 429",2));
        assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 429",3));
        assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("timed out",3));
    }

    @Test public void removedPublicFeedsAreImmediatelyMarkedUnavailable() {
        assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 404",1));
        assertTrue(InitialFeedFailurePolicy.isTerminalUnavailable("HTTP 410",1));
    }

    @Test public void internalBatchBudgetMustNotBeReportedAsDeadFeed() {
        assertFalse(InitialFeedFailurePolicy.isTerminalUnavailable(
            "本轮候选容量已满，保留目录游标并在下轮继续",3));
    }

    @Test public void retryCountsAreClampedAndPreserveProgress() {
        assertEquals(1,InitialFeedFailurePolicy.nextAttempts(0));
        assertEquals(2,InitialFeedFailurePolicy.nextAttempts(1));
        assertEquals(3,InitialFeedFailurePolicy.nextAttempts(2));
        assertEquals(3,InitialFeedFailurePolicy.nextAttempts(Integer.MAX_VALUE));
    }
}
