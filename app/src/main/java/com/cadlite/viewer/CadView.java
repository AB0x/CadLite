package com.cadlite.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.cadlite.viewer.core.Drawing;
import com.cadlite.viewer.core.Geo;

import java.util.ArrayList;
import java.util.List;

/** Fast hardware-accelerated CAD canvas: pan, pinch-zoom, double-tap zoom. */
public class CadView extends View {

    private Drawing drawing;
    private Path[] fillPaths;
    private final Paint line = new Paint();
    private final Paint fill = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dark = true;
    private boolean touching;

    // world -> screen: sx = x*scale + tx ; sy = -y*scale + ty
    private float scale = 1, tx, ty;
    private boolean needFit = true;

    private final ScaleGestureDetector scaleDet;
    private final GestureDetector gestDet;

    public CadView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(0); // hairline: always 1px, fastest path
        fill.setStyle(Paint.Style.FILL);
        text.setSubpixelText(true);

        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestDet = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                tx -= dx; ty -= dy;
                invalidate();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomAt(2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setDrawing(Drawing d) {
        drawing = d;
        fillPaths = null;
        if (d != null) {
            fillPaths = new Path[d.fills.size()];
            for (int i = 0; i < fillPaths.length; i++) {
                Path p = new Path();
                p.setFillType(Path.FillType.EVEN_ODD);
                for (float[] lp : d.fills.get(i).loops) {
                    if (lp.length < 4) continue;
                    p.moveTo(lp[0], lp[1]);
                    for (int k = 2; k + 1 < lp.length; k += 2) p.lineTo(lp[k], lp[k + 1]);
                    p.close();
                }
                fillPaths[i] = p;
            }
        }
        needFit = true;
        invalidate();
    }

    public Drawing getDrawing() { return drawing; }

    public void setDark(boolean d) { dark = d; invalidate(); }
    public boolean isDark() { return dark; }

    public void zoomExtents() {
        if (drawing == null || getWidth() == 0) { needFit = true; return; }
        float w = getWidth(), h = getHeight();
        float bw = Math.max(drawing.maxX - drawing.minX, 1e-9f), bh = Math.max(drawing.maxY - drawing.minY, 1e-9f);
        scale = Math.min(w / bw, h / bh) * 0.94f;
        float cx = (drawing.minX + drawing.maxX) / 2, cy = (drawing.minY + drawing.maxY) / 2;
        tx = w / 2 - cx * scale;
        ty = h / 2 + cy * scale;
        needFit = false;
        invalidate();
    }

    private void zoomAt(float f, float fx, float fy) {
        float ns = scale * f;
        if (ns > 1e9f || ns < 1e-12f) return;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = ns;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (needFit || ow == 0) { zoomExtents(); return; }
        // keep the same center when rotating
        tx += (w - ow) / 2f; ty += (h - oh) / 2f;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) touching = true;
        scaleDet.onTouchEvent(e);
        gestDet.onTouchEvent(e);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { touching = false; invalidate(); }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(dark ? 0xFF1E1E1E : 0xFFFFFFFF);
        Drawing d = drawing;
        if (d == null) return;
        if (needFit) zoomExtents();
        float w = getWidth(), h = getHeight();
        // visible world rectangle
        float vx0 = -tx / scale, vx1 = (w - tx) / scale;
        float vy0 = (ty - h) / scale, vy1 = ty / scale;
        boolean[] vis = d.layerVisible;

        c.save();
        c.translate(tx, ty);
        c.scale(scale, -scale);
        for (int i = 0; i < d.fills.size(); i++) {
            Geo.Fill f = d.fills.get(i);
            if (!vis[f.layer]) continue;
            fill.setColor(d.resolve(f.color, f.layer, dark));
            c.drawPath(fillPaths[i], fill);
        }
        for (Drawing.Batch b : d.batches) {
            if (!vis[b.layer]) continue;
            if (b.maxX < vx0 || b.minX > vx1 || b.maxY < vy0 || b.minY > vy1) continue;
            line.setColor(d.resolve(b.color, b.layer, dark));
            c.drawLines(b.pts, 0, b.count, line);
        }
        c.restore();

        // Texts in screen space. Skipped while panning very dense drawings to stay smooth.
        if (touching && d.texts.size() > 3000) return;
        for (Geo.Text t : d.texts) {
            if (!vis[t.layer]) continue;
            float size = (float) (t.h * scale * 1.38);
            if (size < 3.5f) continue;
            float sx = (float) (t.x * scale + tx), sy = (float) (-t.y * scale + ty);
            float reach = size * 1.2f * (maxLen(t) + t.lines.length);
            if (sx + reach < 0 || sx - reach > w || sy + reach < 0 || sy - reach > h) continue;
            text.setTextSize(Math.min(size, 4000));
            text.setColor(d.resolve(t.color, t.layer, dark));
            text.setTextAlign(t.hAlign == 1 ? Paint.Align.CENTER : t.hAlign == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
            float capH = size * 0.72f, lineStep = size * 1.2f;
            float first; // baseline of first line relative to anchor (screen y grows downward)
            if (t.multi) {
                float total = capH + lineStep * (t.lines.length - 1);
                first = t.vAlign == 3 ? capH : t.vAlign == 2 ? capH - total / 2 : capH - total;
            } else {
                first = t.vAlign == 3 ? capH : t.vAlign == 2 ? capH / 2 : 0;
            }
            c.save();
            c.translate(sx, sy);
            c.rotate((float) -Math.toDegrees(t.rot));
            if (t.widthFactor != 1) c.scale((float) t.widthFactor, 1);
            for (int i = 0; i < t.lines.length; i++) c.drawText(t.lines[i], 0, first + i * lineStep, text);
            c.restore();
        }
    }

    private static int maxLen(Geo.Text t) {
        int m = 0;
        for (String s : t.lines) m = Math.max(m, s.length());
        return m;
    }

    /** Layer names sorted as in file, used by the layer dialog. */
    public List<String> layerNames() {
        return drawing == null ? new ArrayList<String>() : drawing.layerNames;
    }
}
