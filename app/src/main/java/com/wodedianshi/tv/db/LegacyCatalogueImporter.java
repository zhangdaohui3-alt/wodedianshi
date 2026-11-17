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
        if (prefs.getBoolean(PREF_MIGRATED, false)) {
            return new Result(dao.countChannels(), dao.countBackupSources(), 0);
        }

        final String json = prefs.getString("channels", "[]");
        final Set<String> legacyFavorites = new HashSet<>(prefs.getStringSet("fav", new HashSet<String>()));
        if (json == null || json.trim().isEmpty()) throw new IllegalStateException("legacy catalogue is empty");

        final int[] totals = new int[3];
        db.runInTransaction(() -> {
            // A previous process may have died after SQLite committed but before
            // the preference marker was written. Rebuilding is safe and atomic.
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
                    }

                    String id = "legacy:" + row++;
                    String legacyKey = item.name + "|" + item.url;
                    channels.add(new ChannelEntity(id, legacyKey, item.name, item.url,
                            item.group, categoryId, item.tvgId, item.tvgName,
                            toJson(item.channelScopes), item.activeSource, row - 1));

                    if (item.sources.isEmpty()) item.sources.add(item.url);
                    for (int i = 0; i < item.sources.size(); i++) {
                        String sourceUrl = item.sources.get(i);
                        if (sourceUrl == null || sourceUrl.isEmpty()) continue;
                        sources.add(new BackupSourceEntity(id, sourceUrl, i,
                                toJson(item.backupScopes.get(sourceUrl))));
                    }
                    if (legacyFavorites.contains(legacyKey)) {
                        favorites.add(new FavoriteEntity(id, System.currentTimeMillis()));
                    }
                    totals[0]++;
                    totals[1] += item.sources.size();
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
            if (dao.countChannels() != totals[0] || dao.countBackupSources() != totals[1]) {
                throw new IllegalStateException("Room import count validation failed");
            }
        });

        int actualChannels = dao.countChannels();
        int actualSources = dao.countBackupSources();
        if (actualChannels != totals[0] || actualSources != totals[1] || actualChannels == 0) {
            throw new IllegalStateException("Room import validation mismatch");
        }
        // This marker is written only after the transaction and row-count checks.
        // Legacy channels/favorites remain intact for rollback and future diagnosis.
        prefs.edit().putBoolean(PREF_MIGRATED, true).apply();
        return new Result(actualChannels, actualSources, totals[2]);
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
