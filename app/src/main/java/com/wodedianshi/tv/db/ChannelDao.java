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

    // Write-through primitives: update an existing channel row in place rather
    // than REPLACE it. SQLite REPLACE deletes the old row first and would cascade
    // delete its favorites and backup sources through the declared foreign keys.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertCategoryIfAbsent(CategoryEntity category);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertChannelIfAbsent(ChannelEntity channel);

    @Query("UPDATE channels SET legacyKey = :legacyKey, name = :name, "
            + "primaryUrl = :primaryUrl, groupName = :groupName, categoryId = :categoryId, "
            + "tvgId = :tvgId, tvgName = :tvgName, channelScopesJson = :scopesJson, "
            + "activeSource = :activeSource, sortOrder = :sortOrder WHERE id = :id "
            + "AND (legacyKey != :legacyKey OR name != :name OR primaryUrl != :primaryUrl "
            + "OR groupName != :groupName OR categoryId != :categoryId OR tvgId != :tvgId "
            + "OR tvgName != :tvgName OR channelScopesJson != :scopesJson "
            + "OR activeSource != :activeSource OR sortOrder != :sortOrder)")
    void updateChannelFields(String id, String legacyKey, String name, String primaryUrl,
            String groupName, String categoryId, String tvgId, String tvgName,
            String scopesJson, int activeSource, int sortOrder);

    @Query("DELETE FROM backup_sources WHERE channelId = :channelId")
    void clearBackupSourcesForChannel(String channelId);

    @Query("DELETE FROM backup_sources WHERE channelId = :channelId AND url = :url")
    void deleteBackupSource(String channelId, String url);

    @Transaction
    default void writeChannelWithSources(CategoryEntity category, ChannelEntity channel,
            List<BackupSourceEntity> sources) {
        insertCategoryIfAbsent(category);
        insertChannelIfAbsent(channel);
        updateChannelFields(channel.id, channel.legacyKey, channel.name, channel.primaryUrl,
                channel.groupName, channel.categoryId, channel.tvgId, channel.tvgName,
                channel.channelScopesJson, channel.activeSource, channel.sortOrder);
        // Merge source rows instead of clearing the relation. A partial feed
        // refresh must never erase previously verified fallback URLs that were
        // absent from this particular update batch.
        if (sources != null && !sources.isEmpty()) insertBackupSources(sources);
    }

    // Exact source-list replacement for a complete catalogue snapshot. The
    // channel row is updated in place, so favorites remain attached; only that
    // channel's obsolete backup URL rows are replaced.
    @Transaction
    default void replaceChannelWithSources(CategoryEntity category, ChannelEntity channel,
            List<BackupSourceEntity> sources) {
        insertCategoryIfAbsent(category);
        insertChannelIfAbsent(channel);
        updateChannelFields(channel.id, channel.legacyKey, channel.name, channel.primaryUrl,
                channel.groupName, channel.categoryId, channel.tvgId, channel.tvgName,
                channel.channelScopesJson, channel.activeSource, channel.sortOrder);
        clearBackupSourcesForChannel(channel.id);
        if (sources != null && !sources.isEmpty()) insertBackupSources(sources);
    }

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    List<CategoryEntity> getCategories();

    @Query("SELECT id FROM categories")
    List<String> getAllCategoryIds();

    @Query("UPDATE categories SET name = :name, sortOrder = :sortOrder WHERE id = :id "
            + "AND (name != :name OR sortOrder != :sortOrder)")
    void updateCategoryFields(String id, String name, int sortOrder);

    @Query("DELETE FROM categories WHERE id = :id")
    void deleteCategoryById(String id);

    @Query("SELECT id FROM channels")
    List<String> getAllChannelIds();

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    ChannelEntity getChannelById(String id);

    @Query("DELETE FROM channels WHERE id = :id")
    void deleteChannelById(String id);

    @Query("SELECT channelId FROM favorites")
    List<String> getFavoriteChannelIds();

    @Query("SELECT addedAt FROM favorites WHERE channelId = :channelId LIMIT 1")
    Long getFavoriteAddedAt(String channelId);

    @Transaction
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY sortOrder, name, id")
    List<ChannelWithSources> getChannelsByCategory(String categoryId);

    // Lightweight paged projection for startup/category rendering. Unlike
    // ChannelWithSources, this query does not load backup_sources relations.
    @Query("SELECT id, legacyKey, name, primaryUrl, groupName, categoryId, tvgId, tvgName, "
            + "activeSource, sortOrder FROM channels WHERE categoryId = :categoryId "
            + "ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelListRow> getChannelListPage(String categoryId, int limit, int offset);

    @Query("SELECT id, legacyKey, name, primaryUrl, groupName, categoryId, tvgId, tvgName, "
            + "activeSource, sortOrder FROM channels ORDER BY sortOrder, name, id "
            + "LIMIT :limit OFFSET :offset")
    List<ChannelListRow> getAllChannelListPage(int limit, int offset);

    // Load source URLs only after a user selects a channel.
    @Query("SELECT * FROM backup_sources WHERE channelId = :channelId ORDER BY priority, url")
    List<BackupSourceEntity> getBackupSourcesForChannel(String channelId);

    // Manual final publication needs current selected URL, not the old index.
    final class ActiveSelection {
        public String channelId;
        public String legacyKey;
        public String url;
    }
    @Query("SELECT c.id AS channelId, c.legacyKey AS legacyKey, b.url AS url "
            + "FROM channels c INNER JOIN backup_sources b ON b.channelId = c.id "
            + "AND b.priority = c.activeSource WHERE c.id IN (:channelIds)")
    List<ActiveSelection> getActiveSelectionsForChannels(List<String> channelIds);

    // Only the snapshot writer calls this, with at most 64 channel IDs.
    @Query("SELECT * FROM backup_sources WHERE channelId IN (:channelIds)")
    List<BackupSourceEntity> getBackupSourcesForChannels(List<String> channelIds);

    // Bounded reads for TV lists: avoid materializing every channel and every
    // backup-source relation merely to render the first screen of a category.
    @Transaction
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId "
            + "ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelWithSources> getChannelsPage(String categoryId, int limit, int offset);

    @Transaction
    @Query("SELECT * FROM channels ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelWithSources> getAllChannelsPage(int limit, int offset);

    // Recheck membership does not need source scores, scopes, order or metadata.
    // A transaction keeps the projected parent and relation reads consistent.
    final class RouteMembership {
        public String id;
        public String name;
        public String legacyKey;
        public String primaryUrl;
        @androidx.room.Relation(entity = BackupSourceEntity.class,
                parentColumn = "id", entityColumn = "channelId", projection = {"url"})
        public List<String> urls;
    }

    @Transaction
    @Query("SELECT id, name, legacyKey, primaryUrl FROM channels "
            + "ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<RouteMembership> getRouteMembershipPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId")
    int countChannelsInCategory(String categoryId);

    @Transaction
    @Query("SELECT c.* FROM channels c INNER JOIN favorites f ON f.channelId = c.id ORDER BY f.addedAt DESC")
    List<ChannelWithSources> getFavoriteChannels();

    @Transaction
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' OR tvgName LIKE '%' || :query || '%' ORDER BY sortOrder, name, id")
    List<ChannelWithSources> searchChannels(String query);

    @Query("SELECT c.id, c.legacyKey, c.name, c.primaryUrl, c.groupName, c.categoryId, c.tvgId, "
            + "c.tvgName, c.activeSource, c.sortOrder FROM channels c "
            + "INNER JOIN favorites f ON f.channelId = c.id "
            + "ORDER BY f.addedAt DESC, c.sortOrder, c.name, c.id "
            + "LIMIT :limit OFFSET :offset")
    List<ChannelListRow> getFavoriteChannelListPage(int limit, int offset);

    @Query("SELECT COUNT(*) FROM channels c INNER JOIN favorites f ON f.channelId = c.id")
    int countFavoriteChannels();

    // Search projections stay lightweight too: the list screen does not need
    // to load every backup-source relation just to show matching channel names.
    @Query("SELECT id, legacyKey, name, primaryUrl, groupName, categoryId, tvgId, tvgName, "
            + "activeSource, sortOrder FROM channels "
            + "WHERE name LIKE '%' || :query || '%' OR tvgName LIKE '%' || :query || '%' "
            + "ORDER BY sortOrder, name, id LIMIT :limit OFFSET :offset")
    List<ChannelListRow> getSearchChannelListPage(String query, int limit, int offset);

    @Query("SELECT COUNT(*) FROM channels "
            + "WHERE name LIKE '%' || :query || '%' OR tvgName LIKE '%' || :query || '%'")
    int countSearchChannels(String query);

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

    @Query("UPDATE catalogue_migrations SET favoritesCount = (SELECT COUNT(*) FROM favorites), "
            + "completedAt = :completedAt WHERE migrationKey = :migrationKey")
    int refreshFavoriteCheckpointCount(String migrationKey, long completedAt);

    @Query("UPDATE catalogue_migrations SET completedAt = :completedAt "
            + "WHERE migrationKey = :migrationKey")
    int touchCatalogueCheckpoint(String migrationKey, long completedAt);

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

    /** Checkpoint and table counts must describe the same committed snapshot. */
    @Transaction
    default CatalogueMigrationEntity getValidatedCheckpoint(String migrationKey) {
        CatalogueMigrationEntity checkpoint = getMigration(migrationKey);
        if (checkpoint == null || checkpoint.channelsCount < 0
                || checkpoint.backupSourcesCount < 0 || checkpoint.favoritesCount < 0
                || checkpoint.categoriesCount < 0) return null;
        return countChannels() == checkpoint.channelsCount
                && countBackupSources() == checkpoint.backupSourcesCount
                && countFavorites() == checkpoint.favoritesCount
                && countCategories() == checkpoint.categoriesCount ? checkpoint : null;
    }

    @Query("SELECT COUNT(*) FROM categories")
    int countCategories();

    @Query("SELECT COUNT(*) FROM channels")
    int countChannels();

    @Query("SELECT COUNT(*) FROM backup_sources")
    int countBackupSources();

    @Query("SELECT COUNT(*) FROM favorites")
    int countFavorites();

    @Query("SELECT c.legacyKey FROM channels c INNER JOIN favorites f ON f.channelId = c.id ORDER BY f.addedAt DESC")
    List<String> getFavoriteLegacyKeys();

    // Isolated full-rebuild staging. These rows are never visible to the live UI.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCatalogueBuildRows(List<CatalogueBuildRowEntity> rows);

    @Query("SELECT * FROM catalogue_build_rows ORDER BY sortOrder, rowId")
    List<CatalogueBuildRowEntity> getCatalogueBuildRows();

    @Query("SELECT * FROM catalogue_build_rows WHERE mergeIdentity IN (:identities) ORDER BY sortOrder,rowId")
    List<CatalogueBuildRowEntity> getCatalogueBuildRowsByIdentity(List<String> identities);

    @Query("UPDATE catalogue_build_rows SET mergeIdentity=:identity WHERE rowId=:rowId")
    int setCatalogueBuildIdentity(String rowId,String identity);

    @Query("SELECT COUNT(*) FROM catalogue_build_rows")
    int countCatalogueBuildRows();

    @Query("SELECT COUNT(*) FROM catalogue_build_rows WHERE mergeIdentity=''")
    int countUnindexedCatalogueBuildRows();

    @Query("SELECT * FROM catalogue_build_rows WHERE mergeIdentity='' ORDER BY sortOrder,rowId LIMIT :limit")
    List<CatalogueBuildRowEntity> getUnindexedCatalogueBuildRows(int limit);

    @Query("DELETE FROM catalogue_build_rows")
    void clearCatalogueBuildRows();

    @Query("DELETE FROM catalogue_build_rows WHERE sortOrder >= :size")
    void deleteCatalogueBuildRowsFrom(int size);

    @Transaction
    default void replaceCatalogueBuildRows(List<CatalogueBuildRowEntity> rows) {
        clearCatalogueBuildRows();
        if (rows != null && !rows.isEmpty()) insertCatalogueBuildRows(rows);
    }

    @Transaction
    default void mergeCatalogueBuildRows(List<CatalogueBuildRowEntity> changedRows, int size) {
        if (changedRows != null && !changedRows.isEmpty()) insertCatalogueBuildRows(changedRows);
        deleteCatalogueBuildRowsFrom(Math.max(0, size));
    }
}
