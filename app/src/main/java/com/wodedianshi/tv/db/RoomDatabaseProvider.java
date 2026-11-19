package com.wodedianshi.tv.db;

import android.content.Context;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public final class RoomDatabaseProvider {
    private static volatile AppDatabase instance;

    // Version 1 was the initial catalogue schema. Version 2 adds only the
    // migration checkpoint table; preserve all existing rows during upgrade.
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS catalogue_migrations ("
                    + "migrationKey TEXT NOT NULL, "
                    + "channelsCount INTEGER NOT NULL, "
                    + "backupSourcesCount INTEGER NOT NULL, "
                    + "favoritesCount INTEGER NOT NULL, "
                    + "completedAt INTEGER NOT NULL, "
                    + "PRIMARY KEY(migrationKey))");
        }
    };

    // Version 3 records the exact legacy snapshot fingerprint used by the shadow import.
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE catalogue_migrations "
                    + "ADD COLUMN catalogueFingerprint TEXT NOT NULL DEFAULT ''");
        }
    };

    // Version 4 also stores category count so every imported table is verified.
    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE catalogue_migrations "
                    + "ADD COLUMN categoriesCount INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Version 5 replaces the single-column category index with indexes matching
    // the paged catalogue sort order. No channel, source, or favorite rows are removed.
    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("DROP INDEX IF EXISTS index_channels_categoryId");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "index_channels_categoryId_sortOrder_name_id "
                    + "ON channels(categoryId, sortOrder, name, id)");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "index_channels_sortOrder_name_id "
                    + "ON channels(sortOrder, name, id)");
        }
    };

    private RoomDatabaseProvider() {}

    public static AppDatabase get(Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (RoomDatabaseProvider.class) {
                local = instance;
                if (local == null) {
                    local = build(context, "mytv_catalogue_authoritative.db");
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

    private static AppDatabase build(Context context, String databaseName) {
        return Room.databaseBuilder(
                context.getApplicationContext(),
                AppDatabase.class,
                databaseName
        ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5
        ).build();
    }
}
