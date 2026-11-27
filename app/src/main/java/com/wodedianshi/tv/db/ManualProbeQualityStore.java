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
        JSONObject scores=new JSONObject();
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(String url:urls){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(url==null||url.isEmpty())continue;
            keys.add(StableKey.url(url));keys.add(StableKey.legacyUrl(url));
            if(keys.size()>=256){
                readScorePage(db,keys,scores,paused);
                keys.clear();
            }
        }
        if(!keys.isEmpty())readScorePage(db,keys,scores,paused);
        requireCatalogueVersion(db,expectedVersion);
        return scores;
    }
    private static void readScorePage(AppDatabase db,Set<String> keys,JSONObject scores,
            BooleanSupplier paused)throws JSONException{
        SourceProbeTimePolicy.checkPublicationPaused(paused);
        for(ManualProbeQualityEntity row:db.manualProbeQualityDao().load(PUBLISHED_OWNER,new ArrayList<>(keys)))
            if(row.score!=null)scores.put(row.sourceKey,row.score);
    }
    private static void requireCatalogueVersion(AppDatabase db,long expected){
        CatalogueMigrationEntity checkpoint=db.channelDao().getMigration(MasterCatalogueRepository.MIGRATION_KEY);
        if(checkpoint==null||checkpoint.completedAt!=expected)
            throw new RoomCatalogueReader.StaleSnapshotException();
    }

    public static void ensure(Context context,String owner,BooleanSupplier paused)throws JSONException{
        AppDatabase db=RoomDatabaseProvider.get(context);
        if(owner.equals(db.appStateDao().getValue(READY_KEY)))return;
        String a=db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_SCORES_KEY);
        String b=db.appStateDao().getValue(RoomCatalogueBuildStore.MANUAL_STREAKS_KEY);
        JSONObject scores=new JSONObject(a==null?"{}":a),streaks=new JSONObject(b==null?"{}":b);
        Set<String> keys=new HashSet<>();
        Iterator<String> it=scores.keys();while(it.hasNext())keys.add(it.next());
        it=streaks.keys();while(it.hasNext())keys.add(it.next());
        List<ManualProbeQualityEntity> rows=changes(owner,keys,scores,streaks);
        db.runInTransaction(()->{
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            RoomCatalogueBuildStore.requireManualOwner(db,owner);
            if(owner.equals(db.appStateDao().getValue(READY_KEY)))return;
            db.manualProbeQualityDao().clearOwner(owner);
            if("true".equals(db.appStateDao().getValue(PUBLISHED_READY_KEY))){
                // The compatibility JSON is not authoritative once full indexed
                // quality has been published. Copy bounded pages in the same
                // transaction, so a crash cannot seed a partial new round.
                String after="";
                for(;;){
                    SourceProbeTimePolicy.checkPublicationPaused(paused);
                    List<ManualProbeQualityEntity> page=db.manualProbeQualityDao().page(PUBLISHED_OWNER,after,256);
                    if(page.isEmpty())break;
                    List<ManualProbeQualityEntity> copy=new ArrayList<>(page.size());
                    for(ManualProbeQualityEntity row:page)
                        copy.add(new ManualProbeQualityEntity(owner,row.sourceKey,row.score,row.failureStreak));
                    writeInTransaction(db,owner,copy,paused);
                    after=page.get(page.size()-1).sourceKey;
                }
            }else writeInTransaction(db,owner,rows,paused);
            db.appStateDao().put(new AppStateEntity(READY_KEY,owner,System.currentTimeMillis()));
            db.appStateDao().remove(RoomCatalogueBuildStore.MANUAL_SCORES_KEY);
            db.appStateDao().remove(RoomCatalogueBuildStore.MANUAL_STREAKS_KEY);
            SourceProbeTimePolicy.checkPublicationPaused(paused);
        });
    }

    public static void loadUrls(Context context,String owner,Collection<String> urls,Set<String> loaded,
            JSONObject scores,JSONObject streaks,BooleanSupplier paused)throws JSONException{
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(String url:urls){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            if(url==null||url.isEmpty())continue;
            for(String key:new String[]{StableKey.url(url),StableKey.legacyUrl(url)})
                if(!loaded.contains(key))keys.add(key);
        }
        List<String> list=new ArrayList<>(keys);
        ManualProbeQualityDao dao=RoomDatabaseProvider.get(context).manualProbeQualityDao();
        for(int i=0;i<list.size();i+=256){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            List<String> page=list.subList(i,Math.min(i+256,list.size()));
            for(ManualProbeQualityEntity row:dao.load(owner,page)){
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

    public static void readFinal(Context context,String owner,JSONObject scores,JSONObject streaks,
            BooleanSupplier paused)throws JSONException{
        ManualProbeQualityDao dao=RoomDatabaseProvider.get(context).manualProbeQualityDao();
        String after="";
        for(;;){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            List<ManualProbeQualityEntity> page=dao.page(owner,after,256);
            if(page.isEmpty())break;
            for(ManualProbeQualityEntity row:page){
                if(row.score!=null)scores.put(row.sourceKey,row.score);
                if(row.failureStreak!=null)streaks.put(row.sourceKey,row.failureStreak);
            }
            after=page.get(page.size()-1).sourceKey;
        }
    }
    static void publishInTransaction(AppDatabase db,String owner){
        RoomCatalogueBuildStore.requireManualOwner(db,owner);
        RoomCatalogueBuildStore.requireMatchingOwner(owner,db.appStateDao().getValue(READY_KEY));
        if(PUBLISHED_OWNER.equals(owner))throw new IllegalArgumentException("Reserved quality owner");
        db.manualProbeQualityDao().clearOwner(PUBLISHED_OWNER);
        db.manualProbeQualityDao().publish(owner,PUBLISHED_OWNER);
        db.appStateDao().put(new AppStateEntity(PUBLISHED_READY_KEY,"true",System.currentTimeMillis()));
    }

    /** Private first-build scores share the existing table. They become
     * visible to published readers only with the final catalogue transaction. */
    static void writeInitialInTransaction(AppDatabase db,List<ManualProbeQualityEntity> rows,
            BooleanSupplier paused){
        requireInitialTransaction(db);
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
    }

    static void writePublishedInTransaction(AppDatabase db,List<ManualProbeQualityEntity> rows,
            BooleanSupplier paused){
        if(!db.inTransaction()||!"true".equals(db.appStateDao().getValue(PUBLISHED_READY_KEY)))
            throw new IllegalStateException("Published quality unavailable");
        for(ManualProbeQualityEntity row:rows)
            if(!PUBLISHED_OWNER.equals(row.owner))throw new IllegalArgumentException("Wrong published quality owner");
        for(int i=0;i<rows.size();i+=256){
            SourceProbeTimePolicy.checkPublicationPaused(paused);
            db.manualProbeQualityDao().put(rows.subList(i,Math.min(i+256,rows.size())));
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
