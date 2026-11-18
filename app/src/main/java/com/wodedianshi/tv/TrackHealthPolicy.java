package com.wodedianshi.tv;

/** Distinguishes unusable live tracks from a temporarily silent audio output. */
public final class TrackHealthPolicy {
    private TrackHealthPolicy() {}

    /**
     * A live source without a usable video or audio track is incomplete and
     * should fail over. A selected audio track that has not advanced is handled
     * separately as a warning because the programme may temporarily be silent.
     */
    public static boolean shouldFailoverForTrackHealth(boolean missingAudioTrack, boolean missingVideo) {
        return missingAudioTrack || missingVideo;
    }

    /**
     * A selected track may be silent without being broken. Only combine a lack
     * of audio clock advancement with a confirmed sink error before failing over.
     */
    public static boolean shouldFailoverForAudioOutput(boolean missingAudioTrack,
                                                         boolean audioNotAdvancing,
                                                         boolean audioSinkError) {
        return missingAudioTrack || (audioNotAdvancing && audioSinkError);
    }
}
