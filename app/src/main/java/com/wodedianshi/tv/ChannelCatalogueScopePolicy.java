package com.wodedianshi.tv;

import java.util.Set;

/** Strict scope membership for domestic, international and user-managed channel directories. */
public final class ChannelCatalogueScopePolicy {
    private ChannelCatalogueScopePolicy() {}

    /** Unknown/legacy scope metadata never silently leaks into a requested partition. */
    public static boolean matches(Set<String> channelScopes, String requestedScope) {
        return channelScopes != null && requestedScope != null
                && !requestedScope.isEmpty() && channelScopes.contains(requestedScope);
    }
}
