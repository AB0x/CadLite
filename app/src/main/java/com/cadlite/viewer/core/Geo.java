package com.cadlite.viewer.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tessellated geometry bucket: line segments grouped by (layer, color spec),
 * texts and solid fills. Used both for block definitions (local coords) and the
 * final model-space drawing.
 */
public final class Geo {

    public static final class FloatList {
        public float[] a = new float[64];
        public int n;
        Geo owner;
        void add4(float x1, float y1, float x2, float y2) {
            if (owner != null && owner.track) owner.box(x1, y1, x2, y2);
            if (n + 4 > a.length) {
                float[] b = new float[Math.max(a.length * 2, n + 4)];
                System.arraycopy(a, 0, b, 0, n);
                a = b;
            }
            a[n++] = x1; a[n++] = y1; a[n++] = x2; a[n++] = y2;
        }
    }

    public static final class Text {
        public double x, y, h, rot, widthFactor = 1;
        public String[] lines;
        /** 0 left, 1 center, 2 right */
        public int hAlign;
        /** 0 baseline, 1 bottom, 2 middle, 3 top */
        public int vAlign;
        public boolean multi; // MTEXT: vAlign refers to whole text block
        public int maxLen;    // longest line length (set when the drawing is finalized)
        public int layer, color;

        Text copy() {
            Text t = new Text();
            t.x = x; t.y = y; t.h = h; t.rot = rot; t.widthFactor = widthFactor; t.lines = lines;
            t.hAlign = hAlign; t.vAlign = vAlign; t.multi = multi; t.layer = layer; t.color = color;
            return t;
        }
    }

    public static final class Fill {
        public float[][] loops; // each loop: x0,y0,x1,y1,...
        public int layer, color;
    }

    public final Map<Long, FloatList> segs = new HashMap<>();
    public final List<Text> texts = new ArrayList<>();
    public final List<Fill> fills = new ArrayList<>();

    static long key(int layer, int color) {
        return ((long) layer << 32) | (color & 0xFFFFFFFFL);
    }

    FloatList list(int layer, int color) {
        long k = key(layer, color);
        FloatList l = segs.get(k);
        if (l == null) { l = new FloatList(); l.owner = this; segs.put(k, l); }
        return l;
    }

    /** Copies src into this, transformed by m, resolving layer "0" and BYBLOCK inheritance. */
    void instance(Geo src, Mat m, int insLayer, int insColor) {
        for (Map.Entry<Long, FloatList> en : src.segs.entrySet()) {
            long k = en.getKey();
            int layer = (int) (k >>> 32), color = (int) k;
            if (layer == 0) layer = insLayer;
            if (color == Aci.BYBLOCK) color = insColor;
            FloatList s = en.getValue(), d = list(layer, color);
            float[] a = s.a;
            for (int i = 0; i < s.n; i += 4) {
                double x1 = a[i], y1 = a[i + 1], x2 = a[i + 2], y2 = a[i + 3];
                d.add4((float) m.x(x1, y1), (float) m.y(x1, y1), (float) m.x(x2, y2), (float) m.y(x2, y2));
            }
        }
        double sc = m.uniformScale(), rot = m.rotation();
        for (Text t : src.texts) {
            Text c = t.copy();
            c.x = m.x(t.x, t.y); c.y = m.y(t.x, t.y);
            c.h = t.h * sc; c.rot = t.rot + rot;
            if (c.layer == 0) c.layer = insLayer;
            if (c.color == Aci.BYBLOCK) c.color = insColor;
            texts.add(c);
        }
        for (Fill f : src.fills) {
            Fill c = new Fill();
            c.layer = f.layer == 0 ? insLayer : f.layer;
            c.color = f.color == Aci.BYBLOCK ? insColor : f.color;
            c.loops = new float[f.loops.length][];
            for (int i = 0; i < f.loops.length; i++) {
                float[] s = f.loops[i], d = new float[s.length];
                for (int j = 0; j < s.length; j += 2) {
                    d[j] = (float) m.x(s[j], s[j + 1]);
                    d[j + 1] = (float) m.y(s[j], s[j + 1]);
                }
                c.loops[i] = d;
            }
            fills.add(c);
        }
    }

    // bounding box tracking of what is being added (used for per-object extents)
    boolean track;
    float bx0, by0, bx1, by1;

    void resetBox() { bx0 = by0 = Float.MAX_VALUE; bx1 = by1 = -Float.MAX_VALUE; }

    void box(float x1, float y1, float x2, float y2) {
        if (x1 < bx0) bx0 = x1; if (x1 > bx1) bx1 = x1;
        if (y1 < by0) by0 = y1; if (y1 > by1) by1 = y1;
        if (x2 < bx0) bx0 = x2; if (x2 > bx1) bx1 = x2;
        if (y2 < by0) by0 = y2; if (y2 > by1) by1 = y2;
    }

    public boolean isEmpty() {
        return segs.isEmpty() && texts.isEmpty() && fills.isEmpty();
    }
}
