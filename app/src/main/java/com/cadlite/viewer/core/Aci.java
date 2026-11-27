package com.cadlite.viewer.core;

/** AutoCAD Color Index (ACI) palette and color-spec helpers. */
public final class Aci {
    private Aci() {}

    /** Color spec values. Explicit colors are stored as 0xFFRRGGBB (negative ints). */
    public static final int BYLAYER = 1;
    public static final int BYBLOCK = 2;
    /** Foreground: ACI 7 — white on dark background, black on light. */
    public static final int FG = 3;

    private static final int[] RGB = new int[256];

    static {
        int[] base = {0x000000, 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x808080, 0xC0C0C0};
        System.arraycopy(base, 0, RGB, 0, 10);
        double[] vals = {1.0, 0.8, 0.6, 0.5, 0.3};
        for (int i = 10; i < 250; i++) {
            int k = i - 10;
            double hue = (k / 10) * 15.0;
            int sub = k % 10;
            double v = vals[sub / 2];
            double s = (sub % 2 == 0) ? 1.0 : 0.5;
            RGB[i] = hsv(hue, s, v);
        }
        int[] grays = {0x333333, 0x505050, 0x696969, 0x828282, 0xBEBEBE, 0xFFFFFF};
        System.arraycopy(grays, 0, RGB, 250, 6);
    }

    private static int hsv(double h, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs((h / 60.0) % 2 - 1)), m = v - c;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        int R = (int) Math.round((r + m) * 255), G = (int) Math.round((g + m) * 255), B = (int) Math.round((b + m) * 255);
        return (R << 16) | (G << 8) | B;
    }

    /** Converts an ACI index (as found in group 62) to a color spec. */
    public static int fromAci(int aci) {
        if (aci == 256) return BYLAYER;
        if (aci == 0) return BYBLOCK;
        aci = Math.abs(aci);
        if (aci == 7 || aci > 255) return FG;
        return 0xFF000000 | RGB[aci];
    }

    public static int fromTrueColor(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    public static boolean isExplicit(int spec) {
        return (spec & 0xFF000000) == 0xFF000000;
    }
}
