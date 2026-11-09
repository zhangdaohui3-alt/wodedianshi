package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaPayloadValidatorTest {
    @Test public void acceptsMpegTsWithTwoPacketSyncBytes() {
        byte[] sample = new byte[376];
        sample[0] = 0x47;
        sample[188] = 0x47;
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(sample));
    }

    @Test public void rejectsSingleSyncByteAndRandomData() {
        byte[] sample = new byte[512];
        sample[0] = 0x47;
        assertFalse(MediaPayloadValidator.looksLikeHlsSegment(sample));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload("not a media stream".getBytes()));
    }

    @Test public void acceptsStructurallyValidFragmentedMp4Boxes() {
        byte[] moof = new byte[] {0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.hasIsoBmffBox(moof));
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(moof));
    }

    @Test public void rejectsBmffWordsInsideHtmlAndMalformedBoxSizes() {
        byte[] html = "<html><body>moof mdat</body></html>".getBytes();
        byte[] malformed = new byte[] {0,0,0,4,'m','o','o','f',0,0,0,0};
        assertFalse(MediaPayloadValidator.hasIsoBmffBox(html));
        assertFalse(MediaPayloadValidator.looksLikeHlsSegment(html));
        assertFalse(MediaPayloadValidator.hasIsoBmffBox(malformed));
    }

    @Test public void acceptsFlvAndAdtsPayloadSignatures() {
        byte[] flv = new byte[] {'F','L','V',1,0,0,0,9};
        byte[] adts = new byte[] {(byte)0xFF,(byte)0xF1,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(flv));
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(adts));
    }
}
