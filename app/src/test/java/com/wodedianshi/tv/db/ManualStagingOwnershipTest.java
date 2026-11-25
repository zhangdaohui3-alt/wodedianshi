package com.wodedianshi.tv.db;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManualStagingOwnershipTest {
    @Test public void onlyExactNonemptyOwnerMayUseManualStaging(){
        RoomCatalogueBuildStore.requireMatchingOwner("manual-A","manual-A");
        String[][] rejected={{"manual-A","manual-B"},{"manual-A",null},{null,"manual-A"},{"",""},{null,null}};
        for(String[] pair:rejected){
            try{
                RoomCatalogueBuildStore.requireMatchingOwner(pair[0],pair[1]);
                fail("Unowned or stale staging accepted");
            }catch(IllegalStateException expected){}
        }
    }

    @Test public void manualPublicationCannotReuseInitialProgressKeys(){
        assertNotEquals(RoomCatalogueBuildStore.STAGING_REVISION_KEY,RoomCatalogueBuildStore.MANUAL_OWNER_KEY);
        assertNotEquals(RoomCatalogueBuildStore.STAGING_REVISION_KEY,RoomCatalogueBuildStore.MANUAL_BASE_VERSION_KEY);
        assertNotEquals(RoomCatalogueBuildStore.MANUAL_OWNER_KEY,RoomCatalogueBuildStore.MANUAL_BASE_VERSION_KEY);
        assertFalse(CatalogueVersionPolicy.matches(10L,11L));
        assertFalse(CatalogueVersionPolicy.matches(10L,null));
        assertTrue(CatalogueVersionPolicy.matches(10L,10L));
    }
}
