package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceSuccessTimestampPolicyTest {
    @Test public void renderedFirstFrameCountsAsVerifiedSuccess() {
        assertEquals(120L, SourceSuccessTimestampPolicy.latestVerifiedSuccess(120L, 0L));
    }

    @Test public void stablePlaybackTimestampWinsWhenNewer() {
        assertEquals(240L, SourceSuccessTimestampPolicy.latestVerifiedSuccess(120L, 240L));
    }

    @Test public void readyOnlyHistoryDoesNotCreateVerifiedSuccess() {
        assertEquals(0L, SourceSuccessTimestampPolicy.latestVerifiedSuccess(0L, 0L));
    }

    @Test public void negativeTimestampsAreIgnored() {
        assertEquals(0L, SourceSuccessTimestampPolicy.latestVerifiedSuccess(-10L, -1L));
    }
}
