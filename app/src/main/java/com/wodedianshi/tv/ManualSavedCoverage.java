package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Exact, per-manual-round coverage of probe opportunities, not valid-source scores.
 * Shard compact SHA-256 identities so no giant Room state row is necessary. */
final class ManualSavedCoverage {
    private static final int SHARDS=64;
    private static final String PREFIX="source_manual_covered_v1_";
    private final MessageDigest digest;
    private final Set<String> covered=new HashSet<>();
    private final Set<Integer> dirty=new HashSet<>();

    ManualSavedCoverage(Map<String,String> persisted) {
        try{digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        for(int shard=0;shard<SHARDS;shard++){
            String body=persisted.get(key(shard));
            if(body==null||body.isEmpty())continue;
            if(body.length()%64!=0){dirty.add(shard);continue;}
            for(int i=0;i<body.length();i+=64){
                String id=body.substring(i,i+64);
                if(isHex(id)&&shard(id)==shard)covered.add(id);
                else dirty.add(shard);
            }
        }
    }
    static List<String> keys(){
        List<String> keys=new ArrayList<>(SHARDS);
        for(int i=0;i<SHARDS;i++)keys.add(key(i));
        return keys;
    }
    private static String key(int shard){return PREFIX+shard;}
    private static boolean isHex(String id){
        if(id.length()!=64)return false;
        for(int i=0;i<id.length();i++){
            char c=id.charAt(i);
            if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
        }
        return true;
    }
    private static int shard(String id){
        return (Character.digit(id.charAt(0),16)*16+Character.digit(id.charAt(1),16))%SHARDS;
    }
    String identity(String normalizedEndpoint){
        byte[] bytes=digest.digest(normalizedEndpoint.getBytes(StandardCharsets.UTF_8));
        char[] hex="0123456789abcdef".toCharArray(),out=new char[64];
        for(int i=0;i<bytes.length;i++){
            int b=bytes[i]&255;out[i*2]=hex[b>>>4];out[i*2+1]=hex[b&15];
        }
        return new String(out);
    }
    boolean contains(String id){return covered.contains(id);}
    void mark(String id){if(covered.add(id))dirty.add(shard(id));}
    void retainOnly(Set<String> current){
        Iterator<String> it=covered.iterator();
        while(it.hasNext()){
            String id=it.next();
            if(!current.contains(id)){dirty.add(shard(id));it.remove();}
        }
    }
    Map<String,String> changes(){
        Map<Integer,StringBuilder> bodies=new HashMap<>();
        for(int shard:dirty)bodies.put(shard,new StringBuilder());
        for(String id:covered){
            StringBuilder body=bodies.get(shard(id));
            if(body!=null)body.append(id);
        }
        Map<String,String> changes=new HashMap<>();
        for(Map.Entry<Integer,StringBuilder> entry:bodies.entrySet())
            changes.put(key(entry.getKey()),entry.getValue().toString());
        return changes;
    }
}
