package com.wodedianshi.tv;

/** Acceptance rules for bounded HLS media-sequence preflight. */
public final class HlsProbeValidationPolicy {
    private HlsProbeValidationPolicy() {}

    /**
     * Once a playlist advertises a second media segment, it must also be
     * retrievable and pass container/decryption validation. This prevents a
     * stale first segment from making a dead live endpoint look healthy.
     */
    public static boolean acceptsSecondSegment(boolean secondSegmentValid) {
        return secondSegmentValid;
    }
}
