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

    @Test public void scoreWriteRetainsExactAliasIdentityAndMigratesLegacyKey()throws Exception{
        String raw="https://EXAMPLE.invalid:443/live?token=AbC";
        String alias="https://example.invalid/live?token=AbC";
        assertEquals(SourceSelectionPolicy.endpointKey(raw),SourceSelectionPolicy.endpointKey(alias));
        String key=StableKey.url(raw),legacy=StableKey.legacyUrl(raw);
        String aliasKey=StableKey.url(alias);
        assertNotEquals(key,aliasKey);
        org.json.JSONObject scores=new org.json.JSONObject().put(legacy,850).put(aliasKey,925);
        SourceUpdateWorker.ProbeResult bad=new SourceUpdateWorker.ProbeResult();
        SourceUpdateWorker.storePreflightScore(scores,key,legacy,bad);
        assertEquals(-500.0,scores.getDouble(key),0.0);
        assertFalse(scores.has(legacy));
        assertEquals(925.0,scores.getDouble(aliasKey),0.0);
        SourceUpdateWorker.ProbeResult good=new SourceUpdateWorker.ProbeResult();
        good.valid=true;good.score=700;
        SourceUpdateWorker.storePreflightScore(scores,key,legacy,good);
        assertEquals(700.0,scores.getDouble(key),0.0);
        assertEquals(925.0,scores.getDouble(aliasKey),0.0);
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

    @Test public void productionRecheckSkipsThrottledSiblingAndReusesOnlyEquivalentPositive()throws Exception{
        ExecutorService servers=Executors.newFixedThreadPool(2);
        try(ServerSocket limited=new ServerSocket();ServerSocket healthy=new ServerSocket()){
            limited.bind(new InetSocketAddress("127.0.0.1",0));
            healthy.bind(new InetSocketAddress(InetAddress.getByName("localhost"),0));
            limited.setSoTimeout(10000);healthy.setSoTimeout(10000);
            Future<?> first=servers.submit(()->{
                replyRecheck(limited,"/limited.ts",429,new byte[0]);
                return null;
            });
            Future<?> second=servers.submit(()->{
                replyRecheck(healthy,"/live.ts?token=One",200,transportSample());
                replyRecheck(healthy,"/live.ts?token=Two",200,transportSample());
                return null;
            });
            String blocked="http://127.0.0.1:"+limited.getLocalPort()+"/limited.ts";
            String sibling="http://127.0.0.1:"+limited.getLocalPort()+"/sibling.ts";
            String base="http://localhost:"+healthy.getLocalPort()+"/live.ts?token=";
            String good=base+"One",alias=good.replace("localhost","LOCALHOST"),distinct=base+"Two";
            List<String> order=Arrays.asList(blocked,good,sibling,alias,distinct);
            LinkedHashSet<String> pending=new LinkedHashSet<>();
            for(String url:order)pending.add(StableKey.url(url));
            SourceUpdateWorker.QualityRecheckSlice result=SourceUpdateWorker.probeQualityRecheckSlice(
                    order,pending,SystemClock.elapsedRealtime()+90000L,()->false);
            first.get(12,TimeUnit.SECONDS);second.get(12,TimeUnit.SECONDS);
            assertEquals(3,result.checked);
            assertEquals(1,result.originDeferred);
            assertEquals(1,result.reused);
            assertEquals(3L,result.sampleRequests);
            assertEquals(2L*transportSample().length,result.sampleBytes);
            assertEquals(new HashSet<>(Arrays.asList(good,alias,distinct)),result.completed.keySet());
            assertSame(result.completed.get(good),result.completed.get(alias));
            assertNotSame(result.completed.get(good),result.completed.get(distinct));
            assertEquals(Arrays.asList(StableKey.url(sibling),StableKey.url(blocked)),new ArrayList<>(pending));
        }finally{servers.shutdownNow();}
    }

    @Test public void dependentMediaPressureDefersNextSavedRouteOnSameOrigin()throws Exception{
        ExecutorService servers=Executors.newFixedThreadPool(2);
        try(ServerSocket limited=new ServerSocket();ServerSocket healthy=new ServerSocket()){
            limited.bind(new InetSocketAddress("127.0.0.1",0));
            healthy.bind(new InetSocketAddress("127.0.0.1",0));
            limited.setSoTimeout(10000);healthy.setSoTimeout(10000);
            Future<?> first=servers.submit(()->{
                replyRecheck(limited,"/master.m3u8",200,bytes(MASTER));
                replyRecheck(limited,"/high.m3u8",503,new byte[0]);
                return null;
            });
            Future<?> second=servers.submit(()->{
                replyRecheck(healthy,"/good.ts",200,transportSample());
                return null;
            });
            String base="http://127.0.0.1:"+limited.getLocalPort();
            String blocked=base+"/master.m3u8",sibling=base+"/sibling.ts";
            String good="http://127.0.0.1:"+healthy.getLocalPort()+"/good.ts";
            List<String> order=Arrays.asList(blocked,sibling,good);
            LinkedHashSet<String> pending=new LinkedHashSet<>();
            for(String url:order)pending.add(StableKey.url(url));
            SourceUpdateWorker.QualityRecheckSlice result=SourceUpdateWorker.probeQualityRecheckSlice(
                    order,pending,SystemClock.elapsedRealtime()+90000L,()->false);
            first.get(12,TimeUnit.SECONDS);second.get(12,TimeUnit.SECONDS);
            assertEquals(2,result.checked);
            assertEquals(1,result.originDeferred);
            assertEquals(3L,result.sampleRequests);
            assertEquals(Collections.singleton(good),result.completed.keySet());
            assertEquals(Arrays.asList(StableKey.url(sibling),StableKey.url(blocked)),new ArrayList<>(pending));
        }finally{servers.shutdownNow();}
    }

    private static void replyRecheck(ServerSocket server,String path,int status,byte[] body)throws Exception{
        try(Socket socket=server.accept()){
            socket.setSoTimeout(5000);
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String request=reader.readLine();
            assertNotNull(request);assertTrue(request,request.startsWith("GET "+path+" "));
            for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
            OutputStream out=socket.getOutputStream();
            out.write(bytes("HTTP/1.1 "+status+(status==200?" OK":" Too Many Requests")
                    +"\r\nContent-Type: application/octet-stream\r\nContent-Length: "+body.length
                    +"\r\nConnection: close\r\n\r\n"));
            out.write(body);out.flush();
        }
    }

    @Test public void productionRecheckExtendsHealthyBatchThenStopsAtNewPressure()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                for(int i=0;i<48;i++)replyRecheck(server,"/live"+i+".ts",200,transportSample());
                replyRecheck(server,"/live48.ts",429,new byte[0]);
                return null;
            });
            List<String> order=new ArrayList<>();
            LinkedHashSet<String> pending=new LinkedHashSet<>();
            for(int i=0;i<96;i++){
                String url="http://127.0.0.1:"+server.getLocalPort()+"/live"+i+".ts";
                order.add(url);pending.add(StableKey.url(url));
            }
            SourceUpdateWorker.QualityRecheckSlice result=SourceUpdateWorker.probeQualityRecheckSlice(
                    order,pending,SystemClock.elapsedRealtime()+90000L,()->false);
            served.get(12,TimeUnit.SECONDS);
            assertTrue(result.extended);
            assertEquals(49,result.checked);
            assertEquals(48,result.completed.size());
            assertEquals(49L,result.sampleRequests);
            assertEquals(48L*transportSample().length,result.sampleBytes);
            assertEquals(48,pending.size());
            assertEquals(StableKey.url(order.get(49)),pending.iterator().next());
            assertTrue(pending.contains(StableKey.url(order.get(48))));
        }finally{serverThread.shutdownNow();}
    }

    @Test public void qualityRetryReusesFreshEvidenceOnlyForSameCatalogueVersion()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                replyRecheck(server,"/retry.ts",200,transportSample());
                replyRecheck(server,"/retry.ts",200,transportSample());
                return null;
            });
            String url="http://127.0.0.1:"+server.getLocalPort()+"/retry.ts";
            List<String> order=Collections.singletonList(url);
            String key=StableKey.url(url);
            LinkedHashSet<String> pending=new LinkedHashSet<>(Collections.singleton(key));
            SourceUpdateWorker.QualityRecheckSlice first=SourceUpdateWorker.probeQualityRecheckSlice(
                    order,pending,SystemClock.elapsedRealtime()+90000L,()->false,1234567L);
            assertEquals(1,first.checked);
            assertTrue(pending.isEmpty());
            // Simulate transaction rollback: the durable key remains pending.
            pending.add(key);
            SourceUpdateWorker.QualityRecheckSlice retry=SourceUpdateWorker.probeQualityRecheckSlice(
                    order,pending,SystemClock.elapsedRealtime()+90000L,()->false,1234567L);
            assertEquals(0,retry.checked);assertEquals(1,retry.reused);
            assertEquals(0L,retry.sampleRequests);assertTrue(pending.isEmpty());
            pending.add(key);
            SourceUpdateWorker.QualityRecheckSlice changed=SourceUpdateWorker.probeQualityRecheckSlice(
                    order,pending,SystemClock.elapsedRealtime()+90000L,()->false,1234568L);
            assertEquals(1,changed.checked);assertEquals(0,changed.reused);
            served.get(12,TimeUnit.SECONDS);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void checkpointKeepsCompletedBatchWhenPlaybackInterruptsNextBatch()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        AtomicBoolean paused=new AtomicBoolean();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            int batch=SourceUpdateWorker.QUALITY_CHECKPOINT_SIZE;
            Future<?> served=serverThread.submit(()->{
                for(int i=0;i<batch;i++)replyRecheck(server,"/checkpoint"+i+".ts",200,transportSample());
                return null;
            });
            List<String> order=new ArrayList<>();
            LinkedHashSet<String> pending=new LinkedHashSet<>();
            for(int i=0;i<batch+4;i++){
                String url="http://127.0.0.1:"+server.getLocalPort()+"/checkpoint"+i+".ts";
                order.add(url);pending.add(StableKey.url(url));
            }
            List<String> saved=new ArrayList<>();
            LinkedHashSet<String> savedPending=new LinkedHashSet<>();
            try{
                SourceUpdateWorker.probeQualityRecheckSlice(order,pending,
                        SystemClock.elapsedRealtime()+90000L,paused::get,null,completed->{
                    assertEquals(batch,completed.size());
                    saved.addAll(completed.keySet());savedPending.addAll(pending);
                    paused.set(true);
                });
                fail("expected playback cancellation");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            served.get(12,TimeUnit.SECONDS);
            assertEquals(order.subList(0,batch),saved);
            assertEquals(4,savedPending.size());
            for(int i=batch;i<order.size();i++)assertTrue(savedPending.contains(StableKey.url(order.get(i))));
        }finally{serverThread.shutdownNow();}
    }

    @Test public void productionRecheckStopsBeforeAnyNetworkWhenPlaybackResumes(){
        String url="http://127.0.0.1:1/never.ts";
        LinkedHashSet<String> pending=new LinkedHashSet<>(Collections.singleton(StableKey.url(url)));
        try{
            SourceUpdateWorker.probeQualityRecheckSlice(Collections.singletonList(url),pending,
                    SystemClock.elapsedRealtime()+90000L,()->true);
            fail("expected playback cancellation");
        }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
        assertEquals(Collections.singleton(StableKey.url(url)),pending);
    }

    @Test public void idleRenditionFallbackReusesConfirmedFirstAsDistinctSecond()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            String low=media("low").replace("/low2.ts","/high1.ts");
            Future<?> served=serverThread.submit(()->{
                replyRecheck(server,"/master.m3u8",200,bytes(MASTER));
                replyRecheck(server,"/high.m3u8",200,bytes(media("high")));
                replyRecheck(server,"/high1.ts",200,transportSample());
                replyRecheck(server,"/high2.ts",200,bytes("not media"));
                replyRecheck(server,"/low.m3u8",200,bytes(low));
                replyRecheck(server,"/low1.ts",200,transportSample());
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(12,TimeUnit.SECONDS);
            assertTrue(result.valid);assertEquals(640,result.width);
            assertEquals(6L,result.sampleRequests);
            assertEquals(bytes(MASTER).length+bytes(media("high")).length+bytes(low).length
                    +2L*transportSample().length+bytes("not media").length,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void emptyLiveWindowIsUnconfirmedButClosedEmptyPlaylistIsNot()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            String live="#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:42\n";
            Future<?> served=serverThread.submit(()->{
                replyRecheck(server,"/live-empty.m3u8",200,bytes(live));
                replyRecheck(server,"/ended-empty.m3u8",200,bytes(live+"#EXT-X-ENDLIST\n"));
                return null;
            });
            String base="http://127.0.0.1:"+server.getLocalPort();
            SourceUpdateWorker.ProbeResult empty=SourceUpdateWorker.probe(base+"/live-empty.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false);
            SourceUpdateWorker.ProbeResult ended=SourceUpdateWorker.probe(base+"/ended-empty.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false);
            served.get(12,TimeUnit.SECONDS);
            assertFalse(empty.valid);assertTrue(empty.inconclusive());
            assertTrue(empty.emptyLiveWindow);assertFalse(empty.incompleteManifest);
            assertTrue(empty.recoverableCoreWindow());assertTrue(empty.deterministicEvidenceLimit());
            assertFalse(ended.valid);assertFalse(ended.inconclusive());
            assertFalse(ended.recoverableCoreWindow());
            assertEquals(1L,empty.sampleRequests);assertEquals(1L,ended.sampleRequests);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void onlyTemporaryCoreWindowQualifiesForExistingBoundedReplay(){
        SourceUpdateWorker.ProbeResult unsupported=new SourceUpdateWorker.ProbeResult();
        unsupported.unsupportedPreflight=true;
        assertFalse(unsupported.recoverableCoreWindow());
        SourceUpdateWorker.ProbeResult capped=new SourceUpdateWorker.ProbeResult();
        capped.incompleteManifest=true;
        assertFalse(capped.recoverableCoreWindow());
        SourceUpdateWorker.ProbeResult pending=new SourceUpdateWorker.ProbeResult();
        pending.emptyLiveWindow=true;
        assertTrue(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(pending.recoverableCoreWindow(),
                0,0,0,1));
        assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(pending.recoverableCoreWindow(),
                96,0,0,1));
        assertFalse(DiscoveryBatchPolicy.shouldRecheckCoreTimeout(pending.recoverableCoreWindow(),
                0,0,1,1));
        int marker=DiscoveryBatchPolicy.noteTransientRecheck(DiscoveryBatchPolicy.NO_TRANSIENT_RECHECK,2);
        assertTrue(DiscoveryBatchPolicy.shouldReplayTransientSuffix(marker,4,4));
        assertEquals(DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED,
                DiscoveryBatchPolicy.noteTransientRecheck(DiscoveryBatchPolicy.TRANSIENT_RECHECK_USED,2));
    }

    @Test public void shortIdentityHttpBodyCannotEstablishCompleteMediaEvidence()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),StandardCharsets.US_ASCII));
                    assertTrue(reader.readLine().startsWith("GET /short.ts "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                            +"Content-Length: 4096\r\nConnection: close\r\n\r\n"));
                    out.write(transportSample());out.flush();
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/short.ts",
                    SystemClock.elapsedRealtime()+15000L,false,()->false);
            served.get(12,TimeUnit.SECONDS);
            assertFalse(result.valid);assertTrue(result.inconclusive());
            assertEquals(1L,result.sampleRequests);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void expiredOrCancelledProbeIsInconclusiveWithoutNetwork(){
        long now=SystemClock.elapsedRealtime();
        SourceUpdateWorker.ProbeResult expired=SourceUpdateWorker.probe(
                "http://127.0.0.1:1/live.ts",now,false,()->false);
        assertFalse(expired.valid);assertTrue(expired.socketTimedOut);
        assertTrue(expired.inconclusive());assertEquals(0L,expired.sampleRequests);
        SourceUpdateWorker.ProbeResult cancelled=SourceUpdateWorker.probe(
                "http://127.0.0.1:1/live.ts",now+15000L,false,()->true);
        assertFalse(cancelled.valid);assertTrue(cancelled.inconclusive());
        assertEquals(0L,cancelled.sampleRequests);
    }

    @Test public void rejectedRangeIsUnconfirmedWithoutUnboundedFallback()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                replyRecheck(server,"/range.ts",416,bytes("range rejected"));
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/range.ts",
                    SystemClock.elapsedRealtime()+15000L,false,()->false);
            served.get(12,TimeUnit.SECONDS);
            assertFalse(result.valid);assertTrue(result.unsupportedPreflight);
            assertTrue(result.inconclusive());assertTrue(result.deterministicEvidenceLimit());
            assertEquals(1L,result.sampleRequests);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void hlsPressureSkipsSameOriginButAllowsIndependentRendition()throws Exception{
        ExecutorService serverThread=Executors.newFixedThreadPool(2);
        try(ServerSocket limited=new ServerSocket();ServerSocket healthy=new ServerSocket()){
            limited.bind(new InetSocketAddress("127.0.0.1",0));
            healthy.bind(new InetSocketAddress("127.0.0.1",0));
            limited.setSoTimeout(10000);healthy.setSoTimeout(10000);
            String master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n/high.m3u8\n"
                    +"#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\n/mid.m3u8\n"
                    +"#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360\nhttp://127.0.0.1:"
                    +healthy.getLocalPort()+"/low.m3u8\n";
            Future<?> first=serverThread.submit(()->{
                replyRecheck(limited,"/master.m3u8",200,bytes(master));
                replyRecheck(limited,"/high.m3u8",429,new byte[0]);
                return null;
            });
            Future<?> other=serverThread.submit(()->{
                replyRecheck(healthy,"/low.m3u8",200,bytes(media("low")));
                replyRecheck(healthy,"/low1.ts",200,transportSample());
                replyRecheck(healthy,"/low2.ts",200,transportSample());
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+limited.getLocalPort()+"/master.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            first.get(12,TimeUnit.SECONDS);other.get(12,TimeUnit.SECONDS);
            assertTrue(result.valid);assertFalse(result.inconclusive());
            assertEquals(640,result.width);assertEquals(5L,result.sampleRequests);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void sameOrigin503DoesNotCauseRepeatedRenditionRequests()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                replyRecheck(server,"/master.m3u8",200,bytes(MASTER));
                replyRecheck(server,"/high.m3u8",503,new byte[0]);
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(12,TimeUnit.SECONDS);
            assertFalse(result.valid);assertTrue(result.inconclusive());
            assertEquals(2L,result.sampleRequests);
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
                for(int attempt=0;attempt<3;attempt++){
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
                        catch(SocketException closed){if(attempt!=1)throw closed;}
                    }
                }
                return null;
            });
            String url="http://127.0.0.1:"+server.getLocalPort()+"/same.ts";
            SourceUpdateWorker.ProbeResult full=SourceUpdateWorker.probe(url,
                    SystemClock.elapsedRealtime()+15000L,false,()->false,false);
            SourceUpdateWorker.ProbeResult screen=SourceUpdateWorker.probe(url,
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            SourceUpdateWorker.ProbeResult saturated=SourceUpdateWorker.probe(url,
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true,false);
            served.get(15,TimeUnit.SECONDS);
            assertTrue(full.valid);assertTrue(screen.valid);
            assertFalse(full.provisional);assertTrue(screen.provisional);
            assertEquals(1L,full.sampleRequests);assertEquals(1L,screen.sampleRequests);
            assertEquals(32768L,full.sampleBytes);assertEquals(4096L,screen.sampleBytes);
            assertEquals(full.sampleBytes,8L*screen.sampleBytes);
            assertTrue(saturated.valid);assertFalse(saturated.provisional);
            assertEquals(1L,saturated.sampleRequests);
            assertEquals(full.sampleBytes,saturated.sampleBytes);
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

    @Test public void failedBoundedRenditionsRemainUnconfirmedWhenMoreExist()throws Exception{
        assertFailedRenditionCap(9,true);
    }

    @Test public void fullyTestedFailedRenditionsRemainDefinitive()throws Exception{
        assertFailedRenditionCap(8,false);
    }

    private void assertFailedRenditionCap(int distinct,boolean unconfirmed)throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                StringBuilder master=new StringBuilder("#EXTM3U\n");
                for(int i=0;i<distinct;i++)master.append("#EXT-X-STREAM-INF:BANDWIDTH=")
                        .append((distinct-i)*100000).append("\n/v").append(i).append(".m3u8\n");
                replyRecheck(server,"/master.m3u8",200,bytes(master.toString()));
                for(int i=0;i<8;i++)replyRecheck(server,"/v"+i+".m3u8",200,bytes("not a playlist"));
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertFalse(result.valid);
            assertEquals(unconfirmed,result.inconclusive());
            assertEquals(unconfirmed,result.unsupportedPreflight);
            assertEquals(9L,result.sampleRequests);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void largerConfirmedManifestUsesSameRequestAndValidatesBothSegments()throws Exception{
        assertLargeManifestUsesSameResponse("/large.m3u8","bytes=0-131072");
    }

    @Test public void extensionlessManifestUsesSameResponseWithoutLargerRange()throws Exception{
        assertLargeManifestUsesSameResponse("/live","bytes=0-32768");
    }

    private void assertLargeManifestUsesSameResponse(String root,String range)throws Exception{
        byte[] playlist=new byte[40000];
        Arrays.fill(playlist,(byte)' ');
        byte[] marker=bytes("#EXTM3U\n#");
        byte[] segments=bytes("\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n/high1.ts\n#EXTINF:5,\n/high2.ts\n");
        System.arraycopy(marker,0,playlist,0,marker.length);
        System.arraycopy(segments,0,playlist,playlist.length-segments.length,segments.length);
        String[] expected={root,"/high1.ts","/high2.ts"};
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
                        String requestedRange=null;
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){
                            if(header.regionMatches(true,0,"Range:",0,6))requestedRange=header.substring(6).trim();
                        }
                        if(root.equals(path))assertEquals(range,requestedRange);
                        byte[] body=root.equals(path)?playlist:transportSample();
                        OutputStream out=socket.getOutputStream();
                        out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                        out.write(body);out.flush();
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+root,
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

    @Test public void unsupportedRangePlaylistIsUnconfirmedWithoutFetchingMedia()throws Exception{
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        byte[] body=bytes("#EXTM3U\n#EXTINF:6,\n#EXT-X-BYTERANGE:376@0\nshared.ts\n");
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
                    assertTrue(start,start.startsWith("GET /range.m3u8 "));
                    for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                    OutputStream out=socket.getOutputStream();
                    out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n"
                            +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                    out.write(body);out.flush();
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/range.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertFalse(result.valid);
            assertTrue(result.unsupportedPreflight);
            assertTrue(result.inconclusive());
            assertTrue(result.deterministicEvidenceLimit());
            assertEquals(1L,result.sampleRequests);
            assertEquals((long)body.length,result.sampleBytes);
            result.transientNetworkFailure=true;
            assertFalse(result.deterministicEvidenceLimit());
        }finally{serverThread.shutdownNow();}
    }

    /** Compare the actual HTTP path over identical mixed fixtures. No wall-clock
     * assertions: network timing in CI is not a device or Internet benchmark. */
    @Test public void mixedBatchFullVersusScreeningPreservesEvidenceAndReducesBodyReads()throws Exception{
        String[] roots={"/direct.ts","/null.ts","/healthy.m3u8","/master.m3u8",
                "/bad.m3u8","/busy.ts","/range.m3u8"};
        String[] fullRequests={"/direct.ts","/null.ts","/healthy.m3u8","/healthy1.ts","/healthy2.ts",
                "/master.m3u8","/high.m3u8","/shared.ts","/high2.ts","/low.m3u8","/shared.ts","/low2.ts",
                "/bad.m3u8","/bad1.ts","/bad2.ts","/busy.ts","/range.m3u8"};
        String[] fastRequests={"/direct.ts","/null.ts","/healthy.m3u8","/healthy1.ts","/healthy2.ts",
                "/master.m3u8","/high.m3u8","/shared.ts","/high2.ts","/low.m3u8","/low2.ts",
                "/bad.m3u8","/bad1.ts","/bad2.ts","/busy.ts","/range.m3u8"};
        byte[] largeTs=new byte[32768],nullTs=new byte[188*30];
        for(int at=0;at+188<=largeTs.length;at+=188){
            largeTs[at]=0x47;largeTs[at+2]=0x20;largeTs[at+3]=0x10;
        }
        for(int at=0;at<nullTs.length;at+=188){
            nullTs[at]=0x47;nullTs[at+1]=0x1f;nullTs[at+2]=(byte)0xff;nullTs[at+3]=0x10;
        }
        Map<String,byte[]> bodies=new HashMap<>();
        bodies.put("/direct.ts",largeTs);bodies.put("/null.ts",nullTs);
        bodies.put("/healthy.m3u8",bytes(media("healthy")));
        bodies.put("/healthy1.ts",largeTs);bodies.put("/healthy2.ts",Arrays.copyOf(largeTs,8192));
        bodies.put("/master.m3u8",bytes(MASTER));
        bodies.put("/high.m3u8",bytes(media("high").replace("/high1.ts","/shared.ts")));
        bodies.put("/low.m3u8",bytes(media("low").replace("/low1.ts","/shared.ts")));
        bodies.put("/shared.ts",largeTs);bodies.put("/high2.ts",bytes("not media"));
        bodies.put("/low2.ts",Arrays.copyOf(largeTs,8192));
        bodies.put("/bad.m3u8",bytes(media("bad")));bodies.put("/bad1.ts",transportSample());
        bodies.put("/bad2.ts",bytes("not media"));
        bodies.put("/busy.ts",new byte[0]);
        bodies.put("/range.m3u8",bytes("#EXTM3U\n#EXTINF:6,\n#EXT-X-BYTERANGE:376@0\nshared.ts\n"));
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                for(int mode=0;mode<2;mode++){
                    for(String path:mode==0?fullRequests:fastRequests){
                        try(Socket socket=server.accept()){
                            socket.setSoTimeout(5000);
                            BufferedReader reader=new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(),StandardCharsets.US_ASCII));
                            String start=reader.readLine();
                            assertNotNull(start);assertTrue(start,start.startsWith("GET "+path+" "));
                            for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                            byte[] body=bodies.get(path);
                            assertNotNull(body);
                            OutputStream out=socket.getOutputStream();
                            String status="/busy.ts".equals(path)?"429 Too Many Requests":"200 OK";
                            out.write(bytes("HTTP/1.1 "+status+"\r\nContent-Type: application/octet-stream\r\n"
                                    +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                            try{out.write(body);out.flush();}
                            catch(SocketException closed){
                                if(mode==0||!Arrays.asList("/direct.ts","/healthy1.ts","/healthy2.ts","/shared.ts","/low2.ts").contains(path))throw closed;
                            }
                        }
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult[][] observed=new SourceUpdateWorker.ProbeResult[2][roots.length];
            long[] requests=new long[2],bodyBytes=new long[2];
            for(int mode=0;mode<2;mode++){
                for(int i=0;i<roots.length;i++){
                    SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                            "http://127.0.0.1:"+server.getLocalPort()+roots[i],
                            SystemClock.elapsedRealtime()+15000L,false,()->false,mode==1);
                    observed[mode][i]=result;
                    requests[mode]+=result.sampleRequests;bodyBytes[mode]+=result.sampleBytes;
                }
            }
            served.get(15,TimeUnit.SECONDS);
            boolean[] valid={true,false,true,true,false,false,false};
            boolean[] uncertain={false,false,false,false,false,true,true};
            for(int mode=0;mode<2;mode++){
                for(int i=0;i<roots.length;i++){
                    assertEquals(roots[i],valid[i],observed[mode][i].valid);
                    assertEquals(roots[i],uncertain[i],observed[mode][i].inconclusive());
                }
                assertEquals(640,observed[mode][3].width);
                assertTrue(observed[mode][5].firstOriginPressure);
                assertTrue(observed[mode][6].unsupportedPreflight);
            }
            assertFalse(observed[0][0].provisional);assertTrue(observed[1][0].provisional);
            assertFalse(observed[0][2].provisional);assertTrue(observed[1][2].provisional);
            assertFalse(observed[0][3].provisional);assertTrue(observed[1][3].provisional);
            assertFalse(observed[1][4].provisional);
            assertEquals(17L,requests[0]);assertEquals(16L,requests[1]);
            long expectedFullBytes=0;
            for(String path:fullRequests)expectedFullBytes+=bodies.get(path).length;
            assertEquals(expectedFullBytes,bodyBytes[0]);
            // A fragmented confirmation read can consume up to 375+1024
            // bytes before finding two complete packets. Use that safe bound.
            assertTrue("full="+bodyBytes[0]+" fast="+bodyBytes[1],
                    bodyBytes[0]-bodyBytes[1]>=3L*28672L+32768L+2L*(8192L-1399L));
            System.out.println("mixed_probe_comparison full_requests="+requests[0]
                    +" fast_requests="+requests[1]+" full_body_bytes="+bodyBytes[0]
                    +" fast_body_bytes="+bodyBytes[1]);
        }finally{serverThread.shutdownNow();}
    }

    @Test public void singleSegmentPlaylistDoesNotUseProvisionalFirstScreen()throws Exception{
        byte[] manifest=bytes("#EXTM3U\n#EXTINF:6,\n/only.ts\n");
        byte[] media=new byte[32768];
        for(int at=0;at+188<=media.length;at+=188){
            media[at]=0x47;media[at+2]=0x20;media[at+3]=0x10;
        }
        ExecutorService serverThread=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress("127.0.0.1",0));
            server.setSoTimeout(10000);
            Future<?> served=serverThread.submit(()->{
                for(String path:new String[]{"/single.m3u8","/only.ts"}){
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                        String start=reader.readLine();assertNotNull(start);
                        assertTrue(start,start.startsWith("GET "+path+" "));
                        for(String header;(header=reader.readLine())!=null&&!header.isEmpty();){}
                        byte[] body=path.endsWith(".m3u8")?manifest:media;
                        OutputStream out=socket.getOutputStream();
                        out.write(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                                +"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n"));
                        out.write(body);out.flush();
                    }
                }
                return null;
            });
            SourceUpdateWorker.ProbeResult result=SourceUpdateWorker.probe(
                    "http://127.0.0.1:"+server.getLocalPort()+"/single.m3u8",
                    SystemClock.elapsedRealtime()+15000L,false,()->false,true);
            served.get(15,TimeUnit.SECONDS);
            assertTrue(result.valid);assertFalse(result.provisional);
            assertEquals(2L,result.sampleRequests);
            assertEquals((long)manifest.length+media.length,result.sampleBytes);
        }finally{serverThread.shutdownNow();}
    }
}
