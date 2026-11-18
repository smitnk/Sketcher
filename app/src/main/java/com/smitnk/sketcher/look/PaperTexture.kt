package com.smitnk.sketcher.look

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural, seeded paper (base colour x tooth/fibre multiply). Pure Kotlin on IntArray ARGB.
 * Idea (tooth = multiplied grain, fibres = short darker strands) follows the MIT-licensed JS demos of
 * Lemo-Opuscar (https://github.com/lemomo-ai/lemo-opuscar, (c) 2026 Lemomo (LemoLab)); code is original Kotlin.
 * Feature size scales with the image so a x4 export looks like the 1x one.
 */
object PaperTexture {
    fun hasTexture(p: Paper): Boolean = p != Paper.PLAIN

    /** Base paper colour, opaque ARGB. */
    fun baseColor(p: Paper): Int = when (p) {
        Paper.PLAIN -> 0xFFFFFFFF.toInt()
        Paper.CREAM -> 0xFFF3EAD3.toInt()
        Paper.PENCIL_TOOTH -> 0xFFF8F6F0.toInt()
        Paper.ENGRAVING -> 0xFFEEE3C8.toInt()
    }

    fun hash(seed: Int, x: Int, y: Int): Float {
        var h = seed * -0x61c88647 + x * 0x2c1b3c6d + y * 0x297a2d39
        h = (h xor (h ushr 15)) * -0x7a143595
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Value noise 0..1 at (x,y) with lattice period 1. */
    fun valueNoise(seed: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        val a = hash(seed, x0, y0)
        val b = hash(seed, x0 + 1, y0)
        val c = hash(seed, x0, y0 + 1)
        val d = hash(seed, x0 + 1, y0 + 1)
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy
    }

    /** Opaque ARGB paper of size [w] x [h]. Deterministic for the same arguments. */
    fun generate(paper: Paper, w: Int, h: Int, seed: Int): IntArray {
        val base = baseColor(paper)
        val out = IntArray(w * h)
        if (paper == Paper.PLAIN) { out.fill(base); return out }
        val unit = max(1f, min(w, h) / 600f) // image px per "design px"
        val mul = FloatArray(w * h)
        val (tooth, coarse) = when (paper) {
            Paper.PENCIL_TOOTH -> 0.11f to 0.05f
            Paper.ENGRAVING -> 0.07f to 0.05f
            else -> 0.05f to 0.05f // CREAM
        }
        for (y in 0 until h) {
            val fy = y / unit
            for (x in 0 until w) {
                val fx = x / unit
                // fine grain (cell ~1 design px) squared so only the peaks bite, plus broad mottling
                val g = valueNoise(seed, fx * 0.9f, fy * 0.9f) * 0.65f + valueNoise(seed + 7, fx * 2.1f, fy * 2.1f) * 0.35f
                val m = valueNoise(seed + 13, fx / 40f, fy / 40f)
                mul[y * w + x] = 1f - tooth * g * g * 2f - coarse * m
            }
        }
        when (paper) {
            Paper.ENGRAVING -> laidLines(mul, w, h, unit, seed)
            Paper.CREAM, Paper.PENCIL_TOOTH -> fibres(mul, w, h, unit, seed, if (paper == Paper.CREAM) 1f else 0.5f)
            else -> {}
        }
        val br = base shr 16 and 255
        val bg = base shr 8 and 255
        val bb = base and 255
        for (i in 0 until w * h) {
            val f = mul[i].coerceIn(0.5f, 1f)
            out[i] = (0xFF shl 24) or ((br * f + 0.5f).toInt() shl 16) or ((bg * f + 0.5f).toInt() shl 8) or (bb * f + 0.5f).toInt()
        }
        return out
    }

    /** Short soft darker strands at random angles (paper fibres). */
    private fun fibres(mul: FloatArray, w: Int, h: Int, unit: Float, seed: Int, amount: Float) {
        val n = (w.toLong() * h / (900L * unit * unit)).toInt().coerceAtMost(40_000)
        for (k in 0 until n) {
            val x0 = hash(seed + 101, k, 1) * w
            val y0 = hash(seed + 101, k, 2) * h
            val ang = hash(seed + 101, k, 3) * (2f * PI.toFloat())
            val len = (6f + hash(seed + 101, k, 4) * 14f) * unit
            val dark = (0.03f + hash(seed + 101, k, 5) * 0.05f) * amount
            val steps = len.toInt().coerceAtLeast(2)
            // slight curve
            val curv = (hash(seed + 101, k, 6) - 0.5f) * 0.12f
            var x = x0
            var y = y0
            var a = ang
            for (s in 0 until steps) {
                val ix = x.toInt()
                val iy = y.toInt()
                if (ix in 0 until w && iy in 0 until h) mul[iy * w + ix] -= dark
                a += curv
                x += cos(a) * len / steps
                y += sin(a) * len / steps
            }
        }
    }

    /** Faint horizontal laid lines with slight wobble (engraving / laid paper). */
    private fun laidLines(mul: FloatArray, w: Int, h: Int, unit: Float, seed: Int) {
        val pitch = 3f * unit
        for (y in 0 until h) {
            for (x in 0 until w) {
                val wob = valueNoise(seed + 31, x / (60f * unit), y / (60f * unit)) * pitch
                val ph = ((y + wob) / pitch)
                val f = ph - floor(ph)
                if (f < 0.3f) mul[y * w + x] -= 0.045f * (1f - f / 0.3f)
            }
        }
    }
}
