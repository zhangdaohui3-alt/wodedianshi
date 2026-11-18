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
 * Reconciles complete catalogue snapshots into Room in one transaction.
 * Existing rows are updated in place so foreign-key children are not lost.
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

    /**
     * Apply a complete catalogue snapshot in bounded batches. The previous
     * implementation issued several DAO operations for every channel; on a
     * large directory that creates thousands of Room calls inside one
     * transaction. Batch entity writes while preserving channel IDs, favorites,
     * complete source replacement, and category foreign-key ordering.
     */
    public static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys) {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        CatalogueMigrationEntity checkpoint = dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY);
        final int[] written = {0};
        db.runInTransaction(() -> {
            HashSet<String> previousChannelIds = new HashSet<>(dao.getAllChannelIds());
            HashSet<String> previousCategoryIds = new HashSet<>(dao.getAllCategoryIds());
            HashSet<String> presentChannelIds = new HashSet<>();
            HashSet<String> presentCategoryIds = new HashSet<>();
            HashSet<String> desiredFavoriteIds = new HashSet<>();
            HashMap<String, Integer> occurrences = new HashMap<>();
            HashMap<String, Integer> categoryOrders = new HashMap<>();

            // This writer is only used for complete, validated snapshots. Clear
            // once, then repopulate sources in the same transaction so no reader
            // can observe a partially replaced directory.
            dao.clearBackupSources();
            final int batchSize = 128;
            for (int offset = 0; offset < snapshot.size(); offset += batchSize) {
                int limit = Math.min(snapshot.size(), offset + batchSize);
                ArrayList<CategoryEntity> categoryBatch = new ArrayList<>();
                ArrayList<ChannelEntity> channelBatch = new ArrayList<>(limit - offset);
                ArrayList<BackupSourceEntity> sourceBatch = new ArrayList<>();
                HashSet<String> categoryBatchIds = new HashSet<>();

                for (int row = offset; row < limit; row++) {
                    ChannelSnapshot item = snapshot.get(row);
                    if (item == null || item.name == null || item.name.trim().isEmpty()
                            || item.url == null || item.url.trim().isEmpty()) continue;
                    String name = item.name.trim(), primary = item.url.trim();
                    String group = item.group == null ? "" : item.group;
                    String categoryId = group.isEmpty() ? "__ungrouped__" : group;
                    Integer categoryOrder = categoryOrders.get(categoryId);
                    if (categoryOrder == null) {
                        categoryOrder = categoryOrders.size();
                        categoryOrders.put(categoryId, categoryOrder);
                    }
                    if (categoryBatchIds.add(categoryId)) {
                        categoryBatch.add(new CategoryEntity(categoryId,
                                group.isEmpty() ? "未分类" : group, categoryOrder));
                    }
                    presentCategoryIds.add(categoryId);

                    String identity = LegacyChannelIdPolicy.identityKey(name, primary);
                    int occurrence = occurrences.containsKey(identity) ? occurrences.get(identity) : 0;
                    occurrences.put(identity, occurrence + 1);
                    String id = LegacyChannelIdPolicy.idFor(name, primary, occurrence);
                    String legacyKey = name + "|" + primary;

                    LegacySourceOrderPolicy.Result ordered = LegacySourceOrderPolicy.normalize(
                            item.sources, primary, item.activeSource);
                    if (ordered.urls.isEmpty()) continue;
                    int active = Math.max(0, Math.min(ordered.activeIndex, ordered.urls.size() - 1));
                    JSONArray channelScopes = new JSONArray();
                    if (item.sourceScopes != null) {
                        for (String scope : item.sourceScopes) channelScopes.put(scope);
                    }
                    channelBatch.add(new ChannelEntity(id, legacyKey, name,
                            ordered.urls.get(0), group, categoryId,
                            item.tvgId == null ? "" : item.tvgId,
                            item.tvgName == null ? "" : item.tvgName,
                            channelScopes.toString(), active, row));

                    for (int i = 0; i < ordered.urls.size(); i++) {
                        String url = ordered.urls.get(i);
                        JSONArray scopes = new JSONArray();
                        HashSet<String> owned = item.backupSourceScopes == null
                                ? null : item.backupSourceScopes.get(url);
                        if (owned != null) for (String scope : owned) scopes.put(scope);
                        sourceBatch.add(new BackupSourceEntity(id, url, i, scopes.toString()));
                    }
                    presentChannelIds.add(id);
                    if (favoriteKeys != null && favoriteKeys.contains(legacyKey)) {
                        desiredFavoriteIds.add(id);
                    }
                }

                // Insert missing parents before children, then update existing
                // rows in place. @Update avoids SQLite REPLACE's delete/insert
                // behavior, which would cascade-delete favorites.
                if (!categoryBatch.isEmpty()) {
                    dao.insertCategoriesIfAbsent(categoryBatch);
                    dao.updateCategories(categoryBatch);
                }
                if (!channelBatch.isEmpty()) {
                    dao.insertChannelsIfAbsent(channelBatch);
                    dao.updateChannels(channelBatch);
                }
                if (!sourceBatch.isEmpty()) dao.insertBackupSources(sourceBatch);
                written[0] += channelBatch.size();
            }

            if (!snapshot.isEmpty() && written[0] == 0) {
                throw new IllegalStateException("Room catalogue update produced no valid channel rows");
            }

            if (favoriteKeys == null) {
                desiredFavoriteIds.addAll(dao.getFavoriteChannelIds());
                desiredFavoriteIds.retainAll(presentChannelIds);
            }

            previousChannelIds.removeAll(presentChannelIds);
            for (String staleId : previousChannelIds) dao.deleteChannelById(staleId);

            HashSet<String> currentFavoriteIds = new HashSet<>(dao.getFavoriteChannelIds());
            for (String favoriteId : currentFavoriteIds) {
                if (!desiredFavoriteIds.contains(favoriteId)) dao.removeFavorite(favoriteId);
            }
            currentFavoriteIds.retainAll(desiredFavoriteIds);
            for (String favoriteId : desiredFavoriteIds) {
                if (!currentFavoriteIds.contains(favoriteId)) {
                    dao.addFavorite(new FavoriteEntity(favoriteId, System.currentTimeMillis()));
                }
            }

            previousCategoryIds.removeAll(presentCategoryIds);
            for (String staleCategoryId : previousCategoryIds) dao.deleteCategoryById(staleCategoryId);

            dao.insertMigration(new CatalogueMigrationEntity(
                    MasterCatalogueRepository.MIGRATION_KEY, dao.countChannels(), dao.countBackupSources(),
                    dao.countFavorites(), dao.countCategories(), System.currentTimeMillis(),
                    checkpoint == null ? "room_native_seed_v1" : checkpoint.catalogueFingerprint));
        });
        return written[0];
    }

    /** Source updater snapshots are complete Room-derived catalogues too. */
    public static int writeSnapshot(Context context, JSONArray snapshot) throws Exception {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        HashSet<String> favoriteKeys = null; // Preserve favorites atomically inside writeRecords().
        // SourceUpdateWorker only enriches a Room-derived snapshot; it must never
        // replace the authoritative directory with a truncated/partial feed result.
        AppDatabase database = RoomDatabaseProvider.get(context);
        int existingChannels = database.channelDao().countChannels();
        if (!BackgroundSnapshotPolicy.isComplete(existingChannels, snapshot.length())) {
            throw new IllegalStateException("Refusing incomplete background catalogue snapshot: "
                    + snapshot.length() + " rows for " + existingChannels + " saved channels");
        }
        ArrayList<ChannelSnapshot> rows = new ArrayList<>(snapshot.length());
        for (int row = 0; row < snapshot.length(); row++) {
            JSONObject item = snapshot.optJSONObject(row);
            if (item == null) continue;
            ChannelSnapshot channel = new ChannelSnapshot();
            channel.name = item.optString("name", "").trim();
            channel.url = item.optString("url", "").trim();
            channel.group = item.optString("group", "");
            channel.tvgId = item.optString("tvgId", "");
            channel.tvgName = item.optString("tvgName", "");
            channel.activeSource = item.optInt("activeSource", 0);
            JSONArray rawSources = item.optJSONArray("sources");
            if (rawSources != null) {
                for (int i = 0; i < rawSources.length(); i++) {
                    String value = rawSources.optString(i, "").trim();
                    if (!value.isEmpty()) channel.sources.add(value);
                }
            }
            JSONArray rawScopes = item.optJSONArray("sourceScopes");
            if (rawScopes != null) {
                for (int i = 0; i < rawScopes.length(); i++) {
                    String scope = rawScopes.optString(i, "");
                    if (!scope.isEmpty()) channel.sourceScopes.add(scope);
                }
            }
            channel.backupSourceScopes.putAll(readOwnership(item.optJSONArray("backupSourceScopes")));
            rows.add(channel);
        }
        return writeRecords(context, rows, favoriteKeys);
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
