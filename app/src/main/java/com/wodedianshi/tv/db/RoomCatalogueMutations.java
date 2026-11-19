package com.wodedianshi.tv.db;

import android.content.Context;
import java.util.List;

/** Direct Room mutations for user actions that change favorites or active source. */
public final class RoomCatalogueMutations {
    private RoomCatalogueMutations() {}

    public static void setFavorite(Context context, String legacyKey, boolean favorite) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()) return;
        AppDatabase database = RoomDatabaseProvider.get(context.getApplicationContext());
        ChannelDao dao = database.channelDao();
        database.runInTransaction(() -> {
            List<String> ids = dao.getChannelIdsByLegacyKey(legacyKey);
            if (ids == null || ids.isEmpty()) return;
            long addedAt = System.currentTimeMillis();
            for (String id : ids) {
                if (favorite) dao.addFavorite(new FavoriteEntity(id, addedAt));
                else dao.removeFavorite(id);
            }
            String migrationKey = MasterCatalogueRepository.MIGRATION_KEY;
            if (dao.getMigration(migrationKey) != null) {
                dao.refreshFavoriteCheckpointCount(migrationKey,
                        Math.max(System.currentTimeMillis(), dao.getMigration(migrationKey).completedAt + 1L));
            }
        });
    }

    public static boolean setActiveSourceUrl(Context context, String legacyKey, String sourceUrl) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()
                || sourceUrl == null || sourceUrl.isEmpty()) return false;
        AppDatabase database = RoomDatabaseProvider.get(context.getApplicationContext());
        ChannelDao dao = database.channelDao();
        final boolean[] changed = {false};
        database.runInTransaction(() -> {
            changed[0] = dao.setActiveSourceByLegacyKeyAndUrl(legacyKey, sourceUrl) > 0;
            if (changed[0] && dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY) != null) {
                CatalogueMigrationEntity checkpoint =
                        dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY);
                dao.touchCatalogueCheckpoint(MasterCatalogueRepository.MIGRATION_KEY,
                        Math.max(System.currentTimeMillis(), checkpoint.completedAt + 1L));
            }
        });
        return changed[0];
    }
}
