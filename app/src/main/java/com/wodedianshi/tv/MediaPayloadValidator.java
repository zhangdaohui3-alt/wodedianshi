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
        if (isValidFlvHeader(data)) return true;
        return isValidAdtsHeader(data);
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

    private static boolean isValidAdtsHeader(byte[] data) {
        if (data == null || data.length < 7
                || (data[0] & 0xFF) != 0xFF
                || ((data[1] & 0xF0) != 0xF0)
                || ((data[1] & 0x06) != 0)) return false;
        int samplingFrequencyIndex = (data[2] & 0x3C) >>> 2;
        // ADTS indices 13–15 are reserved or forbidden.
        if (samplingFrequencyIndex > 12) return false;
        boolean protectionAbsent = (data[1] & 0x01) != 0;
        int headerLength = protectionAbsent ? 7 : 9;
        if (data.length < headerLength) return false;
        int frameLength = ((data[3] & 0x03) << 11)
                | ((data[4] & 0xFF) << 3)
                | ((data[5] & 0xE0) >>> 5);
        return frameLength >= headerLength;
    }

    private static boolean isValidFlvHeader(byte[] data) {
        if (data == null || data.length < 9
                || data[0] != 'F' || data[1] != 'L' || data[2] != 'V'
                || (data[3] & 0xFF) != 1) return false;
        // Only audio (bit 2) and video (bit 0) flags are defined.
        if (((data[4] & 0xFF) & 0xFA) != 0) return false;
        long dataOffset = uint32(data, 5);
        return dataOffset >= 9L && dataOffset <= data.length;
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
