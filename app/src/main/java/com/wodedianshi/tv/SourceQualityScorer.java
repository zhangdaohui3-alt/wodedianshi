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
    // A short structural screen, like paced sampling, must not replace a
    // previously measured positive quality score with a synthetic estimate.
    static double successfulPreflightScore(boolean paced,boolean provisional,double previous,int candidate) {
        return successfulPreflightScore(paced||provisional,previous,candidate);
    }
    static boolean reusableProbeMode(boolean cachedPaced,boolean viewing) {
        return viewing||!cachedPaced;
    }
    static boolean inconclusivePacedProbe(boolean paced,boolean valid) {
        return paced&&!valid;
    }

    /** One ranking contract for foreground playback and background discovery.
     * History values are read-only observations from the caller's snapshot. */
    static double rankScore(String url,org.json.JSONObject probes,
            java.util.function.Function<String,org.json.JSONObject> history,long now){
        String key=StableKey.url(url);
        double probe=probes.optDouble(key,0.0);
        double score=probe+labelScore(url)*0.01;
        // A failed current media probe must not be rescued by old playback history.
        if(isFailedProbeScore(probe))return score;
        org.json.JSONObject m=history.apply(key);
        if(m==null||m.optInt("attempts",0)<2)return score;
        int attempts=Math.max(1,m.optInt("attempts",0));
        int warnings=m.optInt("audioCodecErrors",0)+m.optInt("audioSinkErrors",0)+m.optInt("videoCodecErrors",0);
        double adjustment=SourceQoeScoringPolicy.adjustment(attempts,m.optInt("ready",0),
            m.optInt("firstFrames",0),m.optInt("stablePlays",0),m.optInt("failures",0),
            m.optInt("buffers",0),m.optLong("avgReadyMs",0L),m.optLong("avgFirstFrameMs",0L),warnings);
        return score+adjustment*SourceQoeScoringPolicy.historyWeight(m.optLong("updated",0L),now)
            +SourceQoeScoringPolicy.recentSuccessBonus(m.optLong("lastFirstFrame",0L),m.optLong("lastStable",0L),now);
    }

    public static int labelScore(String source) {
        if (source == null) return 0;
        String u = source.toLowerCase(Locale.ROOT);
        if (u.contains("1080p") || u.contains("1080i") || u.contains("fullhd") || u.contains("full-hd")) return 1200;
        if (u.contains("master_5000") || u.contains("5000k")) return 1150;
        if (u.contains("720p") || u.contains("720i") || u.contains("hd720")) return 1000;
        if (u.contains("master_4000") || u.contains("4000k")) return 950;
        if (u.contains("master_3000") || u.contains("3000k")) return 900;
        if (u.contains("master_2300") || u.contains("2300k")) return 850;
        if (u.contains("576p") || u.contains("576i") || u.contains("480p") || u.contains("480i")) return 600;
        if (u.contains("360p") || u.contains("240p")) return 300;
        return 0;
    }
}
