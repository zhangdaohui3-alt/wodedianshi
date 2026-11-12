package com.wodedianshi.tv;

/** Timestamp policy for source recency: READY alone is not proof of visible playback. */
public final class SourceSuccessTimestampPolicy {
    private SourceSuccessTimestampPolicy() {}

    /** Returns the newest verified rendered-frame or stable-playback timestamp. */
    public static long latestVerifiedSuccess(long lastFirstFrame, long lastStable) {
        return Math.max(0L, Math.max(lastFirstFrame, lastStable));
    }
}
