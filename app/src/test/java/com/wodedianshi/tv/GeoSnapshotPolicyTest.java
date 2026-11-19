package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeoSnapshotPolicyTest {
    @Test public void samePlaylistHasStableSnapshotKey() {
        String address = "https://iptv-org.github.io/iptv/cities/usphx.m3u";
        assertEquals(GeoSnapshotPolicy.snapshotKey(address), GeoSnapshotPolicy.snapshotKey(address));
    }

    @Test public void differentCityPlaylistsHaveIndependentSnapshotKeys() {
        String phoenix = "https://iptv-org.github.io/iptv/cities/usphx.m3u";
        String tucson = "https://iptv-org.github.io/iptv/cities/ustuz.m3u";
        assertNotEquals(GeoSnapshotPolicy.snapshotKey(phoenix), GeoSnapshotPolicy.snapshotKey(tucson));
    }

    @Test public void emptyPlaylistCannotReplaceLastKnownGoodSnapshot() {
        assertFalse(GeoSnapshotPolicy.shouldReplaceSnapshot(0));
        assertFalse(GeoSnapshotPolicy.shouldReplaceSnapshot(-1));
        assertTrue(GeoSnapshotPolicy.shouldReplaceSnapshot(1));
    }
}
