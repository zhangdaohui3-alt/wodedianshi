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
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY sortOrder, name, id")
    List<ChannelWithSources> getChannelsByCategory(String categoryId);

    // Lightweight paged projection for startup/category rendering. Unlike
    // ChannelWithSources, this query does not load backup_sources relations.
    @Query("SELECT id, name, primaryUrl, groupName, categoryId, tvgId, tvgName, "
            + "activeSource, sortOrder FROM channels WHERE categoryId = :categoryId "
            + "ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelListRow> getChannelListPage(String categoryId, int limit, int offset);

    @Query("SELECT id, name, primaryUrl, groupName, categoryId, tvgId, tvgName, "
            + "activeSource, sortOrder FROM channels ORDER BY sortOrder, name, id "
            + "LIMIT :limit OFFSET :offset")
    List<ChannelListRow> getAllChannelListPage(int limit, int offset);

    // Load source URLs only after a user selects a channel.
    @Query("SELECT * FROM backup_sources WHERE channelId = :channelId ORDER BY priority, url")
    List<BackupSourceEntity> getBackupSourcesForChannel(String channelId);

    // Bounded reads for TV lists: avoid materializing every channel and every
    // backup-source relation merely to render the first screen of a category.
    @Transaction
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId "
            + "ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelWithSources> getChannelsPage(String categoryId, int limit, int offset);

    @Transaction
    @Query("SELECT * FROM channels ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelWithSources> getAllChannelsPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId")
    int countChannelsInCategory(String categoryId);

    @Transaction
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.channelId = c.id ORDER BY f.addedAt DESC")
    List<ChannelWithSources> getFavoriteChannels();

    @Transaction
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' OR tvgName LIKE '%' || :query || '%' ORDER BY sortOrder, name, id")
    List<ChannelWithSources> searchChannels(String query);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addFavorite(FavoriteEntity favorite);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMigration(CatalogueMigrationEntity migration);

    @Query("SELECT * FROM catalogue_migrations WHERE migrationKey = :key LIMIT 1")
    CatalogueMigrationEntity getMigration(String key);

    @Query("DELETE FROM catalogue_migrations WHERE migrationKey = :key")
    void removeMigration(String key);

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    void removeFavorite(String channelId);

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    boolean isFavorite(String channelId);

    // Keep the Room shadow aligned with explicit source choices without relying
    // on an array index that may differ after a source-ranking refresh.
    @Query("UPDATE channels SET activeSource = (SELECT priority FROM backup_sources "
            + "WHERE channelId = :channelId AND url = :sourceUrl LIMIT 1) "
            + "WHERE id = :channelId AND EXISTS (SELECT 1 FROM backup_sources "
            + "WHERE channelId = :channelId AND url = :sourceUrl)")
    int setActiveSourceByUrl(String channelId, String sourceUrl);

    // Legacy favorites are keyed by name|primaryUrl. Update every matching row
    // so duplicate legacy entries keep the same behavior as the old Set<String>.
    @Query("SELECT id FROM channels WHERE legacyKey = :legacyKey")
    List<String> getChannelIdsByLegacyKey(String legacyKey);

    // Scoped catalogue row IDs differ from IDs in the unfiltered master list.
    @Query("UPDATE channels SET activeSource = (SELECT priority FROM backup_sources "
            + "WHERE channelId = channels.id AND url = :sourceUrl LIMIT 1) "
            + "WHERE legacyKey = :legacyKey AND EXISTS (SELECT 1 FROM backup_sources "
            + "WHERE channelId = channels.id AND url = :sourceUrl)")
    int setActiveSourceByLegacyKeyAndUrl(String legacyKey, String sourceUrl);

    @Query("UPDATE catalogue_migrations SET favoritesCount = (SELECT COUNT(*) FROM favorites) "
            + "WHERE migrationKey = :migrationKey")
    int refreshFavoriteCheckpointCount(String migrationKey);

    @Query("DELETE FROM favorites WHERE channelId IN "
            + "(SELECT id FROM channels WHERE legacyKey = :legacyKey)")
    void removeFavoritesForLegacyKey(String legacyKey);

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
