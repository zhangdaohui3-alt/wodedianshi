package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Durable migration checkpoint stored atomically with the imported catalogue. */
@Entity(tableName = "catalogue_migrations")
public class CatalogueMigrationEntity {
    @PrimaryKey @NonNull public String migrationKey;
    public int channelsCount;
    public int backupSourcesCount;
    public int favoritesCount;
    public long completedAt;

    public CatalogueMigrationEntity(@NonNull String migrationKey, int channelsCount,
            int backupSourcesCount, int favoritesCount, long completedAt) {
        this.migrationKey = migrationKey;
        this.channelsCount = channelsCount;
        this.backupSourcesCount = backupSourcesCount;
        this.favoritesCount = favoritesCount;
        this.completedAt = completedAt;
    }
}
