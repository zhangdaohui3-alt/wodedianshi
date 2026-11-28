package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/** One normalized network endpoint per manual refresh round. A processed
 * opportunity includes timeout/failure; it is not a playback-valid flag. */
@Entity(tableName="manual_probe_queue",primaryKeys={"owner","endpoint"},
        indices={@Index(value={"owner","processed","ordinal","endpoint"})})
public final class ManualProbeQueueEntity {
    @NonNull public String owner;
    @NonNull public String endpoint;
    @NonNull public String url;
    @NonNull public String name;
    @NonNull public String groupName;
    @NonNull public String tvgId;
    @NonNull public String tvgName;
    public int ordinal;
    public boolean processed;

    public ManualProbeQueueEntity(@NonNull String owner,@NonNull String endpoint,
            @NonNull String url,@NonNull String name,@NonNull String groupName,
            @NonNull String tvgId,@NonNull String tvgName,int ordinal,boolean processed){
        this.owner=owner;this.endpoint=endpoint;this.url=url;this.name=name;
        this.groupName=groupName;this.tvgId=tvgId;this.tvgName=tvgName;
        this.ordinal=ordinal;this.processed=processed;
    }
}
