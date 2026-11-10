package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Orders multi-query catalogue requests to maximize first-page source diversity. */
public final class VodSearchPlan {
    private VodSearchPlan() {}

    public static final class PageRequest {
        public final int queryIndex;
        public final int page;

        private PageRequest(int queryIndex, int page) {
            this.queryIndex = queryIndex;
            this.page = page;
        }
    }

    /** Page-major ordering: all query page-1 requests precede every page-2 request. */
    public static List<PageRequest> orderedPages(int queryCount, int maxPagesPerQuery) {
        if (queryCount <= 0 || maxPagesPerQuery <= 0) return Collections.emptyList();
        ArrayList<PageRequest> result = new ArrayList<>(queryCount * maxPagesPerQuery);
        for (int page = 1; page <= maxPagesPerQuery; page++) {
            for (int query = 0; query < queryCount; query++) {
                result.add(new PageRequest(query, page));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
