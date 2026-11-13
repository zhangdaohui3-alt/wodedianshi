package com.wodedianshi.tv;

/** Pure identity policy for persisted VOD history rows. */
public final class VodHistoryIdentity {
    private VodHistoryIdentity() {}

    /** Provider IDs take precedence; URL is the stable fallback for ID-less media. */
    public static String forItem(String id, String url) {
        String stableId = safe(id);
        if (!stableId.isEmpty()) return "id:" + stableId;
        String mediaUrl = safe(url);
        return mediaUrl.isEmpty() ? "" : "url:" + mediaUrl;
    }

    /** Preserve a stored key, while migrating legacy rows without one. */
    public static String forEntry(String storedKey, String id, String url) {
        String stored = safe(storedKey);
        return stored.isEmpty() ? forItem(id, url) : stored;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
