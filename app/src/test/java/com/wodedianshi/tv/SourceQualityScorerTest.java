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

    @Test public void pacedValidationDoesNotPretendToMeasureNetworkSpeed() {
        assertEquals(150,SourceQualityScorer.preflightSpeedBonus(true,32768,20L));
        assertEquals(150,SourceQualityScorer.preflightSpeedBonus(true,32768,5000L));
        assertEquals(125,SourceQualityScorer.preflightLatencyPenalty(true,20L));
        assertEquals(125,SourceQualityScorer.preflightLatencyPenalty(true,5000L));
        assertEquals(16,SourceQualityScorer.preflightSpeedBonus(false,32768,1000L));
        assertEquals(300,SourceQualityScorer.preflightSpeedBonus(false,32768,10L));
        assertEquals(250,SourceQualityScorer.preflightLatencyPenalty(false,6000L));
        assertEquals(5,SourceQualityScorer.preflightLatencyPenalty(false,100L));
    }

    @Test public void pacedSuccessKeepsPositivePriorButCanRecoverFailedSource() {
        assertEquals(1900.0,SourceQualityScorer.successfulPreflightScore(true,1900.0,1200),0.0);
        assertEquals(1200.0,SourceQualityScorer.successfulPreflightScore(true,-500.0,1200),0.0);
        assertEquals(1200.0,SourceQualityScorer.successfulPreflightScore(true,0.0,1200),0.0);
        assertEquals(1200.0,SourceQualityScorer.successfulPreflightScore(false,1900.0,1200),0.0);
    }

    @Test public void pacedCacheCannotSubstituteForIdleMeasurement() {
        assertFalse(SourceQualityScorer.reusableProbeMode(true,false));
        assertTrue(SourceQualityScorer.reusableProbeMode(true,true));
        assertTrue(SourceQualityScorer.reusableProbeMode(false,false));
        assertTrue(SourceQualityScorer.reusableProbeMode(false,true));
        assertTrue(SourceQualityScorer.inconclusivePacedProbe(true,false));
        assertFalse(SourceQualityScorer.inconclusivePacedProbe(true,true));
        assertFalse(SourceQualityScorer.inconclusivePacedProbe(false,false));
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

    @Test public void provisionalSuccessDoesNotEraseMeasuredQuality(){
        assertEquals(1900.0,SourceQualityScorer.successfulPreflightScore(false,true,1900.0,350),0.0);
        assertEquals(350.0,SourceQualityScorer.successfulPreflightScore(false,true,-500.0,350),0.0);
        assertEquals(350.0,SourceQualityScorer.successfulPreflightScore(false,true,0.0,350),0.0);
        assertEquals(700.0,SourceQualityScorer.successfulPreflightScore(false,false,1900.0,700),0.0);
    }
}
