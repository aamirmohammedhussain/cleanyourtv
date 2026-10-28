package com.gathin.tvhome;

/**
 * Physical left/right boundaries for a grid whose first column follows its reading direction, and how far the
 * vertical grid scrolls to show a tile.
 */
final class GridNavigation {
    private GridNavigation() { }

    static boolean atHorizontalEdge(int position, int count, int columns, boolean rtl, boolean left) {
        boolean towardStart = left != rtl;
        if (towardStart) return position % columns == 0;
        return position % columns == columns - 1 || position == count - 1;
    }

    /**
     * The scroll (positive moves the rows up) that shows the span top..bottom of a grid {@code height} tall. A span
     * above {@code limitTop} comes down to it; one already on screen stays put, even inside the bottom padding; one
     * running off the bottom stops at {@code limitBottom}. limitTop is 0, the grid's top edge, except in a plain grid
     * with grid_top, where it is that gap: Up back to the first row returns it to its padded place, not 12dp.
     */
    static int verticalScroll(int top, int bottom, int height, int limitTop, int limitBottom) {
        if (top < limitTop) return top - limitTop;
        if (bottom <= height) return 0;
        return Math.min(top - limitTop, bottom - limitBottom);
    }
}
