package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Retains stable channel identity and the last selected route under a source-count cap. */
public final class SourceRankingRetentionPolicy {
    private SourceRankingRetentionPolicy() {}

    /** One ranking operation only: preserve descending score and stable ties,
     * without recomputing the same URL for each comparator invocation. */
    static java.util.Comparator<String> memoizedBestFirst(java.util.function.ToDoubleFunction<String> score) {
        java.util.HashMap<String,Double> scores=new java.util.HashMap<>();
        return (a,b)->Double.compare(scores.computeIfAbsent(b,key->score.applyAsDouble(key)),
                scores.computeIfAbsent(a,key->score.applyAsDouble(key)));
    }

    /** A complete ranking pass has immutable scores and a fixed clock.
     * Reuse calculations across channels with a bounded access-order cache,
     * rather than allocate a separate cache for every row. */
    static java.util.Comparator<String> boundedBestFirst(
            java.util.function.ToDoubleFunction<String> score,int capacity) {
        final int limit=Math.max(1,capacity);
        java.util.LinkedHashMap<String,Double> scores=new java.util.LinkedHashMap<String,Double>(16,0.75f,true){
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Double> eldest){
                return size()>limit;
            }
        };
        return (a,b)->Double.compare(scores.computeIfAbsent(b,key->score.applyAsDouble(key)),
                scores.computeIfAbsent(a,key->score.applyAsDouble(key)));
    }

    /**
     * Input is already quality-ranked from best to worst. Required URLs are retained
     * even when they fall below the cap; remaining slots are filled in ranked order.
     * The caller may sort the returned URLs again by its own score comparator.
     */
    public static ArrayList<String> retainRequiredAndBest(List<String> ranked,
            String primaryUrl, String activeUrl, int limit) {
        if (limit <= 0) return new ArrayList<>();
        LinkedHashSet<String> retained = new LinkedHashSet<>();
        addIfValid(retained, activeUrl, limit);
        addIfValid(retained, primaryUrl, limit);
        if (ranked != null) {
            for (String url : ranked) {
                if (retained.size() >= limit) break;
                addIfValid(retained, url, limit);
            }
        }
        return new ArrayList<>(retained);
    }

    /** Common already-deduplicated rows fit the cap with both required URLs
     * present. Put required ties first, then sort once instead of sorting
     * before retention and again afterwards. Unusual inputs keep the old path. */
    static ArrayList<String> rankRequiredAndBest(ArrayList<String> urls,String primary,
            String active,int limit,java.util.Comparator<String> bestFirst){
        boolean fits=urls.size()<=limit&&urls.contains(primary)&&urls.contains(active);
        if(fits)for(String url:urls){
            if(url==null||!url.equals(url.trim())
                    ||!(url.startsWith("http://")||url.startsWith("https://"))){
                fits=false;break;
            }
        }
        if(fits){
            // A valid single route already represents both required identities.
            if(urls.size()==1)return urls;
            ArrayList<String> retained=retainRequiredAndBest(urls,primary,active,limit);
            retained.sort(bestFirst);
            return retained;
        }
        urls.sort(bestFirst);
        ArrayList<String> retained=retainRequiredAndBest(urls,primary,active,limit);
        if(urls.equals(retained))return urls;
        retained.sort(bestFirst);
        return retained;
    }

    /** Select only surplus keys after the caller chooses its protected/recent
     * and fallback set. Do not copy or rewrite values which will be retained. */
    static List<String> surplusScoreKeys(java.util.Iterator<String> keys,
            java.util.Set<String> keep,java.util.function.BooleanSupplier paused) {
        List<String> surplus=new ArrayList<>();
        while(keys.hasNext()){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            String key=keys.next();
            if(!keep.contains(key))surplus.add(key);
        }
        return surplus;
    }

    /** A list merge can shift or deduplicate the selected URL; never reuse its old index. */
    static int restoreSelectedIndex(List<String> urls,String selected){
        if(urls==null||selected==null||selected.isEmpty())return 0;
        int exact=urls.indexOf(selected);
        if(exact>=0)return exact;
        String endpoint=SourceSelectionPolicy.endpointKey(selected);
        for(int i=0;i<urls.size();i++)if(endpoint.equals(SourceSelectionPolicy.endpointKey(urls.get(i))))return i;
        return 0;
    }

    private static void addIfValid(LinkedHashSet<String> values, String url, int limit) {
        if (url == null || values.size() >= limit) return;
        String value = url.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) values.add(value);
    }
}
