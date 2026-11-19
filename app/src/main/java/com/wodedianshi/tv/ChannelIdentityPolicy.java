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
        return identity(broadcaster, tvgId, fallbackIdentity, "");
    }

    static String identity(String broadcaster, String tvgId, String fallbackIdentity,
                           String localStationIdentity) {
        String canonical = broadcaster == null ? "" : broadcaster.trim();
        if (canonical.startsWith("CCTV-")) return canonical + "|cctv";
        if (canonical.endsWith("卫视")) return canonical.toLowerCase(Locale.ROOT) + "|satellite";

        // For a confidently identified Chinese city station, provider-specific
        // tvg-id namespaces are weaker than the shared province + station label.
        // This joins backups published by different feeds without merging
        // ambiguous city names or generic "新闻综合" labels.
        if (localStationIdentity != null && !localStationIdentity.isEmpty()) {
            return localStationIdentity;
        }
        String stableId = normalizeTvgId(tvgId);
        if (!stableId.isEmpty()) return "tvgid:" + stableId;
        return fallbackIdentity == null ? "" : fallbackIdentity;
    }

    static String localStationIdentity(String name, String tvgName) {
        String displayName = name == null ? "" : name.trim();
        String guideName = tvgName == null ? "" : tvgName.trim();
        String metadata = (displayName + " " + guideName).trim();
        String province = ChannelNamePolicy.provinceForLocalChannel(metadata);
        String city = ChannelNamePolicy.localCityForChannel(metadata);
        if (province.isEmpty() || city.isEmpty()) return "";

        String label = hasMeaningfulStationLabel(displayName) ? displayName : guideName;
        String normalizedLabel = normalizeLocalStationLabel(label);
        if (normalizedLabel.length() < 4) return "";
        return "local:" + province + "|" + city + "|" + normalizedLabel;
    }

    private static boolean hasMeaningfulStationLabel(String value) {
        String normalized = normalizeLocalStationLabel(value);
        return normalized.length() >= 4
                && !normalized.equals("频道")
                && !normalized.equals("直播频道")
                && !normalized.equals("高清频道")
                && !normalized.equals("新闻综合")
                && !normalized.equals("综合频道");
    }

    private static String normalizeLocalStationLabel(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT)
                .replaceAll("(?:超高清|高清|超清|标清|FULLHD|FHD|HD|SD|4K|8K|1080P|1080I|720P|720I|直播)", "")
                .replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
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
