package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class DecoderOwnershipTest {
    @Test public void secondScreenCannotAcquireUntilFirstReleases() {
        DecoderOwnership ownership = new DecoderOwnership();
        Object first = new Object(), next = new Object();
        assertTrue(ownership.acquire(first));
        assertTrue(ownership.acquire(first));
        assertFalse(ownership.availableTo(next));
        assertFalse(ownership.acquire(next));
        ownership.release(next);
        assertFalse(ownership.acquire(next));
        ownership.release(first);
        assertTrue(ownership.acquire(next));
        ownership.release(first);
        assertFalse(ownership.acquire(first));
    }
    @Test public void uncertainReleaseRemainsQuarantined() {
        DecoderOwnership ownership = new DecoderOwnership();
        Object first = new Object(), next = new Object();
        assertTrue(ownership.acquire(first));
        ownership.quarantine(first);
        ownership.release(first);
        assertFalse(ownership.acquire(next));
        assertFalse(ownership.acquire(first));
        assertFalse(ownership.availableTo(next));
    }
    @Test public void staleOwnerCannotQuarantineNewOwner() {
        DecoderOwnership ownership = new DecoderOwnership();
        Object first = new Object(), next = new Object();
        assertTrue(ownership.acquire(first));
        ownership.release(first);
        assertTrue(ownership.acquire(next));
        ownership.quarantine(first);
        assertTrue(ownership.availableTo(next));
    }
}
