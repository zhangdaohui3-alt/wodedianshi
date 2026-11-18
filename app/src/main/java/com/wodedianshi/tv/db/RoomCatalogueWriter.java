package com.wodedianshi.tv.db;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Writes a complete in-memory catalogue snapshot into the single Room store.
 * Used by the source updater while its legacy snapshot hand-off is being
 * retired; SharedPreferences is not read or written by this class.
 */
public final class RoomCatalogueWriter {
    private RoomCatalogueWriter() {}

    public static int writeSnapshot(Context context, JSONArray snapshot) throws Exception {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        ChannelDao dao = RoomDatabaseProvider.get(context).channelDao();
        HashMap<String, Integer> occurrences = new HashMap<>();
        HashMap<String, Integer> categoryOrders = new HashMap<>();
        int written = 0;
        for (int row = 0; row < snapshot.length(); row++) {
            JSONObject item = snapshot.optJSONObject(row);
            if (item == null) continue;
            String name = item.optString("name", "").trim();
            String primary = item.optString("url", "").trim();
            if (name.isEmpty() || primary.isEmpty()) continue;
            String group = item.optString("group", "");
            String categoryId = group.isEmpty() ? "__ungrouped__" : group;
            Integer categoryOrder = categoryOrders.get(categoryId);
            if (categoryOrder == null) {
                categoryOrder = categoryOrders.size();
                categoryOrders.put(categoryId, categoryOrder);
            }
            CategoryEntity category = new CategoryEntity(categoryId,
                    group.isEmpty() ? "未分类" : group, categoryOrder);

            ArrayList<String> rawUrls = new ArrayList<>();
            JSONArray rawSources = item.optJSONArray("sources");
            if (rawSources != null) {
                for (int i = 0; i < rawSources.length(); i++) {
                    String url = rawSources.optString(i, "").trim();
                    if (!url.isEmpty()) rawUrls.add(url);
                }
            }
            LegacySourceOrderPolicy.Result ordered = LegacySourceOrderPolicy.normalize(
                    rawUrls, primary, item.optInt("activeSource", 0));
            if (ordered.urls.isEmpty()) continue;

            String identity = LegacyChannelIdPolicy.identityKey(name, primary);
            int occurrence = occurrences.containsKey(identity) ? occurrences.get(identity) : 0;
            occurrences.put(identity, occurrence + 1);
            String id = LegacyChannelIdPolicy.idFor(name, primary, occurrence);
            String legacyKey = name + "|" + primary;
            JSONArray channelScopes = item.optJSONArray("sourceScopes");
            String scopesJson = channelScopes == null ? "[]" : channelScopes.toString();

            ChannelEntity channel = new ChannelEntity(id, legacyKey, name,
                    ordered.urls.get(0), group, categoryId,
                    item.optString("tvgId", ""), item.optString("tvgName", ""),
                    scopesJson, ordered.activeIndex, row);
            Map<String, HashSet<String>> ownership = readOwnership(item.optJSONArray("backupSourceScopes"));
            ArrayList<BackupSourceEntity> sourceRows = new ArrayList<>();
            for (int i = 0; i < ordered.urls.size(); i++) {
                String url = ordered.urls.get(i);
                HashSet<String> sourceScopes = ownership.get(url);
                JSONArray sourceScopeArray = new JSONArray();
                if (sourceScopes != null) for (String scope : sourceScopes) sourceScopeArray.put(scope);
                sourceRows.add(new BackupSourceEntity(id, url, i, sourceScopeArray.toString()));
            }
            dao.writeChannelWithSources(category, channel, sourceRows);
            written++;
        }
        return written;
    }

    private static Map<String, HashSet<String>> readOwnership(JSONArray ownership) {
        HashMap<String, HashSet<String>> result = new HashMap<>();
        if (ownership == null) return result;
        for (int i = 0; i < ownership.length(); i++) {
            JSONObject item = ownership.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "");
            if (url.isEmpty()) continue;
            HashSet<String> scopes = result.get(url);
            if (scopes == null) {
                scopes = new HashSet<>();
                result.put(url, scopes);
            }
            JSONArray values = item.optJSONArray("scopes");
            if (values != null) for (int j = 0; j < values.length(); j++) {
                String scope = values.optString(j, "");
                if (!scope.isEmpty()) scopes.add(scope);
            }
        }
        return result;
    }
}
