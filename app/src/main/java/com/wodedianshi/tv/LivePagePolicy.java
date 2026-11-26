package com.wodedianshi.tv;

/** Consistent bounded paging rules for the live channel directory. */
public final class LivePagePolicy {
    private LivePagePolicy() {}

    public static int pageCount(int itemCount, int pageSize) {
        if (pageSize <= 0 || itemCount <= 0) return 1;
        return (int) Math.max(1L, ((long) itemCount + pageSize - 1L) / pageSize);
    }

    public static boolean canSelectPage(int targetPage, int itemCount, int pageSize) {
        return targetPage>=0 && targetPage<pageCount(itemCount,pageSize);
    }

    public static boolean canAdvance(int currentPage, int itemCount, int pageSize) {
        return currentPage >= 0 && currentPage + 1 < pageCount(itemCount, pageSize);
    }

    /**
     * Normally keep the currently selected channel visible after a refresh.
     * Explicit page-button navigation takes precedence until that page renders.
     */
    public static int choosePage(int requestedPage, int selectedItemPosition,
                                 int pageSize, boolean preserveRequestedPage) {
        if (!preserveRequestedPage && selectedItemPosition >= 0 && pageSize > 0) {
            return selectedItemPosition / pageSize;
        }
        return Math.max(0, requestedPage);
    }
}
