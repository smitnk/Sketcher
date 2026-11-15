package com.smitnk.sketcher.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportStateTest {
    @Test fun runningBlocksButDoneAndFailedDoNot() {
        assertNull(ExportState.Idle.blockedReason(false))
        assertTrue(ExportState.Running(ExportKind.IMAGE).blockedReason(false) != null)
        assertNull(ExportState.Done(emptyList()).blockedReason(false))
        assertNull(ExportState.Failed("x").blockedReason(false))
    }

    @Test fun generatingBlocksEverything() {
        assertTrue(ExportState.Idle.blockedReason(true) != null)
        assertTrue(ExportState.Done(emptyList()).blockedReason(true) != null)
    }

    @Test fun partialSuccessIsReportedHonestly() {
        val d = ExportState.Done(listOf(
            ExportResult(ExportKind.VIDEO, null, "Movies/Sketcher/a.mp4", true),
            ExportResult(ExportKind.GIF, null, null, false, "disk full"),
        ))
        assertFalse(d.allOk)
        assertTrue(d.anyOk)
        assertEquals("Movies/Sketcher/a.mp4\nGIF failed: disk full", d.summary())
    }

    @Test fun inkAlphaCurveIsMonotonicAndSolid() {
        assertEquals(0, InkAlpha.map(0))
        assertEquals(255, InkAlpha.map(255))
        assertEquals(255, InkAlpha.map((0.6f * 255).toInt() + 1))
        for (i in 1..255) assertTrue(InkAlpha.map(i) >= InkAlpha.map(i - 1))
    }
}
