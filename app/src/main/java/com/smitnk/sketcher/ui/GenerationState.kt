package com.smitnk.sketcher.ui

import com.smitnk.sketcher.image.pipeline.EngineBusyException
import com.smitnk.sketcher.image.pipeline.GenerationAbort
import com.smitnk.sketcher.image.pipeline.ModelClosedException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pure (JVM-testable) generation / model-download state. */
sealed interface GenerationState {
    object Idle : GenerationState
    data class Running(val id: Long, val stage: String, val progress: Float, val startedAt: Long) : GenerationState
    data class Done(val id: Long) : GenerationState
    data class Failed(val id: Long, val reason: String, val fellBackTo: String?) : GenerationState
}

sealed interface GenerationEvent {
    data class Begin(val id: Long, val startedAt: Long) : GenerationEvent
    data class Stage(val id: Long, val stage: String, val progress: Float) : GenerationEvent
    data class Finish(val id: Long) : GenerationEvent
    data class Fail(val id: Long, val reason: String, val fellBackTo: String?) : GenerationEvent
    object Reset : GenerationEvent
}

object GenerationReducer {
    /** Next state, or null when the event is stale/invalid for [s] (late callbacks of an older run are dropped). */
    fun reduceOrNull(s: GenerationState, e: GenerationEvent): GenerationState? = when (e) {
        is GenerationEvent.Begin -> GenerationState.Running(e.id, "Starting", 0f, e.startedAt)
        is GenerationEvent.Stage ->
            if (s is GenerationState.Running && s.id == e.id) s.copy(stage = e.stage, progress = e.progress) else null
        is GenerationEvent.Finish ->
            if (s is GenerationState.Running && s.id == e.id) GenerationState.Done(e.id) else null
        is GenerationEvent.Fail ->
            if (s is GenerationState.Running && s.id == e.id) GenerationState.Failed(e.id, e.reason, e.fellBackTo) else null
        GenerationEvent.Reset -> GenerationState.Idle
    }
}

/** Single generation at a time; every callback carries its run id, so writes of an older run never land. */
class GenerationMachine {
    private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val state: StateFlow<GenerationState> get() = _state
    private val ids = AtomicLong(0)

    /** Starts a new run (superseding any running one) and returns its id. */
    fun begin(now: Long): Long {
        val id = ids.incrementAndGet()
        apply(GenerationEvent.Begin(id, now))
        return id
    }

    fun stage(id: Long, stage: String, progress: Float): Boolean = apply(GenerationEvent.Stage(id, stage, progress))
    fun finish(id: Long): Boolean = apply(GenerationEvent.Finish(id))
    fun fail(id: Long, reason: String, fellBackTo: String? = null): Boolean = apply(GenerationEvent.Fail(id, reason, fellBackTo))
    fun reset() { apply(GenerationEvent.Reset) }

    /** True while [id] is the run currently allowed to write. */
    fun isCurrent(id: Long): Boolean = (_state.value as? GenerationState.Running)?.id == id

    private fun apply(e: GenerationEvent): Boolean {
        while (true) {
            val cur = _state.value
            val next = GenerationReducer.reduceOrNull(cur, e) ?: return false
            if (_state.compareAndSet(cur, next)) return true
        }
    }
}

/** Per-model install state. */
sealed interface ModelState {
    object NotInstalled : ModelState
    data class Downloading(val progress: Float) : ModelState
    object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/** Pure transitions on the state map; use inside `MutableStateFlow.update { }` so concurrent writers cannot lose updates. */
object ModelStates {
    fun beginDownload(m: Map<String, ModelState>, id: String): Map<String, ModelState> =
        if (m[id] is ModelState.Downloading || m[id] == ModelState.Installed) m else m + (id to ModelState.Downloading(0f))

    /** Progress only counts while still downloading (late callbacks after cancel/finish are dropped). */
    fun progress(m: Map<String, ModelState>, id: String, p: Float): Map<String, ModelState> =
        if (m[id] is ModelState.Downloading) m + (id to ModelState.Downloading(p.coerceIn(0f, 1f))) else m

    fun installed(m: Map<String, ModelState>, id: String): Map<String, ModelState> = m + (id to ModelState.Installed)
    fun failed(m: Map<String, ModelState>, id: String, msg: String): Map<String, ModelState> = m + (id to ModelState.Failed(msg))
    fun cancelled(m: Map<String, ModelState>, id: String): Map<String, ModelState> =
        if (m[id] is ModelState.Downloading) m + (id to ModelState.NotInstalled) else m

    /** Applies an on-disk scan; never clobbers an active download. */
    fun scanned(m: Map<String, ModelState>, installedIds: Set<String>, allIds: Collection<String>): Map<String, ModelState> {
        var out = m
        for (id in allIds) {
            val cur = out[id]
            if (cur is ModelState.Downloading) continue
            if (id in installedIds) { if (cur != ModelState.Installed) out = out + (id to ModelState.Installed) }
            else if (cur == ModelState.Installed || cur == null) out = out + (id to ModelState.NotInstalled)
        }
        return out
    }
}

/** How an inference exception must be treated. */
enum class InferFailure { CANCELLED, BUSY, CLOSED, MODEL }

fun classifyInferFailure(t: Throwable): InferFailure = when (t) {
    is GenerationAbort -> InferFailure.CANCELLED
    is CancellationException -> InferFailure.CANCELLED
    is EngineBusyException -> InferFailure.BUSY
    is ModelClosedException -> InferFailure.CLOSED
    else -> InferFailure.MODEL
}

/**
 * Runs [primary]. Busy is waited out (up to [maxBusyRetries]) and never triggers [fallback]; only a genuine model
 * failure does. Exhausted busy / closed model abort the generation with a [GenerationAbort] (selection untouched).
 */
fun <T> inferWithPolicy(
    primary: () -> T,
    fallback: (Throwable) -> T,
    isCancelled: () -> Boolean = { false },
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    maxBusyRetries: Int = 6,
    busyDelayMs: Long = 750,
): T {
    var busy = 0
    while (true) {
        if (isCancelled()) throw CancellationException("cancelled")
        try {
            return primary()
        } catch (t: Throwable) {
            when (classifyInferFailure(t)) {
                InferFailure.CANCELLED -> throw t
                InferFailure.BUSY -> {
                    if (++busy > maxBusyRetries) throw GenerationAbort("AI engine busy - try again in a moment")
                    sleep(busyDelayMs)
                }
                InferFailure.CLOSED -> throw GenerationAbort("AI model was closed - try again")
                InferFailure.MODEL -> return fallback(t)
            }
        }
    }
}
