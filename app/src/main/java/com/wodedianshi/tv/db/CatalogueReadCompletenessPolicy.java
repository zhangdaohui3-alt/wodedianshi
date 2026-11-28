package com.wodedianshi.tv.db;

/** Guards paged Room reads against silently publishing a truncated directory. */
public final class CatalogueReadCompletenessPolicy {
    private CatalogueReadCompletenessPolicy() {}

    public static boolean isComplete(int expectedCount, int loadedCount) {
        return expectedCount >= 0 && loadedCount == expectedCount;
    }

    public static void requireComplete(int expectedCount, int loadedCount) {
        if (!isComplete(expectedCount, loadedCount)) {
            throw new IllegalStateException("Room catalogue read incomplete: expected "
                    + expectedCount + " channels but loaded " + loadedCount);
        }
    }
}
