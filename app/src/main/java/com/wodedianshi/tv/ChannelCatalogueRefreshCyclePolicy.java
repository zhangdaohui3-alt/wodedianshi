package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Pure policy for deciding when a multi-run source-discovery cycle is complete. */
final class ChannelCatalogueRefreshCyclePolicy {
    private ChannelCatalogueRefreshCyclePolicy() {}

    static boolean isComplete(int startIndex, int scannedCount, int candidateCount,
                              boolean requiredRegionalFeedAvailable) {
        return requiredRegionalFeedAvailable
                && candidateCount > 0
                && startIndex >= 0
                && startIndex < candidateCount
                && scannedCount > 0
                && scannedCount <= candidateCount
                && startIndex + scannedCount >= candidateCount;
    }

    /**
     * Fingerprints the ordered scan list. The rotating cursor is positional, so
     * a playlist reorder must restart the cycle rather than risk skipping entries.
     */
    static String fingerprint(List<String> urls) {
        ArrayList<String> ordered = new ArrayList<>();
        if (urls != null) for (String url : urls) {
            if (url != null && !url.trim().isEmpty()) ordered.add(StableKey.url(url));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : ordered) {
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
