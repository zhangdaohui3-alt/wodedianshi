package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class HlsMediaPlaylistPolicyTest {
    @Test public void extractsFmp4InitializationMapAndSegments() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:6.0,\nseg-1.m4s\n"
                + "#EXTINF:6.0,\nseg-2.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertEquals("https://cdn.example/live/init.mp4", resources.initializationUrl);
        assertEquals(2, resources.mediaUrls.size());
        assertEquals("https://cdn.example/live/seg-1.m4s", resources.mediaUrls.get(0));
        assertEquals("https://cdn.example/live/seg-2.m4s", resources.mediaUrls.get(1));
    }

    @Test public void extractsLowLatencyPartsWithoutExtinf() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-PART-INF:PART-TARGET=0.333\n"
                + "#EXT-X-PART:DURATION=0.333,URI=\"part-1.m4s\"\n"
                + "#EXT-X-PART:DURATION=0.333,URI=\"part-2.m4s\"\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/ll/live.m3u8");
        assertNull(resources.initializationUrl);
        assertEquals(2, resources.mediaUrls.size());
        assertEquals("https://cdn.example/ll/part-1.m4s", resources.mediaUrls.get(0));
        assertEquals("https://cdn.example/ll/part-2.m4s", resources.mediaUrls.get(1));
    }

    @Test public void skipsByteRangePartsUntilRangeAwareProbeExists() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-PART:DURATION=0.333,URI=\"shared.m4s\",BYTERANGE=\"100@0\"\n"
                + "#EXT-X-PART:DURATION=0.333,URI=\"part-ok.m4s\"\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/ll/live.m3u8");
        assertEquals(1, resources.mediaUrls.size());
        assertEquals("https://cdn.example/ll/part-ok.m4s", resources.mediaUrls.get(0));
    }

    @Test public void resolvesAbsoluteUrisAndIgnoresMalformedMap() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MAP:BYTERANGE=\"100@0\"\n"
                + "#EXTINF:6,\nhttps://media.example/seg.ts\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertNull(resources.initializationUrl);
        assertEquals(1, resources.mediaUrls.size());
        assertEquals("https://media.example/seg.ts", resources.mediaUrls.get(0));
    }

    @Test public void rejectsInitializationMapWithByteRangeUntilRangeAwareProbeExists() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"shared.mp4\",BYTERANGE=\"720@128\"\n"
                + "#EXTINF:6,\nseg.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.unsupportedInitializationByteRange);
        assertNull(resources.initializationUrl);
        assertEquals(1, resources.mediaUrls.size());
    }

    @Test public void rejectsInitializationMapWithoutUri() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MAP:BYTERANGE=\"100@0\"\n"
                + "#EXTINF:6,\nseg.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.unsupportedInitializationMap);
    }

    @Test public void rejectsChangingInitializationMapUntilTimelineIsTracked() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init-a.mp4\"\n"
                + "#EXTINF:6,\nseg-a.m4s\n"
                + "#EXT-X-MAP:URI=\"init-b.mp4\"\n"
                + "#EXTINF:6,\nseg-b.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.unsupportedInitializationMap);
    }

    @Test public void recognizesIdentityAes128KeyAndResolvesKeyUri() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"../keys/live.key\"\n"
                + "#EXTINF:6,\nseg.ts\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.aes128Encrypted);
        assertFalse(resources.unsupportedEncryption);
        assertEquals("https://cdn.example/keys/live.key", resources.encryptionKeyUrl);
    }

    @Test public void rejectsMixedAes128AndClearMediaUntilPerSegmentEncryptionIsTracked() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-KEY:METHOD=NONE\n"
                + "#EXTINF:6,\nseg.ts\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.unsupportedEncryption);
        assertTrue(resources.aes128Encrypted);
    }

    @Test public void rejectsAes128EncryptedInitializationMapWithoutExplicitIv() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:6,\nseg.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.aes128Encrypted);
        assertTrue("AES-128 EXT-X-MAP requires an explicit IV", resources.unsupportedEncryption);
    }

    @Test public void acceptsAes128EncryptedInitializationMapWithExplicitIv() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXTINF:6,\nseg.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.aes128Encrypted);
        assertFalse(resources.unsupportedEncryption);
    }

    @Test public void rejectsSampleAesAndUnsupportedKeyFormats() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\"\n"
                + "#EXTINF:6,\nseg.ts\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.unsupportedEncryption);
    }

    @Test public void detectsEncryptionChangesAfterMediaCandidateLimit() throws Exception {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n")
                .append("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n");
        for (int i = 0; i < 5; i++) {
            playlist.append("#EXTINF:6,\nseg").append(i).append(".ts\n");
        }
        playlist.append("#EXT-X-KEY:METHOD=NONE\n");
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist.toString(), "https://cdn.example/live/index.m3u8");
        assertEquals(4, resources.mediaUrls.size());
        assertTrue("late METHOD=NONE must not be ignored", resources.unsupportedEncryption);
    }

    @Test public void detectsKeyRotationAfterMediaCandidateLimit() throws Exception {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n")
                .append("#EXT-X-KEY:METHOD=AES-128,URI=\"key-a.bin\"\n");
        for (int i = 0; i < 5; i++) {
            playlist.append("#EXTINF:6,\nseg").append(i).append(".ts\n");
        }
        playlist.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key-b.bin\"\n");
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist.toString(), "https://cdn.example/live/index.m3u8");
        assertEquals(4, resources.mediaUrls.size());
        assertTrue("late key rotation must not be ignored", resources.unsupportedEncryption);
    }

    @Test public void parsesWindowsStyleCrLfPlaylistLines() throws Exception {
        String playlist = "#EXTM3U\r\n"
                + "#EXTINF:6.0,\r\n"
                + "segment.ts\r\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertEquals(1, resources.mediaUrls.size());
        assertEquals("https://cdn.example/live/segment.ts", resources.mediaUrls.get(0));
    }

    @Test public void capsAndDeduplicatesExtractedMediaResources() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-PART:DURATION=0.3,URI=\"a.m4s\"\n"
                + "#EXT-X-PART:DURATION=0.3,URI=\"a.m4s\"\n"
                + "#EXT-X-PART:DURATION=0.3,URI=\"b.m4s\"\n"
                + "#EXT-X-PART:DURATION=0.3,URI=\"c.m4s\"\n"
                + "#EXT-X-PART:DURATION=0.3,URI=\"d.m4s\"\n"
                + "#EXT-X-PART:DURATION=0.3,URI=\"e.m4s\"\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live.m3u8");
        assertEquals(4, resources.mediaUrls.size());
        assertFalse(resources.mediaUrls.contains("https://cdn.example/e.m4s"));
    }

    @Test public void retainsFirstMediaSequenceForImplicitAesIv() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXTINF:6,\nseg-42.ts\n"
                + "#EXTINF:6,\nseg-43.ts\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.aes128Encrypted);
        assertNull(resources.encryptionIv);
        assertEquals(42L, resources.firstMediaSequenceNumber);
        assertEquals(2, resources.mediaUrls.size());
    }

    @Test public void retainsExplicitIvForEncryptedSegments() throws Exception {
        String iv = "0x0000000000000000000000000000002a";
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=" + iv + "\n"
                + "#EXTINF:6,\nseg.ts\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertEquals(iv, resources.encryptionIv);
        assertEquals(0L, resources.firstMediaSequenceNumber);
        assertFalse(resources.unsupportedEncryption);
    }

    @Test public void rejectsAesEncryptedLowLatencyPartsWithoutSequenceMapping() throws Exception {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-PART:DURATION=0.333,URI=\"part-1.ts\"\n";
        HlsMediaPlaylistPolicy.Resources resources = HlsMediaPlaylistPolicy.parse(
                playlist, "https://cdn.example/live/index.m3u8");
        assertTrue(resources.unsupportedEncryption);
    }

    @Test public void skipsGapsAndRangesButRetainsExactEncryptedSequences() throws Exception {
        String playlist="#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:40\n"
                +"#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                +"#EXTINF:6,\n#EXT-X-GAP\nmissing.ts\n"
                +"#EXTINF:6,\n# comment\nfirst.ts\n"
                +"#EXTINF:6,\n#EXT-X-BYTERANGE:100@0\nshared.ts\n"
                +"#EXTINF:6,\nfirst.ts\n"
                +"#EXTINF:6,\nsecond.ts\n";
        HlsMediaPlaylistPolicy.Resources resources=HlsMediaPlaylistPolicy.parse(
                playlist,"https://cdn.example/live/index.m3u8");
        assertFalse(resources.unsupportedEncryption);
        assertEquals(java.util.Arrays.asList("https://cdn.example/live/first.ts",
                "https://cdn.example/live/second.ts"),resources.mediaUrls);
        assertEquals(java.util.Arrays.asList(41L,44L),resources.mediaSequenceNumbers);
        assertEquals(41L,resources.firstMediaSequenceNumber);
    }

    @Test public void skipsUnavailableLowLatencyPartAndKeepsClearPartAlignment() throws Exception {
        String playlist="#EXTM3U\n"
                +"#EXT-X-PART:DURATION=0.3,GAP=YES,URI=\"missing.m4s\"\n"
                +"#EXT-X-PART:DURATION=0.3,URI=\"part.m4s\"\n"
                +"#EXTINF:6,\n# comment\nfull.m4s\n";
        HlsMediaPlaylistPolicy.Resources resources=HlsMediaPlaylistPolicy.parse(
                playlist,"https://cdn.example/live/index.m3u8");
        assertEquals(java.util.Arrays.asList("https://cdn.example/live/part.m4s",
                "https://cdn.example/live/full.m4s"),resources.mediaUrls);
        assertEquals(java.util.Arrays.asList(-1L,0L),resources.mediaSequenceNumbers);
    }

    @Test public void rejectsClearThenEncryptedTimelineWithoutPerSegmentKeys() throws Exception {
        String playlist="#EXTM3U\n#EXTINF:6,\nclear.ts\n"
                +"#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                +"#EXTINF:6,\nencrypted.ts\n";
        assertTrue(HlsMediaPlaylistPolicy.parse(playlist,
                "https://cdn.example/live/index.m3u8").unsupportedEncryption);
    }

    @Test public void longPlaylistSkipsUnusedResourceResolutionButChecksLateMapAndKey()throws Exception{
        StringBuilder playlist=new StringBuilder("#EXTM3U\n#EXT-X-MAP:URI=\"init-a.mp4\"\n");
        for(int i=0;i<4000;i++)playlist.append("#EXTINF:6,\nseg").append(i).append(".m4s\n");
        // Beyond the retained prefix, an unused malformed resource cannot
        // prevent validation of the earlier selected media.
        playlist.append("#EXTINF:6,\nhttp://[invalid\n");
        String base="https://cdn.example/live/index.m3u8";
        HlsMediaPlaylistPolicy.Resources first=HlsMediaPlaylistPolicy.parse(playlist.toString(),base);
        assertEquals(4,first.mediaUrls.size());
        assertEquals(java.util.Arrays.asList(0L,1L,2L,3L),first.mediaSequenceNumbers);
        assertFalse(first.unsupportedEncryption);
        assertFalse(first.unsupportedInitializationMap);
        playlist.append("#EXT-X-MAP:URI=\"init-b.mp4\"\n")
                .append("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\"\n");
        HlsMediaPlaylistPolicy.Resources late=HlsMediaPlaylistPolicy.parse(playlist.toString(),base);
        assertEquals(first.mediaUrls,late.mediaUrls);
        assertEquals(first.mediaSequenceNumbers,late.mediaSequenceNumbers);
        assertTrue(late.unsupportedEncryption);
        assertTrue(late.unsupportedInitializationMap);
    }

    @Test public void unsupportedRangeOnlyPlaylistDiffersFromEmptyAndMixedPlaylist()throws Exception{
        String base="https://example.invalid/live.m3u8";
        String range="#EXTM3U\n#EXTINF:6,\n#EXT-X-BYTERANGE:376@0\nshared.ts\n";
        HlsMediaPlaylistPolicy.Resources only=HlsMediaPlaylistPolicy.parse(range,base);
        assertTrue(only.skippedMediaByteRange);
        assertTrue(only.requiresUnsupportedPreflight());
        assertFalse(HlsMediaPlaylistPolicy.parse("#EXTM3U\n",base).requiresUnsupportedPreflight());
        assertFalse(HlsMediaPlaylistPolicy.parse(range+"#EXTINF:6,\nnormal.ts\n",base).requiresUnsupportedPreflight());
        assertTrue(HlsMediaPlaylistPolicy.parse("#EXTM3U\n#EXT-X-PART:DURATION=0.3,URI=\"shared.m4s\",BYTERANGE=\"100@0\"\n",base).requiresUnsupportedPreflight());
    }
}
