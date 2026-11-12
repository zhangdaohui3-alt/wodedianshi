package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Java-compatible port of the pinned mpv-android MPVLib JNI wrapper.
 *
 * The native JNI symbols expect this exact package/class name. Native methods
 * remain instance methods (matching the upstream Kotlin object ABI), while
 * callbacks are static methods (matching the upstream @JvmStatic callbacks).
 *
 * Adapted from mpv-android at fdf74f6830c47dbaa8a22ac79726e8303f1db5af.
 * See third_party/mpv-android-LICENSE for the upstream MIT notice.
 */
public final class MPVLib {
    public static final MPVLib INSTANCE = new MPVLib();

    static {
        System.loadLibrary("mpv");
        System.loadLibrary("player");
    }

    private static final List<EventObserver> OBSERVERS = new ArrayList<>();
    private static final List<LogObserver> LOG_OBSERVERS = new ArrayList<>();

    private MPVLib() {}

    public native void create(Context appctx);
    public native void init();
    public native void destroy();
    public native void attachSurface(Surface surface);
    public native void detachSurface();
    public native void command(String[] cmd);
    public native int setOptionString(String name, String value);
    public native Bitmap grabThumbnail(int dimension);
    public native Integer getPropertyInt(String property);
    public native void setPropertyInt(String property, int value);
    public native Double getPropertyDouble(String property);
    public native void setPropertyDouble(String property, double value);
    public native Boolean getPropertyBoolean(String property);
    public native void setPropertyBoolean(String property, boolean value);
    public native String getPropertyString(String property);
    public native void setPropertyString(String property, String value);
    public native void observeProperty(String property, int format);

    public static void addObserver(EventObserver observer) {
        if (observer == null) return;
        synchronized (OBSERVERS) {
            OBSERVERS.add(observer);
        }
    }

    public static void removeObserver(EventObserver observer) {
        synchronized (OBSERVERS) {
            OBSERVERS.remove(observer);
        }
    }

    public static void eventProperty(String property) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : new ArrayList<>(OBSERVERS))
                observer.eventProperty(property);
        }
    }

    public static void eventProperty(String property, long value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : new ArrayList<>(OBSERVERS))
                observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, boolean value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : new ArrayList<>(OBSERVERS))
                observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, double value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : new ArrayList<>(OBSERVERS))
                observer.eventProperty(property, value);
        }
    }

    public static void eventProperty(String property, String value) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : new ArrayList<>(OBSERVERS))
                observer.eventProperty(property, value);
        }
    }

    public static void event(int eventId) {
        synchronized (OBSERVERS) {
            for (EventObserver observer : new ArrayList<>(OBSERVERS))
                observer.event(eventId);
        }
    }

    public static void addLogObserver(LogObserver observer) {
        if (observer == null) return;
        synchronized (LOG_OBSERVERS) {
            LOG_OBSERVERS.add(observer);
        }
    }

    public static void removeLogObserver(LogObserver observer) {
        synchronized (LOG_OBSERVERS) {
            LOG_OBSERVERS.remove(observer);
        }
    }

    public static void logMessage(String prefix, int level, String message) {
        synchronized (LOG_OBSERVERS) {
            for (LogObserver observer : new ArrayList<>(LOG_OBSERVERS))
                observer.logMessage(prefix, level, message);
        }
    }

    public interface EventObserver {
        void eventProperty(String property);
        void eventProperty(String property, long value);
        void eventProperty(String property, boolean value);
        void eventProperty(String property, String value);
        void eventProperty(String property, double value);
        void event(int eventId);
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    public static final class MpvFormat {
        public static final int MPV_FORMAT_NONE = 0;
        public static final int MPV_FORMAT_STRING = 1;
        public static final int MPV_FORMAT_OSD_STRING = 2;
        public static final int MPV_FORMAT_FLAG = 3;
        public static final int MPV_FORMAT_INT64 = 4;
        public static final int MPV_FORMAT_DOUBLE = 5;
        public static final int MPV_FORMAT_NODE = 6;
        public static final int MPV_FORMAT_NODE_ARRAY = 7;
        public static final int MPV_FORMAT_NODE_MAP = 8;
        public static final int MPV_FORMAT_BYTE_ARRAY = 9;
        private MpvFormat() {}
    }

    public static final class MpvEvent {
        public static final int MPV_EVENT_NONE = 0;
        public static final int MPV_EVENT_SHUTDOWN = 1;
        public static final int MPV_EVENT_LOG_MESSAGE = 2;
        public static final int MPV_EVENT_GET_PROPERTY_REPLY = 3;
        public static final int MPV_EVENT_SET_PROPERTY_REPLY = 4;
        public static final int MPV_EVENT_COMMAND_REPLY = 5;
        public static final int MPV_EVENT_START_FILE = 6;
        public static final int MPV_EVENT_END_FILE = 7;
        public static final int MPV_EVENT_FILE_LOADED = 8;
        public static final int MPV_EVENT_IDLE = 11;
        public static final int MPV_EVENT_TICK = 14;
        public static final int MPV_EVENT_CLIENT_MESSAGE = 16;
        public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
        public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
        public static final int MPV_EVENT_SEEK = 20;
        public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
        public static final int MPV_EVENT_PROPERTY_CHANGE = 22;
        public static final int MPV_EVENT_QUEUE_OVERFLOW = 24;
        public static final int MPV_EVENT_HOOK = 25;
        private MpvEvent() {}
    }

    public static final class MpvLogLevel {
        public static final int MPV_LOG_LEVEL_NONE = 0;
        public static final int MPV_LOG_LEVEL_FATAL = 10;
        public static final int MPV_LOG_LEVEL_ERROR = 20;
        public static final int MPV_LOG_LEVEL_WARN = 30;
        public static final int MPV_LOG_LEVEL_INFO = 40;
        public static final int MPV_LOG_LEVEL_V = 50;
        public static final int MPV_LOG_LEVEL_DEBUG = 60;
        public static final int MPV_LOG_LEVEL_TRACE = 70;
        private MpvLogLevel() {}
    }
}
