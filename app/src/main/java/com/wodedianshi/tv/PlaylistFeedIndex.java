package com.wodedianshi.tv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time random-access index for a cached M3U snapshot.
 *
 * The first pass parses the playlist and writes compact channel records plus
 * a count-checked fixed-width offset table. Later WorkManager pages seek
 * directly to the next record instead of reparsing the entire prefix.
 */
public final class PlaylistFeedIndex {
    private static final int OFFSET_INDEX_MAGIC = 0x4D545649; // MTVI
    private static final long OFFSET_HEADER_BYTES = 12L;

    private PlaylistFeedIndex() { }

    public static int readPage(File playlist, int startEntry, int maxEntries,
                               PlaylistParser.EntryConsumer consumer) throws IOException {
        return readPage(playlist, startEntry, maxEntries, consumer, () -> false);
    }

    public static int readPage(File playlist, int startEntry, int maxEntries,
                               PlaylistParser.EntryConsumer consumer,
                               java.util.function.BooleanSupplier paused) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        if (playlist == null || !playlist.isFile() || startEntry < 0
                || maxEntries <= 0 || consumer == null) {
            throw new IllegalArgumentException("Invalid playlist page arguments");
        }
        File records = new File(playlist.getAbsolutePath() + ".index-data");
        File offsets = new File(playlist.getAbsolutePath() + ".index-offsets");
        if (!isUsableIndex(records, offsets)) {
            deleteIndex(records, offsets);
            buildIndex(playlist, records, offsets, paused);
        }

