package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class SourceRankingRetentionPolicyTest {
    @Test public void memoizedRankingReusesScoresAcrossBothSortsAndPreservesTies() {
        java.util.Map<String,Integer> calls=new java.util.HashMap<>();
        java.util.Map<String,Double> values=new java.util.HashMap<>();
        values.put("https://a",10.0);values.put("https://b",10.0);
        values.put("https://c",20.0);values.put("https://required",1.0);
        java.util.Comparator<String> cmp=SourceRankingRetentionPolicy.memoizedBestFirst(url->{
            calls.put(url,calls.getOrDefault(url,0)+1);return values.get(url);
        });
        java.util.List<String> ranked=new java.util.ArrayList<>(Arrays.asList("https://a","https://b","https://c"));
        ranked.sort(cmp);
        assertEquals(Arrays.asList("https://c","https://a","https://b"),ranked);
        ranked=SourceRankingRetentionPolicy.retainRequiredAndBest(ranked,"https://required","",3);
        ranked.sort(cmp);
        assertEquals(Arrays.asList("https://c","https://a","https://required"),ranked);
        assertEquals(4,calls.size());
        for(int count:calls.values())assertEquals(1,count);
    }

    @Test public void independentRankingOperationReadsUpdatedScore() {
        java.util.Map<String,Double> values=new java.util.HashMap<>();
        values.put("a",2.0);values.put("b",1.0);
        java.util.Comparator<String> first=SourceRankingRetentionPolicy.memoizedBestFirst(values::get);
        org.junit.Assert.assertTrue(first.compare("a","b")<0);
        values.put("b",3.0);
        java.util.Comparator<String> next=SourceRankingRetentionPolicy.memoizedBestFirst(values::get);
        org.junit.Assert.assertTrue(next.compare("a","b")>0);
    }

    @Test public void keepsStablePrimaryAndLastSelectedEvenBelowRankingCutoff() {
        assertEquals(Arrays.asList(
                "https://active.example/live.m3u8",
                "https://primary.example/live.m3u8",
                "https://best.example/live.m3u8"),
            SourceRankingRetentionPolicy.retainRequiredAndBest(
                Arrays.asList(
                    "https://best.example/live.m3u8",
                    "https://primary.example/live.m3u8",
                    "https://second.example/live.m3u8",
                    "https://active.example/live.m3u8",
                    "https://last.example/live.m3u8"),
                "https://primary.example/live.m3u8",
                "https://active.example/live.m3u8", 3));
    }

    @Test public void deduplicatesWhenPrimaryAndActiveAreTheSame() {
        assertEquals(Arrays.asList("https://same.example/live.m3u8",
                "https://best.example/live.m3u8"),
            SourceRankingRetentionPolicy.retainRequiredAndBest(
                Arrays.asList("https://same.example/live.m3u8",
                    "https://best.example/live.m3u8"),
                "https://same.example/live.m3u8",
                "https://same.example/live.m3u8", 2));
    }

    @Test public void preservesPrimaryEvenWhenItWasMissingFromRankedList() {
        assertEquals(Arrays.asList("https://primary.example/live.m3u8",
                "https://best.example/live.m3u8"),
            SourceRankingRetentionPolicy.retainRequiredAndBest(
                Collections.singletonList("https://best.example/live.m3u8"),
                "https://primary.example/live.m3u8", "", 2));
    }

    @Test public void ignoresInvalidUrlsAndHonorsZeroLimit() {
        assertEquals(Collections.singletonList("https://ok.example/live.m3u8"),
            SourceRankingRetentionPolicy.retainRequiredAndBest(
                Arrays.asList("file:///bad", "https://ok.example/live.m3u8"),
                "not-a-url", "", 1));
        assertEquals(Collections.emptyList(),
            SourceRankingRetentionPolicy.retainRequiredAndBest(
                Collections.singletonList("https://ok.example/live.m3u8"),
                "https://ok.example/live.m3u8", "", 0));
    }
}
