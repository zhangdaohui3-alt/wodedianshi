package com.wodedianshi.tv;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class HlsVariantPolicyTest {
    @Test public void duplicateRenditionsDoNotConsumeTheDistinctAttemptLimit() throws Exception {
        StringBuilder master = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 12; i++) {
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(1000000 + i)
                    .append("\n").append(i % 2 == 0 ? "same.m3u8" :
                        "https://cdn.example/root/same.m3u8").append("\n");
        }
        master.append("#EXT-X-STREAM-INF:BANDWIDTH=200000\nlow.m3u8\n");
        ArrayList<HlsVariantPolicy.Variant> variants = HlsVariantPolicy.parse(
                master.toString(), "https://cdn.example/root/master.m3u8");
        assertEquals(2, variants.size());
        assertEquals("https://cdn.example/root/same.m3u8", variants.get(0).url);
        assertEquals(1000011L, variants.get(0).bandwidth);
        assertEquals("https://cdn.example/root/low.m3u8", variants.get(1).url);
    }

    @Test public void equivalentHostAndDefaultPortDoNotConsumeRenditionSlots() throws Exception {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=900000\nhttps://CDN.example:443/live.m3u8?token=a\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://cdn.example/live.m3u8?token=a\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=700000\nhttps://cdn.example/live.m3u8?token=b\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=600000\nhttps://cdn.example:8443/live.m3u8?token=a\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=500000\nhttps://cdn.example/Live.m3u8?token=a\n";
        ArrayList<HlsVariantPolicy.Variant> variants = HlsVariantPolicy.parse(master,"https://cdn.example/master.m3u8");
        assertEquals(4,variants.size());
        assertEquals("https://CDN.example:443/live.m3u8?token=a",variants.get(0).url);
        assertEquals(900000L,variants.get(0).bandwidth);
        assertEquals("https://cdn.example/live.m3u8?token=b",variants.get(1).url);
        assertEquals("https://cdn.example:8443/live.m3u8?token=a",variants.get(2).url);
        assertEquals("https://cdn.example/Live.m3u8?token=a",variants.get(3).url);
    }

    @Test public void invalidChildUrlsDoNotDiscardHealthySiblingOrDistinctQueries() throws Exception {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=900000\nhttp://[broken\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000\nfile:///tmp/invalid.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=700000\nchild.m3u8?token=a\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=600000\nchild.m3u8?token=b\n";
        ArrayList<HlsVariantPolicy.Variant> variants = HlsVariantPolicy.parse(
                master, "https://cdn.example/root/master.m3u8");
        assertEquals(2, variants.size());
        assertEquals("https://cdn.example/root/child.m3u8?token=a", variants.get(0).url);
        assertEquals("https://cdn.example/root/child.m3u8?token=b", variants.get(1).url);
    }

    @Test public void ordersVariantsFromHighestBandwidthToLowestAndResolvesRelativeUrls() throws Exception {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\nlow/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720\n../hd/index.m3u8\n";
        ArrayList<HlsVariantPolicy.Variant> variants =
                HlsVariantPolicy.parse(master, "https://cdn.example/root/master.m3u8");
        assertEquals(2, variants.size());
        assertEquals(1800000L, variants.get(0).bandwidth);
        assertEquals(1280, variants.get(0).width);
        assertEquals("https://cdn.example/hd/index.m3u8", variants.get(0).url);
        assertEquals(400000L, variants.get(1).bandwidth);
    }

    @Test public void capsVariantAttemptsAndIgnoresMissingChildUris() throws Exception {
        StringBuilder master = new StringBuilder("#EXTM3U\n");
        for (int i = 1; i <= 12; i++) {
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(i * 1000).append("\n")
                    .append("v").append(i).append(".m3u8\n");
        }
        master.append("#EXT-X-STREAM-INF:BANDWIDTH=999999\n");
        ArrayList<HlsVariantPolicy.Variant> variants =
                HlsVariantPolicy.parse(master.toString(), "https://cdn.example/master.m3u8");
        assertEquals(8, variants.size());
        assertEquals(12000L, variants.get(0).bandwidth);
        assertEquals(5000L, variants.get(7).bandwidth);
    }

    @Test public void malformedVariantCannotStealFollowingVariantUri() throws Exception {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=900000\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=300000\n"
                + "low/index.m3u8\n";
        ArrayList<HlsVariantPolicy.Variant> variants =
                HlsVariantPolicy.parse(master, "https://cdn.example/root/master.m3u8");
        assertEquals(1, variants.size());
        assertEquals(300000L, variants.get(0).bandwidth);
        assertEquals("https://cdn.example/root/low/index.m3u8", variants.get(0).url);
    }

    @Test public void skipsBlankLinesButDoesNotSkipPlaylistTagsWhenFindingVariantUri() throws Exception {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=600000\n\n"
                + "#EXT-X-VERSION:6\n"
                + "next/index.m3u8\n";
        assertTrue(HlsVariantPolicy.parse(master, "https://cdn.example/master.m3u8").isEmpty());
    }

    @Test public void detectsExtensionlessPlaylistPayloadsButNotEmbeddedHtmlText() {
        assertTrue(HlsVariantPolicy.isPlaylist(String.valueOf((char)0xFEFF) + "  #EXTM3U\\n#EXTINF:6,\\nseg.ts"));
        assertFalse(HlsVariantPolicy.isPlaylist("<html><body>#EXTM3U</body></html>"));
        assertFalse(HlsVariantPolicy.isPlaylist("#EXTINF:6,\\nseg.ts"));
        assertFalse(HlsVariantPolicy.isPlaylist(null));
    }

    @Test public void returnsEmptyForDirectMediaPlaylist() throws Exception {
        assertTrue(HlsVariantPolicy.parse(
                "#EXTM3U\n#EXTINF:6.0,\nseg.ts\n",
                "https://cdn.example/live.m3u8").isEmpty());
    }

    @Test public void byteMarkerMatchesExistingTextClassifierWithoutDecodingMedia(){
        String bom=String.valueOf((char)0xFEFF);
        String[] fixtures={"", "#EXTM3U", " \t\r\n#EXTM3U\nseg.ts",
                bom+" #EXTM3U", "#EXT"+bom+"M3U", "#EXTM3", "<html>#EXTM3U</html>",
                "prefix#EXTM3U", "#EXTINF:5", "  "+bom+"#EXTM3U"};
        for(String fixture:fixtures)
            assertEquals(fixture,HlsVariantPolicy.isPlaylist(fixture),
                    HlsVariantPolicy.hasPlaylistMarker(fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.util.Random random=new java.util.Random(73L);
        byte[] binary=new byte[32768];
        for(int i=0;i<100;i++){
            random.nextBytes(binary);
            assertEquals(HlsVariantPolicy.isPlaylist(new String(binary,java.nio.charset.StandardCharsets.UTF_8)),
                    HlsVariantPolicy.hasPlaylistMarker(binary));
        }
        assertFalse(HlsVariantPolicy.hasPlaylistMarker(null));
    }

    @Test public void decodingRemovesLeadingBomButPreservesResourceText(){
        String bom=String.valueOf((char)0xFEFF);
        String body="#EXTM3U\n#EXTINF:5,\nsegment.ts?token="+bom+"abc\n";
        assertEquals(body,HlsVariantPolicy.decodePlaylist(
                (bom+body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}