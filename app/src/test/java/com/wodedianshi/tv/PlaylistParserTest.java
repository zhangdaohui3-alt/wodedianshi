package com.wodedianshi.tv;

import org.junit.Test;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PlaylistParserTest {
    @Test public void displayNameCannotSupplyFakeAttributesOrOverrideExtGroup() {
        String m3u = "#EXTM3U\n#EXTGRP:福建地方台\n"
                + "#EXTINF:-1,福州新闻 tvg-id=Fake tvg-name=Other group-title=国际\nhttps://a.example/live\n"
                + "#EXTBURL:https://backup.example/live\n"
                + "#EXTINF:-1 tvg-name=\"CCTV, News\" tvg-id=real group-title=央视,\nhttps://b.example/live";
        List<PlaylistParser.ChannelSource> items=PlaylistParser.parse(m3u,10);
        assertEquals(3,items.size());
        assertEquals("福州新闻 tvg-id=Fake tvg-name=Other group-title=国际",items.get(0).name);
        assertEquals("福建地方台",items.get(0).group);
        assertEquals("",items.get(0).tvgId);
        assertEquals("",items.get(0).tvgName);
        assertEquals("福建地方台",items.get(1).group);
        assertEquals("",items.get(1).tvgId);
        assertEquals("CCTV, News",items.get(2).name);
        assertEquals("real",items.get(2).tvgId);
        assertEquals("央视",items.get(2).group);
    }

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

    @Test public void streamingParserSupportsCrOnlyAndCountsWithoutIntermediateList() {
        String m3u = "#EXTM3U\r#EXTINF:-1,福建综合\rhttps://a.example/live\r"
                + "#EXTINF:-1,湖南卫视\rhttps://b.example/live";
        final int[] count = {0};
        int parsed = PlaylistParser.parseEach(m3u, 10, entry -> {
            count[0]++;
            assertTrue(entry.url.startsWith("https://"));
        });
        assertEquals(2, parsed);
        assertEquals(2, count[0]);
    }

    @Test public void readerParserHandlesBomAndCrOnlyLines() throws IOException {
        String m3u = "\uFEFF#EXTM3U\r"
                + "#EXTINF:-1 tvg-id=\"CCTV1\" group-title=\"央视\",中央电视台综合频道\r"
                + "https://example.com/cctv1.m3u8\r";
        ArrayList<PlaylistParser.ChannelSource> items = new ArrayList<>();
        int parsed;
        try (StringReader reader = new StringReader(m3u)) {
            parsed = PlaylistParser.parseEach(reader, 10, items::add);
        }
        assertEquals(1, parsed);
        assertEquals(1, items.size());
        assertEquals("CCTV1", items.get(0).tvgId);
        assertEquals("央视", items.get(0).group);
        assertEquals("中央电视台综合频道", items.get(0).name);
    }

    @Test public void readerParserCanRotatePastTheFirstPageWithoutMaterializingPlaylist() throws IOException {
        String m3u = "#EXTINF:-1,A\nhttps://a.example/live\n"
                + "#EXTINF:-1,B\nhttps://b.example/live\n"
                + "#EXTINF:-1,C\nhttps://c.example/live\n"
                + "#EXTINF:-1,D\nhttps://d.example/live";
        ArrayList<PlaylistParser.ChannelSource> page = new ArrayList<>();
        try (StringReader reader = new StringReader(m3u)) {
            assertEquals(2, PlaylistParser.parseEach(reader, 2, 2, page::add));
        }
        assertEquals(2, page.size());
        assertEquals("C", page.get(0).name);
        assertEquals("D", page.get(1).name);
    }

    @Test public void rotatedPageReportsShortFinalPage() throws IOException {
        String m3u = "#EXTINF:-1,A\nhttps://a.example/live\n"
                + "#EXTINF:-1,B\nhttps://b.example/live\n"
                + "#EXTINF:-1,C\nhttps://c.example/live";
        ArrayList<PlaylistParser.ChannelSource> page = new ArrayList<>();
        try (StringReader reader = new StringReader(m3u)) {
            assertEquals(1, PlaylistParser.parseEach(reader, 2, 2, page::add));
        }
        assertEquals("C", page.get(0).name);
    }

    @Test public void readerParserStopsAtTheRequestedEntryLimit() throws IOException {
        String m3u = "#EXTINF:-1,A\nhttps://a.example/live\n"
                + "#EXTINF:-1,B\nhttps://b.example/live\n"
                + "#EXTINF:-1,C\nhttps://c.example/live";
        ArrayList<PlaylistParser.ChannelSource> items = new ArrayList<>();
        try (StringReader reader = new StringReader(m3u)) {
            assertEquals(2, PlaylistParser.parseEach(reader, 2, items::add));
        }
        assertEquals(2, items.size());
        assertEquals("B", items.get(1).name);
    }

    @Test public void readerParserKeepsEntriesBeforeTransientReadFailure() throws IOException {
        BufferedReader failingReader = new BufferedReader(new StringReader("")) {
            private int line;
            @Override public String readLine() throws IOException {
                if (line++ == 0) return "#EXTINF:-1,已解析频道";
                if (line == 2) return "https://a.example/live";
                throw new IOException("simulated network interruption");
            }
        };
        ArrayList<PlaylistParser.ChannelSource> items = new ArrayList<>();
        try {
            PlaylistParser.parseEach(failingReader, 10, items::add);
            fail("Expected the simulated read failure");
        } catch (IOException expected) {
            assertEquals("simulated network interruption", expected.getMessage());
        }
        assertEquals(1, items.size());
        assertEquals("已解析频道", items.get(0).name);
    }

    @Test public void parsesExtBurlAlternatesAsAdditionalSourcesForSameChannel() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV One\" group-title=\"央视\",中央电视台综合频道\n"
                + "https://primary.example/live.m3u8\n"
                + "#EXTBURL:https://backup.example/live.m3u8\n"
                + "#EXTBURL:https://backup2.example/live.m3u8\n"
                + "#EXTINF:-1,湖南卫视\nhttps://hunan.example/live.m3u8";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(4, items.size());
        assertEquals("https://primary.example/live.m3u8", items.get(0).url);
        assertEquals("https://backup.example/live.m3u8", items.get(1).url);
        assertEquals("https://backup2.example/live.m3u8", items.get(2).url);
        assertEquals("中央电视台综合频道", items.get(1).name);
        assertEquals("央视", items.get(1).group);
        assertEquals("CCTV1", items.get(1).tvgId);
        assertEquals("CCTV One", items.get(1).tvgName);
        assertEquals("湖南卫视", items.get(3).name);
    }

    @Test public void pagedParserDoesNotSkipExtBurlWhenPageEndsAtPrimaryUrl() throws IOException {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1,频道A\nhttps://primary.example/live.m3u8\n"
                + "#EXTBURL:https://backup.example/live.m3u8\n"
                + "#EXTINF:-1,频道B\nhttps://next.example/live.m3u8";
        ArrayList<PlaylistParser.ChannelSource> first = new ArrayList<>();
        ArrayList<PlaylistParser.ChannelSource> second = new ArrayList<>();
        try (StringReader reader = new StringReader(m3u)) {
            assertEquals(1, PlaylistParser.parseEach(reader, 0, 1, first::add));
        }
        try (StringReader reader = new StringReader(m3u)) {
            assertEquals(1, PlaylistParser.parseEach(reader, 1, 1, second::add));
        }
        assertEquals("https://primary.example/live.m3u8", first.get(0).url);
        assertEquals("https://backup.example/live.m3u8", second.get(0).url);
        assertEquals("频道A", second.get(0).name);
    }

    @Test public void ignoresOrphanAndNonHttpExtBurlLines() {
        String m3u = "#EXTBURL:https://orphan.example/live.m3u8\n"
                + "#EXTINF:-1,频道A\nhttps://primary.example/live.m3u8\n"
                + "#EXTBURL:rtmp://backup.example/live\n"
                + "#EXTBURL:https://backup.example/live.m3u8";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(2, items.size());
        assertEquals("https://backup.example/live.m3u8", items.get(1).url);
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
        String m3u = "#EXTINF:-1 tvg-id=\"FJTV.FUZHOU\" tvg-name=\"Fuzhou City News\" group-title=\"地方频道\",福建综合频道\n"
                + "https://cdn.example/live.m3u8";
        List<PlaylistParser.ChannelSource> items = PlaylistParser.parse(m3u, 10);
        assertEquals(1, items.size());
        assertEquals("FJTV.FUZHOU", items.get(0).tvgId);
        assertEquals("Fuzhou City News", items.get(0).tvgName);
        assertEquals("福建综合频道", items.get(0).name);
        assertEquals("地方频道", items.get(0).group);
    }

    @Test public void resetsTvgMetadataBetweenEntries() {
        String m3u = "#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV One\",央视一套\n"
                + "https://a.example/live\n"
                + "#EXTINF:-1,普通频道\nhttps://b.example/live";
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
