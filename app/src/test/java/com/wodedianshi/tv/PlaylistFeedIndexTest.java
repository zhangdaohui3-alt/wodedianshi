package com.wodedianshi.tv;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PlaylistFeedIndexTest {
    private File playlist(String value) throws Exception {
        File file = File.createTempFile("mytv-feed-index-", ".m3u");
        file.deleteOnExit();
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test public void pagesSeekDirectlyAcrossPrimaryAndBackupEntries() throws Exception {
        File feed = playlist("#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1\" group-title=\"央视\",中央一套\n"
                + "https://primary.example/live.m3u8\n"
                + "#EXTBURL:https://backup.example/live.m3u8\n"
                + "#EXTINF:-1,湖南卫视\nhttps://hunan.example/live.m3u8\n");

        List<PlaylistParser.ChannelSource> first = new ArrayList<>();
        List<PlaylistParser.ChannelSource> second = new ArrayList<>();
        List<PlaylistParser.ChannelSource> third = new ArrayList<>();
        assertEquals(1, PlaylistFeedIndex.readPage(feed, 0, 1, first::add));
        assertEquals(1, PlaylistFeedIndex.readPage(feed, 1, 1, second::add));
        assertEquals(1, PlaylistFeedIndex.readPage(feed, 2, 1, third::add));
        assertEquals(0, PlaylistFeedIndex.readPage(feed, 3, 1, ignored -> { }));

        assertEquals("https://primary.example/live.m3u8", first.get(0).url);
        assertEquals("https://backup.example/live.m3u8", second.get(0).url);
        assertEquals("中央一套", second.get(0).name);
        assertEquals("央视", second.get(0).group);
        assertEquals("CCTV1", second.get(0).tvgId);
        assertEquals("湖南卫视", third.get(0).name);
    }

    @Test public void corruptOffsetAndRecordSidecarsAreRebuiltWithoutPartialEmission() throws Exception {
        File feed = playlist("#EXTM3U\n"
                + "#EXTINF:-1,频道甲\nhttps://a.example/live.m3u8\n"
                + "#EXTINF:-1,频道乙\nhttps://b.example/live.m3u8\n");
        List<PlaylistParser.ChannelSource> initial = new ArrayList<>();
        assertEquals(2, PlaylistFeedIndex.readPage(feed, 0, 2, initial::add));

        File offsets = new File(feed.getAbsolutePath() + ".index-offsets");
        try (RandomAccessFile index = new RandomAccessFile(offsets, "rw")) {
            index.seek(0L);
            index.writeLong(Long.MAX_VALUE);
        }
        List<PlaylistParser.ChannelSource> afterBadOffset = new ArrayList<>();
        assertEquals(1, PlaylistFeedIndex.readPage(feed, 0, 1, afterBadOffset::add));
        assertEquals("频道甲", afterBadOffset.get(0).name);

        File records = new File(feed.getAbsolutePath() + ".index-data");
        try (RandomAccessFile index = new RandomAccessFile(records, "rw")) {
            index.setLength(1L);
        }
        List<PlaylistParser.ChannelSource> afterTruncation = new ArrayList<>();
        assertEquals(2, PlaylistFeedIndex.readPage(feed, 0, 2, afterTruncation::add));
        assertEquals(2, afterTruncation.size());
        assertEquals("频道甲", afterTruncation.get(0).name);
        assertEquals("频道乙", afterTruncation.get(1).name);
    }


    @Test public void alignedOffsetTruncationCannotMasqueradeAsEndOfFeed() throws Exception {
        File feed = playlist("#EXTM3U\n"
                + "#EXTINF:-1,频道甲\nhttps://a.example/live.m3u8\n"
                + "#EXTINF:-1,频道乙\nhttps://b.example/live.m3u8\n");
        List<PlaylistParser.ChannelSource> initial = new ArrayList<>();
        assertEquals(2, PlaylistFeedIndex.readPage(feed, 0, 2, initial::add));

        File offsets = new File(feed.getAbsolutePath() + ".index-offsets");
        // Header stores the true count (2). Keep only one complete offset entry:
        // old indexes could mistake this aligned truncation for a legitimate EOF.
        try (RandomAccessFile index = new RandomAccessFile(offsets, "rw")) {
            index.setLength(20L);
        }
        List<PlaylistParser.ChannelSource> recovered = new ArrayList<>();
        assertEquals(2, PlaylistFeedIndex.readPage(feed, 0, 2, recovered::add));
        assertEquals("频道甲", recovered.get(0).name);
        assertEquals("频道乙", recovered.get(1).name);
    }

    @Test public void repeatedOffsetAcrossPageBoundaryRebuildsInsteadOfRepeatingChannel() throws Exception {
        File feed=playlist("#EXTM3U\n#EXTINF:-1,甲\nhttps://a.example/live\n"
                +"#EXTINF:-1,乙\nhttps://b.example/live\n");
        assertEquals(2,PlaylistFeedIndex.readPage(feed,0,2,ignored->{}));
        File offsets=new File(feed.getAbsolutePath()+".index-offsets");
        try(RandomAccessFile index=new RandomAccessFile(offsets,"rw")){
            index.seek(20L);index.writeLong(0L);
        }
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        assertEquals(1,PlaylistFeedIndex.readPage(feed,1,1,page::add));
        assertEquals("乙",page.get(0).name);
    }

    @Test public void offsetThatSkipsARecordRebuildsBeforeEmittingPage() throws Exception {
        File feed=playlist("#EXTM3U\n#EXTINF:-1,甲\nhttps://a.example/live\n"
                +"#EXTINF:-1,乙\nhttps://b.example/live\n"
                +"#EXTINF:-1,丙\nhttps://c.example/live\n");
        assertEquals(3,PlaylistFeedIndex.readPage(feed,0,3,ignored->{}));
        File offsets=new File(feed.getAbsolutePath()+".index-offsets");
        try(RandomAccessFile index=new RandomAccessFile(offsets,"rw")){
            index.seek(28L);long third=index.readLong();
            index.seek(20L);index.writeLong(third);
        }
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        assertEquals(1,PlaylistFeedIndex.readPage(feed,0,1,page::add));
        assertEquals("甲",page.get(0).name);
        page.clear();
        assertEquals(1,PlaylistFeedIndex.readPage(feed,1,1,page::add));
        assertEquals("乙",page.get(0).name);
    }

    @Test public void cancelledIndexReadKeepsSidecarsAndEmitsNothing() throws Exception {
        File feed=playlist("#EXTM3U\n#EXTINF:-1,甲\nhttps://a.example/live\n"
                +"#EXTINF:-1,乙\nhttps://b.example/live\n");
        assertEquals(2,PlaylistFeedIndex.readPage(feed,0,2,ignored->{}));
        File records=new File(feed.getAbsolutePath()+".index-data");
        File offsets=new File(feed.getAbsolutePath()+".index-offsets");
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        try{
            PlaylistFeedIndex.readPage(feed,0,2,page::add,()->checks.incrementAndGet()>=2);
            org.junit.Assert.fail("expected pause");
        }catch(SourceProbeTimePolicy.PausedException expected){}
        assertEquals(0,page.size());
        org.junit.Assert.assertTrue(records.isFile()&&offsets.isFile());
        assertEquals(2,PlaylistFeedIndex.readPage(feed,0,2,page::add));
    }

    @Test public void emptyAndExhaustedPagesReturnZeroWithoutSkippingEntries() throws Exception {
        File feed = playlist("#EXTM3U\n"
                + "#EXTINF:-1,频道甲\nhttps://a.example/live.m3u8\n"
                + "#EXTINF:-1,频道乙\nhttps://b.example/live.m3u8\n");
        List<PlaylistParser.ChannelSource> page = new ArrayList<>();
        assertEquals(2, PlaylistFeedIndex.readPage(feed, 0, 10, page::add));
        assertEquals(2, page.size());
        assertEquals(0, PlaylistFeedIndex.readPage(feed, 2, 10, ignored -> { }));
    }
}
