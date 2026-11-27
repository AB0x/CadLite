package com.cadlite.viewer;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import com.cadlite.viewer.core.Drawing;
import com.cadlite.viewer.core.Geo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GPU renderer. All geometry is uploaded once into vertex buffers; each frame only sends a
 * 4-float transform and issues one draw call per (layer, color) chunk. Pan/zoom cost is
 * therefore independent of how much the CPU had to tessellate.
 */
final class GlRenderer implements GLSurfaceView.Renderer {

    interface FrameListener { void onFrame(long nanos); }

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "uniform vec4 uXf;\n" +
            "void main(){ gl_Position = vec4(aPos.x*uXf.x+uXf.z, aPos.y*uXf.y+uXf.w, 0.0, 1.0); }\n";
    private static final String FS =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main(){ gl_FragColor = uColor; }\n";

    private static final int CHUNK_TRI_FLOATS = 6 * 4096;

    /** A contiguous range of vertices drawn with one color. */
    private static final class Range {
        int layer, color, first, count;
        float minX, minY, maxX, maxY;
    }

    /** Non-convex or holed fill drawn with the stencil even-odd technique. */
    private static final class StencilFill {
        int layer, color;
        int[] loopFirst, loopCount;
        int quadFirst;
        float minX, minY, maxX, maxY;
    }

    private final Viewport vp;
    private volatile Drawing pending;
    private volatile boolean dark = true;
    volatile FrameListener frameListener;
    volatile long frames;

    private Drawing scene;
    private boolean uploaded;
    private int prog, aPos, uXf, uColor;
    private final int[] vbo = new int[3]; // lines, triangles, stencil fans
    private final List<Range> lines = new ArrayList<>(), tris = new ArrayList<>();
    private final List<StencilFill> sfills = new ArrayList<>();
    private int width = 1, height = 1;
    private final float[] st = new float[3];

    GlRenderer(Viewport vp) { this.vp = vp; }

