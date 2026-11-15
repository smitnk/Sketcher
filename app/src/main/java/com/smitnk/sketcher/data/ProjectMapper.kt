package com.smitnk.sketcher.data

import com.smitnk.sketcher.image.BrushType
import com.smitnk.sketcher.image.Stroke
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind
import java.io.ByteArrayOutputStream
import com.smitnk.sketcher.model.Frame
import com.smitnk.sketcher.model.Project

/** Capture [this] into a Compose-free snapshot. Call on the main thread (reads snapshot state). */
fun Project.toSnapshot(animationSeconds: Float = 0f): ProjectSnapshot {
    val cur = currentFrameIndex.coerceIn(0, frames.lastIndex)
    val fs = frames.mapIndexed { i, f ->
        val secs = if (i == cur && animationSeconds > 0f) animationSeconds else f.durationSec
        f.toSnapshot(secs, cached = i != cur)
    }
    return ProjectSnapshot.ofFrames(width, height, fs, cur)
}

// Layer snapshots of frames that are not being edited only change through frame operations, so they are reused
// while a cheap signature (properties, stroke count, first/last stroke identity, ink revision) is unchanged.
private class CachedLayer(val sig: Long, val snap: LayerSnapshot)

private val layerSnapCache = java.util.WeakHashMap<Layer, CachedLayer>()

private fun Layer.signature(): Long {
    var h = id
    h = h * 31 + name.hashCode()
    h = h * 31 + (if (visible) 1 else 0) + (if (locked) 2 else 0) + (if (inAnimation) 4 else 0)
    h = h * 31 + opacity.toBits()
    h = h * 31 + animPriority
    h = h * 31 + kind.ordinal
    h = h * 31 + strokes.size
    if (strokes.isNotEmpty()) {
        h = h * 31 + System.identityHashCode(strokes[0])
        h = h * 31 + System.identityHashCode(strokes[strokes.lastIndex])
    }
    h = h * 31 + (inkTexture?.let { System.identityHashCode(it) } ?: 0) + inkRev
    h = h * 31 + revealWidthPx.toBits()
    return h
}

private fun Layer.toSnapshot(): LayerSnapshot = LayerSnapshot(
    id = id,
    name = name,
    visible = visible,
    opacity = opacity,
    locked = locked,
    inAnimation = inAnimation,
    animPriority = animPriority,
    kind = kind.ordinal,
    inkPng = inkPngBytes(),
    revealWidthPx = revealWidthPx,
    // Stroke.points arrays are never mutated after creation, so sharing is safe.
    strokes = strokes.map { StrokeSnapshot(it.points, it.color, it.width, it.brush.ordinal, it.alpha) },
)

/** Snapshot of one frame; [cached] reuses unchanged layer snapshots (only for frames that are not being edited). */
fun Frame.toSnapshot(animationSeconds: Float, cached: Boolean = false): FrameSnapshot = FrameSnapshot(
    id = id,
    layers = layers.map { l ->
        if (!cached) {
            l.toSnapshot()
        } else {
            val sig = l.signature()
            synchronized(layerSnapCache) { layerSnapCache[l] }?.takeIf { it.sig == sig }?.snap
                ?: l.toSnapshot().also { s -> synchronized(layerSnapCache) { layerSnapCache[l] = CachedLayer(sig, s) } }
        }
    },
    selectedIndex = selectedIndex,
    backgroundVisible = backgroundVisible,
    backgroundOpacity = backgroundOpacity,
    animationSeconds = animationSeconds,
    source = background,
)

/**
 * Replace this project's contents with [s]: every frame, the open frame index and each frame's reference photo
 * (when the snapshot carries it). Layers get fresh ids; frames keep their persisted ids.
 */
fun Project.applySnapshot(s: ProjectSnapshot) {
    width = s.width
    height = s.height
    frames.clear()
    val brushes = BrushType.values()
    for (fs in s.frames) {
        val frame = frameWithId(fs.id)
        frame.backgroundVisible = fs.backgroundVisible
        frame.backgroundOpacity = fs.backgroundOpacity
        if (fs.animationSeconds > 0f) frame.durationSec = fs.animationSeconds
        if (fs.source != null) {
            frame.background = fs.source
            frame.sourceBitmap = fs.source
        }
        for (ls in fs.layers) {
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
            frame.layers.add(layer)
        }
        if (frame.layers.isEmpty()) frame.layers.add(newLayer("Layer 1")) // every frame must be drawable
        frame.selectedIndex = fs.selectedIndex.coerceIn(0, maxOf(0, frame.layers.size - 1))
        frames.add(frame)
    }
    if (frames.isEmpty()) frames.add(newFrame())
    currentFrameIndex = s.currentFrameIndex.coerceIn(0, frames.lastIndex)
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
