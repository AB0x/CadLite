package com.cadlite.viewer.core;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming ASCII DXF parser that flattens model space into a {@link Drawing}.
 * Supports: LINE, LWPOLYLINE, POLYLINE, CIRCLE, ARC, ELLIPSE, SPLINE, TEXT, MTEXT,
 * ATTRIB, INSERT (nested, arrays), DIMENSION, LEADER, SOLID, TRACE, 3DFACE, HATCH, POINT.
 */
public final class DxfParser {

    public interface Progress { void onProgress(String msg); }

    // ---------------------------------------------------------------- raw entity
    static final class Ent {
        String type;
        int n;
        int[] c = new int[16];
        String[] v = new String[16];

        void add(int code, String val) {
            if (n == c.length) {
                c = java.util.Arrays.copyOf(c, n * 2);
                v = java.util.Arrays.copyOf(v, n * 2);
            }
            c[n] = code; v[n++] = val;
        }
        String s(int code, String def) {
            for (int i = 0; i < n; i++) if (c[i] == code) return v[i];
            return def;
        }
        double d(int code, double def) {
            for (int i = 0; i < n; i++) if (c[i] == code) return num(v[i]);
            return def;
        }
        int i(int code, int def) {
            for (int i = 0; i < n; i++) if (c[i] == code) return (int) num(v[i]);
            return def;
        }
        boolean has(int code) {
            for (int i = 0; i < n; i++) if (c[i] == code) return true;
            return false;
        }
    }

    static double num(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    interface EntSource { Ent next() throws IOException; void pushBack(Ent e); }

    // ---------------------------------------------------------------- group reader
    private BufferedReader in;
    private int code;
    private String value;
    private boolean pushed;

    private boolean nextGroup() throws IOException {
        if (pushed) { pushed = false; return true; }
        String cl = in.readLine();
        if (cl == null) return false;
        while (cl.trim().isEmpty()) { // tolerate blank lines
            cl = in.readLine();
            if (cl == null) return false;
        }
        String vl = in.readLine();
        if (vl == null) return false;
        try { code = Integer.parseInt(cl.trim()); } catch (NumberFormatException e) { code = -9999; }
        value = vl;
        return true;
    }

    /** Reads an entity whose "0/TYPE" group has just been consumed. */
    private Ent readEnt(String type) throws IOException {
        Ent e = new Ent();
        e.type = type;
        while (nextGroup()) {
            if (code == 0) { pushed = true; break; }
            e.add(code, (code == 1 || code == 3) ? value : value.trim());
        }
        return e;
    }

    // ---------------------------------------------------------------- state
    private final Map<String, Integer> layerIdx = new HashMap<>();
    private final List<String> layerNames = new ArrayList<>();
    private final List<Integer> layerColors = new ArrayList<>();
    private final List<Boolean> layerVis = new ArrayList<>();

    private static final class Block {
        double bx, by;
        List<Ent> ents = new ArrayList<>();
        Geo geo;
        boolean building;
    }
    private final Map<String, Block> blocks = new HashMap<>();

    private boolean originSet;
    private double ox, oy;
    private String version = "";
    private Progress progress;
    private long entityCount;
    private final List<float[]> objBoxes = new ArrayList<>();

    private int layer(String name) {
        if (name == null || name.isEmpty()) name = "0";
        String k = name.toUpperCase();
        Integer i = layerIdx.get(k);
        if (i != null) return i;
        int idx = layerNames.size();
        layerIdx.put(k, idx);
        layerNames.add(name);
        layerColors.add(Aci.FG);
        layerVis.add(true);
        return idx;
    }

    // ---------------------------------------------------------------- entry point
    public static Drawing parse(InputStream raw, Progress p) throws IOException {
        DxfParser dp = new DxfParser();
        dp.progress = p;
        return dp.run(raw);
    }

    private Drawing run(InputStream raw) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(raw, 1 << 16);
        Charset cs = sniffCharset(bis);
        in = new BufferedReader(new InputStreamReader(bis, cs), 1 << 16);
        layer("0");
        Geo top = new Geo();
        top.track = true;
        while (nextGroup()) {
            if (code == 0 && "SECTION".equals(value.trim())) {
                if (!nextGroup()) break;
                String sec = value.trim();
                switch (sec) {
                    case "HEADER": readHeader(); break;
                    case "TABLES": readTables(); break;
                    case "BLOCKS": readBlocks(); break;
                    case "ENTITIES": readEntities(top); break;
                    default: skipSection();
                }
            } else if (code == 0 && "EOF".equals(value.trim())) {
                break;
            }
        }
        int n = layerNames.size();
        int[] colors = new int[n];
        boolean[] vis = new boolean[n];
        for (int i = 0; i < n; i++) { colors[i] = layerColors.get(i); vis[i] = layerVis.get(i); }
        Drawing d = new Drawing(top, layerNames, colors, vis);
        d.version = version;
        d.setExtents(objBoxes);
        return d;
    }

