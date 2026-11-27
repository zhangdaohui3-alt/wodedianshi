package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.PrimaryKey;

/** Durable migration checkpoint stored atomically with the imported catalogue. */
@Entity(tableName = "catalogue_migrations")
public class CatalogueMigrationEntity {
    @PrimaryKey @NonNull public String migrationKey;
    @NonNull @ColumnInfo(defaultValue = "''") public String catalogueFingerprint;
    public int channelsCount;
    public int backupSourcesCount;
    public int favoritesCount;
    @ColumnInfo(defaultValue = "0") public int categoriesCount;
    public long completedAt;

    public CatalogueMigrationEntity(@NonNull String migrationKey, int channelsCount,
            int backupSourcesCount, int favoritesCount, int categoriesCount, long completedAt, @NonNull String catalogueFingerprint) {
        this.migrationKey = migrationKey;
        this.catalogueFingerprint = catalogueFingerprint;
        this.channelsCount = channelsCount;
        this.backupSourcesCount = backupSourcesCount;
        this.favoritesCount = favoritesCount;
        this.categoriesCount = categoriesCount;
        this.completedAt = completedAt;
    }
}
