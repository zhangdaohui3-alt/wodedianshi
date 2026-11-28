package com.wodedianshi.tv;

import java.util.HashSet;
import java.util.Set;

/** Small atomic operations for favorites shared by the UI and index workers. */
public final class FavoriteMembershipPolicy {
    private FavoriteMembershipPolicy() {}

    /** A toggle must be atomic; synchronizedSet only protects individual calls. */
    public static boolean toggle(Set<String> favorites, String key) {
        if (favorites == null || key == null || key.isEmpty())
            throw new IllegalArgumentException("Missing favorite set/key");
        synchronized (favorites) {
            boolean nowFavorite = !favorites.contains(key);
            if (nowFavorite) favorites.add(key);
            else favorites.remove(key);
            return nowFavorite;
        }
    }

    /** Iterating a synchronizedSet requires its external monitor. */
    public static HashSet<String> snapshot(Set<String> favorites) {
        if (favorites == null) throw new IllegalArgumentException("No favorite set");
        synchronized (favorites) {
            return new HashSet<>(favorites);
        }
    }
}
