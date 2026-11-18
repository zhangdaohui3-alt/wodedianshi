package com.wodedianshi.tv;

import com.wodedianshi.tv.db.RoomCatalogueWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Compact, bounded binary representation of a complete locally saved channel directory. */
public final class ChannelDirectorySnapshotCodec {
    private static final int MAGIC = 0x4D545643; // MTVC
    private static final int VERSION = 1;
    private static final int MAX_CHANNELS = 250000;
    private static final int MAX_SOURCES_PER_CHANNEL = 64;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final long MAX_DECODED_BYTES = 64L * 1024L * 1024L;

    public static final class Snapshot {
        public final ArrayList<RoomCatalogueWriter.ChannelSnapshot> channels;
        public final HashSet<String> favorites;
        Snapshot(ArrayList<RoomCatalogueWriter.ChannelSnapshot> channels, HashSet<String> favorites) {
            this.channels = channels; this.favorites = favorites;
        }
    }

    private ChannelDirectorySnapshotCodec() {}

    public static byte[] encode(List<RoomCatalogueWriter.ChannelSnapshot> channels,
                                Set<String> favorites) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                Math.min(1024 * 1024, channels == null ? 1024 : channels.size() * 96));
        writeCompressed(bytes, channels, favorites);
        return bytes.toByteArray();
    }

    /**
     * Streams the compressed snapshot to its destination. Production persistence
     * uses a file stream so a complete compressed directory is never duplicated
     * in a large in-memory byte array.
     */
    public static void writeCompressed(OutputStream destination,
                                       List<RoomCatalogueWriter.ChannelSnapshot> channels,
                                       Set<String> favorites) throws IOException {
        if (destination == null) throw new IllegalArgumentException("destination is null");
        if (channels == null || channels.isEmpty() || channels.size() > MAX_CHANNELS)
            throw new IOException("invalid channel count");
        GZIPOutputStream gzip = new GZIPOutputStream(destination);
        DataOutputStream out = new DataOutputStream(gzip);
        try {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(channels.size());
            for (RoomCatalogueWriter.ChannelSnapshot row : channels) {
                if (row == null) throw new IOException("null channel row");
                writeString(out, row.name); writeString(out, row.url); writeString(out, row.group);
                writeString(out, row.tvgId); writeString(out, row.tvgName);
                out.writeInt(row.activeSource);
                int sourceCount = row.sources == null ? 0 : row.sources.size();
                if (sourceCount < 0 || sourceCount > MAX_SOURCES_PER_CHANNEL)
                    throw new IOException("invalid source count");
                out.writeInt(sourceCount);
                if (row.sources != null) for (String source : row.sources) writeString(out, source);
                writeSet(out, row.sourceScopes);
                int mapCount = row.backupSourceScopes == null ? 0 : row.backupSourceScopes.size();
                if (mapCount > MAX_SOURCES_PER_CHANNEL)
                    throw new IOException("invalid source scope count");
                out.writeInt(mapCount);
                if (row.backupSourceScopes != null) {
                    for (Map.Entry<String, HashSet<String>> entry : row.backupSourceScopes.entrySet()) {
                        writeString(out, entry.getKey()); writeSet(out, entry.getValue());
                    }
                }
            }
            writeSet(out, favorites);
            out.finish();
            out.flush();
        } finally {
            out.close();
        }
    }

    public static Snapshot decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > 64 * 1024 * 1024)
            throw new IOException("invalid snapshot payload size");
        DataInputStream in = new DataInputStream(new BoundedInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)), MAX_DECODED_BYTES));
        try {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("unknown snapshot format");
            int count = in.readInt();
            if (count <= 0 || count > MAX_CHANNELS) throw new IOException("invalid channel count");
            ArrayList<RoomCatalogueWriter.ChannelSnapshot> channels = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                RoomCatalogueWriter.ChannelSnapshot row = new RoomCatalogueWriter.ChannelSnapshot();
                row.name = readString(in); row.url = readString(in); row.group = readString(in);
                row.tvgId = readString(in); row.tvgName = readString(in);
                row.activeSource = in.readInt();
                int sources = in.readInt();
                if (sources < 0 || sources > MAX_SOURCES_PER_CHANNEL) throw new IOException("invalid source count");
                for (int n = 0; n < sources; n++) row.sources.add(readString(in));
                row.sourceScopes.addAll(readSet(in));
                int scopeMaps = in.readInt();
                if (scopeMaps < 0 || scopeMaps > MAX_SOURCES_PER_CHANNEL) throw new IOException("invalid source scope map");
                for (int n = 0; n < scopeMaps; n++) row.backupSourceScopes.put(readString(in), readSet(in));
                if (row.name.trim().isEmpty() || row.url.trim().isEmpty() || row.sources.isEmpty())
                    throw new IOException("invalid channel entry");
                if (row.activeSource < 0 || row.activeSource >= row.sources.size())
                    throw new IOException("invalid active source index");
                channels.add(row);
            }
            HashSet<String> favorites = readSet(in);
            if (in.read() != -1) throw new IOException("trailing snapshot data");
            return new Snapshot(channels, favorites);
        } finally { in.close(); }
    }


    /** Limits decompressed bytes, not merely the compressed cache-file size. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;
        BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }
        @Override public int read() throws IOException {
            if (consumed >= limit) {
                int extra = in.read();
                if (extra < 0) return -1;
                throw new IOException("decoded snapshot exceeds size limit");
            }
            int value = in.read();
            if (value >= 0) consumed++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (consumed >= limit) {
                int extra = read();
                return extra < 0 ? -1 : extra;
            }
            int allowed = (int) Math.min((long) length, limit - consumed);
            int count = in.read(buffer, offset, allowed);
            if (count > 0) consumed += count;
            return count;
        }
    }

    private static void writeSet(DataOutputStream out, Set<String> values) throws IOException {
        int count = values == null ? 0 : values.size();
        if (count > MAX_CHANNELS) throw new IOException("set exceeds size limit");
        out.writeInt(count);
        if (values != null) for (String value : values) writeString(out, value);
    }
    private static HashSet<String> readSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_CHANNELS) throw new IOException("invalid set size");
        HashSet<String> values = new HashSet<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) values.add(readString(in));
        return values;
    }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes("UTF-8");
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("string exceeds size limit");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("invalid string length");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }
}
