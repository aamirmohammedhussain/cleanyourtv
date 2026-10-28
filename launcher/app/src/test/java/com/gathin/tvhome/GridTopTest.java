package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** grid_top: the plain grid's gap under the header, and the line a row scrolling back up stops at. */
public class GridTopTest {
    private static final float D = 2f;              // 1080p TV: 2 px per dp
    private static final int GRID_H = 846;          // grid height under the top bar
    private static final int LIMIT_BOTTOM = GRID_H - 80;   // activity_main.xml's 40dp bottom padding

    @Test public void zeroKeepsTodaysPaddingAndKeyline() {
        for (float density : new float[] {1f, 1.33f, 1.5f, 2f, 3f}) {
            int padding = AppAdapter.plainGridTopPx(0, density);
            assertEquals((int) (AppAdapter.TOP_ROOM_DP * density), padding);
            assertEquals(0, AppAdapter.plainGridKeylinePx(padding, density));
        }
    }

    @Test public void theGapIsAddedOnTopOfTheZoomRoom() {
        assertEquals(104, AppAdapter.plainGridTopPx(40, D));     // (12 + 40) dp
        assertEquals(80, AppAdapter.plainGridKeylinePx(104, D)); // the 40dp gap itself
        assertEquals(0, AppAdapter.plainGridKeylinePx(0, D));    // never above the grid's edge
    }

    @Test public void withoutAGapTheScrollIsTheOldRule() {
        assertEquals(-50, GridNavigation.verticalScroll(-50, 150, GRID_H, 0, LIMIT_BOTTOM));
        assertEquals(0, GridNavigation.verticalScroll(300, 700, GRID_H, 0, LIMIT_BOTTOM));
        assertEquals(0, GridNavigation.verticalScroll(500, 800, GRID_H, 0, LIMIT_BOTTOM));   // inside the bottom padding
        assertEquals(134, GridNavigation.verticalScroll(600, 900, GRID_H, 0, LIMIT_BOTTOM)); // stops at the padding line
    }

    @Test public void theFirstRowStartsOnTheKeylineAndStays() {
        int padding = AppAdapter.plainGridTopPx(40, D);
        int keyline = AppAdapter.plainGridKeylinePx(padding, D);
        // onTileFocused asks for the tile plus the glass, 2 x 6dp above it.
        int glassTop = padding - 2 * (int) (6 * D);
        assertEquals(0, GridNavigation.verticalScroll(glassTop, glassTop + 200, GRID_H, keyline, LIMIT_BOTTOM));
    }

    @Test public void upBackToTheFirstRowReturnsItToItsPaddedPlace() {
        int keyline = AppAdapter.plainGridKeylinePx(AppAdapter.plainGridTopPx(40, D), D);
        int top = -120;   // scrolled up past the screen's top
        int dy = GridNavigation.verticalScroll(top, top + 200, GRID_H, keyline, LIMIT_BOTTOM);
        assertEquals(keyline, top - dy);
        // A row left inside the gap comes down to it too, and one on the keyline stays.
        assertEquals(-50, GridNavigation.verticalScroll(30, 230, GRID_H, keyline, LIMIT_BOTTOM));
        assertEquals(0, GridNavigation.verticalScroll(keyline, keyline + 200, GRID_H, keyline, LIMIT_BOTTOM));
    }

    @Test public void aRowFromBelowStillStopsAtTheBottomPaddingLine() {
        assertEquals(134, GridNavigation.verticalScroll(700, 900, GRID_H, 80, LIMIT_BOTTOM));
    }
}
