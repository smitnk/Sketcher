package com.smitnk.sketcher.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Random

/** JVM micro-benchmark: per-frame allowance lookup must not allocate, and stays fast for 5k strokes. */
class AnimPerfTest {
    private fun bigTimeline(strokesPerLayer: Int = 1250, layers: Int = 4): DrawTimeline.Timeline {
        val r = Random(7)
        val specs = (1..layers).map { id ->
            val n = strokesPerLayer
            val len = FloatArray(n) { 5 + r.nextFloat() * 200 }
            val sx = FloatArray(n) { r.nextFloat() * 1000 }; val sy = FloatArray(n) { r.nextFloat() * 1000 }
            DrawTimeline.LayerSpec(id.toLong(), len, sx, sy, FloatArray(n) { sx[it] + len[it] }, sy, FloatArray(n) { 1f }, handSpeed = FloatArray(n) { 1f })
        }
        return DrawTimeline.build(specs)
    }

    private fun allocated(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)

    @Test fun fillAllowancesAllocatesNothingAndReturnsTheSameInstance() {
        val t = bigTimeline()
        val out = Allowances()
        t.fillAllowances(0.5f, out) // warm up + grow once
        val arr = out.values
        val tip = TipState()
        repeat(20_000) { t.fillAllowances(it / 20_000f, out); TimelineQuery.tipAt(t, it / 20_000f, 20f, tip) } // JIT
        val before = allocated()
        var sink = 0f
        val n = 200_000
        for (i in 0 until n) {
            val p = (i % 1000) / 1000f
            t.fillAllowances(p, out)
            sink += out.budget(3L)
            TimelineQuery.tipAt(t, p, 20f, tip)
            sink += tip.x
        }
        val bytes = allocated() - before
        assertSame(arr, out.values)
        assertTrue("allocated $bytes B over $n frames ($sink)", bytes < 64 * 1024L) // JIT/profiler noise only, 0 per frame
        println("PERF new: ${bytes.toDouble() / n} B/frame")
    }

    @Test fun legacyMapPathAllocatesPerFrameAndNewIsFaster() {
        val t = bigTimeline() // 5000 strokes
        assertEquals(5000, t.strokeCount)
        val out = Allowances()
        val n = 100_000
        var sink = 0f
        repeat(10_000) { sink += t.allowances(it / 10_000f)[1L]!!; t.fillAllowances(it / 10_000f, out) }
        val a0 = allocated(); val s0 = System.nanoTime()
        for (i in 0 until n) sink += t.allowances((i % 1000) / 1000f)[1L]!!
        val oldNs = (System.nanoTime() - s0).toDouble() / n; val oldB = (allocated() - a0).toDouble() / n
        val a1 = allocated(); val s1 = System.nanoTime()
        for (i in 0 until n) { t.fillAllowances((i % 1000) / 1000f, out); sink += out.budget(1L) }
        val newNs = (System.nanoTime() - s1).toDouble() / n; val newB = (allocated() - a1).toDouble() / n
        println("PERF 5k strokes: old map ${"%.0f".format(oldNs)} ns, ${"%.0f".format(oldB)} B/frame | new ${"%.0f".format(newNs)} ns, ${"%.2f".format(newB)} B/frame ($sink)")
        assertTrue(oldB > 100.0)
        assertTrue(newB < 1.0)
        assertTrue(newNs < 5_000) // far below a 16 ms frame
    }
}
