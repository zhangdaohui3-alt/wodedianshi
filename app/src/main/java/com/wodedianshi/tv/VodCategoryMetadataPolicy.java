package com.wodedianshi.tv;

import java.util.Locale;

/**
 * Conservative post-search metadata guard for three Chinese catalogue shelves.
 * Rejects only clear cross-category records; sparse archive metadata is kept
 * because the search query itself has already supplied a relevance signal.
 */
public final class VodCategoryMetadataPolicy {
    private VodCategoryMetadataPolicy() {}

    public static boolean accepts(String category, String title, String description,
                                  String subjects, String collections) {
        if (!VodCategoryQueryPolicy.handles(category)) return true;
        String t = normalize(title);
        String s = normalize(subjects);
        String c = normalize(collections);
        boolean animation = containsAny(t + " " + s + " " + c,
                "animation", "animated film", "cartoon", "动漫", "动画片", "动画电影");
        boolean documentary = containsAny(t + " " + s + " " + c,
                "documentary", "nonfiction film", "non-fiction film", "纪录片", "纪实");
        boolean historicalFootage = containsAny(t + " " + s + " " + c,
                "newsreel", "historical footage", "archive footage", "historical documentary",
                "history documentary", "历史影像", "历史纪录片", "新闻纪录片");
        boolean featureFilm = containsAny(s + " " + c,
                "feature film", "fiction film", "narrative film", "剧情长片");

        if (animation) return false;
        if ("中国历史影像".equals(category)) return !featureFilm;
        if ("中国纪录片".equals(category)) return !historicalFootage && !featureFilm;
        if ("中国经典影像".equals(category)) return !documentary && !historicalFootage;
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
