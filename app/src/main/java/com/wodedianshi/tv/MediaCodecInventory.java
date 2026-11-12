package com.wodedianshi.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.Locale;
import java.util.TreeSet;

/**
 * Read-only inventory of the decoder implementations exposed by this device.
 * It intentionally does not create codecs or attempt to play media.
 */
public final class MediaCodecInventory {
    private static final int MAX_LISTED_ENTRIES = 32;

    private MediaCodecInventory() {}

    public static String describe() {
        TreeSet<String> entries = new TreeSet<>();
        int decoderCount = 0;
        int decoderTypeCount = 0;
        int omitted = 0;

        try {
            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for (MediaCodecInfo codec : codecs) {
                if (codec == null || codec.isEncoder()) continue;
                decoderCount++;
                String classification = classification(codec);
                String name = codec.getName() == null ? "unknown" : codec.getName();

                String[] types = codec.getSupportedTypes();
                if (types == null) continue;
                for (String type : types) {
                    if (type == null) continue;
                    String mime = type.toLowerCase(Locale.ROOT);
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue;
                    decoderTypeCount++;
                    entries.add(mime + " -> " + name + " [" + classification + "]");
                }
            }
        } catch (Exception e) {
            return "inventory unavailable: " + e.getClass().getSimpleName();
        } catch (LinkageError e) {
            return "inventory unavailable: " + e.getClass().getSimpleName();
        }

        StringBuilder result = new StringBuilder();
        result.append("decoders=").append(decoderCount)
                .append(", audio/video decoder MIME entries=").append(decoderTypeCount);
        int shown = 0;
        for (String entry : entries) {
            if (shown >= MAX_LISTED_ENTRIES) {
                omitted = entries.size() - shown;
                break;
            }
            result.append('\n').append(entry);
            shown++;
        }
        if (omitted > 0) result.append("\n... ").append(omitted).append(" entries omitted");
        if (decoderCount == 0) result.append("\nNo decoders reported by MediaCodecList");
        return result.toString();
    }

    private static String classification(MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) {
            if (codec.isHardwareAccelerated()) return "hardware";
            if (codec.isSoftwareOnly()) return "software";
            return codec.isVendor() ? "vendor-unclassified" : "platform-unclassified";
        }
        return "classification-unavailable-api-" + Build.VERSION.SDK_INT;
    }
}
