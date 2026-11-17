package com.wodedianshi.tv.db;

import android.content.Context;

/**
 * Small write-through bridge for user actions while Room is still a shadow copy.
 *
 * The legacy SharedPreferences catalogue remains authoritative. These updates
 * only keep the already-imported Room copy closer to the user's latest explicit
 * favorite/source choices; they never create missing catalogue rows or trigger a
 * full 11k-row re-import from a playback callback.
 */
public final class RoomShadowSync {
    private RoomShadowSync() {}

    public static void setFavorite(Context context, String legacyKey, boolean favorite) {
        if (context == null || legacyKey == null || legacyKey.isEmpty()) return;
        ChannelDao dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
        // A missing checkpoint means the initial shadow import has not completed.
        // Do not manufacture partial rows or make Room authoritative by accident.
        if (dao.getMigration(LegacyCatalogueImporter.MIGRATION_KEY) == null) return;
        if (favorite) {
            dao.addFavoritesForLegacyKey(legacyKey, System.currentTimeMillis());
        } else {
            dao.removeFavoritesForLegacyKey(legacyKey);
        }
    }

    public static boolean setActiveSourceUrl(Context context, String channelId, String sourceUrl) {
        if (context == null || channelId == null || channelId.isEmpty()
                || sourceUrl == null || sourceUrl.isEmpty()) return false;
        ChannelDao dao = RoomDatabaseProvider.get(context.getApplicationContext()).channelDao();
        if (dao.getMigration(LegacyCatalogueImporter.MIGRATION_KEY) == null) return false;
        // URL-to-priority lookup prevents a stale in-memory index from selecting
        // the wrong backup after the playback engine reorders candidates.
        return dao.setActiveSourceByUrl(channelId, sourceUrl) > 0;
    }
}
