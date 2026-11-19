package com.wodedianshi.tv.db;

import android.content.Context;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads the single validated Room catalogue for application startup. */
public final class RoomCatalogueReader {
    private static final int PAGE_SIZE = 200;

    public static final class ChannelRecord {
        public final String name;
        public final String url;
        public final String group;
        public final String tvgId;
        public final String tvgName;
        public final ArrayList<String> sources;
        public final HashSet<String> sourceScopes;
        public final HashMap<String, HashSet<String>> backupSourceScopes;
        public final int activeSource;

        ChannelRecord(ChannelEntity channel, List<BackupSourceEntity> sourceRows) {
            name = channel.name == null ? "" : channel.name;
            String legacyKey = channel.legacyKey == null ? "" : channel.legacyKey;
            String prefix = name + "|";
            url = legacyKey.startsWith(prefix) ? legacyKey.substring(prefix.length())
                    : (channel.primaryUrl == null ? "" : channel.primaryUrl);
            group = channel.groupName == null ? "" : channel.groupName;
            tvgId = channel.tvgId == null ? "" : channel.tvgId;
            tvgName = channel.tvgName == null ? "" : channel.tvgName;
            sources = new ArrayList<>();
            sourceScopes = parseScopes(channel.channelScopesJson);
            backupSourceScopes = new HashMap<>();
            if (sourceRows != null) {
                for (BackupSourceEntity row : sourceRows) {
                    if (row == null || row.url == null || row.url.isEmpty()) continue;
                    sources.add(row.url);
                    HashSet<String> scopes = parseScopes(row.scopesJson);
                    if (!scopes.isEmpty()) backupSourceScopes.put(row.url, scopes);
                }
            }
            if (sources.isEmpty() && channel.primaryUrl != null && !channel.primaryUrl.isEmpty()) {
                sources.add(channel.primaryUrl);
            }
            activeSource = Math.max(0, Math.min(channel.activeSource, Math.max(0, sources.size() - 1)));
        }

        private static HashSet<String> parseScopes(String json) {
            HashSet<String> result = new HashSet<>();
            if (json == null || json.isEmpty()) return result;
            try {
                JSONArray values = new JSONArray(json);
                for (int i = 0; i < values.length(); i++) {
                    String scope = values.optString(i, "");
                    if ("china".equals(scope) || "global".equals(scope) || "custom".equals(scope)) {
                        result.add(scope);
                    }
                }
            } catch (Exception ignored) {
                // A malformed optional scope must not make playable channels disappear.
            }
            return result;
        }
    }

    public interface PageConsumer {
        void accept(List<ChannelRecord> page);
    }

    public static final class Snapshot {
        public final List<ChannelRecord> channels;
        public final Set<String> favorites;
        public final int channelCount;
        public final long catalogueVersion;
        Snapshot(List<ChannelRecord> channels, Set<String> favorites, int channelCount,
                long catalogueVersion) {
            this.channels = channels;
            this.favorites = favorites;
            this.channelCount = channelCount;
            this.catalogueVersion = catalogueVersion;
        }
    }

    private RoomCatalogueReader() {}

    /** Returns null when the one-time seed/checkpoint is absent or inconsistent. */
    public static Snapshot read(Context context) {
        return read(context, null);
    }

    /**
     * Paged startup variant. A consumer can convert each page directly into the
     * Activity model, avoiding a second full in-memory copy of a large catalogue.
     */
    public static Snapshot read(Context context, PageConsumer consumer) {
        if (context == null) throw new IllegalArgumentException("context is null");
        MasterCatalogueRepository repository = new MasterCatalogueRepository(context);
        if (!repository.isSnapshotReady()) return null;
        ChannelDao dao = RoomDatabaseProvider.get(context).channelDao();
        ArrayList<ChannelRecord> allRows = consumer == null ? new ArrayList<>() : null;
        int total = dao.countChannels();
        int loaded = 0;
        for (int offset = 0; offset < total; offset += PAGE_SIZE) {
            List<ChannelWithSources> page = dao.getAllChannelsPage(PAGE_SIZE, offset);
            if (page == null || page.isEmpty()) break;
            ArrayList<ChannelRecord> records = new ArrayList<>(page.size());
            for (ChannelWithSources item : page) {
                if (item == null || item.channel == null) continue;
                records.add(new ChannelRecord(item.channel, item.getSourcesInPriorityOrder()));
            }
            loaded += records.size();
            if (consumer == null) allRows.addAll(records);
            else consumer.accept(records);
        }
        // Never publish a partial directory as a valid startup snapshot. A missing
        // middle page, concurrent truncation, or malformed row must fail closed so
        // callers can retain the previous complete local directory instead.
        CatalogueReadCompletenessPolicy.requireComplete(total, loaded);
        HashSet<String> favorites = new HashSet<>(dao.getFavoriteLegacyKeys());
        return new Snapshot(consumer == null ? allRows : java.util.Collections.emptyList(),
                favorites, loaded);
    }
}
