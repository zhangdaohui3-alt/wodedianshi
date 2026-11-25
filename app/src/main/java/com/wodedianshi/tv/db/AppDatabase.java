package com.wodedianshi.tv.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {
    CategoryEntity.class,
    ChannelEntity.class,
    BackupSourceEntity.class,
    FavoriteEntity.class,
    CatalogueMigrationEntity.class,
    AppStateEntity.class,
    CatalogueBuildRowEntity.class,
    ManualProbeQueueEntity.class,
    ManualProbeQualityEntity.class
}, version = 9, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ChannelDao channelDao();
    public abstract AppStateDao appStateDao();
    public abstract ManualProbeQueueDao manualProbeQueueDao();
    public abstract ManualProbeQualityDao manualProbeQualityDao();
}
