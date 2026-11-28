package com.wodedianshi.tv;

/** UI-thread-only: retain the newest directory publication while a touch
 * sequence owns the list. Never detach a pressed channel button mid-gesture. */
final class LiveDirectoryTouchGate {
    private boolean touching;
    private Runnable pending;
    void begin(){touching=true;}
    boolean defer(Runnable publication){
        if(!touching)return false;
        pending=publication;
        return true;
    }
    Runnable end(){
        touching=false;
        Runnable result=pending;
        pending=null;
        return result;
    }
}
