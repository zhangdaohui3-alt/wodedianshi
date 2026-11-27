package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Small durable application state that is business data, not a UI preference. */
@Entity(tableName = "app_state")
public class AppStateEntity {
    @PrimaryKey @NonNull public String key;
    @NonNull @ColumnInfo(name = "stateValue") public String stateValue;
    public long updatedAt;

    public AppStateEntity(@NonNull String key, @NonNull String stateValue, long updatedAt) {
        this.key = key;
        this.stateValue = stateValue;
        this.updatedAt = updatedAt;
    }
}
