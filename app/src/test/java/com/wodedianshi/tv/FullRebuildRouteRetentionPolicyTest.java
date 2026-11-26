package com.wodedianshi.tv;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FullRebuildRouteRetentionPolicyTest {
    @Test public void protectsSavedPrimaryActiveAndBackupsWhileAddingNewChannelsSources() {
        List<String> old=Arrays.asList("https://old.example/primary",
                "https://old.example/backup");
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                old,1,old.get(0),Arrays.asList("https://new.example/a","https://new.example/b"),4);
        assertEquals(Arrays.asList(old.get(0),old.get(1),
                "https://new.example/a","https://new.example/b"),r.urls);
        assertEquals(1,r.activeIndex);
    }

    @Test public void fullChannelDoesNotLoseOldBackupForUnprovenNewFeed() {
        List<String> old=Arrays.asList("https://old.example/primary","https://old.example/a",
                "https://old.example/b");
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                old,2,old.get(0),Collections.singletonList("https://new.example/source"),3);
        assertEquals(old,r.urls);
        assertEquals(2,r.activeIndex);
    }

    @Test public void preservesExactPreviouslySelectedAlias() {
        List<String> old=Arrays.asList("https://CDN.example:443/stream",
                "https://cdn.example/stream","https://old.example/b");
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                old,1,old.get(0),Collections.singletonList("https://NEW.example/source"),4);
        assertEquals(old.get(1),r.urls.get(1));
        assertEquals(1,r.activeIndex);
    }

    @Test public void oversizedOldSourceListKeepsLateSelectedRoute() {
        List<String> old=Arrays.asList(
                "https://old.example/primary","https://old.example/a",
                "https://old.example/b","https://old.example/selected");
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                old,3,old.get(0),Collections.singletonList("https://new.example/a"),3);
        assertEquals(Arrays.asList(old.get(0),old.get(1),old.get(3)),r.urls);
        assertEquals(2,r.activeIndex);
    }

    @Test public void singleAvailableSlotPrefersExplicitlySelectedRoute() {
        List<String> old=Arrays.asList("https://old.example/primary",
                "https://old.example/selected");
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                old,1,old.get(0),Collections.emptyList(),1);
        assertEquals(Collections.singletonList(old.get(1)),r.urls);
        assertEquals(0,r.activeIndex);
    }

    @Test public void skipsInvalidAndDuplicateNewEndpoints() {
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                Collections.singletonList("https://old.example/a"),0,
                "https://old.example/a",Arrays.asList("not-an-url",
                        "https://OLD.example:443/a","https://new.example/b"),3);
        assertEquals(Arrays.asList("https://old.example/a","https://new.example/b"),r.urls);
    }

    @Test public void handlesExistingPrimaryOmittedFromBackupArray() {
        FullRebuildRouteRetentionPolicy.Result r=FullRebuildRouteRetentionPolicy.merge(
                Collections.singletonList("https://old.example/selected"),0,
                "https://old.example/primary",Collections.singletonList("https://new.example/a"),3);
        assertEquals(Arrays.asList("https://old.example/primary",
                "https://old.example/selected","https://new.example/a"),r.urls);
        assertEquals(1,r.activeIndex);
    }
}
