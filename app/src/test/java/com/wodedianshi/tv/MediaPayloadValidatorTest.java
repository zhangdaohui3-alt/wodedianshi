package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaPayloadValidatorTest {
    @Test public void inPlacePrefixNeverReadsUnusedCapacity(){
        byte[] ts=new byte[8192];
        ts[187]=0x47;ts[190]=0x10;ts[375]=0x47;ts[378]=0x10;
        byte[] fragment={0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0,
                0,0,16,0,'m','d','a','t',1};
        byte[] random=new byte[8192];new java.util.Random(71L).nextBytes(random);
        for(byte[] sample:new byte[][]{ts,java.util.Arrays.copyOf(fragment,8192),random}){
            for(int length=0;length<=sample.length;length++){
                assertEquals("length="+length,
                    MediaPayloadValidator.hasConfirmedHlsPrefix(java.util.Arrays.copyOf(sample,length)),
                    MediaPayloadValidator.hasConfirmedHlsPrefix(sample,length));
            }
        }
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(ts,562));
        assertTrue(MediaPayloadValidator.hasConfirmedHlsPrefix(ts,563));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(fragment,24));
        assertTrue(MediaPayloadValidator.hasConfirmedHlsPrefix(fragment,25));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(ts,-1));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(ts,8193));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(null,0));
    }

    @Test public void earlyTsEvidenceMatchesFullSampleAndRequiresTwoCompletePackets(){
        byte[] full=new byte[8192];
        int offset=187;
        full[offset]=0x47;full[offset+3]=0x10;
        full[offset+188]=0x47;full[offset+191]=0x10;
        assertFalse(MediaPayloadValidator.hasTransportStreamPackets(java.util.Arrays.copyOf(full,562)));
        assertTrue(MediaPayloadValidator.hasTransportStreamPackets(java.util.Arrays.copyOf(full,563)));
        assertTrue(MediaPayloadValidator.hasTransportStreamPackets(java.util.Arrays.copyOf(full,1024)));
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(full));
        assertFalse(MediaPayloadValidator.hasTransportStreamPackets("<html>error</html>".getBytes()));
        byte[] mp4={0,0,0,8,'m','o','o','f',0,0,0,8,'m','d','a','t'};
        assertFalse(MediaPayloadValidator.hasTransportStreamPackets(mp4));
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(mp4));
    }

    @Test public void acceptsMpegTsWithTwoPacketSyncBytes() {
        byte[] sample = new byte[376];
        sample[0] = 0x47;
        sample[3] = 0x10;
        sample[188] = 0x47;
        sample[191] = 0x10;
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(sample));
    }

    @Test public void rejectsSingleSyncByteAndRandomData() {
        byte[] sample = new byte[512];
        sample[0] = 0x47;
        assertFalse(MediaPayloadValidator.looksLikeHlsSegment(sample));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload("not a media stream".getBytes()));
    }

    @Test public void rejectsTransportStreamSyncBytesWithReservedHeaderFields() {
        byte[] sample = new byte[376];
        sample[0] = 0x47;
        sample[188] = 0x47;
        assertFalse(MediaPayloadValidator.looksLikeHlsSegment(sample));
        sample[3] = 0x10;
        sample[191] = 0x10;
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(sample));
    }

    @Test public void rejectsTransportStreamPacketWithOversizedAdaptationField() {
        byte[] sample = new byte[376];
        sample[0] = 0x47;
        sample[3] = 0x30;
        sample[4] = (byte)183;
        sample[188] = 0x47;
        sample[191] = 0x10;
        assertFalse(MediaPayloadValidator.looksLikeHlsSegment(sample));
    }

    @Test public void acceptsStructurallyValidFragmentedMp4Boxes() {
        byte[] fragment = new byte[] {
                0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0,
                0,0,0,16,'m','d','a','t',0,0,0,0,0,0,0,0
        };
        assertTrue(MediaPayloadValidator.hasIsoBmffBox(fragment));
        assertTrue(MediaPayloadValidator.hasIsoBmffMediaFragment(fragment));
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(fragment));
    }

    @Test public void hlsMediaFragmentRequiresMoofFollowedByMdat() {
        byte[] moofOnly = new byte[] {0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0};
        byte[] mdatOnly = new byte[] {0,0,0,16,'m','d','a','t',0,0,0,0,0,0,0,0};
        byte[] initOnly = new byte[] {0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0};
        assertFalse(MediaPayloadValidator.hasIsoBmffMediaFragment(moofOnly));
        assertFalse(MediaPayloadValidator.hasIsoBmffMediaFragment(mdatOnly));
        assertFalse(MediaPayloadValidator.looksLikeHlsSegment(initOnly));
    }

    @Test public void acceptsBoundedMdatBodyWhenItsHeaderFollowsMoof() {
        byte[] sample = new byte[] {
                0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0,
                0,0,16,0,'m','d','a','t'
        };
        assertTrue(MediaPayloadValidator.hasIsoBmffMediaFragment(sample));
    }

    @Test public void initializationMapRequiresFileTypeAndMovieBoxes() {
        byte[] init = new byte[] {
                0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0,
                0,0,0,16,'m','o','o','v',0,0,0,0,0,0,0,0
        };
        byte[] mediaFragment = new byte[] {0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0};
        byte[] mediaData = new byte[] {0,0,0,16,'m','d','a','t',0,0,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.hasIsoBmffInitializationSegment(init));
        assertFalse(MediaPayloadValidator.hasIsoBmffInitializationSegment(mediaFragment));
        assertFalse(MediaPayloadValidator.hasIsoBmffInitializationSegment(mediaData));
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
        ts[3] = 0x10;
        ts[188] = 0x47;
        ts[191] = 0x10;
        byte[] mp4 = new byte[] {0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(ts, "https://cdn.example/live.ts"));
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(mp4, "https://cdn.example/movie.mp4"));
    }

    @Test public void rejectsMalformedFlvHeaderFields() {
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {'F','L','V',2,5,0,0,0,9}));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {'F','L','V',1,(byte)0xFF,0,0,0,9}));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {'F','L','V',1,5,0,0,0,8}));
    }

    @Test public void acceptsShortValidFmp4InitializationSamples() {
        byte[] sample = new byte[] {0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(sample, "https://cdn.example/live.mp4"));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {0,0,0,4,'m','o','o','f'}, "https://cdn.example/live.mp4"));
    }

    @Test public void rejectsMalformedAdtsHeaders() {
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {(byte)0xFF,(byte)0xF1,0x3C,(byte)0x80,0x01,0x1F,(byte)0xFC}));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {(byte)0xFF,(byte)0xF7,0x50,(byte)0x80,0x01,0x1F,(byte)0xFC}));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {(byte)0xFF,(byte)0xF1,0x50,(byte)0x80,0x00,0x1F,(byte)0xFC}));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(
                new byte[] {(byte)0xFF,(byte)0xF0,0x50,(byte)0x80,0x01,0x1F,(byte)0xFC}));
    }

    @Test public void acceptsFlvAndAdtsPayloadSignatures() {
        byte[] flv = new byte[] {'F','L','V',1,5,0,0,0,9};
        // Valid ADTS header: 44.1 kHz, stereo, one 8-byte frame.
        byte[] adts = new byte[] {(byte)0xFF,(byte)0xF1,0x50,(byte)0x80,0x01,0x1F,(byte)0xFC};
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(flv));
        assertTrue(MediaPayloadValidator.looksLikeMediaPayload(adts));
    }

    @Test public void earlyFragmentConfirmationRequiresCompleteMoofAndMediaByte(){
        byte[] fragment={0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0,
                0,0,16,0,'m','d','a','t',1};
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(java.util.Arrays.copyOf(fragment,15)));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(java.util.Arrays.copyOf(fragment,24)));
        assertTrue(MediaPayloadValidator.hasConfirmedHlsPrefix(fragment));
        assertTrue(MediaPayloadValidator.hasConfirmedHlsPrefix(java.util.Arrays.copyOf(fragment,8192)));
        assertTrue(MediaPayloadValidator.looksLikeHlsSegment(fragment));
        byte[] emptyMdat={0,0,0,8,'m','o','o','f',0,0,0,8,'m','d','a','t',1};
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(emptyMdat));
    }

    @Test public void earlyConfirmationPreservesExistingTsCheck(){
        byte[] ts=new byte[376];
        ts[0]=0x47;ts[3]=0x10;ts[188]=0x47;ts[191]=0x10;
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(java.util.Arrays.copyOf(ts,375)));
        assertTrue(MediaPayloadValidator.hasConfirmedHlsPrefix(ts));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(new byte[8192]));
    }

    @Test public void directTsScreenRequiresEightPacketsAndNonNullPayload(){
        byte[] sample=new byte[4096];
        for(int i=0;i<8;i++){
            int at=i*188;
            sample[at]=0x47;sample[at+1]=0x1f;sample[at+2]=(byte)0xff;sample[at+3]=0x10;
        }
        assertFalse(MediaPayloadValidator.hasDirectTsScreeningPrefix(sample));
        sample[1]=0;sample[2]=0x20;
        assertTrue(MediaPayloadValidator.hasDirectTsScreeningPrefix(sample));
        assertFalse(MediaPayloadValidator.hasDirectTsScreeningPrefix(java.util.Arrays.copyOf(sample,7*188)));
        sample[7*188+1]=(byte)0x9f;
        assertFalse(MediaPayloadValidator.hasDirectTsScreeningPrefix(sample));
    }

    @Test public void nullOnlyAndTransportErrorSamplesCannotPassEitherTsTier(){
        byte[] data=new byte[188*12];
        for(int at=0;at<data.length;at+=188){
            data[at]=0x47;data[at+1]=0x1f;data[at+2]=(byte)0xff;data[at+3]=0x10;
        }
        assertFalse(MediaPayloadValidator.hasTransportStreamPackets(data));
        assertFalse(MediaPayloadValidator.hasConfirmedHlsPrefix(data));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(data));
        for(int at=0;at<data.length;at+=188){
            data[at+1]=(byte)0x80;data[at+2]=0x20;
        }
        assertFalse(MediaPayloadValidator.hasTransportStreamPackets(data));
        assertFalse(MediaPayloadValidator.hasDirectTsScreeningPrefix(data));
        assertFalse(MediaPayloadValidator.looksLikeMediaPayload(data));
    }

    @Test public void alignedCleanPayloadAfterNullAndErrorPacketsCanRecover(){
        byte[] data=new byte[188*5];
        for(int at=0;at<data.length;at+=188){
            data[at]=0x47;data[at+1]=0x1f;data[at+2]=(byte)0xff;data[at+3]=0x10;
        }
        data[188+1]=(byte)0x80;data[188+2]=0x20;
        data[188*3+1]=0;data[188*3+2]=0x20;
        assertFalse(MediaPayloadValidator.hasTransportStreamPackets(java.util.Arrays.copyOf(data,188*3)));
        assertTrue(MediaPayloadValidator.hasTransportStreamPackets(data));
        assertTrue(MediaPayloadValidator.hasConfirmedHlsPrefix(data));
    }
}
