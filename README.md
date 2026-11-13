# Sketcher

Sketcher turns a photo into vector pen strokes and replays them as a time-lapse sketch,
stroke by stroke, on a canvas. Outline and shading live on separate layers, and you can draw
by hand on any layer (for example: automatic outline + your own hand-drawn shading layer).

Everything is pure Kotlin / Jetpack Compose. There are no native libraries and no OpenCV.

## Features (Batch 1)

- Import a photo (system photo picker) and generate strokes:
  grayscale, Gaussian blur, Sobel, non-max suppression + double threshold (Canny-style),
  Zhang-Suen thinning, chain tracing, Ramer-Douglas-Peucker simplification and
  nearest-neighbour stroke ordering so the pen flows naturally.
- Shading as 45-degree hatching (cross-hatching in the darkest band) on its own layer.
- Layers: "Outline", "Shading", "My Drawing". Reorder, show/hide, opacity, lock, rename, add, delete.
- Draw-along animation: layers play in layer order, strokes are revealed by length at about 60 fps.
  Total time 2-60 s, play / pause / restart / "show finished", per-layer "include in animation".
- Canvas: two-finger pinch-zoom and pan, one-finger drawing on the selected unlocked layer,
  brush (12 colours, size slider), stroke eraser, undo / redo.
- Reference photo can be hidden or faded (it is never part of an export).
- Export to Pictures/Sketcher: PNG (optional transparent background) or JPEG (white, quality 95),
  at x1 or x2 scale, or only the selected layer as a transparent PNG.
  On Android 8/9 files go to the app's own Pictures folder (no storage permission needed).

## Build (GitHub Actions)

Push to GitHub. The workflow `.github/workflows/android.yml` installs JDK 17, the Android SDK and
Gradle 8.10.2, then runs `gradle assembleDebug testDebugUnitTest`. Download the APK from the
run's artifact **Sketcher-debug**. The Gradle wrapper is intentionally not committed.

## Project layout

- `image/StrokeGenerator.kt` - pure Kotlin image-to-strokes pipeline (unit tested)
- `model/` - `Layer`, `Project`
- `anim/AnimationController.kt` - reveal logic
- `export/` - native-canvas renderer shared by screen and exporter, MediaStore saving
- `ui/` - Compose screens, layers sheet, dialogs, ViewModel

## Roadmap

- Batch 2: colour draw mode, fill-by-drawing, MP4 / GIF export, ONNX AI line-art mode
- Batch 3: SVG export, frame timeline
