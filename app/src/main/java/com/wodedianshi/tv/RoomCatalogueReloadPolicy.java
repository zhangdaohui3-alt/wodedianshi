package com.wodedianshi.tv;

/**
 * Prevents a Room catalogue read that raced with a user's favorite or explicit
 * source change from replacing the live UI with a stale snapshot.
 *
 * A dropped read may be retried once after the serialized narrow Room mutation
 * queue drains. A newer reload request supersedes the older one completely.
 */
public final class RoomCatalogueReloadPolicy {
    private RoomCatalogueReloadPolicy() { }

    /** A trusted in-memory directory already represents this exact committed version. */
    public static boolean isUnchanged(long loadedVersion, Long committedVersion) {
        return loadedVersion >= 0L && committedVersion != null
                && loadedVersion == committedVersion.longValue();
    }

    public static boolean mayPublish(boolean activityActive, boolean latestRequest,
            long capturedMutationGeneration, long currentMutationGeneration) {
        return activityActive && latestRequest
            && capturedMutationGeneration == currentMutationGeneration;
    }

    public static boolean shouldRetryAfterUserMutation(boolean activityActive,
            boolean latestRequest, long capturedMutationGeneration,
            long currentMutationGeneration, int retriesRemaining) {
        return activityActive && latestRequest && retriesRemaining > 0
            && capturedMutationGeneration != currentMutationGeneration;
    }

    /**
     * Paged Room reads may race a transaction even if no Activity mutation
     * generation changed (for example, SourceUpdateWorker published Room).
     * A null readiness result can also be transient. Retry once at most, and
     * only for the current visible Activity request.
     */
    public static boolean shouldRetryAfterReadConflict(boolean activityActive,
            boolean latestRequest, int retriesRemaining) {
        return activityActive && latestRequest && retriesRemaining > 0;
    }
}
