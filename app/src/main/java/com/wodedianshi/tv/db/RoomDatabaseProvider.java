package com.wodedianshi.tv.db;

import android.content.Context;
import androidx.room.Room;

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
                        "mytv_catalogue.db"
                    ).build();
                    instance = local;
                }
            }
        }
        return local;
    }
}
