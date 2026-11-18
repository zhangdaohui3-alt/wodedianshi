package com.wodedianshi.tv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Crash-conscious, independent snapshots for the domestic and international
 * channel directories. The store deliberately knows nothing about Channel or
 * MainActivity: callers serialize only one directory partition at a time.
 *
 * A snapshot is accepted only when its header, size, checksum and (when
 * supplied) source fingerprint all match. A failed write never intentionally
 * replaces the previous committed snapshot.
 */
public final class DirectoryCacheStore {
    private static final int MAGIC = 0x4D545644; // MTVD
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final int DIGEST_BYTES = 32;
    private static final String[] FILE_NAMES = {
            "china-directory.cache", "global-directory.cache", "all-directory.cache"
    };

    public enum Directory {
        CHINA(0), GLOBAL(1), ALL(2);
        final int fileIndex;
        Directory(int fileIndex) { this.fileIndex = fileIndex; }
    }

    public static final class Snapshot {
        public final Directory directory;
        public final long savedAtMillis;
        public final String sourceFingerprint;
        public final byte[] payload;

        Snapshot(Directory directory, long savedAtMillis,
                 String sourceFingerprint, byte[] payload) {
            this.directory = directory;
            this.savedAtMillis = savedAtMillis;
            this.sourceFingerprint = sourceFingerprint;
            this.payload = payload;
        }
    }

    private final File directory;

    public DirectoryCacheStore(File filesDirectory) {
        if (filesDirectory == null) throw new IllegalArgumentException("filesDirectory is null");
        this.directory = new File(filesDirectory, "directory-cache-v1");
    }

    /** Returns the independent cache file for diagnostics and tests. */
    public File fileFor(Directory which) {
        if (which == null) throw new IllegalArgumentException("directory is null");
        return new File(directory, FILE_NAMES[which.fileIndex]);
    }

    public synchronized void save(Directory which, String sourceFingerprint,
                                  byte[] payload, long savedAtMillis) throws IOException {
        if (which == null) throw new IllegalArgumentException("directory is null");
        if (payload == null) throw new IllegalArgumentException("payload is null");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IOException("directory snapshot exceeds size limit");
        if (sourceFingerprint == null) sourceFingerprint = "";

        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("cannot create directory cache folder");
        }

        byte[] digest = sha256(payload);
        File target = fileFor(which);
        File temp = new File(directory, target.getName() + ".tmp");
        File backup = new File(directory, target.getName() + ".bak");
        // Recover an interrupted previous commit before rotating files again.
        if (backup.isFile()) {
            Snapshot backupSnapshot = readSnapshotFile(which, backup, null);
            Snapshot targetSnapshot = target.isFile() ? readSnapshotFile(which, target, null) : null;
            if (targetSnapshot == null && backupSnapshot != null) {
                if (target.exists() && !target.delete()) throw new IOException("cannot replace invalid directory cache");
                if (!backup.renameTo(target)) throw new IOException("cannot recover previous directory snapshot");
            } else if (targetSnapshot != null && !backup.delete()) {
                throw new IOException("cannot clear obsolete cache backup");
            }
        }
        if (temp.exists() && !temp.delete()) throw new IOException("cannot clear stale temporary cache");

        try {
            FileOutputStream fileOut = new FileOutputStream(temp);
            try {
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fileOut));
                out.writeInt(MAGIC);
                out.writeInt(SCHEMA_VERSION);
                out.writeLong(savedAtMillis);
                out.writeUTF(sourceFingerprint);
                out.writeInt(payload.length);
                out.writeInt(digest.length);
                out.write(digest);
                out.write(payload);
                out.flush();
                fileOut.getFD().sync();
                out.close();
            } finally {
                // DataOutputStream.close() closes fileOut on the success path.
                // Close again defensively if an exception occurred during serialization.
                try { fileOut.close(); } catch (IOException ignored) {}
            }

            if (backup.exists() && !backup.delete()) throw new IOException("cannot clear stale cache backup");
            if (target.exists() && !target.renameTo(backup)) {
                throw new IOException("cannot preserve previous directory snapshot");
            }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                throw new IOException("cannot commit new directory snapshot");
            }
            if (backup.exists()) backup.delete();
        } catch (IOException failure) {
            if (temp.exists()) temp.delete();
            if (!target.exists() && backup.exists()) backup.renameTo(target);
            throw failure;
        }
    }

    /**
     * Loads a validated snapshot. Pass null expectedFingerprint to accept the
     * last known cache while offline; pass a value to reject stale feed layouts.
     */
    public synchronized Snapshot load(Directory which, String expectedFingerprint)
            throws IOException {
        if (which == null) throw new IllegalArgumentException("directory is null");
        File target = fileFor(which);
        File backup = new File(directory, target.getName() + ".bak");
        Snapshot current = target.isFile() ? readSnapshotFile(which, target, expectedFingerprint) : null;
        if (current != null) return current;

        // A process/device interruption may happen after target->backup but
        // before temp->target. Restore the last verified snapshot on next read.
        Snapshot previous = backup.isFile()
                ? readSnapshotFile(which, backup, expectedFingerprint) : null;
        if (previous == null) return null;
        if (target.exists() && !target.delete()) return previous;
        if (backup.renameTo(target)) return previous;
        return previous;
    }

    private Snapshot readSnapshotFile(Directory which, File source, String expectedFingerprint) {
        if (source == null || !source.isFile()) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(source)));
            if (in.readInt() != MAGIC) return null;
            if (in.readInt() != SCHEMA_VERSION) return null;
            long savedAt = in.readLong();
            String fingerprint = in.readUTF();
            int payloadLength = in.readInt();
            int digestLength = in.readInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES
                    || digestLength != DIGEST_BYTES) return null;
            byte[] expectedDigest = new byte[digestLength];
            in.readFully(expectedDigest);
            byte[] payload = new byte[payloadLength];
            in.readFully(payload);
            if (in.read() != -1) return null;
            if (!MessageDigest.isEqual(expectedDigest, sha256(payload))) return null;
            if (expectedFingerprint != null && !expectedFingerprint.equals(fingerprint)) return null;
            return new Snapshot(which, savedAt, fingerprint, payload);
        } catch (IOException malformed) {
            // A truncated or unreadable cache is a miss, not a fatal app startup error.
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }
}