    void setDrawing(Drawing d) { pending = d; }
    void setDark(boolean d) { dark = d; }

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig cfg) {
        prog = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS));
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        uXf = GLES20.glGetUniformLocation(prog, "uXf");
        uColor = GLES20.glGetUniformLocation(prog, "uColor");
        GLES20.glGenBuffers(3, vbo, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_DITHER);
        uploaded = false; // context is new: buffers must be re-uploaded
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long t0 = System.nanoTime();
        Drawing p = pending;
        if (p != null) { pending = null; scene = p; uploaded = false; }
        if (scene != null && !uploaded) { upload(scene); uploaded = true; }

        boolean dk = dark;
        if (dk) GLES20.glClearColor(0x1E / 255f, 0x1E / 255f, 0x1E / 255f, 1);
        else GLES20.glClearColor(1, 1, 1, 1);
        GLES20.glClearStencil(0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_STENCIL_BUFFER_BIT);
        Drawing d = scene;
        if (d != null) {
            vp.get(st);
            float s = st[0], tx = st[1], ty = st[2];
            GLES20.glUseProgram(prog);
            GLES20.glUniform4f(uXf, 2 * s / width, 2 * s / height, 2 * tx / width - 1, 1 - 2 * ty / height);
            // visible world rectangle
            float vx0 = -tx / s, vx1 = (width - tx) / s, vy0 = (ty - height) / s, vy1 = ty / s;
            float px = 1 / s; // one pixel in world units
            boolean[] vis = d.layerVisible;

            GLES20.glEnableVertexAttribArray(aPos);
            // 1. convex fills (batched triangles)
            bind(vbo[1]);
            for (Range r : tris) {
                if (!vis[r.layer] || out(r, vx0, vy0, vx1, vy1)) continue;
                color(d.resolve(r.color, r.layer, dk));
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, r.first, r.count);
            }
            // 2. other fills via stencil even-odd
            if (!sfills.isEmpty()) {
                bind(vbo[2]);
                GLES20.glEnable(GLES20.GL_STENCIL_TEST);
                GLES20.glStencilMask(1);
                for (StencilFill f : sfills) {
                    if (!vis[f.layer] || f.maxX < vx0 || f.minX > vx1 || f.maxY < vy0 || f.minY > vy1) continue;
                    if (f.maxX - f.minX < px && f.maxY - f.minY < px) continue; // sub-pixel
                    GLES20.glColorMask(false, false, false, false);
                    GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 1);
                    GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INVERT);
                    for (int i = 0; i < f.loopFirst.length; i++)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, f.loopFirst[i], f.loopCount[i]);
                    GLES20.glColorMask(true, true, true, true);
                    GLES20.glStencilFunc(GLES20.GL_EQUAL, 1, 1);
                    GLES20.glStencilOp(GLES20.GL_ZERO, GLES20.GL_ZERO, GLES20.GL_ZERO);
                    color(d.resolve(f.color, f.layer, dk));
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, f.quadFirst, 4);
                }
                GLES20.glDisable(GLES20.GL_STENCIL_TEST);
            }
            // 3. lines
            bind(vbo[0]);
            for (Range r : lines) {
                if (!vis[r.layer] || out(r, vx0, vy0, vx1, vy1)) continue;
                color(d.resolve(r.color, r.layer, dk));
                GLES20.glDrawArrays(GLES20.GL_LINES, r.first, r.count);
            }
            GLES20.glDisableVertexAttribArray(aPos);
        }
        frames++;
        FrameListener fl = frameListener;
        if (fl != null) { GLES20.glFinish(); fl.onFrame(System.nanoTime() - t0); }
    }

    private static boolean out(Range r, float x0, float y0, float x1, float y1) {
        return r.maxX < x0 || r.minX > x1 || r.maxY < y0 || r.minY > y1;
    }

    private void bind(int buf) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buf);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, 0);
    }

    private void color(int argb) {
        GLES20.glUniform4f(uColor, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, 1f);
    }

    // ------------------------------------------------------------------ upload
    private void upload(Drawing d) {
        lines.clear(); tris.clear(); sfills.clear();

        // Lines: Drawing batches are already chunked by (layer, color) with bounding boxes.
        int total = 0;
        for (Drawing.Batch b : d.batches) total += b.count;
        FloatBuffer fb = direct(total);
        for (Drawing.Batch b : d.batches) {
            Range r = new Range();
            r.layer = b.layer; r.color = b.color;
            r.first = fb.position() / 2; r.count = b.count / 2;
            r.minX = b.minX; r.minY = b.minY; r.maxX = b.maxX; r.maxY = b.maxY;
            fb.put(b.pts, 0, b.count);
            lines.add(r);
        }
        put(vbo[0], fb);

        // Fills: convex single loops become triangles batched per color; the rest use the stencil.
        Map<Long, List<float[]>> convex = new HashMap<>();
        List<Geo.Fill> other = new ArrayList<>();
        for (Geo.Fill f : d.fills) {
            if (f.loops.length == 1 && f.loops[0].length >= 6 && isConvex(f.loops[0])) {
                long k = ((long) f.layer << 32) | (f.color & 0xFFFFFFFFL);
                List<float[]> l = convex.get(k);
                if (l == null) { l = new ArrayList<>(); convex.put(k, l); }
                l.add(f.loops[0]);
            } else {
                other.add(f);
            }
        }
        int triFloats = 0;
        for (List<float[]> l : convex.values()) for (float[] p : l) triFloats += (p.length / 2 - 2) * 6;
        fb = direct(triFloats);
        for (Map.Entry<Long, List<float[]>> e : convex.entrySet()) {
            int layer = (int) (e.getKey() >>> 32), color = (int) (long) e.getKey();
            Range r = null;
            for (float[] p : e.getValue()) {
                if (r == null || (fb.position() / 2 - r.first) * 2 > CHUNK_TRI_FLOATS) {
                    r = newRange(layer, color, fb.position() / 2);
                    tris.add(r);
                }
                int n = p.length / 2;
                for (int i = 1; i + 1 < n; i++) {
                    fb.put(p[0]).put(p[1]).put(p[i * 2]).put(p[i * 2 + 1]).put(p[i * 2 + 2]).put(p[i * 2 + 3]);
                }
                grow(r, p);
                r.count = fb.position() / 2 - r.first;
            }
        }
        put(vbo[1], fb);

        int sf = 0;
        for (Geo.Fill f : other) { for (float[] lp : f.loops) sf += lp.length; sf += 8; }
        fb = direct(sf);
        for (Geo.Fill f : other) {
            StencilFill s = new StencilFill();
            s.layer = f.layer; s.color = f.color;
            s.loopFirst = new int[f.loops.length];
            s.loopCount = new int[f.loops.length];
            s.minX = s.minY = Float.MAX_VALUE; s.maxX = s.maxY = -Float.MAX_VALUE;
            for (int i = 0; i < f.loops.length; i++) {
                float[] lp = f.loops[i];
                s.loopFirst[i] = fb.position() / 2;
                s.loopCount[i] = lp.length / 2;
                fb.put(lp);
                for (int k = 0; k + 1 < lp.length; k += 2) {
                    s.minX = Math.min(s.minX, lp[k]); s.maxX = Math.max(s.maxX, lp[k]);
                    s.minY = Math.min(s.minY, lp[k + 1]); s.maxY = Math.max(s.maxY, lp[k + 1]);
                }
            }
            if (s.maxX < s.minX) continue;
            s.quadFirst = fb.position() / 2;
            fb.put(s.minX).put(s.minY).put(s.maxX).put(s.minY).put(s.maxX).put(s.maxY).put(s.minX).put(s.maxY);
            sfills.add(s);
        }
        put(vbo[2], fb);
    }

    private static Range newRange(int layer, int color, int first) {
        Range r = new Range();
        r.layer = layer; r.color = color; r.first = first;
        r.minX = r.minY = Float.MAX_VALUE; r.maxX = r.maxY = -Float.MAX_VALUE;
        return r;
    }

    private static void grow(Range r, float[] p) {
        for (int k = 0; k + 1 < p.length; k += 2) {
            if (p[k] < r.minX) r.minX = p[k]; if (p[k] > r.maxX) r.maxX = p[k];
            if (p[k + 1] < r.minY) r.minY = p[k + 1]; if (p[k + 1] > r.maxY) r.maxY = p[k + 1];
        }
    }

    static boolean isConvex(float[] p) {
        int n = p.length / 2;
        int sign = 0;
        for (int i = 0; i < n; i++) {
            float ax = p[i * 2], ay = p[i * 2 + 1];
            float bx = p[((i + 1) % n) * 2], by = p[((i + 1) % n) * 2 + 1];
            float cx = p[((i + 2) % n) * 2], cy = p[((i + 2) % n) * 2 + 1];
            float cr = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
            if (Math.abs(cr) < 1e-12f) continue;
            int sg = cr > 0 ? 1 : -1;
            if (sign == 0) sign = sg;
            else if (sg != sign) return false;
        }
        return true;
    }

    private static FloatBuffer direct(int floats) {
        return ByteBuffer.allocateDirect(Math.max(1, floats) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void put(int buf, FloatBuffer fb) {
        int n = fb.position();
        fb.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buf);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.max(4, n * 4), n > 0 ? fb : null, GLES20.GL_STATIC_DRAW);
    }

    // ------------------------------------------------------------------ shaders
    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
