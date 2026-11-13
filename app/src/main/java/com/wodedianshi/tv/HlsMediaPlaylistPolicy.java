package com.wodedianshi.tv;

import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts bounded media/init resources from ordinary and low-latency HLS media playlists. */
public final class HlsMediaPlaylistPolicy {
    private static final int MAX_MEDIA_SEGMENTS = 4;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?:^|,)\\s*URI=\"([^\"]+)\"");

    private HlsMediaPlaylistPolicy() {}

    public static final class Resources {
        public final String initializationUrl;
        public final boolean unsupportedInitializationByteRange;
        public final ArrayList<String> mediaUrls;
        Resources(String initializationUrl, boolean unsupportedInitializationByteRange, ArrayList<String> mediaUrls) {
            this.initializationUrl = initializationUrl;
            this.unsupportedInitializationByteRange = unsupportedInitializationByteRange;
            this.mediaUrls = mediaUrls;
        }
    }

    public static Resources parse(String playlist, String finalPlaylistUrl) throws Exception {
        ArrayList<String> media = new ArrayList<>();
        String initialization = null;
        boolean unsupportedInitializationByteRange = false;
        if (playlist == null || finalPlaylistUrl == null) return new Resources(null, false, media);
        String[] lines = playlist.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length && media.size() < MAX_MEDIA_SEGMENTS; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-MAP:")) {
                String attributes = line.substring("#EXT-X-MAP:".length());
                // A MAP byte range may begin at a non-zero offset in a shared file.
                // Until Range-aware fetching exists, reject this variant rather than
                // validating unrelated bytes at offset zero.
                if (attributes.matches(".*(?:^|,)\\s*BYTERANGE\\s*=.*")) {
                    unsupportedInitializationByteRange = true;
                    initialization = null;
                } else {
                    initialization = resolve(uriAttribute(attributes), finalPlaylistUrl);
                }
            } else if (line.startsWith("#EXT-X-PART:")) {
                String attrs = line.substring("#EXT-X-PART:".length());
                // Byte-range parts need Range-aware fetching; do not falsely probe
                // the beginning of the shared resource as if it were the part.
                if (!attrs.contains("BYTERANGE=")) {
                    String uri = uriAttribute(attrs);
                    if (uri != null) addUnique(media, resolve(uri, finalPlaylistUrl));
                }
            } else if (line.startsWith("#EXTINF:")) {
                for (int j = i + 1; j < lines.length; j++) {
                    String candidate = lines[j].trim();
                    if (candidate.isEmpty()) continue;
                    if (candidate.startsWith("#")) break;
                    addUnique(media, resolve(candidate, finalPlaylistUrl));
                    i = j;
                    break;
                }
            }
        }
        return new Resources(initialization, unsupportedInitializationByteRange, media);
    }

    private static String uriAttribute(String attributes) {
        Matcher matcher = URI_ATTRIBUTE.matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String resolve(String uri, String base) throws Exception {
        return uri == null || uri.isEmpty() ? null : new URL(new URL(base), uri).toString();
    }

    private static void addUnique(ArrayList<String> values, String value) {
        if (value != null && !values.contains(value) && values.size() < MAX_MEDIA_SEGMENTS) values.add(value);
    }
}
