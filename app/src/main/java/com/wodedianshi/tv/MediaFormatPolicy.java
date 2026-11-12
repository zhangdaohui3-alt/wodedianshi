package com.wodedianshi.tv;

import java.util.Locale;

/**
 * Conservative media-container/protocol hinting shared by live and VOD.
 * A MIME hint selects the correct Media3 source parser; it does not claim that
 * the device has a decoder for the audio/video codecs inside the stream.
 */
public final class MediaFormatPolicy {
    public static final String HLS = "application/x-mpegURL";
    public static final String DASH = "application/dash+xml";
    public static final String MPEG_TS = "video/mp2t";

    private MediaFormatPolicy() {}

    public static String inferMimeType(String address) {
        if (address == null || address.trim().isEmpty()) return null;
        String value = address.trim().toLowerCase(Locale.ROOT);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        int query = value.indexOf('?');
        String path = query >= 0 ? value.substring(0, query) : value;
        if (path.endsWith(".m3u8")) return HLS;
        if (path.endsWith(".mpd")) return DASH;
        if (path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts")) return MPEG_TS;

        // Some providers hide the extension and expose the stream type as a
        // query parameter. Inspect only recognized parameter names; never
        // guess from an arbitrary token or filename embedded in an auth token.
        if (query >= 0 && query + 1 < value.length()) {
            String[] parameters = value.substring(query + 1).split("&");
            for (String parameter : parameters) {
                int equals = parameter.indexOf('=');
                if (equals <= 0) continue;
                String key = parameter.substring(0, equals).trim();
                if (!"type".equals(key) && !"format".equals(key)
                        && !"ext".equals(key) && !"container".equals(key)) continue;
                String kind = parameter.substring(equals + 1).trim();
                if (kind.contains("mpegurl") || kind.contains("m3u8") || "hls".equals(kind)) return HLS;
                if (kind.contains("dash") || kind.contains("mpd")) return DASH;
                if (kind.contains("mp2t") || kind.contains("mpegts")
                        || kind.contains("m2ts") || "ts".equals(kind)) return MPEG_TS;
            }
        }
        // MP4, MKV, WebM, MOV, FLV and other progressive formats are left to
        // Media3's normal extractor sniffing rather than forced into a parser.
        return null;
    }
}
