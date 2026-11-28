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

    @Test public void recoveryUsesExactDigestAndOriginalInitialAgeWithoutValidators() throws Exception {
        File body=folder.newFile("recovered.m3u");
        byte[] bytes="#EXTM3U\n#EXTINF:-1,Test\nhttps://example.org/live\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileOutputStream output=new FileOutputStream(body)){output.write(bytes);}
        assertTrue(body.setLastModified(123456000L));
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex=new StringBuilder();
        for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        String version=body.getName()+":"+hex;
        Properties initial=PlaylistFeedCache.recoveredBodyMetadata(body,()->false,true);
        assertEquals("123456000",initial.getProperty("cachedAt"));
        assertNull(initial.getProperty("etag"));
        assertNull(initial.getProperty("lastModified"));
        assertTrue(PlaylistFeedCache.matchesRecoveredAcquisition(body,initial,version));
        assertFalse(PlaylistFeedCache.matchesRecoveredAcquisition(body,initial,version+"changed"));
        assertNull(PlaylistFeedCache.recoveredBodyMetadata(body,()->false,false).getProperty("cachedAt"));
        try(FileOutputStream output=new FileOutputStream(body,true)){output.write(1);}
        assertFalse(PlaylistFeedCache.matchesRecoveredAcquisition(body,initial,version));
        try{
            PlaylistFeedCache.recoveredBodyMetadata(body,()->true,true);
            fail("Expected cancellation");
        }catch(SourceProbeTimePolicy.PausedException expected){}
    }

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
    @Test public void invalidFullReplacementRetainsValidatedRangePrefix() throws Exception {
        File prefix=folder.newFile("feed.range-part");
        File state=folder.newFile("feed.range-state");
        File replacement=folder.newFile("feed.m3u.tmp");
        byte[] old="#EXTM3U\n#EXTINF:-1,Test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(prefix)){out.write(old);}
        try(FileOutputStream out=new FileOutputStream(state)){out.write(1);}
        try(FileOutputStream out=new FileOutputStream(replacement)){out.write("invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        try{
            PlaylistFeedCache.validateRangeBody(replacement,prefix,state);
            fail("invalid full replacement");
        }catch(java.io.IOException expected){}
        assertTrue(state.isFile());
        assertArrayEquals(old,java.nio.file.Files.readAllBytes(prefix.toPath()));
        try(FileOutputStream out=new FileOutputStream(replacement)){out.write(old);}
        PlaylistFeedCache.validateRangeBody(replacement,prefix,state);
        assertTrue(state.isFile());
        assertArrayEquals(old,java.nio.file.Files.readAllBytes(prefix.toPath()));
        // A corrupt completed range itself must still invalidate its authority.
        try(FileOutputStream out=new FileOutputStream(prefix)){out.write(0);}
        try{
            PlaylistFeedCache.validateRangeBody(prefix,prefix,state);
            fail("invalid completed range");
        }catch(java.io.IOException expected){}
        assertFalse(state.exists());
        assertFalse(prefix.exists());
    }
}
