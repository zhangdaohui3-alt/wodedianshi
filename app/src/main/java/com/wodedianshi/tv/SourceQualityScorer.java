package com.wodedianshi.tv;

import java.util.Locale;

/**
 * Small, pure scoring helper for source labels.
 *
 * This deliberately scores only hints present in a URL. Real playback
 * measurements remain the stronger signal in MainActivity's QoE ranking.
 */
public final class SourceQualityScorer {
    private SourceQualityScorer() {}

    /** The worker persists -500 when a current media preflight fails. */
    public static boolean isFailedProbeScore(double probeScore) {
        return probeScore <= -500.0d;
    }

    // Pacing controls the reader, not the origin. A successful paced sample
    // proves media structure but cannot measure available throughput or latency.
    static int preflightSpeedBonus(boolean paced,int bytes,long elapsedMs) {
        if(paced)return 150; // provisional midpoint, not measured throughput
        return (int)Math.min(300L,Math.max(0,bytes)*1000L/Math.max(1L,elapsedMs)/2048L);
    }
    static int preflightLatencyPenalty(boolean paced,long elapsedMs) {
        return paced?125:(int)Math.min(250L,Math.max(0L,elapsedMs)/20L);
    }
    static double successfulPreflightScore(boolean paced,double previous,int candidate) {
        return paced&&Double.isFinite(previous)&&previous>0.0?previous:candidate;
    }
    static boolean reusableProbeMode(boolean cachedPaced,boolean viewing) {
        return viewing||!cachedPaced;
    }
    static boolean inconclusivePacedProbe(boolean paced,boolean valid) {
        return paced&&!valid;
    }

    public static int labelScore(String source) {
        if (source == null) return 0;
        String u = source.toLowerCase(Locale.ROOT);
        if (u.matches(".*(?:1080p|1080i|fullhd|full-hd).*")) return 1200;
        if (u.contains("master_5000") || u.contains("5000k") || u.contains("5000kbps")) return 1150;
        if (u.matches(".*(?:720p|720i|hd720).*")) return 1000;
        if (u.contains("master_4000") || u.contains("4000k") || u.contains("4000kbps")) return 950;
        if (u.contains("master_3000") || u.contains("3000k") || u.contains("3000kbps")) return 900;
        if (u.contains("master_2300") || u.contains("2300k") || u.contains("2300kbps")) return 850;
        if (u.matches(".*(?:576p|576i|480p|480i).*")) return 600;
        if (u.matches(".*(?:360p|240p).*")) return 300;
        return 0;
    }
}
