package com.cadlite.viewer.core;

import java.util.Locale;

/**
 * Arabic SHX fonts used in Gulf CAD drawings (xarab.shx, xarb.shx, sosa.shx, ...) store Arabic
 * typed on the Arabic keyboard layout as Latin characters, e.g. "plhL" = حمام. Upper-case keys
 * select the final letter shape, so both cases map to the same Arabic letter. The fonts draw from
 * right to left, so a TEXT insertion point is the right end of the text.
 */
public final class ArabicShx {
    private ArabicShx() {}

    private static final String[] MAP = new String[128];

    static {
        String[][] keys = {
                {"q", "ض"}, {"w", "ص"}, {"e", "ث"}, {"r", "ق"}, {"t", "ف"}, {"y", "غ"}, {"u", "ع"},
                {"i", "ه"}, {"o", "خ"}, {"p", "ح"}, {"a", "ش"}, {"s", "س"}, {"d", "ي"}, {"f", "ب"},
                {"g", "ل"}, {"h", "ا"}, {"j", "ت"}, {"k", "ن"}, {"l", "م"}, {"z", "ئ"}, {"x", "ء"},
                {"c", "ؤ"}, {"v", "ر"}, {"b", "لا"}, {"n", "ى"}, {"m", "ة"},
        };
        for (String[] k : keys) {
            MAP[k[0].charAt(0)] = k[1];
            MAP[Character.toUpperCase(k[0].charAt(0))] = k[1];
        }
        String[][] sym = {
                {"[", "ج"}, {"{", "ج"}, {"]", "د"}, {"}", "د"}, {";", "ك"}, {":", "ك"},
                {"'", "ط"}, {"\"", "ط"}, {",", "و"}, {"<", "و"}, {".", "ز"}, {">", "ز"},
                {"/", "ظ"}, {"?", "ظ"}, {"`", "ذ"}, {"~", "ذ"}, {"_", "ـ"}, // tatweel
        };
        for (String[] k : sym) MAP[k[0].charAt(0)] = k[1];
    }

    /** True for SHX/TTF font files known to use the keyboard-mapped Arabic encoding. */
    public static boolean isMappedFont(String fontFile) {
        if (fontFile == null) return false;
        String f = fontFile.toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = f.lastIndexOf('/');
        if (slash >= 0) f = f.substring(slash + 1);
        int dot = f.lastIndexOf('.');
        if (dot > 0) f = f.substring(0, dot);
        return f.contains("arab") || f.contains("xarb") || f.equals("sosa") || f.startsWith("sosa");
    }

    /** True if the character maps to an Arabic letter or kashida (i.e. continues a word). */
    public static boolean isLetterKey(char ch) {
        return ch < 128 && MAP[ch] != null;
    }

    /** Converts one character run already decoded from DXF codes. */
    public static void appendMapped(StringBuilder out, char ch) {
        String m = ch < 128 ? MAP[ch] : null;
        if (m != null) out.append(m); else out.append(ch);
    }

    public static String map(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) appendMapped(b, s.charAt(i));
        return b.toString();
    }
}
