package com.wodedianshi.tv.db;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacySourceScopePolicyTest {
    @Test public void scopedCataloguesContainOnlySourcesOwnedByThatScope() {
        List<String> urls = Arrays.asList("https://cn.example/live.m3u8",
                "https://global.example/live.m3u8", "https://shared.example/live.m3u8");
        Map<String, Set<String>> ownership = new HashMap<>();
        ownership.put(urls.get(0), Collections.singleton("china"));
        ownership.put(urls.get(1), Collections.singleton("global"));
        ownership.put(urls.get(2), new HashSet<>(Arrays.asList("china", "global")));

        LegacySourceScopePolicy.Selection china =
                LegacySourceScopePolicy.select(urls, 2, ownership, "china");
        LegacySourceScopePolicy.Selection global =
                LegacySourceScopePolicy.select(urls, 2, ownership, "global");

        assertEquals(Arrays.asList(urls.get(0), urls.get(2)), china.urls);
        assertEquals(1, china.activeIndex); // active shared URL remains selected
        assertEquals(Arrays.asList(urls.get(1), urls.get(2)), global.urls);
        assertEquals(1, global.activeIndex);
        assertTrue(china.channelScopes(Collections.emptySet()).contains("china"));
    }

    @Test public void channelIsExcludedWhenNoSourceBelongsToRequestedScope() {
        List<String> urls = Collections.singletonList("https://global.example/live.m3u8");
        Map<String, Set<String>> ownership = new HashMap<>();
        ownership.put(urls.get(0), Collections.singleton("global"));
        LegacySourceScopePolicy.Selection china =
                LegacySourceScopePolicy.select(urls, 0, ownership, "china");
        assertTrue(china.urls.isEmpty());
    }

    @Test public void oldRowsWithoutPerUrlProvenanceRemainAvailableForCompatibility() {
        List<String> urls = Arrays.asList("https://legacy-primary/live.m3u8",
                "https://legacy-backup/live.m3u8");
        LegacySourceScopePolicy.Selection china =
                LegacySourceScopePolicy.select(urls, 1, Collections.emptyMap(), "china");
        assertEquals(urls, china.urls);
        assertEquals(1, china.activeIndex);
    }

    @Test public void fullCatalogueKeepsAllSourcesAndTheirOriginalScopes() {
        List<String> urls = Arrays.asList("https://cn.example/live.m3u8",
                "https://global.example/live.m3u8");
        Map<String, Set<String>> ownership = new HashMap<>();
        ownership.put(urls.get(0), Collections.singleton("china"));
        ownership.put(urls.get(1), Collections.singleton("global"));
        LegacySourceScopePolicy.Selection all =
                LegacySourceScopePolicy.select(urls, 0, ownership, null);
        assertEquals(urls, all.urls);
        assertEquals(0, all.activeIndex);
        assertTrue(all.sourceScopes(urls.get(1), ownership).contains("global"));
    }
}
