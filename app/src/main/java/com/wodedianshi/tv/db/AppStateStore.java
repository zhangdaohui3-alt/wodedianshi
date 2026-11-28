package com.wodedianshi.tv.db;

import android.content.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Background-thread-only access to durable non-setting app state in Room. */
public final class AppStateStore {
    private AppStateStore() {}

    public static String get(Context context, String key, String fallback) {
        if (context == null || key == null) throw new IllegalArgumentException("context/key is null");
        String value = RoomDatabaseProvider.get(context.getApplicationContext())
                .appStateDao().getValue(key);
        return value == null ? fallback : value;
    }

    /** Read related state keys together, without one query per key. */
    public static Map<String,String> getValues(Context context, List<String> keys) {
        Map<String,String> values = new HashMap<>();
        if (keys.isEmpty()) return values;
        for (AppStateEntity row : RoomDatabaseProvider.get(context.getApplicationContext())
                .appStateDao().getValues(keys)) {
            values.put(row.key, row.stateValue);
        }
        return values;
    }

    public static void put(Context context, String key, String value) {
        if (context == null || key == null || value == null) {
            throw new IllegalArgumentException("context/key/value is null");
        }
        RoomDatabaseProvider.get(context.getApplicationContext()).appStateDao()
                .put(new AppStateEntity(key, value, System.currentTimeMillis()));
    }

    public static void remove(Context context, String key) {
        if (context == null || key == null) return;
        RoomDatabaseProvider.get(context.getApplicationContext()).appStateDao().remove(key);
    }
}
