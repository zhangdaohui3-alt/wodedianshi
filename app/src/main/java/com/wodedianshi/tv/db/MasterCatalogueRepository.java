package com.wodedianshi.tv.db;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Authoritative read access to the single Room catalogue, including custom
 * channels and entries outside the China/international display filters.
 * All calls are blocking Room operations and must run on a background executor.
 */
public final class MasterCatalogueRepository {
    public static final String MIGRATION_KEY = "room_native_catalogue_v1";
    private final ChannelDao dao;

    public MasterCatalogueRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
    }

    /** The master snapshot is usable only when every imported table matches its checkpoint. */
    public boolean isSnapshotReady() {
        CatalogueMigrationEntity checkpoint =
                dao.getMigration(MIGRATION_KEY);
        // A zero-row checkpoint is valid during first-install full discovery.
        // It is deliberately distinct from a missing or inconsistent checkpoint.
        if (checkpoint == null || checkpoint.channelsCount < 0
                || checkpoint.backupSourcesCount < 0
                || checkpoint.favoritesCount < 0
                || checkpoint.categoriesCount < 0) return false;
        return dao.countChannels() == checkpoint.channelsCount
                && dao.countBackupSources() == checkpoint.backupSourcesCount
                && dao.countFavorites() == checkpoint.favoritesCount
                && dao.countCategories() == checkpoint.categoriesCount;
    }

    /** Return the category index only after the complete master snapshot validates. */
    public List<CategoryEntity> getCategories() {
        return isSnapshotReady() ? dao.getCategories() : Collections.emptyList();
    }

    /** Fetch a bounded list page without loading backup-source relations. */
    public List<ChannelListRow> getPage(String categoryId, int limit, int offset) {
        if (limit <= 0 || offset < 0 || !isSnapshotReady()) return Collections.emptyList();
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

    /** Search a bounded result page without hydrating every channel's backup URLs. */
    public List<ChannelListRow> getSearchPage(String query, int limit, int offset) {
        if (query == null || query.trim().isEmpty() || limit <= 0 || offset < 0
                || !isSnapshotReady()) return Collections.emptyList();
        return dao.getSearchChannelListPage(query.trim(), limit, offset);
    }

    public int getSearchCount(String query) {
        if (query == null || query.trim().isEmpty() || !isSnapshotReady()) return -1;
        return dao.countSearchChannels(query.trim());
    }

    /** Load playback URLs only after a user selects a channel. */
    public List<BackupSourceEntity> getSourcesForSelectedChannel(String channelId) {
        if (channelId == null || channelId.isEmpty() || !isSnapshotReady()) {
            return Collections.emptyList();
        }
        return dao.getBackupSourcesForChannel(channelId);
    }

    /** Total row count for list paging; -1 means no validated snapshot is available. */
    public int getChannelCount(String categoryId) {
        if (!isSnapshotReady()) return -1;
        return categoryId == null || categoryId.isEmpty()
                ? dao.countChannels() : dao.countChannelsInCategory(categoryId);
    }
}
