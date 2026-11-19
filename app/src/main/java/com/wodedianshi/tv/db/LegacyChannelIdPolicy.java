package com.wodedianshi.tv.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable Room primary keys for imported legacy channels.
 *
 * Length-prefixed name and URL avoid delimiter collisions in the legacy
 * "name|url" favorite key. Duplicate occurrences distinguish truly repeated
 * legacy rows without depending on unrelated rows' positions.
 */
public final class LegacyChannelIdPolicy {
    private LegacyChannelIdPolicy() {}

    public static String identityKey(String name, String primaryUrl) {
        String safeName = name == null ? "" : name;
        String safeUrl = primaryUrl == null ? "" : primaryUrl;
        return safeName.length() + ":" + safeName + safeUrl.length() + ":" + safeUrl;
    }


    /**
     * A source-independent identity for broadcasters with a trustworthy TVG id,
     * plus the well-known national CCTV/satellite names. Generic local stations
     * without a stable provider id remain host/URL-scoped to avoid false merges.
     */
    public static String stableIdentityKey(String name, String primaryUrl, String tvgId) {
        String safeId = tvgId == null ? "" : tvgId.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "");
        if (safeId.length() >= 4 && safeId.matches(".*[a-z\\u4e00-\\u9fff].*")) {
            return "tvgid:" + safeId;
        }
        String canonical = name == null ? "" : name.trim().replaceAll("\\s+", "");
        String upper = canonical.toUpperCase(java.util.Locale.ROOT);
        if (upper.matches("CCTV[-_ ]?\\d+([+]?|KIDS)?")) {
            return "broadcaster:" + upper.replace('_', '-').replace(" ", "");
        }
        if (canonical.endsWith("卫视")) {
            return "broadcaster:" + canonical.toLowerCase(java.util.Locale.ROOT);
        }
        return identityKey(name, primaryUrl);
    }

    public static String idForStableIdentity(String stableIdentity, int occurrence) {
        String identity = (stableIdentity == null ? "" : stableIdentity)
                + ":" + Math.max(0, occurrence);
        return digest(identity);
    }

    public static String idFor(String name, String primaryUrl, int occurrence) {
        int ordinal = Math.max(0, occurrence);
        return digest(identityKey(name, primaryUrl) + ":" + ordinal);
    }

    private static String digest(String identity) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            int unsigned = value & 0xff;
            if (unsigned < 16) hex.append('0');
            hex.append(Integer.toHexString(unsigned));
        }
        return "legacy:" + hex;
    }
}
