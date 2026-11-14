package com.wodedianshi.tv;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlaylistParserTest {
    @Test public void handlesBomCrLfAndQuotedCommaInMetadata() {
        String m3u = "\uFEFF#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-name=\"CCTV, News\" group-title=\"央视\",中央电视台新闻频道\r\n"
                + "https://example.com/live.m3u8\r\n";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(1, items.size());
        assertEquals("中央电视台新闻频道", items.get(0).name);
        assertEquals("央视", items.get(0).group);
        assertEquals("https://example.com/live.m3u8", items.get(0).url);
    }

    @Test public void usesTvgNameWhenDisplayNameIsMissing() {
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(
                "#EXTINF:-1 tvg-name=\"湖南卫视\",\nhttps://example.com/hunan", 10);
        assertEquals("湖南卫视", items.get(0).name);
    }

    @Test public void usesExtGroupBeforeExtinfUnlessGroupTitleOverridesIt() {
        String m3u = "#EXTGRP:地方频道\n#EXTINF:-1,福建综合\nhttps://a.example/live\n"
                + "#EXTGRP:临时分组\n#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttps://b.example/live";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals("地方频道", items.get(0).group);
        assertEquals("卫视", items.get(1).group);
    }

    @Test public void appliesExtGroupAfterExtinfToCurrentEntry() {
        String m3u = "#EXTINF:-1,福建综合\n#EXTGRP:福建地方台\nhttps://a.example/live";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(1, items.size());
        assertEquals("福建地方台", items.get(0).group);
    }

    @Test public void groupTitleWinsOverExtGroupAfterExtinf() {
        String m3u = "#EXTINF:-1 group-title=\"省级卫视\",湖南卫视\n"
                + "#EXTGRP:错误分组\nhttps://a.example/live";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals("省级卫视", items.get(0).group);
    }

    @Test public void ignoresUrlsWithoutPendingExtinfAndNonHttpSchemes() {
        String m3u = "https://orphan.example/live\n#EXTINF:-1,坏地址\nfile:///bad\n"
                + "#EXTINF:-1,有效频道\nhttps://ok.example/live";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(1, items.size());
        assertEquals("有效频道", items.get(0).name);
    }

    @Test public void malformedMetadataStillProducesNamedFallback() {
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(
                "#EXTINF:-1\nhttps://example.com/live", 10);
        assertEquals("未命名频道", items.get(0).name);
    }

    @Test public void preservesTvgIdAndTvgNameForRegionalClassification() {
        String m3u = "#EXTINF:-1 tvg-id=\"FJTV.FUZHOU\" tvg-name=\"Fuzhou City News\" group-title=\"地方频道\",福建综合频道\\n"
                + "https://cdn.example/live.m3u8";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(1, items.size());
        assertEquals("FJTV.FUZHOU", items.get(0).tvgId);
        assertEquals("Fuzhou City News", items.get(0).tvgName);
        assertEquals("福建综合频道", items.get(0).name);
        assertEquals("地方频道", items.get(0).group);
    }

    @Test public void resetsTvgMetadataBetweenEntries() {
        String m3u = "#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV One\",央视一套\\n"
                + "https://a.example/live\\n"
                + "#EXTINF:-1,普通频道\\nhttps://b.example/live";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(2, items.size());
        assertEquals("CCTV1", items.get(0).tvgId);
        assertEquals("", items.get(1).tvgId);
        assertEquals("", items.get(1).tvgName);
    }

    @Test public void respectsPerFeedLimitAndInvalidInputs() {
        String m3u = "#EXTINF:-1,A\nhttps://a.example\n#EXTINF:-1,B\nhttps://b.example";
        assertEquals(1, PlaylistParser.parse(m3u, 1).size());
        assertTrue(PlaylistParser.parse(m3u, 0).isEmpty());
        assertTrue(PlaylistParser.parse(null, 10).isEmpty());
    }
}
