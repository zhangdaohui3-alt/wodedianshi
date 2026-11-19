package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Stable identity and publication guard for per-location channel snapshots. */
public final class GeoSnapshotPolicy {
    private GeoSnapshotPolicy() {}

    public static String snapshotKey(String playlistAddress) {
        if (playlistAddress == null || playlistAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("playlistAddress must not be empty");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(playlistAddress.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("geo_snapshot_");
            for (byte value : digest) {
                key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return key.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /** Empty or invalid playlists must never replace a last-known-good snapshot. */
    public static boolean shouldReplaceSnapshot(int validChannelCount) {
        return validChannelCount > 0;
    }
}
