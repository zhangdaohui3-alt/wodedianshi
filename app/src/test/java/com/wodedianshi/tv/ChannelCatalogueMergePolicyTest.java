package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ChannelCatalogueMergePolicyTest {
    private ChannelCatalogueMergePolicy.ChannelRecord channel(
            String id, String primary, List<String> sources, int active) {
        return new ChannelCatalogueMergePolicy.ChannelRecord(
                id, id, "CCTV", primary, sources, active);
    }

    @Test public void preservesLatestPrimaryAndActiveSourceWhenAddingBackup() {
        List<ChannelCatalogueMergePolicy.ChannelRecord> result =
                ChannelCatalogueMergePolicy.merge(
                        Collections.singletonList(channel("cctv1", "https://primary/live",
                                Arrays.asList("https://primary/live", "https://active/live"), 1)),
                        Collections.singletonList(channel("cctv1", "https://new-best/live",
                                Arrays.asList("https://new-best/live", "https://primary/live",
                                        "https://active/live", "https://new-backup/live"), 0)));
        assertNotNull(result);
        ChannelCatalogueMergePolicy.ChannelRecord merged = result.get(0);
        assertEquals("https://new-best/live", merged.primaryUrl);
        assertEquals("https://active/live", merged.activeUrl());
        assertEquals(2, merged.activeSource);
        assertTrue(merged.sources.contains("https://new-backup/live"));
    }

    @Test public void appendsNewChannelWithoutReplacingExistingChannels() {
        List<ChannelCatalogueMergePolicy.ChannelRecord> result =
                ChannelCatalogueMergePolicy.merge(
                        Collections.singletonList(channel("old", "https://old/live",
                                Collections.singletonList("https://old/live"), 0)),
                        Arrays.asList(
                                channel("old", "https://changed/live",
                                        Arrays.asList("https://changed/live", "https://old/live"), 0),
                                channel("new", "https://new/live",
                                        Collections.singletonList("https://new/live"), 0)));
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("https://changed/live", result.get(0).primaryUrl);
        assertEquals("https://new/live", result.get(1).primaryUrl);
    }

    @Test public void deduplicatesSourcesAndCapsNewBackupsAtFive() {
        List<ChannelCatalogueMergePolicy.ChannelRecord> result =
                ChannelCatalogueMergePolicy.merge(
                        Collections.singletonList(channel("one", "https://a/live",
                                Collections.singletonList("https://a/live"), 0)),
                        Collections.singletonList(channel("one", "https://a/live",
                                Arrays.asList("https://a/live", "https://b/live", "https://b/live",
                                        "https://c/live", "https://d/live", "https://e/live",
                                        "https://f/live", "https://g/live"), 0)));
        assertNotNull(result);
        assertEquals(5, result.get(0).sources.size());
        assertEquals(5, result.get(0).sources.stream().distinct().count());
    }

    @Test public void migratesFavoriteKeyWhenPrimarySourceIsReordered() {
        ChannelCatalogueMergePolicy.ChannelRecord before =
                channel("cctv1", "https://old/live",
                        Arrays.asList("https://old/live", "https://active/live"), 1);
        ChannelCatalogueMergePolicy.ChannelRecord after =
                channel("cctv1", "https://new/live",
                        Arrays.asList("https://new/live", "https://old/live", "https://active/live"), 2);
        java.util.Set<String> favorites = ChannelCatalogueMergePolicy.migrateFavoriteKeys(
                Collections.singleton("CCTV-1|https://old/live"),
                Collections.singletonList(before), Collections.singletonList(after));
        assertFalse(favorites.contains("CCTV-1|https://old/live"));
        assertTrue(favorites.contains("CCTV-1|https://new/live"));
    }

    @Test public void rejectsEmptyOrInvalidCandidateWithoutChangingSnapshot() {
        assertNull(ChannelCatalogueMergePolicy.merge(
                Collections.singletonList(channel("old", "https://old/live",
                        Collections.singletonList("https://old/live"), 0)),
                Collections.emptyList()));
        assertNull(ChannelCatalogueMergePolicy.merge(
                Collections.emptyList(),
                Collections.singletonList(channel("bad", "file:///bad",
                        Collections.singletonList("file:///bad"), 0))));
    }

    @Test public void doesNotResurrectChannelDeletedAfterWorkerSnapshot() {
        List<ChannelCatalogueMergePolicy.ChannelRecord> result =
                ChannelCatalogueMergePolicy.merge(
                        Collections.emptyList(),
                        Collections.singletonList(channel("deleted", "https://old/live",
                                Collections.singletonList("https://old/live"), 0)),
                        Collections.singleton("deleted"));
        assertNull(result);
    }

    @Test public void stillAddsGenuinelyNewDiscoveryNotInWorkerBaseSnapshot() {
        List<ChannelCatalogueMergePolicy.ChannelRecord> result =
                ChannelCatalogueMergePolicy.merge(
                        Collections.emptyList(),
                        Collections.singletonList(channel("new", "https://new/live",
                                Collections.singletonList("https://new/live"), 0)),
                        Collections.singleton("old"));
        assertNotNull(result);
        assertEquals("new", result.get(0).identity);
    }

    @Test public void retainsCurrentSnapshotWhenCandidateContainsOnlyInvalidSources() {
        List<ChannelCatalogueMergePolicy.ChannelRecord> result =
                ChannelCatalogueMergePolicy.merge(
                        Collections.singletonList(channel("old", "https://old/live",
                                Collections.singletonList("https://old/live"), 0)),
                        Collections.singletonList(channel("old", "file:///bad",
                                Collections.singletonList("file:///bad"), 0)));
        assertNull(result);
    }
}
