package com.wodedianshi.tv;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, deterministic ordering of playable HLS media renditions. */
public final class HlsVariantPolicy {
    private static final int MAX_VARIANTS = 8;
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|[, :])BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION = Pattern.compile("(?:^|[, :])RESOLUTION=(\\d+)x(\\d+)");

    private HlsVariantPolicy() {}

    public static final class Variant {
        public final String url;
        public final long bandwidth;
        public final int width;
        public final int height;

        public Variant(String url, long bandwidth, int width, int height) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
        }
    }

    /** True only when the payload begins with the HLS playlist marker (BOM/space tolerant). */
    public static boolean isPlaylist(String manifest) {
        return manifest != null && manifest.replace(String.valueOf((char)0xFEFF), "").trim().startsWith("#EXTM3U");
    }

    // Byte-first classification matches the existing UTF-8/BOM/trim marker
    // rule without allocating a decoded string for every binary media sample.
    private static final String PLAYLIST_MARKER="#EXTM3U";
    static boolean hasPlaylistMarker(byte[] data) {
        if(data==null)return false;
        int matched=0;
        for(int i=0;i<data.length;){
            int value=data[i]&0xff;
            if(value==0xef&&i+2<data.length&&(data[i+1]&0xff)==0xbb&&(data[i+2]&0xff)==0xbf){
                i+=3;continue;
            }
            i++;
            if(matched==0&&value<=0x20)continue;
            if(value!=PLAYLIST_MARKER.charAt(matched))return false;
            if(++matched==PLAYLIST_MARKER.length())return true;
        }
        return false;
    }

    /** Decode once after classification. Remove a leading UTF-8 BOM only,
     * not characters within URI lines or signed query strings. */
    static String decodePlaylist(byte[] data) {
        String text=new String(data,java.nio.charset.StandardCharsets.UTF_8);
        return !text.isEmpty()&&text.charAt(0)==0xFEFF?text.substring(1):text;
    }

    public static ArrayList<Variant> parse(String manifest, String finalManifestUrl) throws Exception {
        ArrayList<Variant> variants = new ArrayList<>();
        if (manifest == null || finalManifestUrl == null) return variants;
        String[] lines = manifest.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            long bandwidth = 0L;
            int width = 0, height = 0;
            Matcher bm = BANDWIDTH.matcher(line.substring("#EXT-X-STREAM-INF:".length()));
            if (bm.find()) {
                try { bandwidth = Long.parseLong(bm.group(1)); } catch (NumberFormatException ignored) {}
            }
            Matcher rm = RESOLUTION.matcher(line.substring("#EXT-X-STREAM-INF:".length()));
            if (rm.find()) {
                try { width = Integer.parseInt(rm.group(1)); height = Integer.parseInt(rm.group(2)); }
                catch (NumberFormatException ignored) {}
            }

            // RFC 8216 places the URI on the next non-empty line. Do not scan
            // past another tag: doing so can bind a malformed rendition to the
            // following rendition's URI and create duplicate/wrong fallbacks.
            String child = "";
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty()) continue;
                if (candidate.startsWith("#")) break;
                child = candidate;
                break;
            }
            if (!child.isEmpty()) {
                // One malformed/unsupported child must not reject healthy
                // siblings. Resolve relative to the actual redirected master.
                try {
                    URL resolved = new URL(new URL(finalManifestUrl), child);
                    if (("http".equalsIgnoreCase(resolved.getProtocol())
                            || "https".equalsIgnoreCase(resolved.getProtocol()))
                            && !resolved.getHost().isEmpty()) {
                        variants.add(new Variant(resolved.toString(), bandwidth, width, height));
                    }
                } catch (java.net.MalformedURLException ignored) {
                    // This child cannot be probed; retain the other renditions.
                }
            }
        }
        Collections.sort(variants, new Comparator<Variant>() {
            @Override public int compare(Variant a, Variant b) {
                return Long.compare(b.bandwidth, a.bandwidth);
            }
        });
        // Apply the attempt cap to distinct resolved endpoints, not repeated
        // manifest declarations. Keep the existing highest-bandwidth order.
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        ArrayList<Variant> distinct = new ArrayList<>();
        for (Variant variant : variants) {
            // Match the existing exact stream endpoint identity, including
            // case-insensitive host and equivalent default ports. Preserve
            // the original selected URL and every distinct path/query.
            if (seen.add(SourceSelectionPolicy.endpointKey(variant.url))) distinct.add(variant);
            if (distinct.size() >= MAX_VARIANTS) break;
        }
        return distinct;
    }
}