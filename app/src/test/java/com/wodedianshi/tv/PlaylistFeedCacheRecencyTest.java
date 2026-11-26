package com.wodedianshi.tv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Properties;
import static org.junit.Assert.*;

public class PlaylistFeedCacheRecencyTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void indexedReadRefreshesMetadataOnlyWithoutChangingBodyVersion() throws Exception {
        File body=folder.newFile("directory.m3u");
        try(FileOutputStream output=new FileOutputStream(body)){
            output.write("#EXTM3U\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        File metadata=new File(body.getPath()+".properties");
        Properties properties=new Properties();
        properties.setProperty("bodySha256",new String(new char[64]).replace('\0','a'));
        properties.setProperty("bodyLength",Long.toString(body.length()));
        properties.setProperty("bodyModified",Long.toString(body.lastModified()));
        properties.setProperty("cachedAt","1000");
        try(FileOutputStream output=new FileOutputStream(metadata)){properties.store(output,"test");}
        assertTrue(metadata.setLastModified(100000L));
        long originalBodyTime=body.lastModified();
        String version=PlaylistFeedCache.contentVersion(body);
        PlaylistFeedCache.noteIndexedInitialAccess(body,200000L);
        assertEquals(200000L,metadata.lastModified());
        assertEquals(originalBodyTime,body.lastModified());
        assertEquals(version,PlaylistFeedCache.contentVersion(body));
        Properties reread=new Properties();
        try(java.io.FileInputStream input=new java.io.FileInputStream(metadata)){reread.load(input);}
        assertEquals("1000",reread.getProperty("cachedAt"));
        PlaylistFeedCache.noteIndexedInitialAccess(body,150000L);
        assertEquals(200000L,metadata.lastModified());
        // Invalid body identity must not update access recency.
        try(FileOutputStream output=new FileOutputStream(body,true)){output.write(1);}
        PlaylistFeedCache.noteIndexedInitialAccess(body,300000L);
        assertEquals(200000L,metadata.lastModified());
    }
}
