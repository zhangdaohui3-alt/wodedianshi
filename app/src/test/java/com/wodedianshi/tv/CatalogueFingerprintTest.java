package com.wodedianshi.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class CatalogueFingerprintTest {
    @Test
    public void fingerprintIsStableForSameCatalogueSnapshot() {
        String snapshot = "[{\"name\":\"CCTV-1\",\"url\":\"https://example.test/live.m3u8\"}]";
        assertEquals(CatalogueFingerprint.sha256(snapshot), CatalogueFingerprint.sha256(snapshot));
    }

    @Test
    public void fingerprintChangesWhenAnySnapshotCharacterChanges() {
        assertNotEquals(
                CatalogueFingerprint.sha256("[{\"name\":\"CCTV-1\"}]"),
                CatalogueFingerprint.sha256("[{\"name\":\"CCTV-2\"}]"));
    }

    @Test
    public void fingerprintHandlesNullAndUnicodeWithoutPlatformEncoding() {
        assertEquals(CatalogueFingerprint.sha256(null), CatalogueFingerprint.sha256(""));
        assertNotEquals(
                CatalogueFingerprint.sha256("{\"name\":\"中央电视台\"}"),
                CatalogueFingerprint.sha256("{\"name\":\"中央電視台\"}"));
        assertNotNull(CatalogueFingerprint.sha256("央视频道 📺"));
    }

    @Test
    public void fingerprintHandlesLargeSnapshotsAcrossBufferBoundaries() {
        StringBuilder snapshot = new StringBuilder(100_000);
        for (int i = 0; i < 20_000; i++) snapshot.append("频道-").append(i).append("|https://example.test/").append(i);
        String value = snapshot.toString();
        assertEquals(CatalogueFingerprint.sha256(value), CatalogueFingerprint.sha256(value));
    }
}
