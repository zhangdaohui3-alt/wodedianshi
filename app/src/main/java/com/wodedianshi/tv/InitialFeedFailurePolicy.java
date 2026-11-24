package com.wodedianshi.tv;

/**
 * Bounds first-build discovery attempts per independently configured feed.
 * A terminal unavailable feed is an inspected outcome, NOT a successful feed.
 * It remains visible in progress diagnostics for future manual/weekly retry.
 */
public final class InitialFeedFailurePolicy {
    private InitialFeedFailurePolicy() {}
    public static final int MAX_FAILURE_ATTEMPTS = 3;

    public static int nextAttempts(int previous) {
        return Math.min(MAX_FAILURE_ATTEMPTS, Math.max(0, previous) + 1);
    }

    public static boolean isTerminalUnavailable(String message, int attempts) {
        String reason = message == null ? "" : message;
        // These represent our own scanning limits, not an unavailable provider.
        // Retrying the same full page later is safer than skipping it forever.
        if (reason.contains("本轮候选容量已满") || reason.contains("source candidate capacity"))
            return false;
        // The publisher returned a clear gone response.
        if (reason.contains("HTTP 404") || reason.contains("HTTP 410")) return true;
        return attempts >= MAX_FAILURE_ATTEMPTS;
    }
}
