package com.wodedianshi.tv.db;

import android.content.Context;
import java.util.List;

/** Direct Room mutations for user actions that change favorites or active source. */
public final class RoomCatalogueMutations {
    private RoomCatalogueMutations() {}

    public static void setFavorite(Context context, String legacyKey, boolean favorite) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()) return;
        ChannelDao dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
        List<String> ids = dao.getChannelIdsByLegacyKey(legacyKey);
        if (ids == null || ids.isEmpty()) return;
        long addedAt = System.currentTimeMillis();
        for (String id : ids) {
            if (favorite) dao.addFavorite(new FavoriteEntity(id, addedAt));
            else dao.removeFavorite(id);
        }
        String migrationKey = MasterCatalogueRepository.MIGRATION_KEY;
        if (dao.getMigration(migrationKey) != null) {
            dao.refreshFavoriteCheckpointCount(migrationKey);
        }
    }

    public static boolean setActiveSourceUrl(Context context, String legacyKey, String sourceUrl) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()
                || sourceUrl == null || sourceUrl.isEmpty()) return false;
        ChannelDao dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
        return dao.setActiveSourceByLegacyKeyAndUrl(legacyKey, sourceUrl) > 0;
    }
}
