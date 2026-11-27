# CAD Lite — DXF / DWG viewer for Android

A small, fast viewer. Plain Java with the Android framework only (no libraries,
no AndroidX). It needs no permissions and makes no network access.

## Features
- Opens **DXF** (ASCII, R12 → 2018+) and **DWG** (R13 → 2018+, via LibreDWG/WebAssembly, fully on-device)
- Pan, pinch-zoom, double-tap zoom, **Fit** (zoom extents, stray far-away objects ignored)
- **Layers** on/off (frozen/off layers start hidden), dark/light background
- Shows in Android's **"Open with"** list for .dxf/.dwg (WhatsApp, Files, Gmail, Drive…)
- Supported entities: LINE, LWPOLYLINE/POLYLINE (bulges, meshes, polyface), CIRCLE, ARC, ELLIPSE,
  SPLINE (NURBS), TEXT, MTEXT, ATTRIB, INSERT (nested blocks, arrays, mirrored), DIMENSION,
  LEADER, SOLID, 3DFACE, HATCH (solid = filled, pattern = outline), POINT
- Colors: ACI, true color, BYLAYER, BYBLOCK. Text decoding: UTF‑8 (R2007+) or the file's code page
  (e.g. ANSI_1256 Arabic). `%%c %%d %%p` and `\U+XXXX` are decoded.

Not included, to keep it basic: linetypes/line weights, hatch patterns, paper-space layouts,
xrefs, images, binary DXF, editing and measuring.

## Performance design
- DXF is streamed and drawn straight into flat float arrays, which are drawn with `Canvas.drawLines`
  using hairline paint (GPU fast path). Chunks off screen are skipped.
- Each block is tessellated once and reused for every insert.
- Coordinates are stored relative to the drawing center, so UTM/large-coordinate drawings stay precise.
- The DWG engine (WebAssembly, ~7.8 MB raw, ~2 MB compressed in the APK) only loads when you open a DWG.

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
  CadView.java          renderer + touch gestures
  DwgConverter.java     DWG → DXF through LibreDWG WASM in a hidden WebView
  core/DxfParser.java   streaming DXF parser + tessellation
  core/Drawing.java     render-ready batches, extents, color resolve
  core/Geo.java, Mat.java, Aci.java
app/src/main/assets/dwg/  index.html + libredwg-web.js/.wasm
```