    /** DXF R2007+ is UTF-8; older files use $DWGCODEPAGE (e.g. ANSI_1256 Arabic). */
    private static Charset sniffCharset(BufferedInputStream bis) throws IOException {
        int lim = 1 << 18;
        bis.mark(lim + 1);
        byte[] buf = new byte[lim];
        int len = 0, r;
        while (len < lim && (r = bis.read(buf, len, lim - len)) > 0) len += r;
        bis.reset();
        String head = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
        String ver = headerValue(head, "$ACADVER");
        String cp = headerValue(head, "$DWGCODEPAGE");
        if (ver != null && ver.compareToIgnoreCase("AC1021") >= 0) return StandardCharsets.UTF_8;
        if (cp != null) {
            String u = cp.toUpperCase();
            String num = u.replaceAll("[^0-9]", "");
            try {
                if (u.startsWith("ANSI_") && !num.isEmpty()) return Charset.forName("windows-" + num);
                if (u.startsWith("DOS") && !num.isEmpty()) return Charset.forName("IBM" + num);
            } catch (Exception ignored) {}
        }
        if (looksUtf8(buf, len)) return StandardCharsets.UTF_8;
        return Charset.forName("windows-1252");
    }

    private static boolean looksUtf8(byte[] b, int len) {
        int multi = 0;
        for (int i = 0; i < len; i++) {
            int x = b[i] & 0xFF;
            if (x < 0x80) continue;
            int need = x >= 0xF0 ? 3 : x >= 0xE0 ? 2 : x >= 0xC0 ? 1 : -1;
            if (need < 0 || i + need >= len) return multi > 0 && need >= 0;
            for (int k = 1; k <= need; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
            i += need; multi++;
        }
        return true;
    }

    private static String headerValue(String head, String var) {
        int i = head.indexOf(var);
        if (i < 0) return null;
        String[] lines = head.substring(i, Math.min(head.length(), i + 200)).split("\r?\n");
        return lines.length >= 3 ? lines[2].trim() : null;
    }

    private void skipSection() throws IOException {
        while (nextGroup()) if (code == 0 && "ENDSEC".equals(value.trim())) return;
    }

    private void readHeader() throws IOException {
        String var = null;
        double exMinX = Double.NaN, exMinY = Double.NaN, exMaxX = Double.NaN, exMaxY = Double.NaN;
        while (nextGroup()) {
            if (code == 0 && "ENDSEC".equals(value.trim())) break;
            if (code == 9) { var = value.trim(); continue; }
            if ("$ACADVER".equals(var) && code == 1) version = value.trim();
            else if ("$EXTMIN".equals(var)) { if (code == 10) exMinX = num(value); else if (code == 20) exMinY = num(value); }
            else if ("$EXTMAX".equals(var)) { if (code == 10) exMaxX = num(value); else if (code == 20) exMaxY = num(value); }
        }
        if (exMaxX >= exMinX && exMaxY >= exMinY && Math.abs(exMinX) < 1e15 && Math.abs(exMaxX) < 1e15) {
            ox = (exMinX + exMaxX) / 2; oy = (exMinY + exMaxY) / 2; originSet = true;
        }
    }

    private void readTables() throws IOException {
        while (nextGroup()) {
            if (code != 0) continue;
            String t = value.trim();
            if ("ENDSEC".equals(t)) return;
            if ("LAYER".equals(t)) {
                Ent e = readEnt(t);
                String name = e.s(2, null);
                if (name == null) continue; // the table header "0 TABLE / 2 LAYER" is handled elsewhere
                int idx = layer(name);
                int aci = e.i(62, 7);
                int col = e.has(420) ? Aci.fromTrueColor(e.i(420, 0)) : Aci.fromAci(aci == 0 ? 7 : aci);
                if (col == Aci.BYLAYER || col == Aci.BYBLOCK) col = Aci.FG;
                layerColors.set(idx, col);
                boolean frozen = (e.i(70, 0) & 1) != 0;
                layerVis.set(idx, aci >= 0 && !frozen);
            }
        }
    }

    private void readBlocks() throws IOException {
        Block cur = null;
        while (nextGroup()) {
            if (code != 0) continue;
            String t = value.trim();
            if ("ENDSEC".equals(t)) return;
            if ("BLOCK".equals(t)) {
                Ent b = readEnt(t);
                String name = b.s(2, b.s(3, ""));
                cur = new Block();
                cur.bx = b.d(10, 0); cur.by = b.d(20, 0);
                if ((b.i(70, 0) & 4) == 0) blocks.put(name.toUpperCase(), cur); // skip xrefs
            } else if ("ENDBLK".equals(t)) {
                readEnt(t);
                cur = null;
            } else {
                Ent e = readEnt(t);
                if (cur != null) cur.ents.add(e);
            }
        }
    }

    private void readEntities(Geo top) throws IOException {
        EntSource src = new EntSource() {
            Ent back;
            public Ent next() throws IOException {
                if (back != null) { Ent b = back; back = null; return b; }
                while (nextGroup()) {
                    if (code != 0) continue;
                    String t = value.trim();
                    if ("ENDSEC".equals(t)) return null;
                    return readEnt(t);
                }
                return null;
            }
            public void pushBack(Ent e) { back = e; }
        };
        Ent e;
        while ((e = src.next()) != null) {
            if (!originSet) {
                double x = e.d(10, Double.NaN), y = e.d(20, Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y)) { ox = x; oy = y; originSet = true; }
            }
            if (e.i(67, 0) == 1) { skipFollowers(e, src); continue; } // paper space
            int nt = top.texts.size(), nf = top.fills.size();
            top.resetBox();
            emit(e, src, top, Mat.translate(-ox, -oy), 0);
            for (int k = nt; k < top.texts.size(); k++) {
                Geo.Text t = top.texts.get(k);
                float x = (float) t.x, y = (float) t.y, hh = (float) t.h;
                top.box(x, y, x + hh, y + hh);
            }
            for (int k = nf; k < top.fills.size(); k++)
                for (float[] lp : top.fills.get(k).loops)
                    for (int q = 0; q + 1 < lp.length; q += 2) top.box(lp[q], lp[q + 1], lp[q], lp[q + 1]);
            if (top.bx1 >= top.bx0) objBoxes.add(new float[]{top.bx0, top.by0, top.bx1, top.by1});
            if (++entityCount % 20000 == 0 && progress != null)
                progress.onProgress("Reading… " + entityCount + " objects");
        }
    }

