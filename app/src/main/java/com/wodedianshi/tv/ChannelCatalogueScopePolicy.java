package com.wodedianshi.tv;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;

/** Strict scope membership for domestic, international and user-managed channel directories. */
public final class ChannelCatalogueScopePolicy {
    private ChannelCatalogueScopePolicy() {}

    /** Unknown/legacy scope metadata never silently leaks into a requested partition. */
    public static boolean matches(Set<String> channelScopes, String requestedScope) {
        return channelScopes != null && requestedScope != null
                && !requestedScope.isEmpty() && channelScopes.contains(requestedScope);
    }

    /** Explicit country/state/city playlist provenance may survive a Room refresh
     * only while at least one retained backup still owns that directory scope. */
    public static boolean isRetainableScope(String scope) {
        if ("china".equals(scope) || "global".equals(scope) || "custom".equals(scope))
            return true;
        return scope != null && scope.matches("geo:(countries|subdivisions|cities):[a-z0-9-]+");
    }

    /**
     * Rebuilds channel-level scope membership from the retained URL ownership map.
     * Removing the last source owned by a region must also remove that region from
     * the channel-level directory; otherwise an old global/china tag can linger.
     */
    public static Set<String> retainedScopes(
            Map<String, ? extends Set<String>> sourceOwnership,
            Set<String> retainedUrls) {
        HashSet<String> result = new HashSet<>();
        if (sourceOwnership == null || retainedUrls == null || retainedUrls.isEmpty()) {
            return result;
        }
        for (String url : retainedUrls) {
            Set<String> scopes = sourceOwnership.get(url);
            if (scopes == null) continue;
            for (String scope : scopes) {
                if (isRetainableScope(scope)) {
                    result.add(scope);
                }
            }
        }
        return result;
    }
}
