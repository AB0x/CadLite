package com.cadlite.viewer;

import android.os.Handler;
import android.os.Looper;
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

    Bench(CadDisplay disp, File out, String renderer) {
        this.disp = disp;
        this.out = out;
        this.renderer = renderer;
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
                disp.setInteracting(false);
                log(String.format(Locale.US, "%s: vsyncs=%d seconds=%.2f render_fps=%.1f ui_fps=%.1f",
                        name, i, sec, gf / sec, uf / sec));
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
