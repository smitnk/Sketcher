package com.smitnk.sketcher.data

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Storage operations the session needs (implemented by [ProjectRepository]; faked in tests). */
interface ProjectStore {
    suspend fun save(id: String?, name: String, snapshot: ProjectSnapshot, source: Bitmap?): String
    suspend fun delete(id: String): Boolean
    suspend fun rename(id: String, newName: String): Boolean
}

/** What the editor is doing about opening a project. Editing is only allowed outside [Loading]. */
enum class ProjectLoad { Empty, Loading, Ready, Failed }

/**
 * Single-writer actor for the open project's persistence.
 *
 * - Every write (save/delete/rename) runs sequentially on one coroutine, so nothing races on disk.
 * - The project id is allocated synchronously on the first save request (under a lock), so concurrent
 *   requests can never create two rows for one project.
 * - Names live in the session (per id), not in snapshots, so a queued save never reverts a rename.
 * - Deleted ids are tombstoned synchronously so queued saves cannot resurrect them.
 * - A failed save is retried (exponential backoff) and, if still failing, kept and surfaced via [saveError];
 *   it is retried again on [flush] and before the next save of another project, never silently dropped.
 * - [beginLoad]/[completeLoad] implement latest-wins project opening.
 */
class ProjectSession(
    private val store: ProjectStore,
    scope: CoroutineScope,
    private val defaultName: String = "Untitled sketch",
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 400L,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private sealed class Cmd {
        class Save(val id: String, val snapshot: ProjectSnapshot, val source: Bitmap?, val reason: String) : Cmd()
        class Flush(val done: CompletableDeferred<Boolean>, val retryFailed: Boolean) : Cmd()
        class Delete(val id: String, val done: CompletableDeferred<Boolean>) : Cmd()
        class Rename(val id: String, val name: String, val done: CompletableDeferred<Boolean>) : Cmd()
    }

    private val channel = Channel<Cmd>(Channel.UNLIMITED)
    private val lock = Any()
    private val tombstones: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val names = ConcurrentHashMap<String, String>()

    private var id: String? = null
    private var currentName: String = defaultName
    private var loadCounter = 0L

    private val _load = MutableStateFlow(ProjectLoad.Empty)
    private val _saveError = MutableStateFlow<Throwable?>(null)

    /** Load state for the UI. */
    val load: StateFlow<ProjectLoad> get() = _load

    /** Non-null while the newest save could not be written (after retries). */
    val saveError: StateFlow<Throwable?> get() = _saveError

    val projectId: String? get() = synchronized(lock) { id }
    val name: String get() = synchronized(lock) { currentName }

    // Only touched by the actor coroutine.
    private var failed: Cmd.Save? = null

    init {
        scope.launch { run() }
    }

    // ---------------------------------------------------------------- name / identity

    /** Sets the open project's name (applies to every save of it from now on, including queued ones). */
    fun setName(n: String) {
        synchronized(lock) {
            currentName = n
            id?.let { names[it] = n }
        }
    }

    /** The editor switched to a brand-new project: no id yet (allocated on its first save); cancels any load. */
    fun reset() {
        synchronized(lock) {
            id = null
            currentName = defaultName
            loadCounter++
            _load.value = ProjectLoad.Empty
        }
    }

    // ---------------------------------------------------------------- saving

    /**
     * Queues a save of [snapshot] and returns the project id (allocated here, synchronously, on the first call).
     * Returns null (and does nothing) while a project is loading.
     */
    fun requestSave(snapshot: ProjectSnapshot, source: Bitmap?, reason: String = "autosave"): String? {
        val pid = synchronized(lock) {
            if (_load.value == ProjectLoad.Loading) return null
            val existing = id
            val pid = existing ?: newId().also { id = it }
            names[pid] = currentName
            if (_load.value == ProjectLoad.Empty) _load.value = ProjectLoad.Ready
            pid
        }
        // Not under the lock; Channel.UNLIMITED never suspends. Order of sends from one thread is preserved.
        channel.trySend(Cmd.Save(pid, snapshot, source, reason))
        return pid
    }

    /**
     * Completes after every command queued so far has been processed. Returns true when nothing is left
     * unsaved. With [retryFailed] a previously failed save is attempted again first.
     */
    suspend fun flush(retryFailed: Boolean = true): Boolean {
        val done = CompletableDeferred<Boolean>()
        if (channel.trySend(Cmd.Flush(done, retryFailed)).isFailure) return failedIsEmpty()
        return done.await()
    }

    private fun failedIsEmpty() = _saveError.value == null

    /** Deletes project [pid]; queued/late saves of it are discarded. Result completes when disk is done. */
    fun delete(pid: String): Deferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        synchronized(lock) {
            tombstones.add(pid)
            names.remove(pid)
            if (id == pid) id = null // further edits of the open content start a new project
        }
        if (channel.trySend(Cmd.Delete(pid, done)).isFailure) done.complete(false)
        return done
    }

    /** Renames project [pid]; saves of it (queued or later) use the new name. */
    fun rename(pid: String, newName: String): Deferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        synchronized(lock) {
            if (pid in tombstones) {
                done.complete(false)
                return done
            }
            names[pid] = newName
            if (id == pid) currentName = newName
        }
        if (channel.trySend(Cmd.Rename(pid, newName, done)).isFailure) done.complete(false)
        return done
    }

    /** Stops accepting commands; everything already queued is still written. */
    fun close() {
        channel.close()
    }

    // ---------------------------------------------------------------- loading (latest wins)

    /** Starts a load: editing is blocked until [completeLoad]/[failLoad]. Returns the token of this load. */
    fun beginLoad(): Long = synchronized(lock) {
        _load.value = ProjectLoad.Loading
        ++loadCounter
    }

    fun isCurrentLoad(token: Long): Boolean = synchronized(lock) { token == loadCounter }

    /** Adopts [pid] as the open project if [token] is still the latest load; false = stale, change nothing. */
    fun completeLoad(token: Long, pid: String, projectName: String): Boolean = synchronized(lock) {
        if (token != loadCounter) return false
        id = pid
        currentName = projectName
        names[pid] = projectName
        _load.value = ProjectLoad.Ready
        true
    }

    /** Marks the load as failed if still current; the previously open project stays open and editable. */
    fun failLoad(token: Long): Boolean = synchronized(lock) {
        if (token != loadCounter) return false
        _load.value = ProjectLoad.Failed
        true
    }

    // ---------------------------------------------------------------- actor

    private suspend fun run() {
        var pending: Cmd? = null
        while (true) {
            val cmd = pending ?: (channel.receiveCatching().getOrNull() ?: break)
            pending = null
            try {
                when (cmd) {
                    is Cmd.Save -> {
                        // Coalesce consecutive saves of the same project: only the newest snapshot matters.
                        var latest: Cmd.Save = cmd
                        while (true) {
                            val next = channel.tryReceive().getOrNull() ?: break
                            if (next is Cmd.Save && next.id == latest.id) latest = next else { pending = next; break }
                        }
                        doSave(latest)
                    }
                    is Cmd.Flush -> {
                        if (cmd.retryFailed) failed?.let { doSave(it) }
                        cmd.done.complete(failed == null)
                    }
                    is Cmd.Delete -> {
                        if (failed?.id == cmd.id) { failed = null; refreshError() }
                        cmd.done.complete(
                            try { store.delete(cmd.id) } catch (e: CancellationException) { throw e } catch (t: Throwable) { false },
                        )
                    }
                    is Cmd.Rename -> cmd.done.complete(
                        try { store.rename(cmd.id, cmd.name) } catch (e: CancellationException) { throw e } catch (t: Throwable) { false },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Never let one bad command kill the actor.
                android.util.Log.w("SketcherSave", "session command failed", t)
                when (cmd) {
                    is Cmd.Flush -> cmd.done.complete(false)
                    is Cmd.Delete -> cmd.done.complete(false)
                    is Cmd.Rename -> cmd.done.complete(false)
                    else -> {}
                }
            }
        }
        // Channel closed: nothing can be flushed any more.
    }

    private fun currentNameSnapshot() = name

    private fun refreshError() {
        if (failed == null) _saveError.value = null
    }

    private suspend fun doSave(cmd: Cmd.Save) {
        if (cmd.id in tombstones) {
            if (failed?.id == cmd.id) failed = null
            refreshError()
            return
        }
        // A failed save of a different project must not be lost just because the editor moved on: try it once more.
        failed?.takeIf { it.id != cmd.id }?.let { orphan ->
            if (orphan.id in tombstones) failed = null else if (attempt(orphan, 1) == null) failed = null
        }
        val error = attempt(cmd, maxAttempts)
        if (error == null) {
            if (failed?.id == cmd.id) failed = null
            if (failed == null) _saveError.value = null
        } else {
            failed = cmd
            _saveError.value = error
        }
    }

    /** Writes [cmd] with exponential backoff; null on success, else the last error. */
    private suspend fun attempt(cmd: Cmd.Save, attempts: Int): Throwable? {
        var wait = retryDelayMs
        var last: Throwable? = null
        for (i in 1..attempts) {
            if (cmd.id in tombstones) return null
            try {
                val n = names[cmd.id] ?: currentNameSnapshot()
                store.save(cmd.id, n, cmd.snapshot, cmd.source)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                android.util.Log.w("SketcherSave", "save attempt $i/$attempts failed (${cmd.reason})", t)
                if (i < attempts) {
                    delay(wait)
                    wait *= 2
                }
            }
        }
        return last
    }
}
