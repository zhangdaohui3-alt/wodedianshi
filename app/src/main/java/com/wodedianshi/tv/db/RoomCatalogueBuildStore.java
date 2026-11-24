package com.wodedianshi.tv.db;

import android.content.Context;
import com.wodedianshi.tv.SourceProbeTimePolicy;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists an isolated full-catalogue build in the same authoritative Room DB.
 * Staging rows are never read by the playback UI and are published only after
 * the discovery chain has completed and the final snapshot passes validation.
 */
public final class RoomCatalogueBuildStore {
    private RoomCatalogueBuildStore() {}

    public static JSONArray read(Context context) throws JSONException {
        List<CatalogueBuildRowEntity> rows = RoomDatabaseProvider.get(context)
                .channelDao().getCatalogueBuildRows();
        JSONArray result = new JSONArray();
        for (CatalogueBuildRowEntity row : rows) {
            JSONObject record = new JSONObject(row.recordJson);
            if (record.optString("name", "").trim().isEmpty()
                    || record.optString("url", "").trim().isEmpty()) {
                throw new JSONException("Invalid staged catalogue row " + row.rowId);
            }
            result.put(record);
        }
        return result;
    }

    public static int replace(Context context, JSONArray snapshot) throws JSONException {
        return replace(context, snapshot, null);
    }

    public static int replace(Context context, JSONArray snapshot, BooleanSupplier paused) throws JSONException {
        checkPause(paused);
        if (context == null || snapshot == null) {
            throw new IllegalArgumentException("context/snapshot is null");
        }
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        // A rebuild may span hundreds of WorkManager runs. Compare against the
        // existing staging rows and write only changed JSON records instead of
        // deleting/reinserting the entire growing catalogue after every batch.
        java.util.HashMap<String, String> existingJson = new java.util.HashMap<>();
        for (CatalogueBuildRowEntity row : dao.getCatalogueBuildRows()) {
            checkPause(paused);
            existingJson.put(row.rowId, row.recordJson);
        }
        ArrayList<CatalogueBuildRowEntity> changedRows = new ArrayList<>();
        for (int i = 0; i < snapshot.length(); i++) {
            checkPause(paused);
            JSONObject record = snapshot.optJSONObject(i);
            if (record == null || record.optString("name", "").trim().isEmpty()
                    || record.optString("url", "").trim().isEmpty()) {
                throw new JSONException("Invalid full-rebuild row at index " + i);
            }
            String rowId = String.format(java.util.Locale.ROOT, "row-%08d", i);
            String recordJson = record.toString();
            if (!recordJson.equals(existingJson.get(rowId))) {
                changedRows.add(new CatalogueBuildRowEntity(rowId, i, recordJson));
            }
        }
        checkPause(paused);
        db.runInTransaction(() -> {
            checkPause(paused);
            for (int i = 0; i < changedRows.size(); i += 64) {
                checkPause(paused);
                dao.insertCatalogueBuildRows(changedRows.subList(i, Math.min(i + 64, changedRows.size())));
            }
            checkPause(paused);
            dao.deleteCatalogueBuildRowsFrom(snapshot.length());
            checkPause(paused);
        });
        return snapshot.length();
    }

    private static void checkPause(BooleanSupplier paused) {
        if (paused != null) SourceProbeTimePolicy.checkPublicationPaused(paused);
    }

    public static void clear(Context context) {
        RoomDatabaseProvider.get(context).channelDao().clearCatalogueBuildRows();
    }
}
