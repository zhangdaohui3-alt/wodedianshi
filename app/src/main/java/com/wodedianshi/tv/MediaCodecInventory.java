package com.wodedianshi.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.Locale;
import java.util.TreeSet;

/**
 * Read-only inventory of decoder implementations and advertised capabilities.
 * It intentionally does not create codecs or attempt to play media.
 */
public final class MediaCodecInventory {
    private static final int MAX_LISTED_ENTRIES = 32;
    private static final int MAX_PROFILE_LEVELS = 4;

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
                    entries.add(mime + " -> " + name + " [" + classification + "]"
                            + capabilitySummary(codec, type, mime));
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

    private static String capabilitySummary(MediaCodecInfo codec, String type, String mime) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(type);
            StringBuilder out = new StringBuilder();
            MediaCodecInfo.CodecProfileLevel[] levels = caps.profileLevels;
            if (levels != null && levels.length > 0) {
                out.append(" profiles=");
                int count = Math.min(levels.length, MAX_PROFILE_LEVELS);
                for (int i = 0; i < count; i++) {
                    if (i > 0) out.append(',');
                    out.append(levels[i].profile).append('/').append(levels[i].level);
                }
                if (levels.length > count) out.append("+").append(levels.length - count);
            }
            if (mime.startsWith("video/") && caps.getVideoCapabilities() != null) {
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                out.append(" size=").append(video.getSupportedWidths())
                        .append('x').append(video.getSupportedHeights());
                out.append(" bitrate=").append(video.getBitrateRange());
            } else if (mime.startsWith("audio/") && caps.getAudioCapabilities() != null) {
                int[] rates = caps.getAudioCapabilities().getSupportedSampleRates();
                if (rates != null && rates.length > 0) {
                    out.append(" sampleRates=");
                    int count = Math.min(rates.length, 8);
                    for (int i = 0; i < count; i++) {
                        if (i > 0) out.append(',');
                        out.append(rates[i]);
                    }
                    if (rates.length > count) out.append("+").append(rates.length - count);
                }
                out.append(" channels=")
                        .append(caps.getAudioCapabilities().getMaxInputChannelCount());
            }
            return out.toString();
        } catch (Exception e) {
            return " capabilities=unavailable(" + e.getClass().getSimpleName() + ")";
        } catch (LinkageError e) {
            return " capabilities=unavailable(" + e.getClass().getSimpleName() + ")";
        }
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
