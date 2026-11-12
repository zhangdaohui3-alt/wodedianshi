package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodResultMergePolicyTest {
    @Test public void playableResultReplacesMetadataOnlyResult() {
        assertTrue(VodResultMergePolicy.shouldReplace(false, true));
    }

    @Test public void metadataCannotReplacePlayableResult() {
        assertFalse(VodResultMergePolicy.shouldReplace(true, false));
    }

    @Test public void duplicateMetadataDoesNotReplaceMetadata() {
        assertFalse(VodResultMergePolicy.shouldReplace(false, false));
    }

    @Test public void laterPlayableDuplicateDoesNotReplaceExistingPlayable() {
        assertFalse(VodResultMergePolicy.shouldReplace(true, true));
    }
}
