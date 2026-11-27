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
    @Test public void sequentialPageRejectsCorruptionAfterValidPrefixWithoutPartialEmission() throws Exception {
        File feed=playlist("#EXTM3U\n#EXTINF:-1,甲\nhttps://a.example/live\n"
                +"#EXTINF:-1,乙\nhttps://b.example/live\n"
                +"#EXTINF:-1,丙\nhttps://c.example/live\n"
                +"#EXTINF:-1,丁\nhttps://d.example/live\n");
        assertEquals(4,PlaylistFeedIndex.readPage(feed,0,4,ignored->{}));
        File offsets=new File(feed.getAbsolutePath()+".index-offsets");
        try(RandomAccessFile index=new RandomAccessFile(offsets,"rw")){
            index.seek(36L);long fourth=index.readLong();
            index.seek(28L);index.writeLong(fourth);
        }
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        try{
            PlaylistFeedIndex.readExistingPage(feed,0,4,page::add,()->false);
            org.junit.Assert.fail("Expected corrupt page rejection");
        }catch(java.io.IOException expected){}
        assertEquals(0,page.size());
        assertEquals(3,PlaylistFeedIndex.readPage(feed,1,3,page::add));
        assertEquals("乙",page.get(0).name);
        assertEquals("丙",page.get(1).name);
        assertEquals("丁",page.get(2).name);
        page.clear();
        assertEquals(1,PlaylistFeedIndex.readExistingPage(feed,3,48,page::add,()->false));
        assertEquals("丁",page.get(0).name);
    }

    @Test public void bufferedIndexPreservesOffsetsAcrossBufferBoundaries() throws Exception {
        StringBuilder body=new StringBuilder("#EXTM3U\n");
        for(int i=0;i<2000;i++)
            body.append("#EXTINF:-1 tvg-id=\"id").append(i).append("\" group-title=\"地方\",频道")
                .append(i).append("\nhttps://example.invalid/live/").append(i).append(".m3u8\n");
        File feed=playlist(body.toString());
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        assertEquals(48,PlaylistFeedIndex.readPage(feed,0,48,page::add));
        assertEquals(2000,PlaylistFeedIndex.indexedEntryCount(feed));
        for(int start:new int[]{0,47,511,1023,1952}){
            page.clear();
            assertEquals(48,PlaylistFeedIndex.readExistingPage(feed,start,48,page::add,()->false));
            for(int i=0;i<48;i++){
                int expected=start+i;
                assertEquals("频道"+expected,page.get(i).name);
                assertEquals("id"+expected,page.get(i).tvgId);
                assertEquals("地方",page.get(i).group);
                assertEquals("https://example.invalid/live/"+expected+".m3u8",page.get(i).url);
            }
        }
        page.clear();
        assertEquals(0,PlaylistFeedIndex.readPage(feed,2000,1,page::add));
        assertEquals(12L+2000L*8L,new File(feed.getAbsolutePath()+".index-offsets").length());
    }

    @Test public void failedRecordWriteAbortsWithoutPublishingPartialIndex() throws Exception {
        char[] oversized = new char[1024*1024+1];
        java.util.Arrays.fill(oversized,'x');
        StringBuilder body=new StringBuilder("#EXTM3U\n#EXTINF:-1,");
        body.append(oversized).append("\nhttps://bad.example/live\n");
        for(int i=0;i<500;i++)
            body.append("#EXTINF:-1,tail\nhttps://tail.example/live\n");
        File feed=playlist(body.toString());
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        try{
            PlaylistFeedIndex.readPage(feed,0,48,page::add,()->{
                checks.incrementAndGet();return false;
            });
            org.junit.Assert.fail("Expected oversized bounded field failure");
        }catch(java.io.IOException expected){
            org.junit.Assert.assertTrue(expected.getMessage().contains("field exceeds size limit"));
        }
        assertEquals(0,page.size());
        org.junit.Assert.assertTrue("Must abort before parsing hundreds of tail entries",checks.get()<30);
        org.junit.Assert.assertFalse(new File(feed.getAbsolutePath()+".index-data").exists());
        org.junit.Assert.assertFalse(new File(feed.getAbsolutePath()+".index-offsets").exists());
        org.junit.Assert.assertFalse(new File(feed.getAbsolutePath()+".index-data.tmp").exists());
        org.junit.Assert.assertFalse(new File(feed.getAbsolutePath()+".index-offsets.tmp").exists());
        try(FileOutputStream out=new FileOutputStream(feed)){
            out.write("#EXTM3U\n#EXTINF:-1,正常\nhttps://good.example/live\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(1,PlaylistFeedIndex.readPage(feed,0,48,page::add));
        assertEquals("正常",page.get(0).name);
    }

    @Test public void extendedFieldsKeepOriginalRowOrderAndContents() throws Exception {
        char[] name=new char[65536];
        java.util.Arrays.fill(name,'中');
        String large=new String(name);
        File feed=playlist("#EXTM3U\n#EXTINF:-1,"+large+"\nhttps://a.example/live\n"
                +"#EXTINF:-1,乙\nhttps://b.example/live\n");
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        assertEquals(1,PlaylistFeedIndex.readPage(feed,0,1,page::add));
        assertEquals(large,page.get(0).name);
        assertEquals(2,PlaylistFeedIndex.indexedEntryCount(feed));
        page.clear();
        assertEquals(1,PlaylistFeedIndex.readPage(feed,1,1,page::add));
        assertEquals("乙",page.get(0).name);
    }

    @Test public void oldIndexFormatRemainsReadableWithoutRebuild() throws Exception {
        File feed=playlist("#EXTM3U\n#EXTINF:-1,甲\nhttps://a.example/live\n");
        File records=new File(feed.getAbsolutePath()+".index-data");
        File offsets=new File(feed.getAbsolutePath()+".index-offsets");
        try(RandomAccessFile data=new RandomAccessFile(records,"rw");
                RandomAccessFile index=new RandomAccessFile(offsets,"rw")){
            data.writeUTF("旧索引");data.writeUTF("https://a.example/live");
            data.writeUTF("");data.writeUTF("");data.writeUTF("");
            index.writeInt(0x4D545649);index.writeLong(1L);index.writeLong(0L);
        }
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        assertEquals(1,PlaylistFeedIndex.indexedEntryCount(feed));
        assertEquals(1,PlaylistFeedIndex.readExistingPage(feed,0,1,page::add,()->false));
        assertEquals("旧索引",page.get(0).name);
    }

    @Test public void damagedExtendedLengthRebuildsBeforeEmittingAnyRow() throws Exception {
        File feed=playlist("#EXTM3U\n#EXTINF:-1,甲\nhttps://a.example/live\n");
        assertEquals(1,PlaylistFeedIndex.readPage(feed,0,1,ignored->{}));
        try(RandomAccessFile data=new RandomAccessFile(new File(feed.getAbsolutePath()+".index-data"),"rw")){
            data.writeInt(Integer.MAX_VALUE);
        }
        List<PlaylistParser.ChannelSource> page=new ArrayList<>();
        assertEquals(1,PlaylistFeedIndex.readPage(feed,0,1,page::add));
        assertEquals("甲",page.get(0).name);
    }

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

    @Test public void initialCachePruningPrefersRetainingCandidatesButKeepsBound() throws Exception {
        File dir=java.nio.file.Files.createTempDirectory("mytv-cache-test").toFile();
        try {
            File first=new File(dir,"initial-old.m3u");
            File current=new File(dir,"initial-current.m3u");
            File routine=new File(dir,"routine.m3u");
            for(File body:new File[]{first,current,routine})
                try(FileOutputStream out=new FileOutputStream(body)){out.write(new byte[10]);}
            File index=new File(first.getPath()+".index-data");
            try(FileOutputStream out=new FileOutputStream(index)){out.write(new byte[10]);}
            java.util.Set<String> preferred=new java.util.HashSet<>(java.util.Arrays.asList(first.getName(),current.getName()));
            PlaylistFeedCache.pruneOldEntries(dir,current,preferred,30L);
            org.junit.Assert.assertFalse(routine.exists());
            org.junit.Assert.assertTrue(first.exists());
            org.junit.Assert.assertTrue(index.exists());
            org.junit.Assert.assertTrue(current.exists());
            PlaylistFeedCache.pruneOldEntries(dir,current,preferred,10L);
            org.junit.Assert.assertFalse(first.exists());
            org.junit.Assert.assertFalse(index.exists());
            org.junit.Assert.assertTrue(current.exists());
        } finally {
            File[] children=dir.listFiles();
            if(children!=null)for(File child:children)child.delete();
            dir.delete();
        }
    }
}
