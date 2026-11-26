package com.smitnk.sketcher.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameOpsTest {
    /** Applies a delete to a list of names and returns the open frame's name afterwards. */
    private fun openAfterDelete(names: List<String>, open: Int, del: Int): String {
        val l = names.toMutableList()
        l.removeAt(del)
        return l[FrameOps.indexAfterDelete(open, del, l.size)]
    }

    @Test fun deleteBeforeOpenKeepsSameFrameOpen() {
        assertEquals("c", openAfterDelete(listOf("a", "b", "c", "d"), open = 2, del = 0))
        assertEquals("c", openAfterDelete(listOf("a", "b", "c", "d"), open = 2, del = 1))
    }

    @Test fun deleteAfterOpenKeepsSameFrameOpen() {
        assertEquals("b", openAfterDelete(listOf("a", "b", "c", "d"), open = 1, del = 3))
    }

    @Test fun deleteOpenSelectsNeighbourAndClampsAtEnd() {
        assertEquals("d", openAfterDelete(listOf("a", "b", "c", "d"), open = 2, del = 2))
        assertEquals("c", openAfterDelete(listOf("a", "b", "c", "d"), open = 3, del = 3))
        assertEquals("b", openAfterDelete(listOf("a", "b"), open = 0, del = 0))
    }

    @Test fun moveKeepsTheOpenDrawingOpenForEveryCombination() {
        val base = listOf("a", "b", "c", "d", "e")
        for (open in base.indices) for (from in base.indices) for (to in base.indices) {
            val l = base.toMutableList()
            assertTrue(FrameOps.move(l, from, to))
            val idx = FrameOps.indexAfterMove(open, from, to)
            assertEquals("open=$open from=$from to=$to", base[open], l[idx])
        }
    }

    @Test fun moveRejectsBadIndices() {
        val l = mutableListOf(1, 2, 3)
        assertFalse(FrameOps.move(l, -1, 1))
        assertFalse(FrameOps.move(l, 0, 3))
        assertEquals(listOf(1, 2, 3), l)
        assertTrue(FrameOps.move(l, 1, 1))
        assertEquals(listOf(1, 2, 3), l)
    }

    @Test fun moveTargetStaysInsideTheList() {
        assertEquals(1, FrameOps.moveTarget(0, 1, 3))
        assertEquals(0, FrameOps.moveTarget(1, -1, 3))
        assertNull(FrameOps.moveTarget(0, -1, 3))
        assertNull(FrameOps.moveTarget(2, 1, 3))
        assertNull(FrameOps.moveTarget(5, 1, 3))
        assertNull(FrameOps.moveTarget(1, 0, 3))
    }

    @Test fun insertShiftsOpenIndexOnlyWhenInsertedAtOrBefore() {
        assertEquals(3, FrameOps.indexAfterInsert(2, 0))
        assertEquals(3, FrameOps.indexAfterInsert(2, 2))
        assertEquals(2, FrameOps.indexAfterInsert(2, 3))
    }

    @Test fun sequenceNamesAreZeroPaddedAndSortable() {
        assertEquals("frame_0001.png", FrameOps.sequenceName(0, 12))
        assertEquals("frame_0012.png", FrameOps.sequenceName(11, 12))
        assertEquals("frame_12345.png", FrameOps.sequenceName(12344, 20000))
        val names = (0 until 120).map { FrameOps.sequenceName(it, 120) }
        assertEquals(names, names.sorted())
        assertEquals(4, FrameOps.padWidth(1)); assertEquals(5, FrameOps.padWidth(10000)); assertEquals(4, FrameOps.padWidth(0))
    }

    @Test fun safeNameKeepsLettersAndNeverReturnsEmpty() {
        assertEquals("My_cat", FrameOps.safeName("My cat!"))
        assertEquals("Sketch", FrameOps.safeName("!!!"))
        assertEquals("Sketch", FrameOps.safeName(""))
        assertEquals("माझे", FrameOps.safeName("माझे"))
        assertEquals(60, FrameOps.safeName("x".repeat(200)).length)
        assertFalse(FrameOps.safeName("../../etc").contains('/'))
    }

    @Test fun counterIsOneBased() = assertEquals("3 / 12", FrameOps.counter(2, 12))

    @Test fun fpsCyclesThroughChoices() {
        assertEquals(listOf(6, 8, 12, 24), FrameOps.FPS_CHOICES)
        assertEquals(8, FrameOps.nextFps(6)); assertEquals(6, FrameOps.nextFps(24)); assertEquals(6, FrameOps.nextFps(7))
    }

    @Test fun coverRectCropsToAspectAndCentres() {
        // wide source into a square canvas: crop the sides
        assertArrayEquals(intArrayOf(250, 0, 500, 500), FrameOps.coverRect(1000, 500, 100, 100))
        // tall source into a square canvas: crop top and bottom
        assertArrayEquals(intArrayOf(0, 250, 500, 500), FrameOps.coverRect(500, 1000, 64, 64))
        // same aspect: whole image
        assertArrayEquals(intArrayOf(0, 0, 400, 300), FrameOps.coverRect(400, 300, 800, 600))
        // degenerate sizes do not crash
        assertEquals(4, FrameOps.coverRect(0, 0, 10, 10).size)
    }

    @Test fun flipbookClockStepsAtTheChosenRate() {
        val c = FlipbookClock(10)
        assertEquals(0, c.advance(0.05f))
        assertEquals(1, c.advance(0.05f))
        assertEquals(2, c.advance(0.21f))
        var total = 0
        repeat(100) { total += c.advance(1f / 60f) }
        assertTrue("got $total", total in 16..17) // 100/60 s at 10 fps
    }

    @Test fun flipbookClockDoesNotQueueCatchUpAfterAHiccup() {
        val c = FlipbookClock(12)
        assertEquals(24, c.advance(2f)) // the hiccup is honoured once...
        assertTrue(c.advance(0.001f) <= 1) // ...but nothing is left queued
    }

    @Test fun flipbookLoopsAndHandlesEmpty() {
        assertEquals(0, FlipbookClock.next(2, 1, 3))
        assertEquals(1, FlipbookClock.next(2, 5, 3))
        assertEquals(0, FlipbookClock.next(0, 3, 0))
        assertEquals(2, FlipbookClock.next(0, -1, 3))
    }

    @Test fun containTransformFitsWholeSourceCentred() {
        assertArrayEquals(floatArrayOf(0.5f, 0f, 100f), FrameOps.containTransform(2000, 1000, 1000, 700), 1e-4f)
        assertArrayEquals(floatArrayOf(1f, 250f, 0f), FrameOps.containTransform(500, 1000, 1000, 1000), 1e-4f)
        assertArrayEquals(floatArrayOf(2f, 0f, 0f), FrameOps.containTransform(512, 512, 1024, 1024), 1e-4f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), FrameOps.containTransform(0, 10, 10, 10), 1e-4f)
    }
}
