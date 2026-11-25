package com.wodedianshi.tv.db;

/**
 * Pure version predicate used under Room's write transaction.
 * A missing checkpoint is never a match for a previously loaded catalogue.
 */
final class CatalogueVersionPolicy {
    private CatalogueVersionPolicy() {}

    static boolean matches(long expected, Long observed) {
        return expected >= 0L && observed != null && observed.longValue() == expected;
    }
}
