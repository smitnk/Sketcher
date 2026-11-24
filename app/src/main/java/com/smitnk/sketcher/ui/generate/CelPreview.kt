package com.smitnk.sketcher.ui.generate

import com.smitnk.sketcher.image.pipeline.GapClosing
import com.smitnk.sketcher.image.pipeline.SketchPipeline
import kotlin.math.max

/** Small flat-cel preview: [argb] (w x h) is downscaled to a long side of [maxSide] and filled with region colours. */
class CelPreviewImage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Pure JVM, run off the main thread. shortcut: ink = dark pixels of the (downscaled) picture, not the real AI line
 * art, so it is an approximation; upgrade by passing the generated outline raster when one exists.
 */
fun celPreview(argb: IntArray, w: Int, h: Int, gap: GapClosing, palette: Int, maxSide: Int = 128): CelPreviewImage {
    require(w > 0 && h > 0 && argb.size >= w * h)
    val k = max(w, h).toFloat() / maxSide
    val tw = if (k > 1f) max(1, (w / k).toInt()) else w
    val th = if (k > 1f) max(1, (h / k).toInt()) else h
    val small = IntArray(tw * th) { argb[(it / tw * h / th) * w + (it % tw) * w / tw] }
    val (map, cols) = SketchPipeline.celRegions(small, tw, th, null, gap, palette)
    return CelPreviewImage(IntArray(tw * th) { cols[map.labels[it]] or (0xFF shl 24) }, tw, th)
}
