package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodGridNavigationPolicyTest {
    @Test public void verticalMovementKeepsColumn() {
        assertEquals(5, VodGridNavigationPolicy.targetPosition(2, 20, 3, VodGridNavigationPolicy.DOWN));
        assertEquals(2, VodGridNavigationPolicy.targetPosition(5, 20, 3, VodGridNavigationPolicy.UP));
    }

    @Test public void firstRowUpReturnsToCategoryStrip() {
        assertEquals(VodGridNavigationPolicy.RETURN_TO_CATEGORIES,
                VodGridNavigationPolicy.targetPosition(1, 20, 3, VodGridNavigationPolicy.UP));
    }

    @Test public void downAtLastRowDelegatesToNativeGridInsteadOfTrappingFocus() {
        assertEquals(VodGridNavigationPolicy.NATIVE_FOCUS,
                VodGridNavigationPolicy.targetPosition(9, 10, 4, VodGridNavigationPolicy.DOWN));
    }

    @Test public void downAtIncompleteLastRowStillMovesWhenTargetExists() {
        assertEquals(9, VodGridNavigationPolicy.targetPosition(5, 10, 4, VodGridNavigationPolicy.DOWN));
    }

    @Test public void horizontalMovementDoesNotWrapRows() {
        assertEquals(VodGridNavigationPolicy.NATIVE_FOCUS,
                VodGridNavigationPolicy.targetPosition(7, 10, 4, VodGridNavigationPolicy.RIGHT));
        assertEquals(VodGridNavigationPolicy.NATIVE_FOCUS,
                VodGridNavigationPolicy.targetPosition(4, 10, 4, VodGridNavigationPolicy.LEFT));
        assertEquals(6, VodGridNavigationPolicy.targetPosition(5, 10, 4, VodGridNavigationPolicy.RIGHT));
    }

    @Test public void invalidPositionsAndDirectionsDelegateToNativeGrid() {
        assertEquals(VodGridNavigationPolicy.NATIVE_FOCUS,
                VodGridNavigationPolicy.targetPosition(-1, 10, 4, VodGridNavigationPolicy.DOWN));
        assertEquals(VodGridNavigationPolicy.NATIVE_FOCUS,
                VodGridNavigationPolicy.targetPosition(10, 10, 4, VodGridNavigationPolicy.UP));
        assertEquals(VodGridNavigationPolicy.NATIVE_FOCUS,
                VodGridNavigationPolicy.targetPosition(0, 10, 4, 99));
    }
}