    private void skipFollowers(Ent e, EntSource src) throws IOException {
        if ("POLYLINE".equals(e.type) || ("INSERT".equals(e.type) && e.i(66, 0) == 1)) {
            Ent x;
            while ((x = src.next()) != null) {
                if ("SEQEND".equals(x.type)) return;
                if (!"VERTEX".equals(x.type) && !"ATTRIB".equals(x.type)) { src.pushBack(x); return; }
            }
        }
    }

    private Geo blockGeo(String name, int depth) {
        Block b = blocks.get(name.toUpperCase());
        if (b == null || b.building || depth > 20) return null;
        if (b.geo == null) {
            b.building = true;
            Geo g = new Geo();
            final List<Ent> list = b.ents;
            EntSource src = new EntSource() {
                int i;
                Ent back;
                public Ent next() {
                    if (back != null) { Ent x = back; back = null; return x; }
                    return i < list.size() ? list.get(i++) : null;
                }
                public void pushBack(Ent e) { back = e; }
            };
            Mat base = Mat.translate(-b.bx, -b.by);
            try {
                Ent e;
                while ((e = src.next()) != null) emit(e, src, g, base, depth + 1);
            } catch (IOException ignored) {}
            b.geo = g;
            b.building = false;
        }
        return b.geo;
    }

    // ---------------------------------------------------------------- emit
    private int color(Ent e) {
        if (e.has(420)) return Aci.fromTrueColor(e.i(420, 0));
        return Aci.fromAci(e.i(62, 256));
    }

    private static Mat ocs(Ent e, Mat m) {
        return e.d(230, 1) < 0 ? m.mul(Mat.MIRROR_X) : m;
    }

    private void emit(Ent e, EntSource src, Geo out, Mat m, int depth) throws IOException {
        if (e.i(60, 0) == 1) { skipFollowers(e, src); return; } // invisible
        int lay = layer(e.s(8, "0"));
        int col = color(e);
        switch (e.type) {
            case "LINE": {
                Geo.FloatList l = out.list(lay, col);
                seg(l, m, e.d(10, 0), e.d(20, 0), e.d(11, 0), e.d(21, 0));
                break;
            }
            case "LWPOLYLINE": lwpolyline(e, out.list(lay, col), ocs(e, m)); break;
            case "POLYLINE": polyline(e, src, out.list(lay, col), m); break;
            case "CIRCLE": {
                Mat mm = ocs(e, m);
                arc(out.list(lay, col), mm, e.d(10, 0), e.d(20, 0), e.d(40, 0), 0, Math.PI * 2);
                break;
            }
            case "ARC": {
                Mat mm = ocs(e, m);
                double a0 = Math.toRadians(e.d(50, 0)), a1 = Math.toRadians(e.d(51, 360));
                while (a1 <= a0) a1 += Math.PI * 2;
                arc(out.list(lay, col), mm, e.d(10, 0), e.d(20, 0), e.d(40, 0), a0, a1);
                break;
            }
            case "ELLIPSE": ellipse(e, out.list(lay, col), m); break;
            case "SPLINE": spline(e, out.list(lay, col), m); break;
            case "TEXT": case "ATTRIB": text(e, out, ocs(e, m), lay, col); break;
            case "MTEXT": mtext(e, out, m, lay, col); break;
            case "INSERT": insert(e, src, out, m, depth, lay, col); break;
            case "DIMENSION": {
                Geo g = blockGeo(e.s(2, ""), depth);
                if (g != null) out.instance(g, m, lay, col);
                break;
            }
            case "LEADER": {
                Geo.FloatList l = out.list(lay, col);
                double px = 0, py = 0; boolean first = true;
                for (int i = 0; i < e.n; i++) {
                    if (e.c[i] == 10) {
                        double x = num(e.v[i]), y = (i + 1 < e.n && e.c[i + 1] == 20) ? num(e.v[i + 1]) : 0;
                        if (!first) seg(l, m, px, py, x, y);
                        px = x; py = y; first = false;
                    }
                }
                break;
            }
            case "SOLID": case "TRACE": {
                Mat mm = ocs(e, m);
                double x1 = e.d(10, 0), y1 = e.d(20, 0), x2 = e.d(11, 0), y2 = e.d(21, 0);
                double x3 = e.d(12, 0), y3 = e.d(22, 0), x4 = e.d(13, x3), y4 = e.d(23, y3);
                Geo.Fill f = new Geo.Fill();
                f.layer = lay; f.color = col;
                f.loops = new float[][]{xf(mm, new double[]{x1, y1, x2, y2, x4, y4, x3, y3})};
                out.fills.add(f);
                break;
            }
            case "3DFACE": {
                Geo.FloatList l = out.list(lay, col);
                double[] p = {e.d(10, 0), e.d(20, 0), e.d(11, 0), e.d(21, 0), e.d(12, 0), e.d(22, 0), e.d(13, 0), e.d(23, 0)};
                int flags = e.i(70, 0);
                for (int k = 0; k < 4; k++) {
                    if ((flags & (1 << k)) != 0) continue;
                    int j = (k + 1) % 4;
                    seg(l, m, p[k * 2], p[k * 2 + 1], p[j * 2], p[j * 2 + 1]);
                }
                break;
            }
            case "HATCH": hatch(e, out, ocs(e, m), lay, col); break;
            case "POINT": {
                double x = e.d(10, 0), y = e.d(20, 0);
                Geo.FloatList l = out.list(lay, col);
                double s = 1e-3;
                seg(l, m, x - s, y, x + s, y);
                break;
            }
            default:
                // ATTDEF, VIEWPORT, IMAGE, 3DSOLID, etc. are ignored.
                break;
        }
    }

