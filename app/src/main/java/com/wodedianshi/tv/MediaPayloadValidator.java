package com.wodedianshi.tv;

/**
 * Conservative, dependency-free checks for sampled IPTV media payloads.
 * This is only a transport/container sanity check, not proof that a decoder
 * can render the stream or produce audible audio.
 */
public final class MediaPayloadValidator {
    private MediaPayloadValidator() {}

    public static boolean looksLikeHlsSegment(byte[] data) {
        if (data == null || data.length < 8) return false;
        int maxOffset = Math.min(187, data.length - 1);
        for (int offset = 0; offset <= maxOffset; offset++) {
            if ((data[offset] & 0xFF) == 0x47
                    && offset + 188 < data.length
                    && (data[offset + 188] & 0xFF) == 0x47) {
                return true;
            }
        }
        return hasIsoBmffBox(data);
    }

    /**
     * Walks top-level ISO-BMFF box headers rather than accepting the words
     * "moof"/"mdat" anywhere in arbitrary bytes (for example, an HTML error
     * page). Sample data may be range-truncated, so a valid declared box may
     * extend beyond the sample; its header must still be structurally valid.
     */
    public static boolean hasIsoBmffBox(byte[] data) {
        if (data == null || data.length < 8) return false;
        int offset = 0;
        int examined = 0;
        while (offset >= 0 && offset + 8 <= data.length && examined++ < 8) {
            long size = uint32(data, offset);
            String type = fourCc(data, offset + 4);
            int headerSize = 8;
            if (size == 1L) {
                if (offset + 16 > data.length) return false;
                size = uint64(data, offset + 8);
                headerSize = 16;
            } else if (size == 0L) {
                size = data.length - offset;
            }
            if (size < headerSize) return false;
            if (isMediaBox(type)) return true;
            if (size > Integer.MAX_VALUE || size > data.length - offset) return false;
            offset += (int) size;
        }
        return false;
    }

    public static boolean looksLikeMediaPayload(byte[] data) {
        if (data == null || data.length < 8) return false;
        if (looksLikeHlsSegment(data)) return true;
        if (data[0] == 'F' && data[1] == 'L' && data[2] == 'V') return true;
        return (data[0] & 0xFF) == 0xFF && ((data[1] & 0xF6) == 0xF0);
    }

    /**
     * Validates the sampled bytes even when the URL has a familiar media suffix.
     * The address is accepted for call-site clarity/diagnostics only: a .ts or
     * .mp4 extension must never turn an HTML/error payload into a valid source.
     */
    public static boolean looksLikeMediaPayload(byte[] data, String address) {
        return looksLikeMediaPayload(data);
    }

    private static boolean isMediaBox(String type) {
        return "ftyp".equals(type) || "styp".equals(type)
                || "moof".equals(type) || "mdat".equals(type)
                || "sidx".equals(type);
    }

    private static long uint32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (long) (data[offset + 3] & 0xFF);
    }

    private static long uint64(byte[] data, int offset) {
        long high = uint32(data, offset);
        long low = uint32(data, offset + 4);
        if (high > 0x7FFFFFFFL) return Long.MAX_VALUE;
        return (high << 32) | low;
    }

    private static String fourCc(byte[] data, int offset) {
        return new String(data, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
