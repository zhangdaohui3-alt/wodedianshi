package com.wodedianshi.tv;

/** One process-wide owner; tokens do not retain an Activity. */
final class DecoderOwnership {
    private Object owner;
    private boolean quarantined;
    synchronized boolean acquire(Object token) {
        if (token == null || quarantined || (owner != null && owner != token)) return false;
        owner = token;
        return true;
    }
    synchronized boolean availableTo(Object token) {
        return !quarantined && (owner == null || owner == token);
    }
    synchronized void release(Object token) {
        if (!quarantined && owner == token) owner = null;
    }
    synchronized void quarantine(Object token) {
        if (owner == token) quarantined = true;
    }
}
