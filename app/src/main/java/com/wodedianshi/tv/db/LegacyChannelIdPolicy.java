package com.wodedianshi.tv.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable Room primary keys for imported legacy channels.
 *
 * Row-number IDs change when a channel is inserted or removed before another
 * row. A content identity plus duplicate occurrence keeps IDs stable for
 * ordinary catalogue edits and enables safe incremental Room synchronization.
 */
public final class LegacyChannelIdPolicy {
    private LegacyChannelIdPolicy() {}

    public static String idFor(String legacyKey, int occurrence) {
        String key = legacyKey == null ? "" : legacyKey;
        int ordinal = Math.max(0, occurrence);
        String identity = key.length() + ":" + key + ":" + ordinal;
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
