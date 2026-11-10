package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Builds bounded, diverse searches for public catalogues. */
public final class VodSearchPlan {
    private VodSearchPlan() {}

    public static final class PageRequest {
        public final int queryIndex;
        public final int page;

        private PageRequest(int queryIndex, int page) {
            this.queryIndex = queryIndex;
            this.page = page;
        }
    }

    /**
     * Build independent title/metadata queries instead of requiring one exact
     * phrase to occur in one field. Terms are reduced to letters, digits and
     * spaces before being inserted into the Archive.org query language.
     */
    public static List<String> archiveQueries(String raw) {
        return archiveQueries(raw, "全部");
    }

    public static List<String> archiveQueries(String raw, String region) {
        String cleaned = raw == null ? "" : raw
                .replaceAll("[\\p{Punct}\\p{Z}\\s]+", " ")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return Collections.emptyList();

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String phrase = quote(cleaned);
        boolean hasHan = raw != null && raw.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        String regionClause = "";
        if ("中国".equals(region)) {
            regionClause = " AND (language:Chinese OR language:chi OR language:zho"
                    + " OR language:Mandarin OR title:Chinese OR title:China"
                    + " OR subject:Chinese OR subject:China OR title:中文 OR title:中国"
                    + " OR title:华语 OR description:Chinese OR description:China"
                    + (hasHan ? " OR title:" + phrase + " OR description:" + phrase : "") + ")";
        } else if ("国外".equals(region)) {
            regionClause = " AND (language:English OR language:eng OR language:Spanish"
                    + " OR language:French OR language:German OR language:Japanese"
                    + " OR language:Korean OR language:Italian OR language:Russian"
                    + " OR language:Portuguese OR language:Hindi OR language:Arabic"
                    + " OR language:Thai OR language:Vietnamese OR language:Indonesian"
                    + " OR language:Dutch OR language:Swedish OR language:Polish"
                    + " OR language:Turkish OR subject:international OR title:foreign"
                    + (hasHan ? " OR title:" + phrase + " OR description:" + phrase : "") + ")";
        }
        queries.add("mediatype:movies AND (title:" + phrase
                + " OR description:" + phrase + " OR subject:" + phrase
                + " OR creator:" + phrase + ")" + regionClause);
        // Region tags in public archives are often incomplete. Keep a second,
        // region-neutral pass so a title is not hidden by missing language metadata.
        queries.add("mediatype:movies AND (title:" + phrase
                + " OR description:" + phrase + " OR subject:" + phrase
                + " OR creator:" + phrase + ")");
        if (terms.length > 1) {
            StringBuilder relaxed = new StringBuilder("mediatype:movies AND (");
            for (int i = 0; i < terms.length; i++) {
                if (i > 0) relaxed.append(" OR ");
                String term = quote(terms[i]);
                relaxed.append("title:").append(term)
                        .append(" OR description:").append(term)
                        .append(" OR subject:").append(term)
                        .append(" OR creator:").append(term);
            }
            relaxed.append(")");
            queries.add(relaxed.toString());
        }
        StringBuilder titleTokens = new StringBuilder("mediatype:movies AND (");
        for (int i = 0; i < termsCount(cleaned); i++) {
            if (i > 0) titleTokens.append(" AND ");
            titleTokens.append("title:").append(quote(cleaned.split(" ")[i]));
        }
        titleTokens.append(")");
        queries.add(titleTokens.toString() + regionClause);

        String[] terms = cleaned.split(" ");
        if (terms.length > 1) {
            StringBuilder allFields = new StringBuilder("mediatype:movies AND ");
            for (int i = 0; i < terms.length; i++) {
                if (i > 0) allFields.append(" AND ");
                String term = quote(terms[i]);
                allFields.append("(title:").append(term)
                        .append(" OR description:").append(term)
                        .append(" OR subject:").append(term)
                        .append(" OR creator:").append(term).append(")");
            }
            queries.add(allFields.toString() + regionClause);
        }
        // A broad all-fields query catches catalogue records whose title is
        // incomplete but whose creator/subject/description carries the name.
        if (cleaned.length() >= 3) {
            queries.add("mediatype:movies AND (title:" + phrase
                    + " OR description:" + phrase + " OR subject:" + phrase
                    + " OR creator:" + phrase + ")" + regionClause);
        }
        return Collections.unmodifiableList(new ArrayList<>(queries));
    }

    private static int termsCount(String value) { return value.split(" ").length; }

    private static String quote(String value) {
        return "\"" + value.replace("\\", " ").replace("\"", " ") + "\"";
    }

    /** Page-major ordering: all query page-1 requests precede every page-2 page. */
    public static List<PageRequest> orderedPages(int queryCount, int maxPagesPerQuery) {
        if (queryCount <= 0 || maxPagesPerQuery <= 0) return Collections.emptyList();
        ArrayList<PageRequest> result = new ArrayList<>(queryCount * maxPagesPerQuery);
        for (int page = 1; page <= maxPagesPerQuery; page++) {
            for (int query = 0; query < queryCount; query++) {
                result.add(new PageRequest(query, page));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
