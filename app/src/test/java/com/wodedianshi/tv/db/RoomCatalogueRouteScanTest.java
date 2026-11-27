package com.wodedianshi.tv.db;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RoomCatalogueRouteScanTest {
    private ChannelWithSources row(String legacy,String primary,BackupSourceEntity... sources){
        ChannelWithSources row=new ChannelWithSources();
        row.channel=new ChannelEntity("id",legacy,"Station",primary,"g","c","","","[]",0,0);
        row.sources=Arrays.asList(sources);
        return row;
    }
    private ChannelDao.RouteMembership projection(ChannelWithSources row){
        ChannelDao.RouteMembership projected=new ChannelDao.RouteMembership();
        projected.id=row.channel.id;
        projected.name=row.channel.name;
        projected.legacyKey=row.channel.legacyKey;
        projected.primaryUrl=row.channel.primaryUrl;
        projected.urls=new ArrayList<>();
        for(BackupSourceEntity source:row.sources)projected.urls.add(source.url);
        return projected;
    }
    @Test public void addressMembershipMatchesFullRecordWithoutSortingOrScopeParsing(){
        ChannelWithSources row=row("Station|https://example.invalid/original","https://example.invalid/first",
            new BackupSourceEntity("id","https://example.invalid/second",1,"[]"),
            new BackupSourceEntity("id","https://example.invalid/first",0,"[]"),
            new BackupSourceEntity("id","https://example.invalid/original",2,"[]"));
        RoomCatalogueReader.ChannelRecord full=new RoomCatalogueReader.ChannelRecord(row.channel,row.getSourcesInPriorityOrder());
        Set<String> expected=new HashSet<>(full.sources);expected.add(full.url);
        List<String> actual=new ArrayList<>();
        assertFalse(RoomCatalogueReader.visitRowRoutes(projection(row),url->{actual.add(url);return false;}));
        assertEquals(expected,new HashSet<>(actual));
        assertEquals(expected.size(),actual.size());
    }
    @Test public void viewingPauseStopsInsideChannelBeforeVisitingBackupRoutes(){
        ChannelWithSources row=row("Station|https://example.invalid/original","https://example.invalid/first",
                new BackupSourceEntity("id","https://example.invalid/first",0,"[]"),
                new BackupSourceEntity("id","https://example.invalid/second",1,"[]"));
        List<String> actual=new ArrayList<>();
        try{
            RoomCatalogueReader.visitRowRoutes(projection(row),url->{actual.add(url);return false;},
                    ()->actual.size()==2);
            fail("Expected playback pause before the second backup");
        }catch(com.wodedianshi.tv.SourceProbeTimePolicy.PublicationPausedException expected){
            assertEquals(Arrays.asList("https://example.invalid/original","https://example.invalid/first"),actual);
        }
    }
    @Test public void viewingPauseStopsBeforeFallbackPrimary(){
        ChannelWithSources row=row("Station|https://example.invalid/original","https://example.invalid/first");
        List<String> actual=new ArrayList<>();
        try{
            RoomCatalogueReader.visitRowRoutes(projection(row),url->{actual.add(url);return false;},
                    ()->!actual.isEmpty());
            fail("Expected playback pause before the fallback primary");
        }catch(com.wodedianshi.tv.SourceProbeTimePolicy.PublicationPausedException expected){
            assertEquals(Collections.singletonList("https://example.invalid/original"),actual);
        }
    }
    @Test public void matchStopsBeforeOtherRoutesAndFallbackMatchesOriginalSemantics(){
        ChannelWithSources row=row("Station|https://example.invalid/original","https://example.invalid/partition");
        List<String> actual=new ArrayList<>();
        assertTrue(RoomCatalogueReader.visitRowRoutes(projection(row),url->{actual.add(url);return true;}));
        assertEquals(Collections.singletonList("https://example.invalid/original"),actual);
        actual.clear();
        assertFalse(RoomCatalogueReader.visitRowRoutes(projection(row),url->{actual.add(url);return false;}));
        assertEquals(Arrays.asList("https://example.invalid/original","https://example.invalid/partition"),actual);
        actual.clear();
        assertFalse(RoomCatalogueReader.visitRowRoutes(projection(row("different","https://example.invalid/partition")),
                url->{actual.add(url);return false;}));
        assertEquals(Collections.singletonList("https://example.invalid/partition"),actual);
    }
}
