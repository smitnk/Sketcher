package com.smitnk.sketcher.ui

import com.smitnk.sketcher.image.pipeline.EngineBusyException
import com.smitnk.sketcher.image.pipeline.GenerationAbort
import com.smitnk.sketcher.image.pipeline.InferenceTimeoutException
import com.smitnk.sketcher.image.pipeline.ModelClosedException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GenerationStateTest {

    @Test fun begin_stage_finish() {
        val m = GenerationMachine()
        val id = m.begin(100)
        assertEquals(GenerationState.Running(id, "Starting", 0f, 100), m.state.value)
        assertTrue(m.stage(id, "Tracing", 0.4f))
        assertEquals(GenerationState.Running(id, "Tracing", 0.4f, 100), m.state.value)
        assertTrue(m.finish(id))
        assertEquals(GenerationState.Done(id), m.state.value)
    }

    @Test fun lateStageAfterResetIsDropped() {
        val m = GenerationMachine()
        val id = m.begin(0)
        m.reset()
        assertFalse(m.stage(id, "late", 0.5f))
        assertEquals(GenerationState.Idle, m.state.value)
    }

    @Test fun supersededRunCannotWriteOrFinish() {
        val m = GenerationMachine()
        val old = m.begin(0)
        val new = m.begin(10)
        assertFalse(m.stage(old, "old", 0.9f))
        assertFalse(m.finish(old))
        assertFalse(m.fail(old, "x"))
        assertTrue(m.isCurrent(new))
        assertEquals(GenerationState.Running(new, "Starting", 0f, 10), m.state.value)
    }

    @Test fun failedKeepsReasonAndFallback() {
        val m = GenerationMachine()
        val id = m.begin(0)
        assertTrue(m.fail(id, "boom", "informative"))
        assertEquals(GenerationState.Failed(id, "boom", "informative"), m.state.value)
        assertFalse(m.stage(id, "after", 1f)) // terminal
    }

    @Test fun modelStateProgressOnlyWhileDownloading() {
        var s = mapOf<String, ModelState>("a" to ModelState.NotInstalled)
        s = ModelStates.progress(s, "a", 0.5f)
        assertEquals(ModelState.NotInstalled, s["a"])
        s = ModelStates.beginDownload(s, "a")
        s = ModelStates.progress(s, "a", 0.5f)
        assertEquals(ModelState.Downloading(0.5f), s["a"])
        s = ModelStates.cancelled(s, "a")
        s = ModelStates.progress(s, "a", 0.9f) // late callback
        assertEquals(ModelState.NotInstalled, s["a"])
    }

    @Test fun modelStateFailAndInstallAndScan() {
        var s = ModelStates.beginDownload(emptyMap(), "a")
        s = ModelStates.failed(s, "a", "HTTP 500")
        assertEquals(ModelState.Failed("HTTP 500"), s["a"])
        s = ModelStates.beginDownload(s, "a") // retry allowed
        assertTrue(s["a"] is ModelState.Downloading)
        val scanned = ModelStates.scanned(s, emptySet(), listOf("a", "b"))
        assertTrue(scanned["a"] is ModelState.Downloading) // scan never clobbers a download
        assertEquals(ModelState.NotInstalled, scanned["b"])
        s = ModelStates.installed(s, "a")
        assertEquals(ModelState.NotInstalled, ModelStates.scanned(s, emptySet(), listOf("a"))["a"])
    }

    @Test fun classification() {
        assertEquals(InferFailure.BUSY, classifyInferFailure(EngineBusyException()))
        assertEquals(InferFailure.CANCELLED, classifyInferFailure(CancellationException()))
        assertEquals(InferFailure.CANCELLED, classifyInferFailure(GenerationAbort("x")))
        assertEquals(InferFailure.CLOSED, classifyInferFailure(ModelClosedException()))
        assertEquals(InferFailure.MODEL, classifyInferFailure(InferenceTimeoutException("t")))
        assertEquals(InferFailure.MODEL, classifyInferFailure(IllegalStateException("could not create ONNX session")))
    }

    @Test fun busyIsRetriedAndNeverFallsBack() {
        var calls = 0
        var fellBack = false
        val slept = ArrayList<Long>()
        val r = inferWithPolicy(
            primary = { if (++calls < 3) throw EngineBusyException() else "ok" },
            fallback = { fellBack = true; "fb" },
            sleep = { slept.add(it) },
        )
        assertEquals("ok", r)
        assertEquals(3, calls)
        assertFalse(fellBack)
        assertEquals(2, slept.size)
    }

    @Test fun persistentBusyAbortsWithoutFallback() {
        var fellBack = false
        try {
            inferWithPolicy(primary = { throw EngineBusyException() }, fallback = { fellBack = true; "fb" }, sleep = {}, maxBusyRetries = 2)
            fail("expected abort")
        } catch (e: GenerationAbort) {
            assertTrue(e.reason.contains("busy"))
        }
        assertFalse(fellBack)
    }

    @Test fun modelFailureFallsBackOnce() {
        val boom = InferenceTimeoutException("slow")
        var got: Throwable? = null
        val r = inferWithPolicy(primary = { throw boom }, fallback = { got = it; "fb" }, sleep = {})
        assertEquals("fb", r)
        assertSame(boom, got)
    }

    @Test fun cancellationPropagatesWithoutFallback() {
        var fellBack = false
        try {
            inferWithPolicy(primary = { throw CancellationException("c") }, fallback = { fellBack = true; "fb" }, sleep = {})
            fail()
        } catch (e: CancellationException) { /* ok */ }
        assertFalse(fellBack)
    }

    @Test fun staleRunStopsBeforeCallingModel() {
        var called = false
        try {
            inferWithPolicy(primary = { called = true; "x" }, fallback = { "fb" }, isCancelled = { true }, sleep = {})
            fail()
        } catch (e: CancellationException) { /* ok */ }
        assertFalse(called)
    }

    @Test fun closedModelAborts() {
        try {
            inferWithPolicy(primary = { throw ModelClosedException() }, fallback = { "fb" }, sleep = {})
            fail()
        } catch (e: GenerationAbort) { /* ok */ }
    }
}
