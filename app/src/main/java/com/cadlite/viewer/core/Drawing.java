package com.cadlite.viewer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Render-ready drawing: chunked segment batches with bounding boxes, texts, fills, layers. */
public final class Drawing {

    public static final class Batch {
        public int layer, color;
        public float[] pts; // x1,y1,x2,y2,...
        public int count;   // number of floats used
        public float minX, minY, maxX, maxY;
    }

    public final List<String> layerNames;
    public final int[] layerColor;
    public final boolean[] layerVisible;
    public final List<Batch> batches = new ArrayList<>();
    public final List<Geo.Text> texts;
    public final List<Geo.Fill> fills;
    public float minX, minY, maxX, maxY; // zoom-extents box
    public long segmentCount;
    public String version = "";

    private static final int CHUNK = 2048 * 4;

    Drawing(Geo g, List<String> names, int[] colors, boolean[] visible) {
        layerNames = names;
        layerColor = colors;
        layerVisible = visible;
        texts = g.texts;
        fills = g.fills;
        for (Geo.Text t : texts) {
            int m = 0;
            for (String l : t.lines) m = Math.max(m, l.length());
            t.maxLen = m;
        }
        for (Map.Entry<Long, Geo.FloatList> en : g.segs.entrySet()) {
            long k = en.getKey();
            Geo.FloatList l = en.getValue();
            if (l.n > CHUNK) spatialSort(l);
            for (int off = 0; off < l.n; off += CHUNK) {
                int len = Math.min(CHUNK, l.n - off);
                Batch b = new Batch();
                b.layer = (int) (k >>> 32);
                b.color = (int) k;
                b.pts = Arrays.copyOfRange(l.a, off, off + len);
                b.count = len;
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                for (int i = 0; i < len; i += 2) {
                    float x = b.pts[i], y = b.pts[i + 1];
                    if (x < x0) x0 = x; if (x > x1) x1 = x;
                    if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
                b.minX = x0; b.minY = y0; b.maxX = x1; b.maxY = y1;
                batches.add(b);
                segmentCount += len / 4;
            }
            l.a = null; // free
        }
    }

    /**
     * Reorders segments along a Z-order (Morton) curve of their midpoints so that each chunk
     * covers a compact area. Off-screen chunks can then be skipped when zoomed in.
     */
    private static void spatialSort(Geo.FloatList l) {
        int n = l.n / 4;
        float[] a = l.a;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < l.n; i += 2) {
            x0 = Math.min(x0, a[i]); x1 = Math.max(x1, a[i]);
            y0 = Math.min(y0, a[i + 1]); y1 = Math.max(y1, a[i + 1]);
        }
        float sx = 32767f / Math.max(x1 - x0, 1e-9f), sy = 32767f / Math.max(y1 - y0, 1e-9f); // 15-bit keeps the key positive
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            int k = i * 4;
            int qx = (int) (((a[k] + a[k + 2]) * 0.5f - x0) * sx), qy = (int) (((a[k + 1] + a[k + 3]) * 0.5f - y0) * sy);
            keys[i] = (morton(qx & 0x7FFF, qy & 0x7FFF) << 32) | i;
        }
        Arrays.sort(keys);
        float[] b = new float[l.n];
        for (int i = 0; i < n; i++) System.arraycopy(a, (int) keys[i] * 4, b, i * 4, 4);
        l.a = b;
    }

    private static long morton(int x, int y) {
        return spread(x) | (spread(y) << 1);
    }

    private static long spread(int v) {
        long x = v & 0xFFFFL;
        x = (x | (x << 8)) & 0x00FF00FFL;
        x = (x | (x << 4)) & 0x0F0F0F0FL;
        x = (x | (x << 2)) & 0x33333333L;
        x = (x | (x << 1)) & 0x55555555L;
        return x;
    }

    /**
     * Zoom extents from per-object boxes, ignoring a few far-away stray objects
     * (common in real drawings) that would otherwise shrink the drawing to a dot.
     */
    void setExtents(List<float[]> boxes) {
        int n = boxes.size();
        if (n == 0) { minX = minY = 0; maxX = maxY = 1; return; }
        float[] cx = new float[n], cy = new float[n];
        for (int i = 0; i < n; i++) {
            float[] b = boxes.get(i);
            cx[i] = (b[0] + b[2]) / 2; cy[i] = (b[1] + b[3]) / 2;
        }
        float wx0 = -Float.MAX_VALUE, wx1 = Float.MAX_VALUE, wy0 = -Float.MAX_VALUE, wy1 = Float.MAX_VALUE;
        if (n >= 10) {
            float[] sx = cx.clone(), sy = cy.clone();
            Arrays.sort(sx); Arrays.sort(sy);
            int lo = (int) (n * 0.01), hi = n - 1 - lo;
            float dx = Math.max(sx[hi] - sx[lo], 1e-6f), dy = Math.max(sy[hi] - sy[lo], 1e-6f);
            float d = Math.max(dx, dy);
            wx0 = sx[lo] - d; wx1 = sx[hi] + d; wy0 = sy[lo] - d; wy1 = sy[hi] + d;
        }
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (cx[i] < wx0 || cx[i] > wx1 || cy[i] < wy0 || cy[i] > wy1) continue;
            float[] b = boxes.get(i);
            x0 = Math.min(x0, b[0]); y0 = Math.min(y0, b[1]);
            x1 = Math.max(x1, b[2]); y1 = Math.max(y1, b[3]);
        }
        if (x1 < x0) return;
        if (x1 - x0 < 1e-6f) { x0 -= 1; x1 += 1; }
        if (y1 - y0 < 1e-6f) { y0 -= 1; y1 += 1; }
        minX = x0; minY = y0; maxX = x1; maxY = y1;
    }

    /** Resolves a color spec to ARGB for the given background. */
    public int resolve(int spec, int layer, boolean darkBg) {
        if (spec == Aci.BYLAYER) spec = layer >= 0 && layer < layerColor.length ? layerColor[layer] : Aci.FG;
        if (spec == Aci.BYBLOCK || spec == Aci.FG || spec == Aci.BYLAYER) return darkBg ? 0xFFFFFFFF : 0xFF000000;
        if (!darkBg) {
            int r = (spec >> 16) & 255, g = (spec >> 8) & 255, b = spec & 255;
            if (r * 0.299 + g * 0.587 + b * 0.114 > 200) return 0xFF000000;
        } else if ((spec & 0xFFFFFF) == 0) {
            return 0xFFFFFFFF;
        }
        return spec;
    }
}
