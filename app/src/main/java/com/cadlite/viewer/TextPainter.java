package com.cadlite.viewer;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.cadlite.viewer.core.Drawing;
import com.cadlite.viewer.core.Geo;

/** Draws drawing texts in screen space, skipping texts that are off screen or too small to read. */
final class TextPainter {
    /** True if, at this scale, at least one text of the drawing could be large enough to draw. */
    static boolean anyReadable(Drawing d, float scale) {
        return d != null && d.maxTextHeight * scale * 1.38 >= MIN_PX;
    }

    /** Below this on-screen size (px) a text is not drawn. */
    static final float MIN_PX = 4f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] visible = new int[256];
    /** Number of texts drawn by the last call. */
    int lastDrawn;

    TextPainter() {
        paint.setSubpixelText(true);
    }

    /**
     * Draws visible texts. If more than {@code budget} texts are readable on screen, nothing is
     * drawn and false is returned (used to keep panning smooth on text-dense views).
     */
    boolean draw(Canvas c, Drawing d, float scale, float tx, float ty, boolean dark, int budget) {
        lastDrawn = 0;
        if (d == null) return true;
        float w = c.getWidth(), h = c.getHeight();
        boolean[] vis = d.layerVisible;
        int n = 0;
        java.util.List<Geo.Text> texts = d.texts;
        for (int i = 0, cnt = texts.size(); i < cnt; i++) {
            Geo.Text t = texts.get(i);
            float size = (float) (t.h * scale * 1.38);
            if (size < MIN_PX || !vis[t.layer]) continue;
            float sx = (float) (t.x * scale + tx), sy = (float) (-t.y * scale + ty);
            float reach = size * 1.2f * (t.maxLen + t.lines.length);
            if (sx + reach < 0 || sx - reach > w || sy + reach < 0 || sy - reach > h) continue;
            if (n == visible.length) visible = java.util.Arrays.copyOf(visible, n * 2);
            visible[n++] = i;
            if (n > budget) return false;
        }
        lastDrawn = n;
        for (int k = 0; k < n; k++) {
            Geo.Text t = texts.get(visible[k]);
            float size = (float) (t.h * scale * 1.38);
            float sx = (float) (t.x * scale + tx), sy = (float) (-t.y * scale + ty);
            paint.setTextSize(Math.min(size, 4000));
            paint.setColor(d.resolve(t.color, t.layer, dark));
            paint.setTextAlign(t.hAlign == 1 ? Paint.Align.CENTER : t.hAlign == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
            float capH = size * 0.72f, lineStep = size * 1.2f;
            float first;
            if (t.multi) {
                float total = capH + lineStep * (t.lines.length - 1);
                first = t.vAlign == 3 ? capH : t.vAlign == 2 ? capH - total / 2 : capH - total;
            } else {
                first = t.vAlign == 3 ? capH : t.vAlign == 2 ? capH / 2 : 0;
            }
            c.save();
            c.translate(sx, sy);
            if (t.rot != 0) c.rotate((float) -Math.toDegrees(t.rot));
            if (t.widthFactor != 1) c.scale((float) t.widthFactor, 1);
            for (int i = 0; i < t.lines.length; i++) c.drawText(t.lines[i], 0, first + i * lineStep, paint);
            c.restore();
        }
        return true;
    }
}
