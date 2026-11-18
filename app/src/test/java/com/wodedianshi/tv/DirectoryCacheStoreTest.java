package com.wodedianshi.tv;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

import static org.junit.Assert.*;

public class DirectoryCacheStoreTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void domesticAndGlobalDirectoriesUseIndependentFiles() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        byte[] china = new byte[] {1, 2, 3};
        byte[] global = new byte[] {7, 8, 9, 10};
        byte[] all = new byte[] {11, 12, 13, 14, 15};
        store.save(DirectoryCacheStore.Directory.CHINA, "cn-v1", china, 100L);
        store.save(DirectoryCacheStore.Directory.GLOBAL, "world-v2", global, 200L);
        store.save(DirectoryCacheStore.Directory.ALL, "all-v1", all, 300L);

        assertNotEquals(store.fileFor(DirectoryCacheStore.Directory.CHINA),
                store.fileFor(DirectoryCacheStore.Directory.GLOBAL));
        assertTrue(Arrays.equals(china,
                store.load(DirectoryCacheStore.Directory.CHINA, "cn-v1").payload));
        assertTrue(Arrays.equals(global,
                store.load(DirectoryCacheStore.Directory.GLOBAL, "world-v2").payload));
        assertNotEquals(store.fileFor(DirectoryCacheStore.Directory.ALL),
                store.fileFor(DirectoryCacheStore.Directory.CHINA));
        assertTrue(Arrays.equals(all,
                store.load(DirectoryCacheStore.Directory.ALL, "all-v1").payload));
    }

    @Test public void offlineLoadAcceptsLastKnownFingerprint() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        byte[] payload = new byte[] {4, 5, 6};
        store.save(DirectoryCacheStore.Directory.CHINA, "feed-hash-1", payload, 123L);

        DirectoryCacheStore.Snapshot cached =
                store.load(DirectoryCacheStore.Directory.CHINA, null);
        assertNotNull(cached);
        assertEquals("feed-hash-1", cached.sourceFingerprint);
        assertEquals(123L, cached.savedAtMillis);
        assertArrayEquals(payload, cached.payload);
    }

    @Test public void changedFeedFingerprintIsTreatedAsCacheMiss() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        store.save(DirectoryCacheStore.Directory.GLOBAL, "old-feed", new byte[] {1}, 1L);
        assertNull(store.load(DirectoryCacheStore.Directory.GLOBAL, "new-feed"));
    }

    @Test public void corruptedPayloadIsRejectedByChecksum() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        store.save(DirectoryCacheStore.Directory.CHINA, "feed", new byte[] {1, 2, 3, 4}, 1L);
        File file = store.fileFor(DirectoryCacheStore.Directory.CHINA);
        RandomAccessFile random = new RandomAccessFile(file, "rw");
        try {
            random.seek(file.length() - 1);
            random.write(99);
        } finally {
            random.close();
        }
        assertNull(store.load(DirectoryCacheStore.Directory.CHINA, null));
    }

    @Test public void missingAndTruncatedCachesAreSafeMisses() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        assertNull(store.load(DirectoryCacheStore.Directory.CHINA, null));
        File file = store.fileFor(DirectoryCacheStore.Directory.CHINA);
        assertTrue(file.getParentFile().mkdirs());
        assertTrue(file.createNewFile());
        assertNull(store.load(DirectoryCacheStore.Directory.CHINA, null));
    }


    @Test public void interruptedCommitRecoversVerifiedBackup() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        byte[] stable = new byte[] {21, 22, 23};
        store.save(DirectoryCacheStore.Directory.CHINA, "stable-feed", stable, 42L);
        File target = store.fileFor(DirectoryCacheStore.Directory.CHINA);
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        assertTrue(target.renameTo(backup));

        DirectoryCacheStore.Snapshot recovered =
                store.load(DirectoryCacheStore.Directory.CHINA, null);
        assertNotNull(recovered);
        assertArrayEquals(stable, recovered.payload);
        assertTrue(target.isFile());
        assertFalse(backup.exists());
    }

    @Test public void corruptTargetFallsBackToVerifiedBackup() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        byte[] stable = new byte[] {31, 32, 33};
        store.save(DirectoryCacheStore.Directory.GLOBAL, "world", stable, 55L);
        File target = store.fileFor(DirectoryCacheStore.Directory.GLOBAL);
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        assertTrue(target.renameTo(backup));
        java.io.FileOutputStream corrupt = new java.io.FileOutputStream(target);
        try { corrupt.write(new byte[] {0, 1, 2}); } finally { corrupt.close(); }

        DirectoryCacheStore.Snapshot recovered =
                store.load(DirectoryCacheStore.Directory.GLOBAL, null);
        assertNotNull(recovered);
        assertArrayEquals(stable, recovered.payload);
        assertTrue(target.isFile());
    }

    @Test public void failedOversizedWriteDoesNotReplacePreviousSnapshot() throws Exception {
        DirectoryCacheStore store = new DirectoryCacheStore(temporaryFolder.getRoot());
        byte[] stable = new byte[] {11, 12, 13};
        store.save(DirectoryCacheStore.Directory.CHINA, "stable", stable, 10L);
        try {
            store.save(DirectoryCacheStore.Directory.CHINA, "bad",
                    new byte[64 * 1024 * 1024 + 1], 20L);
            fail("oversized payload should fail");
        } catch (java.io.IOException expected) {
            // expected
        }
        assertArrayEquals(stable,
                store.load(DirectoryCacheStore.Directory.CHINA, "stable").payload);
    }
}
