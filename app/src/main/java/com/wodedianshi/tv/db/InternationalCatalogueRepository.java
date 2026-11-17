package com.wodedianshi.tv.db;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Read-only access to the validated international catalogue snapshot.
 *
 * Blocking Room calls: invoke only from a background executor. This is a
 * shadow-read API; it does not change the legacy SharedPreferences authority.
 */
public final class InternationalCatalogueRepository {
    private static final String MIGRATION_KEY =
            LegacyCatalogueImporter.MIGRATION_KEY + "_international_partition_v2";

    private final ChannelDao dao;

    public InternationalCatalogueRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        dao = RoomDatabaseProvider.getInternational(
                context.getApplicationContext()).channelDao();
    }

    /** True only when the last international import committed and all row counts match. */
    public boolean isSnapshotReady() {
        CatalogueMigrationEntity checkpoint = dao.getMigration(MIGRATION_KEY);
        if (checkpoint == null || checkpoint.channelsCount <= 0) return false;
        return dao.countChannels() == checkpoint.channelsCount
                && dao.countBackupSources() == checkpoint.backupSourcesCount
                && dao.countFavorites() == checkpoint.favoritesCount
                && dao.countCategories() == checkpoint.categoriesCount;
    }

    /** Read a bounded page without loading backup-source relations. */
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
