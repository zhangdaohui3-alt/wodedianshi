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

    public static final class ChannelSnapshot {
        public String name, url, group, tvgId, tvgName;
        public int activeSource;
        public final ArrayList<String> sources = new ArrayList<>();
        public final HashSet<String> sourceScopes = new HashSet<>();
        public final HashMap<String, HashSet<String>> backupSourceScopes = new HashMap<>();
    }

    /** Full authoritative replacement used by normal catalogue persistence. */
    public static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys) {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        CatalogueMigrationEntity checkpoint = dao.getMigration(LegacyCatalogueImporter.MIGRATION_KEY);
        if (checkpoint == null) throw new IllegalStateException("Room catalogue seed is not ready");
        final int[] written = {0};
        db.runInTransaction(() -> {
            dao.clearFavorites();
            dao.clearBackupSources();
            dao.clearChannels();
            dao.clearCategories();
            HashMap<String, Integer> occurrences = new HashMap<>();
            HashMap<String, Integer> categoryOrders = new HashMap<>();
            ArrayList<CategoryEntity> categories = new ArrayList<>();
            ArrayList<ChannelEntity> channels = new ArrayList<>();
            ArrayList<BackupSourceEntity> sources = new ArrayList<>();
            ArrayList<FavoriteEntity> favorites = new ArrayList<>();
            for (int row = 0; row < snapshot.size(); row++) {
                ChannelSnapshot item = snapshot.get(row);
                if (item == null || item.name == null || item.name.trim().isEmpty()
                        || item.url == null || item.url.trim().isEmpty()) continue;
                String name = item.name.trim(), primary = item.url.trim();
                String group = item.group == null ? "" : item.group;
                String categoryId = group.isEmpty() ? "__ungrouped__" : group;
                if (!categoryOrders.containsKey(categoryId)) {
                    int order = categoryOrders.size();
                    categoryOrders.put(categoryId, order);
                    categories.add(new CategoryEntity(categoryId,
                            group.isEmpty() ? "未分类" : group, order));
                }
                String identity = LegacyChannelIdPolicy.identityKey(name, primary);
                int occurrence = occurrences.containsKey(identity) ? occurrences.get(identity) : 0;
                occurrences.put(identity, occurrence + 1);
                String id = LegacyChannelIdPolicy.idFor(name, primary, occurrence);
                ArrayList<String> urls = new ArrayList<>();
                if (item.sources != null) for (String value : item.sources) {
                    if (value != null && !value.trim().isEmpty() && !urls.contains(value.trim())) urls.add(value.trim());
                }
                if (urls.isEmpty()) urls.add(primary);
                int active = Math.max(0, Math.min(item.activeSource, urls.size() - 1));
                JSONArray channelScopes = new JSONArray();
                for (String scope : item.sourceScopes) channelScopes.put(scope);
                channels.add(new ChannelEntity(id, name + "|" + primary, name,
                        urls.get(0), group, categoryId,
                        item.tvgId == null ? "" : item.tvgId,
                        item.tvgName == null ? "" : item.tvgName,
                        channelScopes.toString(), active, row));
                for (int i = 0; i < urls.size(); i++) {
                    String url = urls.get(i);
                    JSONArray scopes = new JSONArray();
                    HashSet<String> owned = item.backupSourceScopes.get(url);
                    if (owned != null) for (String scope : owned) scopes.put(scope);
                    sources.add(new BackupSourceEntity(id, url, i, scopes.toString()));
                }
                if (favoriteKeys != null && favoriteKeys.contains(name + "|" + primary)) {
                    favorites.add(new FavoriteEntity(id, System.currentTimeMillis()));
                }
                written[0]++;
            }
            if (!categories.isEmpty()) dao.insertCategories(categories);
            if (!channels.isEmpty()) dao.insertChannels(channels);
            if (!sources.isEmpty()) dao.insertBackupSources(sources);
            if (!favorites.isEmpty()) dao.insertFavorites(favorites);
            dao.insertMigration(new CatalogueMigrationEntity(
                    checkpoint.migrationKey, dao.countChannels(), dao.countBackupSources(),
                    dao.countFavorites(), dao.countCategories(), System.currentTimeMillis(),
                    checkpoint.catalogueFingerprint));
        });
        return written[0];
    }

    public static int writeSnapshot(Context context, JSONArray snapshot) throws Exception {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        CatalogueMigrationEntity checkpoint = dao.getMigration(LegacyCatalogueImporter.MIGRATION_KEY);
        // Do not race the one-time legacy seed. Until it has completed, the worker
        // must retry rather than write a partial Room catalogue that a later import
        // would replace with an older snapshot.
        if (checkpoint == null) throw new IllegalStateException("Room catalogue seed is not ready");
        final int[] written = {0};
        db.runInTransaction(() -> {
            HashMap<String, Integer> occurrences = new HashMap<>();
            HashMap<String, Integer> categoryOrders = new HashMap<>();
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
                dao.replaceChannelWithSources(category, channel, sourceRows);
                written[0]++;
            }
            if (snapshot.length() > 0 && written[0] == 0) {
                throw new IllegalStateException("Room catalogue update produced no channel rows");
            }
            // Keep the seed checkpoint consistent with intentional Room writes.
            // Otherwise the next legacy reconcile sees changed row counts and
            // rebuilds the entire DB from stale SharedPreferences, erasing new feeds.
            dao.insertMigration(new CatalogueMigrationEntity(
                    checkpoint.migrationKey, dao.countChannels(), dao.countBackupSources(),
                    dao.countFavorites(), dao.countCategories(), System.currentTimeMillis(),
                    checkpoint.catalogueFingerprint));
        });
        return written[0];
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
