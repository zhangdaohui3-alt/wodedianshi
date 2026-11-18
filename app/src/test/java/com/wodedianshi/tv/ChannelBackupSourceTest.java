package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelBackupSourceTest {
    @Test public void knownUniqueRouteIsAddedAndItsScopeIsTracked() {
        MainActivity.Channel channel = new MainActivity.Channel("CCTV-1", "https://a.example/live.m3u8", "央视");
        channel.addBackupSourceKnownUnique("https://b.example/live.m3u8", "china");
        assertEquals(2, channel.backups.size());
        assertTrue(channel.backupSourceScopes.get("https://b.example/live.m3u8").contains("china"));
        assertTrue(channel.sourceScopes.contains("china"));
    }

    @Test public void ordinaryAdditionStillDeduplicatesRepeatedUrl() {
        MainActivity.Channel channel = new MainActivity.Channel("CCTV-1", "https://a.example/live.m3u8", "央视");
        channel.addBackupSource("https://a.example/live.m3u8", "china");
        assertEquals(1, channel.backups.size());
    }

    @Test public void knownUniqueAdditionDoesNotRemoveExistingBackups() {
        MainActivity.Channel channel = new MainActivity.Channel("CCTV-1", "https://a.example/live.m3u8", "央视");
        channel.addBackupSourceKnownUnique("https://b.example/live.m3u8", "china");
        channel.addBackupSourceKnownUnique("https://c.example/live.m3u8", "global");
        assertEquals(3, channel.backups.size());
        assertTrue(channel.backupSourceScopes.get("https://c.example/live.m3u8").contains("global"));
    }
}
