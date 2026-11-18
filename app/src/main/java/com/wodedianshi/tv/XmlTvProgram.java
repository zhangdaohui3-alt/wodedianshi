package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One validated XMLTV programme interval. Times are epoch milliseconds. */
public final class XmlTvProgram {
    public final String channelId;
    public final long startMillis;
    public final long stopMillis;
    public final String title;
    public final String description;
    public final List<String> categories;

    public XmlTvProgram(String channelId, long startMillis, long stopMillis,
                        String title, String description, List<String> categories) {
        this.channelId = safe(channelId);
        this.startMillis = startMillis;
        this.stopMillis = stopMillis;
        this.title = safe(title);
        this.description = safe(description);
        this.categories = Collections.unmodifiableList(
                new ArrayList<>(categories == null ? Collections.emptyList() : categories));
    }

    public boolean contains(long timeMillis) {
        return timeMillis >= startMillis && timeMillis < stopMillis;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
