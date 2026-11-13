package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaFormatPolicyTest {
    @Test public void detectsHlsAndIgnoresQueryAndFragment() {
        assertEquals(MediaFormatPolicy.HLS, MediaFormatPolicy.inferMimeType("https://cdn.example/live.m3u8?token=abc#part"));
        assertEquals(MediaFormatPolicy.HLS, MediaFormatPolicy.inferMimeType("https://cdn.example/live?format=m3u8"));
        assertEquals(MediaFormatPolicy.HLS, MediaFormatPolicy.inferMimeType("https://cdn.example/live?type=application/vnd.apple.mpegurl"));
    }

    @Test public void detectsDash() {
        assertEquals(MediaFormatPolicy.DASH, MediaFormatPolicy.inferMimeType("https://cdn.example/movie.mpd?auth=123"));
        assertEquals(MediaFormatPolicy.DASH, MediaFormatPolicy.inferMimeType("https://cdn.example/movie?container=dash"));
    }

    @Test public void detectsTransportStreams() {
        assertEquals(MediaFormatPolicy.MPEG_TS, MediaFormatPolicy.inferMimeType("https://cdn.example/channel.ts?token=abc"));
        assertEquals(MediaFormatPolicy.MPEG_TS, MediaFormatPolicy.inferMimeType("https://cdn.example/channel?ext=m2ts"));
    }

    @Test public void detectsSmoothStreamingManifest() {
        assertEquals(MediaFormatPolicy.SMOOTH_STREAMING,
                MediaFormatPolicy.inferMimeType("https://cdn.example/live.ism/Manifest"));
        assertEquals(MediaFormatPolicy.SMOOTH_STREAMING,
                MediaFormatPolicy.inferMimeType("https://cdn.example/live?format=smoothstreaming"));
        assertNull(MediaFormatPolicy.inferMimeType("https://cdn.example/live.ism"));
    }

    @Test public void detectsRtspSchemeWithoutInventingMimeType() {
        assertTrue(MediaFormatPolicy.isRtspUri("rtsp://camera.example/live"));
        assertTrue(MediaFormatPolicy.isRtspUri("RTSP://camera.example/live?token=x"));
        assertFalse(MediaFormatPolicy.isRtspUri("https://camera.example/live"));
        assertFalse(MediaFormatPolicy.isRtspUri("not a uri"));
        assertNull(MediaFormatPolicy.inferMimeType("rtsp://camera.example/live"));
    }

    @Test public void leavesProgressiveFormatsToExtractorSniffing() {
        assertNull(MediaFormatPolicy.inferMimeType("https://cdn.example/movie.mp4"));
        assertNull(MediaFormatPolicy.inferMimeType("https://cdn.example/movie.mkv"));
        assertNull(MediaFormatPolicy.inferMimeType("https://cdn.example/movie.webm"));
        assertNull(MediaFormatPolicy.inferMimeType("https://cdn.example/movie?token=movie.mpd"));
    }

    @Test public void handlesInvalidAddresses() {
        assertNull(MediaFormatPolicy.inferMimeType(null));
        assertNull(MediaFormatPolicy.inferMimeType(""));
        assertNull(MediaFormatPolicy.inferMimeType("   "));
        assertFalse(MediaFormatPolicy.isRtspUri(null));
    }
}
