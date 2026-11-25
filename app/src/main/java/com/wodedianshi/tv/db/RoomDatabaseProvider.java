package com.wodedianshi.tv.db;

import android.content.Context;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Provides the one authoritative catalogue database.
 *
 * The clean-start catalogue uses a new database filename intentionally: older
 * app builds kept an incomplete shadow Room catalogue. We do not import or
 * migrate that data; the current bundled catalogue seeds this fresh database.
 */
public final class RoomDatabaseProvider {
    private static volatile AppDatabase instance;

    private static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS catalogue_build_rows ("
                    + "rowId TEXT NOT NULL, sortOrder INTEGER NOT NULL, "
                    + "recordJson TEXT NOT NULL, PRIMARY KEY(rowId))");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "index_catalogue_build_rows_sortOrder "
                    + "ON catalogue_build_rows(sortOrder)");
        }
    };

    private static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS manual_probe_queue ("
                + "owner TEXT NOT NULL, endpoint TEXT NOT NULL, url TEXT NOT NULL, "
                + "name TEXT NOT NULL, groupName TEXT NOT NULL, tvgId TEXT NOT NULL, tvgName TEXT NOT NULL, "
                + "ordinal INTEGER NOT NULL, processed INTEGER NOT NULL, PRIMARY KEY(owner,endpoint))");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_probe_queue_owner_processed_ordinal_endpoint "
                + "ON manual_probe_queue(owner,processed,ordinal,endpoint)");
        }
    };

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
                    ).addMigrations(MIGRATION_6_7,MIGRATION_7_8).build();
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
