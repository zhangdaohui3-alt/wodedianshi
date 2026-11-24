package com.wodedianshi.tv.db;

import android.content.Context;
import com.wodedianshi.tv.ChannelCatalogueScopePolicy;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads the single validated Room catalogue for application startup. */
public final class RoomCatalogueReader {
    private static final int PAGE_SIZE = 200;

    /** A concurrent Room write invalidated a paged read, not a broken database.
     * The caller may retry after outstanding user mutations finish, but must
     * never render the partial snapshot. */
    public static final class StaleSnapshotException extends IllegalStateException {
        public StaleSnapshotException() {
            super("Room catalogue changed while its snapshot was being read");
        }
    }

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
                    // Preserve the exact country/state/city provenance that
                    // RoomCatalogueWriter already persisted with each backup.
                    // The earlier whitelist silently discarded all geo:* tags
                    // on every Room restart/reload.
                    if (ChannelCatalogueScopePolicy.isRetainableScope(scope)) {
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

    /** Readiness only: no channel/source objects, no paged catalogue read.
     * A publication must still read a complete current snapshot and check its version. */
    public static boolean hasValidatedCatalogue(Context context) {
        if(context==null)throw new IllegalArgumentException("context is null");
        return RoomDatabaseProvider.get(context).channelDao()
                .getValidatedCheckpoint(MasterCatalogueRepository.MIGRATION_KEY)!=null;
    }

    /** Returns null when the one-time seed/checkpoint is absent or inconsistent. */
    public static Snapshot read(Context context) {
        return read(context, null);
    }

    /**
     * Paged startup variant. A consumer can convert each page directly into the
     * Activity model, avoiding a second full in-memory copy of a large catalogue.
     */
    public static Snapshot read(Context context, PageConsumer consumer) {
        return read(context, consumer, null);
    }

    /** Worker reads can yield between bounded pages; never publish an incomplete result. */
    public static Snapshot read(Context context, PageConsumer consumer, java.util.function.BooleanSupplier paused) {
        if (paused != null) com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
        if (context == null) throw new IllegalArgumentException("context is null");
        ChannelDao dao = RoomDatabaseProvider.get(context).channelDao();
        CatalogueMigrationEntity initialCheckpoint =
                dao.getValidatedCheckpoint(MasterCatalogueRepository.MIGRATION_KEY);
        if (initialCheckpoint == null) return null;
        final long catalogueVersion = initialCheckpoint.completedAt;
        ArrayList<ChannelRecord> allRows = consumer == null ? new ArrayList<>() : null;
        int total = initialCheckpoint.channelsCount;
        int loaded = 0;
        for (int offset = 0; offset < total; offset += PAGE_SIZE) {
            if (paused != null) com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
            List<ChannelWithSources> page = dao.getAllChannelsPage(PAGE_SIZE, offset);
            if (page == null || page.isEmpty()) break;
            ArrayList<ChannelRecord> records = new ArrayList<>(page.size());
            for (ChannelWithSources item : page) {
                if (paused != null) com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
                if (item == null || item.channel == null) continue;
                records.add(new ChannelRecord(item.channel, item.getSourcesInPriorityOrder()));
            }
            loaded += records.size();
            if (consumer == null) allRows.addAll(records);
            else consumer.accept(records);
        }
        if (paused != null) com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
        HashSet<String> favorites = new HashSet<>(dao.getFavoriteLegacyKeys());
        CatalogueMigrationEntity finalCheckpoint =
                dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY);
        // Check the optimistic checkpoint BEFORE classifying a page-count
        // mismatch. A full snapshot committed between pages can legitimately
        // change the row count: that is a retryable conflict, not data damage.
        if (finalCheckpoint == null || finalCheckpoint.completedAt != catalogueVersion) {
            throw new StaleSnapshotException();
        }
        // A truncated read without any intervening write is a real structural
        // failure. Do not retry it endlessly or publish a partial directory.
        CatalogueReadCompletenessPolicy.requireComplete(total, loaded);
        if (paused != null) com.wodedianshi.tv.SourceProbeTimePolicy.checkPublicationPaused(paused);
        return new Snapshot(consumer == null ? allRows : java.util.Collections.emptyList(),
                favorites, loaded, catalogueVersion);
    }
}
