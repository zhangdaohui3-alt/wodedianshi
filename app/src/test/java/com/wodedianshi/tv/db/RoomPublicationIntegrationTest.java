package com.wodedianshi.tv.db;

import android.app.Application;
import android.content.Context;
import com.wodedianshi.tv.SourceProbeTimePolicy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Exercise the production Room writer and DAO against a private on-disk
 * SQLite database, on an Android 9 host-side sandbox, never a device database. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class RoomPublicationIntegrationTest {
    private static final String DB_NAME="mytv_catalogue_clean_v1.db";
    private static final String PERCENT="source_initial_progress_percent";

    private interface DatabaseTest { void run(Context context)throws Exception; }
    private void isolated(DatabaseTest test)throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            worker.submit(()->{
                closeProvider();
                context.deleteDatabase(DB_NAME);
                try { test.run(context); }
                finally { closeProvider();context.deleteDatabase(DB_NAME); }
                return null;
            }).get(180,TimeUnit.SECONDS);
        }finally{worker.shutdownNow();}
    }

    // Test-only process-restart boundary. Do not add a mutable test hook to production.
    private static void closeProvider()throws Exception {
        Field field=RoomDatabaseProvider.class.getDeclaredField("instance");
        field.setAccessible(true);
        AppDatabase db=(AppDatabase)field.get(null);
        if(db!=null)db.close();
        field.set(null,null);
    }

    private static JSONObject channel(String name,String url)throws Exception {
        return new JSONObject().put("name",name).put("url",url)
                .put("group","央视").put("sources",new JSONArray().put(url));
    }

    @Test public void lightweightRouteScanMatchesPagedCatalogueAndRejectsChangedVersion()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray rows=new JSONArray();
            for(int i=0;i<205;i++){
                String primary="https://example.invalid/primary/"+i;
                String backup="https://example.invalid/backup/"+i;
                rows.put(channel("Route "+i,primary).put("sources",new JSONArray().put(primary).put(backup)));
            }
            RoomCatalogueWriter.writeSnapshotWithProgress(context,rows,base,false,
                    ()->false,new AppStateEditor(context));
            RoomCatalogueReader.Snapshot full=RoomCatalogueReader.read(context);
            java.util.Set<String> expected=new java.util.HashSet<>();
            for(RoomCatalogueReader.ChannelRecord record:full.channels){
                expected.add(record.url);expected.addAll(record.sources);
            }
            java.util.Set<String> actual=new java.util.HashSet<>();
            assertEquals(Long.valueOf(full.catalogueVersion),
                    RoomCatalogueReader.visitRouteUrls(context,url->{actual.add(url);return false;},()->false));
            assertEquals(expected,actual);
            assertEquals(410,actual.size());
            java.util.concurrent.atomic.AtomicInteger visited=new java.util.concurrent.atomic.AtomicInteger();
            assertEquals(Long.valueOf(full.catalogueVersion),
                    RoomCatalogueReader.visitRouteUrls(context,url->{visited.incrementAndGet();return true;},()->false));
            assertEquals(1,visited.get());
            ChannelDao dao=RoomDatabaseProvider.get(context).channelDao();
            try{
                RoomCatalogueReader.visitRouteUrls(context,url->{
                    dao.touchCatalogueCheckpoint(MasterCatalogueRepository.MIGRATION_KEY,full.catalogueVersion+1);
                    return true;
                },()->false);
                fail("even an early match must reject changed catalogue");
            }catch(RoomCatalogueReader.StaleSnapshotException expectedConflict){}
            assertEquals(205,RoomCatalogueReader.read(context).channelCount);
        });
    }

    @Test public void twentyThousandRoutesMatchFullSnapshotWithEarlyExitAndCancellation()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray rows=new JSONArray();
            java.util.Set<String> expected=new java.util.HashSet<>();
            for(int i=0;i<2500;i++){
                String primary="https://example.invalid/route/"+i+"/0";
                JSONArray sources=new JSONArray();
                for(int j=0;j<8;j++){
                    String url="https://example.invalid/route/"+i+"/"+j;
                    sources.put(url);expected.add(url);
                }
                rows.put(channel("Scale channel "+i,primary).put("sources",sources));
            }
            RoomCatalogueWriter.writeSnapshotWithProgress(context,rows,base,false,
                    ()->false,new AppStateEditor(context));
            closeProvider();
            long fullStart=System.nanoTime();
            RoomCatalogueReader.Snapshot full=RoomCatalogueReader.read(context);
            java.util.Set<String> hydrated=new java.util.HashSet<>();
            for(RoomCatalogueReader.ChannelRecord row:full.channels){
                hydrated.add(row.url);hydrated.addAll(row.sources);
            }
            long fullNanos=System.nanoTime()-fullStart;
            assertEquals(2500,full.channelCount);
            assertEquals(expected,hydrated);
            java.util.Set<String> actual=new java.util.HashSet<>();
            java.util.concurrent.atomic.AtomicInteger visited=new java.util.concurrent.atomic.AtomicInteger();
            long routeStart=System.nanoTime();
            Long version=RoomCatalogueReader.visitRouteUrls(context,url->{
                visited.incrementAndGet();actual.add(url);return false;
            },()->false);
            long routeNanos=System.nanoTime()-routeStart;
            assertEquals(Long.valueOf(full.catalogueVersion),version);
            assertEquals(expected,actual);assertEquals(20000,visited.get());
            visited.set(0);
            long earlyStart=System.nanoTime();
            assertEquals(version,RoomCatalogueReader.visitRouteUrls(context,url->{
                visited.incrementAndGet();return true;
            },()->false));
            long earlyNanos=System.nanoTime()-earlyStart;
            assertEquals(1,visited.get());
            visited.set(0);
            try{
                RoomCatalogueReader.visitRouteUrls(context,url->{
                    visited.incrementAndGet();return false;
                },()->visited.get()>=80);
                fail("cancelled scan must not report a complete membership read");
            }catch(SourceProbeTimePolicy.PublicationPausedException expectedPause){}
            assertTrue(visited.get()>=80);assertTrue(visited.get()<20000);
            System.out.println("room_route_scale channels=2500 routes=20000 full_read_ms="
                    +fullNanos/1000000L+" route_scan_ms="+routeNanos/1000000L
                    +" early_exit_ms="+earlyNanos/1000000L+" cancelled_after="+visited.get());
            // Both paths have now been warmed. Alternate order and compare
            // equivalent exact membership work on each pass; do not assert
            // wall-clock speed on shared CI hardware.
            long[] fullWarm=new long[6],routeWarm=new long[6];
            for(int pass=0;pass<6;pass++){
                for(int order=0;order<2;order++){
                    boolean hydrate=(pass+order)%2==0;
                    java.util.Set<String> keys=new java.util.HashSet<>();
                    long start=System.nanoTime();
                    if(hydrate){
                        RoomCatalogueReader.Snapshot snapshot=RoomCatalogueReader.read(context);
                        assertEquals(version,Long.valueOf(snapshot.catalogueVersion));
                        for(RoomCatalogueReader.ChannelRecord row:snapshot.channels){
                            keys.add(row.url);keys.addAll(row.sources);
                        }
                        fullWarm[pass]=System.nanoTime()-start;
                    }else{
                        assertEquals(version,RoomCatalogueReader.visitRouteUrls(context,url->{
                            keys.add(url);return false;
                        },()->false));
                        routeWarm[pass]=System.nanoTime()-start;
                    }
                    assertEquals(expected,keys);
                }
            }
            java.util.Arrays.sort(fullWarm);java.util.Arrays.sort(routeWarm);
            System.out.println("room_route_balanced rounds=6 full_median_us="
                    +(fullWarm[2]+fullWarm[3])/2000L+" route_median_us="
                    +(routeWarm[2]+routeWarm[3])/2000L);
            // Match the actual quality-recheck membership workload: exact
            // persistent URL digests, not just raw URL equality in a set.
            java.util.Set<String> pending=new java.util.HashSet<>();
            for(int i=0;i<256;i++)for(int j=0;j<8;j++)
                pending.add(com.wodedianshi.tv.StableKey.url("https://example.invalid/route/"+i+"/"+j));
            assertEquals(2048,pending.size());
            java.util.Set<String> matched=new java.util.HashSet<>();
            visited.set(0);
            long pendingStart=System.nanoTime();
            assertEquals(version,RoomCatalogueReader.visitRouteUrls(context,url->{
                visited.incrementAndGet();
                String key=com.wodedianshi.tv.StableKey.url(url);
                if(pending.contains(key))matched.add(key);
                return matched.size()==pending.size();
            },()->false));
            long pendingMs=(System.nanoTime()-pendingStart)/1000000L;
            assertEquals(pending,matched);
            int earlyVisited=visited.get();
            assertTrue(earlyVisited>=2048);assertTrue(earlyVisited<20000);
            // A missing key must force a complete scan before pruning.
            pending.remove(com.wodedianshi.tv.StableKey.url("https://example.invalid/route/0/0"));
            String missing=com.wodedianshi.tv.StableKey.url("https://example.invalid/missing");
            pending.add(missing);matched.clear();visited.set(0);
            long missingStart=System.nanoTime();
            assertEquals(version,RoomCatalogueReader.visitRouteUrls(context,url->{
                visited.incrementAndGet();
                String key=com.wodedianshi.tv.StableKey.url(url);
                if(pending.contains(key))matched.add(key);
                return matched.size()==pending.size();
            },()->false));
            long missingMs=(System.nanoTime()-missingStart)/1000000L;
            assertEquals(20000,visited.get());
            assertEquals(2047,matched.size());
            assertFalse(matched.contains(missing));
            System.out.println("room_pending_scale pending=2048 matched_early_visited="+earlyVisited
                    +" matched_early_ms="+pendingMs+" missing_visited="+visited.get()+" missing_ms="+missingMs);
            assertEquals(version,Long.valueOf(RoomCatalogueReader.read(context).catalogueVersion));
        });
    }

    @Test public void interruptedFinalPublicationRollsBackRowsProgressAndStagingAcrossReopen()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long empty=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray oldRows=new JSONArray().put(channel("CCTV-1","https://example.invalid/old"));
            assertEquals(1,RoomCatalogueWriter.writeSnapshotWithProgress(context,oldRows,empty,false,
                    ()->false,new AppStateEditor(context).putInt(PERCENT,35)));
            long oldVersion=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray nextRows=new JSONArray()
                    .put(channel("CCTV-2","https://example.invalid/new2"))
                    .put(channel("CCTV-3","https://example.invalid/new3"));
            // Model the production incremental commit that owns a durable revision.
            RoomCatalogueBuildStore.updateTouched(context,nextRows,0,
                    new java.util.HashSet<>(java.util.Arrays.asList(0,1)),()->false,
                    new AppStateEditor(context).putString(RoomCatalogueBuildStore.STAGING_REVISION_KEY,"test-revision"));
            AppDatabase db=RoomDatabaseProvider.get(context);
            assertEquals(1,RoomCatalogueReader.read(context).channelCount);
            assertEquals(2,db.channelDao().countCatalogueBuildRows());
            assertEquals("test-revision",db.appStateDao().getValue(RoomCatalogueBuildStore.STAGING_REVISION_KEY));
            assertEquals("35",db.appStateDao().getValue(PERCENT));
            assertNull(db.appStateDao().getValue("source_full_rebuild_completed_at"));
            // Trigger cancellation only after the writer has changed the rows,
            // checkpoint, staging and progress inside its final transaction.
            try{
                RoomCatalogueWriter.writeSnapshotWithProgress(context,nextRows,oldVersion,true,
                        ()->db.inTransaction()&&"100".equals(db.appStateDao().getValue(PERCENT))
                                &&db.channelDao().countCatalogueBuildRows()==0
                                &&db.appStateDao().getValue(RoomCatalogueBuildStore.STAGING_REVISION_KEY)==null
                                &&db.appStateDao().getValue("source_full_rebuild_completed_at")!=null,
                        new AppStateEditor(context).putInt(PERCENT,100));
                fail("expected cancellation after transactional mutations");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            closeProvider();
            AppDatabase reopened=RoomDatabaseProvider.get(context);
            RoomCatalogueReader.Snapshot old=RoomCatalogueReader.read(context);
            assertNotNull(old);
            assertEquals(oldVersion,old.catalogueVersion);
            assertEquals(1,old.channelCount);
            assertEquals("https://example.invalid/old",old.channels.get(0).url);
            assertEquals("35",reopened.appStateDao().getValue(PERCENT));
            assertEquals(2,reopened.channelDao().countCatalogueBuildRows());
            assertEquals("test-revision",reopened.appStateDao().getValue(RoomCatalogueBuildStore.STAGING_REVISION_KEY));
            assertNull(reopened.appStateDao().getValue("source_full_rebuild_completed_at"));
            assertEquals(2,RoomCatalogueWriter.writeSnapshotWithProgress(context,nextRows,oldVersion,true,
                    ()->false,new AppStateEditor(context).putInt(PERCENT,100)));
            closeProvider();
            AppDatabase published=RoomDatabaseProvider.get(context);
            RoomCatalogueReader.Snapshot result=RoomCatalogueReader.read(context);
            assertNotNull(result);
            assertEquals(2,result.channelCount);
            assertTrue(result.catalogueVersion>oldVersion);
            assertEquals("100",published.appStateDao().getValue(PERCENT));
            assertEquals(0,published.channelDao().countCatalogueBuildRows());
            assertNull(published.appStateDao().getValue(RoomCatalogueBuildStore.STAGING_REVISION_KEY));
            assertNotNull(published.appStateDao().getValue("source_full_rebuild_completed_at"));
            try {
                RoomCatalogueWriter.writeSnapshotWithProgress(context,oldRows,oldVersion,false,
                        ()->false,new AppStateEditor(context).putInt(PERCENT,35));
                fail("stale snapshot must not overwrite newly published catalogue");
            }catch(RoomCatalogueWriter.StaleCatalogueException expected){}
            assertEquals(2,RoomCatalogueReader.read(context).channelCount);
        });
    }

    @Test public void twentyThousandEndpointQueueResumesAfterRollbackAndDatabaseReopen()throws Exception {
        isolated(context->{
            final String owner="integration-round";
            final int total=20001;
            AppDatabase db=RoomDatabaseProvider.get(context);
            db.runInTransaction(()->{
                for(int start=0;start<total;start+=256){
                    List<ManualProbeQueueEntity> rows=new ArrayList<>();
                    for(int i=start;i<Math.min(total,start+256);i++){
                        String url="https://example.invalid/route/"+i;
                        rows.add(new ManualProbeQueueEntity(owner,url,url,"Test "+i,"test","","",i,false));
                    }
                    db.manualProbeQueueDao().insertNew(rows);
                }
            });
            assertEquals(total,db.manualProbeQueueDao().countPending(owner));
            List<String> first=endpoints(db.manualProbeQueueDao().pending(owner,48));
            assertEquals(48,db.manualProbeQueueDao().markProcessed(owner,first));
            List<String> next=endpoints(db.manualProbeQueueDao().pending(owner,48));
            try{
                db.runInTransaction(()->{
                    assertEquals(48,db.manualProbeQueueDao().markProcessed(owner,next));
                    throw new SourceProbeTimePolicy.PublicationPausedException();
                });
                fail("expected rollback");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            closeProvider();
            AppDatabase resumed=RoomDatabaseProvider.get(context);
            ManualProbeQueueDao queue=resumed.manualProbeQueueDao();
            assertEquals(total-48,queue.countPending(owner));
            assertEquals(next,endpoints(queue.pending(owner,48)));
            // Repeated insert must not reset a previously completed endpoint.
            String url=first.get(0);
            queue.insertNew(java.util.Collections.singletonList(
                    new ManualProbeQueueEntity(owner,url,url,"duplicate","test","","",0,false)));
            assertEquals(total,queue.count(owner));
            assertEquals(total-48,queue.countPending(owner));
            int processed=48;
            for(;;){
                List<String> batch=endpoints(queue.pending(owner,48));
                if(batch.isEmpty())break;
                assertEquals(batch.size(),queue.markProcessed(owner,batch));
                processed+=batch.size();
            }
            assertEquals(total,processed);
            closeProvider();
            assertEquals(0,RoomDatabaseProvider.get(context).manualProbeQueueDao().countPending(owner));
            assertEquals(total,RoomDatabaseProvider.get(context).manualProbeQueueDao().count(owner));
        });
    }

    @Test public void manualRefreshPublishesReadyQueueAndIndexedQualityAtomically()throws Exception {
        isolated(context->{
            final String owner="manual-integration";
            final String oldUrl="https://example.invalid/old";
            final String newUrl="https://example.invalid/new";
            final String oldKey=com.wodedianshi.tv.StableKey.url(oldUrl);
            final String newKey=com.wodedianshi.tv.StableKey.url(newUrl);
            final String completed="source_manual_completed_id";
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long empty=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray oldRows=new JSONArray().put(channel("CCTV-1",oldUrl));
            RoomCatalogueWriter.writeSnapshotWithProgress(context,oldRows,empty,true,
                    ()->false,new AppStateEditor(context).putInt(PERCENT,100));
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            // Establish the new-version Room quality base; legacy JSON must
            // neither populate nor override private observations.
            AppDatabase db=RoomDatabaseProvider.get(context);
            String initial=ManualProbeQualityStore.INITIAL_OWNER,publishedOwnerBefore=ManualProbeQualityStore.PUBLISHED_OWNER;
            db.runInTransaction(()->{
                ManualProbeQualityStore.seedInitialInTransaction(db,java.util.Collections.singletonList(
                    new ManualProbeQualityEntity(initial,oldKey,600,2)),()->false);
                ManualProbeQualityStore.publishInitialInTransaction(db,()->false);
            });
            AppStateStore.put(context,"source_probe_scores_v1",new JSONObject().put(oldKey,999).toString());
            RoomCatalogueBuildStore.seedManual(context,oldRows,base,owner,()->false,
                    new AppStateEditor(context).putInt("source_manual_next_feed",0));
            ManualProbeQualityStore.ensure(context,owner,()->false);
            assertNull(db.manualProbeQualityDao().find(owner,oldKey));
            JSONObject inheritedScores=new JSONObject(),inheritedStreaks=new JSONObject();
            ManualProbeQualityStore.loadUrls(context,owner,java.util.Collections.singletonList(oldUrl),
                    new java.util.HashSet<>(),inheritedScores,inheritedStreaks,()->false);
            assertEquals(600,inheritedScores.getInt(oldKey));
            assertEquals(2,inheritedStreaks.getInt(oldKey));
            assertTrue(ManualProbeQualityStore.hasPublished(context));
            JSONArray newRows=new JSONArray().put(channel("CCTV-2",newUrl));
            assertFalse(RoomCatalogueBuildStore.commitManualIndexed(context,owner,1,
                    java.util.Collections.emptyList(),newRows,java.util.Collections.singleton(0),
                    ()->false,new AppStateEditor(context).putInt("source_manual_next_feed",1),
                    java.util.Collections.emptyList(),1,1,0,java.util.Collections.emptyList()));
            assertEquals(2,db.channelDao().countCatalogueBuildRows());
            assertEquals(2,db.manualProbeQueueDao().countPending(owner));
            assertEquals(1,RoomCatalogueReader.read(context).channelCount);
            JSONArray staged=RoomCatalogueBuildStore.readManual(context,owner,()->false);
            try {
                RoomCatalogueWriter.publishManualSnapshot(context,staged,base,owner,()->false,
                        new AppStateEditor(context).putString(completed,owner));
                fail("unfinished queue must block publication");
            }catch(IllegalStateException expected){
                assertEquals("Manual queue has unfinished work",expected.getMessage());
            }
            assertTrue(RoomCatalogueBuildStore.canRunManualQueueOnly(context,owner,1));
            List<String> pending=endpoints(db.manualProbeQueueDao().pending(owner,48));
            assertEquals(2,pending.size());
            List<ManualProbeQualityEntity> quality=java.util.Arrays.asList(
                    new ManualProbeQualityEntity(owner,oldKey,650,null),
                    new ManualProbeQualityEntity(owner,newKey,900,null));
            assertTrue(RoomCatalogueBuildStore.commitManualQueueOnly(context,owner,1,0,
                    pending,quality,()->false,new AppStateEditor(context)));
            closeProvider();
            AppDatabase ready=RoomDatabaseProvider.get(context);
            assertEquals("true",ready.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_READY_KEY));
            assertEquals(0,ready.manualProbeQueueDao().countPending(owner));
            assertEquals(Integer.valueOf(900),ready.manualProbeQualityDao().find(owner,newKey).score);
            assertEquals(Integer.valueOf(600),ready.manualProbeQualityDao().find(publishedOwnerBefore,oldKey).score);
            assertNull(ready.manualProbeQualityDao().find(publishedOwnerBefore,newKey));
            assertTrue(ManualProbeQualityStore.hasPublished(context));
            assertEquals(base,RoomCatalogueReader.read(context).catalogueVersion);
            assertEquals(1,RoomCatalogueReader.read(context).channelCount);
            try{
                RoomCatalogueWriter.publishManualSnapshot(context,staged,base,owner,
                        ()->ready.inTransaction()&&owner.equals(ready.appStateDao().getValue(completed))
                                &&ready.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY)==null
                                &&ManualProbeQualityStore.hasPublished(context),
                        new AppStateEditor(context).putString(completed,owner));
                fail("expected late publication rollback");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            closeProvider();
            AppDatabase restored=RoomDatabaseProvider.get(context);
            assertEquals(base,RoomCatalogueReader.read(context).catalogueVersion);
            assertEquals(1,RoomCatalogueReader.read(context).channelCount);
            assertEquals(owner,restored.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY));
            assertEquals("true",restored.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_READY_KEY));
            assertNull(restored.appStateDao().getValue(completed));
            assertEquals(2,restored.channelDao().countCatalogueBuildRows());
            assertEquals(2,restored.manualProbeQueueDao().count(owner));
            assertEquals(0,restored.manualProbeQueueDao().countPending(owner));
            assertEquals(Integer.valueOf(900),restored.manualProbeQualityDao().find(owner,newKey).score);
            assertEquals(Integer.valueOf(600),restored.manualProbeQualityDao().find(publishedOwnerBefore,oldKey).score);
            assertNull(restored.manualProbeQualityDao().find(publishedOwnerBefore,newKey));
            assertTrue(ManualProbeQualityStore.hasPublished(context));
            assertEquals(2,RoomCatalogueWriter.publishManualSnapshot(context,staged,base,owner,()->false,
                    new AppStateEditor(context).putString(completed,owner)));
            closeProvider();
            AppDatabase published=RoomDatabaseProvider.get(context);
            RoomCatalogueReader.Snapshot snapshot=RoomCatalogueReader.read(context);
            assertEquals(2,snapshot.channelCount);
            assertTrue(snapshot.catalogueVersion>base);
            assertEquals(owner,published.appStateDao().getValue(completed));
            assertNull(published.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY));
            assertEquals(0,published.channelDao().countCatalogueBuildRows());
            assertEquals(0,published.manualProbeQueueDao().count(owner));
            assertNull(published.manualProbeQualityDao().find(owner,newKey));
            JSONObject scores=ManualProbeQualityStore.readPlaybackScores(context,
                    java.util.Arrays.asList(oldUrl,newUrl),snapshot.catalogueVersion,()->false);
            assertEquals(650,scores.getInt(oldKey));
            assertEquals(900,scores.getInt(newKey));

            // Exercise the same quality-only transaction used by the worker's
            // 16-result checkpoint. The existing published fixture supplies
            // real indexed quality rows rather than faking the published flag.
            String pendingKey="source_probe_provisional_recheck_v1";
            String revisionKey="source_probe_quality_only_revision_v1";
            String lastKey="source_probe_provisional_recheck_last_v1";
            String initialPending=new JSONArray().put(oldKey).put(newKey).toString();
            String remaining=new JSONArray().put(newKey).toString();
            long version=snapshot.catalogueVersion;
            String publishedOwner=ManualProbeQualityStore.PUBLISHED_OWNER;
            new AppStateEditor(context).putString(pendingKey,initialPending)
                    .putLong(lastKey,100L).applyAtCatalogueVersion(version,()->false);
            new AppStateEditor(context).putString(pendingKey,remaining)
                    .putString(revisionKey,"checkpoint-one")
                    .putPublishedQuality(java.util.Collections.singletonList(
                        new ManualProbeQualityEntity(publishedOwner,oldKey,800,2)),()->false)
                    .applyAtCatalogueVersion(version,()->false);
            closeProvider();
            AppDatabase reopened=RoomDatabaseProvider.get(context);
            assertEquals(remaining,reopened.appStateDao().getValue(pendingKey));
            assertEquals("checkpoint-one",reopened.appStateDao().getValue(revisionKey));
            assertEquals("100",reopened.appStateDao().getValue(lastKey));
            assertEquals(Integer.valueOf(800),reopened.manualProbeQualityDao().find(publishedOwner,oldKey).score);
            assertEquals(Integer.valueOf(2),reopened.manualProbeQualityDao().find(publishedOwner,oldKey).failureStreak);
            assertEquals(Integer.valueOf(900),reopened.manualProbeQualityDao().find(publishedOwner,newKey).score);

            // Trigger pause only after BOTH app state and quality row writes
            // are visible within the transaction, exercising late rollback.
            java.util.function.BooleanSupplier latePause=()->reopened.inTransaction()
                    &&"checkpoint-two".equals(reopened.appStateDao().getValue(revisionKey))
                    &&Integer.valueOf(950).equals(reopened.manualProbeQualityDao().find(publishedOwner,newKey).score);
            try{
                new AppStateEditor(context).putString(pendingKey,"[]")
                        .putString(revisionKey,"checkpoint-two").putLong(lastKey,200L)
                        .putPublishedQuality(java.util.Collections.singletonList(
                            new ManualProbeQualityEntity(publishedOwner,newKey,950,null)),latePause)
                        .applyAtCatalogueVersion(version,latePause);
                fail("late quality checkpoint must roll back");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            closeProvider();
            AppDatabase resumed=RoomDatabaseProvider.get(context);
            assertEquals(remaining,resumed.appStateDao().getValue(pendingKey));
            assertEquals("checkpoint-one",resumed.appStateDao().getValue(revisionKey));
            assertEquals("100",resumed.appStateDao().getValue(lastKey));
            assertEquals(Integer.valueOf(800),resumed.manualProbeQualityDao().find(publishedOwner,oldKey).score);
            assertEquals(Integer.valueOf(900),resumed.manualProbeQualityDao().find(publishedOwner,newKey).score);
            assertEquals(version,RoomCatalogueReader.read(context).catalogueVersion);
            try{
                new AppStateEditor(context).putString(pendingKey,"[]")
                        .putPublishedQuality(java.util.Collections.singletonList(
                            new ManualProbeQualityEntity(publishedOwner,newKey,950,null)),()->false)
                        .applyAtCatalogueVersion(base,()->false);
                fail("stale checkpoint must not consume pending work");
            }catch(RoomCatalogueReader.StaleSnapshotException expected){}
            assertEquals(remaining,resumed.appStateDao().getValue(pendingKey));
            assertEquals(Integer.valueOf(900),resumed.manualProbeQualityDao().find(publishedOwner,newKey).score);
            new AppStateEditor(context).putString(pendingKey,"[]")
                    .putString(revisionKey,"checkpoint-two").putLong(lastKey,200L)
                    .putPublishedQuality(java.util.Collections.singletonList(
                        new ManualProbeQualityEntity(publishedOwner,newKey,950,null)),()->false)
                    .applyAtCatalogueVersion(version,()->false);
            closeProvider();
            AppDatabase finished=RoomDatabaseProvider.get(context);
            assertEquals("[]",finished.appStateDao().getValue(pendingKey));
            assertEquals("checkpoint-two",finished.appStateDao().getValue(revisionKey));
            assertEquals("200",finished.appStateDao().getValue(lastKey));
            assertEquals(Integer.valueOf(800),finished.manualProbeQualityDao().find(publishedOwner,oldKey).score);
            assertEquals(Integer.valueOf(950),finished.manualProbeQualityDao().find(publishedOwner,newKey).score);
            assertEquals(version,RoomCatalogueReader.read(context).catalogueVersion);
            assertEquals(2,RoomCatalogueReader.read(context).channelCount);
        });
    }

    @Test public void playbackStartDuringStagingCommitRollsBackAndOldWorkerCannotResume()throws Exception {
        isolated(context->{
            final String cursor="integration_cursor";
            final String revision=RoomCatalogueBuildStore.STAGING_REVISION_KEY;
            com.wodedianshi.tv.SourceUpdateActivityPolicy.setActivityActive(true);
            com.wodedianshi.tv.SourceUpdateActivityPolicy.setPlaybackActive(false);
            ExecutorService playback=Executors.newSingleThreadExecutor();
            try{
                RoomCatalogueWriter.initializeEmptyCatalogue(context);
                JSONArray original=new JSONArray().put(channel("CCTV-1","https://example.invalid/one"));
                RoomCatalogueBuildStore.updateTouched(context,original,0,
                        java.util.Collections.singleton(0),()->false,
                        new AppStateEditor(context).putInt(cursor,1).putString(revision,"old"));
                JSONArray extended=new JSONArray().put(original.getJSONObject(0))
                        .put(channel("CCTV-2","https://example.invalid/two"));
                AppDatabase db=RoomDatabaseProvider.get(context);
                long admitted=com.wodedianshi.tv.SourceUpdateActivityPolicy.playbackStartGeneration();
                // Same unpaced playback gate and sticky cancellation as the source worker.
                java.util.function.BooleanSupplier gate=SourceProbeTimePolicy.latchPause(()->
                        com.wodedianshi.tv.SourceUpdateActivityPolicy.playbackStartGeneration()!=admitted
                        ||com.wodedianshi.tv.SourceUpdateActivityPolicy.isPlaybackActive());
                java.util.concurrent.atomic.AtomicBoolean injected=new java.util.concurrent.atomic.AtomicBoolean();
                try{
                    RoomCatalogueBuildStore.updateTouched(context,extended,1,
                            java.util.Collections.singleton(1),()->{
                                // The separate playback thread signals a start only when
                                // rows and progress have both been written, before commit.
                                if(db.inTransaction()&&"2".equals(db.appStateDao().getValue(cursor))
                                        &&injected.compareAndSet(false,true)){
                                    try{
                                        playback.submit(()->com.wodedianshi.tv.SourceUpdateActivityPolicy
                                                .setPlaybackActive(true)).get(5,TimeUnit.SECONDS);
                                    }catch(Exception error){throw new AssertionError(error);}
                                }
                                return gate.getAsBoolean();
                            },new AppStateEditor(context).putInt(cursor,2).putString(revision,"new"));
                    fail("playback start must roll back the staging transaction");
                }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
                assertTrue(injected.get());
                assertTrue(gate.getAsBoolean());
                playback.submit(()->com.wodedianshi.tv.SourceUpdateActivityPolicy
                        .setPlaybackActive(false)).get(5,TimeUnit.SECONDS);
                assertTrue("old execution stays cancelled even after playback stops",gate.getAsBoolean());
                closeProvider();
                AppDatabase reopened=RoomDatabaseProvider.get(context);
                assertEquals(1,reopened.channelDao().countCatalogueBuildRows());
                assertEquals("1",reopened.appStateDao().getValue(cursor));
                assertEquals("old",reopened.appStateDao().getValue(revision));
                long nextGeneration=com.wodedianshi.tv.SourceUpdateActivityPolicy.playbackStartGeneration();
                java.util.function.BooleanSupplier nextGate=SourceProbeTimePolicy.latchPause(()->
                        com.wodedianshi.tv.SourceUpdateActivityPolicy.playbackStartGeneration()!=nextGeneration
                        ||com.wodedianshi.tv.SourceUpdateActivityPolicy.isPlaybackActive());
                assertFalse(nextGate.getAsBoolean());
                assertEquals(2,RoomCatalogueBuildStore.updateTouched(context,extended,1,
                        java.util.Collections.singleton(1),nextGate,
                        new AppStateEditor(context).putInt(cursor,2).putString(revision,"new")));
                assertEquals("2",reopened.appStateDao().getValue(cursor));
                assertEquals("new",reopened.appStateDao().getValue(revision));
                assertEquals(2,reopened.channelDao().countCatalogueBuildRows());
                assertEquals(0,RoomCatalogueReader.read(context).channelCount);
            }finally{
                playback.shutdownNow();
                com.wodedianshi.tv.SourceUpdateActivityPolicy.setPlaybackActive(false);
                com.wodedianshi.tv.SourceUpdateActivityPolicy.setActivityActive(false);
            }
        });
    }

    @Test public void supersededPagedReadStopsBeforeNextPageAndRetainsDurableCatalogue()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long empty=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray rows=new JSONArray();
            for(int i=0;i<401;i++){
                rows.put(channel("Audit test channel "+i,"https://example.invalid/station/"+i)
                        .put("group","自建频道"));
            }
            assertEquals(401,RoomCatalogueWriter.writeSnapshotWithProgress(context,rows,empty,false,
                    ()->false,new AppStateEditor(context)));
            long version=RoomCatalogueReader.read(context).catalogueVersion;
            java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong(1);
            List<RoomCatalogueReader.ChannelRecord> privateStaging=new ArrayList<>();
            try{
                RoomCatalogueReader.read(context,page->{
                    privateStaging.addAll(page);
                    generation.incrementAndGet();
                },()->generation.get()!=1L);
                fail("superseded request must not return a complete snapshot");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            assertEquals(200,privateStaging.size());
            List<RoomCatalogueReader.ChannelRecord> replacement=new ArrayList<>();
            RoomCatalogueReader.Snapshot complete=RoomCatalogueReader.read(context,
                    replacement::addAll,()->generation.get()!=2L);
            assertNotNull(complete);
            assertEquals(version,complete.catalogueVersion);
            assertEquals(401,complete.channelCount);
            assertEquals(401,replacement.size());
            assertTrue(complete.channels.isEmpty());
            RoomCatalogueReader.Snapshot full=RoomCatalogueReader.read(context);
            assertEquals(401,full.channelCount);
            assertEquals(401,full.channels.size());
            assertEquals(complete.catalogueVersion,full.catalogueVersion);
            assertEquals(complete.favorites,full.favorites);
            for(int i=0;i<replacement.size();i++){
                RoomCatalogueReader.ChannelRecord streamed=replacement.get(i);
                RoomCatalogueReader.ChannelRecord direct=full.channels.get(i);
                assertEquals(streamed.name,direct.name);
                assertEquals(streamed.url,direct.url);
                assertEquals(streamed.group,direct.group);
                assertEquals(streamed.tvgId,direct.tvgId);
                assertEquals(streamed.tvgName,direct.tvgName);
                assertEquals(streamed.sources,direct.sources);
                assertEquals(streamed.sourceScopes,direct.sourceScopes);
                assertEquals(streamed.backupSourceScopes,direct.backupSourceScopes);
                assertEquals(streamed.activeSource,direct.activeSource);
            }
        });
    }

    private static List<String> endpoints(List<ManualProbeQueueEntity> rows){
        List<String> result=new ArrayList<>();
        for(ManualProbeQueueEntity row:rows)result.add(row.endpoint);
        return result;
    }

    @Test public void qualityOnlyCommitKeepsCatalogueAndRejectsStaleOrRevokedWrites()throws Exception{
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long empty=RoomCatalogueReader.read(context).catalogueVersion;
            JSONArray rows=new JSONArray().put(channel("CCTV-1","https://example.invalid/live"));
            RoomCatalogueWriter.writeSnapshotWithProgress(context,rows,empty,false,()->false,new AppStateEditor(context));
            long version=RoomCatalogueReader.read(context).catalogueVersion;
            String key="quality_only_test";
            new AppStateEditor(context).putString(key,"confirmed").applyAtCatalogueVersion(version,()->false);
            assertEquals(version,RoomCatalogueReader.read(context).catalogueVersion);
            assertEquals(1,RoomCatalogueReader.read(context).channelCount);
            try{
                new AppStateEditor(context).putString(key,"stale").applyAtCatalogueVersion(empty,()->false);
                fail("stale version");
            }catch(RoomCatalogueReader.StaleSnapshotException expected){}
            java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
            try{
                new AppStateEditor(context).putString(key,"cancelled").applyAtCatalogueVersion(version,()->checks.incrementAndGet()>=2);
                fail("cancelled transaction");
            }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
            assertEquals("confirmed",RoomDatabaseProvider.get(context).appStateDao().getValue(key));
            assertEquals(version,RoomCatalogueReader.read(context).catalogueVersion);
        });
    }
    @Test public void sourceMemoryRetriesBothDirtyKeysAfterFailedCheckpoint()throws Exception {
        isolated(context->{
            final String good="source_last_good_v1",cooldown="source_cooldowns_v1";
            final String station="station-test";
            final String older="https://example.invalid/older";
            final String newer="https://example.invalid/newer";
            final String failed="https://example.invalid/failed";
            com.wodedianshi.tv.SourceMemoryStore.initialize(context);
            AppDatabase db=RoomDatabaseProvider.get(context);
            db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER fail_memory BEFORE INSERT ON app_state "
                +"WHEN NEW.`key` = 'source_cooldowns_v1' "
                +"BEGIN SELECT RAISE(ABORT, 'injected memory checkpoint failure'); END");
            Field writerField=com.wodedianshi.tv.SourceMemoryStore.class.getDeclaredField("WRITER");
            writerField.setAccessible(true);
            ExecutorService writer=(ExecutorService)writerField.get(null);
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            writer.submit(()->{release.await();return null;});
            try {
                com.wodedianshi.tv.SourceMemoryStore.saveLastGood(context,station,older);
                com.wodedianshi.tv.SourceMemoryStore.coolDown(context,failed,600000L);
                com.wodedianshi.tv.SourceMemoryStore.saveLastGood(context,station,newer);
            } finally {
                release.countDown();
            }
            writer.submit(()->{}).get(30,TimeUnit.SECONDS);
            assertNull(db.appStateDao().getValue(good));
            assertNull(db.appStateDao().getValue(cooldown));
            assertEquals(newer,com.wodedianshi.tv.SourceMemoryStore.getLastGood(context,station));
            assertTrue(com.wodedianshi.tv.SourceMemoryStore.isCoolingDown(context,failed));
            db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_memory");
            // Only a cooldown update occurs. The failed good-route key must also retry.
            com.wodedianshi.tv.SourceMemoryStore.coolDown(context,failed,600000L);
            writer.submit(()->{}).get(30,TimeUnit.SECONDS);
            JSONObject persistedGood=new JSONObject(db.appStateDao().getValue(good));
            assertEquals(newer,persistedGood.getJSONObject(station).getString("url"));
            JSONObject persistedCooldown=new JSONObject(db.appStateDao().getValue(cooldown));
            assertEquals(1,persistedCooldown.length());
            assertTrue(persistedCooldown.getLong(persistedCooldown.keys().next())>System.currentTimeMillis());
        });
    }

    private static com.wodedianshi.tv.SourceUpdateWorker manualWorker(Context context,String id) {
        return androidx.work.testing.TestWorkerBuilder.from(context,
                com.wodedianshi.tv.SourceUpdateWorker.class,Runnable::run)
            .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
                .putString("manual_refresh_id",id).build()).build();
    }

    @Test public void workerRetriesReadAndDiagnosticFailuresWithoutChangingCatalogue()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            String url="https://example.invalid/confirmed";
            RoomCatalogueWriter.writeSnapshotWithProgress(context,
                new JSONArray().put(channel("CCTV-1",url)),base,false,()->false,
                new AppStateEditor(context).putInt(PERCENT,47)
                    .putLong("source_full_rebuild_completed_at",System.currentTimeMillis())
                    .putString("source_manual_completed_id","completed"));
            long version=RoomCatalogueReader.read(context).catalogueVersion;
            AppDatabase db=RoomDatabaseProvider.get(context);
            androidx.sqlite.db.SupportSQLiteDatabase sql=db.getOpenHelper().getWritableDatabase();
            sql.execSQL("ALTER TABLE app_state RENAME TO unavailable_app_state");
            try {
                assertEquals(androidx.work.ListenableWorker.Result.retry(),
                    manualWorker(context,"completed").doWork());
            } finally {
                sql.execSQL("ALTER TABLE unavailable_app_state RENAME TO app_state");
            }
            // The same completed request must now succeed, proving ownership was released.
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                manualWorker(context,"completed").doWork());
            sql.execSQL("CREATE TRIGGER fail_source_status BEFORE INSERT ON app_state "
                +"WHEN NEW.`key` = 'source_update_result' "
                +"BEGIN SELECT RAISE(ABORT, 'injected status failure'); END");
            com.wodedianshi.tv.SourceUpdateActivityPolicy.setPlaybackActive(true);
            try {
                assertEquals(androidx.work.ListenableWorker.Result.retry(),
                    manualWorker(context,"pending").doWork());
            } finally {
                com.wodedianshi.tv.SourceUpdateActivityPolicy.setPlaybackActive(false);
                sql.execSQL("DROP TRIGGER fail_source_status");
            }
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                manualWorker(context,"completed").doWork());
            assertEquals("47",db.appStateDao().getValue(PERCENT));
            assertNull(db.appStateDao().getValue("source_update_time"));
            RoomCatalogueReader.Snapshot snapshot=RoomCatalogueReader.read(context);
            assertEquals(version,snapshot.catalogueVersion);
            assertEquals(1,snapshot.channelCount);
            assertEquals(url,snapshot.channels.get(0).url);
        });
    }

    @Test public void committedInitialWorkerFinishesDuringPlaybackWithoutRescheduling()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            String url="https://example.invalid/confirmed-initial";
            RoomCatalogueWriter.writeSnapshotWithProgress(context,
                new JSONArray().put(channel("CCTV-1",url)),base,false,()->false,
                new AppStateEditor(context).putInt(PERCENT,100)
                    .putLong("source_full_rebuild_completed_at",System.currentTimeMillis())
                    .remove("source_full_rebuild_active")
                    .putString("source_update_result","complete"));
            long version=RoomCatalogueReader.read(context).catalogueVersion;
            com.wodedianshi.tv.SourceUpdateActivityPolicy.setPlaybackActive(true);
            try {
                com.wodedianshi.tv.SourceUpdateWorker worker=androidx.work.testing.TestWorkerBuilder.from(
                    context,com.wodedianshi.tv.SourceUpdateWorker.class,Runnable::run)
                    .setInputData(new androidx.work.Data.Builder().putBoolean("manual",true)
                        .putBoolean("initial_discovery_chain",true).build()).build();
                assertEquals(androidx.work.ListenableWorker.Result.success(),worker.doWork());
            } finally {
                com.wodedianshi.tv.SourceUpdateActivityPolicy.setPlaybackActive(false);
            }
            AppDatabase db=RoomDatabaseProvider.get(context);
            assertEquals("100",db.appStateDao().getValue(PERCENT));
            assertEquals("complete",db.appStateDao().getValue("source_update_result"));
            RoomCatalogueReader.Snapshot snapshot=RoomCatalogueReader.read(context);
            assertEquals(version,snapshot.catalogueVersion);
            assertEquals(1,snapshot.channelCount);
            assertEquals(url,snapshot.channels.get(0).url);
        });
    }

    @Test public void publicationReadSkipsFavoritesButCommitPreservesThem()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            String url="https://example.invalid/favorite";
            JSONArray rows=new JSONArray().put(channel("CCTV-1",url));
            RoomCatalogueWriter.writeSnapshotWithProgress(context,rows,base,false,
                    ()->false,new AppStateEditor(context));
            ChannelDao dao=RoomDatabaseProvider.get(context).channelDao();
            String id=dao.getAllChannelIds().get(0);
            long stamp=12345L;
            long version=dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY).completedAt+1L;
            dao.addFavorite(new FavoriteEntity(id,stamp));
            dao.refreshFavoriteCheckpointCount(MasterCatalogueRepository.MIGRATION_KEY,version);
            RoomCatalogueReader.Snapshot full=RoomCatalogueReader.read(context);
            List<RoomCatalogueReader.ChannelRecord> streamed=new ArrayList<>();
            RoomCatalogueReader.Snapshot publication=RoomCatalogueReader.readForPublication(
                    context,streamed::addAll,()->false);
            assertEquals(full.catalogueVersion,publication.catalogueVersion);
            assertEquals(full.channelCount,streamed.size());
            assertEquals(1,full.favorites.size());
            assertTrue(publication.favorites.isEmpty());
            RoomCatalogueWriter.writeSnapshotWithProgress(context,rows,publication.catalogueVersion,false,
                    ()->false,new AppStateEditor(context));
            assertEquals(full.favorites,RoomCatalogueReader.read(context).favorites);
            assertEquals(Long.valueOf(stamp),dao.getFavoriteAddedAt(id));
        });
    }

    @Test public void publicationPreservesSurvivingFavoriteAndRemovesStaleFavorite()throws Exception {
        isolated(context->{
            RoomCatalogueWriter.initializeEmptyCatalogue(context);
            long base=RoomCatalogueReader.read(context).catalogueVersion;
            String retained="https://example.invalid/retained";
            String removed="https://example.invalid/removed";
            JSONArray initial=new JSONArray().put(channel("CCTV-1",retained))
                    .put(channel("CCTV-2",removed));
            RoomCatalogueWriter.writeSnapshotWithProgress(context,initial,base,false,
                    ()->false,new AppStateEditor(context));
            ChannelDao dao=RoomDatabaseProvider.get(context).channelDao();
            for(String id:dao.getAllChannelIds())dao.addFavorite(new FavoriteEntity(id,12345L));
            long version=dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY).completedAt+1L;
            dao.refreshFavoriteCheckpointCount(MasterCatalogueRepository.MIGRATION_KEY,version);
            JSONArray updated=new JSONArray().put(channel("CCTV-1",retained));
            RoomCatalogueWriter.writeSnapshotWithProgress(context,updated,version,false,
                    ()->false,new AppStateEditor(context));
            assertEquals(1,dao.countChannels());
            assertEquals(1,dao.countFavorites());
            assertEquals(1,dao.getAllChannelIds().size());
            String id=dao.getAllChannelIds().get(0);
            assertEquals(Long.valueOf(12345L),dao.getFavoriteAddedAt(id));
            assertEquals(1,RoomCatalogueReader.read(context).favorites.size());
        });
    }

}
