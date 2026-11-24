package com.wodedianshi.tv;

/**
 * During the initial mainland-first phase a mixed international directory must
 * spend its media-probe budget only on clearly mainland Chinese stations.
 * Ambiguous/foreign entries remain deferred to the normal international feeds;
 * a scan cursor still counts every inspected record so paging never loops.
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

    public static boolean shouldProbeInInitialDiscovery(int feedIndex, String scope,
            String name, String group, String tvgName, String tvgId) {
        if (!SourceDiscoveryFeedCatalog.isMixedMainlandFeed(feedIndex)) return true;
        return isClearlyMainland(scope, name, group, tvgName, tvgId);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
