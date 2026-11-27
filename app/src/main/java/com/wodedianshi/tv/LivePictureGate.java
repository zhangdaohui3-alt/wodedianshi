package com.wodedianshi.tv;

/** UI-thread-only ownership of the black live transition cover. */
final class LivePictureGate {
    private Object owner;
    private String mediaId;

    void cover() {
        owner=null;
        mediaId=null;
    }

    void arm(Object currentOwner,String currentMediaId) {
        owner=currentOwner;
        mediaId=currentMediaId;
    }

    boolean reveal(Object eventOwner,String eventMediaId) {
        if(owner==null||owner!=eventOwner||mediaId==null||mediaId.isEmpty()
                ||!mediaId.equals(eventMediaId))return false;
        cover(); // A duplicate event must not reopen an invalidated handoff.
        return true;
    }
}
