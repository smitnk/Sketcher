# GPU reveal (beta)

Pencil-reveal ink layers (`layer.inkTexture` + reveal strokes) are uncovered by an arrival map instead of a saveLayer mask.

- **Baker** `anim/ArrivalMap.kt` (pure Kotlin, JVM-tested): scanline capsule rasterisation of every reveal stroke (width `revealWidthPx`) with min-compositing. Value = cumulative length: stroke i starts at the sum of `Stroke.length` of earlier strokes (identical to the renderer's budget), plus walked distance inside the stroke. Unreached = `NEVER`. ~35 ms for 1024^2 / 1000 strokes. Coverage equals the mask union, so the complete state (full texture) does not pop.
- **Packing** `look/ArrivalReveal.kt`: grid <= 1024 px, arrival/total as 16 bit in R,G of ARGB_8888 (65535 = never). The shader samples texel centres, so bilinear filtering cannot corrupt the packed value. RGBA_F16 was rejected: F16 bitmaps are colour-managed (linear extended sRGB) so the channel is transformed when sampled, and 11 bits of mantissa is too coarse for long timelines.
- **Shader** (API 33+): `out = ink.eval(p) * (1 - smoothstep(allow - feather, allow, arrival))` (edge0 < edge1; the reversed form is undefined in GLSL). `allow = budget/total`, feather = 1.5 length px. One `drawRect`, no saveLayer.
- **Fallback**: API < 33, toggle off, `!canvas.isHardwareAccelerated` (bitmap/LayerCache canvas, software `lockCanvas`), or map still baking (async, one background thread, LRU of 6) -> existing saveLayer path. VideoExporter uses `lockHardwareCanvas()` first, so the GPU path applies there. A CPU threshold composite was not added: the saveLayer path is already fast and cached for static frames.
- **Toggle**: Processing sheet "GPU reveal (beta)", default on (API 33+). Log tag `Reveal`: `REVEAL_PATH=gpu|cpu`. CI emulator is API 31, so it only tests `cpu`.

## Docs consulted (Context7, developer.android.com)
- `RuntimeShader.setInputShader(String, Shader)` (API 33): assigns a `uniform shader`; throws IllegalArgumentException if the name is not declared.
- `RuntimeShader.setFloatUniform` (float / vec2 / ...): uniforms default to 0 until set.
- `Bitmap.Config.RGBA_F16` (API 26): 8 bytes/pixel, half floats, for wide gamut/HDR.
- `Canvas.isHardwareAccelerated()` (API 11): whether drawing is hardware accelerated; check before using hardware-only features.
- `BitmapShader` / `RenderEffect`: BitmapShader used for both children; RenderEffect not needed (no full-layer post pass).
