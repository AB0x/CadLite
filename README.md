# CAD Lite — DXF / DWG viewer for Android

A small, fast viewer. Plain Java with the Android framework only (no libraries,
no AndroidX). It needs no permissions and makes no network access.

## Features
- Opens **DXF** (ASCII, R12 → 2018+) and **DWG** (R13 → 2018+, via LibreDWG/WebAssembly, fully on-device)
- Pan, pinch-zoom, double-tap zoom, **Fit** (zoom extents, stray far-away objects ignored)
- **Layers** on/off (frozen/off layers start hidden), dark/light background
- Shows in Android's **"Open with"** list for .dxf/.dwg (WhatsApp, Files, Gmail, Drive…)
- Supported entities: LINE, LWPOLYLINE/POLYLINE (bulges, widths, meshes, polyface), CIRCLE, ARC, ELLIPSE,
  SPLINE (NURBS), TEXT, MTEXT, ATTRIB, INSERT (nested blocks, arrays, mirrored), DIMENSION,
  LEADER (with arrowhead), MULTILEADER (leader lines, landing, arrowhead blocks such as _DotSmall,
  text or block content), SOLID, 3DFACE, HATCH (solid = filled, pattern = outline), POINT
- Colors: ACI, true color, BYLAYER, BYBLOCK. Text decoding: UTF-8 (R2007+) or the file's code page
  (e.g. ANSI_1256). `%%c %%d %%p` and `\U+XXXX` are decoded.
- **Arabic SHX fonts** (xarab.shx, xarb.shx, sosa.shx and other "*arab*" fonts) that store Arabic as
  keyboard-mapped Latin letters (e.g. `plhL` → حمام) are converted to real Arabic and drawn right-to-left,
  including MTEXT inline font switches.

Not included, to keep it basic: linetypes/line weights, hatch patterns, paper-space layouts,
xrefs, images, binary DXF, editing and measuring.

## Performance design
- **GPU rendering (OpenGL ES 2):** all geometry is uploaded to GPU memory once. Panning and zooming
  only send a new 4-number transform, so the cost per frame no longer grows with the size of the drawing.
- Segments are sorted along a space-filling curve into compact chunks, so off-screen chunks are skipped.
- Solid fills: convex shapes are batched as triangles; others use the stencil even-odd technique.
- Texts are drawn on a light layer above the GPU view, which redraws only when some text is readable.
- Slow GPUs: if full-geometry frames take more than ~28 ms while you move, a cached overview texture
  is used **only during the gesture**. Still views are always drawn from the real geometry, so lines stay crisp.
- DXF is streamed, each block is tessellated once and reused for every insert, and coordinates are
  stored relative to the drawing center, so large-coordinate drawings stay precise.
- The DWG engine (WebAssembly, ~7.8 MB raw, ~2 MB compressed in the APK) only loads when you open a DWG.

### Benchmark (emulator, 495k segments / 2,040 fills / 3,360 texts from a 1.1 MB DXF)
| Zoomed out | Old Canvas renderer | GPU renderer |
|---|---|---|
| App CPU time per frame | 112 ms | 0.2–1 ms |

Run it yourself: push to the `perf` branch; `.github/workflows/bench.yml` generates the test drawing
(`bench/make_test_dxf.py`), runs the app on an Android emulator and publishes results to the
`bench-results` branch. (The emulator has no real GPU, so frame rates there are not representative.)

## Build the APK

### Option A — GitHub (no setup)
1. Create a GitHub repo and push this folder.
2. Open **Actions → Build APK**. It runs on every push.
3. Download the **CadLite-apk** artifact and install `app-release.apk` on your phone
   (allow "Install unknown apps").

### Option B — Android Studio
Open the folder in Android Studio, then **Build → Build APK(s)**.
Command line: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

The release build is minified and signed with the debug key, so it installs directly.
For Play Store use, add your own signing key.

## Licence note
DWG support uses LibreDWG (via `@mlightcad/libredwg-web`), which is GPL‑3.0. That is fine for
personal or internal use. If you distribute the app, it must be GPL‑3.0 compatible. To ship
without GPL, remove `app/src/main/assets/dwg/` and the app becomes DXF-only.

## Project layout
```
app/src/main/java/com/cadlite/viewer/
  MainActivity.java     UI: open, fit, layers, background, info
  CadSurface.java       GPU view + text layer + touch gestures
  GlRenderer.java       OpenGL ES renderer (vertex buffers, stencil fills, overview cache)
  TextPainter.java      screen-space text drawing
  CadView.java          old Canvas renderer, kept only for benchmarks
  Bench.java            automated pan/zoom benchmark (debug builds)
  DwgConverter.java     DWG → DXF through LibreDWG WASM in a hidden WebView
  core/DxfParser.java   streaming DXF parser + tessellation
  core/Drawing.java     render-ready batches, extents, color resolve
  core/Geo.java, Mat.java, Aci.java
app/src/main/assets/dwg/  index.html + libredwg-web.js/.wasm
```
