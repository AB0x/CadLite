package com.cadlite.viewer;

/**
 * World-to-screen transform shared by the GPU renderer (GL thread) and the text layer (UI thread).
 * screenX = x * scale + tx ; screenY = -y * scale + ty
 */
final class Viewport {
    private float scale = 1, tx, ty;

    synchronized void set(float s, float x, float y) { scale = s; tx = x; ty = y; }

    synchronized void pan(float dx, float dy) { tx += dx; ty += dy; }

    synchronized boolean zoomAt(float f, float fx, float fy) {
        float ns = scale * f;
        if (ns > 1e9f || ns < 1e-12f) return false;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = ns;
        return true;
    }

    /** Copies scale, tx, ty into out[0..2]. */
    synchronized void get(float[] out) { out[0] = scale; out[1] = tx; out[2] = ty; }
}
