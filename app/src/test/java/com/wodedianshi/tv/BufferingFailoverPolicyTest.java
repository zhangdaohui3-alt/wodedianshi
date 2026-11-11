package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class BufferingFailoverPolicyTest {
    @Test public void firstStartupIsBoundedByFifteenSeconds() {
        assertEquals(15, BufferingFailoverPolicy.timeoutSeconds(false, 0, 20));
        assertEquals(12, BufferingFailoverPolicy.timeoutSeconds(false, 0, 12));
    }

    @Test public void failedSourceBackupGetsAtMostSixSeconds() {
        assertEquals(6, BufferingFailoverPolicy.timeoutSeconds(false, 1, 20));
        assertEquals(5, BufferingFailoverPolicy.timeoutSeconds(false, 2, 5));
    }

    @Test public void lowUserSelectedTimeoutIsPreserved() {
        assertEquals(3, BufferingFailoverPolicy.timeoutSeconds(false, 0, 3));
        assertEquals(3, BufferingFailoverPolicy.timeoutSeconds(false, 1, 3));
    }

    @Test public void midstreamRebufferUsesUserSetting() {
        assertEquals(20, BufferingFailoverPolicy.timeoutSeconds(true, 0, 20));
        assertEquals(8, BufferingFailoverPolicy.timeoutSeconds(true, 2, 8));
    }

    @Test public void zeroDisablesAutomaticSwitching() {
        assertEquals(0, BufferingFailoverPolicy.timeoutSeconds(false, 0, 0));
        assertEquals(0, BufferingFailoverPolicy.timeoutSeconds(false, 3, 0));
    }

    @Test public void subsequentChannelAttemptsAreCappedAtFiveSeconds() {
        assertEquals(5, BufferingFailoverPolicy.timeoutSeconds(false, 0, 1, 12));
        assertEquals(3, BufferingFailoverPolicy.timeoutSeconds(false, 0, 2, 3));
    }

    @Test public void sameChannelBackupKeepsSixSecondCap() {
        assertEquals(6, BufferingFailoverPolicy.timeoutSeconds(false, 1, 0, 12));
    }
}
