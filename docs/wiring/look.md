# Look: stroke feel + timing wiring (X1)

Pure geometry: `look/StrokeFeelGeometry.kt` (whiteboard ribbon/wobble/closure overshoot, plotter dot, scratch boil, cached
per stroke+seed; boil per drawing index). Timing: `anim/DrawTimeline.build(layers, feel)` (whiteboard slow-fast-slow,
plotter trapezoid + pen-up travel), `anim/LookTiming` (onTwos quantisation, boil drawing index).
MIT-only references: Lemo-Opuscar wb.js / plot.js / base.js (ported to Kotlin); no *.md content used.

All new params default to `LookSettings()` / 0 / 12f, so existing calls compile and behave as CLEAN.

## What callers must pass (VM holds `lookSettings`)
- Screen playback: `animation.allowances(layers, look)`  (uses its own `durationSec`)
  and `layerCache.draw(..., look = look, drawing = animation.drawingIndex(look))`.
  When not animating, `drawing = 0` (static boil frame).
- Export (MP4/GIF/PNG): `Renderer.drawPartial(canvas, w, h, layers, progress, look, durationSec)`
  (computes onTwos quantisation + boil drawing index itself); `Renderer.drawLayers(..., look = look)` for the final still.
- Same call sites => identical look on screen and in exports. `Renderer.allowancesFor(layers, progress, look, durationSec)`.
- `LayerTimeline.allowances/timelineFor(layers, ..., look)`: timeline cache is keyed by feel.
- SCRATCH_BOIL bypasses the LayerCache bitmap (re-seeds every 1/12 s); other feels are cached (feel is in the cache key).
- Live stroke preview: pass `look` to `drawStroke` too (done inside LayerCache.draw).
