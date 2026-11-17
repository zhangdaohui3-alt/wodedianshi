package com.wodedianshi.tv.db;

import androidx.room.Embedded;
import androidx.room.Relation;
import java.util.List;

public class ChannelWithSources {
    @Embedded public ChannelEntity channel;
    // Keep the same source order used by the legacy catalogue: activeSource is an index.
    // SQLite does not guarantee relation order unless it is requested explicitly.
    @Relation(parentColumn = "id", entityColumn = "channelId", orderBy = {"priority ASC", "url ASC"})
    public List<BackupSourceEntity> sources;
}
