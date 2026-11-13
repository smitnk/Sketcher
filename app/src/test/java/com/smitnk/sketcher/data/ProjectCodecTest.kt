package com.smitnk.sketcher.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.smitnk.sketcher.model.LayerKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
                LayerKind.AUTO_SHADING.ordinal,
            ),
            LayerSnapshot(7L, "Empty", false, 1f, true, false, -1, emptyList(), LayerKind.MANUAL.ordinal),
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
            assertEquals(x.kind, y.kind)
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

    /** Hand-built version-1 file (no per-layer kind byte), as written by older app versions. */
    private fun v1Bytes(w: Int = 100, h: Int = 50, layerNames: List<String> = listOf("Outline", "Colour Fill", "Shading", "My Drawing")): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.write("SKCH".toByteArray()); o.writeInt(1)
        o.writeInt(w); o.writeInt(h); o.writeInt(0)
        o.writeBoolean(true); o.writeFloat(1f); o.writeFloat(0f)
        o.writeInt(layerNames.size)
        for ((i, n) in layerNames.withIndex()) {
            o.writeLong(i.toLong()); o.writeUTF(n); o.writeBoolean(true); o.writeFloat(1f)
            o.writeBoolean(false); o.writeBoolean(true); o.writeInt(-1)
            o.writeInt(1)
            o.writeInt(0xFF000000.toInt()); o.writeFloat(2f); o.writeInt(0); o.writeFloat(1f)
            o.writeInt(4); for (f in floatArrayOf(1f, 2f, 3f, 4f)) o.writeFloat(f)
        }
        return bos.toByteArray()
    }

    @Test fun inkTextureRoundTrips() {
        val png = byteArrayOf(1, 2, 3, 4, 5)
        val snap = ProjectSnapshot(
            10, 10,
            listOf(
                LayerSnapshot(1L, "Outline", true, 1f, false, true, 0, emptyList(), LayerKind.AUTO_OUTLINE.ordinal, png, 11f),
                LayerSnapshot(2L, "Plain", true, 1f, false, true, 0, emptyList()),
            ),
            0, true, 1f,
        )
        val back = ProjectCodec.decode(ProjectCodec.encode(snap))
        assertArrayEquals(png, back.layers[0].inkPng)
        assertEquals(11f, back.layers[0].revealWidthPx, 0f)
        assertEquals(null, back.layers[1].inkPng)
    }

    @Test fun version1InfersKindFromName() {
        val s = ProjectCodec.decode(v1Bytes())
        assertEquals(4, s.layers.size)
        assertEquals(listOf(LayerKind.AUTO_OUTLINE, LayerKind.AUTO_FILL, LayerKind.AUTO_SHADING, LayerKind.MANUAL).map { it.ordinal }, s.layers.map { it.kind })
        assertEquals(-1, s.layers[0].animPriority)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), s.layers[3].strokes[0].points, 0f)
    }

    @Test fun rejectsHugeCountsAndBadSize() {
        val ok = v1Bytes()
        // point count of first stroke lives at a fixed offset: header(4+4+12+1+4+4+4)=33, layer: 8+2+7+1+4+1+1+4+4=32, stroke 16 -> n
        val nOff = 33 + 32 + 16
        val bad = ok.copyOf()
        DataOutputStream(object : java.io.OutputStream() {
            var pos = nOff
            override fun write(b: Int) { bad[pos++] = b.toByte() }
        }).writeInt(40_000_000)
        try { ProjectCodec.decode(bad); fail() } catch (e: IOException) { assertTrue(true) }
        try { ProjectCodec.decode(v1Bytes(w = 0)); fail() } catch (e: IOException) { assertTrue(true) }
        try { ProjectCodec.decode(v1Bytes(h = 100_000)); fail() } catch (e: IOException) { assertTrue(true) }
    }
}
