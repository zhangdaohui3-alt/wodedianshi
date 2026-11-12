package com.wodedianshi.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.*;

/** Verifies the Java wrapper preserves the JNI ABI expected by pinned mpv-android. */
public class MpvLibBridgeContractTest {
    private Class<?> bridgeClass() throws ClassNotFoundException {
        // Do not initialize the class: native libraries are intentionally absent
        // from the ordinary host-JVM unit-test environment.
        return Class.forName("is.xyz.mpv.MPVLib", false, getClass().getClassLoader());
    }

    @Test public void nativeMethodsKeepExpectedInstanceSignatures() throws Exception {
        Class<?> bridge = bridgeClass();
        assertNativeInstanceMethod(bridge.getDeclaredMethod("create", Context.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("init"));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("destroy"));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("attachSurface", Surface.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("command", String[].class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("setOptionString", String.class, String.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("grabThumbnail", int.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("getPropertyInt", String.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("getPropertyDouble", String.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("getPropertyBoolean", String.class));
        assertNativeInstanceMethod(bridge.getDeclaredMethod("getPropertyString", String.class));
    }

    @Test public void callbacksAreStaticWithExactNativeLookupOverloads() throws Exception {
        Class<?> bridge = bridgeClass();
        assertStaticMethod(bridge.getDeclaredMethod("event", int.class));
        assertStaticMethod(bridge.getDeclaredMethod("eventProperty", String.class));
        assertStaticMethod(bridge.getDeclaredMethod("eventProperty", String.class, long.class));
        assertStaticMethod(bridge.getDeclaredMethod("eventProperty", String.class, boolean.class));
        assertStaticMethod(bridge.getDeclaredMethod("eventProperty", String.class, double.class));
        assertStaticMethod(bridge.getDeclaredMethod("eventProperty", String.class, String.class));
        assertStaticMethod(bridge.getDeclaredMethod("logMessage", String.class, int.class, String.class));
    }

    @Test public void thumbnailReturnTypeAndConstantsMatchUpstreamWrapper() throws Exception {
        Class<?> bridge = bridgeClass();
        Method thumbnail = bridge.getDeclaredMethod("grabThumbnail", int.class);
        assertEquals(Bitmap.class, thumbnail.getReturnType());
        Class<?> format = Class.forName("is.xyz.mpv.MPVLib$MpvFormat", false, getClass().getClassLoader());
        assertEquals(4, format.getField("MPV_FORMAT_INT64").getInt(null));
        Class<?> event = Class.forName("is.xyz.mpv.MPVLib$MpvEvent", false, getClass().getClassLoader());
        assertEquals(21, event.getField("MPV_EVENT_PLAYBACK_RESTART").getInt(null));
    }

    private static void assertNativeInstanceMethod(Method method) {
        assertTrue(Modifier.isNative(method.getModifiers()));
        assertFalse(Modifier.isStatic(method.getModifiers()));
    }

    private static void assertStaticMethod(Method method) {
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }
}
