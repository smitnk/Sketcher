package com.smitnk.sketcher.data

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind
import java.io.ByteArrayOutputStream
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
            inkPng = l.inkPngBytes(),
            revealWidthPx = l.revealWidthPx,
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
        applyInk(layer, ls)
        for (st in ls.strokes) {
            layer.strokes.add(
                Stroke(st.points, st.color, st.width, brushes.getOrElse(st.brush) { BrushType.PEN }, st.alpha),
            )
        }
        layers.add(layer)
    }
    selectedIndex = s.selectedIndex.coerceIn(0, maxOf(0, layers.size - 1))
}

// PNG bytes cache so autosaves don't re-encode an unchanged texture (keyed by bitmap identity + revision).
private val pngCache = java.util.WeakHashMap<Layer, Triple<Bitmap, Int, ByteArray>>()

/** PNG of the layer's ink texture (null if none). Encodes only when the texture changed. Main thread. */
fun Layer.inkPngBytes(): ByteArray? {
    val bmp = inkTexture?.takeIf { !it.isRecycled } ?: return null
    synchronized(pngCache) {
        pngCache[this]?.let { if (it.first === bmp && it.second == inkRev) return it.third }
    }
    val bos = ByteArrayOutputStream()
    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) return null
    val bytes = bos.toByteArray()
    synchronized(pngCache) { pngCache[this] = Triple(bmp, inkRev, bytes) }
    return bytes
}

/** Decodes [ls]'s ink PNG (if any) into [layer] as a mutable ARGB_8888 bitmap. */
fun applyInk(layer: Layer, ls: LayerSnapshot) {
    val png = ls.inkPng ?: return
    try {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return
        layer.revealWidthPx = ls.revealWidthPx
        layer.inkTexture = bmp
    } catch (t: Throwable) {
        android.util.Log.w("SketcherLoad", "cannot decode ink texture", t)
    }
}
