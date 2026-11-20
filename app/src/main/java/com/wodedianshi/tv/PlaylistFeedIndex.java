package com.wodedianshi.tv;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * One-time random-access index for a cached M3U snapshot.
 *
 * The first pass parses the playlist and writes compact channel records plus
 * fixed-width byte offsets. Later WorkManager pages seek directly to the first
 * unprocessed record instead of reparsing the entire prefix on every run.
 */
public final class PlaylistFeedIndex {
    private PlaylistFeedIndex() { }

    public static int readPage(File playlist, int startEntry, int maxEntries,
                               PlaylistParser.EntryConsumer consumer) throws IOException {
        if (playlist == null || !playlist.isFile() || startEntry < 0
                || maxEntries <= 0 || consumer == null) {
            throw new IllegalArgumentException("Invalid playlist page arguments");
        }
        File records = new File(playlist.getAbsolutePath() + ".index-data");
        File offsets = new File(playlist.getAbsolutePath() + ".index-offsets");
        if (!isUsableIndex(records, offsets)) {
            buildIndex(playlist, records, offsets);
        }

        long count;
        try (RandomAccessFile offsetFile = new RandomAccessFile(offsets, "r")) {
            count = offsetFile.length() / 8L;
            if (startEntry >= count) return 0;
            int emitted = (int) Math.min((long) maxEntries, count - startEntry);
            try (RandomAccessFile recordFile = new RandomAccessFile(records, "r")) {
                for (int i = 0; i < emitted; i++) {
                    offsetFile.seek(((long) startEntry + i) * 8L);
                    recordFile.seek(offsetFile.readLong());
                    PlaylistParser.ChannelSource source = readRecord(recordFile);
                    consumer.accept(source);
                }
            }
            return emitted;
        }
    }

    private static boolean isUsableIndex(File records, File offsets) {
        return records.isFile() && offsets.isFile() && offsets.length() % 8L == 0L;
    }

    private static void buildIndex(File playlist, File records, File offsets)
            throws IOException {
        File recordsTemporary = new File(records.getAbsolutePath() + ".tmp");
        File offsetsTemporary = new File(offsets.getAbsolutePath() + ".tmp");
        final IOException[] writeFailure = new IOException[1];
        final int[] written = new int[1];

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new FileInputStream(playlist), StandardCharsets.UTF_8), 8192);
             RandomAccessFile recordFile = new RandomAccessFile(recordsTemporary, "rw");
             DataOutputStream offsetFile = new DataOutputStream(
                     new FileOutputStream(offsetsTemporary))) {
            recordFile.setLength(0L);
            String header = reader.readLine();
            if (header == null || !header.replace("\uFEFF", "").trim().startsWith("#EXTM3U")) {
                throw new IOException("Cached resource is not a valid M3U playlist");
            }
            PlaylistParser.parseEach(reader, Integer.MAX_VALUE, source -> {
                if (writeFailure[0] != null) return;
                try {
                    offsetFile.writeLong(recordFile.getFilePointer());
                    writeRecord(recordFile, source);
                    written[0]++;
                } catch (IOException error) {
                    writeFailure[0] = error;
                }
            });
            offsetFile.flush();
            if (writeFailure[0] != null) throw writeFailure[0];
            if (offsetFile.size() != (long) written[0] * 8L) {
                throw new IOException("Playlist index offset count mismatch");
            }
        } catch (IOException error) {
            recordsTemporary.delete();
            offsetsTemporary.delete();
            throw error;
        }

        // A pair is considered valid only when both final files are present.
        // If the process stops between renames, the next call safely rebuilds it.
        if (records.exists() && !records.delete()) {
            recordsTemporary.delete();
            offsetsTemporary.delete();
            throw new IOException("Cannot replace playlist record index");
        }
        if (offsets.exists() && !offsets.delete()) {
            recordsTemporary.delete();
            offsetsTemporary.delete();
            throw new IOException("Cannot replace playlist offset index");
        }
        if (!recordsTemporary.renameTo(records)) {
            offsetsTemporary.delete();
            throw new IOException("Cannot publish playlist record index");
        }
        if (!offsetsTemporary.renameTo(offsets)) {
            records.delete();
            throw new IOException("Cannot publish playlist offset index");
        }
    }

    private static void writeRecord(RandomAccessFile output,
                                    PlaylistParser.ChannelSource source) throws IOException {
        output.writeUTF(source.name);
        output.writeUTF(source.url);
        output.writeUTF(source.group);
        output.writeUTF(source.tvgId);
        output.writeUTF(source.tvgName);
    }

    private static PlaylistParser.ChannelSource readRecord(RandomAccessFile input)
            throws IOException {
        return PlaylistParser.indexedSource(input.readUTF(), input.readUTF(),
                input.readUTF(), input.readUTF(), input.readUTF());
    }
}
