package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeReleaseConfirmationTest {
    @Test public void duplicateReleaseCannotHideFailure() {
        NativeReleaseConfirmation release = new NativeReleaseConfirmation();
        assertTrue(release.mayInitialize());
        assertTrue(release.beginRelease());
        release.finishRelease(false);
        assertFalse(release.beginRelease());
        assertFalse(release.isConfirmed());
        release.finishRelease(true);
        assertFalse(release.isConfirmed());
        assertFalse(release.mayInitialize());
    }

    @Test public void inFlightReleaseNeverCountsAsSuccess() {
        NativeReleaseConfirmation release = new NativeReleaseConfirmation();
        assertTrue(release.beginRelease());
        assertFalse(release.beginRelease());
        assertFalse(release.isConfirmed());
        assertFalse(release.mayInitialize());
        release.finishRelease(true);
        assertTrue(release.isConfirmed());
        assertFalse(release.beginRelease());
        assertFalse(release.mayInitialize());
    }
}
