package com.wodedianshi.tv.db;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One-time, lossless importer for the legacy SharedPreferences catalogue.
 * It runs on the caller's background thread and commits the whole import in
 * one SQLite transaction. The old preferences are deliberately never deleted.
 */
public final class LegacyCatalogueImporter {
    private static final String PREF_MIGRATED = "room_catalogue_migration_v1";
    private static final int BATCH_SIZE = 96;

    private LegacyCatalogueImporter() {}

    public static final class Result {
        public final int channels;
        public final int sources;
        public final int favorites;
        Result(int channels, int sources, int favorites) {
            this.channels = channels;
            this.sources = sources;
            this.favorites = favorites;
        }
    }

    public static Result importIfNeeded(Context context, SharedPreferences prefs) throws Exception {
        if (context == null || prefs == null) throw new IllegalArgumentException("context/prefs is null");
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        final String json = prefs.getString("channels", "[]");
        final Set<String> legacyFavorites = new HashSet<>(prefs.getStringSet("fav", new HashSet<String>()));
        if (json == null || json.trim().isEmpty()) throw new IllegalStateException("legacy catalogue is empty");
        final String snapshotFingerprint = fingerprint(json, legacyFavorites);

        CatalogueMigrationEntity checkpoint = dao.getMigration(PREF_MIGRATED);
        if (checkpoint != null && snapshotFingerprint.equals(checkpoint.catalogueFingerprint)) {
            int channels = dao.countChannels();
            int sources = dao.countBackupSources();
            int favorites = dao.countFavorites();
            int categories = dao.countCategories();
            // A fingerprint alone is not enough: detect a truncated or partially
            // damaged shadow database before trusting its completion marker.
            if (channels == checkpoint.channelsCount
                    && sources == checkpoint.backupSourcesCount
                    && favorites == checkpoint.favoritesCount
                    && categories == checkpoint.categoriesCount
                    && channels > 0) {
                return new Result(channels, sources, favorites);
            }
            dao.removeMigration(PREF_MIGRATED);
        }

        // Track category rows as well as channels/sources/favorites. A matching
        // channel count alone cannot detect an incomplete category index.
        final int[] totals = new int[4];
        db.runInTransaction(() -> {
            // A previous process may have died before the import transaction
            // committed. Rebuilding is safe because the transaction is atomic.
            dao.clearBackupSources();
            dao.clearFavorites();
            dao.clearChannels();
            dao.clearCategories();

            ArrayList<CategoryEntity> categories = new ArrayList<>();
            ArrayList<ChannelEntity> channels = new ArrayList<>();
            ArrayList<BackupSourceEntity> sources = new ArrayList<>();
            ArrayList<FavoriteEntity> favorites = new ArrayList<>();
            HashSet<String> categoryIds = new HashSet<>();
            int row = 0;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue();
                        continue;
                    }
                    LegacyChannel item = readChannel(reader);
                    if (item.name.isEmpty() || item.url.isEmpty()) continue;

                    String categoryId = item.group.isEmpty() ? "__ungrouped__" : item.group;
                    if (categoryIds.add(categoryId)) {
                        categories.add(new CategoryEntity(categoryId,
                                item.group.isEmpty() ? "未分类" : item.group, categoryIds.size() - 1));
                        totals[3]++;
                    }

                    String id = "legacy:" + row++;
                    String legacyKey = item.name + "|" + item.url;
                    if (item.sources.isEmpty()) item.sources.add(item.url);
                    // Preserve the selected source by URL, not by its old array index:
                    // removing duplicate URLs can shift that index during normalization.
                    String activeSourceUrl = item.activeSource >= 0 && item.activeSource < item.sources.size()
                            ? item.sources.get(item.activeSource) : item.url;
                    // The legacy primary URL is independently playable even if an older
                    // catalogue omitted it from the optional backup list. Keep it in the
                    // Room source relation without disturbing the selected source index.
                    if (!item.sources.contains(item.url)) item.sources.add(item.url);
                    // Keep the legacy unique-source invariant before inserting the composite PK.
                    ArrayList<String> uniqueSources = new ArrayList<>();
                    HashSet<String> seenSources = new HashSet<>();
                    for (String sourceUrl : item.sources) {
                        if (sourceUrl != null && !sourceUrl.isEmpty() && seenSources.add(sourceUrl)) {
                            uniqueSources.add(sourceUrl);
                        }
                    }
                    if (uniqueSources.isEmpty()) uniqueSources.add(item.url);
                    int activeSource = uniqueSources.indexOf(activeSourceUrl);
                    if (activeSource < 0) activeSource = 0;
                    channels.add(new ChannelEntity(id, legacyKey, item.name, item.url,
                            item.group, categoryId, item.tvgId, item.tvgName,
                            toJson(item.channelScopes), activeSource, row - 1));

                    for (int i = 0; i < uniqueSources.size(); i++) {
                        String sourceUrl = uniqueSources.get(i);
                        sources.add(new BackupSourceEntity(id, sourceUrl, i,
                                toJson(item.backupScopes.get(sourceUrl))));
                    }
                    if (legacyFavorites.contains(legacyKey)) {
                        favorites.add(new FavoriteEntity(id, System.currentTimeMillis()));
                    }
                    totals[0]++;
                    totals[1] += uniqueSources.size();
                    if (legacyFavorites.contains(legacyKey)) totals[2]++;

                    if (channels.size() >= BATCH_SIZE) {
                        flush(dao, categories, channels, sources, favorites);
                    }
                }
                reader.endArray();
            } catch (Exception malformed) {
                throw new IllegalStateException("legacy catalogue parse failed; transaction rolled back", malformed);
            }
            flush(dao, categories, channels, sources, favorites);
            if (dao.countCategories() != totals[3] || dao.countChannels() != totals[0]
                    || dao.countBackupSources() != totals[1] || dao.countFavorites() != totals[2]) {
                throw new IllegalStateException("Room import count validation failed");
            }
            // If the background source updater or a user action changed the legacy
            // snapshot while it was being copied, roll back instead of blessing stale data.
            String latestJson = prefs.getString("channels", "[]");
            Set<String> latestFavorites = new HashSet<>(prefs.getStringSet("fav", new HashSet<String>()));
            if (!snapshotFingerprint.equals(fingerprint(latestJson == null ? "" : latestJson, latestFavorites))) {
                throw new IllegalStateException("legacy catalogue changed during Room import; retry required");
            }
            // Commit the checkpoint in the same SQLite transaction as the imported
            // rows. A changed legacy snapshot gets a different fingerprint next time.
            dao.insertMigration(new CatalogueMigrationEntity(PREF_MIGRATED,
                    totals[0], totals[1], totals[2], totals[3], System.currentTimeMillis(), snapshotFingerprint));
        });

        // Recheck after SQLite commits as well: the preference catalogue is maintained
        // by a separate worker, so it can change immediately after the in-transaction
        // check. If it did, invalidate the checkpoint; the next background pass retries.
        String committedJson = prefs.getString("channels", "[]");
        Set<String> committedFavorites = new HashSet<>(prefs.getStringSet("fav", new HashSet<String>()));
        if (!snapshotFingerprint.equals(fingerprint(committedJson == null ? "" : committedJson, committedFavorites))) {
            dao.removeMigration(PREF_MIGRATED);
            throw new IllegalStateException("legacy catalogue changed immediately after Room import; checkpoint invalidated");
        }

        int actualChannels = dao.countChannels();
        int actualSources = dao.countBackupSources();
        if (dao.countCategories() != totals[3] || actualChannels != totals[0]
                || actualSources != totals[1] || dao.countFavorites() != totals[2] || actualChannels == 0) {
            throw new IllegalStateException("Room import validation mismatch");
        }
        // Legacy channels/favorites remain intact for rollback and future diagnosis.
        // The durable checkpoint was committed atomically with the catalogue rows.
        return new Result(actualChannels, actualSources, totals[2]);
    }

    private static String fingerprint(String json, Set<String> favorites) {
        // 64-bit FNV-1a over UTF-16 code units avoids allocating a second 17 MB
        // UTF-8 byte array merely to identify the legacy snapshot.
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < json.length(); i++) {
            hash ^= json.charAt(i);
            hash *= 0x100000001b3L;
        }
        ArrayList<String> sortedFavorites = new ArrayList<>(favorites);
        java.util.Collections.sort(sortedFavorites);
        hash ^= sortedFavorites.size();
        hash *= 0x100000001b3L;
        for (String favorite : sortedFavorites) {
            if (favorite == null) continue;
            for (int i = 0; i < favorite.length(); i++) {
                hash ^= favorite.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= 0xff;
            hash *= 0x100000001b3L;
        }
        return json.length() + ":" + Long.toHexString(hash);
    }

    private static void flush(ChannelDao dao, ArrayList<CategoryEntity> categories,
            ArrayList<ChannelEntity> channels, ArrayList<BackupSourceEntity> sources,
            ArrayList<FavoriteEntity> favorites) {
        if (!categories.isEmpty()) dao.insertCategories(new ArrayList<>(categories));
        if (!channels.isEmpty()) dao.insertChannels(new ArrayList<>(channels));
        if (!sources.isEmpty()) dao.insertBackupSources(new ArrayList<>(sources));
        if (!favorites.isEmpty()) dao.insertFavorites(new ArrayList<>(favorites));
        categories.clear();
        channels.clear();
        sources.clear();
        favorites.clear();
    }

    private static final class LegacyChannel {
        String name = "", url = "", group = "", tvgId = "", tvgName = "";
        int activeSource = 0;
        ArrayList<String> sources = new ArrayList<>();
        Set<String> channelScopes = new HashSet<>();
        Map<String, Set<String>> backupScopes = new HashMap<>();
    }

    private static LegacyChannel readChannel(JsonReader r) throws Exception {
        LegacyChannel c = new LegacyChannel();
        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            switch (field) {
                case "name": c.name = readString(r); break;
                case "url": c.url = readString(r); break;
                case "group": c.group = readString(r); break;
                case "tvgId": c.tvgId = readString(r); break;
                case "tvgName": c.tvgName = readString(r); break;
                case "activeSource":
                    try { c.activeSource = Integer.parseInt(readString(r)); }
                    catch (NumberFormatException ignored) { c.activeSource = 0; }
                    break;
                case "sources": readSources(r, c.sources); break;
                case "sourceScopes": readScopeArray(r, c.channelScopes); break;
                case "backupSourceScopes": readOwnership(r, c.backupScopes); break;
                default: r.skipValue();
            }
        }
        r.endObject();
        if (c.sources.isEmpty() && !c.url.isEmpty()) c.sources.add(c.url);
        c.activeSource = Math.max(0, Math.min(c.activeSource, Math.max(0, c.sources.size() - 1)));
        return c;
    }

    private static String readString(JsonReader r) throws Exception {
        JsonToken token = r.peek();
        if (token == JsonToken.NULL) { r.nextNull(); return ""; }
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) return r.nextString();
        r.skipValue();
        return "";
    }

    private static void readSources(JsonReader r, ArrayList<String> out) throws Exception {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return; }
        r.beginArray();
        while (r.hasNext()) {
            String value = readString(r);
            if (value.startsWith("http://") || value.startsWith("https://")) out.add(value);
        }
        r.endArray();
    }

    private static void readScopeArray(JsonReader r, Set<String> out) throws Exception {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return; }
        r.beginArray();
        while (r.hasNext()) {
            String scope = readString(r);
            if (isKnownScope(scope)) out.add(scope);
        }
        r.endArray();
    }

    private static void readOwnership(JsonReader r, Map<String, Set<String>> out) throws Exception {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return; }
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
            String url = "";
            Set<String> scopes = new HashSet<>();
            r.beginObject();
            while (r.hasNext()) {
                String field = r.nextName();
                if ("url".equals(field)) url = readString(r);
                else if ("scopes".equals(field)) readScopeArray(r, scopes);
                else r.skipValue();
            }
            r.endObject();
            if (!url.isEmpty() && !scopes.isEmpty()) out.put(url, scopes);
        }
        r.endArray();
    }

    private static boolean isKnownScope(String value) {
        return "china".equals(value) || "global".equals(value) || "custom".equals(value);
    }

    private static String toJson(Set<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) for (String value : values) array.put(value);
        return array.toString();
    }
}
