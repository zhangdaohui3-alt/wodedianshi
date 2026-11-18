package com.wodedianshi.tv;

/** Scheduling guard for audio sink errors observed during live playback. */
final class AudioTrackCheckSchedulePolicy {
    private AudioTrackCheckSchedulePolicy() {}

    /**
     * A late sink-error recheck must not shorten the initial 12-second READY
     * observation window. The original check already includes the sink-error
     * flag when it eventually runs.
     */
    static boolean shouldScheduleLateSinkRecheck(boolean initialReadyCheckCompleted) {
        return initialReadyCheckCompleted;
    }
}
