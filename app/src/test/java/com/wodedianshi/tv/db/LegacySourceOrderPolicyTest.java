package com.wodedianshi.tv.db;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class LegacySourceOrderPolicyTest {
    @Test public void appendsMissingPrimaryWithoutChangingSelectedBackup() {
        LegacySourceOrderPolicy.Result result = LegacySourceOrderPolicy.normalize(
                Arrays.asList("https://backup-b.example/live.m3u8",
                        "https://backup-a.example/live.m3u8"),
                "https://primary.example/live.m3u8", 1);

        assertEquals(Arrays.asList("https://backup-b.example/live.m3u8",
                "https://backup-a.example/live.m3u8",
                "https://primary.example/live.m3u8"), result.urls);
        assertEquals(1, result.activeIndex);
    }

    @Test public void duplicateUrlsDoNotRedirectTheSelectedSource() {
        LegacySourceOrderPolicy.Result result = LegacySourceOrderPolicy.normalize(
                Arrays.asList("https://a.example/live.m3u8",
                        "https://b.example/live.m3u8",
                        "https://a.example/live.m3u8"),
                "https://primary.example/live.m3u8", 2);

        assertEquals(Arrays.asList("https://a.example/live.m3u8",
                "https://b.example/live.m3u8",
                "https://primary.example/live.m3u8"), result.urls);
        assertEquals(0, result.activeIndex);
    }

    @Test public void emptySourcesFallBackToPrimary() {
        LegacySourceOrderPolicy.Result result = LegacySourceOrderPolicy.normalize(
                Collections.<String>emptyList(), "https://primary.example/live.m3u8", 0);

        assertEquals(Collections.singletonList("https://primary.example/live.m3u8"), result.urls);
        assertEquals(0, result.activeIndex);
    }

    @Test public void invalidActiveIndexFallsBackToPrimaryWhenAvailable() {
        LegacySourceOrderPolicy.Result result = LegacySourceOrderPolicy.normalize(
                Arrays.asList("https://backup.example/live.m3u8"),
                "https://primary.example/live.m3u8", 99);

        assertEquals(Arrays.asList("https://backup.example/live.m3u8",
                "https://primary.example/live.m3u8"), result.urls);
        assertEquals(1, result.activeIndex);
    }
    @Test public void normalizesReadOnlyInputWithoutChangingOriginalSelection() {
        String primary="https://primary.example/live";
        String active="https://active.example/live";
        java.util.List<String> input=Collections.unmodifiableList(
                Arrays.asList(null,primary,"",active,primary,active));
        LegacySourceOrderPolicy.Result result=LegacySourceOrderPolicy.normalize(input,primary,5);
        assertEquals(Arrays.asList(primary,active),result.urls);
        assertEquals(1,result.activeIndex);
        assertEquals(Arrays.asList(null,primary,"",active,primary,active),input);
    }

    @Test public void missingSourceListUsesSeparatelyStoredPrimary() {
        String primary="https://primary.example/live";
        LegacySourceOrderPolicy.Result result=LegacySourceOrderPolicy.normalize(null,primary,0);
        assertEquals(Collections.singletonList(primary),result.urls);
        assertEquals(0,result.activeIndex);
    }

}
