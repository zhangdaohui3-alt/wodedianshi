package com.wodedianshi.tv.db;

import android.content.Context;
import com.wodedianshi.tv.SourceProbeTimePolicy;
import com.wodedianshi.tv.StableKey;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.json.JSONException;

/** Private-round quality rows; no per-slice full score JSON rewrite. */
public final class ManualProbeQualityStore {
    private ManualProbeQualityStore(){}
    static final String READY_KEY="source_manual_quality_owner_v1";
    public static final String PUBLISHED_OWNER="@published";
    public static final String INITIAL_OWNER="@initial";
    static final String INITIAL_READY_KEY="source_initial_indexed_quality_ready_v1";
    private static final String PUBLISHED_READY_KEY="source_indexed_quality_published_v1";

    public static boolean hasPublished(Context context){
        return "true".equals(RoomDatabaseProvider.get(context).appStateDao().getValue(PUBLISHED_READY_KEY));
    }

    /** Read only scores used by the current catalogue, never the unbounded
     * historical table. Called off the UI thread. A changed checkpoint
     * invalidates the entire prepared cache rather than mixing generations. */
    public static JSONObject readPlaybackScores(Context context,Collection<String> urls,
            long expectedVersion,BooleanSupplier paused)throws JSONException{
        AppDatabase db=RoomDatabaseProvider.get(context);
        if(!hasPublished(context))return null;
        requireCatalogueVersion(db,expectedVersion);
        JSONObject scores=readScoresForUrls(db,PUBLISHED_OWNER,urls,paused);
        requireCatalogueVersion(db,expectedVersion);
        return scores;
    }

    public static JSONObject readManualRankingScores(Context context,String owner,Collection<String> urls,
            BooleanSupplier paused)throws JSONException{
        return readManualRankingScores(RoomDatabaseProvider.get(context),owner,urls,paused);
    }

    static JSONObject readManualRankingScores(AppDatabase db,String owner,Collection<String> urls,
            BooleanSupplier paused)throws JSONException{
        requireManualReadyOwner(db,owner);
        JSONObject scores=readScoresForUrls(db,owner,urls,paused);
        requireManualReadyOwner(db,owner);
        return scores;
    }

