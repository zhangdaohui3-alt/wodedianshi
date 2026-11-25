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

    /**
     * A 206 response can be capped by an origin or CDN below the requested range.
     * Its Content-Range total/end must therefore participate in completeness checks.
     * Malformed or missing range metadata is treated conservatively as incomplete.
     */
    public static boolean isIncompleteContentRange(String contentRange, int maxBytes) {
        if (contentRange == null || maxBytes <= 0) return true;
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$")
                .matcher(contentRange.trim());
        if (!match.matches()) return true;
        try {
            long start = Long.parseLong(match.group(1));
            long end = Long.parseLong(match.group(2));
            if (start != 0L || end < 0L || end < start) return true;
            String totalText = match.group(3);
            if ("*".equals(totalText)) return end + 1L < (long) maxBytes + 1L;
            long total = Long.parseLong(totalText);
            if (total <= end || total <= 0L) return true;
            return total > maxBytes || end + 1L < total;
        } catch (NumberFormatException invalidRange) {
            return true;
        }
    }
}
