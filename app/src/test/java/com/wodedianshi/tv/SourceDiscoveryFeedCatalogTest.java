package com.wodedianshi.tv;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceDiscoveryFeedCatalogTest {
    @Test public void localCountyCatalogIsBeforeGlobalAndCrossCountryIndexes() {
        String[] feeds=SourceDiscoveryFeedCatalog.feeds();
        int county=indexOf(feeds,"cs3306/IPTV-Sources");
        int cn=indexOf(feeds,"/streams/cn.m3u");
        int worldwide=indexOf(feeds,"/iptv/index.m3u");
        assertTrue(county>=0);
        assertTrue(cn>county);
        assertTrue(worldwide>cn);
        assertTrue(county<6);
    }

    @Test public void directoryCatalogIsStableUniqueAndCallerCannotMutateIt() {
        String[] first=SourceDiscoveryFeedCatalog.feeds();
        assertTrue(first.length>=20);
        assertEquals(first.length,new HashSet<>(Arrays.asList(first)).size());
        String expected=first[0];
        first[0]="malicious-change";
        assertEquals(expected,SourceDiscoveryFeedCatalog.feeds()[0]);
    }

    private static int indexOf(String[] feeds,String match) {
        for(int i=0;i<feeds.length;i++)if(feeds[i].contains(match))return i;
        return -1;
    }
}
