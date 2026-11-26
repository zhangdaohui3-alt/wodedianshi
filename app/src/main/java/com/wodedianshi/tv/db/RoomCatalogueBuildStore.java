package com.wodedianshi.tv.db;

import android.content.Context;
import com.wodedianshi.tv.SourceProbeTimePolicy;
import java.util.function.BooleanSupplier;
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
    public static final String STAGING_REVISION_KEY="source_initial_staging_revision";
    public static final String MANUAL_OWNER_KEY="source_manual_staging_owner";
    public static final String MANUAL_BASE_VERSION_KEY="source_manual_staging_base_version";
    public static final String MANUAL_BASE_STRUCTURE_KEY="source_manual_staging_base_structure";
    public static final String MANUAL_READY_KEY="source_manual_staging_ready";
    private static final String MANUAL_QUEUE_KEY="source_manual_queue_owner_v1";
    private static final String MANUAL_IDENTITY_KEY="source_manual_identity_index_owner_v1";
    public static final String MANUAL_BASE_COUNT_KEY="source_manual_staging_base_count";

    public static String currentStructureVersion(Context context){
        return RoomDatabaseProvider.get(context).appStateDao().getValue(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY);
    }
    static void requireReconciledManualStructure(AppDatabase db,String owner,String expected){
        requireManualOwner(db,owner);
        if(!CatalogueVersionPolicy.structureMatches(expected,db.appStateDao().getValue(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY)))
            throw new RoomCatalogueWriter.StaleCatalogueException();
    }
    public static final String MANUAL_SCORES_KEY="source_manual_staging_scores";
    public static final String MANUAL_STREAKS_KEY="source_manual_staging_streaks";

    public static boolean manualStructureStillCurrent(Context context,String owner){
        AppStateDao state=RoomDatabaseProvider.get(context).appStateDao();
        requireMatchingOwner(owner,state.getValue(MANUAL_OWNER_KEY));
        return CatalogueVersionPolicy.structureMatches(state.getValue(MANUAL_BASE_STRUCTURE_KEY),
                state.getValue(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY));
    }

    /** Never authorize a final publish solely from a fresh total version: a
     * structural edit may have changed the old directory since staging began. */
    static void requireUnchangedManualStructure(AppDatabase db,String owner){
        requireManualOwner(db,owner);
        AppStateDao state=db.appStateDao();
        if(!CatalogueVersionPolicy.structureMatches(state.getValue(MANUAL_BASE_STRUCTURE_KEY),
                state.getValue(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY)))
            throw new RoomCatalogueWriter.StaleCatalogueException();
    }

    static void requireManualQueueReady(AppDatabase db,String owner){
        requireManualOwner(db,owner);
        AppStateDao state=db.appStateDao();
        requireMatchingOwner(owner,state.getValue(MANUAL_QUEUE_KEY));
        if(!"true".equals(state.getValue(MANUAL_READY_KEY))||db.manualProbeQueueDao().countPending(owner)!=0)
            throw new IllegalStateException("Manual queue has unfinished work");
    }

    static void requireManualOwner(AppDatabase db,String owner){
        if(!db.inTransaction())throw new IllegalStateException("Manual staging requires transaction");
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_OWNER_KEY));
    }
    static void requireMatchingOwner(String expected,String actual){
        if(expected==null||expected.isEmpty()||!expected.equals(actual))
            throw new IllegalStateException("Manual staging owner changed");
    }
    static void clearManualInTransaction(AppDatabase db,String owner){
        requireManualOwner(db,owner);
        db.manualProbeQueueDao().clearOwner(owner);
        db.manualProbeQualityDao().clearOwner(owner);
        db.appStateDao().remove(ManualProbeQualityStore.READY_KEY);
        db.channelDao().clearCatalogueBuildRows();
        db.appStateDao().remove(MANUAL_OWNER_KEY);
        db.appStateDao().remove(MANUAL_BASE_VERSION_KEY);
        db.appStateDao().remove(MANUAL_BASE_STRUCTURE_KEY);
        db.appStateDao().remove(MANUAL_READY_KEY);
        db.appStateDao().remove(MANUAL_QUEUE_KEY);
        db.appStateDao().remove(MANUAL_IDENTITY_KEY);
        db.appStateDao().remove(MANUAL_BASE_COUNT_KEY);
        db.appStateDao().remove(MANUAL_SCORES_KEY);
        db.appStateDao().remove(MANUAL_STREAKS_KEY);
    }

    /** Seed an isolated manual build only from a still-current published snapshot.
     * No active initial-build rows may be taken over. Caller is sole source worker. */
    public static int seedManual(Context context,JSONArray snapshot,long baseVersion,String owner,
            BooleanSupplier paused,AppStateEditor progress)throws JSONException {
        if(owner==null||owner.isEmpty()||baseVersion<0||progress==null||snapshot==null||snapshot.length()==0)
            throw new IllegalArgumentException("Invalid manual staging seed");
        return replaceInternal(context,snapshot,paused,progress,owner,baseVersion);
    }

    /** Only the saved-only tail can work without reading any staging JSON.
     * Upgraded or not-yet-initialized rounds must take the normal seed path. */
    public static boolean canRunManualQueueOnly(Context context,String owner,int feedCount){
        AppDatabase db=RoomDatabaseProvider.get(context);
        return queueOnlyReady(db.appStateDao(),owner,feedCount);
    }

    private static boolean queueOnlyReady(AppStateDao state,String owner,int feedCount){
        if(owner==null||owner.isEmpty()||feedCount<=0)return false;
        return owner.equals(state.getValue(MANUAL_OWNER_KEY))
            &&owner.equals(state.getValue(MANUAL_QUEUE_KEY))
            &&owner.equals(state.getValue(ManualProbeQualityStore.READY_KEY))
            &&Integer.toString(feedCount).equals(state.getValue("source_manual_next_feed"))
            &&!"true".equals(state.getValue(MANUAL_READY_KEY));
    }

    public static boolean commitManualQueueOnly(Context context,String owner,int feedCount,int failedFeeds,
            List<String> processed,List<ManualProbeQualityEntity> quality,BooleanSupplier paused,AppStateEditor progress){
        AppDatabase db=RoomDatabaseProvider.get(context);
        boolean[] ready={false};
        db.runInTransaction(()->{
            checkPause(paused);
            requireManualOwner(db,owner);
            if(!queueOnlyReady(db.appStateDao(),owner,feedCount)||db.channelDao().countCatalogueBuildRows()<=0)
                throw new IllegalStateException("Manual queue-only checkpoint changed");
            commitManualProbeProgress(db,owner,processed,true,feedCount,feedCount,
                new ManualCommit(ready,failedFeeds,quality),paused,progress);
            progress.applyInTransaction(db);
            checkPause(paused);
        });
        return ready[0];
    }

    private static String mergeIdentity(JSONObject row){
        return com.wodedianshi.tv.SourceDiscoveryIdentityPolicy.identity(row.optString("name"),row.optString("group"),
            row.optString("url"),row.optString("tvgId"),row.optString("tvgName"));
    }

    /** Upgrade an existing manual round once. Existing JSON, queue and cursors stay intact. */
    public static void ensureManualIdentityIndex(Context context,String owner,BooleanSupplier paused)throws JSONException{
        AppDatabase db=RoomDatabaseProvider.get(context);
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_OWNER_KEY));
        if(owner.equals(db.appStateDao().getValue(MANUAL_IDENTITY_KEY)))return;
        // Prepare outside the write transaction, retaining only row ID and identity.
        java.util.LinkedHashMap<String,String> keys=new java.util.LinkedHashMap<>();
        for(CatalogueBuildRowEntity row:db.channelDao().getCatalogueBuildRows()){
            checkPause(paused);
            keys.put(row.rowId,mergeIdentity(new JSONObject(row.recordJson)));
        }
        db.runInTransaction(()->{
            checkPause(paused);
            requireManualOwner(db,owner);
            if(owner.equals(db.appStateDao().getValue(MANUAL_IDENTITY_KEY)))return;
            if(db.channelDao().countCatalogueBuildRows()!=keys.size())
                throw new IllegalStateException("Staging identity backfill count changed");
            for(java.util.Map.Entry<String,String> key:keys.entrySet()){
                checkPause(paused);
                if(db.channelDao().setCatalogueBuildIdentity(key.getKey(),key.getValue())!=1)
                    throw new IllegalStateException("Staging identity backfill row missing");
            }
            db.appStateDao().put(new AppStateEntity(MANUAL_IDENTITY_KEY,owner,System.currentTimeMillis()));
            checkPause(paused);
        });
    }

    public static boolean canRunManualIndexed(Context context,String owner){
        AppStateDao state=RoomDatabaseProvider.get(context).appStateDao();
        return owner!=null&&!owner.isEmpty()&&owner.equals(state.getValue(MANUAL_OWNER_KEY))
            &&owner.equals(state.getValue(MANUAL_QUEUE_KEY))
            &&owner.equals(state.getValue(ManualProbeQualityStore.READY_KEY))
            &&!"true".equals(state.getValue(MANUAL_READY_KEY));
    }

    public static int manualStagingCount(Context context,String owner){
        AppDatabase db=RoomDatabaseProvider.get(context);
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_OWNER_KEY));
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_IDENTITY_KEY));
        int count=db.channelDao().countCatalogueBuildRows();
        if(count<=0)throw new IllegalStateException("Empty manual staging");
        return count;
    }

    /** Map local working-set positions back to durable staging positions.
     * Existing rows retain their positions; new rows append after the full staging count. */
    static int indexedRowPosition(int baseCount,List<Integer> existing,int local){
        if(baseCount<=0||local<0)throw new IllegalArgumentException("Invalid indexed staging position");
        if(local<existing.size()){
            int position=existing.get(local);
            if(position<0||position>=baseCount)throw new IllegalArgumentException("Existing row outside staging");
            return position;
        }
        return Math.addExact(baseCount,local-existing.size());
    }

    public static boolean commitManualIndexed(Context context,String owner,int baseCount,
            List<Integer> existing,JSONArray working,java.util.Set<Integer> touched,
            BooleanSupplier paused,AppStateEditor progress,List<String> processed,
            int nextFeed,int feedCount,int failedFeeds,List<ManualProbeQualityEntity> quality)throws JSONException{
        validateTouchedRows(existing.size(),working.length(),touched);
        if(baseCount<=0||new java.util.HashSet<>(existing).size()!=existing.size())
            throw new IllegalArgumentException("Invalid indexed staging base");
        for(int i=0;i<existing.size();i++)indexedRowPosition(baseCount,existing,i);
        ArrayList<CatalogueBuildRowEntity> changed=new ArrayList<>();
        ArrayList<JSONObject> changedRecords=new ArrayList<>();
        for(int local:touched){
            checkPause(paused);
            JSONObject record=working.getJSONObject(local);
            if(record.optString("name","").trim().isEmpty()||record.optString("url","").trim().isEmpty())
                throw new JSONException("Invalid indexed staging row");
            int position=indexedRowPosition(baseCount,existing,local);
            CatalogueBuildRowEntity entity=new CatalogueBuildRowEntity(
                String.format(java.util.Locale.ROOT,"row-%08d",position),position,record.toString());
            entity.mergeIdentity=mergeIdentity(record);
            changed.add(entity);changedRecords.add(record);
        }
        int finalCount=Math.addExact(baseCount,working.length()-existing.size());
        AppDatabase db=RoomDatabaseProvider.get(context);
        boolean[] ready={false};
        db.runInTransaction(()->{
            checkPause(paused);
            requireManualOwner(db,owner);
            requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_IDENTITY_KEY));
            if(!canRunManualIndexed(context,owner)||db.channelDao().countCatalogueBuildRows()!=baseCount)
                throw new IllegalStateException("Indexed staging base changed");
            for(int i=0;i<changed.size();i+=64){
                checkPause(paused);
                db.channelDao().insertCatalogueBuildRows(changed.subList(i,Math.min(i+64,changed.size())));
            }
            if(db.channelDao().countCatalogueBuildRows()!=finalCount)
                throw new IllegalStateException("Indexed staging count mismatch");
            for(int i=0;i<changed.size();i++)enqueueRow(db,owner,changedRecords.get(i),changed.get(i).sortOrder,paused);
            // Directory discovery slices must never certify the saved-only tail complete.
            commitManualProbeProgress(db,owner,processed,false,nextFeed,feedCount,
                new ManualCommit(ready,failedFeeds,quality),paused,progress);
            progress.applyInTransaction(db);
            checkPause(paused);
        });
        return ready[0];
    }

    /** Returns matching rows in original order; duplicate identities remain distinct. */
    public static List<CatalogueBuildRowEntity> readManualByIdentity(Context context,String owner,
            java.util.Collection<String> identities,BooleanSupplier paused){
        AppDatabase db=RoomDatabaseProvider.get(context);
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_OWNER_KEY));
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_IDENTITY_KEY));
        List<String> keys=new ArrayList<>(new java.util.LinkedHashSet<>(identities));
        List<CatalogueBuildRowEntity> result=new ArrayList<>();
        for(int i=0;i<keys.size();i+=256){
            checkPause(paused);
            result.addAll(db.channelDao().getCatalogueBuildRowsByIdentity(keys.subList(i,Math.min(i+256,keys.size()))));
        }
        result.sort(java.util.Comparator.comparingInt((CatalogueBuildRowEntity row)->row.sortOrder).thenComparing(row->row.rowId));
        checkPause(paused);
        return result;
    }

    public static JSONArray readManual(Context context,String owner,BooleanSupplier paused)throws JSONException {
        requireMatchingOwner(owner,RoomDatabaseProvider.get(context).appStateDao().getValue(MANUAL_OWNER_KEY));
        return readRows(context,paused);
    }

    public static void discardManual(Context context,String owner,AppStateEditor progress,BooleanSupplier paused){
        AppDatabase db=RoomDatabaseProvider.get(context);
        db.runInTransaction(()->{
            checkPause(paused);
            clearManualInTransaction(db,owner);
            if(progress!=null)progress.applyInTransaction(db);
            checkPause(paused);
        });
    }

    public static JSONArray read(Context context) throws JSONException {
        return read(context, null);
    }

    public static JSONArray read(Context context, BooleanSupplier paused) throws JSONException {
        if(RoomDatabaseProvider.get(context).appStateDao().getValue(MANUAL_OWNER_KEY)!=null)
            throw new IllegalStateException("Initial discovery cannot read manual staging");
        return readRows(context,paused);
    }

    private static JSONArray readRows(Context context,BooleanSupplier paused)throws JSONException {
        checkPause(paused);
        List<CatalogueBuildRowEntity> rows = RoomDatabaseProvider.get(context)
                .channelDao().getCatalogueBuildRows();
        JSONArray result = new JSONArray();
        for (CatalogueBuildRowEntity row : rows) {
            checkPause(paused);
            JSONObject record = new JSONObject(row.recordJson);
            if (record.optString("name", "").trim().isEmpty()
                    || record.optString("url", "").trim().isEmpty()) {
                throw new JSONException("Invalid staged catalogue row " + row.rowId);
            }
            result.put(record);
        }
        checkPause(paused);
        return result;
    }

    public static int replace(Context context, JSONArray snapshot) throws JSONException {
        return replace(context, snapshot, null);
    }

    public static int replace(Context context, JSONArray snapshot, BooleanSupplier paused) throws JSONException {
        return replace(context,snapshot,paused,null);
    }

    public static int replace(Context context, JSONArray snapshot, BooleanSupplier paused,
            AppStateEditor progress) throws JSONException {
        return replaceInternal(context,snapshot,paused,progress,null,-1L);
    }

    private static int replaceInternal(Context context,JSONArray snapshot,BooleanSupplier paused,
            AppStateEditor progress,String manualOwner,long baseVersion)throws JSONException {
        checkPause(paused);
        if (context == null || snapshot == null) {
            throw new IllegalArgumentException("context/snapshot is null");
        }
        AppDatabase db = RoomDatabaseProvider.get(context);
        ChannelDao dao = db.channelDao();
        // A rebuild may span hundreds of WorkManager runs. Compare against the
        // existing staging rows and write only changed JSON records instead of
        // deleting/reinserting the entire growing catalogue after every batch.
        java.util.HashMap<String, CatalogueBuildRowEntity> existingRows = new java.util.HashMap<>();
        for (CatalogueBuildRowEntity row : dao.getCatalogueBuildRows()) {
            checkPause(paused);
            existingRows.put(row.rowId, row);
        }
        ArrayList<CatalogueBuildRowEntity> changedRows = new ArrayList<>();
        for (int i = 0; i < snapshot.length(); i++) {
            checkPause(paused);
            JSONObject record = snapshot.optJSONObject(i);
            if (record == null || record.optString("name", "").trim().isEmpty()
                    || record.optString("url", "").trim().isEmpty()) {
                throw new JSONException("Invalid full-rebuild row at index " + i);
            }
            String rowId = String.format(java.util.Locale.ROOT, "row-%08d", i);
            String recordJson = record.toString();
            // Initial discovery uses the same durable merge identity as the
            // manual staging path. Repair missing/stale identities even when
            // JSON is unchanged; old installations need no schema migration.
            String identity=mergeIdentity(record);
            CatalogueBuildRowEntity old=existingRows.get(rowId);
            if (old==null||!recordJson.equals(old.recordJson)||!identity.equals(old.mergeIdentity)
                    ||old.sortOrder!=i) {
                CatalogueBuildRowEntity entity=new CatalogueBuildRowEntity(rowId,i,recordJson);
                entity.mergeIdentity=identity;
                changedRows.add(entity);
            }
        }
        checkPause(paused);
        db.runInTransaction(() -> {
            checkPause(paused);
            String existingOwner=db.appStateDao().getValue(MANUAL_OWNER_KEY);
            if(manualOwner!=null){
                CatalogueMigrationEntity checkpoint=dao.getMigration(MasterCatalogueRepository.MIGRATION_KEY);
                if(!CatalogueVersionPolicy.matches(baseVersion,checkpoint==null?null:checkpoint.completedAt))
                    throw new RoomCatalogueWriter.StaleCatalogueException();
                if(existingOwner!=null||dao.countCatalogueBuildRows()!=0)
                    throw new IllegalStateException("Staging is already owned or not empty");
                String initialCompleted=db.appStateDao().getValue("source_full_rebuild_completed_at");
                long completedAt=0L;
                try{completedAt=Long.parseLong(initialCompleted);}catch(NumberFormatException ignored){}
                if(completedAt<=0)throw new IllegalStateException("Initial discovery not complete");
            }else if(existingOwner!=null)throw new IllegalStateException("Manual staging must not be replaced by initial discovery");
            for (int i = 0; i < changedRows.size(); i += 64) {
                checkPause(paused);
                dao.insertCatalogueBuildRows(changedRows.subList(i, Math.min(i + 64, changedRows.size())));
            }
            checkPause(paused);
            dao.deleteCatalogueBuildRowsFrom(snapshot.length());
            if (progress != null) progress.applyInTransaction(db);
            db.appStateDao().remove(STAGING_REVISION_KEY);
            if(manualOwner!=null){
                long now=System.currentTimeMillis();
                db.appStateDao().put(new AppStateEntity(MANUAL_OWNER_KEY,manualOwner,now));
                db.appStateDao().put(new AppStateEntity(MANUAL_BASE_VERSION_KEY,Long.toString(baseVersion),now));
                // Seed the structural epoch from the checked live version on upgrade.
                // Favorite/source mutations keep the general checkpoint advancing,
                // but do not update this independent complete-write epoch.
                String stored=db.appStateDao().getValue(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY);
                long structure=CatalogueVersionPolicy.readStructureVersion(stored,baseVersion);
                db.appStateDao().put(new AppStateEntity(CatalogueVersionPolicy.STRUCTURE_VERSION_KEY,Long.toString(structure),now));
                db.appStateDao().put(new AppStateEntity(MANUAL_BASE_STRUCTURE_KEY,Long.toString(structure),now));
                seedQueueInTransaction(db,manualOwner,snapshot,paused);
                db.appStateDao().put(new AppStateEntity(MANUAL_IDENTITY_KEY,manualOwner,now));
            }
            checkPause(paused);
        });
        return snapshot.length();
    }

    // Insert every distinct endpoint in the round, including addresses later
    // retired from staging. A saved-only health check never resurrects a retired route.
    private static void enqueueRow(AppDatabase db,String owner,JSONObject row,int ordinal,
            BooleanSupplier paused){
        checkPause(paused);
        java.util.LinkedHashMap<String,ManualProbeQueueEntity> entries=new java.util.LinkedHashMap<>();
        JSONArray urls=row.optJSONArray("sources");
        int length=urls==null?0:urls.length();
        for(int i=0;i<=length;i++){
            String url=i==length?row.optString("url"):urls.optString(i);
            if(!url.startsWith("http://")&&!url.startsWith("https://"))continue;
            String endpoint=com.wodedianshi.tv.SourceSelectionPolicy.endpointKey(url);
            entries.putIfAbsent(endpoint,new ManualProbeQueueEntity(owner,endpoint,url,
                row.optString("name"),row.optString("group"),row.optString("tvgId"),row.optString("tvgName"),ordinal,false));
        }
        if(!entries.isEmpty())db.manualProbeQueueDao().insertNew(new ArrayList<>(entries.values()));
    }

    private static void seedQueueInTransaction(AppDatabase db,String owner,JSONArray rows,BooleanSupplier paused){
        requireManualOwner(db,owner);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row==null)throw new IllegalStateException("Invalid queue seed");
            enqueueRow(db,owner,row,i,paused);
        }
        db.appStateDao().put(new AppStateEntity(MANUAL_QUEUE_KEY,owner,System.currentTimeMillis()));
    }

    /** Upgrade an in-flight pre-queue round without trusting old capped coverage.
     * Keep directory cursor and staging; conservatively recheck its saved endpoints. */
    public static void ensureManualQueue(Context context,String owner,JSONArray rows,BooleanSupplier paused){
        AppDatabase db=RoomDatabaseProvider.get(context);
        if(owner.equals(db.appStateDao().getValue(MANUAL_QUEUE_KEY)))return;
        db.runInTransaction(()->{
            checkPause(paused);
            requireManualOwner(db,owner);
            if(owner.equals(db.appStateDao().getValue(MANUAL_QUEUE_KEY)))return;
            db.manualProbeQueueDao().clearOwner(owner);
            seedQueueInTransaction(db,owner,rows,paused);
            db.appStateDao().remove(MANUAL_READY_KEY);
            db.appStateDao().remove("source_manual_saved_scanned");
            ArrayList<String> legacy=new ArrayList<>();
            for(int i=0;i<64;i++)legacy.add("source_manual_covered_v1_"+i);
            db.appStateDao().removeAll(legacy);
            checkPause(paused);
        });
    }

    /** Append/update only explicitly touched rows, in the same transaction as
     * the cursor. The sole discovery owner guarantees unchanged row positions.
     * Reject stale counts and missing appended rows rather than silently losing data. */
    public static int updateTouched(Context context, JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress)
            throws JSONException {
        return updateTouchedInternal(context,snapshot,previousSize,touched,paused,progress,null,null,false,0,0,null);
    }

    public static boolean updateManualTouched(Context context,JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress,String owner,
            List<String> processed,boolean directoryExhausted,int nextFeed,int feedCount,int failedFeeds)throws JSONException {
        if(owner==null||owner.isEmpty()||processed==null)throw new IllegalArgumentException("Missing manual queue owner/progress");
        return updateManualTouchedWithQuality(context,snapshot,previousSize,touched,paused,progress,owner,
                processed,directoryExhausted,nextFeed,feedCount,failedFeeds,null);
    }

    public static boolean updateManualTouchedWithQuality(Context context,JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress,String owner,
            List<String> processed,boolean directoryExhausted,int nextFeed,int feedCount,int failedFeeds,
            List<ManualProbeQualityEntity> quality)throws JSONException {
        if(owner==null||owner.isEmpty()||processed==null)throw new IllegalArgumentException("Missing manual queue owner/progress");
        boolean[] ready={false};
        updateTouchedInternal(context,snapshot,previousSize,touched,paused,progress,owner,
                processed,directoryExhausted,nextFeed,feedCount,new ManualCommit(ready,failedFeeds,quality));
        return ready[0];
    }

    private static final class ManualCommit {
        final boolean[] ready;final int failedFeeds;final List<ManualProbeQualityEntity> quality;
        ManualCommit(boolean[] ready,int failedFeeds,List<ManualProbeQualityEntity> quality){
            this.ready=ready;this.failedFeeds=failedFeeds;this.quality=quality;
        }
    }

    private static int updateTouchedInternal(Context context,JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress,String manualOwner,
            List<String> processed,boolean directoryExhausted,int nextFeed,int feedCount,ManualCommit manualCommit)throws JSONException {
        checkPause(paused);
        if(snapshot==null||progress==null)throw new IllegalArgumentException("Invalid incremental build");
        validateTouchedRows(previousSize,snapshot.length(),touched);
        ArrayList<CatalogueBuildRowEntity> changed=new ArrayList<>();
        for(int i:touched){
            checkPause(paused);
            if(i<0||i>=snapshot.length())throw new IllegalArgumentException("Invalid touched row");
            JSONObject record=snapshot.getJSONObject(i);
            if(record.optString("name","").trim().isEmpty()||record.optString("url","").trim().isEmpty())
                throw new JSONException("Invalid incremental build row "+i);
            CatalogueBuildRowEntity entity=new CatalogueBuildRowEntity(
                String.format(java.util.Locale.ROOT,"row-%08d",i),i,record.toString());
            // Keep the initial staging index current in the same transaction
            // as touched rows and progress; never index an uncommitted candidate.
            entity.mergeIdentity=mergeIdentity(record);
            changed.add(entity);
        }
        AppDatabase db=RoomDatabaseProvider.get(context);
        ChannelDao dao=db.channelDao();
        db.runInTransaction(()->{
            checkPause(paused);
            if(manualOwner!=null)requireManualOwner(db,manualOwner);
            else if(db.appStateDao().getValue(MANUAL_OWNER_KEY)!=null)
                throw new IllegalStateException("Manual staging must not be changed by initial discovery");
            if(dao.countCatalogueBuildRows()!=previousSize)
                throw new IllegalStateException("Staging count changed");
            for(int i=0;i<changed.size();i+=64){
                checkPause(paused);
                dao.insertCatalogueBuildRows(changed.subList(i,Math.min(i+64,changed.size())));
            }
            if(dao.countCatalogueBuildRows()!=snapshot.length())
                throw new IllegalStateException("Incremental staging count mismatch");
            if(manualOwner!=null){
                requireMatchingOwner(manualOwner,db.appStateDao().getValue(MANUAL_QUEUE_KEY));
                // Add endpoints before certifying completion. Repeated insert
                // preserves already processed state via INSERT IGNORE.
                for(int index:touched)enqueueRow(db,manualOwner,snapshot.optJSONObject(index),index,paused);
                commitManualProbeProgress(db,manualOwner,processed,directoryExhausted,nextFeed,feedCount,
                    manualCommit,paused,progress);
            }
            progress.applyInTransaction(db);
            checkPause(paused);
        });
        return snapshot.length();
    }

    private static void commitManualProbeProgress(AppDatabase db,String owner,List<String> processed,
            boolean directoryExhausted,int nextFeed,int feedCount,ManualCommit manualCommit,
            BooleanSupplier paused,AppStateEditor progress){
        requireManualOwner(db,owner);
        requireMatchingOwner(owner,db.appStateDao().getValue(MANUAL_QUEUE_KEY));
        if(manualCommit.quality!=null){
            requireMatchingOwner(owner,db.appStateDao().getValue(ManualProbeQualityStore.READY_KEY));
            ManualProbeQualityStore.writeInTransaction(db,owner,manualCommit.quality,paused);
        }
        ManualProbeQueueDao queue=db.manualProbeQueueDao();
        if(!processed.isEmpty()&&queue.markProcessed(owner,processed)!=processed.size())
            throw new IllegalStateException("Manual queue checkpoint mismatch");
        int total=queue.count(owner),pending=queue.countPending(owner);
        boolean ready=directoryExhausted&&nextFeed>=feedCount&&pending==0;
        manualCommit.ready[0]=ready;
        progress.putInt("source_manual_saved_scanned",total-pending)
            .putString("source_update_result","手动完整目录轮检：已处理 "+nextFeed+"/"+feedCount
                +" 个来源，暂不可用 "+manualCommit.failedFeeds+" 个；线路检测机会 "
                +(total-pending)+"/"+total+"；"+(ready?"检测完成，等待整体发布；":"已保存暂存断点；")
                +"超时未确认不等于有效，媒体预检不等于实播。");
        if(ready)progress.putBoolean(MANUAL_READY_KEY,true);
        else progress.remove(MANUAL_READY_KEY);
    }

    public static void validateTouchedRows(int previousSize,int nextSize,java.util.Set<Integer> touched) {
        if(touched==null||previousSize<0||nextSize<previousSize)
            throw new IllegalArgumentException("Invalid incremental build");
        for(Integer row:touched)
            if(row==null||row<0||row>=nextSize)throw new IllegalArgumentException("Invalid touched row");
        for(int i=previousSize;i<nextSize;i++)
            if(!touched.contains(i))throw new IllegalArgumentException("Missing appended row");
    }

    private static void checkPause(BooleanSupplier paused) {
        if (paused != null) SourceProbeTimePolicy.checkPublicationPaused(paused);
    }

    public static void clear(Context context) {
        clear(context,null,null);
    }

    public static void clear(Context context, AppStateEditor progress, BooleanSupplier paused) {
        AppDatabase db=RoomDatabaseProvider.get(context);
        db.runInTransaction(() -> {
            checkPause(paused);
            if(db.appStateDao().getValue(MANUAL_OWNER_KEY)!=null)
                throw new IllegalStateException("Manual staging must not be cleared by initial discovery");
            db.channelDao().clearCatalogueBuildRows();
            if(progress!=null)progress.applyInTransaction(db);
            db.appStateDao().remove(STAGING_REVISION_KEY);
            checkPause(paused);
        });
    }
}
