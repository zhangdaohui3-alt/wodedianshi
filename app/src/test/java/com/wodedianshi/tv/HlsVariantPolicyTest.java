package com.wodedianshi.tv;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class HlsVariantPolicyTest {
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

    @Test public void detectsExtensionlessPlaylistPayloadsButNotEmbeddedHtmlText() {
        assertTrue(HlsVariantPolicy.isPlaylist(String.valueOf((char)0xFEFF) + "  #EXTM3U\n#EXTINF:6,\nseg.ts"));
        assertFalse(HlsVariantPolicy.isPlaylist("<html><body>#EXTM3U</body></html>"));
        assertFalse(HlsVariantPolicy.isPlaylist("#EXTINF:6,\nseg.ts"));
        assertFalse(HlsVariantPolicy.isPlaylist(null));
    }

    @Test public void returnsEmptyForDirectMediaPlaylist() throws Exception {
        assertTrue(HlsVariantPolicy.parse(
                "#EXTM3U\n#EXTINF:6.0,\nseg.ts\n",
                "https://cdn.example/live.m3u8").isEmpty());
    }
}
