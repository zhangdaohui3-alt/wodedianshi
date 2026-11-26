package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Confined to the existing directory filter worker. Only unfiltered scope
 * memberships are stored; every position belongs to one catalogue epoch. */
final class SnapshotCategoryIndex {
    private static final int MAX_SCOPES=12;
    private long epoch=Long.MIN_VALUE;
    private int size=-1;
    private final LinkedHashMap<List<String>,List<Integer>> scopes=new LinkedHashMap<>(16,0.75f,true);

    private void ensureVersion(long mutation,int count){
        if(epoch!=mutation||size!=count){
            scopes.clear();epoch=mutation;size=count;
        }
    }

    List<Integer> get(long mutation,int count,List<String> key){
        ensureVersion(mutation,count);
        return scopes.get(key);
    }

    void put(long mutation,int count,List<String> key,List<Integer> indexes){
        ensureVersion(mutation,count);
        scopes.put(new ArrayList<>(key),Collections.unmodifiableList(new ArrayList<>(indexes)));
        while(scopes.size()>MAX_SCOPES)scopes.remove(scopes.keySet().iterator().next());
    }
}
