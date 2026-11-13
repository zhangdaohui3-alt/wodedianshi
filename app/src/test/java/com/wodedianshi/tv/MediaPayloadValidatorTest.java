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
        byte[] flv = new byte[] {'F','L','V',1,0,0,0,9,0};
        byte[] adts = new byte[] {(byte)0xFF,(byte)0xF1,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(flv));
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(adts));
        assertTrue(MediaPayloadValidator.looksLikePayloadForAddress(flv, "https://cdn.test/live.flv"));
        assertTrue(MediaPayloadValidator.looksLikePayloadForAddress(adts, "https://cdn.test/audio.aac"));
    }

    @Test public void validatesContainerSignaturesInsteadOfTrustingExtensions() {
        byte[] html = "<html>not a stream</html>".getBytes();
        byte[] ebml = new byte[] {0x1A,0x45,(byte)0xDF,(byte)0xA3,0,0,0,0};
        byte[] mp4 = new byte[] {0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0};
        assertFalse(MediaPayloadValidator.looksLikePayloadForAddress(html, "https://cdn.test/channel.ts"));
        assertFalse(MediaPayloadValidator.looksLikePayloadForAddress(html, "https://cdn.test/movie.mp4"));
        assertTrue(MediaPayloadValidator.looksLikePayloadForAddress(ebml, "https://cdn.test/movie.mkv"));
        assertTrue(MediaPayloadValidator.looksLikePayloadForAddress(ebml, "https://cdn.test/movie.webm"));
        assertTrue(MediaPayloadValidator.looksLikePayloadForAddress(mp4, "https://cdn.test/movie.mp4?token=abc"));
    }

    @Test public void rejectsWrongContainerForKnownExtension() {
        byte[] flv = new byte[] {'F','L','V',1,0,0,0,9};
        assertFalse(MediaPayloadValidator.looksLikePayloadForAddress(flv, "https://cdn.test/movie.mp4"));
        assertFalse(MediaPayloadValidator.looksLikePayloadForAddress(flv, "https://cdn.test/movie.mkv"));
    }
}
