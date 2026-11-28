package com.wodedianshi.tv.db;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure policy test; full Room transaction still requires Android instrumentation. */
public class CatalogueVersionPolicyTest {
    @Test public void currentVersionCanCommit() {
        assertTrue(CatalogueVersionPolicy.matches(1234L, 1234L));
    }

    @Test public void staleMemoryMustNeverReplaceNewerRoomDirectory() {
        assertFalse(CatalogueVersionPolicy.matches(1234L, 1235L));
    }

    @Test public void missingOrUninitializedCheckpointIsNotCurrent() {
        assertFalse(CatalogueVersionPolicy.matches(12L, null));
        assertFalse(CatalogueVersionPolicy.matches(-1L, 12L));
    }
}
