package com.wodedianshi.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
/**
 * Reports decoder implementations advertised by the current Android device.
 * This is a diagnostic inventory only: a listed codec is not proof that every
 * profile, level, resolution, bit depth, or real-world stream is playable.
 */
public final class DeviceCodecCapabilityInspector {
    private static final String[] TARGET_MIME_TYPES = {
            "video/avc",
            "video/hevc",
            "video/av01",
            "video/x-vnd.on2.vp9",
            "video/mp4v-es",
            "video/mpeg2",
            "audio/mp4a-latm",
            "audio/ac3",
            "audio/eac3",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/mpeg"
    };

    private DeviceCodecCapabilityInspector() {}

    public static List<String> describeDecoderInventory() {
        ArrayList<String> report = new ArrayList<>();
        MediaCodecInfo[] codecs;
        try {
            codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (RuntimeException error) {
            report.add("decoder inventory unavailable: " + error.getClass().getSimpleName());
            return report;
        }

        for (String mime : TARGET_MIME_TYPES) {
            ArrayList<String> names = new ArrayList<>();
            for (MediaCodecInfo codec : codecs) {
                if (codec == null || codec.isEncoder()) continue;
                try {
                    boolean supports = false;
                    for (String supported : codec.getSupportedTypes()) {
                        if (mime.equalsIgnoreCase(supported)) {
                            supports = true;
                            break;
                        }
                    }
                    if (!supports) continue;

                    String kind = "type?";
                    if (Build.VERSION.SDK_INT >= 29) {
                        kind = codec.isHardwareAccelerated() ? "HW"
                                : codec.isSoftwareOnly() ? "SW" : "vendor?";
                    }
                    String name = codec.getName();
                    if (name == null) name = "unnamed";
                    name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
                    names.add(name + "(" + kind + ")");
                    if (names.size() >= 3) break;
                } catch (RuntimeException ignored) {
                    // One malformed vendor codec must not hide the rest.
                }
            }
            if (names.isEmpty()) report.add(mime + "=none-advertised");
            else report.add(mime + "=" + join(names));
        }
        return report;
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }
}
