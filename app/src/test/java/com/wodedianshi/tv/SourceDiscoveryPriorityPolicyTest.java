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

    @Test public void completedAndUnavailableBothCountAsInspected() {
        boolean[] done = {true,true,false,false};
        assertEquals(2,SourceDiscoveryPriorityPolicy.completedInStage(done,0,4));
        assertEquals(2,SourceDiscoveryPriorityPolicy.completedInStage(done,-5,20));
        assertEquals(0,SourceDiscoveryPriorityPolicy.completedInStage(done,3,1));
        assertEquals("优先抓取中国大陆：央视、卫视、各省地方台",
            SourceDiscoveryPriorityPolicy.stageLabel(0));
    }
}
