package com.wodedianshi.tv.db;

import com.wodedianshi.tv.SourceProbeTimePolicy;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

/** Exercise the production reconciliation method with a recording DAO.
 * This verifies the write plan, not SQLite/Room transaction rollback itself. */
public class RoomCatalogueBatchReconciliationTest {
    private static final class RecordingDao {
        final List<BackupSourceEntity> oldRows=new ArrayList<>();
        final List<ChannelDao.ActiveSelection> liveSelections=new ArrayList<>();
        final Map<String,Integer> selectedIndices=new HashMap<>();
        final List<List<String>> queries=new ArrayList<>();
        final List<String> updates=new ArrayList<>(), inserts=new ArrayList<>(), deletes=new ArrayList<>();
        final List<BackupSourceEntity> sourceWrites=new ArrayList<>();
        boolean pauseAfterQuery;
        ChannelDao dao=(ChannelDao)Proxy.newProxyInstance(ChannelDao.class.getClassLoader(),
            new Class<?>[]{ChannelDao.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "getBackupSourcesForChannels":
                        List<String> ids=new ArrayList<>((List<String>)args[0]);
                        queries.add(ids);
                        List<BackupSourceEntity> found=new ArrayList<>();
                        for(BackupSourceEntity row:oldRows)
                            if(ids.contains(row.channelId))found.add(row);
                        pauseAfterQuery=true;
                        return found;
                    case "getActiveSelectionsForChannels":
                        return liveSelections;
                    case "updateChannelFields": updates.add((String)args[0]);selectedIndices.put((String)args[0],(Integer)args[9]);return null;
                    case "insertChannelIfAbsent": inserts.add(((ChannelEntity)args[0]).id);return null;
                    case "deleteBackupSource": deletes.add(args[0]+"|"+args[1]);return null;
                    case "insertBackupSources": sourceWrites.addAll((List<BackupSourceEntity>)args[0]);return null;
                    default: throw new AssertionError("Unexpected DAO method "+method.getName());
                }
            });
    }
    private static BackupSourceEntity source(String id,String url,int priority,String scopes) {
        return new BackupSourceEntity(id,url,priority,scopes);
    }
    private static RoomCatalogueWriter.PreparedChannel channel(String id,BackupSourceEntity... sources) {
        ChannelEntity row=new ChannelEntity(id,id,id,"https://example.invalid/shared","group","group",
            "","","[]",0,0);
        return new RoomCatalogueWriter.PreparedChannel(row,Arrays.asList(sources));
    }
    private static ChannelDao.ActiveSelection selection(String id,String legacy,String url){
        ChannelDao.ActiveSelection row=new ChannelDao.ActiveSelection();
        row.channelId=id;row.legacyKey=legacy;row.url=url;return row;
    }
    @Test public void manualPublishPreservesLatestExactUrlAcrossReordering(){
        RecordingDao r=new RecordingDao();
        r.liveSelections.add(selection("a","a","chosen"));
        r.liveSelections.add(selection("b","b","removed"));
        r.liveSelections.add(selection("c","different-legacy","chosen"));
        RoomCatalogueWriter.writePreparedBatch(r.dao,Arrays.asList(
            channel("a",source("a","best",0,"[]"),source("a","chosen",1,"[]")),
            channel("b",source("b","best",0,"[]")),
            channel("c",source("c","best",0,"[]"),source("c","chosen",1,"[]"))),
            new HashSet<>(Arrays.asList("a","b","c")),null,true);
        assertEquals(Integer.valueOf(1),r.selectedIndices.get("a"));
        assertEquals(Integer.valueOf(0),r.selectedIndices.get("b"));
        assertEquals(Integer.valueOf(0),r.selectedIndices.get("c"));
    }
    @Test public void restoreSelectionOnlyUsesFreeSlotOrNewNonprimaryRoute(){
        BackupSourceEntity[] rows=new BackupSourceEntity[8];
        Map<String,BackupSourceEntity> previous=new HashMap<>();
        for(int i=0;i<8;i++){rows[i]=source("a","https://example.invalid/"+i,i,"[]");previous.put(rows[i].url,rows[i]);}
        assertEquals(-1,RoomCatalogueWriter.selectionRetentionSlot(channel("a",rows),previous));
        previous.remove(rows[6].url);
        assertEquals(6,RoomCatalogueWriter.selectionRetentionSlot(channel("a",rows),previous));
        assertEquals(7,RoomCatalogueWriter.selectionRetentionSlot(channel("a",Arrays.copyOf(rows,7)),previous));
        previous.put(rows[6].url,rows[6]);previous.remove(rows[0].url);
        assertEquals(-1,RoomCatalogueWriter.selectionRetentionSlot(channel("a",rows),previous));
    }
    @Test public void batchPreservesChannelIsolationAndWritesOnlyChangedSources() {
        RecordingDao r=new RecordingDao();
        r.oldRows.add(source("a","shared",0,"[]"));
        r.oldRows.add(source("a","obsolete",1,"[]"));
        r.oldRows.add(source("b","shared",0,"[]"));
        r.oldRows.add(source("b","scope",1,"[]"));
        RoomCatalogueWriter.writePreparedBatch(r.dao,Arrays.asList(
            channel("a",source("a","shared",0,"[]"),source("a","new",1,"[]")),
            channel("b",source("b","shared",1,"[]"),source("b","scope",0,"[\"china\"]")),
            channel("c",source("c","shared",0,"[]"))),
            new HashSet<>(Arrays.asList("a","b")),null);
        assertEquals(Collections.singletonList(Arrays.asList("a","b")),r.queries);
        assertEquals(Arrays.asList("a","b"),r.updates);
        assertEquals(Collections.singletonList("c"),r.inserts);
        assertEquals(Collections.singletonList("a|obsolete"),r.deletes);
        List<String> changes=new ArrayList<>();
        for(BackupSourceEntity row:r.sourceWrites)
            changes.add(row.channelId+"|"+row.url+"|"+row.priority+"|"+row.scopesJson);
        assertEquals(Arrays.asList("a|new|1|[]","b|shared|1|[]","b|scope|0|[\"china\"]","c|shared|0|[]"),changes);
    }
    @Test public void sixtyFourExistingChannelsUseOneQueryAndNoSourceRewrites() {
        RecordingDao r=new RecordingDao();
        List<RoomCatalogueWriter.PreparedChannel> batch=new ArrayList<>();
        HashSet<String> existing=new HashSet<>();
        for(int i=0;i<64;i++){
            String id="channel-"+i;
            BackupSourceEntity old=source(id,"shared",0,"[]");
            r.oldRows.add(old);existing.add(id);batch.add(channel(id,source(id,"shared",0,"[]")));
        }
        RoomCatalogueWriter.writePreparedBatch(r.dao,batch,existing,null);
        assertEquals(1,r.queries.size());
        assertEquals(64,r.queries.get(0).size());
        assertEquals(64,r.updates.size());
        assertTrue(r.sourceWrites.isEmpty());
        assertTrue(r.deletes.isEmpty());
    }
    @Test public void pauseDuringRelationReadPreventsSubsequentWrites() {
        RecordingDao r=new RecordingDao();
        r.oldRows.add(source("a","shared",0,"[]"));
        try {
            RoomCatalogueWriter.writePreparedBatch(r.dao,Collections.singletonList(
                channel("a",source("a","new",0,"[]"))),new HashSet<>(Collections.singletonList("a")),
                ()->r.pauseAfterQuery);
            fail("Expected publication pause");
        }catch(SourceProbeTimePolicy.PublicationPausedException expected){}
        assertEquals(1,r.queries.size());
        assertTrue(r.updates.isEmpty());
        assertTrue(r.sourceWrites.isEmpty());
        assertTrue(r.deletes.isEmpty());
    }
    @Test public void manualRetentionReturnsExistingSelectionWithoutQualityLookup() {
        String active="https://CDN.example:443/live";
        String alias="https://cdn.example/live";
        RoomCatalogueWriter.PreparedChannel item=channel("a",
            source("a","https://other.example/live",0,"[]"),source("a",alias,1,"[]"));
        Map<String,BackupSourceEntity> old=new HashMap<>();
        old.put(active,source("a",active,0,"[]"));
        assertEquals(1,RoomCatalogueWriter.retainLiveSelection(item,old,active,
            url->{throw new AssertionError("Retained endpoint needs no quality lookup");}));
        assertEquals(2,item.sources.size());
        assertEquals(alias,item.sources.get(1).url);
    }

    @Test public void manualRetentionReturnsRankedFallbackWhenOldRouteIsIneligible() {
        String active="https://old.example/live";
        RoomCatalogueWriter.PreparedChannel item=channel("a",source("a","https://new.example/live",0,"[]"));
        Map<String,BackupSourceEntity> old=new HashMap<>();
        old.put(active,source("a",active,0,"[]"));
        assertEquals(0,RoomCatalogueWriter.retainLiveSelection(item,old,active,url->false));
        assertEquals(1,item.sources.size());
    }


}
