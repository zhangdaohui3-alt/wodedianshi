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
    private static final int LEGACY_OFFSET_INDEX_MAGIC = 0x4D545649; // MTVI
    private static final int OFFSET_INDEX_MAGIC = 0x4D54564A; // MTVJ: length-prefixed UTF-8
    private static final int COVERAGE_INDEX_MAGIC = 0x4D54564B; // MTVK: station-first initial index
    private static final int COVERAGE_IDENTITY_LIMIT = 16384;
    private static final int MAX_FIELD_BYTES = 1024 * 1024;

    private static boolean supportedMagic(int magic) {
        return magic == LEGACY_OFFSET_INDEX_MAGIC || magic == OFFSET_INDEX_MAGIC || magic == COVERAGE_INDEX_MAGIC;
    }
    private static final long OFFSET_HEADER_BYTES = 12L;

    private PlaylistFeedIndex() { }

    // Bridge the parser's non-throwing consumer to the index's checked
    // I/O contract without scanning the remaining body after a write fails.
    private static final class CorruptIndexException extends IOException {
        CorruptIndexException(String message){super(message);}
    }

    private static final class IndexWriteFailure extends RuntimeException {
        final IOException failure;
        IndexWriteFailure(IOException failure) { super(failure); this.failure=failure; }
    }

    public static int readPage(File playlist, int startEntry, int maxEntries,
                               PlaylistParser.EntryConsumer consumer) throws IOException {
        return readPage(playlist, startEntry, maxEntries, consumer, () -> false);
    }

    public static int readPage(File playlist, int startEntry, int maxEntries,
                               PlaylistParser.EntryConsumer consumer,
                               java.util.function.BooleanSupplier paused) throws IOException {
        return readPage(playlist,startEntry,maxEntries,consumer,paused,false);
    }

    static int readInitialPage(File playlist,int startEntry,int maxEntries,
            PlaylistParser.EntryConsumer consumer,java.util.function.BooleanSupplier paused)throws IOException {
        return readPage(playlist,startEntry,maxEntries,consumer,paused,true);
    }

    /** Acquisition identity stays the body version; a probe cursor additionally
     * identifies its deterministic record order. Legacy raw cursors reset only
     * when the order actually changes. */
    static String initialProbeVersion(String bodyVersion,boolean coverage){
        return coverage?bodyVersion+"|station-first-v1":bodyVersion;
    }

    /** Keep an exact raw-order checkpoint instead of restarting already
     * inspected rows for a sorting optimization. A changed/unknown body
     * receives the normal station-first preparation. */
    static boolean preferCoverageOrder(String bodyVersion,String savedVersion,int cursor,int partialScanned){
        return !(DiscoveryBatchPolicy.isPreparedInitialFeed(savedVersion,bodyVersion)
                &&(cursor>0||partialScanned>0));
    }

    /** A missing derived index must not downgrade an already started
     * station-first checkpoint to raw order during stable viewing. */
    static boolean needsCoverageRecovery(String bodyVersion,String savedVersion,int cursor,int partialScanned){
        return bodyVersion!=null&&!bodyVersion.isEmpty()&&(cursor>0||partialScanned>0)
                &&initialProbeVersion(bodyVersion,true).equals(savedVersion);
    }

    static boolean requiresIdleCoverageRepair(File body,String bodyVersion,String savedVersion,
            int cursor,int partialScanned){
        return needsCoverageRecovery(bodyVersion,savedVersion,cursor,partialScanned)
                &&!hasCoverageOrder(body);
    }

    static boolean hasCoverageOrder(File playlist){
        if(playlist==null)return false;
        File records=new File(playlist.getAbsolutePath()+".index-data");
        File offsets=new File(playlist.getAbsolutePath()+".index-offsets");
        IndexHeader header=inspectHeader(records,offsets);
        return header!=null&&header.magic==COVERAGE_INDEX_MAGIC;
    }

    private static int readPage(File playlist,int startEntry,int maxEntries,
            PlaylistParser.EntryConsumer consumer,java.util.function.BooleanSupplier paused,
            boolean coverage) throws IOException {
        SourceProbeTimePolicy.checkPaused(paused);
        if (playlist == null || !playlist.isFile() || startEntry < 0
                || maxEntries <= 0 || consumer == null) {
            throw new IllegalArgumentException("Invalid playlist page arguments");
        }
        File records = new File(playlist.getAbsolutePath() + ".index-data");
        File offsets = new File(playlist.getAbsolutePath() + ".index-offsets");
        IndexHeader header=inspectHeaderForRepair(records,offsets);
        boolean usable=header!=null;
        boolean repairCoverage=coverage||(usable&&header.magic==COVERAGE_INDEX_MAGIC);
        if (!usable || (coverage && header.magic!=COVERAGE_INDEX_MAGIC)) {
            // Keep a usable raw index until the coverage replacement is fully
            // written. Playback may pause the two-pass build at any point.
            SourceProbeTimePolicy.checkPaused(paused);
            if (!usable) deleteIndex(records, offsets);
            buildIndex(playlist, records, offsets, paused, repairCoverage);
        }

        // Decode the whole page before invoking the consumer. If a sidecar is
        // corrupt halfway through, rebuilding cannot duplicate partial emissions.
        List<PlaylistParser.ChannelSource> page;
        try {
            page = readIndexedPage(records, offsets, startEntry, maxEntries, paused);
        } catch (SourceProbeTimePolicy.PausedException pausedRead) {
            throw pausedRead; // Cancellation is not corruption; retain the valid sidecars.
        } catch (CorruptIndexException | java.io.EOFException | java.io.UTFDataFormatException corruptIndex) {
            SourceProbeTimePolicy.checkPaused(paused);
            deleteIndex(records, offsets);
            buildIndex(playlist, records, offsets, paused,repairCoverage);
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
        IndexHeader header=inspectHeader(records,offsets);
        return header==null?-1:header.count;
    }

    private static List<PlaylistParser.ChannelSource> readIndexedPage(
            File records, File offsets, int startEntry, int maxEntries,
            java.util.function.BooleanSupplier paused) throws IOException {
        List<PlaylistParser.ChannelSource> page = new ArrayList<>();
        try (RandomAccessFile offsetFile = new RandomAccessFile(offsets, "r");
             RandomAccessFile recordFile = new RandomAccessFile(records, "r")) {
            long recordsLength=recordFile.length();
            IndexHeader header=readCheckedHeader(offsetFile,recordsLength);
            int magic=header.magic;
            long count=header.count;
            if (startEntry >= count) return page;
            int emitted = (int) Math.min((long) maxEntries, count - startEntry);
            long previousOffset = -1L;
            if(startEntry>0){
                offsetFile.seek(OFFSET_HEADER_BYTES+((long)startEntry-1L)*8L);
                previousOffset=offsetFile.readLong();
                if(previousOffset<0L||previousOffset>=recordsLength)
                    throw new CorruptIndexException("Previous playlist offset is invalid");
            }
            // The offset immediately after one row is the next row's start.
            // Read it once, then carry it forward; no repeated seek/read per row.
            offsetFile.seek(OFFSET_HEADER_BYTES+((long)startEntry)*8L);
            long recordOffset=offsetFile.readLong();
            for (int i = 0; i < emitted; i++) {
                SourceProbeTimePolicy.checkPaused(paused);
                long entry=(long)startEntry+i;
                long nextOffset=entry+1L<count?offsetFile.readLong():recordsLength;
                if (recordOffset <= previousOffset || recordOffset < 0L
                        || recordOffset >= recordsLength || (entry==0L&&recordOffset!=0L)
                        || nextOffset<=recordOffset||nextOffset>recordsLength) {
                    throw new CorruptIndexException("Playlist index offset is invalid");
                }
                previousOffset = recordOffset;
                if(i==0)recordFile.seek(recordOffset);
                // Prior record's verified end must be the current record's start.
                if(recordFile.getFilePointer()!=recordOffset)
                    throw new CorruptIndexException("Playlist record start does not match indexed boundary");
                PlaylistParser.ChannelSource source=readRecord(recordFile,magic,nextOffset);
                if(recordFile.getFilePointer()!=nextOffset)
                    throw new CorruptIndexException("Playlist record does not match indexed boundary");
                page.add(source);
                recordOffset=nextOffset;
            }
        }
        return page;
    }

    private static final class IndexHeader {
        final int magic,count;
        IndexHeader(int magic,int count){this.magic=magic;this.count=count;}
    }

    /** One validated header supplies both record count and ordering. */
    private static IndexHeader readCheckedHeader(RandomAccessFile input,long recordsLength)throws IOException{
        int magic=input.readInt();
        long count=input.readLong();
        if(!supportedMagic(magic)||count<0L||count>Integer.MAX_VALUE
                ||input.length()!=OFFSET_HEADER_BYTES+count*8L
                ||(count==0L?recordsLength!=0L:recordsLength<=0L))
            throw new CorruptIndexException("Playlist index header/count mismatch");
        return new IndexHeader(magic,(int)count);
    }

    private static IndexHeader inspectHeader(File records,File offsets){
        try{return inspectHeaderForRepair(records,offsets);}
        catch(IOException unavailable){return null;} // Scheduling hint only.
    }

    /** Missing or structurally invalid sidecars can be rebuilt. An open/read
     * failure alone is not evidence that an existing index is corrupt. */
    private static IndexHeader inspectHeaderForRepair(File records,File offsets)throws IOException {
        if((records.exists()&&!records.isFile())||(offsets.exists()&&!offsets.isFile()))
            throw new IOException("Playlist index path is not a regular file");
        if(!records.exists()||!offsets.exists())return null;
        try(RandomAccessFile input=new RandomAccessFile(offsets,"r");
                RandomAccessFile data=new RandomAccessFile(records,"r")){
            return readCheckedHeader(input,data.length());
        }catch(CorruptIndexException | java.io.EOFException invalid){return null;}
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
            java.util.function.BooleanSupplier paused,boolean coverage) throws IOException {
        File recordsTemporary = new File(records.getAbsolutePath() + ".tmp");
        File offsetsTemporary = new File(offsets.getAbsolutePath() + ".tmp");
        final long[] written = new long[1];
        final long[] recordPosition = new long[1];

        // Sequential construction needs no random seeks until the final count.
        // Bound buffering to two 32 KiB streams, not the whole playlist.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new FileInputStream(playlist), StandardCharsets.UTF_8), 8192);
             java.io.FileOutputStream recordRaw = new java.io.FileOutputStream(recordsTemporary);
             java.io.FileOutputStream offsetRaw = new java.io.FileOutputStream(offsetsTemporary);
             java.io.DataOutputStream recordFile = new java.io.DataOutputStream(
                     new java.io.BufferedOutputStream(recordRaw,32768));
             java.io.DataOutputStream offsetFile = new java.io.DataOutputStream(
                     new java.io.BufferedOutputStream(offsetRaw,32768))) {
            offsetFile.writeInt(coverage?COVERAGE_INDEX_MAGIC:OFFSET_INDEX_MAGIC);
            offsetFile.writeLong(0L); // Filled after the complete index is written.
            PlaylistParser.BoundedLineReader lines=new PlaylistParser.BoundedLineReader(reader);
            String header = lines.readLine(paused);
            if (header == null || !header.replace("\uFEFF", "").trim().startsWith("#EXTM3U")) {
                throw new IOException("Cached resource is not a valid M3U playlist");
            }
            PlaylistParser.EntryConsumer write=source->{
                try {
                    offsetFile.writeLong(recordPosition[0]);
                    recordPosition[0] = Math.addExact(recordPosition[0],writeRecord(recordFile, source));
                    written[0]++;
                } catch (IOException error) {
                    throw new IndexWriteFailure(error);
                }
            };
            if(!coverage)PlaylistParser.parseLines(lines,0,Integer.MAX_VALUE,write,paused);
            else {
                // First occurrences are scheduling candidates, not validated
                // stations. Keep every backup, even ambiguous or repeated rows.
                java.util.HashSet<String> seen=new java.util.HashSet<>();
                java.util.BitSet firstPositions=new java.util.BitSet();
                int[] position={0};
                int total=PlaylistParser.parseLines(lines,0,Integer.MAX_VALUE,source->{
                    int ordinal=position[0]++;
                    if(seen.size()>=COVERAGE_IDENTITY_LIMIT)return;
                    // Conflicting station labels cannot claim the first slot.
                    if(ChannelNamePolicy.consistentBroadcaster(source.name,source.tvgName,source.tvgId)==null)return;
                    String id=SourceDiscoveryIdentityPolicy.identity(source.name,source.group,
                        source.url,source.tvgId,source.tvgName);
                    if(id.length()<=512&&seen.add(id)){
                        firstPositions.set(ordinal);
                        write.accept(source);
                    }
                },paused);
                seen.clear();
                position[0]=0;
                // Re-read the same immutable local body, not the remote URL.
                // The bitset avoids re-normalizing identities on the second pass.
                try(BufferedReader backupReader=new BufferedReader(new InputStreamReader(
                        new FileInputStream(playlist),StandardCharsets.UTF_8),8192)){
                    PlaylistParser.BoundedLineReader backupLines=new PlaylistParser.BoundedLineReader(backupReader);
                    backupLines.readLine(paused);
                    int repeated=PlaylistParser.parseLines(backupLines,0,Integer.MAX_VALUE,source->{
                        if(!firstPositions.get(position[0]++))write.accept(source);
                    },paused);
                    if(repeated!=total||written[0]!=total)
                        throw new IOException("Coverage index candidate count mismatch");
                }
            }
            SourceProbeTimePolicy.checkPaused(paused);
            recordFile.flush();
            offsetFile.flush();
            if (recordsTemporary.length()!=recordPosition[0]
                    ||offsetsTemporary.length()!=OFFSET_HEADER_BYTES+written[0]*8L)
                throw new IOException("Playlist index byte count mismatch");
            recordRaw.getFD().sync();
            // Flush body before publishing the authoritative offset count.
            try(RandomAccessFile headerFile=new RandomAccessFile(offsetsTemporary,"rw")){
                headerFile.seek(4L);
                headerFile.writeLong(written[0]);
                headerFile.getFD().sync();
            }
            SourceProbeTimePolicy.checkPaused(paused);
        } catch (IndexWriteFailure error) {
            recordsTemporary.delete();
            offsetsTemporary.delete();
            throw error.failure;
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

    private static long writeRecord(java.io.DataOutputStream output,
                                    PlaylistParser.ChannelSource source) throws IOException {
        long bytes=writeField(output,source.name);
        bytes+=writeField(output,source.url);
        bytes+=writeField(output,source.group);
        bytes+=writeField(output,source.tvgId);
        bytes+=writeField(output,source.tvgName);
        return bytes;
    }

    private static long writeField(java.io.DataOutputStream output,String value)throws IOException {
        if(value.length()>MAX_FIELD_BYTES)throw new IOException("Playlist index field exceeds size limit");
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FIELD_BYTES)throw new IOException("Playlist index field exceeds size limit");
        output.writeInt(bytes.length);
        output.write(bytes);
        return 4L+bytes.length;
    }

    private static String readField(RandomAccessFile input,long boundary)throws IOException {
        if(boundary-input.getFilePointer()<4L)throw new CorruptIndexException("Missing playlist field length");
        int length=input.readInt();
        if(length<0||length>MAX_FIELD_BYTES||length>boundary-input.getFilePointer())
            throw new CorruptIndexException("Invalid playlist field length");
        byte[] bytes=new byte[length];
        input.readFully(bytes);
        return new String(bytes,StandardCharsets.UTF_8);
    }

    private static PlaylistParser.ChannelSource readRecord(RandomAccessFile input,int magic,long boundary)
            throws IOException {
        if(magic==LEGACY_OFFSET_INDEX_MAGIC)
            return PlaylistParser.indexedSource(input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readUTF());
        return PlaylistParser.indexedSource(readField(input,boundary),readField(input,boundary),
                readField(input,boundary),readField(input,boundary),readField(input,boundary));
    }
}
