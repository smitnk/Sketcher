package com.smitnk.sketcher.data

import com.smitnk.sketcher.look.LookSettings
import com.smitnk.sketcher.look.Paper
import com.smitnk.sketcher.look.PostFilter
import com.smitnk.sketcher.look.StrokeFeel
import com.smitnk.sketcher.model.LayerKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class ProjectCodecFramesTest {
    private fun layer(id: Long, name: String, pts: FloatArray = floatArrayOf(1f, 2f, 3f, 4f), png: ByteArray? = null) = LayerSnapshot(
        id, name, true, 0.5f, false, true, 1,
        listOf(StrokeSnapshot(pts, 0xFF112233.toInt(), 2f, 1, 0.9f)),
        LayerKind.AUTO_OUTLINE.ordinal, png, if (png != null) 7f else 0f,
    )

    private fun frames() = listOf(
        FrameSnapshot(10L, listOf(layer(1, "A")), 0, false, 0.3f, 8f),
        FrameSnapshot(11L, listOf(layer(2, "B1"), layer(3, "B2", floatArrayOf(5f, 6f), byteArrayOf(9, 8, 7))), 1, true, 1f, 20f),
        FrameSnapshot(12L, emptyList(), 0, true, 1f, 0f),
    )

    @Test fun v4RoundTripKeepsEveryFrame() {
        val snap = ProjectSnapshot.ofFrames(640, 480, frames(), 1)
        val back = ProjectCodec.decode(ProjectCodec.encode(snap))
        assertEquals(640, back.width); assertEquals(480, back.height)
        assertEquals(3, back.frames.size)
        assertEquals(1, back.currentFrameIndex)
        assertEquals(listOf(10L, 11L, 12L), back.frames.map { it.id })
        assertEquals(8f, back.frames[0].animationSeconds, 0f)
        assertFalse(back.frames[0].backgroundVisible)
        assertEquals(0.3f, back.frames[0].backgroundOpacity, 0f)
        assertEquals(listOf("A"), back.frames[0].layers.map { it.name })
        assertEquals(listOf("B1", "B2"), back.frames[1].layers.map { it.name })
        assertEquals(1, back.frames[1].selectedIndex)
        assertArrayEquals(floatArrayOf(5f, 6f), back.frames[1].layers[1].strokes[0].points, 0f)
        assertArrayEquals(byteArrayOf(9, 8, 7), back.frames[1].layers[1].inkPng)
        assertEquals(7f, back.frames[1].layers[1].revealWidthPx, 0f)
        assertNull(back.frames[0].layers[0].inkPng)
        assertTrue(back.frames[2].layers.isEmpty())
    }

    @Test fun mirrorFieldsDescribeTheOpenFrame() {
        val back = ProjectCodec.decode(ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 1)))
        assertEquals(listOf("B1", "B2"), back.layers.map { it.name })
        assertEquals(1, back.selectedIndex)
        assertEquals(20f, back.animationSeconds, 0f)
        assertTrue(back.backgroundVisible)
    }

    @Test fun openFrameIndexIsClampedOnDecode() {
        val snap = ProjectSnapshot.ofFrames(10, 10, frames(), 2)
        assertEquals(2, ProjectCodec.decode(ProjectCodec.encode(snap)).currentFrameIndex)
        assertEquals(2, ProjectSnapshot.ofFrames(10, 10, frames(), 99).currentFrameIndex)
        assertEquals(0, ProjectSnapshot.ofFrames(10, 10, frames(), -4).currentFrameIndex)
    }

    @Test fun legacyConstructorIsOneFrame() {
        val s = ProjectSnapshot(10, 10, listOf(layer(1, "L")), 0, true, 1f, 5f)
        assertEquals(1, s.frames.size)
        assertEquals(5f, s.frames[0].animationSeconds, 0f)
        val back = ProjectCodec.decode(ProjectCodec.encode(s))
        assertEquals(1, back.frames.size)
        assertEquals(listOf("L"), back.frames[0].layers.map { it.name })
    }

    /** Hand-built version-3 file (single frame, header order width,height,selected,bg...), as written before frames. */
    private fun v3Bytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.write("SKCH".toByteArray()); o.writeInt(3)
        o.writeInt(300); o.writeInt(200); o.writeInt(1)
        o.writeBoolean(false); o.writeFloat(0.6f); o.writeFloat(9f)
        o.writeInt(2)
        for ((i, n) in listOf("Outline", "Mine").withIndex()) {
            o.writeLong(i + 5L); o.writeUTF(n); o.writeBoolean(true); o.writeFloat(1f)
            o.writeBoolean(false); o.writeBoolean(true); o.writeInt(0)
            o.writeByte(if (i == 0) LayerKind.AUTO_OUTLINE.ordinal else LayerKind.MANUAL.ordinal)
            o.writeInt(1)
            o.writeInt(0xFF000000.toInt()); o.writeFloat(2f); o.writeInt(0); o.writeFloat(1f)
            o.writeInt(4); for (f in floatArrayOf(1f, 2f, 3f, 4f)) o.writeFloat(f)
            if (i == 0) { o.writeBoolean(true); o.writeFloat(3f); o.writeInt(2); o.write(byteArrayOf(1, 2)) } else o.writeBoolean(false)
        }
        return bos.toByteArray()
    }

    @Test fun v3LoadsAsSingleFrame() {
        val s = ProjectCodec.decode(v3Bytes())
        assertEquals(300, s.width); assertEquals(200, s.height)
        assertEquals(1, s.frames.size)
        assertEquals(0, s.currentFrameIndex)
        val f = s.frames[0]
        assertEquals(1, f.selectedIndex)
        assertFalse(f.backgroundVisible)
        assertEquals(0.6f, f.backgroundOpacity, 0f)
        assertEquals(9f, f.animationSeconds, 0f)
        assertEquals(listOf("Outline", "Mine"), f.layers.map { it.name })
        assertEquals(LayerKind.AUTO_OUTLINE.ordinal, f.layers[0].kind)
        assertArrayEquals(byteArrayOf(1, 2), f.layers[0].inkPng)
        // mirror fields agree with the single frame
        assertEquals(2, s.layers.size)
        assertEquals(9f, s.animationSeconds, 0f)
    }

    @Test fun v3ReEncodesAsV4() {
        val s = ProjectCodec.decode(ProjectCodec.encode(ProjectCodec.decode(v3Bytes())))
        assertEquals(1, s.frames.size)
        assertEquals(listOf("Outline", "Mine"), s.layers.map { it.name })
    }

    @Test fun duplicateFrameIdsAreMadeUnique() {
        val dup = listOf(
            FrameSnapshot(4L, listOf(layer(1, "A")), 0, true, 1f),
            FrameSnapshot(4L, listOf(layer(2, "B")), 0, true, 1f),
            FrameSnapshot(5L, listOf(layer(3, "C")), 0, true, 1f),
        )
        val back = ProjectCodec.decode(ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, dup, 0)))
        assertEquals(3, back.frames.map { it.id }.toSet().size)
        assertEquals(listOf("A", "B", "C"), back.frames.map { it.layers[0].name })
    }

    @Test fun corruptFrameCountIsRejected() {
        val bytes = ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 0))
        // header: magic(4) version(4) w(4) h(4) current(4) then frameCount at offset 20
        val bad = bytes.copyOf()
        bad[20] = 0x7f
        try { ProjectCodec.decode(bad); fail() } catch (e: IOException) { assertTrue(true) }
        val zero = bytes.copyOf()
        for (i in 20..23) zero[i] = 0
        try { ProjectCodec.decode(zero); fail() } catch (e: IOException) { assertTrue(true) }
        try { ProjectCodec.decode(bytes.copyOf(bytes.size - 5)); fail() } catch (e: IOException) { assertTrue(true) }
    }

    private val V7_BLOCK = 26 // input frame (23 bytes) + background (3 bytes), see ProjectCodec.writeFrameAndBackground

    @Test fun versionIsSeven() = assertEquals(7, ProjectCodec.VERSION)

    @Test fun lookRoundTrips() {
        val look = LookSettings(StrokeFeel.PEN_PLOTTER, Paper.ENGRAVING, true, PostFilter.HALFTONE, true, 77)
        val back = ProjectCodec.decode(ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 0, look)))
        assertEquals(look, back.look)
        assertEquals(LookSettings(), ProjectCodec.decode(ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 0))).look)
    }

    @Test fun v4FileWithoutLookLoadsWithDefaultLook() {
        val v5 = ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 0))
        val v4 = v5.copyOf(v5.size - 16 - V7_BLOCK) // look block (9 bytes) + motion block (7 bytes) + v7 block trail the frames
        v4[7] = 4 // version int, big endian, at offset 4..7
        val back = ProjectCodec.decode(v4)
        assertEquals(LookSettings(), back.look)
        assertEquals(3, back.frames.size)
    }

    @Test fun motionRoundTrips() {
        val look = LookSettings(showPen = true, penStyle = com.smitnk.sketcher.look.PenStyle.BRUSH, cameraFollow = true, cameraZoom = 2.2f)
        val back = ProjectCodec.decode(ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 0, look)))
        assertEquals(look, back.look)
    }

    @Test fun v5FileWithoutMotionLoadsWithMotionOff() {
        val look = LookSettings(StrokeFeel.PEN_PLOTTER, Paper.ENGRAVING, true, PostFilter.HALFTONE, true, 77, showPen = true, cameraFollow = true)
        val v6 = ProjectCodec.encode(ProjectSnapshot.ofFrames(10, 10, frames(), 0, look))
        val v5 = v6.copyOf(v6.size - 7 - V7_BLOCK)
        v5[7] = 5
        val back = ProjectCodec.decode(v5)
        assertEquals(look.copy(showPen = false, cameraFollow = false), back.look)
    }
}
