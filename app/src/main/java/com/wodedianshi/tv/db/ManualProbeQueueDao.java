package com.wodedianshi.tv.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ManualProbeQueueDao {
    // Repeated discovery of the same endpoint must not reset a processed row.
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    void insertNew(List<ManualProbeQueueEntity> rows);

    @Query("SELECT * FROM manual_probe_queue WHERE owner=:owner AND processed=0 ORDER BY ordinal,endpoint LIMIT :limit")
    List<ManualProbeQueueEntity> pending(String owner,int limit);

    @Query("SELECT COUNT(*) FROM manual_probe_queue WHERE owner=:owner")
    int count(String owner);

    @Query("SELECT COUNT(*) FROM manual_probe_queue WHERE owner=:owner AND processed=0")
    int countPending(String owner);

    @Query("UPDATE manual_probe_queue SET processed=1 WHERE owner=:owner AND endpoint IN (:endpoints) AND processed=0")
    int markProcessed(String owner,List<String> endpoints);

    @Query("DELETE FROM manual_probe_queue WHERE owner=:owner")
    void clearOwner(String owner);
}
