package com.smitnk.sketcher.model

/** How the editor adds a frame after the open one. */
enum class AddFrameKind { BLANK, DUPLICATE, FROM_IMAGE }

/** Pure helpers for the frame list (no Compose / Android): index bookkeeping, file names, flipbook timing. */
object FrameOps {
    /** Allowed flipbook speeds. */
    val FPS_CHOICES = listOf(6, 8, 12, 24)
    const val DEFAULT_FPS = 12

    /** Next entry of [FPS_CHOICES] after [fps] (wraps); unknown values restart at the first. */
    fun nextFps(fps: Int): Int {
        val i = FPS_CHOICES.indexOf(fps)
        return FPS_CHOICES[(i + 1) % FPS_CHOICES.size]
    }

    /** Index of the open frame after the frame at [deleted] was removed (list size is now [newSize], > 0). */
    fun indexAfterDelete(current: Int, deleted: Int, newSize: Int): Int {
        val i = if (deleted < current) current - 1 else current
        return i.coerceIn(0, (newSize - 1).coerceAtLeast(0))
    }

    /** Index of the open frame after the frame at [from] moved to position [to] (remove, then insert at [to]). */
    fun indexAfterMove(current: Int, from: Int, to: Int): Int = when {
        current == from -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    /** Index of the open frame after a frame was inserted at [at]. */
    fun indexAfterInsert(current: Int, at: Int): Int = if (at <= current) current + 1 else current

    /** Destination of a "move by [delta]" request, or null when it would leave the list. */
    fun moveTarget(from: Int, delta: Int, size: Int): Int? = (from + delta).takeIf { from in 0 until size && it in 0 until size && it != from }

    /** Removes the element at [from] and re-inserts it at [to]; false when either index is out of range. */
    fun <T> move(list: MutableList<T>, from: Int, to: Int): Boolean {
        if (from !in list.indices || to !in list.indices) return false
        if (from == to) return true
        list.add(to, list.removeAt(from))
        return true
    }

    /**
     * Source rectangle (x, y, w, h) of the largest centred crop of a [srcW] x [srcH] image that has the aspect ratio
     * of [dstW] x [dstH] ("cover" fit: no bars, nothing stretched).
     */
    fun coverRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return intArrayOf(0, 0, maxOf(srcW, 0), maxOf(srcH, 0))
        val srcAspect = srcW.toDouble() / srcH
        val dstAspect = dstW.toDouble() / dstH
        return if (srcAspect > dstAspect) {
            val w = Math.round(srcH * dstAspect).toInt().coerceIn(1, srcW)
            intArrayOf((srcW - w) / 2, 0, w, srcH)
        } else {
            val h = Math.round(srcW / dstAspect).toInt().coerceIn(1, srcH)
            intArrayOf(0, (srcH - h) / 2, srcW, h)
        }
    }

    /** Width of the zero padding for a sequence of [count] files (at least 4 digits: frame_0001). */
    fun padWidth(count: Int): Int = maxOf(4, count.coerceAtLeast(1).toString().length)

    /** "frame_0007.png" style name for the 0-based [index] of [count] frames (numbering starts at 1). */
    fun sequenceName(index: Int, count: Int, extension: String = "png", prefix: String = "frame"): String =
        "${prefix}_${(index + 1).toString().padStart(padWidth(count), '0')}.$extension"

    /** Folder / file safe version of a project name ("My cat!" -> "My_cat_"); never empty. */
    fun safeName(name: String): String = name.replace(Regex("[^\\p{L}\\p{M}\\p{N}_-]"), "_").trim('_').ifEmpty { "Sketch" }.take(60)

    /** "3 / 12" counter text for the 0-based [index]. */
    fun counter(index: Int, count: Int): String = "${index + 1} / $count"
}

/**
 * Flipbook timing: feed it elapsed seconds and it says how many frames to step. Carries the remainder so playback
 * stays on speed even when display frames do not line up with the chosen fps.
 */
class FlipbookClock(fps: Int = FrameOps.DEFAULT_FPS) {
    var fps: Int = fps.coerceAtLeast(1)
        set(v) { field = v.coerceAtLeast(1) }
    private var acc = 0f

    fun reset() { acc = 0f }

    /** Number of frames to advance after [dtSeconds] (0 when a frame is not due yet). */
    fun advance(dtSeconds: Float): Int {
        if (dtSeconds <= 0f) return 0
        acc += dtSeconds
        val step = 1f / fps
        val n = (acc / step).toInt()
        if (n > 0) acc -= n * step
        // Never let a long hiccup queue seconds of catch-up.
        if (acc > step) acc = step
        return n
    }

    companion object {
        /** Next frame index when stepping [steps] frames (always loops). */
        fun next(index: Int, steps: Int, count: Int): Int = if (count <= 0) 0 else ((index + steps) % count + count) % count
    }
}
