package com.wodedianshi.tv;

/**
 * Discovery must not silently promote a failed media probe into a new
 * published channel. Previously saved sources are preserved separately by
 * Room snapshot reconciliation and source-retention rules.
 *
 * A successful preflight proves only format/reachability, not playback,
 * sound, authorization, or suitability for redistribution.
 */
public final class SourceDiscoveryAcceptancePolicy {
    private SourceDiscoveryAcceptancePolicy() {}

    // Changing acceptance semantics requires resetting an in-progress isolated
    // first-build staging set; otherwise previously staged dead URLs survive.
    public static final String BUILD_POLICY_VERSION = "validated-new-candidates-v2";

    public static boolean mayPromoteNewCandidate(boolean mediaPreflightPassed) {
        return mediaPreflightPassed;
    }
}
