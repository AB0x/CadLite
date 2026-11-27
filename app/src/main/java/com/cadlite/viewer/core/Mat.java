package com.cadlite.viewer.core;

/** 2D affine transform: x' = a*x + c*y + e ; y' = b*x + d*y + f. */
public final class Mat {
    public final double a, b, c, d, e, f;

    public static final Mat IDENTITY = new Mat(1, 0, 0, 1, 0, 0);

    public Mat(double a, double b, double c, double d, double e, double f) {
        this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
    }

    /** Returns this * o (apply o first, then this). */
    public Mat mul(Mat o) {
        return new Mat(
                a * o.a + c * o.b,
                b * o.a + d * o.b,
                a * o.c + c * o.d,
                b * o.c + d * o.d,
                a * o.e + c * o.f + e,
                b * o.e + d * o.f + f);
    }

    public static Mat translate(double x, double y) { return new Mat(1, 0, 0, 1, x, y); }
    public static Mat scale(double sx, double sy) { return new Mat(sx, 0, 0, sy, 0, 0); }
    public static Mat rotate(double rad) {
        double cs = Math.cos(rad), sn = Math.sin(rad);
        return new Mat(cs, sn, -sn, cs, 0, 0);
    }
    /** OCS with extrusion (0,0,-1) maps to WCS by mirroring X. */
    public static final Mat MIRROR_X = new Mat(-1, 0, 0, 1, 0, 0);

    public double x(double x, double y) { return a * x + c * y + e; }
    public double y(double x, double y) { return b * x + d * y + f; }

    public double det() { return a * d - b * c; }
    public double uniformScale() { return Math.sqrt(Math.abs(det())); }
    public double rotation() { return Math.atan2(b, a); }
}
