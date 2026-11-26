package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class HlsAes128PreflightTest {
    private static final byte[] KEY = new byte[] {
            0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15
    };

    private static byte[] transportStreamSample() {
        byte[] data = new byte[576];
        Arrays.fill(data, (byte) 0x55);
        for (int offset = 0; offset + 188 <= data.length; offset += 188) {
            data[offset] = 0x47;
            data[offset + 1] = 0x00;
            data[offset + 2] = 0x10;
            data[offset + 3] = 0x10; // payload only; adaptation_field_control must not be zero
        }
        return data;
    }

    private static byte[] encrypt(byte[] clear, byte[] iv, String transformation) throws Exception {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(clear);
    }

    @Test public void derivesMediaSequenceIvAsSixteenByteBigEndianValue() {
        byte[] iv = HlsAes128Preflight.ivForMediaSequence(0x0102030405060708L);
        assertNotNull(iv);
        assertEquals(16, iv.length);
        assertEquals(1, iv[8] & 0xff);
        assertEquals(8, iv[15] & 0xff);
        assertEquals(0, iv[7]);
        assertNull(HlsAes128Preflight.ivForMediaSequence(-1L));
    }

    @Test public void parsesExplicit128BitIv() {
        byte[] iv = HlsAes128Preflight.parseExplicitIv("0x0000000000000000000000000000002a");
        assertNotNull(iv);
        assertEquals(0x2a, iv[15] & 0xff);
        assertNull(HlsAes128Preflight.parseExplicitIv("0x1234"));
    }

    @Test public void decryptsCompletePkcs7PaddedTsAndRejectsWrongKey() throws Exception {
        byte[] iv = HlsAes128Preflight.ivForMediaSequence(7L);
        byte[] encrypted = encrypt(transportStreamSample(), iv, "AES/CBC/PKCS5Padding");
        assertTrue(HlsAes128Preflight.validatesMediaSegment(encrypted, KEY, iv, true));
        byte[] wrongKey = KEY.clone();
        wrongKey[0] ^= 0x7f;
        assertFalse(HlsAes128Preflight.validatesMediaSegment(encrypted, wrongKey, iv, true));
    }

    @Test public void decryptsBlockAlignedBoundedTsPrefixWithoutAssumingFinalPadding() throws Exception {
        byte[] iv = HlsAes128Preflight.ivForMediaSequence(9L);
        byte[] encrypted = encrypt(transportStreamSample(), iv, "AES/CBC/NoPadding");
        assertTrue(HlsAes128Preflight.validatesMediaSegment(encrypted, KEY, iv, false));
    }

    @Test public void rejectsUnalignedOrTooShortEncryptedSamples() {
        byte[] iv = HlsAes128Preflight.ivForMediaSequence(1L);
        assertNull(HlsAes128Preflight.decryptSample(new byte[31], KEY, iv, false));
        assertNull(HlsAes128Preflight.decryptSample(new byte[33], KEY, iv, false));
    }
}
