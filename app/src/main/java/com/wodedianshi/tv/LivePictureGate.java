package com.wodedianshi.tv;

/** UI-thread-only ownership of the black live transition cover. */
final class LivePictureGate {
    private Object owner;
    private String mediaId;
    private long earliestRenderAt;

    void cover() {
        owner=null;
        mediaId=null;
        earliestRenderAt=0L;
    }

    void arm(Object currentOwner,String currentMediaId) {
        arm(currentOwner,currentMediaId,0L);
    }

    void arm(Object currentOwner,String currentMediaId,long assignedAt) {
        owner=currentOwner;
        mediaId=currentMediaId;
        earliestRenderAt=assignedAt;
    }

    /** Untimed Vout uses zero; it cannot satisfy a positive Media3 time floor. */
    boolean reveal(Object eventOwner,String eventMediaId,long renderedAt) {
        if(owner==null||owner!=eventOwner||mediaId==null||mediaId.isEmpty()
                ||!mediaId.equals(eventMediaId)||renderedAt<earliestRenderAt)return false;
        cover(); // A duplicate event must not reopen an invalidated handoff.
        return true;
    }
}
