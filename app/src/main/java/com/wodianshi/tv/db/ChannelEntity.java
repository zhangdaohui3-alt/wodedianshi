package com.wodianshi.tv.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "channels",
    foreignKeys = @ForeignKey(entity = CategoryEntity.class,
        parentColumns = "id", childColumns = "categoryId",
        onDelete = ForeignKey.RESTRICT),
    indices = {
        @Index(value = {"categoryId", "sortOrder", "name", "id"}),
        @Index(value = {"sortOrder", "name", "id"}),
        @Index("name"),
        @Index("groupName")
    })
public class ChannelEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String legacyKey;
    @NonNull public String name;
    @NonNull public String primaryUrl;
    @NonNull public String groupName;
    @NonNull public String categoryId;
    @NonNull public String tvgId;
    @NonNull public String tvgName;
    @NonNull public String channelScopesJson;
    public int activeSource;
    public int sortOrder;

    public ChannelEntity(@NonNull String id, @NonNull String legacyKey, @NonNull String name,
            @NonNull String primaryUrl, @NonNull String groupName,
            @NonNull String categoryId, @NonNull String tvgId,
            @NonNull String tvgName, @NonNull String channelScopesJson,
            int activeSource, int sortOrder) {
        this.id = id;
        this.legacyKey = legacyKey;
        this.name = name;
        this.primaryUrl = primaryUrl;
        this.groupName = groupName;
        this.categoryId = categoryId;
        this.tvgId = tvgId;
        this.tvgName = tvgName;
        this.channelScopesJson = channelScopesJson;
        this.activeSource = activeSource;
        this.sortOrder = sortOrder;
    }
}
