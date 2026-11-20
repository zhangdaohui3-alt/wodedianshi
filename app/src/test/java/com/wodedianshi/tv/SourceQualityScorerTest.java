package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceQualityScorerTest {
    @Test public void ranksResolutionLabelsAsSmallHintsNotPlaybackProof() {
        assertTrue(SourceQualityScorer.labelScore("https://cdn.example/live_1080p.m3u8")
                > SourceQualityScorer.labelScore("https://cdn.example/live_720p.m3u8"));
        assertTrue(SourceQualityScorer.labelScore("https://cdn.example/live_720p.m3u8")
                > SourceQualityScorer.labelScore("https://cdn.example/live.m3u8"));
        assertEquals(0, SourceQualityScorer.labelScore("https://cdn.example/live.m3u8"));
    }

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
