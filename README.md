# Sketcher

Sketcher turns a photo or a rendered 3D pose into pen strokes and replays them as an animated
sketch, stroke by stroke, on a canvas. Outline, shading and colour fill live on separate layers,
and you can draw by hand on any layer (for example: automatic outline plus your own shading layer).

Pure Kotlin and Jetpack Compose. No native libraries, no OpenCV.

## What it does

- **Photo to strokes:** import a photo and generate an outline (Canny-style edges, thinning,
  path tracing, simplification, nearest-neighbour ordering so the pen flows).
- **Three draw modes:** Outline only, Outline + Shading (hatching on its own layer), and
  Colour outline + Fill (k-means colours with scribble-filled regions).
- **Manual layers:** add, reorder, rename, lock, hide and set opacity. Draw with a brush
  (12 colours, size slider), a stroke eraser, and undo / redo.
- **Animation:** layers play in order, strokes are revealed at about 60 fps (2-60 s total),
  with play, pause, restart, and per-layer "include in animation".
- **Export** to Pictures/Sketcher:
  - PNG (optional transparent background), JPEG (white, quality 95), at x1 or x2 scale.
  - Single layer as a transparent PNG.
  - MP4 and GIF of the animation.
  - On Android 8/9 files go to the app's own Pictures folder (no storage permission needed).
- The reference photo can be hidden or faded. It is never part of an export.

## 3D-pose workflow

1. Render a pose from your 3D tool (plain background is fine).
2. Import it into Sketcher and choose **Outline only**.
3. Generate, then tidy up by hand on a new layer if needed.
4. Select the **Outline** layer and export it as a transparent PNG.
5. Import the PNG as a frame or layer in FlipaClip or Ibis.

## Automation extras

The launch intent accepts these extras (used for CI screenshots and testing):

- `autodemo` (boolean): generate and animate the demo image.
- `demo` (string): `color` loads `assets/demo_color.png` instead of `demo.png`.
- `mode` (string): `outline`, `shading` or `color`. Sets the draw mode before generation.
- `showlayers`, `autoexport`, `autovideo` (boolean): open the layers sheet, export, or export a video.

## Build (GitHub Actions)

Push to GitHub. The workflow `.github/workflows/android.yml` installs JDK 17, the Android SDK and
Gradle 8.10.2, then runs `gradle assembleDebug testDebugUnitTest`. Download the APK from the
run's artifact **Sketcher-debug**. The Gradle wrapper is intentionally not committed.

The same workflow runs the app on an emulator. Its screenshots and reports are pushed to the
**ci-results** branch, so the results can be viewed without downloading artifacts.

## Project structure

```
app/src/main/java/com/smitnk/sketcher/
  MainActivity.kt              intent extras, Compose host
  image/StrokeGenerator.kt     photo -> outline and shading strokes (unit tested)
  image/ColorStrokeGenerator.kt  colour outline and fill (unit tested)
  image/ImageLoader.kt         photo and asset decoding
  model/                       Layer, Project
  anim/AnimationController.kt  stroke reveal timing
  export/                      shared renderer, PNG/JPEG, MP4 and GIF exporters, MediaStore saving
  ui/                          Compose screens, layers sheet, dialogs, ViewModel
app/src/main/assets/           demo.png, demo_color.png
app/src/test/                  unit tests for the generators
```

## Roadmap

- ONNX AI line-art model as an alternative to the classic edge pipeline.
- SVG export of the strokes.
- Frame timeline with onion skin.
- Stroke editing: select, move and reshape existing strokes.
