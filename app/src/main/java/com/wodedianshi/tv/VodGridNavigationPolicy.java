package com.wodedianshi.tv;

/** Pure D-pad navigation decisions for the recycled VOD poster grid. */
public final class VodGridNavigationPolicy {
    public static final int UP = 0;
    public static final int DOWN = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;
    public static final int NATIVE_FOCUS = -1;
    public static final int RETURN_TO_CATEGORIES = -2;

    private VodGridNavigationPolicy() {}

    /**
     * Returns a concrete adapter position, or a sentinel to delegate edge
     * movement to Android's native focus/scroll behavior.
     */
    public static int targetPosition(int current, int count, int columns, int direction) {
        if (count <= 0 || columns <= 0 || current < 0 || current >= count) return NATIVE_FOCUS;
        int column = current % columns;
        switch (direction) {
            case UP:
                if (current < columns) return RETURN_TO_CATEGORIES;
                return current - columns;
            case DOWN:
                return current + columns < count ? current + columns : NATIVE_FOCUS;
            case LEFT:
                return column > 0 ? current - 1 : NATIVE_FOCUS;
            case RIGHT:
                return column + 1 < columns && current + 1 < count
                        ? current + 1 : NATIVE_FOCUS;
            default:
                return NATIVE_FOCUS;
        }
    }
}
