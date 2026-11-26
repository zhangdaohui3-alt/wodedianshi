package com.wodedianshi.tv;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Collection;
import java.util.concurrent.Future;

/** Owner-confined admission for a contiguous idle discovery wave. */
final class IdleProbeWavePolicy {
    static final int MAX_CONCURRENT=6;
    static final int ORDINARY_CONCURRENT=4;
    static final int MAX_PER_HOST=2;
    // Viewing work stays serial or paused under the existing playback gate.
    // Explicit manual refresh may use the same bounded executor as first build.
    static boolean permitsParallel(boolean initial,boolean manual,boolean viewing) {
        return !viewing&&(initial||manual);
    }
    static boolean canStart(boolean initial,long now,long addressDeadline,long batchDeadline) {
        if(initial)return SourceProbeTimePolicy.hasWaveDrainMargin(batchDeadline,addressDeadline);
        // Manual probes retain their original full remaining stage budget (not
        // the first-build 15s address cap). Avoid speculative waves near its end.
        return now>=0L&&batchDeadline>=now&&batchDeadline-now>=17000L;
    }
    /** Only rows that provably cannot start a fresh probe may be bridged. */
    static boolean canBridgeKnownRow(boolean http,boolean measured) {
        return !http||measured;
    }
    private final int maxConcurrent;
    IdleProbeWavePolicy(){this(ORDINARY_CONCURRENT);}
    IdleProbeWavePolicy(int limit){maxConcurrent=Math.max(1,Math.min(MAX_CONCURRENT,limit));}
    private final HashSet<String> endpoints=new HashSet<>();
    private final HashMap<String,Integer> hosts=new HashMap<>();
    boolean canAdd(String endpoint,String host) {
        return endpoint!=null&&!endpoint.isEmpty()&&host!=null&&!host.isEmpty()
                &&endpoints.size()<maxConcurrent&&!endpoints.contains(endpoint)
                &&hosts.getOrDefault(host,0)<MAX_PER_HOST;
    }
    void add(String endpoint,String host) {
        if(!canAdd(endpoint,host))throw new IllegalArgumentException("Probe wave admission");
        endpoints.add(endpoint);
        hosts.put(host,hosts.getOrDefault(host,0)+1);
    }
    /** Deterministic host round-robin within equal priority, never dropping a row.
     * Applied to an entire fixed initial page, independent of playback state. */
    static <T> java.util.ArrayList<T> interleaveHosts(java.util.List<T> entries,
            java.util.function.Function<T,String> hostOf,
            java.util.function.ToIntFunction<T> priorityOf) {
        java.util.TreeMap<Integer,java.util.LinkedHashMap<String,java.util.ArrayDeque<T>>> groups=
                new java.util.TreeMap<>();
        for(T entry:entries){
            java.util.LinkedHashMap<String,java.util.ArrayDeque<T>> hosts=
                    groups.computeIfAbsent(priorityOf.applyAsInt(entry),k->new java.util.LinkedHashMap<>());
            String host=hostOf.apply(entry);
            hosts.computeIfAbsent(host==null?"":host,k->new java.util.ArrayDeque<>()).add(entry);
        }
        java.util.ArrayList<T> ordered=new java.util.ArrayList<>(entries.size());
        for(java.util.LinkedHashMap<String,java.util.ArrayDeque<T>> hosts:groups.values()){
            while(!hosts.isEmpty()){
                java.util.Iterator<java.util.ArrayDeque<T>> iterator=hosts.values().iterator();
                while(iterator.hasNext()){
                    java.util.ArrayDeque<T> queue=iterator.next();
                    ordered.add(queue.removeFirst());
                    if(queue.isEmpty())iterator.remove();
                }
            }
        }
        return ordered;
    }

    /** Within a fixed priority group, try one address for each distinct station
     * before its second address, then its third, etc. Host balance remains
     * bounded within each round. Ordering depends only on immutable page rows. */
    static <T> java.util.ArrayList<T> coverageFirst(java.util.List<T> entries,
            java.util.function.Function<T,String> stationOf,
            java.util.function.Function<T,String> hostOf,
            java.util.function.ToIntFunction<T> priorityOf) {
        return coverageFirst(entries,stationOf,hostOf,priorityOf,entry->"");
    }

    /** Optional regional balance within each station round and host. Keep the
     * outer priority and host rotation unchanged; unknown regions remain a bucket. */
    static <T> java.util.ArrayList<T> coverageFirst(java.util.List<T> entries,
            java.util.function.Function<T,String> stationOf,
            java.util.function.Function<T,String> hostOf,
            java.util.function.ToIntFunction<T> priorityOf,
            java.util.function.Function<T,String> regionOf) {
        java.util.TreeMap<Integer,java.util.LinkedHashMap<String,java.util.ArrayDeque<T>>> groups=
                new java.util.TreeMap<>();
        for(T entry:entries){
            groups.computeIfAbsent(priorityOf.applyAsInt(entry),k->new java.util.LinkedHashMap<>())
                .computeIfAbsent(stationOf.apply(entry),k->new java.util.ArrayDeque<>()).add(entry);
        }
        java.util.ArrayList<T> ordered=new java.util.ArrayList<>(entries.size());
        for(java.util.LinkedHashMap<String,java.util.ArrayDeque<T>> stations:groups.values()){
            while(!stations.isEmpty()){
                java.util.ArrayList<T> round=new java.util.ArrayList<>(stations.size());
                java.util.Iterator<java.util.ArrayDeque<T>> iterator=stations.values().iterator();
                while(iterator.hasNext()){
                    java.util.ArrayDeque<T> queue=iterator.next();
                    round.add(queue.removeFirst());
                    if(queue.isEmpty())iterator.remove();
                }
                // Preserve host order and rotate regions independently inside
                // each host, then use the existing host round-robin.
                java.util.LinkedHashMap<String,java.util.ArrayList<T>> byHost=new java.util.LinkedHashMap<>();
                for(T entry:round){
                    String host=hostOf.apply(entry);
                    byHost.computeIfAbsent(host==null?"":host,k->new java.util.ArrayList<>()).add(entry);
                }
                java.util.ArrayList<T> balanced=new java.util.ArrayList<>(round.size());
                for(java.util.ArrayList<T> hostRows:byHost.values())
                    balanced.addAll(interleaveHosts(hostRows,regionOf,entry->0));
                ordered.addAll(interleaveHosts(balanced,hostOf,entry->0));
            }
        }
        return ordered;
    }

    /** Never treat cancellation as proof the underlying network call exited. */
    static boolean allFinished(Collection<? extends Future<?>> futures) {
        for(Future<?> future:futures)
            if(!future.isDone()||future.isCancelled())return false;
        return true;
    }
}
