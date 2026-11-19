package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class SourceChannelMergePolicyTest {
    private static final java.util.Comparator<String> BEST_FIRST =
            (a, b) -> Integer.compare(score(b), score(a));

    private static int score(String url) {
        if (url.contains("best")) return 100;
        if (url.contains("mid")) return 50;
        if (url.contains("weak")) return 1;
        return 10;
    }

    @Test public void attachesGloballyKnownEndpointToSecondChannelIdentity() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://channel-a.example/live.m3u8"),
                "https://shared.example/live.m3u8", 8, BEST_FIRST);
        assertEquals(Arrays.asList("https://channel-a.example/live.m3u8",
                "https://shared.example/live.m3u8"), result);
    }

    @Test public void deduplicatesEndpointAliasesAlreadyAttachedToChannel() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://CDN.example:443/live.m3u8"),
                "https://cdn.example/live.m3u8", 8, BEST_FIRST);
        assertEquals(1, result.size());
    }

    @Test public void replacesOnlyTheWorstSourceWhenChannelIsAtCapacity() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://best.example/live.m3u8",
                        "https://weak.example/live.m3u8"),
                "https://mid.example/live.m3u8", 2, BEST_FIRST);
        assertEquals(Arrays.asList("https://best.example/live.m3u8",
                "https://mid.example/live.m3u8"), result);
    }

    @Test public void preservesExactActiveUrlWhenDeduplicatingEquivalentEndpoint() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://cdn.example:443/live.m3u8",
                        "https://CDN.example/live.m3u8"),
                "https://other.example/live.m3u8", 8, BEST_FIRST,
                "https://cdn.example:443/live.m3u8",
                "https://CDN.example/live.m3u8");
        assertEquals(Arrays.asList("https://CDN.example/live.m3u8",
                "https://other.example/live.m3u8"), result);
    }

    @Test public void neverEvictsTheUsersActiveSourceAtCapacity() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://best.example/live.m3u8",
                        "https://active-weak.example/live.m3u8"),
                "https://mid.example/live.m3u8", 2, BEST_FIRST,
                "https://best.example/live.m3u8",
                "https://active-weak.example/live.m3u8");
        assertEquals(Arrays.asList("https://best.example/live.m3u8",
                "https://active-weak.example/live.m3u8"), result);
    }

    @Test public void neverEvictsStablePrimaryWhenActiveSourceIsDifferent() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://primary-weak.example/live.m3u8",
                        "https://active-mid.example/live.m3u8",
                        "https://weak.example/live.m3u8"),
                "https://best.example/live.m3u8", 3, BEST_FIRST,
                "https://primary-weak.example/live.m3u8",
                "https://active-mid.example/live.m3u8");
        assertEquals(Arrays.asList("https://primary-weak.example/live.m3u8",
                "https://active-mid.example/live.m3u8",
                "https://best.example/live.m3u8"), result);
    }

    @Test public void keepsExistingSourcesWhenNewSharedEndpointIsWorse() {
        List<String> result = SourceChannelMergePolicy.attachSharedEndpoint(
                Arrays.asList("https://best.example/live.m3u8",
                        "https://mid.example/live.m3u8"),
                "https://weak.example/live.m3u8", 2, BEST_FIRST);
        assertEquals(Arrays.asList("https://best.example/live.m3u8",
                "https://mid.example/live.m3u8"), result);
    }
}
