package com.wodedianshi.tv;

import java.net.URI;
import java.util.Locale;

/**
 * Keeps catalogue metadata separate from playable media.
 * A webpage or a metadata-only search result must never be sent to Media3.
 */
public final class VodPlaybackEligibilityPolicy {
    private VodPlaybackEligibilityPolicy() {}

    public static boolean canPlay(String identifier, String url) {
        if (identifier != null && identifier.trim().toLowerCase(Locale.ROOT).startsWith("meta:")) {
            return false;
        }
        if (url == null || url.trim().isEmpty()) return false;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                return false;
            }
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }
}
