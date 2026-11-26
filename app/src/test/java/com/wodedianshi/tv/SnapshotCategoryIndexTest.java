package com.wodedianshi.tv;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotCategoryIndexTest {
    @Test public void sameSizeReplacementInvalidatesEveryScope(){
        SnapshotCategoryIndex cache=new SnapshotCategoryIndex();
        List<String> fujian=Arrays.asList("地方","福建",null);
        List<String> fuzhou=Arrays.asList("地方","福建","福州");
        cache.put(1,100,fujian,Arrays.asList(2,7,9));
        cache.put(1,100,fuzhou,Arrays.asList(7));
        assertEquals(Arrays.asList(2,7,9),cache.get(1,100,fujian));
        assertEquals(Arrays.asList(7),cache.get(1,100,fuzhou));
        assertNull(cache.get(2,100,fujian));
        assertNull(cache.get(2,100,fuzhou));
    }
    @Test public void preservesOrderAndCachesEmptyScopeWithoutAliasing(){
        SnapshotCategoryIndex cache=new SnapshotCategoryIndex();
        List<String> key=Arrays.asList("央视",null,null);
        ArrayList<Integer> values=new ArrayList<>(Arrays.asList(8,3,1));
        cache.put(1,10,key,values);
        values.clear();
        assertEquals(Arrays.asList(8,3,1),cache.get(1,10,key));
        List<String> empty=Arrays.asList("地方","福建","福州");
        cache.put(1,10,empty,java.util.Collections.emptyList());
        assertNotNull(cache.get(1,10,empty));
        assertTrue(cache.get(1,10,empty).isEmpty());
        assertNull(cache.get(1,11,key));
    }
    @Test public void cacheIsBoundedAndRecentScopesRemain(){
        SnapshotCategoryIndex cache=new SnapshotCategoryIndex();
        for(int i=0;i<13;i++)cache.put(1,20,Arrays.asList("地方","福建","city"+i),Arrays.asList(i));
        assertNull(cache.get(1,20,Arrays.asList("地方","福建","city0")));
        assertEquals(Arrays.asList(12),cache.get(1,20,Arrays.asList("地方","福建","city12")));
    }
}
