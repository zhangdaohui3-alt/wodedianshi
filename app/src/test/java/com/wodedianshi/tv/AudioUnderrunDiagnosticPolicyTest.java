package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioUnderrunDiagnosticPolicyTest {
    @Test public void recordsFirstUnderrun() {
        assertTrue(AudioUnderrunDiagnosticPolicy.shouldRecord(1));
    }

    @Test public void throttlesIntermediateUnderruns() {
        assertFalse(AudioUnderrunDiagnosticPolicy.shouldRecord(2));
        assertFalse(AudioUnderrunDiagnosticPolicy.shouldRecord(4));
    }

    @Test public void recordsEveryFifthUnderrun() {
        assertTrue(AudioUnderrunDiagnosticPolicy.shouldRecord(5));
        assertTrue(AudioUnderrunDiagnosticPolicy.shouldRecord(10));
    }

    @Test public void ignoresInvalidCounts() {
        assertFalse(AudioUnderrunDiagnosticPolicy.shouldRecord(0));
        assertFalse(AudioUnderrunDiagnosticPolicy.shouldRecord(-1));
    }
}
