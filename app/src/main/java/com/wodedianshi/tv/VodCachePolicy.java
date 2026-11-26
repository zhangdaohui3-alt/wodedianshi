package com.wodedianshi.tv;

/** Freshness policy for completed VOD catalogue aggregates. */
public final class VodCachePolicy {
    private VodCachePolicy() {}

    /** Freeze this key at request creation, never consult current UI region in a worker.
     * Search v10 deliberately does not reuse potentially cross-region v8 entries. */
    static String cacheKey(String category,String region) {
        if(category.startsWith("搜索：")){
            String normalizedRegion="国外".equals(region)?"国外":"中国";
            return "vod_cache_v10_"+CatalogueFingerprint.sha256(normalizedRegion+"\n"+category);
        }
        String revision=("多源精选".equals(category)||"精选电影".equals(category))?"9":"8";
        return "vod_cache_v"+revision+"_"+Integer.toHexString(category.hashCode());
    }

    public static boolean isFresh(long savedAtMs, long nowMs, long maxAgeMs) {
        return savedAtMs > 0L && nowMs >= savedAtMs
                && maxAgeMs > 0L && nowMs - savedAtMs < maxAgeMs;
    }
}
