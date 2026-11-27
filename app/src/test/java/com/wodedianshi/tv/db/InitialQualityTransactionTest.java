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

}
