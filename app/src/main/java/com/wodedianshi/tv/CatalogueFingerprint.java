package com.wodedianshi.tv;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable, allocation-bounded fingerprint for large legacy catalogue snapshots. */
public final class CatalogueFingerprint {
    private CatalogueFingerprint() {}

    public static String sha256(String value) {
        String input = value == null ? "" : value;
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }

        // Hash UTF-16 code units directly in bounded chunks. This avoids allocating
        // another 10–20 MB UTF-8 byte array for the large saved channel catalogue.
        byte[] chunk = new byte[8192];
        int offset = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            chunk[offset++] = (byte) (c >>> 8);
            chunk[offset++] = (byte) c;
            if (offset == chunk.length) {
                digest.update(chunk, 0, offset);
                offset = 0;
            }
        }
        if (offset > 0) digest.update(chunk, 0, offset);

        byte[] hash = digest.digest();
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            int v = b & 0xff;
            if (v < 16) result.append('0');
            result.append(Integer.toHexString(v));
        }
        return result.toString();
    }
}
