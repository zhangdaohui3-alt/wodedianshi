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

    @Test public void familiarUrlSuffixNeverOverridesAnInvalidPayload() {
        byte[] html = "<html><body>upstream error</body></html>".getBytes();
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(html, "https://cdn.example/live.ts"));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(html, "https://cdn.example/movie.mp4"));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(html, "https://cdn.example/movie.flv"));
    }

    @Test public void validMediaSignaturesRemainAcceptedWithKnownSuffixes() {
        byte[] ts = new byte[376];
        ts[0] = 0x47;
        ts[188] = 0x47;
        byte[] mp4 = new byte[] {0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(ts, "https://cdn.example/live.ts"));
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(mp4, "https://cdn.example/movie.mp4"));
    }

    @Test public void acceptsShortValidFmp4InitializationSamples() {
        byte[] sample = new byte[] {0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(sample, "https://cdn.example/live.mp4"));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {0,0,0,4,'m','o','o','f'}, "https://cdn.example/live.mp4"));
    }

    @Test public void acceptsFlvAndAdtsPayloadSignatures() {
        byte[] flv = new byte[] {'F','L','V',1,0,0,0,9};
        byte[] adts = new byte[] {(byte)0xFF,(byte)0xF1,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(flv));
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(adts));
    }
}
