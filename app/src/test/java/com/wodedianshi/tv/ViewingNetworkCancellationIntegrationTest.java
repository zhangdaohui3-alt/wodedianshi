package com.wodedianshi.tv;

import android.app.Application;
import android.content.Context;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Loopback HTTP exercises actual directory IO without public providers.
 * Logical health time is injected; no claims about real decoder or network speed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class ViewingNetworkCancellationIntegrationTest {
    @Test public void revokedViewingLeaseCancelsDownloadAndFreshLeaseCanRetry()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ExecutorService network=Executors.newSingleThreadExecutor();
        ExecutorService handlers=Executors.newCachedThreadPool();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CountDownLatch firstBody=new CountDownLatch(1),allowTail=new CountDownLatch(1);
        AtomicBoolean blockFirst=new AtomicBoolean(true);
        AtomicLong healthTime=new AtomicLong(9000L);
        byte[] body=("#EXTM3U\n#EXTINF:-1,CCTV-1\nhttps://example.invalid/one\n"
                +"#EXTINF:-1,CCTV-2\nhttps://example.invalid/two\n").getBytes(StandardCharsets.UTF_8);
        server.setExecutor(handlers);
        server.createContext("/directory",exchange->{
            try{
                exchange.sendResponseHeaders(200,body.length);
                if(blockFirst.getAndSet(false)){
                    exchange.getResponseBody().write(body,0,8);
                    exchange.getResponseBody().flush();
                    firstBody.countDown();
                    if(!allowTail.await(5,TimeUnit.SECONDS))return;
                    exchange.getResponseBody().write(body,8,body.length-8);
                }else exchange.getResponseBody().write(body);
            }catch(IOException cancelled){/* client may have closed the stream */}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });
        server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/directory";
        try{
            SourceUpdateActivityPolicy.setActivityActive(true);
            SourceUpdateActivityPolicy.setPlaybackActive(true);
            SourceUpdateActivityPolicy.reportStableLive(1000L);
            SourceUpdateActivityPolicy.reportStableLive(9000L);
            long oldGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(healthTime.get(),oldGeneration));
            java.util.function.BooleanSupplier gate=SourceProbeTimePolicy.latchPause(()->
                    !SourceUpdateActivityPolicy.permitsInitialPlaybackWork(healthTime.get(),oldGeneration));
            Future<File> attempt=network.submit(()->PlaylistFeedCache.obtainSmallInitialDuringViewing(
                    context,url,false,gate));
            assertTrue("server entered response body",firstBody.await(5,TimeUnit.SECONDS));
            // A player pressure event revokes the old worker. Restore healthy
            // observations before the body is released, exercising epoch isolation.
            SourceUpdateActivityPolicy.notePressure(10000L);
            SourceUpdateActivityPolicy.reportStableLive(11000L);
            SourceUpdateActivityPolicy.reportStableLive(19000L);
            healthTime.set(19000L);
            long newGeneration=SourceUpdateActivityPolicy.stableRevocationGeneration();
            assertTrue(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(healthTime.get(),newGeneration));
            assertFalse(SourceUpdateActivityPolicy.permitsInitialPlaybackWork(healthTime.get(),oldGeneration));
            allowTail.countDown();
            try{
                attempt.get(8,TimeUnit.SECONDS);
                fail("revoked download must not return a directory");
            }catch(ExecutionException expected){
                assertTrue("expected cooperative pause, got "+expected.getCause(),
                        expected.getCause() instanceof SourceProbeTimePolicy.PausedException);
            }
            File directory=new File(context.getFilesDir(),"playlist-feed-cache");
            File[] files=directory.listFiles();
            assertNotNull(files);
            for(File file:files)
                assertFalse("cancelled body or temporary file survived: "+file.getName(),
                        file.getName().endsWith(".m3u")||file.getName().endsWith(".tmp"));
            assertTrue(gate.getAsBoolean());
            java.util.function.BooleanSupplier nextGate=SourceProbeTimePolicy.latchPause(()->
                    !SourceUpdateActivityPolicy.permitsInitialPlaybackWork(healthTime.get(),newGeneration));
            File result=network.submit(()->PlaylistFeedCache.obtainSmallInitialDuringViewing(
                    context,url,false,nextGate)).get(8,TimeUnit.SECONDS);
            assertNotNull(result);
            assertArrayEquals(body,Files.readAllBytes(result.toPath()));
            assertFalse(nextGate.getAsBoolean());
        }finally{
            allowTail.countDown();
            server.stop(0);
            network.shutdownNow();
            handlers.shutdownNow();
            SourceUpdateActivityPolicy.setPlaybackActive(false);
            SourceUpdateActivityPolicy.setActivityActive(false);
        }
    }
}
