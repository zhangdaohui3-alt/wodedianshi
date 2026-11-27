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
    private java.util.List<ManualProbeQualityEntity> publishedQuality;
    private java.util.function.BooleanSupplier qualityPaused;

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

    public AppStateEditor putPublishedQuality(java.util.List<ManualProbeQualityEntity> rows,
            java.util.function.BooleanSupplier paused){
        publishedQuality=new ArrayList<>(rows);qualityPaused=paused;return this;
    }

    public void apply() {
        AppDatabase db = RoomDatabaseProvider.get(context);
        db.runInTransaction(() -> applyInTransaction(db));
    }

    /** Quality-only write: refuse a stale catalogue or revoked viewing lease.
     * Do not replace any catalogue rows or advance its structural checkpoint. */
    public void applyAtCatalogueVersion(long expected,java.util.function.BooleanSupplier paused) {
        AppDatabase db=RoomDatabaseProvider.get(context);
        db.runInTransaction(()->{
            com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
            CatalogueMigrationEntity checkpoint=db.channelDao().getMigration(MasterCatalogueRepository.MIGRATION_KEY);
            if(checkpoint==null||checkpoint.completedAt!=expected)
                throw new RoomCatalogueReader.StaleSnapshotException();
            applyInTransaction(db);
            com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
        });
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
        if(publishedQuality!=null)ManualProbeQualityStore.writePublishedInTransaction(db,publishedQuality,qualityPaused);
    }
}
