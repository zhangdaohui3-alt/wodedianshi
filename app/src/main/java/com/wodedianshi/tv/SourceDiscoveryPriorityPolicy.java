package com.wodedianshi.tv;

/**
 * Explicit, bounded first-build discovery order:
 * mainland China (CCTV, provincial, local), then Hong Kong/Macau/Taiwan,
 * then other countries. Round-robin is fair *within* each stage.
 *
 * A stage with uncompleted feeds cannot be bypassed just because every
 * feed in it failed once in the current worker: the next worker retries
 * and the bounded-failure policy eventually marks it unavailable.
 */
public final class SourceDiscoveryPriorityPolicy {
    private SourceDiscoveryPriorityPolicy() {}

    // Keep the persisted feed indices unchanged. For the small first-watch
    // window prefer distinct CCTV, provincial and county directory families,
    // then the remaining mainland feeds and the two status mirrors.
    private static final int[] FIRST_WATCH_ORDER={0,2,4,5,6,7,8,9,1,3};

    static int firstWatchBootstrapFeed(int attempt) {
        return attempt==1?2:0;
    }

    static int nextAfterFirstWatchTurn(int attempted,int feedCount) {
        if(feedCount>=FIRST_WATCH_ORDER.length){
            for(int i=0;i<FIRST_WATCH_ORDER.length;i++)
                if(FIRST_WATCH_ORDER[i]==attempted)
                    return FIRST_WATCH_ORDER[(i+1)%FIRST_WATCH_ORDER.length];
        }
        return InitialFeedRotationPolicy.nextAfterAttempt(attempted,feedCount,false,false);
    }

    static int nextFeed(int preferredStart,boolean[] completed,boolean[] attempted,
            int mainlandEnd,int regionEnd,boolean firstWatch) {
        if(firstWatch&&completed!=null&&attempted!=null
                &&completed.length==attempted.length
                &&completed.length>=FIRST_WATCH_ORDER.length
                &&mainlandEnd==FIRST_WATCH_ORDER.length
                &&activeStage(completed,mainlandEnd,regionEnd)==0){
            int start=0;
            for(int i=0;i<FIRST_WATCH_ORDER.length;i++)
                if(FIRST_WATCH_ORDER[i]==preferredStart){start=i;break;}
            for(int d=0;d<FIRST_WATCH_ORDER.length;d++){
                int index=FIRST_WATCH_ORDER[(start+d)%FIRST_WATCH_ORDER.length];
                if(!completed[index]&&!attempted[index])return index;
            }
            return -1;
        }
        return nextFeed(preferredStart,completed,attempted,mainlandEnd,regionEnd);
    }

    /** 0 mainland, 1 HMT, 2 other regions, 3 all inspected. */
    public static int activeStage(boolean[] completed, int mainlandEnd, int regionEnd) {
        int size = completed == null ? 0 : completed.length;
        int china = bound(mainlandEnd, size);
        int hmt = Math.max(china, bound(regionEnd, size));
        for (int i = 0; i < china; i++) if (!completed[i]) return 0;
        for (int i = china; i < hmt; i++) if (!completed[i]) return 1;
        for (int i = hmt; i < size; i++) if (!completed[i]) return 2;
        return 3;
    }

    /**
     * Select next feed without changing the saved cursor; completed and
     * attempted flags have the same indices as the persisted feed list.
     * Return -1 once all feeds in the active stage were tried in this run.
     */
    public static int nextFeed(int preferredStart, boolean[] completed,
            boolean[] attempted, int mainlandEnd, int regionEnd) {
        int size = completed == null ? 0 : completed.length;
        if (size == 0 || attempted == null || attempted.length != size) return -1;
        int mainland = bound(mainlandEnd, size);
        int regions = Math.max(mainland, bound(regionEnd, size));
        int stage = activeStage(completed, mainland, regions);
        if (stage == 3) return -1;
        int from = stage == 0 ? 0 : stage == 1 ? mainland : regions;
        int to = stage == 0 ? mainland : stage == 1 ? regions : size;
        if (to <= from) return -1;
        int start = preferredStart >= from && preferredStart < to
                ? preferredStart : from;
        for (int distance = 0; distance < to - from; distance++) {
            int index = from + (start - from + distance) % (to - from);
            if (!completed[index] && !attempted[index]) return index;
        }
        return -1;
    }

    public static int completedInStage(boolean[] completed, int from, int end) {
        if (completed == null) return 0;
        int start = bound(from, completed.length);
        int stop = Math.max(start, bound(end, completed.length));
        int count = 0;
        for (int i = start; i < stop; i++) if (completed[i]) count++;
        return count;
    }

    public static String stageLabel(int activeStage) {
        if (activeStage == 0) return "优先抓取中国大陆：央视、卫视、各省地方台";
        if (activeStage == 1) return "中国大陆来源已检查，正在整理港澳台频道";
        if (activeStage == 2) return "中国频道优先阶段已完成，正在检查国际来源";
        return "所有配置目录已检查，等待完整快照发布";
    }

    private static int bound(int value, int size) {
        return Math.max(0, Math.min(size, value));
    }
}
