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

    /** Stable channel identity independent of whichever backup URL is currently primary. */
    public static String channelIdentity(String identity) {
        return digest("channel-identity\n" + safe(identity));
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
            // Exact same lowercase encoding without 32 Formatter allocations per digest.
            char[] out = new char[3 + bytes.length * 2];
            out[0]='v';out[1]='2';out[2]=':';
            final String hex="0123456789abcdef";
            for(int i=0;i<bytes.length;i++){
                int b=bytes[i]&0xff;
                out[3+i*2]=hex.charAt(b>>>4);
                out[4+i*2]=hex.charAt(b&15);
            }
            return new String(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
