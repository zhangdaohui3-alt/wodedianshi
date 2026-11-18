package com.wodedianshi.tv;

import com.wodedianshi.tv.db.RoomCatalogueWriter;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import static org.junit.Assert.*;

public class ChannelDirectorySnapshotCodecTest {
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

    @Test public void rejectsEmptyAndCorruptSnapshots() throws Exception {
        try { ChannelDirectorySnapshotCodec.encode(new ArrayList<>(),new HashSet<>()); fail(); }
        catch (java.io.IOException expected) {}
        try { ChannelDirectorySnapshotCodec.decode(new byte[]{1,2,3}); fail(); }
        catch (java.io.IOException expected) {}
    }
}
