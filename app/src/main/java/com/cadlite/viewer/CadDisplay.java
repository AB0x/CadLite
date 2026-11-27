package com.cadlite.viewer;

import android.view.View;

import com.cadlite.viewer.core.Drawing;

/** A drawing viewer surface (GPU renderer, or the legacy Canvas renderer used for comparison). */
interface CadDisplay {
    View view();
    void setDrawing(Drawing d);
    Drawing getDrawing();
    void zoomExtents();
    void setDark(boolean dark);
    boolean isDark();
    /** Redraw after layer visibility changes. */
    void refresh();
    void zoomAt(float factor, float fx, float fy);
    void pan(float dx, float dy);
    /** Number of frames rendered so far (for benchmarks). */
    long framesRendered();
    /** Marks a programmatic gesture as active (text layer behaves as during touch). */
    void setInteracting(boolean on);
}
