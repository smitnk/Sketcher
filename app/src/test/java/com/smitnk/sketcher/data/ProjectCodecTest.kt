package com.smitnk.sketcher.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ProjectCodecTest {
    private fun sample() = ProjectSnapshot(
        width = 800, height = 600,
        layers = listOf(
            LayerSnapshot(
                1L, "Outline éन", true, 0.8f, false, true, 2,
                listOf(
                    StrokeSnapshot(floatArrayOf(1f, 2f, 3.5f, 4.5f), 0xFF222222.toInt(), 1.8f, 0, 1f),
                    StrokeSnapshot(floatArrayOf(), 0x80FF0000.toInt(), 3f, 3, 0.25f),
                ),
            ),
            LayerSnapshot(7L, "Empty", false, 1f, true, false, -1, emptyList()),
        ),
        selectedIndex = 1, backgroundVisible = false, backgroundOpacity = 0.4f, animationSeconds = 6.5f,
    )

    @Test fun roundTrip() {
        val a = sample()
        val b = ProjectCodec.decode(ProjectCodec.encode(a))
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertEquals(a.selectedIndex, b.selectedIndex)
        assertFalse(b.backgroundVisible)
        assertEquals(0.4f, b.backgroundOpacity, 0f)
        assertEquals(6.5f, b.animationSeconds, 0f)
        assertEquals(2, b.layers.size)
        for (i in a.layers.indices) {
            val x = a.layers[i]; val y = b.layers[i]
            assertEquals(x.id, y.id); assertEquals(x.name, y.name)
            assertEquals(x.visible, y.visible); assertEquals(x.opacity, y.opacity, 0f)
            assertEquals(x.locked, y.locked); assertEquals(x.inAnimation, y.inAnimation)
            assertEquals(x.animPriority, y.animPriority)
            assertEquals(x.strokes.size, y.strokes.size)
            for (j in x.strokes.indices) {
                val p = x.strokes[j]; val q = y.strokes[j]
                assertArrayEquals(p.points, q.points, 0f)
                assertEquals(p.color, q.color); assertEquals(p.width, q.width, 0f)
                assertEquals(p.brush, q.brush); assertEquals(p.alpha, q.alpha, 0f)
            }
        }
    }

    @Test fun badMagicAndTruncation() {
        val bytes = ProjectCodec.encode(sample())
        val bad = bytes.copyOf(); bad[0] = 'X'.code.toByte()
        try { ProjectCodec.decode(bad); fail() } catch (e: IOException) { assertTrue(true) }
        try { ProjectCodec.decode(bytes.copyOf(bytes.size / 2)); fail() } catch (e: IOException) { assertTrue(true) }
    }
}
