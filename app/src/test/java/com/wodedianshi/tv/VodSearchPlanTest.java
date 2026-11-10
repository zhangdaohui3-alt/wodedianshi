package com.wodedianshi.tv;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class VodSearchPlanTest {
    @Test public void schedulesEveryQueryFirstPageBeforeDeeperPages() {
        List<VodSearchPlan.PageRequest> plan = VodSearchPlan.orderedPages(3, 3);
        assertEquals(9, plan.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, plan.get(i).queryIndex);
            assertEquals(1, plan.get(i).page);
        }
        for (int i = 3; i < 6; i++) assertEquals(2, plan.get(i).page);
        for (int i = 6; i < 9; i++) assertEquals(3, plan.get(i).page);
    }

    @Test public void keepsStableQueryOrderWithinEachPage() {
        List<VodSearchPlan.PageRequest> plan = VodSearchPlan.orderedPages(2, 2);
        assertEquals(0, plan.get(0).queryIndex);
        assertEquals(1, plan.get(1).queryIndex);
        assertEquals(1, plan.get(0).page);
        assertEquals(1, plan.get(1).page);
        assertEquals(0, plan.get(2).queryIndex);
        assertEquals(1, plan.get(3).queryIndex);
        assertEquals(2, plan.get(2).page);
        assertEquals(2, plan.get(3).page);
    }

    @Test public void titleSearchFansOutAcrossMetadataAndTokens() {
        List<String> queries = VodSearchPlan.archiveQueries("The Matrix 1999");
        assertTrue(queries.size() >= 2);
        assertTrue(queries.get(0).contains("title:"));
        assertTrue(queries.get(0).contains("description:"));
        assertTrue(queries.get(0).contains("subject:"));
        assertTrue(queries.get(0).contains("creator:"));
        assertTrue(queries.get(queries.size() - 1).contains(" AND "));
    }

    @Test public void titleSearchSanitizesQuerySyntaxAndHandlesChinese() {
        List<String> queries = VodSearchPlan.archiveQueries("霸王别姬 (1993) OR *");
        assertFalse(queries.isEmpty());
        for (String query : queries) {
            assertFalse(query.contains("*"));
            assertFalse(query.contains(" OR *"));
        }
        assertTrue(VodSearchPlan.archiveQueries("   ").isEmpty());
    }

    @Test public void chinaRegionPrioritizesChineseCatalogueMetadata() {
        List<String> queries = VodSearchPlan.archiveQueries("霸王别姬", "中国");
        assertFalse(queries.isEmpty());
        assertTrue(queries.get(0).contains("language:Chinese"));
        assertTrue(queries.get(0).contains("language:zho"));
        assertTrue(queries.get(0).contains("subject:Chinese"));
    }

    @Test public void internationalRegionUsesMultipleLanguageMetadata() {
        List<String> queries = VodSearchPlan.archiveQueries("The Matrix", "国外");
        assertFalse(queries.isEmpty());
        assertTrue(queries.get(0).contains("language:English"));
        assertTrue(queries.get(0).contains("language:Japanese"));
        assertTrue(queries.get(0).contains("language:Spanish"));
    }

    @Test public void allRegionLeavesSearchUnrestricted() {
        List<String> all = VodSearchPlan.archiveQueries("Cinema", "全部");
        List<String> defaultPlan = VodSearchPlan.archiveQueries("Cinema");
        assertEquals(defaultPlan, all);
        assertFalse(all.get(0).contains("language:Chinese"));
        assertFalse(all.get(0).contains("language:English"));
    }

    @Test public void rejectsEmptyPlans() {
        assertTrue(VodSearchPlan.orderedPages(0, 3).isEmpty());
        assertTrue(VodSearchPlan.orderedPages(3, 0).isEmpty());
        assertTrue(VodSearchPlan.orderedPages(-1, 3).isEmpty());
    }
}