    private static void requireManualReadyOwner(AppDatabase db,String owner){
        RoomCatalogueBuildStore.requireMatchingOwner(owner,db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY));
        RoomCatalogueBuildStore.requireMatchingOwner(owner,db.appStateDao().getValue(READY_KEY));
        if(!"true".equals(db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_READY_KEY)))
            throw new IllegalStateException("Manual quality not ready for final ranking");
    }

    private static JSONObject readScoresForUrls(AppDatabase db,String owner,Collection<String> urls,
            BooleanSupplier paused)throws JSONException{
        JSONObject scores=new JSONObject();
        boolean inherit=inheritsPublished(db,owner);
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(String url:urls){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(url==null||url.isEmpty())continue;
            keys.add(StableKey.url(url));
            if(keys.size()>=256){
                readScorePage(db,owner,keys,scores,inherit,paused);
                keys.clear();
            }
        }
        if(!keys.isEmpty())readScorePage(db,owner,keys,scores,inherit,paused);
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        return scores;
    }
    private static boolean inheritsPublished(AppDatabase db,String owner){
        if(PUBLISHED_OWNER.equals(owner))return false;
        AppStateDao state=db.appStateDao();
        return "true".equals(state.getValue(PUBLISHED_READY_KEY))
            &&(INITIAL_OWNER.equals(owner)||owner.equals(state.getValue(READY_KEY))
                &&owner.equals(state.getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY)));
    }
    private static void readScorePage(AppDatabase db,String owner,Set<String> keys,JSONObject scores,
            boolean inherit,BooleanSupplier paused)throws JSONException{
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        List<String> page=new ArrayList<>(keys);
        ManualProbeQualityDao dao=db.manualProbeQualityDao();
        for(ManualProbeQualityEntity row:inherit?dao.loadInherited(owner,PUBLISHED_OWNER,page):dao.load(owner,page))
            if(row.score!=null)scores.put(row.sourceKey,row.score);
    }
    private static void requireCatalogueVersion(AppDatabase db,long expected){
        CatalogueMigrationEntity checkpoint=db.channelDao().getMigration(MasterCatalogueRepository.MIGRATION_KEY);
        if(checkpoint==null||checkpoint.completedAt!=expected)
            throw new RoomCatalogueReader.StaleSnapshotException();
    }

    public static void ensure(Context context,String owner,BooleanSupplier paused)throws JSONException{
        ensure(RoomDatabaseProvider.get(context),owner,paused);
    }

    static void ensure(AppDatabase db,String owner,BooleanSupplier paused)throws JSONException{
        if(owner.equals(db.appStateDao().getValue(READY_KEY)))return;
        db.runInTransaction((java.util.concurrent.Callable<Void>)()->{
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            RoomCatalogueBuildStore.requireManualOwner(db,owner);
            if(owner.equals(db.appStateDao().getValue(READY_KEY)))return null;
            db.manualProbeQualityDao().clearOwner(owner);
            // A new round stores only its observations. The published base
            // remains authoritative for keys without a private row.
            db.appStateDao().put(new AppStateEntity(READY_KEY,owner,System.currentTimeMillis()));
            db.appStateDao().remove(RoomCatalogueBuildStore.MANUAL_SCORES_KEY);
            db.appStateDao().remove(RoomCatalogueBuildStore.MANUAL_STREAKS_KEY);
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            return null;
        });
    }

    public static void loadUrls(Context context,String owner,Collection<String> urls,Set<String> loaded,
            JSONObject scores,JSONObject streaks,BooleanSupplier paused)throws JSONException{
        loadUrls(RoomDatabaseProvider.get(context),owner,urls,loaded,scores,streaks,paused);
    }

    static void loadUrls(AppDatabase db,String owner,Collection<String> urls,Set<String> loaded,
            JSONObject scores,JSONObject streaks,BooleanSupplier paused)throws JSONException{
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(String url:urls){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(url==null||url.isEmpty())continue;
            String key=StableKey.url(url);
            if(!loaded.contains(key))keys.add(key);
        }
        List<String> list=new ArrayList<>(keys);
        ManualProbeQualityDao dao=db.manualProbeQualityDao();
        boolean inherit=inheritsPublished(db,owner);
        for(int i=0;i<list.size();i+=256){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            List<String> page=list.subList(i,Math.min(i+256,list.size()));
            // Both IN lists together use at most 512 key parameters; stay
            // below Android 9 SQLite's 999-variable bound. One statement
            // observes a consistent view of private and published evidence.
            List<ManualProbeQualityEntity> rows=inherit
                ?dao.loadInherited(owner,PUBLISHED_OWNER,page):dao.load(owner,page);
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            for(ManualProbeQualityEntity row:rows){
                if(row.score!=null)scores.put(row.sourceKey,row.score);
                if(row.failureStreak!=null)streaks.put(row.sourceKey,row.failureStreak);
            }
            loaded.addAll(page);
        }
    }

    public static List<ManualProbeQualityEntity> changes(String owner,Collection<String> keys,
            JSONObject scores,JSONObject streaks){
        List<ManualProbeQualityEntity> result=new ArrayList<>();
        for(String key:keys)result.add(new ManualProbeQualityEntity(owner,key,
            scores.has(key)?Integer.valueOf(scores.optInt(key,0)):null,
            streaks.has(key)?Integer.valueOf(streaks.optInt(key,0)):null));
        return result;
    }

    static void publishInTransaction(AppDatabase db,String owner,BooleanSupplier paused){
        RoomCatalogueBuildStore.requireManualOwner(db,owner);
        RoomCatalogueBuildStore.requireMatchingOwner(owner,db.appStateDao().getValue(READY_KEY));
        if(PUBLISHED_OWNER.equals(owner))throw new IllegalArgumentException("Reserved quality owner");
        // Merge only observed keys. Inherited evidence remains in place.
        copyQualityInTransaction(db,owner,PUBLISHED_OWNER,paused);
        // A published row with neither value has no fallback to suppress.
        // Do not copy these empty records into every later manual round.
        db.manualProbeQualityDao().removeEmptyRows(PUBLISHED_OWNER);
        db.appStateDao().put(new AppStateEntity(PUBLISHED_READY_KEY,"true",System.currentTimeMillis()));
    }

    public static boolean hasInitial(Context context){
        return hasInitial(RoomDatabaseProvider.get(context));
    }

    static boolean hasInitial(AppDatabase db){
        return "true".equals(db.appStateDao().getValue(INITIAL_READY_KEY));
    }

    /** Start with empty private observations; inherit published Room evidence on demand. */
    public static void ensureInitial(Context context,BooleanSupplier paused){
        ensureInitial(RoomDatabaseProvider.get(context),paused);
    }

    static void ensureInitial(AppDatabase db,BooleanSupplier paused){
        if(hasInitial(db))return;
        db.runInTransaction(()->seedInitialInTransaction(db,Collections.emptyList(),paused));
    }

    static void seedInitialInTransaction(AppDatabase db,List<ManualProbeQualityEntity> rows,BooleanSupplier paused){
        requireInitialTransaction(db);
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        if("true".equals(db.appStateDao().getValue(INITIAL_READY_KEY)))return;
        db.manualProbeQualityDao().clearOwner(INITIAL_OWNER);
        db.appStateDao().put(new AppStateEntity(INITIAL_READY_KEY,"true",System.currentTimeMillis()));
        // Missing private keys inherit published Room values lazily.
        if(!"true".equals(db.appStateDao().getValue(PUBLISHED_READY_KEY)))
            writeInitialInTransaction(db,rows,paused);
        SourceProbeTimePolicy.checkPublicationPaused(paused);
    }

    /** Preview is itself a checked published snapshot. Only evidence for its
     * retained exact URLs can cross from the private build into playback.
     * Keep the private owner and every cursor intact for subsequent slices. */
    static void publishInitialPreviewInTransaction(AppDatabase db,Collection<String> urls,BooleanSupplier paused){
        requireInitialTransaction(db);
        if(!hasInitial(db))throw new IllegalStateException("Initial quality not initialized");
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(String url:urls){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(url==null||url.isEmpty())continue;
            keys.add(StableKey.url(url));
            if(keys.size()>=256){
                publishPreviewPage(db,keys,paused);keys.clear();
            }
        }
        if(!keys.isEmpty())publishPreviewPage(db,keys,paused);
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        db.appStateDao().put(new AppStateEntity(PUBLISHED_READY_KEY,"true",System.currentTimeMillis()));
    }

    private static void publishPreviewPage(AppDatabase db,Set<String> keys,BooleanSupplier paused){
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        // Copy only exact preview keys; missing private rows leave inherited
        // published evidence untouched. Nullable private rows still replace.
        List<String> page=new ArrayList<>(keys);
        ManualProbeQualityDao dao=db.manualProbeQualityDao();
        dao.copyKeys(INITIAL_OWNER,PUBLISHED_OWNER,page);
        // Published state has no inherited base. A cleared observation is
        // represented by absence, while its private tombstone remains intact.
        dao.removeEmptyKeys(PUBLISHED_OWNER,page);
    }

    /** Merge all remaining private evidence at final publication.
     * Unobserved published keys survive, and explicit nulls remain tombstones.
     * Caller must own the checked full catalogue transaction. */
    static void publishInitialInTransaction(AppDatabase db,BooleanSupplier paused){
        requireInitialTransaction(db);
        if(!"true".equals(db.appStateDao().getValue(INITIAL_READY_KEY)))
            throw new IllegalStateException("Initial quality not initialized");
        copyQualityInTransaction(db,INITIAL_OWNER,PUBLISHED_OWNER,paused);
        db.appStateDao().put(new AppStateEntity(PUBLISHED_READY_KEY,"true",System.currentTimeMillis()));
        clearInitialInTransaction(db);
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        db.manualProbeQualityDao().removeEmptyRows(PUBLISHED_OWNER);
        SourceProbeTimePolicy.checkPublicationPaused(paused);
    }

    /** Keep cancellation between bounded key ranges, but let SQLite copy
     * values directly instead of materializing two Java entity lists per page.
     * Existing callers own the transaction and validate build ownership. */
    static void copyQualityInTransaction(AppDatabase db,String source,String target,BooleanSupplier paused){
        if(!db.inTransaction()||source.equals(target))
            throw new IllegalStateException("Quality copy requires distinct owners in transaction");
        ManualProbeQualityDao dao=db.manualProbeQualityDao();
        String after="";
        for(;;){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            List<String> page=dao.pageKeys(source,after,256);
            if(page.isEmpty())break;
            String through=page.get(page.size()-1);
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            dao.copyRange(source,target,after,through);
            after=through;
        }
        SourceProbeTimePolicy.checkPublicationPaused(paused);
    }

    /** Private first-build scores share the existing table. They become
     * visible to published readers only with the final catalogue transaction. */
    static void writeInitialInTransaction(AppDatabase db,List<ManualProbeQualityEntity> rows,
            BooleanSupplier paused){
        requireInitialTransaction(db);
        if(!"true".equals(db.appStateDao().getValue(INITIAL_READY_KEY)))
            throw new IllegalStateException("Initial quality not initialized");
        for(ManualProbeQualityEntity row:rows)
            if(!INITIAL_OWNER.equals(row.owner))throw new IllegalArgumentException("Wrong initial quality owner");
        for(int i=0;i<rows.size();i+=256){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            db.manualProbeQualityDao().put(rows.subList(i,Math.min(i+256,rows.size())));
        }
    }

    private static void requireInitialTransaction(AppDatabase db){
        if(!db.inTransaction()||db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_OWNER_KEY)!=null)
            throw new IllegalStateException("Initial quality requires independent build transaction");
    }

    static void clearInitialInTransaction(AppDatabase db){
        requireInitialTransaction(db);
        db.manualProbeQualityDao().clearOwner(INITIAL_OWNER);
        db.appStateDao().remove(INITIAL_READY_KEY);
    }

    static void writePublishedInTransaction(AppDatabase db,List<ManualProbeQualityEntity> rows,
            BooleanSupplier paused){
        if(!db.inTransaction()||!"true".equals(db.appStateDao().getValue(PUBLISHED_READY_KEY)))
            throw new IllegalStateException("Published quality unavailable");
        for(ManualProbeQualityEntity row:rows)
            if(!PUBLISHED_OWNER.equals(row.owner))throw new IllegalArgumentException("Wrong published quality owner");
        for(int i=0;i<rows.size();i+=256){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            List<ManualProbeQualityEntity> retained=new ArrayList<>();
            List<String> cleared=new ArrayList<>();
            for(ManualProbeQualityEntity row:rows.subList(i,Math.min(i+256,rows.size()))){
                if(row.score==null&&row.failureStreak==null)cleared.add(row.sourceKey);
                else retained.add(row);
            }
            ManualProbeQualityDao dao=db.manualProbeQualityDao();
            if(!retained.isEmpty())dao.put(retained);
            if(!cleared.isEmpty())dao.removeKeys(PUBLISHED_OWNER,cleared);
        }
    }

    static void writeInTransaction(AppDatabase db,String owner,List<ManualProbeQualityEntity> rows,
            BooleanSupplier paused){
        RoomCatalogueBuildStore.requireManualOwner(db,owner);
        for(ManualProbeQualityEntity row:rows)
            if(!owner.equals(row.owner))throw new IllegalArgumentException("Wrong quality owner");
        for(int i=0;i<rows.size();i+=256){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            db.manualProbeQualityDao().put(rows.subList(i,Math.min(i+256,rows.size())));
        }
    }
}
