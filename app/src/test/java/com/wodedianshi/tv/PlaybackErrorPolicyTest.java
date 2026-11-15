package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackErrorPolicyTest {
    @Test public void skipsSameUrlRetryForConnectionFailuresAndTimeouts() {
        assertTrue(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry(
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"));
        assertTrue(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry(
                "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"));
    }

    @Test public void retainsLibVlcFallbackForHttpAndDecoderCompatibilityErrors() {
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry("ERROR_CODE_IO_BAD_HTTP_STATUS"));
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry("ERROR_CODE_DECODING_FAILED"));
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"));
    }

    @Test public void unknownAndMissingErrorsRemainConservative() {
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry(null));
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry(""));
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry("UNKNOWN_ERROR"));
    }
}
