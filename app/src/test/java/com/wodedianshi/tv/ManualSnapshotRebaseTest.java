package com.wodedianshi.tv;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ManualSnapshotRebaseTest {
    private ManualSnapshotRebase.Row<String> r(String id,String base,String before,String now,String value){
        return new ManualSnapshotRebase.Row<>(id,base,before,now,value);
    }
    @Test public void userChangesAndDeletesWinWithoutLosingUnrelatedDiscovery(){
        List<ManualSnapshotRebase.Row<String>> staged=Arrays.asList(
            r("a","a","oldA","","new routes A"),r("b","b","oldB","","staged B"),
            r("c","c","oldC","","deleted C"),r("d","","","","new D"),
            r("e","","","","discovered E"));
        List<ManualSnapshotRebase.Row<String>> live=Arrays.asList(
            r("a","","","oldA","live A"),r("b","","","editedB","user B"),
            r("e","","","addedE","user E"),r("f","","","addedF","user F"));
        assertEquals(Arrays.asList("new routes A","user B","user E","user F","new D"),
            ManualSnapshotRebase.merge(staged,live,3,()->false));
    }
    @Test public void missingOrAmbiguousBaselineFailsClosed(){
        try{ManualSnapshotRebase.merge(Collections.singletonList(r("a","a","old","","a")),
                Collections.emptyList(),2,()->false);fail("Missing baseline accepted");}
        catch(IllegalArgumentException expected){}
        try{ManualSnapshotRebase.merge(Arrays.asList(r("a","a","old","","a"),r("a","","","","duplicate")),
                Collections.emptyList(),1,()->false);fail("Ambiguous identities accepted");}
        catch(IllegalArgumentException expected){}
    }
}
