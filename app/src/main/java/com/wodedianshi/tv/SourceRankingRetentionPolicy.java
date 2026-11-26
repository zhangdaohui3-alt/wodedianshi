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

    /**
     * Input is already quality-ranked from best to worst. Required URLs are retained
     * even when they fall below the cap; remaining slots are filled in ranked order.
     * The caller may sort the returned URLs again by its own score comparator.
     */
    public static List<String> retainRequiredAndBest(List<String> ranked,
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
