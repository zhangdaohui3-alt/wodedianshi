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
    @Query("SELECT * FROM manual_probe_quality WHERE owner=:owner AND sourceKey=:key LIMIT 1")
    ManualProbeQualityEntity find(String owner,String key);
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    void put(List<ManualProbeQualityEntity> rows);
    @Query("DELETE FROM manual_probe_quality WHERE owner=:owner")
    void clearOwner(String owner);
    @Query("UPDATE manual_probe_quality SET owner=:published WHERE owner=:owner")
    void publish(String owner,String published);
}
