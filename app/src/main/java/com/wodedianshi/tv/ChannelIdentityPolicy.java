package com.wodedianshi.tv;

import java.util.Locale;

/**
 * Conservative cross-feed channel identity.
 *
 * Recognized CCTV/satellite identities keep their established keys. Other
 * channels may share backups across hosts only when a sufficiently specific
 * tvg-id is present; display names alone are not strong enough to merge local
 * stations from different cities or providers.
 */
final class ChannelIdentityPolicy {
    private ChannelIdentityPolicy() {}

    static String identity(String broadcaster, String tvgId, String fallbackIdentity) {
        String canonical = broadcaster == null ? "" : broadcaster.trim();
        if (canonical.startsWith("CCTV-")) return canonical + "|cctv";
        if (canonical.endsWith("卫视")) return canonical.toLowerCase(Locale.ROOT) + "|satellite";

        String stableId = normalizeTvgId(tvgId);
        if (!stableId.isEmpty()) return "tvgid:" + stableId;
        return fallbackIdentity == null ? "" : fallbackIdentity;
    }

    /**
     * Reject short or purely numeric IDs: feeds sometimes use generic values
     * such as "1" or "101" for unrelated channels. Keep punctuation because
     * provider namespaces (for example FJTV.FUZHOU) are meaningful.
     */
    static String normalizeTvgId(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (normalized.length() < 4 || !normalized.matches(".*[a-z\\u4e00-\\u9fff].*")) return "";
        return normalized;
    }
}
