package com.wodedianshi.tv;

import com.wodedianshi.tv.db.RoomCatalogueWriter;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import static org.junit.Assert.*;

public class ChannelDirectorySnapshotCodecTest {
    private static final class TrackingOutputStream extends java.io.ByteArrayOutputStream {
        boolean closed;
        @Override public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }
    @Test public void roundTripPreservesChannelsBackupsScopesAndFavorites() throws Exception {
        RoomCatalogueWriter.ChannelSnapshot row = new RoomCatalogueWriter.ChannelSnapshot();
        row.name="CCTV-1"; row.url="https://one.example/live.m3u8"; row.group="央视";
        row.tvgId="cctv1"; row.tvgName="CCTV 1"; row.activeSource=1;
        row.sources.add(row.url); row.sources.add("https://backup.example/live.m3u8");
        row.sourceScopes.add("china");
        HashSet<String> backupScopes=new HashSet<>(); backupScopes.add("china");
        row.backupSourceScopes.put(row.sources.get(1),backupScopes);
        ArrayList<RoomCatalogueWriter.ChannelSnapshot> rows=new ArrayList<>(); rows.add(row);
        HashSet<String> favorites=new HashSet<>(); favorites.add("CCTV-1|"+row.url);
        ChannelDirectorySnapshotCodec.Snapshot decoded=
                ChannelDirectorySnapshotCodec.decode(ChannelDirectorySnapshotCodec.encode(rows,favorites));
        assertEquals(1,decoded.channels.size());
        assertEquals("央视",decoded.channels.get(0).group);
        assertEquals(2,decoded.channels.get(0).sources.size());
        assertEquals(1,decoded.channels.get(0).activeSource);
        assertTrue(decoded.channels.get(0).backupSourceScopes.containsKey(row.sources.get(1)));
        assertEquals(favorites,decoded.favorites);
    }

    @Test public void streamingEncoderMatchesByteArrayEncoder() throws Exception {
        RoomCatalogueWriter.ChannelSnapshot row = new RoomCatalogueWriter.ChannelSnapshot();
        row.name="CCTV-1"; row.url="https://one.example/live.m3u8"; row.group="央视";
        row.sources.add(row.url);
        ArrayList<RoomCatalogueWriter.ChannelSnapshot> rows=new ArrayList<>(); rows.add(row);
        HashSet<String> favorites=new HashSet<>(); favorites.add("CCTV-1");
        java.io.ByteArrayOutputStream streamed=new java.io.ByteArrayOutputStream();
        ChannelDirectorySnapshotCodec.writeCompressed(streamed,rows,favorites);
        assertArrayEquals(ChannelDirectorySnapshotCodec.encode(rows,favorites),streamed.toByteArray());
        assertEquals(favorites,ChannelDirectorySnapshotCodec.decode(streamed.toByteArray()).favorites);
    }

    @Test public void streamingEncoderLeavesCallerOwnedOutputStreamOpen() throws Exception {
        RoomCatalogueWriter.ChannelSnapshot row = new RoomCatalogueWriter.ChannelSnapshot();
        row.name="CCTV-1"; row.url="https://one.example/live.m3u8"; row.group="央视";
        row.sources.add(row.url);
        ArrayList<RoomCatalogueWriter.ChannelSnapshot> rows=new ArrayList<>(); rows.add(row);
        TrackingOutputStream streamed=new TrackingOutputStream();
        ChannelDirectorySnapshotCodec.writeCompressed(streamed,rows,new HashSet<>());
        assertFalse("caller must be able to flush and sync its file descriptor", streamed.closed);
        streamed.write(1);
    }

    @Test public void rejectsInvalidActiveSourceIndex() throws Exception {
        RoomCatalogueWriter.ChannelSnapshot row = new RoomCatalogueWriter.ChannelSnapshot();
        row.name="CCTV-1"; row.url="https://one.example/live.m3u8"; row.activeSource=2;
        row.sources.add(row.url);
        ArrayList<RoomCatalogueWriter.ChannelSnapshot> rows=new ArrayList<>(); rows.add(row);
        byte[] encoded=ChannelDirectorySnapshotCodec.encode(rows,new HashSet<>());
        try { ChannelDirectorySnapshotCodec.decode(encoded); fail("invalid active source should be rejected"); }
        catch (java.io.IOException expected) {}
    }

    @Test public void rejectsEmptyAndCorruptSnapshots() throws Exception {
        try { ChannelDirectorySnapshotCodec.encode(new ArrayList<>(),new HashSet<>()); fail(); }
        catch (java.io.IOException expected) {}
        try { ChannelDirectorySnapshotCodec.decode(new byte[]{1,2,3}); fail(); }
        catch (java.io.IOException expected) {}
    }
}
