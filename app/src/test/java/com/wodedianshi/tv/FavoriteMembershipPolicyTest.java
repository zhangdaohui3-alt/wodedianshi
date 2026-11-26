package com.wodedianshi.tv;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class FavoriteMembershipPolicyTest {
    @Test public void toggleIsAtomicAndReturnsNewMembership() {
        Set<String> favorites = Collections.synchronizedSet(new HashSet<>());
        assertTrue(FavoriteMembershipPolicy.toggle(favorites, "CCTV-1"));
        assertTrue(favorites.contains("CCTV-1"));
        assertFalse(FavoriteMembershipPolicy.toggle(favorites, "CCTV-1"));
        assertFalse(favorites.contains("CCTV-1"));
    }

    @Test public void snapshotCopiesCompleteSetAndDoesNotShareMutability() {
        Set<String> favorites = Collections.synchronizedSet(new HashSet<>(
                Arrays.asList("CCTV-1", "湖南卫视", "福州新闻")));
        Set<String> before=FavoriteMembershipPolicy.snapshot(favorites);
        assertEquals(3,before.size());
        favorites.remove("CCTV-1");
        favorites.add("浙江卫视");
        assertTrue(before.contains("CCTV-1"));
        assertFalse(before.contains("浙江卫视"));
        assertEquals(3,FavoriteMembershipPolicy.snapshot(favorites).size());
    }

    @Test public void invalidInputCannotCorruptExistingFavorites() {
        Set<String> favorites=Collections.synchronizedSet(new HashSet<>());
        assertThrowsIllegalArgument(() -> FavoriteMembershipPolicy.toggle(favorites,""));
        assertThrowsIllegalArgument(() -> FavoriteMembershipPolicy.toggle(null,"CCTV-1"));
        assertThrowsIllegalArgument(() -> FavoriteMembershipPolicy.snapshot(null));
        assertTrue(favorites.isEmpty());
    }

    private static void assertThrowsIllegalArgument(Runnable action) {
        try { action.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { /* pass */ }
    }
}
