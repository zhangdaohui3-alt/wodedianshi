package com.wodedianshi.tv.db;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Read-only access to the validated China catalogue snapshot.
 *
 * All methods are blocking Room calls and MUST be invoked from a background
 * executor, never from the Android main thread.
 *
 * The importer replaces the rows inside one SQLite transaction. Readers see
 * the previous committed catalogue until that transaction commits, then see
 * the complete replacement; they can never observe the import's intermediate
 * DELETE/INSERT state. The migration checkpoint and row counts are checked
 * before every read so a partial or stale shadow is never treated as active.
 *
 * This repository intentionally does not make Room authoritative yet. The
 * legacy catalogue remains the playback/editing source until every mutation
 * path has write-through coverage.
 */
public final class ChinaCatalogueRepository {
    private static final String MIGRATION_KEY =
            LegacyCatalogueImporter.MIGRATION_KEY + "_china_partition_v2";

    private final ChannelDao dao;

    public ChinaCatalogueRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        dao = RoomDatabaseProvider.getChina(context.getApplicationContext()).channelDao();
    }

    /** True only when the last China import committed and all row counts still match. */
    public boolean isSnapshotReady() {
        CatalogueMigrationEntity checkpoint = dao.getMigration(MIGRATION_KEY);
        if (checkpoint == null || checkpoint.channelsCount <= 0) return false;
        return dao.countChannels() == checkpoint.channelsCount
                && dao.countBackupSources() == checkpoint.backupSourcesCount
                && dao.countFavorites() == checkpoint.favoritesCount
                && dao.countCategories() == checkpoint.categoriesCount;
    }

    /**
     * Read a small page without hydrating backup-source relations.
     * Returns an empty immutable list when no validated China snapshot exists.
     */
    public List<ChannelListRow> getPage(String categoryId, int limit, int offset) {
        if (limit <= 0 || offset < 0 || !isSnapshotReady()) {
            return Collections.emptyList();
        }
        if (categoryId == null || categoryId.isEmpty()) {
            return dao.getAllChannelListPage(limit, offset);
        }
        return dao.getChannelListPage(categoryId, limit, offset);
    }

    /** Fetch a bounded favorites page without loading source relations. */
    public List<ChannelListRow> getFavoritePage(int limit, int offset) {
        if (limit <= 0 || offset < 0 || !isSnapshotReady()) return Collections.emptyList();
        return dao.getFavoriteChannelListPage(limit, offset);
    }

    public int getFavoriteCount() {
        return isSnapshotReady() ? dao.countFavoriteChannels() : -1;
    }

    /** Search a bounded result page without hydrating every backup URL. */
    public List<ChannelListRow> getSearchPage(String query, int limit, int offset) {
        if (query == null || query.trim().isEmpty() || limit <= 0 || offset < 0
                || !isSnapshotReady()) return Collections.emptyList();
        return dao.getSearchChannelListPage(query.trim(), limit, offset);
    }

    public int getSearchCount(String query) {
        if (query == null || query.trim().isEmpty() || !isSnapshotReady()) return -1;
        return dao.countSearchChannels(query.trim());
    }

    /** Load playback candidates only after the selected channel is known. */
    public List<BackupSourceEntity> getSourcesForSelectedChannel(String channelId) {
        if (channelId == null || channelId.isEmpty() || !isSnapshotReady()) {
            return Collections.emptyList();
        }
        return dao.getBackupSourcesForChannel(channelId);
    }

    /** Total row count for paging controls; -1 means the snapshot is not ready. */
    public int getChannelCount(String categoryId) {
        if (!isSnapshotReady()) return -1;
        return categoryId == null || categoryId.isEmpty()
                ? dao.countChannels() : dao.countChannelsInCategory(categoryId);
    }
}
