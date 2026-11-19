package com.wodedianshi.tv.db;

import android.content.Context;
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
        if (context == null || snapshot == null) {
            throw new IllegalArgumentException("context/snapshot is null");
        }
        ArrayList<CatalogueBuildRowEntity> rows = new ArrayList<>(snapshot.length());
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject record = snapshot.optJSONObject(i);
            if (record == null || record.optString("name", "").trim().isEmpty()
                    || record.optString("url", "").trim().isEmpty()) {
                throw new JSONException("Invalid full-rebuild row at index " + i);
            }
            rows.add(new CatalogueBuildRowEntity(
                    String.format(java.util.Locale.ROOT, "row-%08d", i),
                    i, record.toString()));
        }
        RoomDatabaseProvider.get(context).channelDao().replaceCatalogueBuildRows(rows);
        return rows.size();
    }

    public static void clear(Context context) {
        RoomDatabaseProvider.get(context).channelDao().clearCatalogueBuildRows();
    }
}
