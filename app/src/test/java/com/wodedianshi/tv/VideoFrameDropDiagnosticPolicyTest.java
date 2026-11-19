package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoFrameDropDiagnosticPolicyTest {
    @Test public void recordsFirstDropReport() {
        assertTrue(VideoFrameDropDiagnosticPolicy.shouldRecord(1));
    }

    @Test public void throttlesRepeatedDropReports() {
        assertFalse(VideoFrameDropDiagnosticPolicy.shouldRecord(2));
        assertFalse(VideoFrameDropDiagnosticPolicy.shouldRecord(4));
    }

    @Test public void recordsEveryFifthDropReport() {
        assertTrue(VideoFrameDropDiagnosticPolicy.shouldRecord(5));
        assertTrue(VideoFrameDropDiagnosticPolicy.shouldRecord(10));
    }

    @Test public void ignoresInvalidReportCounts() {
        assertFalse(VideoFrameDropDiagnosticPolicy.shouldRecord(0));
        assertFalse(VideoFrameDropDiagnosticPolicy.shouldRecord(-1));
    }
}
