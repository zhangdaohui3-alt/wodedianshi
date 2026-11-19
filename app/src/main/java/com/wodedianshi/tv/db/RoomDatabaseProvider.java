package com.wodedianshi.tv.db;

import android.content.Context;
import androidx.room.Room;

/**
 * Provides the one authoritative catalogue database.
 *
 * The clean-start catalogue uses a new database filename intentionally: older
 * app builds kept an incomplete shadow Room catalogue. We do not import or
 * migrate that data; the current bundled catalogue seeds this fresh database.
 */
public final class RoomDatabaseProvider {
    private static volatile AppDatabase instance;

    private RoomDatabaseProvider() {}

    public static AppDatabase get(Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (RoomDatabaseProvider.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "mytv_catalogue_clean_v1.db"
                    ).build();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Compatibility alias: regional catalogues are scope-filtered views of the
     * one authoritative catalogue database. Never create a second SQLite store.
     */
    @Deprecated
    public static AppDatabase getChina(Context context) {
        return get(context);
    }

    /** Compatibility alias for the single authoritative catalogue database. */
    @Deprecated
    public static AppDatabase getInternational(Context context) {
        return get(context);
    }
}
