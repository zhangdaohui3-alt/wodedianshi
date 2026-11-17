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

    /** A capped sample is not a complete resource, even when its byte count equals the cap. */
    public static boolean isCompleteSample(boolean truncated) {
        return !truncated;
    }

    /** The extra sentinel byte is the proof that the original resource exceeded the retained cap. */
    public static boolean isTruncatedSample(int receivedBytes, int maxBytes) {
        return maxBytes > 0 && receivedBytes > maxBytes;
    }
}
