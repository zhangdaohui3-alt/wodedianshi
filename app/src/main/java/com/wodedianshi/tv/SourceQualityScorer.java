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

    /**
     * Small tie-breaker for publisher metadata. It cannot make an unreachable
     * URL valid and remains weaker than local probes/device QoE.
     * responseTimeMs is remote directory telemetry, not this device's RTT.
     */
    public static int metadataScore(int declaredHeight, long responseTimeMs) {
        int score = declaredHeight >= 1080 ? 24
                : declaredHeight >= 720 ? 14
                : declaredHeight >= 576 ? 6 : 0;
        if (responseTimeMs > 0L && responseTimeMs <= 60L) score += 8;
        else if (responseTimeMs > 60L && responseTimeMs <= 120L) score += 5;
        else if (responseTimeMs > 120L && responseTimeMs <= 250L) score += 2;
        return score;
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
