package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceFrameHealthPolicyTest {
    @Test public void pureBlackSampleIsNearBlack() {
        int[] pixels = new int[32 * 18];
        assertTrue(SurfaceFrameHealthPolicy.isNearBlack(pixels));
    }

    @Test public void nullOrEmptySampleIsNotTreatedAsBlackEvidence() {
        assertFalse(SurfaceFrameHealthPolicy.isNearBlack(null));
        assertFalse(SurfaceFrameHealthPolicy.isNearBlack(new int[0]));
    }

    @Test public void visibleSceneDetailIsNotClassifiedAsBlack() {
        int[] pixels = new int[32 * 18];
        java.util.Arrays.fill(pixels, 0xff000000);
        for (int i = 0; i < pixels.length / 10; i++) pixels[i] = 0xff808080;
        assertFalse(SurfaceFrameHealthPolicy.isNearBlack(pixels));
    }

    @Test public void isolatedBrightPixelDoesNotHideAnOtherwiseBlackSurface() {
        int[] pixels = new int[32 * 18];
        pixels[0] = 0xffffffff;
        assertTrue(SurfaceFrameHealthPolicy.isNearBlack(pixels));
    }

    @Test public void requiresThreeConsecutiveSamplesBeforeFailover() {
        assertFalse(SurfaceFrameHealthPolicy.shouldFailOverAfterBlackSamples(2));
        assertTrue(SurfaceFrameHealthPolicy.shouldFailOverAfterBlackSamples(3));
        assertTrue(SurfaceFrameHealthPolicy.shouldFailOverAfterBlackSamples(6));
    }
}
