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
            LegacyCatalogueImporter.MIGRATION_KEY + "_international";

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
