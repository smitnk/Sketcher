package com.smitnk.sketcher.data

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.model.LayerKind
import com.smitnk.sketcher.model.Project

/** Capture [this] into a Compose-free snapshot. Call on the main thread (reads snapshot state). */
fun Project.toSnapshot(animationSeconds: Float = 0f): ProjectSnapshot = ProjectSnapshot(
    width = width,
    height = height,
    layers = layers.map { l ->
        LayerSnapshot(
            id = l.id,
            name = l.name,
            visible = l.visible,
            opacity = l.opacity,
            locked = l.locked,
            inAnimation = l.inAnimation,
            animPriority = l.animPriority,
            kind = l.kind.ordinal,
            // Stroke.points arrays are never mutated after creation, so sharing is safe.
            strokes = l.strokes.map { StrokeSnapshot(it.points, it.color, it.width, it.brush.ordinal, it.alpha) },
        )
    },
    selectedIndex = selectedIndex,
    backgroundVisible = backgroundVisible,
    backgroundOpacity = backgroundOpacity,
    animationSeconds = animationSeconds,
)

/**
 * Replace this project's contents with [s]. The background bitmap is NOT touched
 * (set `project.background` from the loaded bitmap yourself). Layers get fresh ids.
 */
fun Project.applySnapshot(s: ProjectSnapshot) {
    width = s.width
    height = s.height
    backgroundVisible = s.backgroundVisible
    backgroundOpacity = s.backgroundOpacity
    layers.clear()
    val brushes = BrushType.values()
    for (ls in s.layers) {
        val layer = newLayer(ls.name)
        layer.visible = ls.visible
        layer.opacity = ls.opacity
        layer.locked = ls.locked
        layer.inAnimation = ls.inAnimation
        layer.animPriority = ls.animPriority
        layer.kind = LayerKind.values().getOrElse(ls.kind) { LayerKind.MANUAL }
        for (st in ls.strokes) {
            layer.strokes.add(
                Stroke(st.points, st.color, st.width, brushes.getOrElse(st.brush) { BrushType.PEN }, st.alpha),
            )
        }
        layers.add(layer)
    }
    selectedIndex = s.selectedIndex.coerceIn(0, maxOf(0, layers.size - 1))
}
