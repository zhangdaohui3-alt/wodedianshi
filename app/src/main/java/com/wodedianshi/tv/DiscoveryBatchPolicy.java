package com.wodedianshi.tv;

/**
 * Tracks progress inside an immutable discovery batch by position, not URL.
 * A playlist may legitimately list the same endpoint under several channel
 * identities, so URL-based resume keys can jump over unprocessed entries.
 */
public final class DiscoveryBatchPolicy {
    private DiscoveryBatchPolicy() {}

    public static int scannedForBatch(String fingerprint, String previousFingerprint,
            int previousScanned, int candidateCount) {
        if (candidateCount <= 0 || fingerprint == null
                || !fingerprint.equals(previousFingerprint)) return 0;
        return Math.max(0, Math.min(previousScanned, candidateCount));
    }

    public static int startIndex(int scanned, int candidateCount) {
        if (candidateCount <= 0 || scanned >= candidateCount) return 0;
        return Math.max(0, scanned);
    }

    public static int remaining(int scanned, int candidateCount) {
        return Math.max(0, candidateCount - Math.max(0, scanned));
    }
}
