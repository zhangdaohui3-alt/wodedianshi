package com.wodedianshi.tv;

/**
 * In-process transaction lock shared by UI persistence and background
 * catalogue commits. All writes to the active "channels" snapshot must use it.
 */
final class ChannelCatalogueStore {
    static final Object LOCK = new Object();
    private ChannelCatalogueStore() {}
}
