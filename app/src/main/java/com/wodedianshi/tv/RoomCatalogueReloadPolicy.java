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
}
