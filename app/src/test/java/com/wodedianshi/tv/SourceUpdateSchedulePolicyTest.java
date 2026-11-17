package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceUpdateSchedulePolicyTest {
    @Test public void schedulesInitialDiscoveryWhenNoUpdateRecordExists() {
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, null));
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, ""));
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, "  "));
        assertTrue(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, "尚未更新"));
    }

    @Test public void doesNotScheduleWhenAutomaticUpdatesAreDisabled() {
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(false, null));
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(false, "尚未更新"));
    }

    @Test public void doesNotRepeatInitialDiscoveryAfterAnUpdateRecordExists() {
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, "2026-11-12 09:30:00"));
        assertFalse(SourceUpdateSchedulePolicy.shouldRunInitialDiscovery(true, "更新失败：网络不可用"));
    }
}
