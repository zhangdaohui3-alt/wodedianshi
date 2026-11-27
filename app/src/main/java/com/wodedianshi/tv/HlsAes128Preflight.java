package com.wodedianshi.tv;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Bounded AES-128 HLS sample decryption and post-decryption container validation. */
public final class HlsAes128Preflight {
    private HlsAes128Preflight() {}

    /** RFC 8216: encode the media sequence number as a 16-byte, big-endian unsigned integer. */
    public static byte[] ivForMediaSequence(long sequence) {
        if (sequence < 0L) return null;
        byte[] iv = new byte[16];
        long value = sequence;
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) value;
            value >>>= 8;
        }
        return iv;
    }

    /** Parse a validated 0x-prefixed 128-bit HLS IV. */
    public static byte[] parseExplicitIv(String value) {
        if (value == null || !value.matches("0[xX][0-9a-fA-F]{32}")) return null;
        byte[] iv = new byte[16];
        for (int i = 0; i < iv.length; i++) {
            int at = 2 + i * 2;
            iv[i] = (byte) Integer.parseInt(value.substring(at, at + 2), 16);
        }
        return iv;
    }

    /**
     * Decrypt a bounded sample. A complete segment uses PKCS7-compatible PKCS5
     * padding validation; a range-truncated prefix uses NoPadding and must be
     * block aligned. Plaintext is returned only to the caller for immediate checks.
     */
    public static byte[] decryptSample(byte[] encrypted, byte[] key, byte[] iv, boolean completeSegment) {
        if (encrypted == null || key == null || key.length != 16 || iv == null || iv.length != 16
                || encrypted.length < 32 || (encrypted.length & 15) != 0) return null;
        try {
            Cipher cipher = Cipher.getInstance(completeSegment ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException invalidCiphertext) {
            return null;
        }
    }

    public static boolean validatesMediaSegment(byte[] encrypted, byte[] key, byte[] iv,
                                                  boolean completeSegment) {
        byte[] clear = decryptSample(encrypted, key, iv, completeSegment);
        return clear != null && MediaPayloadValidator.looksLikeHlsSegment(clear);
    }

    public static boolean validatesInitializationSegment(byte[] encrypted, byte[] key, byte[] iv,
                                                            boolean completeResource) {
        byte[] clear = decryptSample(encrypted, key, iv, completeResource);
        return clear != null && MediaPayloadValidator.hasIsoBmffInitializationSegment(clear);
    }
}
