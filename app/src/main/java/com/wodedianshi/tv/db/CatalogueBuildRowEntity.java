package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Durable work-in-progress row for a full catalogue rebuild; never shown to playback UI. */
@Entity(tableName = "catalogue_build_rows", indices = {@Index("sortOrder"), @Index({"mergeIdentity","sortOrder"})})
public class CatalogueBuildRowEntity {
    @PrimaryKey @NonNull public String rowId;
    public int sortOrder;
    @NonNull public String recordJson;
    @NonNull @ColumnInfo(defaultValue="\'\'") public String mergeIdentity="";

    public CatalogueBuildRowEntity(@NonNull String rowId, int sortOrder,
            @NonNull String recordJson) {
        this.rowId = rowId;
        this.sortOrder = sortOrder;
        this.recordJson = recordJson;
    }
}
