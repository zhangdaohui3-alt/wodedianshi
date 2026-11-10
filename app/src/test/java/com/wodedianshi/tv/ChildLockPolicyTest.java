package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Calendar;
import static org.junit.Assert.*;

public class ChildLockPolicyTest {
    private Calendar at(int dayOfWeek, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 9, hour, minute, 0);
        c.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    @Test public void overnightWindowIncludesNextMorning() {
        int everyDay = 0x7f;
        assertTrue(ChildLockPolicy.isBlocked(at(Calendar.MONDAY, 22, 0), 22*60, 3*60, everyDay));
        assertTrue(ChildLockPolicy.isBlocked(at(Calendar.TUESDAY, 2, 59), 22*60, 3*60, everyDay));
        assertFalse(ChildLockPolicy.isBlocked(at(Calendar.TUESDAY, 3, 0), 22*60, 3*60, everyDay));
        assertFalse(ChildLockPolicy.isBlocked(at(Calendar.TUESDAY, 12, 0), 22*60, 3*60, everyDay));
    }

    @Test public void overnightMorningUsesPreviousDaySelection() {
        int mondayOnly = 1 << (Calendar.MONDAY - Calendar.SUNDAY);
        assertTrue(ChildLockPolicy.isBlocked(at(Calendar.TUESDAY, 2, 0), 22*60, 3*60, mondayOnly));
        assertFalse(ChildLockPolicy.isBlocked(at(Calendar.TUESDAY, 22, 30), 22*60, 3*60, mondayOnly));
    }

    @Test public void equalStartAndEndDisablesWindow() {
        assertFalse(ChildLockPolicy.isBlocked(at(Calendar.MONDAY, 23, 0), 22*60, 22*60, 0x7f));
    }

    @Test public void pinHashIsStableAndNotPlainText() {
        String hash = ChildLockPolicy.sha256("1234");
        assertEquals(64, hash.length());
        assertNotEquals("1234", hash);
        assertEquals(hash, ChildLockPolicy.sha256("1234"));
    }
}
