package com.wodedianshi.tv.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects source URLs for an isolated Room catalogue without changing the
 * legacy channel identity. A scoped database must never inherit another
 * partition's backup URLs just because the channel also belongs to that scope.
 */
public final class LegacySourceScopePolicy {
    private LegacySourceScopePolicy() {}

    public static final class Selection {
        public final List<String> urls;
        public final int activeIndex;
        private final Map<String, Set<String>> scopesByUrl;
        private final String requestedScope;

        Selection(List<String> selectedUrls, int selectedActive,
                  Map<String, Set<String>> selectedScopes, String scope) {
            urls = Collections.unmodifiableList(new ArrayList<>(selectedUrls));
            activeIndex = selectedActive;
            scopesByUrl = selectedScopes;
            requestedScope = scope;
        }

        public String channelScopesJson(Set<String> originalChannelScopes) {
            if (requestedScope == null) return jsonArray(originalChannelScopes);
            return jsonArray(Collections.singleton(requestedScope));
        }

        public String sourceScopesJson(String url,
                Map<String, Set<String>> originalOwnership) {
            if (requestedScope != null) {
                return jsonArray(Collections.singleton(requestedScope));
            }
            Set<String> scopes = scopesByUrl.get(url);
            if (scopes == null && originalOwnership != null) scopes = originalOwnership.get(url);
            return jsonArray(scopes);
        }
    }

    /**
     * @param orderedUrls URL-normalized source order from LegacySourceOrderPolicy
     * @param activeIndex index selected in orderedUrls before partition filtering
     * @param ownership URL -> explicit catalogue scopes
     * @param requestedScope null for the full catalogue, otherwise "china" or "global"
     *
     * Old saved rows may have channel scope metadata but no per-URL ownership map.
     * Only in that legacy case, keep their whole source list for compatibility.
     * Once any per-URL provenance exists, include only sources explicitly owned by
     * the requested partition. If none survive, the channel is not imported there.
     */
    public static Selection select(List<String> orderedUrls, int activeIndex,
            Map<String, Set<String>> ownership, String requestedScope) {
        ArrayList<String> safeUrls = new ArrayList<>();
        if (orderedUrls != null) {
            for (String url : orderedUrls) {
                if (url != null && !url.trim().isEmpty()) safeUrls.add(url);
            }
        }

        String activeUrl = activeIndex >= 0 && activeIndex < safeUrls.size()
                ? safeUrls.get(activeIndex) : null;
        boolean provenanceMissing = ownership == null || ownership.isEmpty();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        HashMap<String, Set<String>> selectedScopes = new HashMap<>();

        for (String url : safeUrls) {
            Set<String> sourceScopes = ownership == null ? null : ownership.get(url);
            if (requestedScope != null && !provenanceMissing
                    && (sourceScopes == null || !sourceScopes.contains(requestedScope))) {
                continue;
            }
            selected.add(url);
            if (requestedScope != null) {
                selectedScopes.put(url, Collections.singleton(requestedScope));
            } else if (sourceScopes != null && !sourceScopes.isEmpty()) {
                selectedScopes.put(url, new HashSet<>(sourceScopes));
            }
        }

        ArrayList<String> result = new ArrayList<>(selected);
        int selectedActiveIndex = activeUrl == null ? -1 : result.indexOf(activeUrl);
        if (selectedActiveIndex < 0) selectedActiveIndex = result.isEmpty() ? 0 : 0;
        return new Selection(result, selectedActiveIndex, selectedScopes, requestedScope);
    }

    private static String jsonArray(Set<String> values) {
        org.json.JSONArray array = new org.json.JSONArray();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) array.put(value);
            }
        }
        return array.toString();
    }
}
