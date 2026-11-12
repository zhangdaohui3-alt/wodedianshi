package com.wodedianshi.tv;

import java.net.URI;
import java.util.Locale;

/**
 * Conservative media-container/protocol hinting shared by live and VOD.
 * A MIME hint selects a Media3 source parser; it does not prove decoder support.
 */
public final class MediaFormatPolicy {
    public static final String HLS = "application/x-mpegURL";
    public static final String DASH = "application/dash+xml";
    public static final String MPEG_TS = "video/mp2t";
    public static final String SMOOTH_STREAMING = "application/vnd.ms-sstr+xml";

    private MediaFormatPolicy() {}

    public static boolean isRtspUri(String address) {
        if (address == null || address.trim().isEmpty()) return false;
        try {
            String scheme = new URI(address.trim()).getScheme();
            return scheme != null && "rtsp".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }

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
        if (path.endsWith(".ism/manifest") || path.endsWith(".isml/manifest")) return SMOOTH_STREAMING;

        // Inspect only known format parameters. Never infer a parser from an
        // arbitrary token, signed query value, or unrelated filename fragment.
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
                if (kind.contains("smoothstreaming") || kind.contains("smooth-streaming")
                        || "ss".equals(kind)) return SMOOTH_STREAMING;
            }
        }
        // MP4, MKV, WebM, MOV, FLV and other progressive formats remain on
        // Media3 extractor sniffing; filename alone cannot prove codec support.
        return null;
    }
}
