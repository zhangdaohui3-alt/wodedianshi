package com.wodedianshi.tv;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class VodCategoryQueryPolicyTest {
    @Test public void handlesOnlyTheThreeOverlappingChineseShelves() {
        assertTrue(VodCategoryQueryPolicy.handles("中国历史影像"));
        assertTrue(VodCategoryQueryPolicy.handles("中国纪录片"));
        assertTrue(VodCategoryQueryPolicy.handles("中国经典影像"));
        assertFalse(VodCategoryQueryPolicy.handles("中国动画"));
        assertFalse(VodCategoryQueryPolicy.handles("电影"));
    }

    @Test public void historicalShelfRequiresChinaAndHistoricalDocumentarySignals() {
        List<String> queries = VodCategoryQueryPolicy.queriesFor("中国历史影像");
        assertEquals(3, queries.size());
        for (String query : queries) {
            assertTrue(query.startsWith("mediatype:movies AND"));
            assertTrue(query.contains("history") || query.contains("historical")
                    || query.contains("newsreel") || query.contains("archive footage"));
            assertTrue(query.contains("NOT (subject:feature film OR subject:animation"));
        }
    }

    @Test public void generalChineseDocumentariesExcludeHistoricalAndAnimationResults() {
        List<String> queries = VodCategoryQueryPolicy.queriesFor("中国纪录片");
        assertEquals(2, queries.size());
        for (String query : queries) {
            assertTrue(query.contains("documentary"));
            assertTrue(query.contains("NOT (subject:history"));
            assertTrue(query.contains("subject:animation"));
        }
    }

    @Test public void ChineseClassicsExcludeDocumentariesHistoryAndAnimation() {
        List<String> queries = VodCategoryQueryPolicy.queriesFor("中国经典影像");
        assertEquals(2, queries.size());
        for (String query : queries) {
            assertTrue(query.contains("classic") || query.contains("cinema"));
            assertTrue(query.contains("NOT (subject:documentary"));
            assertTrue(query.contains("subject:history"));
            assertTrue(query.contains("subject:animation"));
        }
    }

    @Test public void unsupportedCategoryReturnsNoQueries() {
        assertTrue(VodCategoryQueryPolicy.queriesFor("中国动画").isEmpty());
    }
}