    private static float[] xf(Mat m, double[] p) {
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i += 2) {
            r[i] = (float) m.x(p[i], p[i + 1]);
            r[i + 1] = (float) m.y(p[i], p[i + 1]);
        }
        return r;
    }

    private static void seg(Geo.FloatList l, Mat m, double x1, double y1, double x2, double y2) {
        l.add4((float) m.x(x1, y1), (float) m.y(x1, y1), (float) m.x(x2, y2), (float) m.y(x2, y2));
    }

    private static int arcSteps(double sweep) {
        return Math.max(4, Math.min(256, (int) Math.ceil(Math.abs(sweep) / (Math.PI * 2) * 96)));
    }

    private static void arc(Geo.FloatList l, Mat m, double cx, double cy, double r, double a0, double a1) {
        if (r <= 0) return;
        int n = arcSteps(a1 - a0);
        double px = cx + r * Math.cos(a0), py = cy + r * Math.sin(a0);
        for (int i = 1; i <= n; i++) {
            double a = a0 + (a1 - a0) * i / n;
            double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            seg(l, m, px, py, x, y);
            px = x; py = y;
        }
    }

    /** Adds a straight or bulged segment. Collects polygon points in pts if non-null. */
    private static void bulgeSeg(Geo.FloatList l, Mat m, double x1, double y1, double x2, double y2, double b, List<Double> pts) {
        if (Math.abs(b) < 1e-9) {
            if (l != null) seg(l, m, x1, y1, x2, y2);
            if (pts != null) { pts.add(x2); pts.add(y2); }
            return;
        }
        double dx = x2 - x1, dy = y2 - y1, chord = Math.hypot(dx, dy);
        if (chord < 1e-12) return;
        double theta = 4 * Math.atan(b);
        double h = (chord / 2) * (1 - b * b) / (2 * b);
        double cx = (x1 + x2) / 2 - dy / chord * h, cy = (y1 + y2) / 2 + dx / chord * h;
        double r = Math.hypot(x1 - cx, y1 - cy), a0 = Math.atan2(y1 - cy, x1 - cx);
        int n = arcSteps(theta);
        double px = x1, py = y1;
        for (int i = 1; i <= n; i++) {
            double a = a0 + theta * i / n;
            double x = i == n ? x2 : cx + r * Math.cos(a), y = i == n ? y2 : cy + r * Math.sin(a);
            if (l != null) seg(l, m, px, py, x, y);
            if (pts != null) { pts.add(x); pts.add(y); }
            px = x; py = y;
        }
    }

    private void lwpolyline(Ent e, Geo.FloatList l, Mat m) {
        List<double[]> v = new ArrayList<>();
        for (int i = 0; i < e.n; i++) {
            int c = e.c[i];
            if (c == 10) v.add(new double[]{num(e.v[i]), 0, 0});
            else if (c == 20 && !v.isEmpty()) v.get(v.size() - 1)[1] = num(e.v[i]);
            else if (c == 42 && !v.isEmpty()) v.get(v.size() - 1)[2] = num(e.v[i]);
        }
        boolean closed = (e.i(70, 0) & 1) != 0;
        drawVerts(l, m, v, closed);
    }

    private static void drawVerts(Geo.FloatList l, Mat m, List<double[]> v, boolean closed) {
        int n = v.size();
        if (n < 2) return;
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            double[] a = v.get(i), b = v.get((i + 1) % n);
            bulgeSeg(l, m, a[0], a[1], b[0], b[1], a[2], null);
        }
    }

    private void polyline(Ent e, EntSource src, Geo.FloatList l, Mat m) throws IOException {
        int flags = e.i(70, 0);
        boolean is3d = (flags & 8) != 0, mesh = (flags & 16) != 0, face = (flags & 64) != 0;
        Mat mm = (is3d || mesh || face) ? m : ocs(e, m);
        List<double[]> v = new ArrayList<>();
        List<double[]> facePts = new ArrayList<>();
        Ent x;
        while ((x = src.next()) != null) {
            if ("SEQEND".equals(x.type)) break;
            if (!"VERTEX".equals(x.type)) { src.pushBack(x); break; }
            int vf = x.i(70, 0);
            if (face) {
                if ((vf & 128) != 0 && (vf & 64) == 0) { // face record
                    int[] idx = {x.i(71, 0), x.i(72, 0), x.i(73, 0), x.i(74, 0)};
                    List<double[]> poly = new ArrayList<>();
                    for (int k : idx) if (k != 0 && Math.abs(k) <= facePts.size()) poly.add(facePts.get(Math.abs(k) - 1));
                    for (int k = 0; k < poly.size(); k++) {
                        double[] a = poly.get(k), b = poly.get((k + 1) % poly.size());
                        seg(l, mm, a[0], a[1], b[0], b[1]);
                    }
                } else {
                    facePts.add(new double[]{x.d(10, 0), x.d(20, 0)});
                }
                continue;
            }
            if ((vf & 16) != 0) continue; // spline frame control point
            v.add(new double[]{x.d(10, 0), x.d(20, 0), is3d ? 0 : x.d(42, 0)});
        }
        if (face) return;
        if (mesh) {
            int mc = e.i(71, 0), nc = e.i(72, 0);
            if (mc * nc == v.size() && mc > 0 && nc > 0) {
                for (int i = 0; i < mc; i++) for (int j = 0; j < nc; j++) {
                    double[] p = v.get(i * nc + j);
                    if (j + 1 < nc) { double[] q = v.get(i * nc + j + 1); seg(l, mm, p[0], p[1], q[0], q[1]); }
                    if (i + 1 < mc) { double[] q = v.get((i + 1) * nc + j); seg(l, mm, p[0], p[1], q[0], q[1]); }
                }
                return;
            }
        }
        drawVerts(l, mm, v, (flags & 1) != 0);
    }

    private void ellipse(Ent e, Geo.FloatList l, Mat m) {
        double cx = e.d(10, 0), cy = e.d(20, 0), mx = e.d(11, 1), my = e.d(21, 0);
        double ratio = e.d(40, 1), t0 = e.d(41, 0), t1 = e.d(42, Math.PI * 2);
        if (e.d(230, 1) < 0) { double tmp = -t0; t0 = -t1; t1 = tmp; } // reversed direction in mirrored OCS
        while (t1 <= t0) t1 += Math.PI * 2;
        double ux = mx, uy = my, vx = -my * ratio, vy = mx * ratio;
        int n = arcSteps(t1 - t0);
        double px = cx + ux * Math.cos(t0) + vx * Math.sin(t0), py = cy + uy * Math.cos(t0) + vy * Math.sin(t0);
        for (int i = 1; i <= n; i++) {
            double t = t0 + (t1 - t0) * i / n;
            double x = cx + ux * Math.cos(t) + vx * Math.sin(t), y = cy + uy * Math.cos(t) + vy * Math.sin(t);
            seg(l, m, px, py, x, y);
            px = x; py = y;
        }
    }

    private void spline(Ent e, Geo.FloatList l, Mat m) {
        int deg = e.i(71, 3);
        List<Double> knots = new ArrayList<>(), w = new ArrayList<>();
        List<double[]> ctrl = new ArrayList<>(), fit = new ArrayList<>();
        for (int i = 0; i < e.n; i++) {
            int c = e.c[i];
            double val = num(e.v[i]);
            if (c == 40) knots.add(val);
            else if (c == 41) w.add(val);
            else if (c == 10) ctrl.add(new double[]{val, 0});
            else if (c == 20 && !ctrl.isEmpty()) ctrl.get(ctrl.size() - 1)[1] = val;
            else if (c == 11) fit.add(new double[]{val, 0});
            else if (c == 21 && !fit.isEmpty()) fit.get(fit.size() - 1)[1] = val;
        }
        double[][] pts = sampleSpline(deg, knots, w, ctrl);
        if (pts == null) pts = fit.toArray(new double[0][]);
        for (int i = 1; i < pts.length; i++) seg(l, m, pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]);
    }

    /** Rational B-spline evaluation (de Boor). Returns null if data is inconsistent. */
    static double[][] sampleSpline(int p, List<Double> kn, List<Double> wl, List<double[]> cp) {
        int n = cp.size();
        if (n < 2 || p < 1 || kn.size() != n + p + 1) return null;
        double[] U = new double[kn.size()];
        for (int i = 0; i < U.length; i++) U[i] = kn.get(i);
        double[] W = new double[n];
        for (int i = 0; i < n; i++) W[i] = (wl.size() == n && wl.get(i) > 0) ? wl.get(i) : 1;
        double t0 = U[p], t1 = U[n];
        if (!(t1 > t0)) return null;
        int samples = Math.max(8, Math.min(1000, n * 12));
        double[][] out = new double[samples + 1][];
        double[] dx = new double[p + 1], dy = new double[p + 1], dw = new double[p + 1];
        for (int s = 0; s <= samples; s++) {
            double t = t0 + (t1 - t0) * s / samples;
            int k = p;
            while (k < n - 1 && t >= U[k + 1]) k++;
            for (int j = 0; j <= p; j++) {
                int idx = k - p + j;
                double ww = W[idx];
                dx[j] = cp.get(idx)[0] * ww; dy[j] = cp.get(idx)[1] * ww; dw[j] = ww;
            }
            for (int r = 1; r <= p; r++) {
                for (int j = p; j >= r; j--) {
                    int i = k - p + j;
                    double den = U[i + p - r + 1] - U[i];
                    double a = den == 0 ? 0 : (t - U[i]) / den;
                    dx[j] = (1 - a) * dx[j - 1] + a * dx[j];
                    dy[j] = (1 - a) * dy[j - 1] + a * dy[j];
                    dw[j] = (1 - a) * dw[j - 1] + a * dw[j];
                }
            }
            double ww = dw[p] == 0 ? 1 : dw[p];
            out[s] = new double[]{dx[p] / ww, dy[p] / ww};
        }
        return out;
    }

    // ---------------------------------------------------------------- text
    private void text(Ent e, Geo out, Mat m, int lay, int col) {
        if ("ATTRIB".equals(e.type) && (e.i(70, 0) & 1) != 0) return;
        String s = cleanText(e.s(1, ""), false);
        if (s.trim().isEmpty()) return;
        int h = e.i(72, 0);
        int vcode = "ATTRIB".equals(e.type) ? 74 : 73;
        int va = e.i(vcode, 0);
        double x = e.d(10, 0), y = e.d(20, 0);
        Geo.Text t = new Geo.Text();
        if ((h != 0 || va != 0) && e.has(11) && h != 3 && h != 5) { x = e.d(11, x); y = e.d(21, y); }
        if (h == 4) { t.hAlign = 1; t.vAlign = 2; }
        else if (h == 1 || h == 2) { t.hAlign = h; t.vAlign = va; }
        else { t.hAlign = 0; t.vAlign = va; }
        t.h = e.d(40, 1);
        t.rot = Math.toRadians(e.d(50, 0));
        t.widthFactor = e.d(41, 1);
        if (t.widthFactor <= 0) t.widthFactor = 1;
        place(t, m, x, y);
        t.lines = new String[]{s};
        t.layer = lay; t.color = col;
        out.texts.add(t);
    }

    private void mtext(Ent e, Geo out, Mat m, int lay, int col) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < e.n; i++) if (e.c[i] == 3) sb.append(e.v[i]);
        sb.append(e.s(1, ""));
        String s = cleanText(sb.toString(), true);
        if (s.trim().isEmpty()) return;
        Geo.Text t = new Geo.Text();
        int ap = e.i(71, 1);
        t.hAlign = (ap - 1) % 3;
        int row = (ap - 1) / 3; // 0 top, 1 middle, 2 bottom
        t.vAlign = row == 0 ? 3 : row == 1 ? 2 : 1;
        t.multi = true;
        t.h = e.d(40, 1);
        if (e.has(11)) t.rot = Math.atan2(e.d(21, 0), e.d(11, 1));
        else t.rot = Math.toRadians(e.d(50, 0));
        place(t, m, e.d(10, 0), e.d(20, 0));
        t.lines = s.split("\n", -1);
        t.layer = lay; t.color = col;
        out.texts.add(t);
    }

    private static void place(Geo.Text t, Mat m, double x, double y) {
        t.x = m.x(x, y); t.y = m.y(x, y);
        t.h *= m.uniformScale();
        t.rot += m.rotation();
        if (m.det() < 0) t.rot = Math.PI - t.rot; // keep mirrored text readable
    }

    /** Strips MTEXT formatting codes and decodes %% and \U+ escapes. */
    static String cleanText(String s, boolean mtext) {
        if (s.indexOf('\\') < 0 && s.indexOf('%') < 0 && s.indexOf('{') < 0) return s;
        StringBuilder o = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < n && s.charAt(i + 1) == '%') {
                char k = Character.toLowerCase(s.charAt(i + 2));
                if (k == 'c') { o.append('Ø'); i += 2; continue; }
                if (k == 'd') { o.append('°'); i += 2; continue; }
                if (k == 'p') { o.append('±'); i += 2; continue; }
                if (k == '%') { o.append('%'); i += 2; continue; }
                if (k == 'u' || k == 'o') { i += 2; continue; }
                if (Character.isDigit(k) && i + 4 < n) {
                    try { o.append((char) Integer.parseInt(s.substring(i + 2, i + 5))); i += 4; continue; } catch (Exception ignored) {}
                }
            }
            if (ch == '\\' && i + 1 < n) {
                char k = s.charAt(i + 1);
                if ((k == 'U' || k == 'u') && i + 6 < n && s.charAt(i + 2) == '+') {
                    try { o.append((char) Integer.parseInt(s.substring(i + 3, i + 7), 16)); i += 6; continue; } catch (Exception ignored) {}
                }
                if (!mtext) { o.append(ch); continue; }
                switch (k) {
                    case 'P': o.append('\n'); i++; continue;
                    case '~': o.append(' '); i++; continue;
                    case '\\': case '{': case '}': o.append(k); i++; continue;
                    case 'L': case 'l': case 'O': case 'o': case 'K': case 'k': i++; continue;
                    case 'S': { // stacked fraction \S1/2;
                        int end = s.indexOf(';', i);
                        if (end < 0) end = n;
                        o.append(s.substring(i + 2, end).replace('^', '/').replace('#', '/'));
                        i = end; continue;
                    }
                    default:
                        if (Character.isLetter(k)) { // \f..; \H..; \C..; \A..; \W..; \Q..; \T..; \p..;
                            int end = s.indexOf(';', i);
                            i = end < 0 ? n : end;
                            continue;
                        }
                }
            }
            if (mtext && (ch == '{' || ch == '}')) continue;
            o.append(ch);
        }
        return o.toString();
    }

    // ---------------------------------------------------------------- insert
    private void insert(Ent e, EntSource src, Geo out, Mat m, int depth, int lay, int col) throws IOException {
        Mat mm = ocs(e, m);
        Geo g = blockGeo(e.s(2, ""), depth);
        double ix = e.d(10, 0), iy = e.d(20, 0);
        double sx = e.d(41, 1), sy = e.d(42, sx);
        if (sx == 0) sx = 1;
        if (sy == 0) sy = 1;
        double rot = Math.toRadians(e.d(50, 0));
        int cols = Math.max(1, Math.min(e.i(70, 1), 1000)), rows = Math.max(1, Math.min(e.i(71, 1), 1000));
        double cdx = e.d(44, 0), rdy = e.d(45, 0);
        if (g != null && !g.isEmpty()) {
            Mat base = mm.mul(Mat.translate(ix, iy)).mul(Mat.rotate(rot));
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                Mat t = base.mul(Mat.translate(c * cdx, r * rdy)).mul(Mat.scale(sx, sy));
                out.instance(g, t, lay, col);
            }
        }
        if (e.i(66, 0) == 1) { // following ATTRIBs (already in parent coordinates)
            Ent x;
            while ((x = src.next()) != null) {
                if ("SEQEND".equals(x.type)) break;
                if (!"ATTRIB".equals(x.type)) { src.pushBack(x); break; }
                if (x.i(60, 0) == 1) continue;
                text(x, out, ocs(x, m), layer(x.s(8, "0")), color(x));
            }
        }
    }

    // ---------------------------------------------------------------- hatch
    private void hatch(Ent e, Geo out, Mat m, int lay, int col) {
        boolean solid = false;
        int i = 0;
        // find pattern name/solid flag and number of loops
        int loops = 0;
        for (; i < e.n; i++) {
            if (e.c[i] == 2 && "SOLID".equalsIgnoreCase(e.v[i].trim())) solid = true;
            if (e.c[i] == 70 && i > 0) { if ((int) num(e.v[i]) == 1) solid = true; }
            if (e.c[i] == 91) { loops = (int) num(e.v[i]); i++; break; }
        }
        // gradient fills are also drawn solid
        if (e.i(450, 0) == 1) solid = true;
        List<float[]> polys = new ArrayList<>();
        Cursor cur = new Cursor(e, i);
        for (int lp = 0; lp < loops && cur.i < e.n; lp++) {
            if (!cur.seek(92)) break;
            int flags = (int) cur.val();
            List<Double> pts = new ArrayList<>();
            if ((flags & 2) != 0) {
                boolean bulge = cur.seek(72) && cur.val() != 0;
                boolean closed = cur.seek(73) && cur.val() != 0;
                int nv = cur.seek(93) ? (int) cur.val() : 0;
                List<double[]> v = new ArrayList<>();
                for (int k = 0; k < nv; k++) {
                    double x = cur.seek(10) ? cur.val() : 0, y = cur.seek(20) ? cur.val() : 0, b = 0;
                    if (bulge && cur.peek() == 42) { cur.i++; b = cur.prev(); }
                    v.add(new double[]{x, y, b});
                }
                if (!v.isEmpty()) {
                    pts.add(v.get(0)[0]); pts.add(v.get(0)[1]);
                    int segs = v.size();
                    for (int k = 0; k < segs; k++) {
                        if (k == segs - 1 && !closed && !solid) break;
                        double[] a = v.get(k), b2 = v.get((k + 1) % segs);
                        bulgeSeg(null, m, a[0], a[1], b2[0], b2[1], a[2], pts);
                    }
                }
            } else {
                int ne = cur.seek(93) ? (int) cur.val() : 0;
                for (int k = 0; k < ne; k++) {
                    if (!cur.seek(72)) break;
                    int type = (int) cur.val();
                    if (type == 1) {
                        double x1 = cur.get(10), y1 = cur.get(20), x2 = cur.get(11), y2 = cur.get(21);
                        if (pts.isEmpty()) { pts.add(x1); pts.add(y1); }
                        pts.add(x2); pts.add(y2);
                    } else if (type == 2 || type == 3) {
                        double cx = cur.get(10), cy = cur.get(20);
                        double r = 0, ex = 0, ey = 0, ratio = 1;
                        if (type == 2) r = cur.get(40);
                        else { ex = cur.get(11); ey = cur.get(21); ratio = cur.get(40); }
                        double a0 = Math.toRadians(cur.get(50)), a1 = Math.toRadians(cur.get(51));
                        boolean ccw = cur.seek(73) && cur.val() != 0;
                        if (!ccw) { a0 = -a0; a1 = -a1; }
                        while (ccw ? a1 < a0 : a1 > a0) a1 += ccw ? Math.PI * 2 : -Math.PI * 2;
                        if (Math.abs(a1 - a0) < 1e-9) a1 = a0 + (ccw ? 2 * Math.PI : -2 * Math.PI);
                        int n = arcSteps(a1 - a0);
                        for (int s = 0; s <= n; s++) {
                            double a = a0 + (a1 - a0) * s / n, x, y;
                            if (type == 2) { x = cx + r * Math.cos(a); y = cy + r * Math.sin(a); }
                            else {
                                double vx = -ey * ratio, vy = ex * ratio;
                                x = cx + ex * Math.cos(a) + vx * Math.sin(a);
                                y = cy + ey * Math.cos(a) + vy * Math.sin(a);
                            }
                            if (s == 0 && !pts.isEmpty()) continue;
                            pts.add(x); pts.add(y);
                        }
                    } else if (type == 4) {
                        int deg = (int) cur.get(94);
                        cur.seek(73); cur.seek(74);
                        int nk = (int) cur.get(95), nc = (int) cur.get(96);
                        List<Double> kn = new ArrayList<>(), w = new ArrayList<>();
                        List<double[]> cp = new ArrayList<>();
                        for (int q = 0; q < nk; q++) kn.add(cur.get(40));
                        for (int q = 0; q < nc; q++) {
                            double x = cur.get(10), y = cur.get(20);
                            cp.add(new double[]{x, y});
                            if (cur.peek() == 42) { cur.i++; w.add(cur.prev()); }
                        }
                        if (cur.peek() == 97) {
                            cur.i++;
                            int nf = (int) cur.prev();
                            for (int q = 0; q < nf; q++) { cur.get(11); cur.get(21); }
                        }
                        if (cur.peek() == 12) { cur.get(12); cur.get(22); cur.get(13); cur.get(23); }
                        double[][] sp = sampleSpline(deg, kn, w, cp);
                        if (sp == null) sp = cp.toArray(new double[0][]);
                        for (double[] p : sp) { pts.add(p[0]); pts.add(p[1]); }
                    }
                }
            }
            // skip source boundary handles
            if (cur.peek() == 97) {
                cur.i++;
                int ns = (int) cur.prev();
                for (int q = 0; q < ns && cur.peek() == 330; q++) cur.i++;
            }
            if (pts.size() >= 4) {
                double[] arr = new double[pts.size()];
                for (int q = 0; q < arr.length; q++) arr[q] = pts.get(q);
                polys.add(xf(m, arr));
            }
        }
        if (polys.isEmpty()) return;
        if (solid) {
            Geo.Fill f = new Geo.Fill();
            f.layer = lay; f.color = col;
            f.loops = polys.toArray(new float[0][]);
            out.fills.add(f);
        } else {
            Geo.FloatList l = out.list(lay, col);
            for (float[] p : polys)
                for (int q = 2; q + 1 < p.length; q += 2) l.add4(p[q - 2], p[q - 1], p[q], p[q + 1]);
        }
    }

    /** Sequential reader over an entity's groups (for HATCH). */
    private static final class Cursor {
        final Ent e; int i;
        Cursor(Ent e, int i) { this.e = e; this.i = i; }
        /** Advances to the next group with the given code; true if found (cursor after it). */
        boolean seek(int code) {
            for (int j = i; j < e.n && j < i + 12; j++) {
                if (e.c[j] == code) { i = j + 1; return true; }
            }
            return false;
        }
        double val() { return num(e.v[i - 1]); }
        double prev() { return num(e.v[i - 1]); }
        int peek() { return i < e.n ? e.c[i] : -1; }
        double get(int code) { return seek(code) ? val() : 0; }
    }
}
