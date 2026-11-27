package com.wodedianshi.tv;

/**
 * Decoder fallback is a renderer-level recovery mechanism, not a second player.
 * Media3 still prefers the platform's normal decoder selection; fallback lets
 * it try another available decoder if initialization of the selected one fails.
 */
final class DecoderPolicy {
    static final int MODE_SYSTEM_DEFAULT = 0;
    static final int MODE_COMPATIBILITY_FALLBACK = 1;
    static final int DEFAULT_MODE = MODE_COMPATIBILITY_FALLBACK;

    private DecoderPolicy() {}

    static boolean isDecoderFallbackEnabled(int mode) {
        return mode == MODE_COMPATIBILITY_FALLBACK;
    }
}
