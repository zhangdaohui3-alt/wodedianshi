package com.wodedianshi.tv;

/**
 * Honest first-build progress: each configured feed is one unit of work,
 * with fractional progress from the indexed cursor of the active feed.
 * A percentage of work completed is not a prediction of remaining time,
 * and 100% is reserved for a successfully published complete Room snapshot.
 */
public final class FullDiscoveryProgressPolicy {
    private FullDiscoveryProgressPolicy() {}

    // Do not hold the first playable preview behind every mainland download.
    // At most two bootstrap acquisitions, then normal fair paged discovery.
    public static int bootstrapIndexLimit(int mainlandCount) {
        return Math.max(0,Math.min(2,mainlandCount));
    }
    /** Acquisition attempts are separate from the final verified-build percent. */
    public static boolean isMainlandIndexPhase(int attempted, int mainlandCount,
            int checked, long indexedEntries, boolean complete) {
        return !complete && checked <= 0 && indexedEntries <= 0
                && Math.max(0,attempted) < bootstrapIndexLimit(mainlandCount);
    }

    /** WorkManager completion is not proof that a Room snapshot was published. */
    public static boolean hasPublishedInitialSnapshot(long completedAt) {
        return completedAt>0L;
    }

    /** Finishing discovery is independent of permission to replace live rows. */
    public static boolean awaitingIdleFinalization(boolean allFeedsInspected, boolean viewing) {
        return allFeedsInspected && viewing;
    }

    public static boolean canPublishInitialSnapshot(boolean allFeedsInspected,
            boolean hasValidatedSource, boolean viewing) {
        return allFeedsInspected && hasValidatedSource && !viewing;
    }

    // Bounded, opportunistic cache. Room is authoritative after every commit.
    public static boolean canRetainStaging(int rows,boolean viewing) {
        return rows>=0&&rows<=(viewing?512:1024);
    }
    public static boolean canReuseStaging(int rows,String revision,String durableRevision,long savedAt,long now) {
        return canRetainStaging(rows,false)&&revision!=null&&!revision.isEmpty()
                &&revision.equals(durableRevision)&&savedAt>=0L&&now>=savedAt&&now-savedAt<=60000L;
    }

    /** A fresh empty staging set can be initialized during stable viewing.
     * Never clear a populated staging build while the viewer is watching. */
    public static boolean canResetInitialStaging(boolean stableViewing,int stagedRows) {
        return !stableViewing || stagedRows==0;
    }

    /** The optional idle bootstrap must not block the bounded viewing path. */
    public static boolean shouldRunIdleBootstrap(boolean stableViewing,int attempted,
            int mainlandCount,int checked,long indexedEntries,boolean complete) {
        return !stableViewing&&isMainlandIndexPhase(attempted,mainlandCount,checked,indexedEntries,complete);
    }

    // Small early snapshots may be published under an existing healthy viewing
    // lease. Larger/full publications retain the idle-only barrier.
    static final int MAX_VIEWING_PREVIEW_ROWS=512;
    static boolean canPublishPreview(boolean viewing,int stagedRows,int existingRows) {
        return !viewing||(stagedRows>=0&&existingRows>=0
                &&(long)stagedRows+existingRows<=MAX_VIEWING_PREVIEW_ROWS);
    }

    public static final String PREVIEW_LEVEL_KEY = "source_initial_preview_level";
    // More frequent early publication, then geometric growth to bound
    // database work. Existing watermark values remain valid on upgrade.
    public static final String PREVIEW_MILESTONE_KEY = "source_initial_preview_milestone";
    private static final int[] PREVIEW_MILESTONES={1,4,8,16,32,64,96,128,192,256,384,512,1024,2048,4096,8192};
    static int nextPreviewMilestone(int previous,int stagedChannels,int mainlandValidated) {
        if(mainlandValidated<=0)return previous;
        int reached=previous;
        for(int milestone:PREVIEW_MILESTONES){
            if(milestone>stagedChannels)break;
            reached=Math.max(reached,milestone);
        }
        return reached;
    }

    // At most two extra publications: an early useful set, then mainland coverage.
    // A milestone is not a completed full build and does not change the probe policy.
    public static int nextPreviewLevel(int previous, int stagedChannels,
            int mainlandValidated, boolean mainlandInspected) {
        if (stagedChannels <= 0 || mainlandValidated <= 0) return previous;
        if (mainlandInspected) return Math.max(previous, 2);
        return stagedChannels >= 12 ? Math.max(previous, 1) : previous;
    }

