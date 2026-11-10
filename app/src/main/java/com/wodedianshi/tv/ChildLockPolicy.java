package com.wodedianshi.tv;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKeyFactory;\nimport javax.crypto.spec.PBEKeySpec;
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

    /** PBKDF2 password hash; callers must persist a random per-install salt. */
    public static String hashPin(String pin, String salt) {
        char[] password = (pin == null ? "" : pin).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, (salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8), 120000, 256);
        try {
            byte[] digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\\0');
        }
    }
}
