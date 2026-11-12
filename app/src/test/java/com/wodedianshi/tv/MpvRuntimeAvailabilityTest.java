package com.wodedianshi.tv;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class MpvRuntimeAvailabilityTest {
    @Test public void armv7AbiMustBeExplicitlyPresent() {
        assertTrue(MpvRuntimeAvailability.supportsArmV7(new String[]{"armeabi-v7a", "armeabi"}));
        assertTrue(MpvRuntimeAvailability.supportsArmV7(new String[]{"arm64-v8a", "armeabi-v7a"}));
        assertFalse(MpvRuntimeAvailability.supportsArmV7(new String[]{"arm64-v8a"}));
        assertFalse(MpvRuntimeAvailability.supportsArmV7(new String[]{"x86_64"}));
        assertFalse(MpvRuntimeAvailability.supportsArmV7(null));
    }

    @Test public void bothNativeLibrariesMustExistBeforeLoadingBridge() throws IOException {
        File dir = createTempDir();
        assertFalse(MpvRuntimeAvailability.hasRequiredLibraries(dir));
        assertTrue(new File(dir, "libmpv.so").createNewFile());
        assertFalse(MpvRuntimeAvailability.hasRequiredLibraries(dir));
        assertTrue(new File(dir, "libplayer.so").createNewFile());
        assertTrue(MpvRuntimeAvailability.hasRequiredLibraries(dir));
    }

    @Test public void nullOrNonDirectoryPathIsUnavailable() {
        assertFalse(MpvRuntimeAvailability.hasRequiredLibraries(null));
        assertFalse(MpvRuntimeAvailability.hasRequiredLibraries(new File("missing-mpv-native-dir")));
    }

    private static File createTempDir() throws IOException {
        File file = File.createTempFile("mpv-native", "dir");
        assertTrue(file.delete());
        assertTrue(file.mkdir());
        file.deleteOnExit();
        return file;
    }
}
