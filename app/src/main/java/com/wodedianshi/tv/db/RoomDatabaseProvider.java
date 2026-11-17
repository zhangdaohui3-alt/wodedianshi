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
                        "mytv_catalogue.db"
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
                    instance = local;
                }
            }
        }
        return local;
    }
}
