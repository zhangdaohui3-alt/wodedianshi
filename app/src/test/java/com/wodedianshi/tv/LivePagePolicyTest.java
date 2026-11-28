package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LivePagePolicyTest {
    @Test public void countsPagesUsingTheRenderedPageSize() {
        assertEquals(1, LivePagePolicy.pageCount(48, 48));
        assertEquals(2, LivePagePolicy.pageCount(49, 48));
        assertEquals(2, LivePagePolicy.pageCount(96, 48));
        assertEquals(3, LivePagePolicy.pageCount(97, 48));
    }

    @Test public void directPageSelectionRejectsOutOfRangeIndexes() {
        assertTrue(LivePagePolicy.canSelectPage(0,97,48));
        assertTrue(LivePagePolicy.canSelectPage(2,97,48));
        assertFalse(LivePagePolicy.canSelectPage(3,97,48));
        assertFalse(LivePagePolicy.canSelectPage(-1,97,48));
        assertFalse(LivePagePolicy.canSelectPage(1,0,48));
        assertEquals(2,LivePagePolicy.choosePage(2,5,48,true));
    }

    @Test public void nextPageRemainsAvailableUntilTheLastRenderedPage() {
        assertTrue(LivePagePolicy.canRequestPage(1,0,49,48,false));
        assertTrue(LivePagePolicy.canRequestPage(2,1,97,48,false));
        assertFalse(LivePagePolicy.canRequestPage(2,1,96,48,false));
        assertFalse(LivePagePolicy.canRequestPage(3,2,97,48,false));
    }

    @Test public void pagingRejectsOverlappingClicksAndBoundariesWithoutDisablingControls(){
        assertTrue(LivePagePolicy.canRequestPage(1,0,97,48,false));
        assertTrue(LivePagePolicy.canRequestPage(0,1,97,48,false));
        assertFalse(LivePagePolicy.canRequestPage(2,1,97,48,true));
        assertFalse(LivePagePolicy.canRequestPage(1,1,97,48,false));
        assertFalse(LivePagePolicy.canRequestPage(-1,0,97,48,false));
        assertFalse(LivePagePolicy.canRequestPage(3,2,97,48,false));
    }

    @Test public void emptyOrInvalidPageSizeIsSafe() {
        assertEquals(1, LivePagePolicy.pageCount(0, 48));
        assertEquals(1, LivePagePolicy.pageCount(20, 0));
        assertFalse(LivePagePolicy.canRequestPage(1,0,0,48,false));
    }

    @Test public void manualPageNavigationIsNotOverriddenBySelectedChannel() {
        assertEquals(1, LivePagePolicy.choosePage(1, 5, 48, true));
        assertEquals(2, LivePagePolicy.choosePage(2, 5, 48, true));
    }

    @Test public void ordinaryRefreshKeepsSelectedChannelVisible() {
        assertEquals(1, LivePagePolicy.choosePage(0, 50, 48, false));
        assertEquals(0, LivePagePolicy.choosePage(2, 5, 48, false));
    }
}
