package com.wodedianshi.tv.db;

import android.content.Context;

/**
 * Write-through bridge for scoped Room catalogues.
 * Scoped imports assign IDs within each catalogue, so updates use the stable
 * legacy channel identity rather than an index or an ID from the master list.
 */
public final class RoomShadowSync {
    private RoomShadowSync() {}

    public static void setFavorite(Context context, String legacyKey, boolean favorite) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()) return;
        Context app = context.getApplicationContext();
        syncFavorite(RoomDatabaseProvider.getChina(app).channelDao(),
                LegacyCatalogueImporter.MIGRATION_KEY + "_china_partition_v2", legacyKey, favorite);
        syncFavorite(RoomDatabaseProvider.getInternational(app).channelDao(),
                LegacyCatalogueImporter.MIGRATION_KEY + "_international_partition_v2", legacyKey, favorite);
        syncFavorite(RoomDatabaseProvider.get(app).channelDao(),
                LegacyCatalogueImporter.MIGRATION_KEY, legacyKey, favorite);
    }

    private static void syncFavorite(ChannelDao dao, String migrationKey,
            String legacyKey, boolean favorite) {
        if (dao.getMigration(migrationKey) == null) return;
        java.util.List<String> ids = dao.getChannelIdsByLegacyKey(legacyKey);
        long addedAt = System.currentTimeMillis();
        for (String id : ids) {
            if (favorite) dao.addFavorite(new FavoriteEntity(id, addedAt));
            else dao.removeFavorite(id);
        }
        // Keep snapshot integrity metadata consistent with this committed favorite mutation.
        dao.refreshFavoriteCheckpointCount(migrationKey);
    }

    public static boolean setActiveSourceUrl(Context context, String legacyKey, String sourceUrl) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()
                || sourceUrl == null || sourceUrl.isEmpty()) return false;
        Context app = context.getApplicationContext();
        boolean updated = false;
        updated |= syncActiveSource(RoomDatabaseProvider.getChina(app).channelDao(),
                LegacyCatalogueImporter.MIGRATION_KEY + "_china_partition_v2", legacyKey, sourceUrl);
        updated |= syncActiveSource(RoomDatabaseProvider.getInternational(app).channelDao(),
                LegacyCatalogueImporter.MIGRATION_KEY + "_international_partition_v2", legacyKey, sourceUrl);
        updated |= syncActiveSource(RoomDatabaseProvider.get(app).channelDao(),
                LegacyCatalogueImporter.MIGRATION_KEY, legacyKey, sourceUrl);
        return updated;
    }

    private static boolean syncActiveSource(ChannelDao dao, String migrationKey,
            String legacyKey, String sourceUrl) {
        if (dao.getMigration(migrationKey) == null) return false;
        return dao.setActiveSourceByLegacyKeyAndUrl(legacyKey, sourceUrl) > 0;
    }
}
