package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteChannelOrderPolicyTest {
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
