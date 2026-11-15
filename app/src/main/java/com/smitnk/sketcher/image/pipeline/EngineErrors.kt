package com.smitnk.sketcher.image.pipeline

import java.util.concurrent.CancellationException

/** The AI step did not finish in time (hung driver / far too slow); callers fall back to the bundled model. */
class InferenceTimeoutException(message: String) : RuntimeException(message)

/**
 * The shared engine lock could not be taken in time (another run still unwinding). This is NOT a model failure:
 * callers wait/retry and must never change the user's selected model because of it.
 */
class EngineBusyException(message: String = "AI engine busy") : RuntimeException(message)

/** The model instance was closed (terminal state); it can never be revived. */
class ModelClosedException(message: String = "model closed") : IllegalStateException(message)

/**
 * Aborts the whole generation (not only the AI step) with a user-facing [reason]. It is a CancellationException so
 * the pipeline rethrows it instead of silently falling back to the classic extractor.
 */
class GenerationAbort(val reason: String) : CancellationException(reason)
