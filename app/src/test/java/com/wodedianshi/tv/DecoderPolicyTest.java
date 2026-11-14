package com.wodedianshi.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DecoderPolicyTest {
    @Test public void compatibilityFallbackIsEnabledByDefault() {
        assertTrue(DecoderPolicy.isDecoderFallbackEnabled(DecoderPolicy.DEFAULT_MODE));
    }

    @Test public void systemDefaultModeDoesNotEnableRendererFallback() {
        assertFalse(DecoderPolicy.isDecoderFallbackEnabled(DecoderPolicy.MODE_SYSTEM_DEFAULT));
    }

    @Test public void compatibilityModeEnablesRendererFallback() {
        assertTrue(DecoderPolicy.isDecoderFallbackEnabled(DecoderPolicy.MODE_COMPATIBILITY_FALLBACK));
    }
}
