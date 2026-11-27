package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class SourceRankingRetentionPolicyTest {
    @Test public void trimRemovesOnlyKeysOutsideChosenKeepSet() {
        java.util.Set<String> keep=new java.util.HashSet<>(Arrays.asList("saved","recent","fallback"));
        assertEquals(Arrays.asList("old1","old2"),SourceRankingRetentionPolicy.surplusScoreKeys(
            Arrays.asList("old1","saved","recent","old2","fallback").iterator(),keep,()->false));
        assertEquals(Collections.emptyList(),SourceRankingRetentionPolicy.surplusScoreKeys(
            Arrays.asList("saved","fallback").iterator(),keep,()->false));
        try{
            SourceRankingRetentionPolicy.surplusScoreKeys(Arrays.asList("old").iterator(),keep,()->true);
            org.junit.Assert.fail("Cancelled prune must not continue");
        }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
    }

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
    @org.junit.Test public void mergeRestoresSelectionByUrlInsteadOfStaleIndex(){
        java.util.List<String> reordered=java.util.Arrays.asList("https://example.invalid/b","https://example.invalid/a");
        org.junit.Assert.assertEquals(1,SourceRankingRetentionPolicy.restoreSelectedIndex(reordered,"https://example.invalid/a"));
        org.junit.Assert.assertEquals(1,SourceRankingRetentionPolicy.restoreSelectedIndex(reordered,"https://EXAMPLE.invalid:443/a"));
        org.junit.Assert.assertEquals(0,SourceRankingRetentionPolicy.restoreSelectedIndex(reordered,"https://example.invalid/a?token=other"));
    }

    @Test public void sharedRankingMatchesRowLocalOrderAcrossTwentyThousandRoutePositions(){
        java.util.concurrent.atomic.AtomicInteger oldCalls=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger newCalls=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.ToDoubleFunction<String> score=url->url.hashCode()%11;
        java.util.Comparator<String> shared=SourceRankingRetentionPolicy.boundedBestFirst(url->{
            newCalls.incrementAndGet();return score.applyAsDouble(url);
        },1024);
        for(int i=0;i<5000;i++){
            String active="https://example.invalid/shared/"+(i%16);
            String primary="https://example.invalid/primary/"+i;
            java.util.List<String> input=Arrays.asList(active,primary,
                "https://example.invalid/shared/"+((i+1)%16),
                "https://example.invalid/shared/"+((i+2)%16));
            java.util.Comparator<String> old=SourceRankingRetentionPolicy.memoizedBestFirst(url->{
                oldCalls.incrementAndGet();return score.applyAsDouble(url);
            });
            java.util.ArrayList<String> expected=new java.util.ArrayList<>(input);
            expected.sort(old);
            expected=new java.util.ArrayList<>(SourceRankingRetentionPolicy.retainRequiredAndBest(expected,primary,active,3));
            expected.sort(old);
            java.util.ArrayList<String> actual=new java.util.ArrayList<>(input);
            actual.sort(shared);
            java.util.List<String> retained=SourceRankingRetentionPolicy.retainRequiredAndBest(actual,primary,active,3);
            if(!actual.equals(retained)){actual=new java.util.ArrayList<>(retained);actual.sort(shared);}
            assertEquals(expected,actual);
        }
        assertEquals(20000,oldCalls.get());
        assertEquals(5016,newCalls.get());
    }

    @Test public void boundedRankingEvictsOldEvidenceAndRetainsComparatorOrder(){
        java.util.Map<String,Integer> calls=new java.util.HashMap<>();
        java.util.Comparator<String> cmp=SourceRankingRetentionPolicy.boundedBestFirst(url->{
            calls.put(url,calls.getOrDefault(url,0)+1);return url.charAt(0);
        },2);
        org.junit.Assert.assertTrue(cmp.compare("b","a")<0);
        org.junit.Assert.assertTrue(cmp.compare("c","b")<0);
        org.junit.Assert.assertTrue(cmp.compare("a","b")>0);
        assertEquals(Integer.valueOf(2),calls.get("a"));
    }
}
