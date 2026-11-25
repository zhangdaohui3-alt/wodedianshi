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

    @Test public void usesShortCooldownForTransientNetworkAndServerErrors() {
        assertEquals(30000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", 0));
        assertEquals(30000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 0));
        assertEquals(30000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_BAD_HTTP_STATUS", 503));
    }

    @Test public void usesLongCooldownForDeterministicMissingOrIncompatibleSources() {
        assertEquals(600000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_BAD_HTTP_STATUS", 404));
        assertEquals(600000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_BAD_HTTP_STATUS", 410));
        assertEquals(600000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_DECODING_FAILED", 0));
        assertEquals(600000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", 0));
    }

    @Test public void appliesRateLimitAndConservativeFallbackCooldowns() {
        assertEquals(60000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_BAD_HTTP_STATUS", 429));
        assertEquals(120000L, PlaybackErrorPolicy.cooldownMs(
                "ERROR_CODE_IO_BAD_HTTP_STATUS", 403));
        assertEquals(120000L, PlaybackErrorPolicy.cooldownMs(null, 0));
    }

    @Test public void unknownAndMissingErrorsRemainConservative() {
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry(null));
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry(""));
        assertFalse(PlaybackErrorPolicy.shouldSkipSameUrlVlcRetry("UNKNOWN_ERROR"));
    }
}
