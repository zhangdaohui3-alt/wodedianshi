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
        String cleaned = raw == null ? "" : raw
                .replaceAll("[\\p{Punct}\\p{Z}\\s]+", " ")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return Collections.emptyList();

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String phrase = quote(cleaned);
        queries.add("mediatype:movies AND (title:" + phrase
                + " OR description:" + phrase + " OR subject:" + phrase
                + " OR creator:" + phrase + ")");
        StringBuilder titleTokens = new StringBuilder("mediatype:movies AND (");
        for (int i = 0; i < termsCount(cleaned); i++) {
            if (i > 0) titleTokens.append(" AND ");
            titleTokens.append("title:").append(quote(cleaned.split(" ")[i]));
        }
        titleTokens.append(")");
        queries.add(titleTokens.toString());

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
            queries.add(allFields.toString());
        }
        // A broad all-fields query catches catalogue records whose title is
        // incomplete but whose creator/subject/description carries the name.
        if (cleaned.length() >= 3) {
            queries.add("mediatype:movies AND (title:" + phrase
                    + " OR description:" + phrase + " OR subject:" + phrase
                    + " OR creator:" + phrase + ")");
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
