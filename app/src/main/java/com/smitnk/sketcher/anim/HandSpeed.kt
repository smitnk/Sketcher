package com.smitnk.sketcher.anim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot

/**
 * Human hand speed per stroke (pure Kotlin). Curvature: the 2/3 power law of handwriting, v ~ kappa^(-1/3), i.e. the hand
 * slows on tight curves; kappa is the stroke's mean curvature (total turning angle / length) relative to a reference
 * radius, clamped. Jitter: a small seeded +-8% factor so repeated strokes never move at exactly the same speed.
 * Deterministic: the same ids always give the same timeline (the timeline cache and export rely on it).
 */
object HandSpeed {
    /** Radius (px) at which the curvature factor is 1. */
    const val REF_RADIUS_PX = 80f
    const val MIN_MUL = 0.55f
    const val MAX_MUL = 1.3f
    const val JITTER = 0.08f

    /** Speed factor (>1 faster) from the polyline [p] (x,y pairs) of total length [length]. */
    fun curvatureMul(p: FloatArray, length: Float): Float {
        if (p.size < 6 || length < 1f) return MAX_MUL // a line or a dot: no curve to slow down for
        var turn = 0.0
        var px = p[2] - p[0]; var py = p[3] - p[1]
        var i = 4
        while (i + 1 < p.size) {
            val dx = p[i] - p[i - 2]; val dy = p[i + 1] - p[i - 1]
            if (hypot(dx, dy) > 1e-3f && hypot(px, py) > 1e-3f) {
                turn += abs(atan2((px * dy - py * dx).toDouble(), (px * dx + py * dy).toDouble()))
                px = dx; py = dy
            }
            i += 2
        }
        val kappa = (turn / length).coerceAtLeast(1e-6)
        return cbrt((1.0 / REF_RADIUS_PX) / kappa).toFloat().coerceIn(MIN_MUL, MAX_MUL)
    }

    /** Seeded jitter factor in [1 - JITTER, 1 + JITTER]. */
    fun jitter(seed: Long): Float {
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        val unit = ((z ushr 40).toInt() / 16777216f) // 0..1
        return 1f + (unit * 2f - 1f) * JITTER
    }

    /** Combined factor for stroke [index] of layer [layerId]. */
    fun strokeMul(layerId: Long, index: Int, p: FloatArray, length: Float): Float =
        curvatureMul(p, length) * jitter(layerId * 1_000_003L + index)
}