    public static final String PUBLISHED_COVERAGE_KEY = "source_initial_published_coverage_v1";
    public static final String FRESH_COVERAGE_KEY = "source_initial_fresh_coverage_v1";
    private static final String[] PROVINCES = {"北京","天津","河北","山西","内蒙古","辽宁","吉林",
        "黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南",
        "广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};

    /** Counts catalogue rows, not real-world playable channels or unique successful probes.
     * Retained old rows are intentionally included, with no claim of fresh validation. */
    public static final class Coverage {
        private int total, cctv, satellite, local, unresolvedLocal;
        private final java.util.Set<String> provinces = new java.util.HashSet<>();

        public void add(String name, String group, String tvgName, String tvgId) {
            total++;
            String metadata = safe(name) + " " + safe(tvgName) + " " + safe(tvgId);
            if (RegionCategoryPolicy.isHongKongMacauTaiwan(metadata, group)) return;
            String broadcaster = ChannelNamePolicy.canonicalBroadcasterName(name,tvgName,tvgId,group);
            if (broadcaster.startsWith("CCTV-")) { cctv++; return; }
            if (ChannelNamePolicy.isSatellite(metadata, group)) { satellite++; return; }
            String all = (metadata + " " + safe(group)).toLowerCase(java.util.Locale.ROOT);
            String province = ChannelNamePolicy.provinceForLocalChannel(all);
            if (!province.isEmpty()) {
                local++;
                provinces.add(province);
            } else if (all.contains("地方") || all.contains("local")) {
                // A generic foreign "Local" group is not evidence of China.
                if (all.contains("地方") || all.contains("中国") || all.contains("china")
                        || all.contains(".cn")) {
                    local++;
                    unresolvedLocal++;
                }
            }
        }

        public int provinceCount() { return provinces.size(); }
        public int cctvCount() { return cctv; }
        public int satelliteCount() { return satellite; }
        public int localCount() { return local; }

        public String summary() {
            return summary("首次发布目录（含保留旧频道，非实播验证）");
        }

        public String summary(String label) {
            java.util.ArrayList<String> missing = new java.util.ArrayList<>();
            for (String province : PROVINCES) if (!provinces.contains(province)) missing.add(province);
            String warning = cctv == 0 || satellite == 0 || local == 0 || !missing.isEmpty()
                    ? "覆盖有缺口，待补查" : "各省级地区均有记录，仍待实播验收";
            return label + "：" + total + " 条"
                    + "\n央视 " + cctv + " · 卫视 " + satellite + " · 识别地方台 " + local
                    + "\n省级地区覆盖 " + provinceCount() + "/31 · 地方台省份未识别 " + unresolvedLocal
                    + "\n" + warning + (missing.isEmpty() ? "" : "；无已识别地方台记录：" + String.join("、", missing));
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }


    /** One feed contributes at most 10,000 basis points, including partial pages. */
    public static int feedProgress(int cursor, int entries) {
        if (entries <= 0 || cursor <= 0) return 0;
        return (int)Math.min(10000L, 10000L * cursor / entries);
    }

    public static int overallBasisPoints(boolean[] finished, int[] partial, boolean published) {
        if (published) return 10000;
        if (finished == null || finished.length == 0) return 0;
        long work = 0;
        for (int i = 0; i < finished.length; i++) {
            work += finished[i] ? 10000 : partial != null && i < partial.length
                    ? Math.max(0, Math.min(10000, partial[i])) : 0;
        }
        return (int)Math.min(9999L, work / finished.length);
    }

    public static int overallPercent(boolean[] finished, int[] partial, boolean published) {
        return overallBasisPoints(finished,partial,published)/100;
    }

    public static String formatIncompleteProgress(int basisPoints) {
        return String.format(java.util.Locale.ROOT,"%.2f%%",
                Math.max(0,Math.min(9999,basisPoints))/100.0);
    }

    public static int percent(int completedFeeds, int totalFeeds,
            int activeCursor, int activeFeedEntries, boolean published) {
        if (published) return 100;
        if (totalFeeds <= 0) return 0;
        int done = Math.max(0, Math.min(completedFeeds, totalFeeds));
        double current = 0d;
        if (done < totalFeeds && activeFeedEntries > 0) {
            current = Math.min(1d, Math.max(0d,
                    (double) Math.max(0, activeCursor) / activeFeedEntries));
        }
        int measured = (int)Math.floor(100d * (done + current) / totalFeeds);
        return Math.max(0, Math.min(99, measured));
    }
}
