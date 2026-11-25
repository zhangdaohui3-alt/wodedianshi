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
        db.channelDao().clearCatalogueBuildRows();
        db.appStateDao().remove(MANUAL_OWNER_KEY);
        db.appStateDao().remove(MANUAL_BASE_VERSION_KEY);
    }

    /** Seed an isolated manual build only from a still-current published snapshot.
     * No active initial-build rows may be taken over. Caller is sole source worker. */
    public static int seedManual(Context context,JSONArray snapshot,long baseVersion,String owner,
            BooleanSupplier paused,AppStateEditor progress)throws JSONException {
        if(owner==null||owner.isEmpty()||baseVersion<0||progress==null||snapshot==null||snapshot.length()==0)
            throw new IllegalArgumentException("Invalid manual staging seed");
        return replaceInternal(context,snapshot,paused,progress,owner,baseVersion);
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
        java.util.HashMap<String, String> existingJson = new java.util.HashMap<>();
        for (CatalogueBuildRowEntity row : dao.getCatalogueBuildRows()) {
            checkPause(paused);
            existingJson.put(row.rowId, row.recordJson);
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
            if (!recordJson.equals(existingJson.get(rowId))) {
                changedRows.add(new CatalogueBuildRowEntity(rowId, i, recordJson));
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
            }
            checkPause(paused);
        });
        return snapshot.length();
    }

    /** Append/update only explicitly touched rows, in the same transaction as
     * the cursor. The sole discovery owner guarantees unchanged row positions.
     * Reject stale counts and missing appended rows rather than silently losing data. */
    public static int updateTouched(Context context, JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress)
            throws JSONException {
        return updateTouchedInternal(context,snapshot,previousSize,touched,paused,progress,null);
    }

    public static int updateManualTouched(Context context,JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress,String owner)throws JSONException {
        if(owner==null||owner.isEmpty())throw new IllegalArgumentException("Missing manual staging owner");
        return updateTouchedInternal(context,snapshot,previousSize,touched,paused,progress,owner);
    }

    private static int updateTouchedInternal(Context context,JSONArray snapshot,int previousSize,
            java.util.Set<Integer> touched,BooleanSupplier paused,AppStateEditor progress,String manualOwner)throws JSONException {
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
            changed.add(new CatalogueBuildRowEntity(
                String.format(java.util.Locale.ROOT,"row-%08d",i),i,record.toString()));
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
            progress.applyInTransaction(db);
            checkPause(paused);
        });
        return snapshot.length();
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
