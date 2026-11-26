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

    private LocalMembership localMembership;

    /** Immutable transfer from the snapshot preparation worker to the filter worker. */
    static final class LocalMembership {
        final int count;
        private final java.util.Map<String,List<Integer>> provinces;
        private final List<Integer> allLocal;
        private final java.util.Map<String,List<Integer>> fixedCategories;
        private final java.util.Map<String,java.util.Set<String>> geoKeys;
        private final boolean geoPrepared;
        LocalMembership(int count,List<Integer> local,java.util.Map<String,List<Integer>> byProvince){
            this(count,local,byProvince,Collections.emptyMap());
        }
        LocalMembership(int count,List<Integer> local,java.util.Map<String,List<Integer>> byProvince,
                java.util.Map<String,List<Integer>> fixed){
            this(count,local,byProvince,fixed,null);
        }
        LocalMembership(int count,List<Integer> local,java.util.Map<String,List<Integer>> byProvince,
                java.util.Map<String,List<Integer>> fixed,java.util.Map<String,java.util.Set<String>> geo){
            this.count=count;
            geoPrepared=geo!=null;
            java.util.Map<String,java.util.Set<String>> geoCopy=new java.util.HashMap<>();
            if(geo!=null)for(java.util.Map.Entry<String,java.util.Set<String>> entry:geo.entrySet())
                geoCopy.put(entry.getKey(),Collections.unmodifiableSet(new java.util.HashSet<>(entry.getValue())));
            geoKeys=Collections.unmodifiableMap(geoCopy);
            if(byProvince.size()>MAX_PROVINCES)throw new IllegalArgumentException("Province index limit");
            java.util.Map<String,List<Integer>> copy=new java.util.HashMap<>();
            for(java.util.Map.Entry<String,List<Integer>> entry:byProvince.entrySet())
                copy.put(entry.getKey(),Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            allLocal=Collections.unmodifiableList(new ArrayList<>(local));
            provinces=Collections.unmodifiableMap(copy);
            java.util.Map<String,List<Integer>> categories=new java.util.HashMap<>();
            for(java.util.Map.Entry<String,List<Integer>> entry:fixed.entrySet())
                categories.put(entry.getKey(),Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            fixedCategories=Collections.unmodifiableMap(categories);
        }
        List<Integer> fixedCategory(String category){return fixedCategories.get(category);}
        java.util.Set<String> geoKeys(String scope){
            if(!geoPrepared)return null;
            java.util.Set<String> keys=geoKeys.get(scope);
            return keys==null?Collections.emptySet():keys;
        }
        List<Integer> candidates(String province){
            if(province==null)return allLocal;
            List<Integer> found=provinces.get(province);
            return found==null?Collections.emptyList():found;
        }
    }

    static final class PreparedLocal {
        final long mutation;
        final LocalMembership membership;
        PreparedLocal(long mutation,LocalMembership membership){
            this.mutation=mutation;this.membership=membership;
        }
        boolean matches(long epoch,int count){
            return membership!=null&&mutation==epoch&&membership.count==count;
        }
    }

    /** Geographic and ordinary categories must never share positional cache entries. */
    static List<String> scopeKey(String category,String province,String city,String geoScope,String country){
        return java.util.Arrays.asList(category,province,city,geoScope,geoScope==null?null:country);
    }

    private void ensureVersion(long mutation,int count){
        if(epoch!=mutation||size!=count){
            scopes.clear();localMembership=null;epoch=mutation;size=count;
        }
    }

    boolean hasLocalIndex(long mutation,int count){
        ensureVersion(mutation,count);
        return localMembership!=null;
    }

    void putLocalIndex(long mutation,int count,List<Integer> local,
            java.util.Map<String,List<Integer>> byProvince){
        putLocalMembership(mutation,count,new LocalMembership(count,local,byProvince));
    }

    void putLocalMembership(long mutation,int count,LocalMembership membership){
        ensureVersion(mutation,count);
        if(membership.count!=count)throw new IllegalArgumentException("Local index size mismatch");
        localMembership=membership;
    }

    List<Integer> localCandidates(long mutation,int count,String province){
        ensureVersion(mutation,count);
        return localMembership==null?null:localMembership.candidates(province);
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
