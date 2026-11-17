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

    public static String idFor(String name, String primaryUrl, int occurrence) {
        int ordinal = Math.max(0, occurrence);
        String identity = identityKey(name, primaryUrl) + ":" + ordinal;
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
