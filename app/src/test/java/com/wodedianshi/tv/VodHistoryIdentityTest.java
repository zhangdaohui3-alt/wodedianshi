package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodHistoryIdentityTest {
    @Test public void providerIdWinsOverChangingStreamUrl() {
        assertEquals("id:episode-42",
                VodHistoryIdentity.forItem(" episode-42 ", "https://cdn.example/a.m3u8"));
        assertEquals(VodHistoryIdentity.forItem("episode-42", "https://old.example/a"),
                VodHistoryIdentity.forItem("episode-42", "https://new.example/b"));
    }

    @Test public void missingProviderIdFallsBackToTrimmedMediaUrl() {
        assertEquals("url:https://cdn.example/movie.m3u8",
                VodHistoryIdentity.forItem(" ", " https://cdn.example/movie.m3u8 "));
    }

    @Test public void emptyIdentityIsRejected() {
        assertEquals("", VodHistoryIdentity.forItem(null, "  "));
        assertEquals("", VodHistoryIdentity.forItem(null, null));
    }

    @Test public void storedKeyWinsAndLegacyRowsAreMigrated() {
        assertEquals("episode:provider-9",
                VodHistoryIdentity.forEntry(" episode:provider-9 ", "old-id", "https://old.example/a"));
        assertEquals("url:https://cdn.example/a",
                VodHistoryIdentity.forEntry("", "", " https://cdn.example/a "));
    }

    @Test public void distinctIdlessMediaUrlsDoNotCollide() {
        assertNotEquals(VodHistoryIdentity.forItem("", "https://cdn.example/a"),
                VodHistoryIdentity.forItem("", "https://cdn.example/b"));
    }
}
