package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Collision-resistant persistent keys with helpers for reading pre-v2 keys. */
public final class StableKey {
    private StableKey() {}

    public static String url(String value) {
        return digest("url\n" + safe(value));
    }

    public static String channel(String name, String group, String primaryUrl) {
        return digest("channel\n" + safe(name) + "\n" + safe(group) + "\n" + safe(primaryUrl));
    }

    public static String legacyUrl(String value) {
        String raw = safe(value);
        return Integer.toHexString(raw.hashCode()) + ":" + raw.length();
    }

    public static String legacyChannel(String name, String group, String primaryUrl) {
        String raw = safe(name) + "\n" + safe(group) + "\n" + safe(primaryUrl);
        return Integer.toHexString(raw.hashCode()) + ":" + raw.length();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("v2:");
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
