package com.wodedianshi.tv.db;

import android.content.Context;

/**
 * Temporary bridge for the single Room shadow catalogue.
 *
 * MainActivity is still being moved to Room write-through. Until that cutover
 * is complete, mirror the two mutable fields that have explicit callbacks.
 * China and international are filters over this catalogue, not separate stores.
 */
public final class RoomShadowSync {
    private RoomShadowSync() {}

    public static void setFavorite(Context context, String legacyKey, boolean favorite) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()) return;
        ChannelDao dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
        String migrationKey = LegacyCatalogueImporter.MIGRATION_KEY;
        if (dao.getMigration(migrationKey) == null) return;
        java.util.List<String> ids = dao.getChannelIdsByLegacyKey(legacyKey);
        long addedAt = System.currentTimeMillis();
        for (String id : ids) {
            if (favorite) dao.addFavorite(new FavoriteEntity(id, addedAt));
            else dao.removeFavorite(id);
        }
        dao.refreshFavoriteCheckpointCount(migrationKey);
    }

    public static boolean setActiveSourceUrl(Context context, String legacyKey, String sourceUrl) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()
                || sourceUrl == null || sourceUrl.isEmpty()) return false;
        ChannelDao dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
        String migrationKey = LegacyCatalogueImporter.MIGRATION_KEY;
        if (dao.getMigration(migrationKey) == null) return false;
        return dao.setActiveSourceByLegacyKeyAndUrl(legacyKey, sourceUrl) > 0;
    }
}
