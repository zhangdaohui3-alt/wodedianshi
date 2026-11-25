package com.wodedianshi.tv;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Collection;
import java.util.concurrent.Future;

/** Owner-confined admission for a contiguous idle discovery wave. */
final class IdleProbeWavePolicy {
    static final int MAX_CONCURRENT=4;
    static final int MAX_PER_HOST=2;
    private final HashSet<String> endpoints=new HashSet<>();
    private final HashMap<String,Integer> hosts=new HashMap<>();
    boolean canAdd(String endpoint,String host) {
        return endpoint!=null&&!endpoint.isEmpty()&&host!=null&&!host.isEmpty()
                &&endpoints.size()<MAX_CONCURRENT&&!endpoints.contains(endpoint)
                &&hosts.getOrDefault(host,0)<MAX_PER_HOST;
    }
    void add(String endpoint,String host) {
        if(!canAdd(endpoint,host))throw new IllegalArgumentException("Probe wave admission");
        endpoints.add(endpoint);
        hosts.put(host,hosts.getOrDefault(host,0)+1);
    }
    /** Never treat cancellation as proof the underlying network call exited. */
    static boolean allFinished(Collection<? extends Future<?>> futures) {
        for(Future<?> future:futures)
            if(!future.isDone()||future.isCancelled())return false;
        return true;
    }
}
