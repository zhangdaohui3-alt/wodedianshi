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
        if (looksLikeFlv(data) || looksLikeAdts(data) || looksLikeEbml(data)) return true;
        return looksLikeMpegProgramStream(data) || looksLikeAvi(data) || looksLikeMp3(data);
    }

    /**
     * Validates a sampled response against its declared container extension.
     * A successful HTTP response or a familiar filename is not sufficient:
     * HTML error pages and proxy/login pages must never enter the source pool.
     * Unknown extensions fall back to conservative signature detection.
     */
    public static boolean looksLikePayloadForAddress(byte[] data, String address) {
        if (data == null || data.length < 8) return false;
        String path = address == null ? "" : address;
        try {
            java.net.URI uri = java.net.URI.create(path.trim());
            path = uri.getPath() == null ? "" : uri.getPath();
        } catch (RuntimeException ignored) {
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            int fragment = path.indexOf('#');
            if (fragment >= 0) path = path.substring(0, fragment);
        }
        path = path.toLowerCase(java.util.Locale.ROOT);
        if (endsWithAny(path, ".ts", ".m2ts", ".mts")) return looksLikeHlsSegment(data);
        if (endsWithAny(path, ".mp4", ".m4v", ".mov", ".m4a", ".m4s", ".ismv")) {
            return hasIsoBmffBox(data);
        }
        if (path.endsWith(".flv")) return looksLikeFlv(data);
        if (path.endsWith(".aac")) return looksLikeAdts(data);
        if (endsWithAny(path, ".mkv", ".webm")) return looksLikeEbml(data);
        if (path.endsWith(".mp3")) return looksLikeMp3(data);
        if (endsWithAny(path, ".mpg", ".mpeg", ".vob")) return looksLikeMpegProgramStream(data);
        if (path.endsWith(".avi")) return looksLikeAvi(data);
        return looksLikeMediaPayload(data);
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
        return false;
    }

    private static boolean looksLikeFlv(byte[] data) {
        return data.length >= 9 && data[0] == 'F' && data[1] == 'L' && data[2] == 'V'
                && data[3] == 1;
    }

    private static boolean looksLikeAdts(byte[] data) {
        return data.length >= 8 && (data[0] & 0xFF) == 0xFF
                && ((data[1] & 0xF6) == 0xF0);
    }

    private static boolean looksLikeEbml(byte[] data) {
        return data.length >= 4 && (data[0] & 0xFF) == 0x1A
                && (data[1] & 0xFF) == 0x45 && (data[2] & 0xFF) == 0xDF
                && (data[3] & 0xFF) == 0xA3;
    }

    private static boolean looksLikeMpegProgramStream(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0
                && data[2] == 1 && (data[3] & 0xFF) == 0xBA;
    }

    private static boolean looksLikeAvi(byte[] data) {
        return data.length >= 12 && data[0] == 'R' && data[1] == 'I'
                && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'A' && data[9] == 'V' && data[10] == 'I' && data[11] == ' ';
    }

    private static boolean looksLikeMp3(byte[] data) {
        if (data.length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') return true;
        return data.length >= 2 && (data[0] & 0xFF) == 0xFF
                && ((data[1] & 0xE0) == 0xE0) && ((data[1] & 0x06) != 0x06);
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
