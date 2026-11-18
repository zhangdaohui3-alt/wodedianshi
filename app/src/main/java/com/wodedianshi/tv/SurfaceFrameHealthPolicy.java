package com.wodedianshi.tv;

/** Conservative visual-surface health checks for the LibVLC fallback renderer. */
final class SurfaceFrameHealthPolicy {
    static final int BLACK_SAMPLE_FAILOVER_COUNT = 3;
    private static final int NEAR_BLACK_LUMA = 8;
    private static final long MAX_AVERAGE_LUMA = 4L;

    private SurfaceFrameHealthPolicy() {}

    /**
     * Treat only an almost entirely black sample as a black frame. A few bright
     * pixels (logos/subtitles/noise) are tolerated, while genuinely dark scenes
     * with visible detail should not be classified as a blank surface.
     */
    static boolean isNearBlack(int[] argbPixels) {
        if (argbPixels == null || argbPixels.length == 0) return false;
        int nearBlack = 0;
        long totalLuma = 0L;
        for (int color : argbPixels) {
            int red = (color >>> 16) & 0xff;
            int green = (color >>> 8) & 0xff;
            int blue = color & 0xff;
            int luma = (red * 2126 + green * 7152 + blue * 722) / 10000;
            totalLuma += luma;
            if (luma <= NEAR_BLACK_LUMA) nearBlack++;
        }
        return nearBlack * 100L >= argbPixels.length * 99L
                && totalLuma <= argbPixels.length * MAX_AVERAGE_LUMA;
    }

    static boolean shouldFailOverAfterBlackSamples(int consecutiveSamples) {
        return consecutiveSamples >= BLACK_SAMPLE_FAILOVER_COUNT;
    }
}
