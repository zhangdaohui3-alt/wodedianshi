package com.wodedianshi.tv;

/** Consistent bounded paging rules for the live channel directory. */
public final class LivePagePolicy {
    private LivePagePolicy() {}

    public static int pageCount(int itemCount, int pageSize) {
        if (pageSize <= 0 || itemCount <= 0) return 1;
        return (int) Math.max(1L, ((long) itemCount + pageSize - 1L) / pageSize);
    }

    public static boolean canAdvance(int currentPage, int itemCount, int pageSize) {
        return currentPage >= 0 && currentPage + 1 < pageCount(itemCount, pageSize);
    }
}
