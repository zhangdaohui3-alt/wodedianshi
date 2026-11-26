package com.wodedianshi.tv.db;

import android.content.Context;
import com.wodedianshi.tv.FullDiscoveryProgressPolicy;
import com.wodedianshi.tv.SourceProbeTimePolicy;
import java.util.function.BooleanSupplier;
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

    // Keep relation reads bounded on low-memory TV devices and comfortably
    // below SQLite bind parameter limits. No full second catalogue in memory.
    private static final int SOURCE_QUERY_BATCH_SIZE = 64;
    static final class PreparedChannel {
        final ChannelEntity channel;
        final List<BackupSourceEntity> sources;
        PreparedChannel(ChannelEntity channel,List<BackupSourceEntity> sources) {
            this.channel=channel;this.sources=sources;
        }
    }

    public static final class ChannelSnapshot {
        public String name, url, group, tvgId, tvgName;
        public int activeSource;
        public final ArrayList<String> sources = new ArrayList<>();
        public final HashSet<String> sourceScopes = new HashSet<>();
        public final HashMap<String, HashSet<String>> backupSourceScopes = new HashMap<>();
    }

    /** The precise checkpoint created by a successful optimistic Room commit.
     * Do not re-read the version after commit: another worker may publish a
     * newer version between the transaction and that read. */
    public static final class CommitResult {
        public final int channelCount;
        public final long catalogueVersion;
        CommitResult(int channelCount, long catalogueVersion) {
            this.channelCount = channelCount;
            this.catalogueVersion = catalogueVersion;
        }
    }

    public static final class StaleCatalogueException extends IllegalStateException {
        public StaleCatalogueException() {
            super("Room catalogue changed during source scan; stale snapshot rejected");
        }
    }

    public static final class ChannelRefreshResult {
        public final int addedSources;
        public final long catalogueVersion;
        public final Map<String,java.util.Set<String>> addedRoutes;
        ChannelRefreshResult(int addedSources,long catalogueVersion,
                Map<String,java.util.Set<String>> addedRoutes){
            this.addedSources=addedSources;this.catalogueVersion=catalogueVersion;
            this.addedRoutes=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(addedRoutes));
        }
    }

    /**
     * Commit a bounded list of independently preflight-validated candidates for
     * one existing channel. Caller must establish station identity before calling.
     * No network, full-catalogue rewrite, staging mutation or route retirement here.
     * Exact checkpoint guard prevents stale results from updating a changed row.
     */
    public static ChannelRefreshResult appendVerifiedChannelSources(Context context,
            String channelId,long expectedVersion,
            Map<String,? extends java.util.Set<String>> verifiedBestFirst,
            BooleanSupplier paused) {
        if(channelId==null||channelId.isEmpty()||expectedVersion<0L||verifiedBestFirst==null)
            throw new IllegalArgumentException("Missing channel refresh identity/version");
        AppDatabase db=RoomDatabaseProvider.get(context);
        ChannelDao dao=db.channelDao();
        final ChannelRefreshResult[] result={null};
        db.runInTransaction(()->{
            checkPause(paused);
            CatalogueMigrationEntity checkpoint=dao.getValidatedCheckpoint(MasterCatalogueRepository.MIGRATION_KEY);
            if(checkpoint==null||!CatalogueVersionPolicy.matches(expectedVersion,checkpoint.completedAt))
                throw new StaleCatalogueException();
            ChannelEntity row=dao.getChannelById(channelId);
            if(row==null)throw new StaleCatalogueException();
            List<BackupSourceEntity> old=dao.getBackupSourcesForChannel(channelId);
            ArrayList<String> existing=new ArrayList<>();
            for(int i=0;i<old.size();i++){
                if(old.get(i).priority!=i)throw new IllegalStateException("Noncontiguous channel source priorities");
                existing.add(old.get(i).url);
            }
            if(existing.isEmpty()||!existing.contains(row.primaryUrl)
                    ||row.activeSource<0||row.activeSource>=existing.size())
                throw new IllegalStateException("Untrusted current channel source list");
            List<String> additions=com.wodedianshi.tv.SingleChannelRefreshPolicy.appendCandidates(
                existing,verifiedBestFirst.keySet(),8);
            if(additions.isEmpty()){
                result[0]=new ChannelRefreshResult(0,checkpoint.completedAt,java.util.Collections.emptyMap());return;
            }
            java.util.TreeSet<String> scopes=new java.util.TreeSet<>();
            try{
                JSONArray prior=new JSONArray(row.channelScopesJson);
                for(int i=0;i<prior.length();i++)scopes.add(prior.getString(i));
            }catch(org.json.JSONException malformed){
                throw new IllegalStateException("Invalid stored channel scope",malformed);
            }
            List<BackupSourceEntity> newRows=new ArrayList<>();
            Map<String,java.util.Set<String>> addedRoutes=new java.util.LinkedHashMap<>();
            for(String url:additions){
                checkPause(paused);
                java.util.TreeSet<String> owned=new java.util.TreeSet<>();
                java.util.Set<String> incoming=verifiedBestFirst.get(url);
                if(incoming!=null)for(String scope:incoming)
                    if(scope!=null&&!scope.isEmpty())owned.add(scope);
                scopes.addAll(owned);
                addedRoutes.put(url,java.util.Collections.unmodifiableSet(new java.util.HashSet<>(owned)));
                JSONArray sourceScopes=new JSONArray();
                for(String scope:owned)sourceScopes.put(scope);
                newRows.add(new BackupSourceEntity(channelId,url,old.size()+newRows.size(),sourceScopes.toString()));
            }
            JSONArray channelScopes=new JSONArray();for(String scope:scopes)channelScopes.put(scope);
            dao.updateChannelFields(row.id,row.legacyKey,row.name,row.primaryUrl,row.groupName,
                row.categoryId,row.tvgId,row.tvgName,channelScopes.toString(),row.activeSource,row.sortOrder);
            dao.insertBackupSources(newRows);
            long version=Math.max(System.currentTimeMillis(),checkpoint.completedAt+1L);
            dao.insertMigration(new CatalogueMigrationEntity(MasterCatalogueRepository.MIGRATION_KEY,
                checkpoint.channelsCount,Math.addExact(checkpoint.backupSourcesCount,newRows.size()),
                checkpoint.favoritesCount,checkpoint.categoriesCount,version,checkpoint.catalogueFingerprint));
            db.appStateDao().put(new AppStateEntity(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY,
                Long.toString(version),System.currentTimeMillis()));
            checkPause(paused);
            result[0]=new ChannelRefreshResult(newRows.size(),version,addedRoutes);
        });
        return result[0];
    }

    /** MainActivity must pass the version of the in-memory Room snapshot.
     * A stale in-memory catalogue is rejected atomically instead of overwriting
     * newer SourceUpdateWorker country/local channels.
     */
    public static CommitResult writeRecordsAtVersion(Context context,
            List<ChannelSnapshot> snapshot, java.util.Set<String> favoriteKeys,
            long expectedCatalogueVersion) {
        if (expectedCatalogueVersion < 0L) {
            throw new IllegalArgumentException("Expected Room catalogue version is not initialized");
        }
        final long[] committedAt = {-1L};
        int written = writeRecords(context, snapshot, favoriteKeys, expectedCatalogueVersion,
                false, false, committedAt);
        if (committedAt[0] < 0L) throw new IllegalStateException("Room commit lacked catalogue checkpoint");
        return new CommitResult(written, committedAt[0]);
    }

    // Deliberately no public unversioned writeRecords overload. Every ordinary
    // Activity catalogue save must use writeRecordsAtVersion() so an older
    // in-memory list can never silently replace the SourceUpdateWorker snapshot.
    // Only first-install initialization uses the unguarded private path.

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion,
            boolean clearBuildRowsOnCommit) {
        return writeRecords(context, snapshot, favoriteKeys, expectedCatalogueVersion,
                clearBuildRowsOnCommit, false);
    }

    /**
     * Establishes a valid zero-row Room checkpoint for a brand-new installation.
     * This is not a published channel catalogue: it only allows the first full
     * discovery worker to persist progress without inventing bundled channels.
     */
    public static void initializeEmptyCatalogue(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        writeRecords(context, java.util.Collections.emptyList(),
                java.util.Collections.emptySet(), -1L, false, true);
    }

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion,
            boolean clearBuildRowsOnCommit, boolean allowEmptyInitialization) {
        return writeRecords(context, snapshot, favoriteKeys, expectedCatalogueVersion,
                clearBuildRowsOnCommit, allowEmptyInitialization, null);
    }

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion,
            boolean clearBuildRowsOnCommit, boolean allowEmptyInitialization,
            long[] committedAt) {
        return writeRecords(context, snapshot, favoriteKeys, expectedCatalogueVersion,
                clearBuildRowsOnCommit, allowEmptyInitialization, committedAt, null);
    }

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion,
            boolean clearBuildRowsOnCommit, boolean allowEmptyInitialization,
            long[] committedAt, BooleanSupplier paused) {
        return writeRecords(context,snapshot,favoriteKeys,expectedCatalogueVersion,
                clearBuildRowsOnCommit,allowEmptyInitialization,committedAt,paused,null);
    }

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion,
            boolean clearBuildRowsOnCommit, boolean allowEmptyInitialization,
            long[] committedAt, BooleanSupplier paused, AppStateEditor progress) {
        return writeRecords(context,snapshot,favoriteKeys,expectedCatalogueVersion,
                clearBuildRowsOnCommit,allowEmptyInitialization,committedAt,paused,progress,null,null);
    }

    private static int writeRecords(Context context, List<ChannelSnapshot> snapshot,
            java.util.Set<String> favoriteKeys, long expectedCatalogueVersion,
            boolean clearBuildRowsOnCommit, boolean allowEmptyInitialization,
            long[] committedAt, BooleanSupplier paused, AppStateEditor progress,String manualOwner,String reconciledStructure) {
        checkPause(paused);
        if (context == null || snapshot == null) throw new IllegalArgumentException("catalogue snapshot is null");
        if (snapshot.isEmpty() && !allowEmptyInitialization) {
            throw new IllegalArgumentException("refusing to commit an empty catalogue");
        }
        // Compute once off the UI and outside the SQLite transaction.
        String coverageText = "";
        if (clearBuildRowsOnCommit) {
            FullDiscoveryProgressPolicy.Coverage coverage = new FullDiscoveryProgressPolicy.Coverage();
            for (ChannelSnapshot item : snapshot) {
                checkPause(paused);
                if (item != null) coverage.add(item.name,item.group,item.tvgName,item.tvgId,
                         item.sourceScopes.contains("china"));
            }
            coverageText = coverage.summary();
        }
        final String publishedCoverage = coverageText;
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        // A missing checkpoint means a genuinely fresh database. It may receive
        // either the explicit empty first-install checkpoint or a complete snapshot;
        // no legacy JSON import or sidecar catalogue is involved.
        final int[] written = {0};
        db.runInTransaction(() -> {
            checkPause(paused);
            CatalogueMigrationEntity checkpoint =
                    dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY);
            if (allowEmptyInitialization && (checkpoint != null || dao.countChannels() != 0
                    || dao.countBackupSources() != 0 || dao.countFavorites() != 0
                    || dao.countCategories() != 0)) {
                throw new IllegalStateException(
                        "Empty Room initialization is allowed only for a genuinely fresh database");
            }
            if (expectedCatalogueVersion >= 0L
                    && !CatalogueVersionPolicy.matches(expectedCatalogueVersion,
                            checkpoint == null ? null : checkpoint.completedAt)) {
                throw new StaleCatalogueException();
            }
            if(manualOwner!=null){
                RoomCatalogueBuildStore.requireManualQueueReady(db,manualOwner);
                if(reconciledStructure==null)RoomCatalogueBuildStore.requireUnchangedManualStructure(db,manualOwner);
                else RoomCatalogueBuildStore.requireReconciledManualStructure(db,manualOwner,reconciledStructure);
            }
            else if(clearBuildRowsOnCommit&&db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY)!=null)
                throw new IllegalStateException("Initial publication cannot clear manual staging");
            final java.util.function.Predicate<String> eligibleSelection=manualOwner==null?url->false:
                    manualSelectionEligibility(db,manualOwner);
            HashSet<String> previousChannelIds = new HashSet<>(dao.getAllChannelIds());
            HashSet<String> previousCategoryIds = new HashSet<>(dao.getAllCategoryIds());
            HashSet<String> presentChannelIds = new HashSet<>();
            HashSet<String> presentCategoryIds = new HashSet<>();
            HashSet<String> desiredFavoriteIds = new HashSet<>();
            HashMap<String, Long> preservedFavoriteTimes = new HashMap<>();
            HashMap<String, Integer> occurrences = new HashMap<>();
            HashMap<String, String> newIdsByStableIdentity = new HashMap<>();
            HashSet<String> ambiguousStableIdentities = new HashSet<>();
            // Capture favorites before stale channel rows are deleted. A full rebuild
            // may change the primary URL, but verified TVG ids, national broadcaster
            // names, or a local channel's same host/category preserve its identity.
            List<ChannelWithSources> previousFavorites = dao.getFavoriteChannels();
            HashMap<String, Long> previousFavoriteTimes = new HashMap<>();
            HashSet<String> ambiguousPreviousFavorites = new HashSet<>();
            for (ChannelWithSources favorite : previousFavorites) {
                checkPause(paused);
                if (favorite == null || favorite.channel == null) continue;
                ChannelEntity old = favorite.channel;
                String oldUrl = legacyPrimaryUrl(old.legacyKey, old.name, old.primaryUrl);
                String stableKey = LegacyChannelIdPolicy.stableIdentityKey(
                        old.name, oldUrl, old.tvgId, old.groupName, old.tvgName);
                Long addedAt = dao.getFavoriteAddedAt(old.id);
                long timestamp = addedAt == null ? System.currentTimeMillis() : addedAt;
                if (previousFavoriteTimes.containsKey(stableKey)) {
                    previousFavoriteTimes.remove(stableKey);
                    ambiguousPreviousFavorites.add(stableKey);
                } else if (!ambiguousPreviousFavorites.contains(stableKey)) {
                    previousFavoriteTimes.put(stableKey, timestamp);
                }
            }
            HashMap<String, Integer> categoryOrders = new HashMap<>();
            ArrayList<PreparedChannel> pendingChannels = new ArrayList<>(SOURCE_QUERY_BATCH_SIZE);

            for (int row = 0; row < snapshot.size(); row++) {
                checkPause(paused);
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
                    CategoryEntity category = new CategoryEntity(categoryId,
                            group.isEmpty() ? "未分类" : group, categoryOrder);
                    if (previousCategoryIds.contains(categoryId)) {
                        dao.updateCategoryFields(categoryId, category.name, categoryOrder);
                    } else {
                        dao.insertCategoryIfAbsent(category);
                    }
                    presentCategoryIds.add(categoryId);
                }

                String legacyKey = name + "|" + primary;
                LegacySourceOrderPolicy.Result ordered = LegacySourceOrderPolicy.normalize(
                        item.sources, primary, item.activeSource);
                if (ordered.urls.isEmpty()) continue;

                String stableIdentity = LegacyChannelIdPolicy.stableIdentityKey(
                        name, primary, item.tvgId, group, item.tvgName);
                int occurrence = occurrences.containsKey(stableIdentity)
                        ? occurrences.get(stableIdentity) : 0;
                occurrences.put(stableIdentity, occurrence + 1);
                String id = LegacyChannelIdPolicy.idForStableIdentity(stableIdentity, occurrence);
                if (!ambiguousStableIdentities.contains(stableIdentity)) {
                    if (newIdsByStableIdentity.containsKey(stableIdentity)) {
                        newIdsByStableIdentity.remove(stableIdentity);
                        ambiguousStableIdentities.add(stableIdentity);
                    } else {
                        newIdsByStableIdentity.put(stableIdentity, id);
                    }
                }
                int active = Math.max(0, Math.min(ordered.activeIndex, ordered.urls.size() - 1));
                JSONArray channelScopes = new JSONArray();
                if (item.sourceScopes != null) {
                    for (String scope : new java.util.TreeSet<>(item.sourceScopes)) channelScopes.put(scope);
                }
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
                    if (owned != null) {
                        for (String scope : new java.util.TreeSet<>(owned)) scopes.put(scope);
                    }
                    sourceRows.add(new BackupSourceEntity(id, url, i, scopes.toString()));
                }
                pendingChannels.add(new PreparedChannel(channel,sourceRows));
                if (pendingChannels.size() == SOURCE_QUERY_BATCH_SIZE) {
                    writePreparedBatch(dao,pendingChannels,previousChannelIds,paused,manualOwner!=null,eligibleSelection);
                    pendingChannels.clear();
                }
                presentChannelIds.add(id);
                if (favoriteKeys != null && favoriteKeys.contains(legacyKey)) {
                    desiredFavoriteIds.add(id);
                }
                written[0]++;
            }

            if (!pendingChannels.isEmpty()) {
                writePreparedBatch(dao,pendingChannels,previousChannelIds,paused,manualOwner!=null,eligibleSelection);
                pendingChannels.clear();
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
                // Retain unchanged IDs directly, then remap favorites whose primary
                // stream changed using a conservative stable TVG/broadcaster identity.
                desiredFavoriteIds.addAll(dao.getFavoriteChannelIds());
                desiredFavoriteIds.retainAll(presentChannelIds);
                for (Map.Entry<String, Long> oldFavorite : previousFavoriteTimes.entrySet()) {
                    checkPause(paused);
                    if (ambiguousPreviousFavorites.contains(oldFavorite.getKey())) continue;
                    String newId = newIdsByStableIdentity.get(oldFavorite.getKey());
                    if (newId == null || !presentChannelIds.contains(newId)) continue;
                    desiredFavoriteIds.add(newId);
                    preservedFavoriteTimes.put(newId, oldFavorite.getValue());
                }
            }

            // Remove only rows absent from the complete snapshot. FK cascades then
            // clean up the removed channel's own sources/favorite, never its peers.
            previousChannelIds.removeAll(presentChannelIds);
            for (String staleId : previousChannelIds) {
                checkPause(paused);
                dao.deleteChannelById(staleId);
            }

            // Preserve existing favorite timestamps; only apply actual additions/removals.
            HashSet<String> currentFavoriteIds = new HashSet<>(dao.getFavoriteChannelIds());
            for (String favoriteId : currentFavoriteIds) {
                checkPause(paused);
                if (!desiredFavoriteIds.contains(favoriteId)) dao.removeFavorite(favoriteId);
            }
            currentFavoriteIds.retainAll(desiredFavoriteIds);
            for (String favoriteId : desiredFavoriteIds) {
                checkPause(paused);
                if (!currentFavoriteIds.contains(favoriteId)) {
                    Long originalAddedAt = preservedFavoriteTimes.get(favoriteId);
                    dao.addFavorite(new FavoriteEntity(favoriteId,
                            originalAddedAt == null ? System.currentTimeMillis() : originalAddedAt));
                }
            }

            previousCategoryIds.removeAll(presentCategoryIds);
            for (String staleCategoryId : previousCategoryIds) {
                checkPause(paused);
                // Category rows are RESTRICT parents; deleting only after stale
                // channels are gone keeps the FK invariant intact.
                dao.deleteCategoryById(staleCategoryId);
            }

            checkPause(paused);
            long completedAt = Math.max(System.currentTimeMillis(),
                    checkpoint == null ? 1L : checkpoint.completedAt + 1L);
            dao.insertMigration(new CatalogueMigrationEntity(
                    MasterCatalogueRepository.MIGRATION_KEY, dao.countChannels(), dao.countBackupSources(),
                    dao.countFavorites(), dao.countCategories(), completedAt,
                    checkpoint == null
                            ? (allowEmptyInitialization ? "room_empty_initialization_v1" : "room_native_seed_v1")
                            : checkpoint.catalogueFingerprint));
            // This value is written in the same transaction as the rows and
            // is the only version that the committing Activity may adopt.
            if (committedAt != null) committedAt[0] = completedAt;
            if (progress != null) progress.applyInTransaction(db);
            if(manualOwner!=null){
                if(manualOwner.equals(db.appStateDao().getValue(ManualProbeQualityStore.READY_KEY)))
                    ManualProbeQualityStore.publishInTransaction(db,manualOwner);
                RoomCatalogueBuildStore.clearManualInTransaction(db,manualOwner);
            }
            // Every full directory commit may change structure. Do not relax the
            // ordinary checkpoint that also protects user favorite/source writes.
            db.appStateDao().put(new AppStateEntity(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY,
                    Long.toString(completedAt),System.currentTimeMillis()));
            if (clearBuildRowsOnCommit) {
                // Publish and discard the staging snapshot in the same transaction.
                // A crash cannot leave a stale build marked active after publication.
                dao.clearCatalogueBuildRows();
                db.appStateDao().remove(RoomCatalogueBuildStore.STAGING_REVISION_KEY);
                long publishedAt = System.currentTimeMillis();
                db.appStateDao().put(new AppStateEntity(
                        "source_full_rebuild_completed_at",
                        Long.toString(publishedAt), publishedAt));
                db.appStateDao().put(new AppStateEntity(
                        "source_update_last_success_at",
                        Long.toString(publishedAt), publishedAt));
                db.appStateDao().put(new AppStateEntity(
                        FullDiscoveryProgressPolicy.PUBLISHED_COVERAGE_KEY, publishedCoverage, publishedAt));
                db.appStateDao().put(new AppStateEntity(
                        "source_initial_progress_percent", "100", publishedAt));
                db.appStateDao().put(new AppStateEntity(
                        "source_initial_progress_staged_channels", Integer.toString(written[0]), publishedAt));
                db.appStateDao().remove("source_full_rebuild_active");
            }
            // Throwing from this callback rolls back rows, checkpoint and staging together.
            checkPause(paused);
        });
        return written[0];
    }

    /** Read one bounded set of existing relations, then apply the original
     * per-channel reconciliation inside the same outer Room transaction. */
    static void writePreparedBatch(ChannelDao dao,List<PreparedChannel> batch,
            HashSet<String> previousChannelIds,BooleanSupplier paused) {
        writePreparedBatch(dao,batch,previousChannelIds,paused,false);
    }

    static void writePreparedBatch(ChannelDao dao,List<PreparedChannel> batch,
            HashSet<String> previousChannelIds,BooleanSupplier paused,boolean preserveLiveSelection) {
        writePreparedBatch(dao,batch,previousChannelIds,paused,preserveLiveSelection,url->false);
    }

    static void writePreparedBatch(ChannelDao dao,List<PreparedChannel> batch,
            HashSet<String> previousChannelIds,BooleanSupplier paused,boolean preserveLiveSelection,
            java.util.function.Predicate<String> eligibleSelection) {
        checkPause(paused);
        ArrayList<String> existingIds=new ArrayList<>(batch.size());
        for(PreparedChannel item:batch)
            if(previousChannelIds.contains(item.channel.id))existingIds.add(item.channel.id);
        HashMap<String,HashMap<String,BackupSourceEntity>> oldByChannel=new HashMap<>();
        if(!existingIds.isEmpty()) {
            for(BackupSourceEntity old:dao.getBackupSourcesForChannels(existingIds)) {
                checkPause(paused);
                oldByChannel.computeIfAbsent(old.channelId,key->new HashMap<>()).put(old.url,old);
            }
        }
        HashMap<String,ChannelDao.ActiveSelection> liveSelections=new HashMap<>();
        HashSet<String> ambiguousSelections=new HashSet<>();
        if(preserveLiveSelection&&!existingIds.isEmpty()){
            for(ChannelDao.ActiveSelection selection:dao.getActiveSelectionsForChannels(existingIds)){
                checkPause(paused);
                if(liveSelections.put(selection.channelId,selection)!=null)
                    ambiguousSelections.add(selection.channelId);
            }
        }
        for(PreparedChannel item:batch) {
            checkPause(paused);
            ChannelEntity channel=item.channel;
            String id=channel.id;
            if(previousChannelIds.contains(id)) {
                HashMap<String,BackupSourceEntity> oldSources=oldByChannel.remove(id);
                if(oldSources==null)oldSources=new HashMap<>();
                if(preserveLiveSelection&&!ambiguousSelections.contains(id)){
                    ChannelDao.ActiveSelection live=liveSelections.get(id);
                    if(live!=null&&channel.legacyKey.equals(live.legacyKey)){
                        retainLiveSelection(item,oldSources,live.url,eligibleSelection);
                        channel.activeSource=resolveLiveSelection(item.sources,live.url);
                    }
                }
                dao.updateChannelFields(channel.id,channel.legacyKey,channel.name,
                        channel.primaryUrl,channel.groupName,channel.categoryId,
                        channel.tvgId,channel.tvgName,channel.channelScopesJson,
                        channel.activeSource,channel.sortOrder);
                ArrayList<BackupSourceEntity> changedSources=new ArrayList<>();
                for(BackupSourceEntity next:item.sources) {
                    BackupSourceEntity old=oldSources.remove(next.url);
                    if(old==null||old.priority!=next.priority||!old.scopesJson.equals(next.scopesJson))
                        changedSources.add(next);
                }
                for(String staleUrl:oldSources.keySet()) {
                    checkPause(paused);
                    dao.deleteBackupSource(id,staleUrl);
                }
                if(!changedSources.isEmpty())dao.insertBackupSources(changedSources);
            } else {
                dao.insertChannelIfAbsent(channel);
                if(!item.sources.isEmpty())dao.insertBackupSources(item.sources);
            }
        }
    }

    private static java.util.function.Predicate<String> manualSelectionEligibility(AppDatabase db,String owner){
        if(owner.equals(db.appStateDao().getValue(ManualProbeQualityStore.READY_KEY)))return url->{
            ManualProbeQualityEntity row=db.manualProbeQualityDao().find(owner,com.wodedianshi.tv.StableKey.url(url));
            return row!=null&&row.score!=null&&row.score>0&&(row.failureStreak==null||row.failureStreak<com.wodedianshi.tv.SourceMaintenanceRetentionPolicy.FAILURES_BEFORE_RETIREMENT);
        };
        try{
            String rawScores=db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_SCORES_KEY);
            String rawStreaks=db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_STREAKS_KEY);
            final JSONObject scores=new JSONObject(rawScores==null?"{}":rawScores);
            final JSONObject streaks=new JSONObject(rawStreaks==null?"{}":rawStreaks);
            return url->{String key=com.wodedianshi.tv.StableKey.url(url);
                return scores.optInt(key,0)>0&&streaks.optInt(key,0)<com.wodedianshi.tv.SourceMaintenanceRetentionPolicy.FAILURES_BEFORE_RETIREMENT;};
        }catch(org.json.JSONException malformed){return url->false;}
    }

    /** Keep a positively scored current old URL if the staged ranking evicted
     * it. Never displace a retained old route or the ranked primary. */
    static void retainLiveSelection(PreparedChannel item,Map<String,BackupSourceEntity> oldSources,
            String selected,java.util.function.Predicate<String> eligible){
        if(selected==null||!oldSources.containsKey(selected))return;
        if(!selected.startsWith("http://")&&!selected.startsWith("https://"))return;
        String endpoint=com.wodedianshi.tv.SourceSelectionPolicy.endpointKey(selected);
        for(BackupSourceEntity row:item.sources)
            if(endpoint.equals(com.wodedianshi.tv.SourceSelectionPolicy.endpointKey(row.url)))return;
        if(!eligible.test(selected))return;
        int position=selectionRetentionSlot(item,oldSources);
        if(position<0)return;
        BackupSourceEntity old=oldSources.get(selected);
        BackupSourceEntity restored=new BackupSourceEntity(item.channel.id,selected,position,old.scopesJson);
        // Keep source scope ownership consistent with the restored relation.
        final String mergedScopes;
        try{
            java.util.TreeSet<String> scopes=new java.util.TreeSet<>();
            for(String raw:new String[]{item.channel.channelScopesJson,old.scopesJson}){
                JSONArray array=new JSONArray(raw);
                for(int i=0;i<array.length();i++)scopes.add(array.getString(i));
            }
            JSONArray merged=new JSONArray();for(String scope:scopes)merged.put(scope);
            mergedScopes=merged.toString();
        }catch(org.json.JSONException malformed){return;}
        if(position==item.sources.size())item.sources.add(restored);
        else item.sources.set(position,restored);
        item.channel.channelScopesJson=mergedScopes;
    }

    static int selectionRetentionSlot(PreparedChannel item,Map<String,BackupSourceEntity> oldSources){
        final int limit=8;
        if(item.sources.size()>limit)return -1;
        if(item.sources.size()<limit)return item.sources.size();
        for(int i=item.sources.size()-1;i>0;i--){
            BackupSourceEntity row=item.sources.get(i);
            if(!oldSources.containsKey(row.url)&&!row.url.equals(legacyPrimaryUrl(item.channel.legacyKey,item.channel.name,item.channel.primaryUrl)))return i;
        }
        return -1;
    }

    /** Preserve an exact current URL if retained, otherwise use ranked source 0.
     * Never reuse a numeric index across reordered source arrays. */
    static int resolveLiveSelection(List<BackupSourceEntity> sources,String liveUrl){
        if(liveUrl!=null)for(BackupSourceEntity source:sources)
            if(liveUrl.equals(source.url))return source.priority;
        return 0;
    }

    private static void checkPause(BooleanSupplier paused) {
        // UI-requested edits use the original non-cancellable path.
        if (paused != null) SourceProbeTimePolicy.checkPublicationPaused(paused);
    }

    private static String legacyPrimaryUrl(String legacyKey, String name, String fallback) {
        String prefix = (name == null ? "" : name) + "|";
        if (legacyKey != null && legacyKey.startsWith(prefix)) {
            return legacyKey.substring(prefix.length());
        }
        return fallback == null ? "" : fallback;
    }

    /** Source updater snapshots are complete Room-derived catalogues too. */
    public static int writeSnapshot(Context context, JSONArray snapshot) throws Exception {
        return writeSnapshot(context, snapshot, -1L);
    }

    /** Commit only if no user/catalogue mutation has happened since the worker read Room. */
    public static int writeSnapshot(Context context, JSONArray snapshot,
            long expectedCatalogueVersion) throws Exception {
        return writeSnapshot(context, snapshot, expectedCatalogueVersion, false, null);
    }

    /** Atomically publish a complete rebuild and clear its staging rows. */
    public static int writeSnapshotAndClearBuild(Context context, JSONArray snapshot,
            long expectedCatalogueVersion) throws Exception {
        return writeSnapshot(context, snapshot, expectedCatalogueVersion, true, null);
    }

    /** Background-only cancellable publication. The caller must supply a version. */
    public static int writeSnapshot(Context context, JSONArray snapshot,
            long expectedCatalogueVersion, BooleanSupplier paused) throws Exception {
        if (expectedCatalogueVersion < 0L) throw new IllegalArgumentException("Missing catalogue version");
        return writeSnapshot(context, snapshot, expectedCatalogueVersion, false, paused);
    }

    public static int writeSnapshotAndClearBuild(Context context, JSONArray snapshot,
            long expectedCatalogueVersion, BooleanSupplier paused) throws Exception {
        if (expectedCatalogueVersion < 0L) throw new IllegalArgumentException("Missing catalogue version");
        return writeSnapshot(context, snapshot, expectedCatalogueVersion, true, paused);
    }

    private static int writeSnapshot(Context context, JSONArray snapshot,
            long expectedCatalogueVersion, boolean clearBuildRowsOnCommit,
            BooleanSupplier paused) throws Exception {
        return writeSnapshot(context,snapshot,expectedCatalogueVersion,clearBuildRowsOnCommit,paused,null);
    }

    /** Commit a worker's snapshot and its progress together. */
    public static int writeSnapshotWithProgress(Context context, JSONArray snapshot,
            long expectedCatalogueVersion, boolean clearBuildRowsOnCommit,
            BooleanSupplier paused, AppStateEditor progress) throws Exception {
        if (expectedCatalogueVersion < 0L || progress == null)
            throw new IllegalArgumentException("Missing catalogue version or progress");
        return writeSnapshot(context,snapshot,expectedCatalogueVersion,clearBuildRowsOnCommit,paused,progress);
    }

    /** Final manual publication has its own ownership check and cleanup, never
     * writes initial-build completion markers. Caller must reconcile live edits
     * and supply the precise version backing the reconciled snapshot. */
    public static int publishManualSnapshot(Context context,JSONArray snapshot,long expectedCatalogueVersion,
            String manualOwner,BooleanSupplier paused,AppStateEditor progress)throws Exception {
        if(expectedCatalogueVersion<0||manualOwner==null||manualOwner.isEmpty()||progress==null)
            throw new IllegalArgumentException("Missing manual publication identity/version/progress");
        return writeSnapshot(context,snapshot,expectedCatalogueVersion,false,paused,progress,manualOwner,null);
    }

    /** Only the source worker's conservative three-way merge uses this path.
     * Both the exact live checkpoint and the reconciled structural epoch must match. */
    public static int publishReconciledManualSnapshot(Context context,JSONArray snapshot,long expectedCatalogueVersion,
            String manualOwner,String reconciledStructure,BooleanSupplier paused,AppStateEditor progress)throws Exception {
        if(expectedCatalogueVersion<0||manualOwner==null||manualOwner.isEmpty()||reconciledStructure==null
                ||reconciledStructure.isEmpty()||progress==null)throw new IllegalArgumentException("Missing reconciled publication version");
        return writeSnapshot(context,snapshot,expectedCatalogueVersion,false,paused,progress,manualOwner,reconciledStructure);
    }

    private static int writeSnapshot(Context context, JSONArray snapshot,
            long expectedCatalogueVersion, boolean clearBuildRowsOnCommit,
            BooleanSupplier paused, AppStateEditor progress) throws Exception {
        return writeSnapshot(context,snapshot,expectedCatalogueVersion,clearBuildRowsOnCommit,paused,progress,null,null);
    }

    private static int writeSnapshot(Context context, JSONArray snapshot,
            long expectedCatalogueVersion, boolean clearBuildRowsOnCommit,
            BooleanSupplier paused, AppStateEditor progress,String manualOwner,String reconciledStructure) throws Exception {
        checkPause(paused);
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
            checkPause(paused);
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
        return writeRecords(context, rows, favoriteKeys, expectedCatalogueVersion,
                clearBuildRowsOnCommit, false, null, paused, progress,manualOwner,reconciledStructure);
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
