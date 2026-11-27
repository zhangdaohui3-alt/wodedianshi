package com.wodedianshi.tv.db;

import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogueReadCompletenessPolicyTest {
    @Test public void acceptsExactCount() {
        assertTrue(CatalogueReadCompletenessPolicy.isComplete(1200, 1200));
    }

    @Test public void acceptsEmptyCatalogueCountAsCompleteRead() {
        assertTrue(CatalogueReadCompletenessPolicy.isComplete(0, 0));
    }

    @Test public void rejectsTruncatedRead() {
        assertFalse(CatalogueReadCompletenessPolicy.isComplete(1200, 800));
    }

    @Test public void rejectsUnexpectedExtraRows() {
        assertFalse(CatalogueReadCompletenessPolicy.isComplete(1200, 1201));
    }

    @Test public void rejectsInvalidExpectedCount() {
        assertFalse(CatalogueReadCompletenessPolicy.isComplete(-1, 0));
    }

    @Test public void requireCompleteExplainsTruncatedRead() {
        try {
            CatalogueReadCompletenessPolicy.requireComplete(200, 199);
            fail("truncated read must not be published");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("expected 200"));
            assertTrue(expected.getMessage().contains("loaded 199"));
        }
    }
}
