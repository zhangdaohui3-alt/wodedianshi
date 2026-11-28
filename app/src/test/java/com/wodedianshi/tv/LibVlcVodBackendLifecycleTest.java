package com.wodedianshi.tv;

import android.app.Application;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class LibVlcVodBackendLifecycleTest {
    @Test public void startupFailureIsDeliveredButPendingFailureIsRemovedOnRevoke() throws Exception {
        List<Runnable> queued=new ArrayList<>();
        List<String> failures=new ArrayList<>();
        ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()){
            @Override public void execute(Runnable action){queued.add(action);}
        };
        try {
            LibVlcVodBackend backend=new LibVlcVodBackend(RuntimeEnvironment.getApplication(),null,worker);
            backend.setListener(new VodPlaybackBackend.Listener(){
                public void onReady(){fail("Unexpected ready");}
                public void onVideoOutput(boolean available){fail("Unexpected video");}
                public void onFailure(String reason){failures.add(reason);}
                public void onEnded(){fail("Unexpected ended");}
            });
            java.lang.reflect.Method submit=LibVlcVodBackend.class.getDeclaredMethod("submit",Runnable.class);
            submit.setAccessible(true);
            Runnable failure=()->{throw new IllegalStateException("test startup");};
            submit.invoke(backend,failure);
            queued.remove(0).run();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(java.util.Collections.singletonList("IllegalStateException"),failures);
            submit.invoke(backend,failure);
            queued.remove(0).run();
            backend.revoke();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(java.util.Collections.singletonList("IllegalStateException"),failures);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test public void revokedBackendRejectsQueuedStartAndListenerReinstallation() throws Exception {
        List<Runnable> queued=new ArrayList<>();
        ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()){
            @Override public void execute(Runnable action){queued.add(action);}
        };
        try {
            LibVlcVodBackend backend=new LibVlcVodBackend(RuntimeEnvironment.getApplication(),null,worker);
            VodPlaybackBackend.Listener listener=new VodPlaybackBackend.Listener(){
                public void onReady(){fail("Revoked backend delivered ready");}
                public void onVideoOutput(boolean available){fail("Revoked backend delivered video");}
                public void onFailure(String reason){fail("Revoked backend delivered failure");}
                public void onEnded(){fail("Revoked backend delivered ended");}
            };
            backend.setListener(listener);
            backend.play("https://example.invalid/video");
            assertEquals(1,queued.size());
            backend.revoke();
            backend.setListener(listener);
            queued.remove(0).run(); // must return before touching JNI
            backend.play("https://example.invalid/other");
            backend.pause();
            backend.resume();
            backend.setVideoScaleMode(1);
            assertTrue(queued.isEmpty());
            assertFalse(backend.isPlaying());
            assertFalse(backend.seekToMs(100L));
            Field callback=LibVlcVodBackend.class.getDeclaredField("listener");
            callback.setAccessible(true);
            assertNull(callback.get(backend));
            Field engine=LibVlcVodBackend.class.getDeclaredField("engine");
            engine.setAccessible(true);
            Field nativeCallback=LibVlcPlaybackEngine.class.getDeclaredField("listener");
            nativeCallback.setAccessible(true);
            assertNull(nativeCallback.get(engine.get(backend)));
        } finally {
            worker.shutdownNow();
        }
    }
}
