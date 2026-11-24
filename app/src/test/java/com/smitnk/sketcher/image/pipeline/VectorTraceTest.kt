package com.smitnk.sketcher.image.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorTraceTest {
    /** 0 = background, 1 = square 4..11, 2 = ring 20..35 with hole 24..31 (hole belongs to region 0). */
    private fun map(): RegionMap {
        val w = 40; val h = 40
        val l = IntArray(w * h)
        for (y in 4..11) for (x in 4..11) l[y * w + x] = 1
        for (y in 20..35) for (x in 20..35) if (x !in 24..31 || y !in 24..31) l[y * w + x] = 2
        return RegionMap(w, h, l, 3)
    }

    @Test fun squareAndRing() {
        val shapes = VectorTrace.trace(map(), intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        assertEquals(3, shapes.size)
        assertEquals(listOf(0, 2, 1), shapes.map { it.regionId }) // largest area first
        assertEquals(64, shapes.first { it.regionId == 1 }.area)
        for (s in shapes) {
            assertTrue(s.pathData.startsWith("M"))
            assertTrue(s.pathData.endsWith("Z"))
        }
        assertEquals(1, shapes.first { it.regionId == 1 }.pathData.count { it == 'M' })
        assertEquals("M12 4L12 12L4 12L4 4Z", shapes.first { it.regionId == 1 }.pathData) // sharp corners stay straight
        assertEquals(2, shapes.first { it.regionId == 2 }.pathData.count { it == 'M' }) // outer + hole
        assertEquals(4, shapes.first { it.regionId == 0 }.pathData.count { it == 'M' }) // border, 2 holes, ring centre
    }

    @Test fun minAreaDropsSmallRegions() {
        assertEquals(listOf(0, 2), VectorTrace.trace(map(), IntArray(3), minArea = 100).map { it.regionId })
    }
}
