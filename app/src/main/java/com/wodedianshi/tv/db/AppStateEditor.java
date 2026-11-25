package com.wodedianshi.tv.db;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Transactional editor for Room-backed application business state. */
public final class AppStateEditor {
    private final Context context;
    private final LinkedHashMap<String, String> updates = new LinkedHashMap<>();
    private final LinkedHashSet<String> removals = new LinkedHashSet<>();

    public AppStateEditor(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        this.context = context.getApplicationContext();
    }

    public AppStateEditor putString(String key, String value) {
        updates.put(key, value == null ? "" : value);
        removals.remove(key);
        return this;
    }

    public AppStateEditor putInt(String key, int value) {
        return putString(key, Integer.toString(value));
    }

    public AppStateEditor putLong(String key, long value) {
        return putString(key, Long.toString(value));
    }

    public AppStateEditor putBoolean(String key, boolean value) {
        return putString(key, Boolean.toString(value));
    }

    public AppStateEditor remove(String key) {
        updates.remove(key);
        removals.add(key);
        return this;
    }

    public void apply() {
        AppDatabase db = RoomDatabaseProvider.get(context);
        db.runInTransaction(() -> applyInTransaction(db));
    }

    /** The catalogue writer owns the outer transaction and its rollback. */
    void applyInTransaction(AppDatabase db) {
        if (!db.inTransaction()) throw new IllegalStateException("App state requires Room transaction");
        AppStateDao dao = db.appStateDao();
        ArrayList<AppStateEntity> rows = new ArrayList<>(updates.size());
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            rows.add(new AppStateEntity(entry.getKey(), entry.getValue(), now));
        }
        ArrayList<String> deleteKeys = new ArrayList<>(removals);
        if (!deleteKeys.isEmpty()) dao.removeAll(deleteKeys);
        if (!rows.isEmpty()) dao.putAll(rows);
    }
}
