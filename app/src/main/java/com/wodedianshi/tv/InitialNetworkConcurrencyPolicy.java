package com.wodedianshi.tv;

import java.util.HashSet;

/** Owner-confined recent initial-idle transport observations. Not a bandwidth
 * measurement: a single failing provider must not throttle healthy providers. */
final class InitialNetworkConcurrencyPolicy {
    static final int WINDOW=12;
    static final long FRESH_MS=60000L;
    private final String[] origins=new String[WINDOW];
    private final boolean[] failures=new boolean[WINDOW];
    private final long[] times=new long[WINDOW];
    private int next,size;
    void clear(){next=0;size=0;}
    void observe(String origin,long now,boolean failed,boolean reached){
        if(origin==null||origin.isEmpty()||now<0L||(!failed&&!reached))return;
        origins[next]=origin;failures[next]=failed&&!reached;times[next]=now;
        next=(next+1)%WINDOW;size=Math.min(WINDOW,size+1);
    }
    int limit(int existingCeiling,long now){
        if(existingCeiling<=2)return existingCeiling;
        int count=0,bad=0;
        HashSet<String> badOrigins=new HashSet<>();
        for(int i=0;i<size;i++){
            if(now<times[i]||now-times[i]>=FRESH_MS)continue;
            count++;
            if(failures[i]){bad++;badOrigins.add(origins[i]);}
        }
        // Reduce only on broad, sustained first-request trouble. Rolling
        // observations age out naturally, restoring the existing ceiling.
        if(count>=8&&bad*4>=count*3&&badOrigins.size()>=3)
            return Math.min(existingCeiling,4);
        return existingCeiling;
    }
}
