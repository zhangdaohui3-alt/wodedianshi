package com.wodedianshi.tv;

/** Throttles repeated live video frame-drop diagnostic reports. */
final class VideoFrameDropDiagnosticPolicy {
    private VideoFrameDropDiagnosticPolicy() {}

    /** Keep the first report and then every fifth report; never trigger failover. */
    static boolean shouldRecord(int reportCount) {
        return reportCount == 1 || (reportCount > 1 && reportCount % 5 == 0);
    }
}
