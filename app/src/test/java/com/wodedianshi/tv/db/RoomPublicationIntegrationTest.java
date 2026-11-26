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
            RoomCatalogueBuildStore.seedManual(context,oldRows,base,owner,()->false,
                    new AppStateEditor(context).putInt("source_manual_next_feed",0)
                            .putString(RoomCatalogueBuildStore.MANUAL_SCORES_KEY,
                                    new JSONObject().put(oldKey,600).toString()));
            ManualProbeQualityStore.ensure(context,owner,()->false);
            AppDatabase db=RoomDatabaseProvider.get(context);
            assertEquals(Integer.valueOf(600),db.manualProbeQualityDao().find(owner,oldKey).score);
            assertFalse(ManualProbeQualityStore.hasPublished(context));
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
            assertFalse(ManualProbeQualityStore.hasPublished(context));
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
            assertFalse(ManualProbeQualityStore.hasPublished(context));
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
            assertEquals(401,RoomCatalogueReader.read(context).channelCount);
        });
    }

    private static List<String> endpoints(List<ManualProbeQueueEntity> rows){
        List<String> result=new ArrayList<>();
        for(ManualProbeQueueEntity row:rows)result.add(row.endpoint);
        return result;
    }
}
