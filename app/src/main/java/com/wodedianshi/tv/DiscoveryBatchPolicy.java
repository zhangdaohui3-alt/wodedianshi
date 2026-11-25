package com.wodedianshi.tv;

/**
 * Tracks progress inside an immutable discovery batch by position, not URL.
 * A playlist may legitimately list the same endpoint under several channel
 * identities, so URL-based resume keys can jump over unprocessed entries.
 */
public final class DiscoveryBatchPolicy {
    private DiscoveryBatchPolicy() {}

    // Fixed across idle/viewing transitions so partial page fingerprints stay
    // stable. Only the number of probes admitted per slice changes.
    static final int INITIAL_PAGE_SIZE=48;
    static int probeLimit(boolean initial,boolean viewing,int ordinaryLimit) {
        return initial?(viewing?1:INITIAL_PAGE_SIZE):ordinaryLimit;
    }

    /** Independent partial page checkpoints enable fair rotation. */
    public static String initialBatchPrefix(int feedIndex) {
        return "source_initial_page_"+feedIndex+"_";
    }

    /** A prepared index belongs to one exact body, not simply its URL. */
    public static boolean isPreparedInitialFeed(String preparedVersion,String currentVersion) {
        return currentVersion!=null&&!currentVersion.isEmpty()&&currentVersion.equals(preparedVersion);
    }

    /** Never apply an offset to a different or unidentified feed body. */
    public static int cursorForVersion(int cursor, String savedVersion, String currentVersion) {
        return cursor > 0 && currentVersion != null && !currentVersion.isEmpty()
                && currentVersion.equals(savedVersion) ? cursor : 0;
    }

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
