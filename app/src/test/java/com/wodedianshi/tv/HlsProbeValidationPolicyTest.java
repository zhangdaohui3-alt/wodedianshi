package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class HlsProbeValidationPolicyTest {
    @Test public void acceptsAValidSecondSegment() {
        assertTrue(HlsProbeValidationPolicy.acceptsSecondSegment(true));
    }

    @Test public void rejectsMissingOrInvalidSecondSegment() {
        assertFalse(HlsProbeValidationPolicy.acceptsSecondSegment(false));
    }
}