        // Decode the whole page before invoking the consumer. If a sidecar is
        // corrupt halfway through, rebuilding cannot duplicate partial emissions.
        List<PlaylistParser.ChannelSource> page;
        try {
            page = readIndexedPage(records, offsets, startEntry, maxEntries, paused);
        } catch (SourceProbeTimePolicy.PausedException pausedRead) {
            throw pausedRead; // Cancellation is not corruption; retain the valid sidecars.
        } catch (IOException corruptIndex) {
            deleteIndex(records, offsets);
            buildIndex(playlist, records, offsets, paused);
            // A second failure is a real I/O/source-data problem; preserve the cursor.
            page = readIndexedPage(records, offsets, startEntry, maxEntries, paused);
        }
        SourceProbeTimePolicy.checkPaused(paused);
        for (PlaylistParser.ChannelSource source : page) consumer.accept(source);
        return page.size();
    }

    /** No rebuilding while viewing; a damaged index is deferred to idle work. */
    public static int readExistingPage(File playlist,int startEntry,int maxEntries,
            PlaylistParser.EntryConsumer consumer,java.util.function.BooleanSupplier paused) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        File records=new File(playlist.getAbsolutePath()+".index-data");
        File offsets=new File(playlist.getAbsolutePath()+".index-offsets");
        if(!isUsableIndex(records,offsets))throw new IOException("Cached index unavailable");
        List<PlaylistParser.ChannelSource> page=readIndexedPage(records,offsets,startEntry,maxEntries,paused);
        for(PlaylistParser.ChannelSource source:page){
            SourceProbeTimePolicy.checkPaused(paused);
            consumer.accept(source);
        }
        return page.size();
    }

    /**
     * Cheap, checked entry count from the already built feed index. Do not
     * claim a fractional percentage if the cache/index is missing or corrupt.
     */
    public static int indexedEntryCount(File playlist) {
        if (playlist == null) return -1;
        File records = new File(playlist.getAbsolutePath() + ".index-data");
        File offsets = new File(playlist.getAbsolutePath() + ".index-offsets");
        if (!isUsableIndex(records, offsets)) return -1;
        try (RandomAccessFile input = new RandomAccessFile(offsets, "r")) {
            if (input.readInt() != OFFSET_INDEX_MAGIC) return -1;
            long count = input.readLong();
            return count >= 0L && count <= Integer.MAX_VALUE ? (int)count : -1;
        } catch (IOException ignored) {
            return -1;
        }
    }

    private static List<PlaylistParser.ChannelSource> readIndexedPage(
            File records, File offsets, int startEntry, int maxEntries,
            java.util.function.BooleanSupplier paused) throws IOException {
        List<PlaylistParser.ChannelSource> page = new ArrayList<>();
        try (RandomAccessFile offsetFile = new RandomAccessFile(offsets, "r");
             RandomAccessFile recordFile = new RandomAccessFile(records, "r")) {
            if (offsetFile.readInt() != OFFSET_INDEX_MAGIC) {
                throw new IOException("Playlist index header is invalid");
            }
            long count = offsetFile.readLong();
            if (count < 0L || count > Integer.MAX_VALUE
                    || offsetFile.length() != OFFSET_HEADER_BYTES + count * 8L) {
                throw new IOException("Playlist index count does not match offset table");
            }
            if (startEntry >= count) return page;
            int emitted = (int) Math.min((long) maxEntries, count - startEntry);
            long recordsLength = recordFile.length();
            long previousOffset = -1L;
            if(startEntry>0){
                offsetFile.seek(OFFSET_HEADER_BYTES+((long)startEntry-1L)*8L);
                previousOffset=offsetFile.readLong();
                if(previousOffset<0L||previousOffset>=recordsLength)
                    throw new IOException("Previous playlist offset is invalid");
            }
            for (int i = 0; i < emitted; i++) {
                SourceProbeTimePolicy.checkPaused(paused);
                long entry=(long)startEntry+i;
                offsetFile.seek(OFFSET_HEADER_BYTES+entry*8L);
                long recordOffset = offsetFile.readLong();
                long nextOffset=entry+1L<count?offsetFile.readLong():recordsLength;
                if (recordOffset <= previousOffset || recordOffset < 0L
                        || recordOffset >= recordsLength || (entry==0L&&recordOffset!=0L)
                        || nextOffset<=recordOffset||nextOffset>recordsLength) {
                    throw new IOException("Playlist index offset is invalid");
                }
                previousOffset = recordOffset;
                recordFile.seek(recordOffset);
                PlaylistParser.ChannelSource source=readRecord(recordFile);
                if(recordFile.getFilePointer()!=nextOffset)
                    throw new IOException("Playlist record does not match indexed boundary");
                page.add(source);
            }
        }
        return page;
    }

    private static boolean isUsableIndex(File records, File offsets) {
        if (!records.isFile() || !offsets.isFile()
                || offsets.length() < OFFSET_HEADER_BYTES
                || (offsets.length() - OFFSET_HEADER_BYTES) % 8L != 0L) {
            return false;
        }
        try (RandomAccessFile input = new RandomAccessFile(offsets, "r")) {
            if (input.readInt() != OFFSET_INDEX_MAGIC) return false;
            long count = input.readLong();
            return count >= 0L && count <= Integer.MAX_VALUE
                    && offsets.length() == OFFSET_HEADER_BYTES + count * 8L
                    && (count==0L?records.length()==0L:records.length()>0L);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void deleteIndex(File records, File offsets) throws IOException {
        if (records.exists() && !records.delete()) {
            throw new IOException("Cannot remove corrupt playlist record index");
        }
        if (offsets.exists() && !offsets.delete()) {
            throw new IOException("Cannot remove corrupt playlist offset index");
        }
    }

    private static void buildIndex(File playlist, File records, File offsets,
            java.util.function.BooleanSupplier paused) throws IOException {
        File recordsTemporary = new File(records.getAbsolutePath() + ".tmp");
        File offsetsTemporary = new File(offsets.getAbsolutePath() + ".tmp");
        final IOException[] writeFailure = new IOException[1];
        final long[] written = new long[1];

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new FileInputStream(playlist), StandardCharsets.UTF_8), 8192);
             RandomAccessFile recordFile = new RandomAccessFile(recordsTemporary, "rw");
             RandomAccessFile offsetFile = new RandomAccessFile(offsetsTemporary, "rw")) {
            recordFile.setLength(0L);
            offsetFile.setLength(0L);
            offsetFile.writeInt(OFFSET_INDEX_MAGIC);
            offsetFile.writeLong(0L); // Filled after the complete index is written.
            String header = reader.readLine();
            if (header == null || !header.replace("\uFEFF", "").trim().startsWith("#EXTM3U")) {
                throw new IOException("Cached resource is not a valid M3U playlist");
            }
            PlaylistParser.parseEach(reader, 0, Integer.MAX_VALUE, source -> {
                if (writeFailure[0] != null) return;
                try {
                    offsetFile.writeLong(recordFile.getFilePointer());
                    writeRecord(recordFile, source);
                    written[0]++;
                } catch (IOException error) {
                    writeFailure[0] = error;
                }
            }, paused);
            SourceProbeTimePolicy.checkPaused(paused);
            if (writeFailure[0] != null) throw writeFailure[0];
            offsetFile.seek(4L);
            offsetFile.writeLong(written[0]);
            if (offsetFile.length() != OFFSET_HEADER_BYTES + written[0] * 8L) {
                throw new IOException("Playlist index offset count mismatch");
            }
            offsetFile.getFD().sync();
            recordFile.getFD().sync();
        } catch (IOException error) {
            recordsTemporary.delete();
            offsetsTemporary.delete();
            throw error;
        }

        // If the process stops between the two renames, the incomplete pair
        // fails the header/count validation and is rebuilt on the next read.
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
