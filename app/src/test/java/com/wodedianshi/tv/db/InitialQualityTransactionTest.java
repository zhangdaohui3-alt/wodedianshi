package com.wodedianshi.tv.db;

import androidx.room.Room;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class InitialQualityTransactionTest {
    @Test public void manualSeedCapturesLegacyOnlyWhenUnindexedAndRollsBack()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String owner="manual-capture";
            String scores=RoomCatalogueBuildStore.MANUAL_SCORES_KEY,streaks=RoomCatalogueBuildStore.MANUAL_STREAKS_KEY;
            db.appStateDao().put(new AppStateEntity("source_probe_scores_v1","{\"a\":900}",1L));
            db.appStateDao().put(new AppStateEntity("source_probe_failure_streaks_v1","{\"b\":2}",1L));
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_OWNER_KEY,owner,1L));
            db.runInTransaction(()->ManualProbeQualityStore.captureLegacyManualSeedInTransaction(db,owner,2L,()->false));
            assertEquals("{\"a\":900}",db.appStateDao().getValue(scores));
            assertEquals("{\"b\":2}",db.appStateDao().getValue(streaks));
            db.appStateDao().remove(RoomCatalogueBuildStore.MANUAL_OWNER_KEY);
            db.runInTransaction(()->{
                ManualProbeQualityStore.seedInitialInTransaction(db,Collections.emptyList(),()->false);
                ManualProbeQualityStore.publishInitialInTransaction(db,()->false);
            });
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_OWNER_KEY,owner,1L));
            try{
                db.runInTransaction(()->{
                    ManualProbeQualityStore.captureLegacyManualSeedInTransaction(db,owner,3L,()->false);
                    throw new IllegalStateException("rollback");
                });
                fail("Expected rollback");
            }catch(IllegalStateException expected){assertEquals("rollback",expected.getMessage());}
            assertEquals("{\"a\":900}",db.appStateDao().getValue(scores));
            db.runInTransaction(()->ManualProbeQualityStore.captureLegacyManualSeedInTransaction(db,owner,4L,()->false));
            assertNull(db.appStateDao().getValue(scores));
            assertNull(db.appStateDao().getValue(streaks));
            assertEquals("{\"a\":900}",db.appStateDao().getValue("source_probe_scores_v1"));
        }finally{db.close();}
    }

    @Test public void manualFinalRankingReadsOnlyRetainedPrivateScores()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String owner="manual-ranking";
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_OWNER_KEY,owner,1L));
            db.appStateDao().put(new AppStateEntity(ManualProbeQualityStore.READY_KEY,owner,1L));
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_READY_KEY,"true",1L));
            java.util.List<String> urls=new java.util.ArrayList<>();
            java.util.List<ManualProbeQualityEntity> quality=new java.util.ArrayList<>();
            for(int i=0;i<300;i++){
                String url="https://example.invalid/rank/"+i;
                urls.add(url);
                String key=com.wodedianshi.tv.StableKey.url(url);
                quality.add(new ManualProbeQualityEntity(owner,key,i==150?null:Integer.valueOf(i),2));
                quality.add(new ManualProbeQualityEntity(ManualProbeQualityStore.PUBLISHED_OWNER,key,999,null));
            }
            String historical=com.wodedianshi.tv.StableKey.url("https://example.invalid/historical");
            String legacy=com.wodedianshi.tv.StableKey.legacyUrl(urls.get(150));
            quality.add(new ManualProbeQualityEntity(owner,historical,888,3));
            quality.add(new ManualProbeQualityEntity(owner,legacy,456,null));
            db.manualProbeQualityDao().put(quality);
            org.json.JSONObject scores=ManualProbeQualityStore.readManualRankingScores(db,owner,urls,()->false);
            assertEquals(300,scores.length());
            assertEquals(299,scores.getInt(com.wodedianshi.tv.StableKey.url(urls.get(299))));
            assertFalse(scores.has(com.wodedianshi.tv.StableKey.url(urls.get(150))));
            assertEquals(456,scores.getInt(legacy));
            assertFalse(scores.has(historical));
            assertEquals(Integer.valueOf(888),db.manualProbeQualityDao().find(owner,historical).score);
            db.appStateDao().remove(RoomCatalogueBuildStore.MANUAL_READY_KEY);
            try{
                ManualProbeQualityStore.readManualRankingScores(db,owner,urls,()->false);
                fail("Unready owner must not rank");
            }catch(IllegalStateException expected){}
        }finally{db.close();}
    }

    @Test public void indexedSeedIgnoresObsoleteMalformedMirrors()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            db.runInTransaction(()->{
                ManualProbeQualityStore.seedInitialInTransaction(db,Collections.singletonList(
                    new ManualProbeQualityEntity(initial,"key",900,2)),()->false);
                ManualProbeQualityStore.publishInitialInTransaction(db,()->false);
            });
            db.appStateDao().put(new AppStateEntity("old_scores","invalid json",1L));
            db.appStateDao().put(new AppStateEntity("old_streaks","invalid json",1L));
            ManualProbeQualityStore.ensureInitial(db,"old_scores","old_streaks",()->false);
            assertTrue(ManualProbeQualityStore.hasInitial(db));
            assertNull(db.manualProbeQualityDao().find(initial,"key"));
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(published,"key").score);
            db.runInTransaction(()->ManualProbeQualityStore.clearInitialInTransaction(db));
            String manual="manual-test";
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_OWNER_KEY,manual,1L));
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_SCORES_KEY,"invalid json",1L));
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_STREAKS_KEY,"invalid json",1L));
            ManualProbeQualityStore.ensure(db,manual,()->false);
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(manual,"key").score);
            assertEquals(Integer.valueOf(2),db.manualProbeQualityDao().find(manual,"key").failureStreak);
            assertNull(db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_SCORES_KEY));
        }finally{db.close();}
    }

    @Test public void unindexedSeedStillReadsLegacyScoresAndStreaks()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            db.appStateDao().put(new AppStateEntity("old_scores","{\"a\":900}",1L));
            db.appStateDao().put(new AppStateEntity("old_streaks","{\"b\":2}",1L));
            ManualProbeQualityStore.ensureInitial(db,"old_scores","old_streaks",()->false);
            String initial=ManualProbeQualityStore.INITIAL_OWNER;
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(initial,"a").score);
            assertEquals(Integer.valueOf(2),db.manualProbeQualityDao().find(initial,"b").failureStreak);
            db.runInTransaction(()->ManualProbeQualityStore.clearInitialInTransaction(db));
            String manual="manual-test";
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_OWNER_KEY,manual,1L));
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_SCORES_KEY,"{\"a\":900}",1L));
            db.appStateDao().put(new AppStateEntity(RoomCatalogueBuildStore.MANUAL_STREAKS_KEY,"{\"b\":2}",1L));
            ManualProbeQualityStore.ensure(db,manual,()->false);
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(manual,"a").score);
            assertEquals(Integer.valueOf(2),db.manualProbeQualityDao().find(manual,"b").failureStreak);
        }finally{db.close();}
    }

    @Test public void privateQualityCommitsAndRollsBackWithProgressWithoutChangingPublished() {
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try {
            String key="v2:example";
            db.manualProbeQualityDao().put(Collections.singletonList(
                new ManualProbeQualityEntity(ManualProbeQualityStore.PUBLISHED_OWNER,key,900,null)));
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,Collections.emptyList(),()->false));
            db.runInTransaction(()->new AppStateEditor(context).putInt("test_cursor",1)
                .putInitialQuality(Collections.singletonList(new ManualProbeQualityEntity(
                    ManualProbeQualityStore.INITIAL_OWNER,key,1200,null)),()->false).applyInTransaction(db));
            assertEquals("1",db.appStateDao().getValue("test_cursor"));
            assertEquals(Integer.valueOf(1200),db.manualProbeQualityDao().find(ManualProbeQualityStore.INITIAL_OWNER,key).score);
            try {
                db.runInTransaction(()->{
                    new AppStateEditor(context).putInt("test_cursor",2)
                        .putInitialQuality(Collections.singletonList(new ManualProbeQualityEntity(
                            ManualProbeQualityStore.INITIAL_OWNER,key,-500,null)),()->false).applyInTransaction(db);
                    throw new IllegalStateException("rollback");
                });
                fail("Expected rollback");
            }catch(IllegalStateException expected){assertEquals("rollback",expected.getMessage());}
            assertEquals("1",db.appStateDao().getValue("test_cursor"));
            assertEquals(Integer.valueOf(1200),db.manualProbeQualityDao().find(ManualProbeQualityStore.INITIAL_OWNER,key).score);
            db.runInTransaction(()->ManualProbeQualityStore.clearInitialInTransaction(db));
            assertNull(db.manualProbeQualityDao().find(ManualProbeQualityStore.INITIAL_OWNER,key));
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(ManualProbeQualityStore.PUBLISHED_OWNER,key).score);
        }finally{db.close();}
    }

    @Test public void initialSeedIsIdempotentAndPromotionMergesOnlyPrivateKeys(){
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            db.manualProbeQualityDao().put(java.util.Arrays.asList(
                new ManualProbeQualityEntity(published,"a",900,2),
                new ManualProbeQualityEntity(published,"untouched",700,null)));
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,
                Collections.singletonList(new ManualProbeQualityEntity(initial,"a",1200,null)),()->false));
            db.runInTransaction(()->ManualProbeQualityStore.writeInitialInTransaction(db,
                Collections.singletonList(new ManualProbeQualityEntity(initial,"a",1500,null)),()->false));
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,
                Collections.singletonList(new ManualProbeQualityEntity(initial,"a",100,null)),()->false));
            assertEquals(Integer.valueOf(1500),db.manualProbeQualityDao().find(initial,"a").score);
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(published,"a").score);
            try{
                db.runInTransaction(()->{
                    ManualProbeQualityStore.publishInitialInTransaction(db,()->false);
                    throw new IllegalStateException("rollback");
                });
                fail("Expected rollback");
            }catch(IllegalStateException expected){assertEquals("rollback",expected.getMessage());}
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(published,"a").score);
            assertEquals(Integer.valueOf(1500),db.manualProbeQualityDao().find(initial,"a").score);
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialInTransaction(db,()->false));
            assertEquals(Integer.valueOf(1500),db.manualProbeQualityDao().find(published,"a").score);
            assertNull(db.manualProbeQualityDao().find(published,"a").failureStreak);
            assertEquals(Integer.valueOf(700),db.manualProbeQualityDao().find(published,"untouched").score);
            assertNull(db.manualProbeQualityDao().find(initial,"a"));
            assertNull(db.appStateDao().getValue(ManualProbeQualityStore.INITIAL_READY_KEY));
        }finally{db.close();}
    }


    @Test public void initialIndexedScoresRetainMoreThanLegacyCompatibilityCapacity(){
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            java.util.List<ManualProbeQualityEntity> rows=new java.util.ArrayList<>();
            for(int i=0;i<8001;i++)rows.add(new ManualProbeQualityEntity(initial,String.format(java.util.Locale.ROOT,"k%05d",i),1000+i,null));
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,rows,()->false));
            assertEquals(Integer.valueOf(9000),db.manualProbeQualityDao().find(initial,"k08000").score);
            assertEquals(Integer.valueOf(1000),db.manualProbeQualityDao().find(initial,"k00000").score);
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialInTransaction(db,()->false));
            assertEquals(Integer.valueOf(9000),db.manualProbeQualityDao().find(published,"k08000").score);
            assertEquals(Integer.valueOf(1000),db.manualProbeQualityDao().find(published,"k00000").score);
            assertNull(db.manualProbeQualityDao().find(initial,"k08000"));
        }finally{db.close();}
    }


    @Test public void indexedRebuildInheritsPublishedOnlyUntilPrivateObservation()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            String url="https://example.invalid/live",key=com.wodedianshi.tv.StableKey.url(url);
            String untouchedUrl="https://example.invalid/other",other=com.wodedianshi.tv.StableKey.url(untouchedUrl);
            db.runInTransaction(()->{
                ManualProbeQualityStore.seedInitialInTransaction(db,java.util.Arrays.asList(
                    new ManualProbeQualityEntity(initial,key,900,2),
                    new ManualProbeQualityEntity(initial,other,700,null)),()->false);
                ManualProbeQualityStore.publishInitialInTransaction(db,()->false);
            });
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,
                Collections.singletonList(new ManualProbeQualityEntity(initial,key,100,9)),()->false));
            assertNull(db.manualProbeQualityDao().find(initial,key));
            org.json.JSONObject scores=new org.json.JSONObject(),streaks=new org.json.JSONObject();
            ManualProbeQualityStore.loadUrls(db,initial,Collections.singletonList(url),new java.util.HashSet<>(),scores,streaks,()->false);
            assertEquals(900,scores.getInt(key));
            assertEquals(2,streaks.getInt(key));
            db.runInTransaction(()->ManualProbeQualityStore.writeInitialInTransaction(db,
                Collections.singletonList(new ManualProbeQualityEntity(initial,key,null,null)),()->false));
            scores=new org.json.JSONObject();streaks=new org.json.JSONObject();
            ManualProbeQualityStore.loadUrls(db,initial,Collections.singletonList(url),new java.util.HashSet<>(),scores,streaks,()->false);
            assertFalse(scores.has(key));assertFalse(streaks.has(key));
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(published,key).score);
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialInTransaction(db,()->false));
            assertNull(db.manualProbeQualityDao().find(published,key));
            assertEquals(Integer.valueOf(700),db.manualProbeQualityDao().find(published,other).score);
        }finally{db.close();}
    }


    @Test public void inheritedReadAcrossPagesPreservesWholeRowPrecedence()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            java.util.List<String> urls=new java.util.ArrayList<>();
            java.util.List<ManualProbeQualityEntity> rows=new java.util.ArrayList<>();
            for(int n=0;n<300;n++){
                String url="https://example.invalid/inherit/"+n;
                urls.add(url);
                String key=com.wodedianshi.tv.StableKey.url(url);
                if(n%4!=3)rows.add(new ManualProbeQualityEntity(published,key,900,2));
                if(n%4==1)rows.add(new ManualProbeQualityEntity(initial,key,null,5));
                if(n%4==2)rows.add(new ManualProbeQualityEntity(initial,key,null,null));
            }
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,Collections.emptyList(),()->false));
            db.manualProbeQualityDao().put(rows);
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialPreviewInTransaction(db,Collections.emptyList(),()->false));
            org.json.JSONObject scores=new org.json.JSONObject(),streaks=new org.json.JSONObject();
            java.util.Set<String> loaded=new java.util.HashSet<>();
            ManualProbeQualityStore.loadUrls(db,initial,urls,loaded,scores,streaks,()->false);
            for(int n=0;n<urls.size();n++){
                String key=com.wodedianshi.tv.StableKey.url(urls.get(n));
                assertTrue(loaded.contains(key));
                assertTrue(loaded.contains(com.wodedianshi.tv.StableKey.legacyUrl(urls.get(n))));
                if(n%4==0){assertEquals(900,scores.getInt(key));assertEquals(2,streaks.getInt(key));}
                else if(n%4==1){assertFalse(scores.has(key));assertEquals(5,streaks.getInt(key));}
                else{assertFalse(scores.has(key));assertFalse(streaks.has(key));}
            }
            // Repeated preparation must not overwrite an observation made in memory.
            String first=com.wodedianshi.tv.StableKey.url(urls.get(0));
            scores.put(first,1234);
            ManualProbeQualityStore.loadUrls(db,initial,urls,loaded,scores,streaks,()->false);
            assertEquals(1234,scores.getInt(first));
        }finally{db.close();}
    }

    @Test public void previewPublishesOnlyRetainedQualityAndKeepsPrivateBuild()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            String visible="https://example.invalid/visible",hidden="https://example.invalid/hidden";
            String vk=com.wodedianshi.tv.StableKey.url(visible),hk=com.wodedianshi.tv.StableKey.url(hidden);
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,java.util.Arrays.asList(
                new ManualProbeQualityEntity(initial,vk,1200,null),
                new ManualProbeQualityEntity(initial,hk,1500,null)),()->false));
            try{
                db.runInTransaction(()->{
                    new AppStateEditor(context).putInt("preview_milestone",1)
                        .putInitialPreviewQuality(Collections.singletonList(visible),()->false).applyInTransaction(db);
                    throw new IllegalStateException("rollback");
                });
                fail("Expected rollback");
            }catch(IllegalStateException expected){assertEquals("rollback",expected.getMessage());}
            assertNull(db.appStateDao().getValue("preview_milestone"));
            assertNull(db.manualProbeQualityDao().find(published,vk));
            db.runInTransaction(()->new AppStateEditor(context).putInt("preview_milestone",1)
                .putInitialPreviewQuality(Collections.singletonList(visible),()->false).applyInTransaction(db));
            assertEquals("1",db.appStateDao().getValue("preview_milestone"));
            assertEquals(Integer.valueOf(1200),db.manualProbeQualityDao().find(published,vk).score);
            assertNull(db.manualProbeQualityDao().find(published,hk));
            assertEquals(Integer.valueOf(1500),db.manualProbeQualityDao().find(initial,hk).score);
            assertTrue(ManualProbeQualityStore.hasInitial(db));
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialInTransaction(db,()->false));
            assertEquals(Integer.valueOf(1500),db.manualProbeQualityDao().find(published,hk).score);
        }finally{db.close();}
    }


    @Test public void finalCleanupRemovesOnlyPublishedEmptyRowsAndPreservesPrivateOwners(){
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            db.manualProbeQualityDao().put(java.util.Arrays.asList(
                new ManualProbeQualityEntity(published,"empty",null,null),
                new ManualProbeQualityEntity(published,"positive",1200,null),
                new ManualProbeQualityEntity(published,"negative",-500,null),
                new ManualProbeQualityEntity(published,"streak",null,2),
                new ManualProbeQualityEntity("unrelated-private","empty",null,null)));
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,
                Collections.singletonList(new ManualProbeQualityEntity(initial,"new-empty",null,null)),()->false));
            assertNotNull(db.manualProbeQualityDao().find(initial,"new-empty"));
            try{
                db.runInTransaction(()->{
                    ManualProbeQualityStore.publishInitialInTransaction(db,()->false);
                    throw new IllegalStateException("rollback");
                });
                fail("Expected rollback");
            }catch(IllegalStateException expected){assertEquals("rollback",expected.getMessage());}
            assertNotNull(db.manualProbeQualityDao().find(initial,"new-empty"));
            assertNotNull(db.manualProbeQualityDao().find(published,"empty"));
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialInTransaction(db,()->false));
            assertNull(db.manualProbeQualityDao().find(published,"empty"));
            assertNull(db.manualProbeQualityDao().find(published,"new-empty"));
            assertEquals(Integer.valueOf(1200),db.manualProbeQualityDao().find(published,"positive").score);
            assertEquals(Integer.valueOf(-500),db.manualProbeQualityDao().find(published,"negative").score);
            assertEquals(Integer.valueOf(2),db.manualProbeQualityDao().find(published,"streak").failureStreak);
            assertNotNull(db.manualProbeQualityDao().find("unrelated-private","empty"));
        }finally{db.close();}
    }


    @Test public void boundedSqlCopyPreservesValuesOverridesAndUnrelatedKeys(){
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String source="copy-source",target="copy-target";
            java.util.List<ManualProbeQualityEntity> rows=new java.util.ArrayList<>();
            for(int n=0;n<513;n++)rows.add(new ManualProbeQualityEntity(source,
                String.format(java.util.Locale.ROOT,"k%04d",n),n==256?null:Integer.valueOf(n),n==256?null:Integer.valueOf(2)));
            rows.add(new ManualProbeQualityEntity(target,"k0256",999,3));
            rows.add(new ManualProbeQualityEntity(target,"unrelated",700,null));
            db.manualProbeQualityDao().put(rows);
            try{
                db.runInTransaction(()->{
                    ManualProbeQualityStore.copyQualityInTransaction(db,source,target,()->false);
                    throw new IllegalStateException("rollback");
                });
                fail("Expected rollback");
            }catch(IllegalStateException expected){assertEquals("rollback",expected.getMessage());}
            assertNull(db.manualProbeQualityDao().find(target,"k0512"));
            assertEquals(Integer.valueOf(999),db.manualProbeQualityDao().find(target,"k0256").score);
            db.runInTransaction(()->ManualProbeQualityStore.copyQualityInTransaction(db,source,target,()->false));
            assertEquals(514,db.manualProbeQualityDao().page(target,"",600).size());
            assertEquals(Integer.valueOf(0),db.manualProbeQualityDao().find(target,"k0000").score);
            assertEquals(Integer.valueOf(512),db.manualProbeQualityDao().find(target,"k0512").score);
            assertEquals(Integer.valueOf(2),db.manualProbeQualityDao().find(target,"k0255").failureStreak);
            assertNull(db.manualProbeQualityDao().find(target,"k0256").score);
            assertNull(db.manualProbeQualityDao().find(target,"k0256").failureStreak);
            assertEquals(Integer.valueOf(700),db.manualProbeQualityDao().find(target,"unrelated").score);
            assertEquals(513,db.manualProbeQualityDao().page(source,"",600).size());
        }finally{db.close();}
    }


    @Test public void previewSqlCopyDoesNotReplaceMissingPrivateKeysOrUnpublishedRoutes(){
        android.content.Context context=RuntimeEnvironment.getApplication();
        AppDatabase db=Room.inMemoryDatabaseBuilder(context,AppDatabase.class).allowMainThreadQueries().build();
        try{
            String initial=ManualProbeQualityStore.INITIAL_OWNER,published=ManualProbeQualityStore.PUBLISHED_OWNER;
            java.util.List<String> visible=new java.util.ArrayList<>();
            java.util.List<ManualProbeQualityEntity> rows=new java.util.ArrayList<>();
            for(int n=0;n<300;n++){
                String url="https://example.invalid/visible/"+n;
                visible.add(url);
                String key=com.wodedianshi.tv.StableKey.url(url);
                rows.add(new ManualProbeQualityEntity(initial,key,n==150?null:Integer.valueOf(1000+n),null));
            }
            String hidden=com.wodedianshi.tv.StableKey.url("https://example.invalid/hidden");
            String inheritedUrl="https://example.invalid/inherited",inherited=com.wodedianshi.tv.StableKey.url(inheritedUrl);
            String cleared=com.wodedianshi.tv.StableKey.url(visible.get(150));
            rows.add(new ManualProbeQualityEntity(initial,hidden,1800,null));
            db.manualProbeQualityDao().put(java.util.Arrays.asList(
                new ManualProbeQualityEntity(published,inherited,900,2),
                new ManualProbeQualityEntity(published,cleared,700,1)));
            db.runInTransaction(()->ManualProbeQualityStore.seedInitialInTransaction(db,rows,()->false));
            visible.add(inheritedUrl);
            db.runInTransaction(()->ManualProbeQualityStore.publishInitialPreviewInTransaction(db,visible,()->false));
            assertEquals(301,db.manualProbeQualityDao().page(published,"",400).size());
            assertEquals(Integer.valueOf(1299),db.manualProbeQualityDao().find(published,com.wodedianshi.tv.StableKey.url(visible.get(299))).score);
            assertEquals(Integer.valueOf(900),db.manualProbeQualityDao().find(published,inherited).score);
            assertEquals(Integer.valueOf(2),db.manualProbeQualityDao().find(published,inherited).failureStreak);
            assertNull(db.manualProbeQualityDao().find(published,cleared).score);
            assertNull(db.manualProbeQualityDao().find(published,cleared).failureStreak);
            assertNull(db.manualProbeQualityDao().find(published,hidden));
            assertEquals(Integer.valueOf(1800),db.manualProbeQualityDao().find(initial,hidden).score);
            assertTrue(ManualProbeQualityStore.hasInitial(db));
        }finally{db.close();}
    }

}
