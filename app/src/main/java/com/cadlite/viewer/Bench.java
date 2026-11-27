package com.cadlite.viewer;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Window;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Automated pan/zoom benchmark, started with:
 * am start -n com.cadlite.viewer/.MainActivity --es bench FILE --es renderer gl|canvas
 * Results and screenshot markers are written to files/bench.txt.
 */
final class Bench {
    private static final int FRAMES_PER_PHASE = 300; // ~5 s at 60 Hz

    private final CadDisplay disp;
    private final File out;
    private final String renderer;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Activity act;
    // per-frame CPU times collected during a phase (ms)
    private final java.util.List<Double> uiMs = java.util.Collections.synchronizedList(new java.util.ArrayList<Double>());
    private final java.util.List<Double> glMs = java.util.Collections.synchronizedList(new java.util.ArrayList<Double>());
    private volatile boolean collecting;

    Bench(Activity act, CadDisplay disp, File out, String renderer) {
        this.act = act;
        this.disp = disp;
        this.out = out;
        this.renderer = renderer;
        HandlerThread ht = new HandlerThread("frame-metrics");
        ht.start();
        // Window frames: UI-thread drawing + RenderThread command issue (CPU work per frame).
        act.getWindow().addOnFrameMetricsAvailableListener(new Window.OnFrameMetricsAvailableListener() {
            @Override public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
                if (!collecting) return;
                long ns = m.getMetric(FrameMetrics.DRAW_DURATION) + m.getMetric(FrameMetrics.SYNC_DURATION)
                        + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION);
                uiMs.add(ns / 1e6);
            }
        }, new Handler(ht.getLooper()));
        if (disp instanceof CadSurface) {
            // GL thread: time to issue the frame (CPU), without waiting for the GPU.
            ((CadSurface) disp).setFrameListener(ns -> { if (collecting) glMs.add(ns / 1e6); });
        }
    }

    private static String stats(java.util.List<Double> l) {
        java.util.List<Double> c;
        synchronized (l) { c = new java.util.ArrayList<>(l); }
        if (c.isEmpty()) return "n/a";
        java.util.Collections.sort(c);
        double sum = 0;
        for (double v : c) sum += v;
        return String.format(Locale.US, "avg %.2f ms, p90 %.2f ms, max %.1f ms (n=%d)",
                sum / c.size(), c.get((int) (c.size() * 0.9)), c.get(c.size() - 1), c.size());
    }

    void start(long parseMs) {
        log("renderer=" + renderer + " parse_ms=" + parseMs + " segments=" + disp.getDrawing().segmentCount
                + " texts=" + disp.getDrawing().texts.size() + " fills=" + disp.getDrawing().fills.size());
        h.postDelayed(() -> {
            disp.zoomExtents();
            h.postDelayed(() -> {
                log("SHOT_out");
                h.postDelayed(() -> phase("zoomed_out", 0.010f, () -> {
                    disp.zoomExtents();
                    View v = disp.view();
                    disp.zoomAt(6f, v.getWidth() / 2f, v.getHeight() / 2f);
                    h.postDelayed(() -> {
                        log("SHOT_in");
                        h.postDelayed(() -> phase("zoomed_in", 0.004f, () -> {
                            disp.zoomExtents();
                            log("DONE");
                        }), 4000);
                    }, 1500);
                }), 4000);
            }, 1500);
        }, 2000);
    }

    /** Animates pan + gentle zoom on every vsync and measures how many frames actually render. */
    private void phase(final String name, final float zoomAmp, final Runnable next) {
        final View v = disp.view();
        final long f0 = disp.framesRendered();
        final long u0 = disp instanceof CadSurface ? ((CadSurface) disp).uiFrames() : f0;
        final long t0 = System.nanoTime();
        uiMs.clear(); glMs.clear();
        collecting = true;
        disp.setInteracting(true);
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            int i;
            @Override public void doFrame(long frameTimeNanos) {
                i++;
                float ph = i / 30f;
                disp.pan((float) Math.sin(ph) * 6, (float) Math.cos(ph * 0.7) * 4);
                disp.zoomAt(1 + zoomAmp * (float) Math.sin(ph * 0.5), v.getWidth() / 2f, v.getHeight() / 2f);
                if (i < FRAMES_PER_PHASE) {
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
                double sec = (System.nanoTime() - t0) / 1e9;
                long gf = disp.framesRendered() - f0;
                long uf = (disp instanceof CadSurface ? ((CadSurface) disp).uiFrames() : disp.framesRendered()) - u0;
                collecting = false;
                disp.setInteracting(false);
                String cached = disp instanceof CadSurface ? " cache=" + ((CadSurface) disp).lastFrameCached() : "";
                log(String.format(Locale.US, "%s: vsyncs=%d seconds=%.2f render_fps=%.1f ui_fps=%.1f%s",
                        name, i, sec, gf / sec, uf / sec, cached));
                log("  " + name + " window CPU/frame: " + stats(uiMs));
                if (disp instanceof CadSurface) log("  " + name + " GL thread CPU/frame: " + stats(glMs));
                h.postDelayed(next, 500);
            }
        });
    }

    private void log(String s) {
        android.util.Log.i("CADBENCH", s);
        try (FileWriter w = new FileWriter(out, true)) {
            w.write(s + "\n");
        } catch (IOException ignored) {}
    }
}
