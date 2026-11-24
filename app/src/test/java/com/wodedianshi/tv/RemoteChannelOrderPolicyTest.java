package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteChannelOrderPolicyTest {
    @Test public void pendingKeyStepsAreMergedAndBounded() {
        org.junit.Assert.assertEquals(4, RemoteChannelOrderPolicy.mergeZapSteps(3, 1));
        org.junit.Assert.assertEquals(0, RemoteChannelOrderPolicy.mergeZapSteps(1, -1));
        org.junit.Assert.assertEquals(12, RemoteChannelOrderPolicy.mergeZapSteps(12, 1));
        org.junit.Assert.assertEquals(-12, RemoteChannelOrderPolicy.mergeZapSteps(-12, -1));
    }

    @Test public void countryAndFavoriteMembershipAreBothRequired() {
        java.util.Set<String> geo = java.util.Collections.singleton("fujian");
        java.util.Set<String> fav = java.util.Collections.singleton("other");
        assertTrue(RemoteChannelOrderPolicy.matchesMembership("fujian", geo, false, fav));
        assertFalse(RemoteChannelOrderPolicy.matchesMembership("other", geo, false, fav));
        assertFalse(RemoteChannelOrderPolicy.matchesMembership("fujian", geo, true, fav));
        assertTrue(RemoteChannelOrderPolicy.matchesMembership("other", null, true, fav));
    }

    @Test public void displayAliasAndWhitespaceAreHandledConsistently() {
        assertTrue(RemoteChannelOrderPolicy.matchesQuery(" CCTV-2 ", "Finance", null, "CCTV-2"));
        assertFalse(RemoteChannelOrderPolicy.matchesQuery("CCTV-3", "Finance", null, "CCTV-2"));
        assertTrue(RemoteChannelOrderPolicy.matchesQuery(" ", null, null, null));
    }

    @Test public void autoResumeCannotUseReplacedPositionOrInterruptVod() {
        Object original = new Object();
        assertTrue(RemoteChannelOrderPolicy.canResume(false, false, original, original));
        assertFalse(RemoteChannelOrderPolicy.canResume(false, false, original, new Object()));
        assertFalse(RemoteChannelOrderPolicy.canResume(true, false, original, original));
        assertFalse(RemoteChannelOrderPolicy.canResume(false, true, original, original));
    }

    @Test public void pendingResumeFollowsIdentityInsteadOfOldPosition() {
        Object original = new Object();
        java.util.Map<String,Integer> positions = java.util.Collections.singletonMap("CCTV-2|cctv", 4);
        org.junit.Assert.assertEquals(4, RemoteChannelOrderPolicy.remapResumeIndex(
                false, false, original, original, "CCTV-2|cctv", positions, 5));
        org.junit.Assert.assertEquals(-1, RemoteChannelOrderPolicy.remapResumeIndex(
                false, false, original, original, "CCTV-3|cctv", positions, 5));
        org.junit.Assert.assertEquals(-1, RemoteChannelOrderPolicy.remapResumeIndex(
                false, false, original, new Object(), "CCTV-2|cctv", positions, 5));
        org.junit.Assert.assertEquals(-1, RemoteChannelOrderPolicy.remapResumeIndex(
                true, false, original, original, "CCTV-2|cctv", positions, 5));
        org.junit.Assert.assertEquals(-1, RemoteChannelOrderPolicy.remapResumeIndex(
                false, true, original, original, "CCTV-2|cctv", positions, 5));
        org.junit.Assert.assertEquals(-1, RemoteChannelOrderPolicy.remapResumeIndex(
                false, false, original, original, "CCTV-2|cctv", positions, 4));
    }

    @Test public void completedOrderCanPublish() {
        assertTrue(RemoteChannelOrderPolicy.mayPublish(
                false, 31L, 31L, 4L, 4L, 6861, 6861, true, true, 123, 123));
    }

    @Test public void interruptedPartialOrderMustNotPublish() {
        assertFalse(RemoteChannelOrderPolicy.complete(true, 31L, 31L));
        assertFalse(RemoteChannelOrderPolicy.mayPublish(
                true, 31L, 31L, 4L, 4L, 6861, 6861, false, false, 0, 0));
    }

    @Test public void equalRowCountDoesNotMakeReorderedCatalogueSafe() {
        assertFalse(RemoteChannelOrderPolicy.mayPublish(
                false, 31L, 32L, 4L, 4L, 6861, 6861, false, false, 0, 0));
    }

    @Test public void oldCategoryOrChangedFavoriteSetMustNotPublish() {
        assertFalse(RemoteChannelOrderPolicy.mayPublish(
                false, 31L, 31L, 4L, 5L, 6861, 6861, false, false, 0, 0));
        assertFalse(RemoteChannelOrderPolicy.mayPublish(
                false, 31L, 31L, 4L, 4L, 6861, 6861, true, true, 123, 124));
        assertFalse(RemoteChannelOrderPolicy.mayPublish(
                false, 31L, 31L, 4L, 4L, 6861, 6861, false, true, 0, 0));
    }

    @Test public void differentRowCountNeverPublishesOldPositions() {
        assertFalse(RemoteChannelOrderPolicy.mayPublish(
                false, 31L, 31L, 4L, 4L, 6861, 6862, false, false, 0, 0));
    }
}
