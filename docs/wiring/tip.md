# Pen tip query (camera / pen overlay)

`anim/TimelineQuery.kt` exposes where the drawing hand is, computed from the SAME cached timeline that drives the reveal
(`LayerTimeline.timelineFor`), by binary search. Pure, no allocation when you reuse a `TipState`.

```kotlin
class TipState(var x: Float, var y: Float, var layerIndex: Int, var strokeIndex: Int,
               var penDown: Boolean, var speed: Float, var layerId: Long)

// project layers (what the editor has): layerIndex = index into project.layers
LayerTimeline.tipAt(project.layers, progress, look, durationSec, out = tip)

// raw timeline (tests / exporters that already hold one): layerIndex = play-order index
TimelineQuery.tipAt(LayerTimeline.timelineFor(layers, look), effectiveProgress, durationSec, tip)
```

- `progress` 0..1 = `AnimationController.progress`. The layer overload applies `LookTiming.effectiveProgress` (onTwos), so
  the tip matches the revealed ink exactly; the timeline overload takes it as given.
- `x`, `y`: image px (same space as `Stroke.points`). `speed`: image px per real second (0 when resting).
- `penDown = true`: on the stroke `strokeIndex` of layer `layerIndex`, at the point the reveal has reached
  (pencil-reveal layers report the source stroke, not the internal piece).
- `penDown = false`: pen-up travel between strokes (smoothstep from the last end to the next start). The indices are those
  of the stroke about to be drawn. The human feels shorten the gap by up to 80 ms (the hand leaves during the stroke's tail).
  Before the first stroke the tip rests at its start; at progress 1 at the last end. Empty timeline: indices -1.
- Hidden / excluded layers are not on the timeline. `layerId` is stable if you prefer it to the index.
- Reuse one `TipState` per frame loop (the call overwrites it). Not thread-safe per instance.
