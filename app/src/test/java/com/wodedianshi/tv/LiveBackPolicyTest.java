package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.wodedianshi.tv.LiveBackPolicy.Action.*;

public class LiveBackPolicyTest {
    @Test public void popupBackOnlyClosesMenuRegardlessOfFocus(){
        assertEquals(CLOSE_MENU,LiveBackPolicy.choose(true,true,true,true,true));
        assertEquals(CLOSE_MENU,LiveBackPolicy.choose(true,true,true,false,true));
        assertEquals(CLOSE_MENU,LiveBackPolicy.choose(true,false,true,false,true));
    }
    @Test public void backFromVideoLeavesFullscreenButBackFromLiveListRestoresIt(){
        assertEquals(EXIT_FULLSCREEN,LiveBackPolicy.choose(false,true,true,false,true));
        assertEquals(RETURN_FULLSCREEN,LiveBackPolicy.choose(false,false,true,false,true));
    }
    @Test public void withoutLiveSessionPreserveCategoryAndExitNavigation(){
        assertEquals(FOCUS_CATEGORY,LiveBackPolicy.choose(false,false,true,false,false));
        assertEquals(EXIT_ACTIVITY,LiveBackPolicy.choose(false,false,true,true,false));
        assertEquals(EXIT_ACTIVITY,LiveBackPolicy.choose(false,false,true,true,true));
        assertEquals(EXIT_ACTIVITY,LiveBackPolicy.choose(false,false,false,false,false));
    }
}
