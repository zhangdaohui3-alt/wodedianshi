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

    @Test public void directLabelMatchingPreservesEveryHintAndPriority() {
        String[][] hints={
            {"1200","1080p","1080i","fullhd","full-hd"},
            {"1150","master_5000","5000k","5000kbps"},
            {"1000","720p","720i","hd720"},
            {"950","master_4000","4000k","4000kbps"},
            {"900","master_3000","3000k","3000kbps"},
            {"850","master_2300","2300k","2300kbps"},
            {"600","576p","576i","480p","480i"},
            {"300","360p","240p"}
        };
        for(String[] group:hints)
            for(int i=1;i<group.length;i++)
                assertEquals(group[i],Integer.parseInt(group[0]),
                    SourceQualityScorer.labelScore("https://example.invalid/"+group[i].toUpperCase(java.util.Locale.ROOT)));
        assertEquals(1200,SourceQualityScorer.labelScore("https://example.invalid/240p/5000kbps/1080i"));
        assertEquals(1150,SourceQualityScorer.labelScore("https://example.invalid/720p/5000kbps"));
        assertEquals(0,SourceQualityScorer.labelScore("https://example.invalid/1080/5000"));
        assertEquals(0,SourceQualityScorer.labelScore(null));
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
