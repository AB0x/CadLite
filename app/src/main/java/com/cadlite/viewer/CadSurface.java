package com.cadlite.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;

import com.cadlite.viewer.core.Drawing;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/** GPU-accelerated viewer: OpenGL ES layer for geometry + a light text layer on top. */
final class CadSurface extends FrameLayout implements CadDisplay {

    /** While panning, texts are hidden if more than this many are readable on screen. */
    private static final int TEXT_BUDGET_MOVING = 1500;

    private final Viewport vp = new Viewport();
    private final GLSurfaceView gl;
    private final GlRenderer renderer;
    private final TextLayer textLayer;
    private Drawing drawing;
    private boolean dark = true, needFit = true, interacting;
    private volatile long frames;

    private final ScaleGestureDetector scaleDet;
    private final GestureDetector gestDet;

    CadSurface(Context c) {
        super(c);
        renderer = new GlRenderer(vp);
        gl = new GLSurfaceView(c);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(new ConfigChooser());
        gl.setPreserveEGLContextOnPause(true);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        addView(gl);
        textLayer = new TextLayer(c);
        addView(textLayer);

        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestDet = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                pan(-dx, -dy);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomAt(2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ CadDisplay
    @Override public View view() { return this; }

    @Override public void setDrawing(final Drawing d) {
        drawing = d;
        renderer.setDrawing(d);
        needFit = true;
        if (getWidth() > 0) zoomExtents();
        redraw();
    }

    @Override public Drawing getDrawing() { return drawing; }

    @Override public void zoomExtents() {
        Drawing d = drawing;
        if (d == null || getWidth() == 0) { needFit = true; return; }
        float w = getWidth(), h = getHeight();
        float bw = Math.max(d.maxX - d.minX, 1e-9f), bh = Math.max(d.maxY - d.minY, 1e-9f);
        float s = Math.min(w / bw, h / bh) * 0.94f;
        float cx = (d.minX + d.maxX) / 2, cy = (d.minY + d.maxY) / 2;
        vp.set(s, w / 2 - cx * s, h / 2 + cy * s);
        needFit = false;
        redraw();
    }

    @Override public void setDark(boolean d) { dark = d; renderer.setDark(d); redraw(); }
    @Override public boolean isDark() { return dark; }
    @Override public void refresh() { redraw(); }

    @Override public void zoomAt(float f, float fx, float fy) { if (vp.zoomAt(f, fx, fy)) redraw(); }
    @Override public void pan(float dx, float dy) { vp.pan(dx, dy); redraw(); }

    /** GPU frames rendered. */
    @Override public long framesRendered() { return renderer.frames; }

    /** Text-layer (UI thread) frames drawn. */
    long uiFrames() { return frames; }

    @Override public void setInteracting(boolean on) { interacting = on; if (!on) textLayer.invalidate(); }

    void setFrameListener(GlRenderer.FrameListener l) { renderer.frameListener = l; }

    private void redraw() {
        gl.requestRender();
        textLayer.invalidate();
    }

    // ------------------------------------------------------------------ touch
    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) interacting = true;
        scaleDet.onTouchEvent(e);
        gestDet.onTouchEvent(e);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) setInteracting(false);
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (needFit || ow == 0) zoomExtents();
        else { vp.pan((w - ow) / 2f, (h - oh) / 2f); redraw(); }
    }

    void onPause() { gl.onPause(); }
    void onResume() { gl.onResume(); redraw(); }

    // ------------------------------------------------------------------ text layer
    private final class TextLayer extends View {
        private final TextPainter painter = new TextPainter();
        private final float[] st = new float[3];

        TextLayer(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            frames++;
            Drawing d = drawing;
            if (d == null || d.texts.isEmpty()) return;
            vp.get(st);
            painter.draw(c, d, st[0], st[1], st[2], dark, interacting ? TEXT_BUDGET_MOVING : Integer.MAX_VALUE);
        }
    }

    // ------------------------------------------------------------------ EGL config
    /** Prefers 4x MSAA (smooth lines) with a stencil buffer; falls back gracefully. */
    private static final class ConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES2_BIT = 4;

        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[][] attempts = {
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                            EGL10.EGL_STENCIL_SIZE, 8, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4,
                            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE},
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                            EGL10.EGL_STENCIL_SIZE, 8, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE},
                    {EGL10.EGL_STENCIL_SIZE, 1, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE},
                    {EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE},
            };
            for (int[] attr : attempts) {
                int[] num = new int[1];
                EGLConfig[] cfgs = new EGLConfig[1];
                if (egl.eglChooseConfig(display, attr, cfgs, 1, num) && num[0] > 0) return cfgs[0];
            }
            throw new IllegalStateException("No OpenGL ES 2 configuration available");
        }
    }
}
