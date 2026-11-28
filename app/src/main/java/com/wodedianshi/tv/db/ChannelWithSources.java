package com.wodedianshi.tv.db;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ChannelWithSources {
    @Embedded public ChannelEntity channel;

    // Room's @Relation in the project's current Room version has no orderBy option.
    // Never rely on SQLite's incidental row order: activeSource is an index into
    // the legacy source order, which is stored explicitly as BackupSourceEntity.priority.
    @Relation(parentColumn = "id", entityColumn = "channelId")
    public List<BackupSourceEntity> sources;

    private static final Comparator<BackupSourceEntity> SOURCE_ORDER = (left, right) -> {
        int byPriority = Integer.compare(left.priority, right.priority);
        if (byPriority != 0) return byPriority;
        String leftUrl = left.url == null ? "" : left.url;
        String rightUrl = right.url == null ? "" : right.url;
        return leftUrl.compareTo(rightUrl);
    };

    /** Returns an independent copy in persisted priority order. */
    public List<BackupSourceEntity> getSourcesInPriorityOrder() {
        ArrayList<BackupSourceEntity> ordered = sources == null
                ? new ArrayList<>() : new ArrayList<>(sources);
        if (ordered.size() > 1) Collections.sort(ordered, SOURCE_ORDER);
        return ordered;
    }

    /** Resolves activeSource only after restoring the deterministic source order. */
    public String getActiveSourceUrl() {
        List<BackupSourceEntity> ordered = getSourcesInPriorityOrder();
        if (channel == null) return "";
        if (ordered.isEmpty()) return channel.primaryUrl == null ? "" : channel.primaryUrl;
        int index = Math.max(0, Math.min(channel.activeSource, ordered.size() - 1));
        return ordered.get(index).url == null ? "" : ordered.get(index).url;
    }
}
