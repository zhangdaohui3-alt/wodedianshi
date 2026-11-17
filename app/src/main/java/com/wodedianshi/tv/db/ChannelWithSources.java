package com.wodedianshi.tv.db;

import androidx.room.Embedded;
import androidx.room.Relation;
import java.util.List;

public class ChannelWithSources {
    @Embedded public ChannelEntity channel;
    @Relation(parentColumn = "id", entityColumn = "channelId")
    public List<BackupSourceEntity> sources;
}
