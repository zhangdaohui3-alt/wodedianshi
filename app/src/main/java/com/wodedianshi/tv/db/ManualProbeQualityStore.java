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
    private static final String PUBLISHED_READY_KEY="source_indexed_quality_published_v1";

    public static boolean hasPublished(Context context){
        return "true".equals(RoomDatabaseProvider.get(context).appStateDao().getValue(PUBLISHED_READY_KEY));
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
