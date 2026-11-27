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

    @Test public void distinguishesTruncatedSamplesFromCompleteResources() {
        assertFalse(HlsProbeValidationPolicy.isCompleteSample(true));
        assertTrue(HlsProbeValidationPolicy.isCompleteSample(false));
    }

    @Test public void detectsOnlyBytesBeyondTheSampleLimitAsTruncation() {
        assertFalse(HlsProbeValidationPolicy.isTruncatedSample(32767,32768));
        assertFalse(HlsProbeValidationPolicy.isTruncatedSample(32768,32768));
        assertTrue(HlsProbeValidationPolicy.isTruncatedSample(32769,32768));
        assertFalse(HlsProbeValidationPolicy.isTruncatedSample(1,0));
    }

    @Test public void detectsOriginsThatReturnAShorterByteRangeThanRequested() {
        assertFalse(HlsProbeValidationPolicy.isIncompleteContentRange("bytes 0-32767/32768",32768));
        assertTrue(HlsProbeValidationPolicy.isIncompleteContentRange("bytes 0-16383/100000",32768));
        assertTrue(HlsProbeValidationPolicy.isIncompleteContentRange("bytes 0-16383/*",32768));
        assertTrue(HlsProbeValidationPolicy.isIncompleteContentRange("bytes 16-32783/100000",32768));
        assertTrue(HlsProbeValidationPolicy.isIncompleteContentRange("invalid",32768));
        assertTrue(HlsProbeValidationPolicy.isIncompleteContentRange(null,32768));
    }

    @Test public void prefixRangeRejectsWrongOffsetAndInconsistentBody(){
        assertNull(HlsProbeValidationPolicy.parsePrefixRange("bytes 16-31/100"));
        assertNull(HlsProbeValidationPolicy.parsePrefixRange("bytes 0-100/100"));
        assertNull(HlsProbeValidationPolicy.parsePrefixRange("bytes 0-9223372036854775807/*"));
        long[] range=HlsProbeValidationPolicy.parsePrefixRange("bytes 0-99/100");
        assertArrayEquals(new long[]{100L,100L},range);
        assertTrue(HlsProbeValidationPolicy.prefixBodyMatches(range,100,32768));
        assertFalse(HlsProbeValidationPolicy.prefixBodyMatches(range,99,32768));
        assertFalse(HlsProbeValidationPolicy.prefixBodyMatches(range,101,32768));
        assertTrue(HlsProbeValidationPolicy.prefixBodyMatches(range,33,32));
        assertTrue(HlsProbeValidationPolicy.isIncompleteContentRange("bytes 0-31/*",32));
    }
}
