package com.wodedianshi.tv;

/** Deterministic preference when multiple providers return the same title. */
public final class VodResultMergePolicy {
    private VodResultMergePolicy() {}

    /** A playable direct result may replace metadata-only data, never the reverse. */
    public static boolean shouldReplace(boolean existingPlayable, boolean incomingPlayable) {
        return !existingPlayable && incomingPlayable;
    }
}
