package com.wodedianshi.tv.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {
    CategoryEntity.class,
    ChannelEntity.class,
    BackupSourceEntity.class,
    FavoriteEntity.class
}, version = 1, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ChannelDao channelDao();
}
