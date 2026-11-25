package com.wodedianshi.tv.db;

/**
 * Lightweight row for rendering a TV channel list.
 *
 * Deliberately excludes backup-source relations and JSON metadata so opening a
 * category does not hydrate every URL for every visible channel.
 */
public final class ChannelListRow {
    public String id;
    public String legacyKey;
    public String name;
    public String primaryUrl;
    public String groupName;
    public String categoryId;
    public String tvgId;
    public String tvgName;
    public int activeSource;
    public int sortOrder;

    public ChannelListRow(String id, String legacyKey, String name, String primaryUrl, String groupName,
            String categoryId, String tvgId, String tvgName, int activeSource, int sortOrder) {
        this.id = id;
        this.legacyKey = legacyKey;
        this.name = name;
        this.primaryUrl = primaryUrl;
        this.groupName = groupName;
        this.categoryId = categoryId;
        this.tvgId = tvgId;
        this.tvgName = tvgName;
        this.activeSource = activeSource;
        this.sortOrder = sortOrder;
    }

    /**
     * Recover the original legacy primary URL used by favorites and channel
     * identity. primaryUrl is the first URL in this Room partition and may be
     * different when a channel is shared across China/international catalogues.
     */
    public String legacyPrimaryUrl() {
        if (legacyKey != null && name != null) {
            String prefix = name + "|";
            if (legacyKey.startsWith(prefix)) return legacyKey.substring(prefix.length());
        }
        return primaryUrl == null ? "" : primaryUrl;
    }
}
