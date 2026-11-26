package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(tableName = "backup_sources",
    primaryKeys = {"channelId", "url"},
    foreignKeys = @ForeignKey(entity = ChannelEntity.class,
        parentColumns = "id", childColumns = "channelId",
        onDelete = ForeignKey.CASCADE),
    indices = {@Index("channelId"), @Index(value = {"channelId", "priority"})})
public class BackupSourceEntity {
    @NonNull public String channelId;
    @NonNull public String url;
    public int priority;
    @NonNull public String scopesJson;

    public BackupSourceEntity(@NonNull String channelId, @NonNull String url,
            int priority, @NonNull String scopesJson) {
        this.channelId = channelId;
        this.url = url;
        this.priority = priority;
        this.scopesJson = scopesJson;
    }
}
