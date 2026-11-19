package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

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
     * Fingerprints the set, not its ordering, so harmless playlist reordering
     * does not restart an in-progress cycle. Any added/removed URL starts a new
     * cycle and invalidates the old staged candidate.
     */
    static String fingerprint(List<String> urls) {
        TreeSet<String> unique = new TreeSet<>();
        if (urls != null) for (String url : urls) {
            if (url != null && !url.trim().isEmpty()) unique.add(StableKey.url(url));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : unique) {
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
