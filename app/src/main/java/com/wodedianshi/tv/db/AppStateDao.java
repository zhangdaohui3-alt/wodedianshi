package com.wodedianshi.tv.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface AppStateDao {
    @Query("SELECT stateValue FROM app_state WHERE key = :key LIMIT 1")
    String getValue(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(AppStateEntity value);

    @Query("DELETE FROM app_state WHERE key = :key")
    void remove(String key);
}
