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
}
