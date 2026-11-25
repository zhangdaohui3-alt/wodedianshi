package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class SourceRankingRetentionPolicyTest {
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
