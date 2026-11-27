package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceDiscoveryPriorityPolicyTest {
    @Test public void earlyFirstWatchVisitsDistinctDomesticFamiliesBeforeMirrors() {
        boolean[] done=new boolean[26],tried=new boolean[26];
        int[] order={0,2,4,5,6,7,8,9,1,3};
        int start=0;
        for(int expected:order){
            int actual=SourceDiscoveryPriorityPolicy.nextFeed(start,done,tried,10,13,true);
            assertEquals(expected,actual);
            tried[actual]=true;
            start=SourceDiscoveryPriorityPolicy.nextAfterFirstWatchTurn(actual,26);
        }
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(start,done,tried,10,13,true));
        assertEquals(0,start);
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextFeed(1,done,new boolean[26],10,13,false));
        java.util.Arrays.fill(done,0,10,true);
        assertEquals(10,SourceDiscoveryPriorityPolicy.nextFeed(4,done,new boolean[26],10,13,true));
        assertEquals(0,SourceDiscoveryPriorityPolicy.firstWatchBootstrapFeed(0));
        assertEquals(2,SourceDiscoveryPriorityPolicy.firstWatchBootstrapFeed(1));
    }

    @Test public void mainlandHasPriorityEvenAfterCursorRotatesIntoGlobalArea() {
        boolean[] done = new boolean[8], tried = new boolean[8];
        assertEquals(0, SourceDiscoveryPriorityPolicy.nextFeed(7, done, tried,3,5));
        tried[0] = true;
        assertEquals(1, SourceDiscoveryPriorityPolicy.nextFeed(1,done,tried,3,5));
        tried[1] = true;
        tried[2] = true;
        assertEquals(-1, SourceDiscoveryPriorityPolicy.nextFeed(5,done,tried,3,5));
        assertEquals(0,SourceDiscoveryPriorityPolicy.activeStage(done,3,5));
    }

    @Test public void onceMainlandIsInspectedHongKongMacauTaiwanGoNext() {
        boolean[] done = {true,true,true,false,false,false,false};
        boolean[] tried = new boolean[done.length];
        assertEquals(1,SourceDiscoveryPriorityPolicy.activeStage(done,3,5));
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,5));
        tried[3] = true;
        assertEquals(4,SourceDiscoveryPriorityPolicy.nextFeed(6,done,tried,3,5));
    }

    @Test public void globalFeedsOnlyBeginAfterChineseStagesAreInspected() {
        boolean[] done = {true,true,true,true,true,false,false};
        boolean[] tried = new boolean[done.length];
        assertEquals(2,SourceDiscoveryPriorityPolicy.activeStage(done,3,5));
        assertEquals(6,SourceDiscoveryPriorityPolicy.nextFeed(6,done,tried,3,5));
        assertEquals(5,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,5));
    }

    @Test public void roundRobinDoesNotRepeatedlyProbeSameFeedInOneWorker() {
        boolean[] done=new boolean[6],tried=new boolean[6];
        assertEquals(2,SourceDiscoveryPriorityPolicy.nextFeed(2,done,tried,3,4));
        tried[2]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,4));
        tried[0]=true;
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,3,4));
    }

    @Test public void allFinishedOrInvalidStateEndsSafely() {
        boolean[] done = {true,true,true,true};
        boolean[] tried=new boolean[4];
        assertEquals(3,SourceDiscoveryPriorityPolicy.activeStage(done,2,3));
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,done,tried,2,3));
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,null,tried,2,3));
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextFeed(0,done,new boolean[2],2,3));
    }

    @Test public void deferredMainlandFeedsDoNotBlockEligibleOtherRegions() {
        boolean[] done=new boolean[26], deferred=new boolean[26];
        java.util.Arrays.fill(done,0,8,true);
        deferred[8]=true; deferred[9]=true;
        boolean[] originalDone=done.clone(), originalDeferred=deferred.clone();
        assertEquals(10,SourceDiscoveryPriorityPolicy.nextEligibleFeed(8,done,deferred,10,13,false));
        java.util.Arrays.fill(deferred,10,13,true);
        assertEquals(13,SourceDiscoveryPriorityPolicy.nextEligibleFeed(8,done,deferred,10,13,false));
        assertEquals(0,SourceDiscoveryPriorityPolicy.activeStage(done,10,13));
        assertEquals(8,SourceDiscoveryPriorityPolicy.completedInStage(done,0,26));
        assertArrayEquals(originalDone,done);
        java.util.Arrays.fill(deferred,10,13,false);
        assertArrayEquals(originalDeferred,deferred);
        deferred[9]=false;
        assertEquals(9,SourceDiscoveryPriorityPolicy.nextEligibleFeed(13,done,deferred,10,13,false));
    }

    @Test public void eligibleMainlandKeepsEarlyFamilyPriority() {
        boolean[] done=new boolean[26], deferred=new boolean[26];
        deferred[0]=true;
        assertEquals(2,SourceDiscoveryPriorityPolicy.nextEligibleFeed(0,done,deferred,10,13,true));
        java.util.Arrays.fill(deferred,0,10,true);
        assertEquals(10,SourceDiscoveryPriorityPolicy.nextEligibleFeed(0,done,deferred,10,13,true));
        java.util.Arrays.fill(deferred,true);
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextEligibleFeed(0,done,deferred,10,13,true));
        assertEquals(0,SourceDiscoveryPriorityPolicy.completedInStage(done,0,26));
    }

    @Test public void eligibleSelectionRejectsMalformedAndCompletedState() {
        boolean[] done={true,true,true}, deferred={false,false,false};
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextEligibleFeed(0,done,deferred,1,2,false));
        assertEquals(-1,SourceDiscoveryPriorityPolicy.nextEligibleFeed(0,done,new boolean[2],1,2,false));
    }

    @Test public void missingCoreCoverageGivesUnvisitedDomesticFamiliesFirstTurn() {
        boolean[] done=new boolean[26], attempted=new boolean[26], visited=new boolean[26];
        visited[0]=true;
        assertEquals(2,SourceDiscoveryPriorityPolicy.nextCoverageFeed(0,done,attempted,visited,10,13,true));
        attempted[2]=true;
        assertEquals(4,SourceDiscoveryPriorityPolicy.nextCoverageFeed(0,done,attempted,visited,10,13,true));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextCoverageFeed(0,done,attempted,visited,10,13,false));
        java.util.Arrays.fill(visited,0,10,true);
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextCoverageFeed(0,done,attempted,visited,10,13,true));
        java.util.Arrays.fill(attempted,0,10,true);
        assertEquals(10,SourceDiscoveryPriorityPolicy.nextCoverageFeed(0,done,attempted,visited,10,13,true));
        assertEquals(0,SourceDiscoveryPriorityPolicy.completedInStage(done,0,26));
    }

    @Test public void completedAndUnavailableBothCountAsInspected() {
        boolean[] done = {true,true,false,false};
        assertEquals(2,SourceDiscoveryPriorityPolicy.completedInStage(done,0,4));
        assertEquals(2,SourceDiscoveryPriorityPolicy.completedInStage(done,-5,20));
        assertEquals(0,SourceDiscoveryPriorityPolicy.completedInStage(done,3,1));
        assertEquals("优先抓取中国大陆：央视、卫视、各省地方台",
            SourceDiscoveryPriorityPolicy.stageLabel(0));
    }

    @Test public void preparedDirectoryYieldsToFreshEligiblePeerOnlyAfterCoreCoverage(){
        boolean[] done={false,false,false,false,false};
        boolean[] tried=new boolean[5],visited=new boolean[5],failed=new boolean[5];
        boolean[] prepared={true,false,false,false,false};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,tried,visited,prepared,failed,3,4,false,false,false));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,tried,visited,prepared,failed,3,4,true,false,false));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,tried,visited,prepared,failed,3,4,false,true,false));
        failed[1]=true;tried[2]=true;
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,tried,visited,prepared,failed,3,4,false,false,false));
        tried[0]=true;tried[1]=true;
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,tried,visited,prepared,failed,3,4,false,false,false));
    }

    @Test public void partialAcquisitionGivesOrdinaryRotationTurnWithoutChangingCompletion(){
        boolean[] done={false,false,false,false,false};
        boolean[] tried=new boolean[5],visited=new boolean[5],failed=new boolean[5];
        boolean[] prepared={true,false,true,false,false};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextInitialFeed(2,done,tried,visited,prepared,failed,3,4,false,false,false));
        assertEquals(2,SourceDiscoveryPriorityPolicy.nextInitialFeed(2,done,tried,visited,prepared,failed,3,4,false,false,true));
        tried[2]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(2,done,tried,visited,prepared,failed,3,4,false,false,true));
        assertFalse(done[1]);
    }

    @Test public void globalAcquisitionRemainsChinaFirstThenResumesChinaFirstProbes(){
        boolean[] done=new boolean[6],tried=new boolean[6],visited=new boolean[6],failed=new boolean[6];
        boolean[] prepared={true,false,true,false,false,false};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextInitialFeed(5,done,tried,visited,prepared,failed,3,4,false,false,false));
        prepared[1]=true;
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextInitialFeed(5,done,tried,visited,prepared,failed,3,4,false,false,false));
        prepared[3]=true;
        assertEquals(5,SourceDiscoveryPriorityPolicy.nextInitialFeed(5,done,tried,visited,prepared,failed,3,4,false,false,false));
        prepared[4]=true;prepared[5]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(5,done,tried,visited,prepared,failed,3,4,false,false,false));
        assertArrayEquals(new boolean[6],done);
    }

    @Test public void preparedDirectoriesReceiveFirstTurnAcrossRegionsBeforeDeepScan(){
        boolean[] done=new boolean[6],attempted=new boolean[6],failed=new boolean[6];
        boolean[] prepared={true,true,true,true,true,true};
        boolean[] visited={true,true,false,false,false,false};
        assertEquals(2,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,false,false,false));
        visited[2]=true;
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,false,false,false));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,true,false,false));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,false,true,false));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,false,false,true));
        visited[3]=true;attempted[4]=true;
        assertEquals(5,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,false,false,false));
        visited[5]=true;failed[4]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeed(0,done,attempted,visited,prepared,failed,3,4,false,false,false));
        assertArrayEquals(new boolean[6],done);
    }

    @Test public void pressurePrefersReadyIndexWithinSameInspectionTier(){
        boolean[] done=new boolean[6],attempted=new boolean[6],failed=new boolean[6];
        boolean[] visited={false,false,true,false,false,false};
        boolean[] prepared={true,true,true,true,true,true};
        boolean[] available={true,true,true,true,true,true};
        boolean[] ready={false,true,true,true,true,true};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available,ready));
        ready[1]=false;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available,ready));
        visited[0]=true;visited[1]=true;
        assertEquals(2,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available,ready));
        attempted[2]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available,ready));
        assertArrayEquals(new boolean[6],done);
    }

    @Test public void cachePressureGivesUnvisitedCachedPeerFirstTurnWithinSameRegion(){
        boolean[] done=new boolean[6],attempted=new boolean[6],failed=new boolean[6];
        boolean[] visited={true,false,true,false,false,false};
        boolean[] prepared={true,true,true,true,true,false};
        boolean[] available={true,true,true,true,true,false};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available));
        visited[1]=true;
        // Do not jump to HMT/international first turns while mainland
        // still has eligible cached work under pressure.
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available));
        visited[1]=false;failed[1]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available));
        java.util.Arrays.fill(attempted,0,3,true);
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,
            prepared,failed,3,4,false,false,false,available));
        assertArrayEquals(new boolean[6],done);
        assertArrayEquals(new boolean[]{true,true,true,false,false,false},attempted);
    }

    @Test public void cachePressureDrainsEligibleIndexedDirectoriesBeforeNewAcquisition(){
        boolean[] done=new boolean[6],attempted=new boolean[6],visited=new boolean[6],failed=new boolean[6];
        boolean[] prepared={false,true,true,true,false,false};
        boolean[] available={false,true,false,true,false,false};
        assertEquals(1,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,prepared,failed,3,4,false,false,false,available));
        attempted[1]=true;
        assertEquals(3,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,prepared,failed,3,4,false,false,false,available));
        attempted[3]=true;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,prepared,failed,3,4,false,false,false,available));
        attempted[1]=false;
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,prepared,failed,3,4,true,false,false,available));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,prepared,failed,3,4,false,true,false,available));
        assertEquals(0,SourceDiscoveryPriorityPolicy.nextInitialFeedWithCache(0,done,attempted,visited,prepared,failed,3,4,false,false,false,null));
        assertArrayEquals(new boolean[6],done);
        assertFalse(PlaylistFeedCache.initialCacheNeedsDrain(160L*1024*1024-1));
        assertTrue(PlaylistFeedCache.initialCacheNeedsDrain(160L*1024*1024));
    }
}
