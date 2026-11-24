package com.wodedianshi.tv;

/**
 * During the initial mainland-first phase a mixed international directory must
 * check clearly mainland Chinese stations first inside each mixed-feed page.
 * Other entries are not discarded: all are still probed before the cursor
 * advances, so a page's complete source coverage is preserved.
 *
 * A playlist label is not proof of streaming rights or playable video/audio.
 */
public final class ChinaDiscoveryCandidatePolicy {
    private ChinaDiscoveryCandidatePolicy() { }

    public static boolean isClearlyMainland(String scope, String name,
            String group, String tvgName, String tvgId) {
        if (!"china".equals(scope)) return false;
        String metadata = safe(name) + " " + safe(tvgName) + " " + safe(tvgId);
        return !RegionCategoryPolicy.isHongKongMacauTaiwan(metadata, group);
    }

    /**
     * Stable sort rank within a page of the known mixed public directory.
     * The caller must retain ALL candidates: foreign rows are checked after
     * mainland rows, not deleted or silently skipped from the full catalogue.
     */
    public static int initialProbePriority(int feedIndex, String scope,
            String name, String group, String tvgName, String tvgId) {
        if (!SourceDiscoveryFeedCatalog.isMixedMainlandFeed(feedIndex)) return 0;
        if (isClearlyMainland(scope, name, group, tvgName, tvgId)) return 0;
        String metadata = safe(name) + " " + safe(tvgName) + " " + safe(tvgId);
        if (RegionCategoryPolicy.isHongKongMacauTaiwan(metadata, group)) return 1;
        return 2;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
