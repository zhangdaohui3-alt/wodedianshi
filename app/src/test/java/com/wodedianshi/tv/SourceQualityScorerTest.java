package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceQualityScorerTest {
    @Test public void recognizesThePersistedFailedProbeSentinel() {
        assertTrue(SourceQualityScorer.isFailedProbeScore(-500.0d));
        assertTrue(SourceQualityScorer.isFailedProbeScore(-600.0d));
    }

    @Test public void doesNotTreatUnprobedOrValidScoresAsFailed() {
        assertFalse(SourceQualityScorer.isFailedProbeScore(-499.0d));
        assertFalse(SourceQualityScorer.isFailedProbeScore(0.0d));
        assertFalse(SourceQualityScorer.isFailedProbeScore(900.0d));
    }
}
