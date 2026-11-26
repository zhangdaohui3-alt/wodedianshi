package com.wodedianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(tableName = "favorites",
    primaryKeys = {"channelId"},
    foreignKeys = @ForeignKey(entity = ChannelEntity.class,
        parentColumns = "id", childColumns = "channelId",
        onDelete = ForeignKey.CASCADE),
    indices = {@Index("channelId")})
public class FavoriteEntity {
    @NonNull public String channelId;
    public long addedAt;

    public FavoriteEntity(@NonNull String channelId, long addedAt) {
        this.channelId = channelId;
        this.addedAt = addedAt;
    }
}
