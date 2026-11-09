package com.wodedianshi.tv;

/** Restricts the official CCTV catalogue to categories it actually represents. */
public final class CctvVodCategoryPolicy {
    private static final String DOCUMENTARY_CHANNEL = "CHAL1460955924871139";
    private static final String ANIMATION_CHANNEL = "CHAL1460955899450127";

    private CctvVodCategoryPolicy() {}

    public static String channelIdFor(String category) {
        if ("中国纪录片".equals(category) || "中文纪录片".equals(category)) {
            return DOCUMENTARY_CHANNEL;
        }
        if ("中国动画".equals(category) || "中文动画".equals(category)) {
            return ANIMATION_CHANNEL;
        }
        return null;
    }
}
