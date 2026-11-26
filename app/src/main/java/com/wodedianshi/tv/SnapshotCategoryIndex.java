package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Confined to the existing directory filter worker. Only unfiltered scope
 * memberships are stored; every position belongs to one catalogue epoch. */
final class SnapshotCategoryIndex {
    private static final int MAX_SCOPES=12;
    private static final int MAX_PROVINCES=31;
    private long epoch=Long.MIN_VALUE;
    private int size=-1;
    private final LinkedHashMap<List<String>,List<Integer>> scopes=new LinkedHashMap<>(16,0.75f,true);

    private java.util.Map<String,List<Integer>> provinces;
    private List<Integer> allLocal;

    private void ensureVersion(long mutation,int count){
        if(epoch!=mutation||size!=count){
            scopes.clear();provinces=null;allLocal=null;epoch=mutation;size=count;
        }
    }

    boolean hasLocalIndex(long mutation,int count){
        ensureVersion(mutation,count);
        return provinces!=null;
    }

    void putLocalIndex(long mutation,int count,List<Integer> local,
            java.util.Map<String,List<Integer>> byProvince){
        ensureVersion(mutation,count);
        if(byProvince.size()>MAX_PROVINCES)throw new IllegalArgumentException("Province index limit");
        java.util.Map<String,List<Integer>> copy=new java.util.HashMap<>();
        for(java.util.Map.Entry<String,List<Integer>> entry:byProvince.entrySet())
            copy.put(entry.getKey(),Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        allLocal=Collections.unmodifiableList(new ArrayList<>(local));
        provinces=Collections.unmodifiableMap(copy);
    }

    List<Integer> localCandidates(long mutation,int count,String province){
        ensureVersion(mutation,count);
        if(provinces==null)return null;
        if(province==null)return allLocal;
        List<Integer> found=provinces.get(province);
        return found==null?Collections.emptyList():found;
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
