package com.wodedianshi.tv;

import android.content.Context;
import android.os.Build;

import java.io.File;

/** Verifies the ABI-specific MPV runtime before an optional engine is registered. */
public final class MpvRuntimeAvailability {
    private MpvRuntimeAvailability() {}

    public static boolean supportsArmV7(String[] supportedAbis) {
        if (supportedAbis == null) return false;
        for (String abi : supportedAbis) {
            if ("armeabi-v7a".equals(abi)) return true;
        }
        return false;
    }

    public static boolean hasRequiredLibraries(File nativeLibraryDir) {
        if (nativeLibraryDir == null || !nativeLibraryDir.isDirectory()) return false;
        return new File(nativeLibraryDir, "libmpv.so").isFile()
                && new File(nativeLibraryDir, "libplayer.so").isFile();
    }

    /**
     * Loads and links MPVLib only after checking the installed APK actually
     * contains the ARMv7 libraries. LinkageError is returned as unavailable.
     */
    public static boolean isReady(Context context) {
        if (context == null || !supportsArmV7(Build.SUPPORTED_ABIS)) return false;
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        if (!hasRequiredLibraries(nativeDir)) return false;
        try {
            Class.forName("is.xyz.mpv.MPVLib", true, context.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }
}
