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
     * Stable sort rank within a page of any mainland-stage discovery directory.
     * The caller must retain ALL candidates: foreign rows are checked after
     * mainland rows, not deleted or silently skipped from the full catalogue.
     */
    public static int initialProbePriority(int feedIndex, String scope,
            String name, String group, String tvgName, String tvgId) {
        if (feedIndex < 0 || feedIndex >= SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT) return 0;
        if (isClearlyMainland(scope, name, group, tvgName, tvgId)) return 0;
        String metadata = safe(name) + " " + safe(tvgName) + " " + safe(tvgId);
        if (RegionCategoryPolicy.isHongKongMacauTaiwan(metadata, group)) return 1;
        return 2;
    }

    /** Keep the immutable page complete. Prioritize local coverage in the
     * broad mainland directories rather than repeating national backup stations. */
    static int initialCoveragePriority(int feedIndex,String scope,String name,
            String group,String tvgName,String tvgId) {
        int rank=initialProbePriority(feedIndex,scope,name,group,tvgName,tvgId);
        if(!SourceDiscoveryFeedCatalog.isBroadMainlandFeed(feedIndex))return rank;
        if(rank!=0)return rank+1;
        int category=FullDiscoveryProgressPolicy.coreCategoryMask(name,group,tvgName,tvgId,"china".equals(scope));
        if(category==FullDiscoveryProgressPolicy.CORE_LOCAL)return 0;
        if(category==0&&ChannelNamePolicy.isDomesticLocalLabel(
                safe(name)+" "+safe(group)+" "+safe(tvgName)+" "+safe(tvgId),true))return 0;
        return 1;
    }

    /** A real province label is a scheduling hint only. Ambiguous and foreign
     * metadata stays unknown rather than inventing regional membership. */
    static String initialCoverageRegion(int feedIndex,String scope,String name,
            String group,String tvgName,String tvgId) {
        if(!SourceDiscoveryFeedCatalog.isBroadMainlandFeed(feedIndex)
                ||!isClearlyMainland(scope,name,group,tvgName,tvgId)
                ||FullDiscoveryProgressPolicy.coreCategoryMask(name,group,tvgName,tvgId,true)
                    !=FullDiscoveryProgressPolicy.CORE_LOCAL)return "";
        return ChannelNamePolicy.provinceForLocalChannel(
            safe(name)+" "+safe(group)+" "+safe(tvgName)+" "+safe(tvgId));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
