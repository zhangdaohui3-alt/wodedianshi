package com.wodedianshi.tv.db;

/**
 * Lightweight row for rendering a TV channel list.
 *
 * Deliberately excludes backup-source relations and JSON metadata so opening a
 * category does not hydrate every URL for every visible channel.
 */
public final class ChannelListRow {
    public String id;
    public String name;
    public String primaryUrl;
    public String groupName;
    public String categoryId;
    public String tvgId;
    public String tvgName;
    public int activeSource;
    public int sortOrder;

    public ChannelListRow(String id, String name, String primaryUrl, String groupName,
            String categoryId, String tvgId, String tvgName, int activeSource, int sortOrder) {
        this.id = id;
        this.name = name;
        this.primaryUrl = primaryUrl;
        this.groupName = groupName;
        this.categoryId = categoryId;
        this.tvgId = tvgId;
        this.tvgName = tvgName;
        this.activeSource = activeSource;
        this.sortOrder = sortOrder;
    }
}
