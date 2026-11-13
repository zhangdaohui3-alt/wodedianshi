package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** XMLTV channel identity and its human-readable aliases. */
public final class XmlTvChannel {
    public final String id;
    public final List<String> displayNames;

    public XmlTvChannel(String id, List<String> displayNames) {
        this.id = id == null ? "" : id.trim();
        this.displayNames = Collections.unmodifiableList(new ArrayList<>(
                displayNames == null ? Collections.emptyList() : displayNames));
    }

    public String preferredName() {
        return displayNames.isEmpty() ? id : displayNames.get(0);
    }
}
