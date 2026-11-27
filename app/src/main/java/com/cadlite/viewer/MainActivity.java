package com.cadlite.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cadlite.viewer.core.Drawing;
import com.cadlite.viewer.core.DxfParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final int M_OPEN = 1, M_FIT = 2, M_LAYERS = 3, M_BG = 4, M_INFO = 5;

    private FrameLayout root;
    private CadDisplay view;
    private String benchRenderer;
    private TextView status;
    private String fileName;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        Intent start = getIntent();
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String bench = debuggable && start != null ? start.getStringExtra("bench") : null; // benchmarks: debug builds only
        benchRenderer = bench != null ? start.getStringExtra("renderer") : null;
        String r = benchRenderer == null ? "" : benchRenderer;
        view = r.equals("canvas") ? new CadView(this) : new CadSurface(this, !r.contains("nomsaa"));
        if (r.contains("nocache")) ((CadSurface) view).setCacheDisabled(true);
        root.addView(view.view());
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xCC000000);
        status.setPadding(40, 24, 40, 24);
        status.setTextSize(15);
        status.setText("Open a DXF or DWG file");
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        setTitle("CAD Lite");
        if (bench != null) loadBench(new File(getFilesDir(), bench));
        else handleIntent(start);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view instanceof CadSurface) ((CadSurface) view).onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view instanceof CadSurface) ((CadSurface) view).onResume();
    }

    /** Benchmark mode (debug builds, started via adb). */
    private void loadBench(final File f) {
        busy = true;
        fileName = f.getName();
        setTitle(fileName + " (bench " + (benchRenderer == null ? "gl" : benchRenderer) + ")");
        showStatus("Opening " + fileName + "…");
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                final Drawing d = DxfParser.parse(new FileInputStream(f), null);
                final long ms = System.currentTimeMillis() - t0;
                runOnUiThread(() -> {
                    busy = false;
                    showStatus(null);
                    view.setDrawing(d);
                    new Bench(this, view, new File(getFilesDir(), "bench.txt"),
                            benchRenderer == null ? "gl" : benchRenderer).start(ms);
                });
            } catch (Throwable t) {
                fail("Bench load failed: " + t);
            }
        }, "bench-loader").start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) load(i.getData());
    }

    // ------------------------------------------------------------------ menu
    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, M_OPEN, 0, "Open").setIcon(android.R.drawable.ic_menu_upload)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        m.add(0, M_FIT, 1, "Fit").setIcon(android.R.drawable.ic_menu_zoom)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, M_LAYERS, 2, "Layers").setIcon(android.R.drawable.ic_menu_sort_by_size)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, M_BG, 3, "Light / dark background");
        m.add(0, M_INFO, 4, "Drawing info");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_OPEN: pick(); return true;
            case M_FIT: view.zoomExtents(); return true;
            case M_LAYERS: showLayers(); return true;
            case M_BG: view.setDark(!view.isDark()); return true;
            case M_INFO: showInfo(); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) load(data.getData());
    }

    // ------------------------------------------------------------------ loading
    private void showStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (s == null) status.setVisibility(TextView.GONE);
                else { status.setText(s); status.setVisibility(TextView.VISIBLE); }
            }
        });
    }

    private void load(final Uri uri) {
        if (busy) return;
        busy = true;
        fileName = displayName(uri);
        setTitle(fileName);
        showStatus("Opening " + fileName + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri), 1 << 16);
                    in.mark(64);
                    byte[] head = new byte[22];
                    int n = in.read(head);
                    in.reset();
                    String sig = n > 0 ? new String(head, 0, n, "ISO-8859-1") : "";
                    if (sig.startsWith("AC10") || sig.startsWith("AC2")) {
                        File dwg = new File(getCacheDir(), "in.dwg");
                        copy(in, dwg);
                        convertDwg(dwg);
                    } else if (sig.startsWith("AutoCAD Binary DXF")) {
                        in.close();
                        fail("Binary DXF is not supported. Save as ASCII DXF.");
                    } else {
                        parseDxf(in);
                    }
                } catch (Throwable t) {
                    fail(t instanceof OutOfMemoryError ? "File too large for this device" : "Cannot open file: " + t.getMessage());
                }
            }
        }, "loader").start();
    }

    private void parseDxf(InputStream in) throws IOException {
        try {
            final Drawing d = DxfParser.parse(in, new DxfParser.Progress() {
                @Override public void onProgress(String msg) { showStatus(msg); }
            });
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    busy = false;
                    if (d.batches.isEmpty() && d.texts.isEmpty() && d.fills.isEmpty()) {
                        showStatus("No model-space geometry found");
                    } else {
                        showStatus(null);
                    }
                    view.setDrawing(d);
                }
            });
        } finally {
            in.close();
        }
    }

    private void convertDwg(final File dwg) {
        showStatus("Converting DWG…");
        runOnUiThread(new Runnable() {
            @Override public void run() {
                new DwgConverter().convert(MainActivity.this, root, dwg, new File(getCacheDir(), "out.dxf"),
                        new DwgConverter.Callback() {
                            @Override public void done(final File dxf, String error) {
                                if (dxf == null) { fail(error); return; }
                                showStatus("Reading drawing…");
                                new Thread(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            parseDxf(new FileInputStream(dxf));
                                        } catch (Throwable t) {
                                            fail(t instanceof OutOfMemoryError ? "File too large for this device" : "Cannot read: " + t.getMessage());
                                        } finally {
                                            dxf.delete();
                                            dwg.delete();
                                        }
                                    }
                                }, "parser").start();
                            }
                        });
            }
        });
    }

    private void fail(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                busy = false;
                showStatus(view.getDrawing() == null ? "Open a DXF or DWG file" : null);
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void copy(InputStream in, File f) throws IOException {
        try (InputStream i = in; OutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = i.read(buf)) > 0) o.write(buf, 0, r);
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "Drawing" : name;
    }

    // ------------------------------------------------------------------ dialogs
    private void showLayers() {
        final Drawing d = view.getDrawing();
        if (d == null) return;
        final int n = d.layerNames.size();
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> d.layerNames.get(a).compareToIgnoreCase(d.layerNames.get(b)));
        String[] names = new String[n];
        boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) { names[i] = d.layerNames.get(order[i]); checked[i] = d.layerVisible[order[i]]; }
        new AlertDialog.Builder(this)
                .setTitle("Layers (" + n + ")")
                .setMultiChoiceItems(names, checked, (dlg, which, on) -> {
                    d.layerVisible[order[which]] = on;
                    view.refresh();
                })
                .setPositiveButton("Done", null)
                .setNeutralButton("All on", (dlg, w) -> { Arrays.fill(d.layerVisible, true); view.refresh(); })
                .setNegativeButton("All off", (dlg, w) -> { Arrays.fill(d.layerVisible, false); view.refresh(); })
                .show();
    }

    private void showInfo() {
        Drawing d = view.getDrawing();
        if (d == null) return;
        String msg = "File: " + fileName
                + "\nFormat version: " + d.version
                + "\nLayers: " + d.layerNames.size()
                + "\nLine segments: " + d.segmentCount
                + "\nTexts: " + d.texts.size()
                + "\nFilled areas: " + d.fills.size()
                + "\nWidth × height: " + fmt(d.maxX - d.minX) + " × " + fmt(d.maxY - d.minY);
        new AlertDialog.Builder(this).setTitle("Drawing info").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%,.2f", v);
    }
}
