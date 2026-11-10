package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps overlapping Chinese VOD shelves semantically distinct at the catalogue
 * query layer. Search terms intentionally use both Chinese and English metadata
 * because public archives are inconsistently tagged.
 */
public final class VodCategoryQueryPolicy {
    private static final String CHINA =
            "(language:Chinese OR language:chi OR language:zho OR title:China OR title:Chinese"
            + " OR subject:China OR subject:Chinese OR title:中国 OR title:中文 OR title:华语 OR title:国语)";

    private VodCategoryQueryPolicy() {}

    public static boolean handles(String category) {
        return "中国历史影像".equals(category)
                || "中国纪录片".equals(category)
                || "中国经典影像".equals(category);
    }

    public static List<String> queriesFor(String category) {
        ArrayList<String> queries = new ArrayList<>();
        if ("中国历史影像".equals(category)) {
            queries.add("mediatype:movies AND " + CHINA
                    + " AND (subject:history OR title:history OR title:historical"
                    + " OR title:历史 OR subject:historical footage)"
                    + " AND (subject:documentary OR title:documentary OR subject:newsreel"
                    + " OR title:newsreel OR subject:archive footage OR title:archive footage)"
                    + " AND NOT (subject:feature film OR subject:animation OR title:cartoon)");
            queries.add("mediatype:movies AND (title:China newsreel OR title:Chinese newsreel"
                    + " OR title:Chinese archive footage OR subject:China historical footage)"
                    + " AND NOT (subject:feature film OR subject:animation)");
            queries.add("mediatype:movies AND " + CHINA
                    + " AND (title:historical documentary OR subject:Chinese history"
                    + " OR subject:Chinese historical documentary)"
                    + " AND NOT (subject:feature film OR subject:animation)");
        } else if ("中国纪录片".equals(category)) {
            queries.add("mediatype:movies AND " + CHINA
                    + " AND (subject:documentary OR title:documentary OR subject:educational film"
                    + " OR title:纪录片)"
                    + " AND NOT (subject:history OR title:history OR title:historical"
                    + " OR subject:historical footage OR title:newsreel OR subject:animation"
                    + " OR title:animation)");
            queries.add("mediatype:movies AND (title:China documentary OR title:Chinese documentary"
                    + " OR subject:Chinese documentary)"
                    + " AND NOT (subject:history OR title:history OR title:historical"
                    + " OR subject:animation)");
        } else if ("中国经典影像".equals(category)) {
            queries.add("mediatype:movies AND " + CHINA
                    + " AND (subject:classic film OR subject:feature film OR subject:classic cinema"
                    + " OR title:classic OR title:cinema OR subject:Chinese cinema OR title:电影)"
                    + " AND NOT (subject:documentary OR title:documentary OR subject:history"
                    + " OR title:history OR title:historical OR subject:animation"
                    + " OR title:animation OR subject:newsreel)");
            queries.add("mediatype:movies AND (title:Hong Kong classic OR title:Taiwan classic"
                    + " OR subject:Hong Kong cinema OR subject:Chinese cinema)"
                    + " AND NOT (subject:documentary OR subject:history OR subject:animation"
                    + " OR subject:newsreel)");
        }
        return Collections.unmodifiableList(queries);
    }
}
