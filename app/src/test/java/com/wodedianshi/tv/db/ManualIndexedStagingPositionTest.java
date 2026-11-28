package com.wodedianshi.tv.db;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;

public class ManualIndexedStagingPositionTest {
    @Test public void sparseRowsRetainGlobalPositionsAndAppendAfterFullCount(){
        assertEquals(7,RoomCatalogueBuildStore.indexedRowPosition(100,Arrays.asList(7,89),0));
        assertEquals(89,RoomCatalogueBuildStore.indexedRowPosition(100,Arrays.asList(7,89),1));
        assertEquals(100,RoomCatalogueBuildStore.indexedRowPosition(100,Arrays.asList(7,89),2));
        assertEquals(101,RoomCatalogueBuildStore.indexedRowPosition(100,Arrays.asList(7,89),3));
        assertEquals(100,RoomCatalogueBuildStore.indexedRowPosition(100,Collections.emptyList(),0));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsOutsideBase(){
        RoomCatalogueBuildStore.indexedRowPosition(100,Arrays.asList(100),0);
    }
}
