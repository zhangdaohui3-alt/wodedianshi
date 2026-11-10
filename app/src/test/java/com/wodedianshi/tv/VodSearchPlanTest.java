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
        assertEquals(1, plan.get(2).page);
        assertEquals(2, plan.get(2).page);
        assertEquals(0, plan.get(2).queryIndex);
        assertEquals(1, plan.get(3).queryIndex);
    }

    @Test public void rejectsEmptyPlans() {
        assertTrue(VodSearchPlan.orderedPages(0, 3).isEmpty());
        assertTrue(VodSearchPlan.orderedPages(3, 0).isEmpty());
        assertTrue(VodSearchPlan.orderedPages(-1, 3).isEmpty());
    }
}
