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
        assertEquals(4L,r.sampleRequests);
        assertEquals(bytes(MASTER).length+bytes(media("high")).length+2L*transportSample().length,r.sampleBytes);
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

    @Test public void firstRequest429IsOriginPressureNotInvalidMedia()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),StandardCharsets.US_ASCII));
                    assertTrue(reader.readLine().startsWith("GET /live.m3u8 "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
                    out.flush();
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/live.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(12,TimeUnit.SECONDS);
            assertFalse(result.valid);
            assertTrue(result.transientNetworkFailure);
            assertTrue(result.firstOriginPressure);
            assertEquals(1L,result.sampleRequests);
            assertEquals(0L,result.sampleBytes);
            assertFalse(result.firstRequestFailed);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void idleDirectTsUsesProvisionalScreeningWithoutWaitingForFullBody()throws Exception{
        CountDownLatch releaseTail=new CountDownLatch(1);
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),StandardCharsets.US_ASCII));
                    assertTrue(reader.readLine().startsWith("GET /live.ts "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    byte[] sample=new byte[4096];
                    for(int i=0;i<8;i++){
                        int at=i*188;
                        sample[at]=0x47;sample[at+2]=0x20;sample[at+3]=0x10;
                    }
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n"
                            +"Content-Length: 32768\r\nConnection: close\r\n\r\n"));
                    out.write(sample);out.flush();
                    assertTrue(releaseTail.await(12,TimeUnit.SECONDS));
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result;
            try{
                result=SourceUpdateWorker.probe("http://127.0.0.1:"+server.getLocalPort()+"/live.ts",
                        SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            }finally{releaseTail.countDown();}
            served.get(15,TimeUnit.SECONDS);
            assertTrue(result.valid);
            assertFalse(result.socketTimedOut);
            assertEquals(350,result.score);
            assertTrue(result.provisional);
            assertEquals(1L,result.sampleRequests);
            assertEquals(4096L,result.sampleBytes);
        }finally{releaseTail.countDown();serverThread.shutdownNow();}
    }

    @Test public void sameDirectStreamFullVersusInitialUsesOneEighthBodyBytes()throws Exception{
        byte[] body=new byte[32768];
        for(int at=0;at+188<=body.length;at+=188){
            body[at]=0x47;body[at+2]=0x20;body[at+3]=0x10;
        }
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                for(int attempt=0;attempt<2;attempt++){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(
                                socket.getInputStream(),StandardCharsets.US_ASCII));
                        assertTrue(reader.readLine().startsWith("GET /same.ts "));
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                        OutputStream out=socket.getOutputStream();
                        out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n"
                                +"Content-Length: 32768\r\nConnection: close\r\n\r\n"));
                        out.write(body,0,1379);out.flush();
                        // The fast reader can close after its bounded prefix.
                        try{out.write(body,1379,body.length-1379);out.flush();}
                        catch(SocketException closed){if(attempt==0)throw closed;}
                    }
                }
                return null;
            });
            String url="http://127.0.0.1:"+server.getLocalPort()+"/same.ts";
            SourceUpdateWorker.ProbeResult full=SourceUpdateWorker.probe(url,
                    SystemClock.elapsedRealtime()+15000L,false,()->false,false);
            SourceUpdateWorker.ProbeResult screen=SourceUpdateWorker.probe(url,
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertTrue(full.valid);assertTrue(screen.valid);
            assertFalse(full.provisional);assertTrue(screen.provisional);
            assertEquals(1L,full.sampleRequests);assertEquals(1L,screen.sampleRequests);
            assertEquals(32768L,full.sampleBytes);assertEquals(4096L,screen.sampleBytes);
            assertEquals(full.sampleBytes,8L*screen.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void idleSiblingFallbackReusesClearFirstSegmentButChecksDistinctSecond()throws Exception{
        String[] expected={"/master.m3u8","/high.m3u8","/shared.ts","/high2.ts",
                "/low.m3u8","/low2.ts"};
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
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
                        else if("/high.m3u8".equals(path))body=bytes(media("high").replace("/high1.ts","/shared.ts"));
                        else if("/low.m3u8".equals(path))body=bytes(media("low").replace("/low1.ts","/shared.ts"));
                        else if("/high2.ts".equals(path))body=bytes("not media");
                        else body=transportSample();
                        OutputStream out=socket.getOutputStream();
                        out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
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
            assertEquals(6L,result.sampleRequests);
            assertEquals(bytes(MASTER).length+bytes(media("high").replace("/high1.ts","/shared.ts")).length
                    +bytes(media("low").replace("/low1.ts","/shared.ts")).length
                    +bytes("not media").length+2L*transportSample().length,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void directNullOnlyStreamDoesNotPassAfterFullFallback()throws Exception{
        byte[] body=new byte[188*30];
        for(int at=0;at<body.length;at+=188){
            body[at]=0x47;body[at+1]=0x1f;body[at+2]=(byte)0xff;body[at+3]=0x10;
        }
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),StandardCharsets.US_ASCII));
                    String start=reader.readLine();
                    assertNotNull(start);
                    assertTrue(start,start.startsWith("GET /null.ts "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n"
                            +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                    out.write(body);out.flush();
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/null.ts",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertFalse(result.valid);
            assertFalse(result.provisional);
            assertFalse(result.socketTimedOut);
            assertEquals(1L,result.sampleRequests);
            assertEquals((long)body.length,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void cappedManifestIsInconclusiveWithoutDependentMediaRequests()throws Exception{
        byte[] body=new byte[131073];
        Arrays.fill(body,(byte)' ');
        byte[] prefix=bytes(media("high"));
        System.arraycopy(prefix,0,body,0,prefix.length);
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),StandardCharsets.US_ASCII));
                    String start=reader.readLine();
                    assertNotNull(start);
                    assertTrue(start,start.startsWith("GET /capped.m3u8 "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n"
                            +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                    out.write(body);out.flush();
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/capped.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertFalse(result.valid);
            assertTrue(result.incompleteManifest);
            assertTrue(result.inconclusive());
            assertFalse(result.socketTimedOut);
            assertFalse(result.transientNetworkFailure);
            assertEquals(1L,result.sampleRequests);
            assertEquals(131073L,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void largerConfirmedManifestUsesSameRequestAndValidatesBothSegments()throws Exception{
        byte[] playlist=new byte[40000];
        Arrays.fill(playlist,(byte)' ');
        byte[] marker=bytes("#EXTM3U\n#");
        byte[] segments=bytes("\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n/high1.ts\n#EXTINF:5,\n/high2.ts\n");
        System.arraycopy(marker,0,playlist,0,marker.length);
        System.arraycopy(segments,0,playlist,playlist.length-segments.length,segments.length);
        String[] expected={"/large.m3u8","/high1.ts","/high2.ts"};
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                for(String path:expected){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                        String start=reader.readLine();
                        assertNotNull(start);
                        assertTrue(start,start.startsWith("GET "+path+" "));
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                        byte[] body="/large.m3u8".equals(path)?playlist:transportSample();
                        OutputStream out=socket.getOutputStream();
                        out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                        out.write(body);out.flush();
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/large.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertTrue(result.valid);
            assertFalse(result.inconclusive());
            assertEquals(3L,result.sampleRequests);
            assertEquals(40000L+2L*transportSample().length,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void nonzeroHttpRangeIsUnconfirmedBeforeAnyMediaRead()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),StandardCharsets.US_ASCII));
                    String start=reader.readLine();
                    assertNotNull(start);
                    assertTrue(start,start.startsWith("GET /wrong.ts "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    byte[] body=transportSample();
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp2t\r\n"
                            +"Content-Range: bytes 188-563/1000\r\nContent-Length: "+body.length
                            +"\r\nConnection: close\r\n\r\n"));
                    out.write(body);out.flush();
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/wrong.ts",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertFalse(result.valid);
            assertTrue(result.incompleteSample);
            assertTrue(result.inconclusive());
            assertEquals(1L,result.sampleRequests);
            assertEquals(0L,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }
}
