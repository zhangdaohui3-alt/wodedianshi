package com.wodedianshi.tv;

import android.app.Application;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise the actual worker's HTTP preflight, not a separate model of the loop. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class HlsWorkerProbeIntegrationTest {
    private static final String MASTER="#EXTM3U\n"
            +"#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n/high.m3u8\n"
            +"#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360\n/low.m3u8\n";
    private static String media(String rendition){
        return "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n/"+rendition+"1.ts\n"
                +"#EXTINF:5,\n/"+rendition+"2.ts\n";
    }
    private static byte[] transportSample(){
        byte[] sample=new byte[376];
        sample[0]=sample[188]=0x47;
        sample[3]=sample[191]=0x10;
        return sample;
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static final String[] HIGH={"/master.m3u8","/high.m3u8","/high1.ts","/high2.ts"};
    private static final String[] BOTH={"/master.m3u8","/high.m3u8","/high1.ts","/high2.ts",
            "/low.m3u8","/low1.ts","/low2.ts"};
    private static final String[] AFTER_TIMEOUT={"/master.m3u8","/high.m3u8",
            "/low.m3u8","/low1.ts","/low2.ts"};

    private SourceUpdateWorker.ProbeResult runProbe(String[] expected,boolean highValid,
            boolean lowValid,boolean pauseOnHighSecond,boolean timeoutHighManifest)throws Exception{
        AtomicBoolean paused=new AtomicBoolean();
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(12000);
            Future<?> served=serverThread.submit(()->{
                for(String path:expected){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(
                                socket.getInputStream(),StandardCharsets.US_ASCII));
                        String start=reader.readLine();
                        assertNotNull(start);
                        assertTrue(start,start.startsWith("GET "+path+" "));
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                        if(timeoutHighManifest&&"/high.m3u8".equals(path)){
                            // Worker read inactivity timeout is 3 s; no data is sent.
                            Thread.sleep(3500L);
                            continue;
                        }
                        byte[] body;
                        if("/master.m3u8".equals(path))body=bytes(MASTER);
                        else if("/high.m3u8".equals(path))body=bytes(media("high"));
                        else if("/low.m3u8".equals(path))body=bytes(media("low"));
                        else if(("/high2.ts".equals(path)&&!highValid)
                                ||("/low2.ts".equals(path)&&!lowValid))body=bytes("not media");
                        else body=transportSample();
                        if(pauseOnHighSecond&&"/high2.ts".equals(path))paused.set(true);
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(body);out.flush();
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,paused::get);
            served.get(15,TimeUnit.SECONDS);
            return result;
        }finally{serverThread.shutdownNow();}
    }

    /** The second segment has valid TS packets at its head, but the server
     * stalls before the rest. The ordinary strict probe must remain unchanged.
     * Use a latch rather than timing assertions to establish the slow tail. */
    private SourceUpdateWorker.ProbeResult probeWithHeldSecondTail(boolean initialIdle)throws Exception{
        CountDownLatch releaseTail=new CountDownLatch(1);
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(12000);
            Future<?> served=serverThread.submit(()->{
                for(String path:new String[]{"/live.m3u8","/live1.ts","/live2.ts"}){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(
                                socket.getInputStream(),StandardCharsets.US_ASCII));
                        String start=reader.readLine();
                        assertNotNull(start);
                        assertTrue(start,start.startsWith("GET "+path+" "));
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                        boolean held="/live2.ts".equals(path);
                        byte[] body="/live.m3u8".equals(path)?bytes(media("live")):
                                held?Arrays.copyOf(transportSample(),8192):transportSample();
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        if(held){
                            out.write(body,0,1024);out.flush();
                            assertTrue("probe must return before tail is released",
                                    releaseTail.await(12,TimeUnit.SECONDS));
                        }else{out.write(body);out.flush();}
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result;
            try{
                result=SourceUpdateWorker.probe("http://127.0.0.1:"+server.getLocalPort()+"/live.m3u8",
                        SystemClock.elapsedRealtime()+15000L,false,()->false,initialIdle);
            }finally{releaseTail.countDown();}
            served.get(15,TimeUnit.SECONDS);
            return result;
        }finally{releaseTail.countDown();serverThread.shutdownNow();}
    }

    @Test public void initialIdleAcceptsConfirmedTsWithoutWaitingForStalledTail()throws Exception{
        SourceUpdateWorker.ProbeResult r=probeWithHeldSecondTail(true);
        assertTrue(r.valid);assertFalse(r.socketTimedOut);
    }

    @Test public void ordinaryProbeRetainsFullSecondSampleRequirement()throws Exception{
        SourceUpdateWorker.ProbeResult r=probeWithHeldSecondTail(false);
        assertFalse(r.valid);assertTrue(r.socketTimedOut);
    }

    @Test public void healthyHighRenditionWinsWithoutProbingLow()throws Exception{
        SourceUpdateWorker.ProbeResult r=runProbe(HIGH,true,true,false,false);
        assertTrue(r.valid);assertFalse(r.socketTimedOut);
        assertEquals(1920,r.width);assertEquals(5000000L,r.bitrate);
    }
    @Test public void staleSecondHighSegmentFallsBackToHealthyLow()throws Exception{
        SourceUpdateWorker.ProbeResult r=runProbe(BOTH,false,true,false,false);
        assertTrue(r.valid);assertEquals(640,r.width);assertEquals(360,r.height);
        assertEquals(1000000L,r.bitrate);
    }
    @Test public void rejectsMasterWhenBothSecondSegmentsFail()throws Exception{
        SourceUpdateWorker.ProbeResult r=runProbe(BOTH,false,false,false,false);
        assertFalse(r.valid);assertFalse(r.socketTimedOut);
    }
    @Test public void viewingPressureStopsBeforeTryingSibling()throws Exception{
        assertFalse(runProbe(HIGH,false,true,true,false).valid);
    }
    @Test public void manifestTimeoutDoesNotHideHealthySibling()throws Exception{
        SourceUpdateWorker.ProbeResult r=runProbe(AFTER_TIMEOUT,false,true,false,true);
        assertTrue(r.valid);assertFalse(r.socketTimedOut);assertEquals(640,r.width);
    }
    @Test public void timeoutRemainsInconclusiveWhenNoSiblingSucceeds()throws Exception{
        SourceUpdateWorker.ProbeResult r=runProbe(AFTER_TIMEOUT,false,false,false,true);
        assertFalse(r.valid);assertTrue(r.socketTimedOut);
    }

    @Test public void idleSiblingFallbackReusesOnlyConfirmedClearInitialization()throws Exception{
        String[] expected={"/master.m3u8","/high.m3u8","/init.mp4","/high1.ts","/high2.ts",
                "/low.m3u8","/low1.ts","/low2.ts"};
        byte[] init={0,0,0,16,'f','t','y','p',0,0,0,0,0,0,0,0,
                0,0,0,16,'m','o','o','v',0,0,0,0,0,0,0,0};
        byte[] fragment={0,0,0,16,'m','o','o','f',0,0,0,0,0,0,0,0,
                0,0,0,9,'m','d','a','t',1};
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(12000);
            Future<?> served=serverThread.submit(()->{
                for(String path:expected){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(
                                socket.getInputStream(),StandardCharsets.US_ASCII));
                        String start=reader.readLine();
                        assertNotNull(start);
                        assertTrue(start,start.startsWith("GET "+path+" "));
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                        byte[] body;
                        if("/master.m3u8".equals(path))body=bytes(MASTER);
                        else if("/high.m3u8".equals(path)||"/low.m3u8".equals(path))
                            body=bytes(media(path.contains("high")?"high":"low")
                                .replace("#EXTM3U\n","#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n"));
                        else if("/init.mp4".equals(path))body=init;
                        else if("/high2.ts".equals(path))body=bytes("not media");
                        else body=fragment;
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(body);out.flush();
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertTrue(result.valid);
            assertEquals(640,result.width);
        }finally{serverThread.shutdownNow();}
    }
}
