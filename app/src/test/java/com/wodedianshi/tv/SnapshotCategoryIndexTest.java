package com.wodedianshi.tv;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotCategoryIndexTest {
    @Test public void preparedMembershipCanBeAdoptedOnlyForExactSnapshot(){
        java.util.Map<String,List<Integer>> provinces=new java.util.HashMap<>();
        provinces.put("福建",Arrays.asList(3,6));
        SnapshotCategoryIndex.LocalMembership membership=new SnapshotCategoryIndex.LocalMembership(
            10,Arrays.asList(3,6,9),provinces);
        SnapshotCategoryIndex.PreparedLocal prepared=new SnapshotCategoryIndex.PreparedLocal(7,membership);
        provinces.clear();
        assertTrue(prepared.matches(7,10));
        assertFalse(prepared.matches(8,10));
        assertFalse(prepared.matches(7,11));
        SnapshotCategoryIndex cache=new SnapshotCategoryIndex();
        cache.putLocalMembership(7,10,prepared.membership);
        assertEquals(Arrays.asList(3,6),cache.localCandidates(7,10,"福建"));
        assertNull(cache.localCandidates(8,10,"福建"));
        assertEquals(Arrays.asList(3,6,9),prepared.membership.candidates(null));
    }

    @Test public void completeProvinceIndexRetainsEmptyAndAmbiguousMembership(){
        SnapshotCategoryIndex cache=new SnapshotCategoryIndex();
        java.util.Map<String,List<Integer>> provinces=new java.util.HashMap<>();
        ArrayList<Integer> fujian=new ArrayList<>(Arrays.asList(2,8));
        provinces.put("福建",fujian);
        provinces.put("广东",Arrays.asList(5));
        cache.putLocalIndex(10,20,Arrays.asList(2,5,7,8),provinces);
        fujian.clear();provinces.clear();
        assertTrue(cache.hasLocalIndex(10,20));
        assertEquals(Arrays.asList(2,5,7,8),cache.localCandidates(10,20,null));
        assertEquals(Arrays.asList(2,8),cache.localCandidates(10,20,"福建"));
        assertEquals(Arrays.asList(5),cache.localCandidates(10,20,"广东"));
        assertTrue(cache.localCandidates(10,20,"湖北").isEmpty());
        for(int i=0;i<20;i++)cache.put(10,20,Arrays.asList("地方","福建","city"+i),Arrays.asList(2));
        assertEquals(Arrays.asList(2,8),cache.localCandidates(10,20,"福建"));
        assertFalse(cache.hasLocalIndex(11,20));
        assertNull(cache.localCandidates(11,20,"福建"));
    }

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
