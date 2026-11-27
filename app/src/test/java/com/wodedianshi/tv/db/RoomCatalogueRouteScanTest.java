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
    @Test public void addressMembershipMatchesFullRecordWithoutSortingOrScopeParsing(){
        ChannelWithSources row=row("Station|https://example.invalid/original","https://example.invalid/first",
            new BackupSourceEntity("id","https://example.invalid/second",1,"[]"),
            new BackupSourceEntity("id","https://example.invalid/first",0,"[]"),
            new BackupSourceEntity("id","https://example.invalid/original",2,"[]"));
        RoomCatalogueReader.ChannelRecord full=new RoomCatalogueReader.ChannelRecord(row.channel,row.getSourcesInPriorityOrder());
        Set<String> expected=new HashSet<>(full.sources);expected.add(full.url);
        List<String> actual=new ArrayList<>();
        assertFalse(RoomCatalogueReader.visitRowRoutes(row,url->{actual.add(url);return false;}));
        assertEquals(expected,new HashSet<>(actual));
        assertEquals(expected.size(),actual.size());
    }
    @Test public void matchStopsBeforeOtherRoutesAndFallbackMatchesOriginalSemantics(){
        ChannelWithSources row=row("Station|https://example.invalid/original","https://example.invalid/partition");
        List<String> actual=new ArrayList<>();
        assertTrue(RoomCatalogueReader.visitRowRoutes(row,url->{actual.add(url);return true;}));
        assertEquals(Collections.singletonList("https://example.invalid/original"),actual);
        actual.clear();
        assertFalse(RoomCatalogueReader.visitRowRoutes(row,url->{actual.add(url);return false;}));
        assertEquals(Arrays.asList("https://example.invalid/original","https://example.invalid/partition"),actual);
        actual.clear();
        assertFalse(RoomCatalogueReader.visitRowRoutes(row("different","https://example.invalid/partition"),
                url->{actual.add(url);return false;}));
        assertEquals(Collections.singletonList("https://example.invalid/partition"),actual);
    }
}
