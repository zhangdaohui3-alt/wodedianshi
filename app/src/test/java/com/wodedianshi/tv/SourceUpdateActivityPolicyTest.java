package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateActivityPolicyTest {
    @Test public void viewingFlagsAreIndependentAndCanBeCleared() {
        try {
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            assertTrue(SourceUpdateActivityPolicy.isActivityActive());
            assertFalse(SourceUpdateActivityPolicy.isPlaybackActive());
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.setActivityActive(false);
            assertFalse(SourceUpdateActivityPolicy.isActivityActive());
            assertTrue(SourceUpdateActivityPolicy.isPlaybackActive());
        } finally {
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
        assertFalse(SourceUpdateActivityPolicy.isPlaybackActive());
        assertFalse(SourceUpdateActivityPolicy.isActivityActive());
    }
}
