package com.wodedianshi.tv.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface ManualProbeQualityDao {
    @Query("SELECT * FROM manual_probe_quality WHERE owner=:owner AND sourceKey IN (:keys)")
    List<ManualProbeQualityEntity> load(String owner,List<String> keys);
    // Select the private row even when both values are null. Only a missing
    // private row inherits the corresponding published row.
    @Query("SELECT * FROM manual_probe_quality WHERE owner=:owner AND sourceKey IN (:keys) "
        + "UNION ALL SELECT p.* FROM manual_probe_quality p "
        + "WHERE p.owner=:published AND p.sourceKey IN (:keys) "
        + "AND NOT EXISTS (SELECT 1 FROM manual_probe_quality r "
        + "WHERE r.owner=:owner AND r.sourceKey=p.sourceKey)")
    List<ManualProbeQualityEntity> loadInherited(String owner,String published,List<String> keys);
    @Query("SELECT * FROM manual_probe_quality WHERE owner=:owner AND sourceKey=:key LIMIT 1")
    ManualProbeQualityEntity find(String owner,String key);
    @Query("SELECT * FROM manual_probe_quality WHERE owner=:owner AND sourceKey>:after ORDER BY sourceKey LIMIT :limit")
    List<ManualProbeQualityEntity> page(String owner,String after,int limit);
    @Query("SELECT sourceKey FROM manual_probe_quality WHERE owner=:owner AND sourceKey>:after "
        + "AND observedAt>0 AND observedAt<=:cutoff ORDER BY sourceKey LIMIT :limit")
    List<String> expiredKeys(String owner,String after,long cutoff,int limit);
    @Query("SELECT sourceKey FROM manual_probe_quality WHERE owner=:owner AND sourceKey>:after ORDER BY sourceKey LIMIT :limit")
    List<String> pageKeys(String owner,String after,int limit);
    @Query("INSERT OR REPLACE INTO manual_probe_quality (owner,sourceKey,score,failureStreak,observedAt) "
        + "SELECT :target,sourceKey,score,failureStreak,observedAt FROM manual_probe_quality "
        + "WHERE owner=:source AND sourceKey>:after AND sourceKey<=:through")
    void copyRange(String source,String target,String after,String through);
    @Query("INSERT OR REPLACE INTO manual_probe_quality (owner,sourceKey,score,failureStreak,observedAt) "
        + "SELECT :target,sourceKey,score,failureStreak,observedAt FROM manual_probe_quality "
        + "WHERE owner=:source AND sourceKey IN (:keys)")
    void copyKeys(String source,String target,List<String> keys);
    @Query("DELETE FROM manual_probe_quality WHERE owner=:owner AND sourceKey IN (:keys)")
    void removeKeys(String owner,List<String> keys);
    @Query("DELETE FROM manual_probe_quality WHERE owner=:owner AND sourceKey IN (:keys) "
        + "AND score IS NULL AND failureStreak IS NULL")
    void removeEmptyKeys(String owner,List<String> keys);
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    void put(List<ManualProbeQualityEntity> rows);
    @Query("DELETE FROM manual_probe_quality WHERE owner=:owner")
    void clearOwner(String owner);
    @Query("DELETE FROM manual_probe_quality WHERE owner=:owner AND score IS NULL AND failureStreak IS NULL")
    int removeEmptyRows(String owner);
    @Query("UPDATE manual_probe_quality SET owner=:published WHERE owner=:owner")
    void publish(String owner,String published);
}
