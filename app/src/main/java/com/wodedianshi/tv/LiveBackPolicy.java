package com.wodedianshi.tv;

/** One Back action for each live screen state. Popup dismissal takes precedence. */
final class LiveBackPolicy {
    private LiveBackPolicy() {}
    enum Action { CLOSE_MENU, EXIT_FULLSCREEN, RETURN_FULLSCREEN, FOCUS_CATEGORY, EXIT_ACTIVITY }

    static Action choose(boolean popupShowing, boolean fullscreen, boolean hasCategoryPane,
            boolean categoryFocused, boolean liveAvailable) {
        if(popupShowing)return Action.CLOSE_MENU;
        if(fullscreen)return Action.EXIT_FULLSCREEN;
        if(hasCategoryPane&&!categoryFocused)
            return liveAvailable?Action.RETURN_FULLSCREEN:Action.FOCUS_CATEGORY;
        return Action.EXIT_ACTIVITY;
    }
}
