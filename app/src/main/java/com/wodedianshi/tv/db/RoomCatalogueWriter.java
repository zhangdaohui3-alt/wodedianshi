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

    /** Apply an exact snapshot without dropping and recreating the whole database. */
    public static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys) {
        return writeRecords(context, snapshot, favoriteKeys, -1L);
    }

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion) {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        if (snapshot.isEmpty()) throw new IllegalArgumentException("refusing to commit an empty catalogue");
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        // A missing checkpoint means a genuinely fresh database. Seed it directly
        // from the bundled/current catalogue; no legacy JSON import is required.
        final int[] written = {0};
        db.runInTransaction(() -> {
            CatalogueMigrationEntity checkpoint =
                    dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY);
            if (expectedCatalogueVersion >= 0L
                    && (checkpoint == null || checkpoint.completedAt != expectedCatalogueVersion)) {
                throw new IllegalStateException(
                        "Room catalogue changed during source scan; stale snapshot rejected");
            }
            HashSet<String> previousChannelIds = new HashSet<>(dao.getAllChannelIds());
            HashSet<String> previousCategoryIds = new HashSet<>(dao.getAllCategoryIds());
            HashSet<String> presentChannelIds = new HashSet<>();
            HashSet<String> presentCategoryIds = new HashSet<>();
            HashSet<String> desiredFavoriteIds = new HashSet<>();
            HashMap<String, Integer> occurrences = new HashMap<>();
            HashMap<String, Integer> categoryOrders = new HashMap<>();

            for (int row = 0; row < snapshot.size(); row++) {
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
                CategoryEntity category = new CategoryEntity(categoryId,
                        group.isEmpty() ? "未分类" : group, categoryOrder);
                dao.insertCategoryIfAbsent(category);
                dao.updateCategoryFields(categoryId, category.name, categoryOrder);
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
                if (item.sourceScopes != null) for (String scope : item.sourceScopes) channelScopes.put(scope);
                ChannelEntity channel = new ChannelEntity(id, legacyKey, name,
                        ordered.urls.get(0), group, categoryId,
                        item.tvgId == null ? "" : item.tvgId,
                        item.tvgName == null ? "" : item.tvgName,
                        channelScopes.toString(), active, row);

                ArrayList<BackupSourceEntity> sourceRows = new ArrayList<>();
                for (int i = 0; i < ordered.urls.size(); i++) {
                    String url = ordered.urls.get(i);
                    JSONArray scopes = new JSONArray();
                    HashSet<String> owned = item.backupSourceScopes.get(url);
                    if (owned != null) for (String scope : owned) scopes.put(scope);
                    sourceRows.add(new BackupSourceEntity(id, url, i, scopes.toString()));
                }
                dao.replaceChannelWithSources(category, channel, sourceRows);
                presentChannelIds.add(id);
                if (favoriteKeys != null && favoriteKeys.contains(legacyKey)) {
                    desiredFavoriteIds.add(id);
                }
                written[0]++;
            }

            // A catalogue snapshot is an all-or-nothing unit. Skipping even one
            // malformed row would otherwise commit a silently truncated directory.
            if (written[0] != snapshot.size()) {
                throw new IllegalStateException("Room catalogue snapshot was incomplete: expected "
                        + snapshot.size() + " valid rows, wrote " + written[0]);
            }

            // A worker refresh preserves the latest favorites from inside this same
            // transaction. This avoids racing a user favorite toggle between a
            // pre-transaction read and the catalogue commit.
            if (favoriteKeys == null) {
                desiredFavoriteIds.addAll(dao.getFavoriteChannelIds());
                desiredFavoriteIds.retainAll(presentChannelIds);
            }

            // Remove only rows absent from the complete snapshot. FK cascades then
            // clean up the removed channel's own sources/favorite, never its peers.
            previousChannelIds.removeAll(presentChannelIds);
            for (String staleId : previousChannelIds) dao.deleteChannelById(staleId);

            // Preserve existing favorite timestamps; only apply actual additions/removals.
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
            for (String staleCategoryId : previousCategoryIds) {
                // Category rows are RESTRICT parents; deleting only after stale
                // channels are gone keeps the FK invariant intact.
                dao.deleteCategoryById(staleCategoryId);
            }

            long completedAt = Math.max(System.currentTimeMillis(),
                    checkpoint == null ? 1L : checkpoint.completedAt + 1L);
            dao.insertMigration(new CatalogueMigrationEntity(
                    MasterCatalogueRepository.MIGRATION_KEY, dao.countChannels(), dao.countBackupSources(),
                    dao.countFavorites(), dao.countCategories(), completedAt,
                    checkpoint == null ? "room_native_seed_v1" : checkpoint.catalogueFingerprint));
        });
        return written[0];
    }

    /** Source updater snapshots are complete Room-derived catalogues too. */
    public static int writeSnapshot(Context context, JSONArray snapshot) throws Exception {
        return writeSnapshot(context, snapshot, -1L);
    }

    /** Commit only if no user/catalogue mutation has happened since the worker read Room. */
    public static int writeSnapshot(Context context, JSONArray snapshot,
            long expectedCatalogueVersion) throws Exception {
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        HashSet<String> favoriteKeys = null; // Preserve favorites atomically inside writeRecords().
        // Channel count is deliberately not compared with the previous catalogue:
        // a fully collected and normalized refresh may correctly remove dead,
        // duplicate or invalid channels. Completeness is validated structurally
        // below and again inside the Room transaction, not inferred from row count.
        AppDatabase database = RoomDatabaseProvider.get(context);
        if (snapshot.length() == 0) {
            throw new IllegalStateException("Refusing an empty background catalogue snapshot");
        }
        ArrayList<ChannelSnapshot> rows = new ArrayList<>(snapshot.length());
        for (int row = 0; row < snapshot.length(); row++) {
            JSONObject item = snapshot.optJSONObject(row);
            if (item == null) {
                throw new IllegalStateException("Background catalogue snapshot contains an invalid row at index " + row);
            }
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
        // Validate the decoded row count as well as the JSON array count. Otherwise
        // skipping a malformed JSON element here could turn a full snapshot into a
        // shorter list that still passes the non-shrinking background policy.
        if (rows.size() != snapshot.length()) {
            throw new IllegalStateException("Background catalogue snapshot was truncated while decoding");
        }
        return writeRecords(context, rows, favoriteKeys, expectedCatalogueVersion);
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
