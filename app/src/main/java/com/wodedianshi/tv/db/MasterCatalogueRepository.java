package com.wodedianshi.tv.db;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Read-only access to the complete legacy catalogue shadow, including custom
 * channels and entries not assigned to the China/international partitions.
 *
 * All calls are blocking Room operations and must run on a background executor.
 * This repository deliberately does not switch MainActivity to Room: callers
 * must first validate parity and keep the SharedPreferences rollback snapshot.
 */
public final class MasterCatalogueRepository {
    private final ChannelDao dao;

    public MasterCatalogueRepository(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
    }

    /** The master snapshot is usable only when every imported table matches its checkpoint. */
    public boolean isSnapshotReady() {
        CatalogueMigrationEntity checkpoint =
                dao.getMigration(LegacyCatalogueImporter.MIGRATION_KEY);
        if (checkpoint == null || checkpoint.channelsCount <= 0) return false;
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
