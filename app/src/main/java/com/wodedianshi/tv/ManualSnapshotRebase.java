package com.wodedianshi.tv;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Conservative three-way merge: user edits win for the affected channel.
 * Ambiguous stable identities fail closed rather than guessing. */
final class ManualSnapshotRebase {
    static final class Row<T>{
        final String identity,baseIdentity,baseFingerprint,fingerprint;
        final T value;
        Row(String identity,String baseIdentity,String baseFingerprint,String fingerprint,T value){
            this.identity=identity;this.baseIdentity=baseIdentity;
            this.baseFingerprint=baseFingerprint;this.fingerprint=fingerprint;this.value=value;
        }
    }
    static <T> List<T> merge(List<Row<T>> staged,List<Row<T>> live,int expectedBaseCount,
                             BooleanSupplier paused){
        Map<String,Row<T>> originals=new HashMap<>(),fresh=new LinkedHashMap<>();
        Set<String> stagedIdentities=new HashSet<>(),liveIdentities=new HashSet<>();
        for(Row<T> row:staged){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(row.identity.isEmpty()||!stagedIdentities.add(row.identity))
                throw new IllegalArgumentException("Ambiguous staged identity");
            if(!row.baseIdentity.isEmpty()){
                if(row.baseFingerprint.isEmpty()||originals.put(row.baseIdentity,row)!=null)
                    throw new IllegalArgumentException("Ambiguous baseline identity");
            }else fresh.put(row.identity,row);
        }
        if(originals.size()!=expectedBaseCount)
            throw new IllegalArgumentException("Missing baseline identities");
        for(String identity:fresh.keySet())if(originals.containsKey(identity))
            throw new IllegalArgumentException("Rediscovered baseline collision");
        // Live order preserves user additions and changes. Omitted baseline
        // channels were deleted by the user; never append them from staging.
        List<T> result=new ArrayList<>();
        for(Row<T> current:live){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(current.identity.isEmpty()||!liveIdentities.add(current.identity))
                throw new IllegalArgumentException("Ambiguous live identity");
            Row<T> old=originals.get(current.identity);
            if(old!=null&&old.baseFingerprint.equals(current.fingerprint)
                    &&old.identity.equals(current.identity))result.add(old.value);
            else result.add(current.value);
            fresh.remove(current.identity);
        }
        for(Row<T> row:fresh.values()){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            result.add(row.value);
        }
        return result;
    }
}
