# Perf wiring

## OnnxLineArtModel
- `fun warmUp()` — background session load + dummy inference. Call once when the editor opens (SketchViewModel `init`, or after `model()` is first built):
  `model().warmUp()`.
- `var detail: Float` — set before generating (`model().detail = settings.detail`); >0.7 uses 640 px long side, else 512.
- CPU + XNNPACK by default (falls back to plain CPU if XNNPACK is unavailable). NNAPI is opt-in: `OnnxLineArtModel(app, useNnapi = true)`.

## Canvas layer cache
Internal to SketchCanvas (`Renderer.LayerCache`); no wiring needed. Caches fully revealed layers below the live/animating layer as a screen-scale bitmap (<= 4096 px side, <= 10 MP, else falls back to direct drawing).
