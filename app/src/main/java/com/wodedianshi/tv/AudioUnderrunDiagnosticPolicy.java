package com.wodedianshi.tv;

/** Throttles repeated audio-underrun diagnostics during live playback. */
final class AudioUnderrunDiagnosticPolicy {
    private AudioUnderrunDiagnosticPolicy() {}

    /** Keep the first event and every fifth event; never turn an underrun into a retry. */
    static boolean shouldRecord(int count) {
        return count == 1 || (count > 1 && count % 5 == 0);
    }
}
