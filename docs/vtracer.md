# vtracer: Kotlin port, not Rust + JNI

Decision: `image/pipeline/VectorTrace.kt` is a pure-Kotlin port of the subset of
[vtracer](https://github.com/visioncortex/vtracer) (MIT, visioncortex) we need.

Why not build vtracer with cargo-ndk and call it over JNI:

- **Input is already clustered.** `LineFill` gives a `RegionMap`, so vtracer's colour clustering (most of its
  code) is not needed. What is left is boundary walking, simplification and curve fitting: about 200 lines.
- **Size.** A Rust `.so` per ABI (arm64, armv7, x86_64) adds several hundred KB each; the port adds a few KB of dex.
- **Build.** CI and dev machines have no Rust/NDK toolchain; the port needs nothing new.
- **Reliability.** No `UnsatisfiedLinkError` / native load failures, no ABI splits, no JNI marshalling of large arrays.

## What is ported

1. **Path walking**: crack-following on the pixel-corner lattice. Each boundary edge has its region on the
   right, so every loop (outer border and holes) closes. At diagonal pixel contacts the walk turns right first.
   All loops of a region go in one `d` string; draw with `fill-rule="evenodd"`.
2. **Simplification**: only corner vertices are kept, then closed-loop Ramer-Douglas-Peucker (1 px tolerance),
   which removes 1 px staircases (vtracer uses staircase removal + penalty simplification; RDP is close enough here).
3. **Spline fitting** ("spline" mode): Catmull-Rom through the points written as cubic Béziers. Turns sharper
   than 60 degrees (vtracer's default corner threshold) stay sharp corners with straight segments.

Output: `VectorTrace.trace(map, colours, minArea = 16)` -> `List<VectorShape>`, largest area first.
`SvgExporter` uses it for colour layers that have region data.

Limits: adjacent regions are traced independently, so smoothed shared borders can leave hairline gaps
(vtracer has the same behaviour in stacked mode). Upgrade path: trace shared edges once and reuse them.
