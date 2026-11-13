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
                variants.add(new Variant(new URL(new URL(finalManifestUrl), child).toString(),
                        bandwidth, width, height));
            }
        }
        Collections.sort(variants, new Comparator<Variant>() {
            @Override public int compare(Variant a, Variant b) {
                return Long.compare(b.bandwidth, a.bandwidth);
            }
        });
        if (variants.size() > MAX_VARIANTS) {
            return new ArrayList<>(variants.subList(0, MAX_VARIANTS));
        }
        return variants;
    }
}