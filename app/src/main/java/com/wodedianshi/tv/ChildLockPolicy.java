package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Locale;

/** Pure local policy helpers for the parental controls. */
public final class ChildLockPolicy {
    private ChildLockPolicy() {}

    /**
     * Checks a recurring weekly quiet window. For a window crossing midnight,
     * the after-midnight portion belongs to the previous day's selected bit.
     * startMinute == endMinute disables the window.
     */
    public static boolean isBlocked(Calendar now, int startMinute, int endMinute, int daysMask) {
        if (now == null || startMinute < 0 || startMinute >= 1440
                || endMinute < 0 || endMinute >= 1440 || startMinute == endMinute) return false;
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int dayBit = 1 << (now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY);
        if (startMinute < endMinute) {
            return (daysMask & dayBit) != 0 && minute >= startMinute && minute < endMinute;
        }
        if (minute >= startMinute) return (daysMask & dayBit) != 0;
        if (minute < endMinute) {
            int previousDay = (now.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 6) % 7;
            return (daysMask & (1 << previousDay)) != 0;
        }
        return false;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
