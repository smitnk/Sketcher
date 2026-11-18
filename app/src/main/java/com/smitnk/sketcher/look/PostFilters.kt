package com.smitnk.sketcher.look

import kotlin.math.PI
import kotlin.math.cos

/**
 * 1-bit post filters on IntArray ARGB (in place). Atkinson error diffusion and Bayer ordered dithering are
 * generic algorithms; structure follows the MIT-licensed one-bit demo of Lemo-Opuscar
 * (https://github.com/lemomo-ai/lemo-opuscar, (c) 2026 Lemomo (LemoLab)). Output pixels are 0xFF000000 or 0xFFFFFFFF.
 */
object PostFilters {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** 8x8 Bayer matrix, values 0..63, built recursively. */
    val BAYER8: IntArray = bayer(3)

    private fun bayer(n: Int): IntArray {
        var m = intArrayOf(0)
        var size = 1
        repeat(n) {
            val ns = size * 2
            val nm = IntArray(ns * ns)
            for (y in 0 until size) for (x in 0 until size) {
                val v = m[y * size + x] * 4
                nm[y * ns + x] = v
                nm[y * ns + x + size] = v + 2
                nm[(y + size) * ns + x] = v + 3
                nm[(y + size) * ns + x + size] = v + 1
            }
            m = nm; size = ns
        }
        return m
    }

    /** Bayer threshold in (0,1) for pixel (x,y). */
    fun bayerThreshold(x: Int, y: Int): Float = (BAYER8[(y and 7) * 8 + (x and 7)] + 0.5f) / 64f

    /** Luminance 0 (black)..1 (white) of an ARGB pixel composited on white. */
    fun gray(p: Int): Float {
        val a = (p ushr 24) / 255f
        val l = (0.299f * (p shr 16 and 255) + 0.587f * (p shr 8 and 255) + 0.114f * (p and 255)) / 255f
        return l * a + (1f - a)
    }

    fun apply(px: IntArray, w: Int, h: Int, filter: PostFilter, seed: Int = 1) {
        when (filter) {
            PostFilter.NONE -> {}
            PostFilter.ONE_BIT_ATKINSON -> atkinson(px, w, h)
            PostFilter.ONE_BIT_BAYER -> bayerDither(px, w, h)
            PostFilter.HALFTONE -> halftone(px, w, h, seed)
        }
    }

    /** Transparent-PNG variant: dithers the ink composited on white, then writes black ink (opaque) / transparent paper. */
    fun applyToInk(px: IntArray, w: Int, h: Int, filter: PostFilter, seed: Int = 1) {
        if (filter == PostFilter.NONE) return
        apply(px, w, h, filter, seed)
        for (i in 0 until w * h) px[i] = if (px[i] == BLACK) BLACK else 0
    }

    fun atkinson(px: IntArray, w: Int, h: Int) {
        val a = FloatArray(w * h) { gray(px[it]) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val o = if (a[i] < 0.5f) 0f else 1f
            val e = (a[i] - o) / 8f
            px[i] = if (o == 0f) BLACK else WHITE
            if (x + 1 < w) a[i + 1] += e
            if (x + 2 < w) a[i + 2] += e
            if (y + 1 < h) {
                if (x > 0) a[i + w - 1] += e
                a[i + w] += e
                if (x + 1 < w) a[i + w + 1] += e
            }
            if (y + 2 < h) a[i + 2 * w] += e
        }
    }

    fun bayerDither(px: IntArray, w: Int, h: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            px[i] = if (gray(px[i]) > bayerThreshold(x, y)) WHITE else BLACK
        }
    }

    /** Dot size (image px) of the halftone cell for a given image size. */
    fun halftoneCell(w: Int, h: Int): Float = (minOf(w, h) / 110f).coerceIn(3f, 14f)

    /** 45-degree dot screen: ink where the pixel is darker than a cosine "spot function" peaking at cell centres. */
    fun halftone(px: IntArray, w: Int, h: Int, @Suppress("UNUSED_PARAMETER") seed: Int = 1) {
        val cell = halftoneCell(w, h)
        val k = 2f * PI.toFloat() / cell
        val c45 = 0.70710678f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val u = (x + y) * c45
            val v = (x - y) * c45
            val spot = 0.5f + 0.25f * (cos(u * k) + cos(v * k)) // 0..1, 1 at cell centre
            // dark gray -> large dot: ink where (1 - gray) > 1 - spot  <=>  gray < spot
            px[i] = if (gray(px[i]) < spot) BLACK else WHITE
        }
    }
}
