package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FullDiscoveryProgressPolicyTest {
    @org.junit.Test public void firstCoreCategoriesIndependentlyTriggerPreview() {
        int c=FullDiscoveryProgressPolicy.coreCategoryMask("CCTV-1","央视","","");
        int s=FullDiscoveryProgressPolicy.coreCategoryMask("湖南卫视","卫视","","");
        int l=FullDiscoveryProgressPolicy.coreCategoryMask("Shenzhen News","广东","","");
        org.junit.Assert.assertEquals(1,c);
        org.junit.Assert.assertEquals(2,s);
        org.junit.Assert.assertEquals(4,l);
        org.junit.Assert.assertEquals(0,FullDiscoveryProgressPolicy.coreCategoryMask("Local News","US","",""));
        org.junit.Assert.assertEquals(0,FullDiscoveryProgressPolicy.coreCategoryMask("香港新闻","香港","",""));
        org.junit.Assert.assertEquals(0,FullDiscoveryProgressPolicy.nextPreviewCoreMask(0,c|s|l,0));
        org.junit.Assert.assertEquals(1,FullDiscoveryProgressPolicy.nextPreviewCoreMask(0,c,1));
        org.junit.Assert.assertEquals(5,FullDiscoveryProgressPolicy.nextPreviewCoreMask(c,c|l,1));
        org.junit.Assert.assertEquals(7,FullDiscoveryProgressPolicy.nextPreviewCoreMask(c|l,s,1));
        org.junit.Assert.assertEquals(7,FullDiscoveryProgressPolicy.nextPreviewCoreMask(7,c,1));
    }

    @org.junit.Test public void earlyIdlePreviewCanRefreshOnNextTicker() {
        org.junit.Assert.assertEquals(4000L,FullDiscoveryProgressPolicy.previewReloadIntervalMs(false,1));
        org.junit.Assert.assertEquals(4000L,FullDiscoveryProgressPolicy.previewReloadIntervalMs(false,16));
        org.junit.Assert.assertEquals(15000L,FullDiscoveryProgressPolicy.previewReloadIntervalMs(false,32));
        org.junit.Assert.assertEquals(15000L,FullDiscoveryProgressPolicy.previewReloadIntervalMs(true,1));
        org.junit.Assert.assertEquals(15000L,FullDiscoveryProgressPolicy.previewReloadIntervalMs(false,0));
    }

    @org.junit.Test public void earlyMilestonesExposeFirstUsefulGroupsWithoutResettingOldWatermark(){
        org.junit.Assert.assertEquals(0,FullDiscoveryProgressPolicy.nextPreviewMilestone(0,0,20));
        org.junit.Assert.assertEquals(0,FullDiscoveryProgressPolicy.nextPreviewMilestone(0,1,0));
        org.junit.Assert.assertEquals(1,FullDiscoveryProgressPolicy.nextPreviewMilestone(0,1,1));
        org.junit.Assert.assertEquals(4,FullDiscoveryProgressPolicy.nextPreviewMilestone(1,6,6));
        org.junit.Assert.assertEquals(8,FullDiscoveryProgressPolicy.nextPreviewMilestone(4,15,20));
        org.junit.Assert.assertEquals(128,FullDiscoveryProgressPolicy.nextPreviewMilestone(128,8,20));
        org.junit.Assert.assertEquals(16,FullDiscoveryProgressPolicy.nextPreviewMilestone(0,16,20));
        org.junit.Assert.assertEquals(128,FullDiscoveryProgressPolicy.nextPreviewMilestone(64,163,20));
        org.junit.Assert.assertEquals(192,FullDiscoveryProgressPolicy.nextPreviewMilestone(128,246,20));
        org.junit.Assert.assertEquals(192,FullDiscoveryProgressPolicy.nextPreviewMilestone(192,160,20));
    }

    @org.junit.Test public void smallPreviewCanPublishWhileHealthyViewingButFinalSnapshotCannot(){
        org.junit.Assert.assertTrue(FullDiscoveryProgressPolicy.canPublishPreview(true,246,13));
        org.junit.Assert.assertTrue(FullDiscoveryProgressPolicy.canPublishPreview(true,256,256));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishPreview(true,256,257));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishPreview(true,Integer.MAX_VALUE,1));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishPreview(true,-1,13));
        org.junit.Assert.assertTrue(FullDiscoveryProgressPolicy.canPublishPreview(false,20000,13));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,true,true));
        org.junit.Assert.assertTrue(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,true,false));
    }

    @org.junit.Test public void boundedPreviewMilestonesExposeUsefulGrowthWithoutRepeatedWrites() {
        org.junit.Assert.assertEquals(32, FullDiscoveryProgressPolicy.nextPreviewMilestone(0,63,20));
        org.junit.Assert.assertEquals(64, FullDiscoveryProgressPolicy.nextPreviewMilestone(0,87,20));
        org.junit.Assert.assertEquals(192, FullDiscoveryProgressPolicy.nextPreviewMilestone(64,200,20));
        org.junit.Assert.assertEquals(256, FullDiscoveryProgressPolicy.nextPreviewMilestone(64,256,20));
        org.junit.Assert.assertEquals(1024, FullDiscoveryProgressPolicy.nextPreviewMilestone(64,2000,20));
        org.junit.Assert.assertEquals(8192, FullDiscoveryProgressPolicy.nextPreviewMilestone(1024,20000,20));
        org.junit.Assert.assertEquals(256, FullDiscoveryProgressPolicy.nextPreviewMilestone(256,40,20));
        org.junit.Assert.assertEquals(0, FullDiscoveryProgressPolicy.nextPreviewMilestone(0,1000,0));
    }

    @Test public void subPercentProgressIsVisibleWithoutPrematureCompletion() {
        boolean[] finished={false,false,false,false};
        int[] partial={100,0,0,0};
        assertEquals(25,FullDiscoveryProgressPolicy.overallBasisPoints(finished,partial,false));
        assertEquals("0.25%",FullDiscoveryProgressPolicy.formatIncompleteProgress(25));
        assertEquals(0,FullDiscoveryProgressPolicy.overallPercent(finished,partial,false));
        assertEquals(9999,FullDiscoveryProgressPolicy.overallBasisPoints(new boolean[]{true},null,false));
        assertEquals(10000,FullDiscoveryProgressPolicy.overallBasisPoints(finished,partial,true));
    }

    @Test public void durablePublicationMarkerAloneConfirmsCompletion() {
        assertTrue(FullDiscoveryProgressPolicy.hasPublishedInitialSnapshot(1L));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.hasPublishedInitialSnapshot(0L));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.hasPublishedInitialSnapshot(-1L));
    }

    @Test public void playingSeedDoesNotBlockEmptyStagingOrOptionalBootstrap() {
        assertTrue(FullDiscoveryProgressPolicy.canResetInitialStaging(true,0));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canResetInitialStaging(true,1));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canResetInitialStaging(true,-1));
        assertTrue(FullDiscoveryProgressPolicy.canResetInitialStaging(false,100));
        assertTrue(FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(false,0,10,0,0,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(true,0,10,0,0,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.shouldRunIdleBootstrap(false,2,10,0,0,false));
    }

    @Test public void stagingReuseRequiresMatchingCommittedRevisionAndFreshBoundedRows() {
        assertTrue(FullDiscoveryProgressPolicy.canRetainStaging(512,true));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canRetainStaging(513,true));
        assertTrue(FullDiscoveryProgressPolicy.canRetainStaging(1024,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canRetainStaging(1025,false));
        assertTrue(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","a",1000,61000));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","a",1000,61001));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","b",1000,1100));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"","",1000,1100));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(20,"a","a",1000,999));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canReuseStaging(-1,"a","a",1000,1100));
    }

    @Test public void finalizationDoesNotConfuseInspectedWithPublished() {
        assertTrue(FullDiscoveryProgressPolicy.awaitingIdleFinalization(true,true));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.awaitingIdleFinalization(false,true));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.awaitingIdleFinalization(true,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,true,true));
        assertTrue(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,true,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(false,true,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.canPublishInitialSnapshot(true,false,false));
        assertEquals(99,FullDiscoveryProgressPolicy.overallPercent(new boolean[]{true,true},new int[]{0,0},false));
    }

    @Test public void mainlandIndexPhaseIsSeparateFromVerifiedProgress() {
        assertTrue(FullDiscoveryProgressPolicy.isMainlandIndexPhase(0,10,0,0,false));
        assertTrue(FullDiscoveryProgressPolicy.isMainlandIndexPhase(1,10,0,0,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(2,10,0,0,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(1,10,0,5,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(1,10,1,0,false));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(0,10,0,0,true));
        org.junit.Assert.assertFalse(FullDiscoveryProgressPolicy.isMainlandIndexPhase(0,0,0,0,false));
        assertEquals(2,FullDiscoveryProgressPolicy.bootstrapIndexLimit(10));
        assertEquals(1,FullDiscoveryProgressPolicy.bootstrapIndexLimit(1));
        assertEquals(0,FullDiscoveryProgressPolicy.bootstrapIndexLimit(-1));
    }

    @org.junit.Test public void earlyPreviewMilestonesNeverPretendToCompleteFullDiscovery() {
        org.junit.Assert.assertEquals(0, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 11, 3, false));
        org.junit.Assert.assertEquals(0, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 20, 0, true));
        org.junit.Assert.assertEquals(1, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 12, 3, false));
        org.junit.Assert.assertEquals(1, FullDiscoveryProgressPolicy.nextPreviewLevel(1, 40, 20, false));
        org.junit.Assert.assertEquals(2, FullDiscoveryProgressPolicy.nextPreviewLevel(1, 40, 20, true));
        org.junit.Assert.assertEquals(2, FullDiscoveryProgressPolicy.nextPreviewLevel(0, 3, 3, true));
        org.junit.Assert.assertEquals(2, FullDiscoveryProgressPolicy.nextPreviewLevel(2, 40, 20, true));
    }

    @Test public void overallProgressIncludesEveryPartialFeed() {
        boolean[] done={true,false,false,false};
        int[] partial={0,5000,2500,0};
        assertEquals(43,FullDiscoveryProgressPolicy.overallPercent(done,partial,false));
        partial[1]=0;
        assertEquals(31,FullDiscoveryProgressPolicy.overallPercent(done,partial,false));
        assertEquals(99,FullDiscoveryProgressPolicy.overallPercent(
            new boolean[]{true,true},new int[]{0,0},false));
        assertEquals(100,FullDiscoveryProgressPolicy.overallPercent(done,partial,true));
        assertEquals(5000,FullDiscoveryProgressPolicy.feedProgress(12,24));
        assertEquals(0,FullDiscoveryProgressPolicy.feedProgress(12,-1));
        assertEquals(10000,FullDiscoveryProgressPolicy.feedProgress(Integer.MAX_VALUE,1));
    }

    @Test public void coverageSeparatesMainlandCategoriesAndDoesNotCountForeignLocal() {
        FullDiscoveryProgressPolicy.Coverage c = new FullDiscoveryProgressPolicy.Coverage();
        c.add("CCTV-2","央视","","");
        c.add("福建东南卫视","卫视","","");
        c.add("厦门新闻","福建","","");
        c.add("Shenzhen News","广东","","");
        c.add("Local News","US","","");
        c.add("香港新闻","香港","","");
        assertEquals(1,c.cctvCount());
        assertEquals(1,c.satelliteCount());
        assertEquals(2,c.localCount());
        assertEquals(2,c.provinceCount());
        assertTrue(c.summary().contains("覆盖有缺口"));
        assertTrue(c.summary().contains("非实播验证"));
    }
    @Test public void emptyCoverageNeverClaimsSuccess() {
        FullDiscoveryProgressPolicy.Coverage c = new FullDiscoveryProgressPolicy.Coverage();
        assertEquals(0,c.provinceCount());
        assertTrue(c.summary().contains("0/31"));
        assertTrue(c.summary().contains("覆盖有缺口"));
    }

    @Test public void countsCompletedFeedsAndIndexedCursor() {
        assertEquals(0, FullDiscoveryProgressPolicy.percent(0, 20, 0, 500, false));
        assertEquals(12, FullDiscoveryProgressPolicy.percent(2, 20, 250, 500, false));
        assertEquals(25, FullDiscoveryProgressPolicy.percent(5, 20, 0, 0, false));
    }

    @Test public void aUnpublishedSnapshotNeverShowsOneHundredPercent() {
        assertEquals(99, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, false));
        assertEquals(100, FullDiscoveryProgressPolicy.percent(20, 20, 0, 0, true));
    }

    @Test public void missingOrInvalidIndexNeverInventsFractionalProgress() {
        assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, 50, -1, false));
        assertEquals(15, FullDiscoveryProgressPolicy.percent(3, 20, -5, 100, false));
        assertEquals(0, FullDiscoveryProgressPolicy.percent(3, 0, 50, 100, false));
    }

    @Test public void corruptOrOverflowingCursorIsClamped() {
        assertEquals(20, FullDiscoveryProgressPolicy.percent(3, 20, 5000, 100, false));
        assertEquals(99, FullDiscoveryProgressPolicy.percent(Integer.MAX_VALUE, 20,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false));
    }

    @Test public void completedPercentRequiresActualPublication() {
        assertEquals(100, FullDiscoveryProgressPolicy.percent(0, 20, 0, 0, true));
    }
}
