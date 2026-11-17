package com.wodedianshi.tv.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCategory(CategoryEntity category);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCategories(List<CategoryEntity> categories);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChannels(List<ChannelEntity> channels);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertBackupSources(List<BackupSourceEntity> sources);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertFavorites(List<FavoriteEntity> favorites);

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    List<CategoryEntity> getCategories();

    @Transaction
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY sortOrder, name")
    List<ChannelWithSources> getChannelsByCategory(String categoryId);

    @Transaction
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.channelId = c.id ORDER BY f.addedAt DESC")
    List<ChannelWithSources> getFavoriteChannels();

    @Transaction
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' OR tvgName LIKE '%' || :query || '%' ORDER BY sortOrder, name")
    List<ChannelWithSources> searchChannels(String query);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addFavorite(FavoriteEntity favorite);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMigration(CatalogueMigrationEntity migration);

    @Query("SELECT * FROM catalogue_migrations WHERE migrationKey = :key LIMIT 1")
    CatalogueMigrationEntity getMigration(String key);

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    void removeFavorite(String channelId);

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    boolean isFavorite(String channelId);

    @Query("DELETE FROM backup_sources")
    void clearBackupSources();

    @Query("DELETE FROM favorites")
    void clearFavorites();

    @Query("DELETE FROM channels")
    void clearChannels();

    @Query("DELETE FROM categories")
    void clearCategories();

    @Query("SELECT COUNT(*) FROM categories")
    int countCategories();

    @Query("SELECT COUNT(*) FROM channels")
    int countChannels();

    @Query("SELECT COUNT(*) FROM backup_sources")
    int countBackupSources();

    @Query("SELECT COUNT(*) FROM favorites")
    int countFavorites();
}
