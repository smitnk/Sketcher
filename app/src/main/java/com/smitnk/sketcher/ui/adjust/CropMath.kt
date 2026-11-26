package com.smitnk.sketcher.ui.adjust

/** Normalised (0..1) crop rectangle over the photo. */
data class CropRect(val l: Float, val t: Float, val r: Float, val b: Float)

/** Move [c] by (dx, dy) (normalised), keeping its size and staying inside 0..1. */
fun moveRect(c: CropRect, dx: Float, dy: Float): CropRect {
    val x = (c.l + dx).coerceIn(0f, 1f - (c.r - c.l))
    val y = (c.t + dy).coerceIn(0f, 1f - (c.b - c.t))
    return CropRect(x, y, x + (c.r - c.l), y + (c.b - c.t))
}

enum class Corner { TL, TR, BL, BR }

/**
 * Drag [corner] of [c] to (nx, ny) (normalised). The opposite corner stays put; result stays inside 0..1 and is at
 * least [minSize] wide/tall. [ratio] = w/h in pixels (null = free); [photoAspect] = photoW/photoH.
 */
fun dragCorner(c: CropRect, corner: Corner, nx: Float, ny: Float, ratio: Float?, photoAspect: Float, minSize: Float = 0.1f): CropRect {
    val left = corner == Corner.TL || corner == Corner.BL
    val top = corner == Corner.TL || corner == Corner.TR
    val ax = if (left) c.r else c.l
    val ay = if (top) c.b else c.t
    val maxW = if (left) ax else 1f - ax
    val maxH = if (top) ay else 1f - ay
    val px = nx.coerceIn(0f, 1f)
    val py = ny.coerceIn(0f, 1f)
    var w = if (left) ax - px else px - ax
    var h = if (top) ay - py else py - ay
    if (ratio == null) {
        w = minOf(maxOf(w, minSize), maxW)
        h = minOf(maxOf(h, minSize), maxH)
    } else {
        val k = ratio / photoAspect // normalised w = k * normalised h
        val fitW = minOf(maxW, maxH * k)
        w = minOf(maxOf(maxOf(w, h * k), maxOf(minSize, minSize * k)), fitW)
        h = w / k
    }
    return CropRect(if (left) ax - w else ax, if (top) ay - h else ay, if (left) ax else ax + w, if (top) ay else ay + h)
}
