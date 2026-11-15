package com.smitnk.sketcher.data

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class FakeStore : ProjectStore {
    /** id -> (name, snapshot) as on "disk". */
    val disk = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ProjectSnapshot>>()
    val saveCalls = CopyOnWriteArrayList<String>()
    val failuresLeft = AtomicInteger(0)
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var concurrent = 0
    @Volatile var maxConcurrent = 0

    override suspend fun save(id: String?, name: String, snapshot: ProjectSnapshot, source: Bitmap?): String {
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            gate?.await()
            saveCalls.add(id!!)
            if (failuresLeft.get() > 0) {
                failuresLeft.decrementAndGet()
                throw java.io.IOException("disk full")
            }
            disk[id] = name to snapshot
            return id
        } finally {
            concurrent--
        }
    }

    override suspend fun delete(id: String): Boolean = disk.remove(id) != null

    override suspend fun rename(id: String, newName: String): Boolean {
        val cur = disk[id] ?: return false
        disk[id] = newName to cur.second
        return true
    }
}

private fun snap(tag: Int = 0) = ProjectSnapshot(
    width = 10 + tag, height = 10, layers = emptyList(), selectedIndex = 0,
    backgroundVisible = true, backgroundOpacity = 1f,
)

class ProjectSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = FakeStore()

    private fun session(attempts: Int = 3) = ProjectSession(store, scope, "Untitled", attempts, retryDelayMs = 1L)

    @After fun tearDown() = scope.cancel()

    @Test fun firstSaveAllocatesIdSynchronouslyAndConcurrentRequestsShareIt() = runBlocking {
        val s = session()
        val ids = CopyOnWriteArrayList<String?>()
        val go = CountDownLatch(1)
        val threads = (1..16).map { i -> thread { go.await(); ids.add(s.requestSave(snap(i), null)) } }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(1, ids.toSet().size)
        assertNotNull(ids.first())
        assertTrue(s.flush())
        assertEquals(1, store.disk.size)
        assertEquals(s.projectId, store.disk.keys.single())
        assertEquals(1, store.maxConcurrent) // single writer
    }

    @Test fun deleteThenQueuedSaveDoesNotResurrect() = runBlocking {
        val s = session()
        store.gate = CompletableDeferred()
        val id = s.requestSave(snap(), null)!!
        delay(20) // actor is now blocked inside the first save
        s.requestSave(snap(1), null) // queued behind it
        val del = s.delete(id)
        store.gate!!.complete(Unit)
        assertTrue(withTimeout(5000) { del.await() })
        s.flush()
        assertNull(store.disk[id])
        // Editing after the delete starts a fresh project instead of reviving the old id.
        val id2 = s.requestSave(snap(2), null)!!
        assertTrue(id != id2)
        s.flush()
        assertEquals(setOf(id2), store.disk.keys)
    }

    @Test fun queuedSaveNeverOverwritesRename() = runBlocking {
        val s = session()
        s.setName("Old")
        store.gate = CompletableDeferred()
        val id = s.requestSave(snap(), null)!!
        delay(20)
        s.requestSave(snap(1), null)
        val r = s.rename(id, "New")
        store.gate!!.complete(Unit)
        r.await()
        s.requestSave(snap(2), null)
        s.flush()
        assertEquals("New", store.disk[id]!!.first)
        assertEquals(12, store.disk[id]!!.second.width)
    }

    @Test fun renameOfOtherProjectDoesNotLeakIntoCurrent() = runBlocking {
        val s = session()
        s.setName("A")
        val a = s.requestSave(snap(), null)!!
        s.flush()
        s.reset()
        s.setName("B")
        val b = s.requestSave(snap(), null)!!
        s.rename(a, "A2").await()
        s.flush()
        assertEquals("A2", store.disk[a]!!.first)
        assertEquals("B", store.disk[b]!!.first)
    }

    @Test fun failedSaveIsRetriedThenSucceeds() = runBlocking {
        val s = session()
        store.failuresLeft.set(2)
        val id = s.requestSave(snap(), null)!!
        assertTrue(s.flush())
        assertNotNull(store.disk[id])
        assertNull(s.saveError.value)
        assertEquals(3, store.saveCalls.size)
    }

    @Test fun persistentFailureSurfacesErrorAndFlushRetries() = runBlocking {
        val s = session()
        store.failuresLeft.set(1000)
        val id = s.requestSave(snap(), null)!!
        assertFalse(s.flush(retryFailed = false))
        assertNotNull(s.saveError.value)
        assertNull(store.disk[id])
        store.failuresLeft.set(0)
        assertTrue(s.flush()) // retries the failed snapshot
        assertNotNull(store.disk[id])
        assertNull(s.saveError.value)
    }

    @Test fun failedSaveSurvivesSwitchingToAnotherProject() = runBlocking {
        val s = session()
        store.failuresLeft.set(3) // all attempts of the first save fail
        val a = s.requestSave(snap(1), null)!!
        s.reset()
        val b = s.requestSave(snap(2), null)!!
        assertTrue(s.flush())
        assertNotNull("edits of the first project must not be dropped", store.disk[a])
        assertNotNull(store.disk[b])
        assertEquals(setOf(a, b), store.disk.keys)
    }

    @Test fun latestLoadWinsAndStaleLoadIsIgnored() {
        val s = session()
        val t1 = s.beginLoad()
        assertEquals(ProjectLoad.Loading, s.load.value)
        val t2 = s.beginLoad()
        assertFalse(s.completeLoad(t1, "one", "One"))
        assertNull(s.projectId)
        assertTrue(s.completeLoad(t2, "two", "Two"))
        assertEquals("two", s.projectId)
        assertEquals("Two", s.name)
        assertEquals(ProjectLoad.Ready, s.load.value)
    }

    @Test fun resetCancelsPendingLoad() {
        val s = session()
        val t = s.beginLoad()
        s.reset()
        assertFalse(s.completeLoad(t, "x", "X"))
        assertEquals(ProjectLoad.Empty, s.load.value)
    }

    @Test fun savesAreBlockedWhileLoadingAndFailedLoadKeepsOldProject() = runBlocking {
        val s = session()
        val id = s.requestSave(snap(), null)!!
        val t = s.beginLoad()
        assertNull(s.requestSave(snap(1), null))
        assertTrue(s.failLoad(t))
        assertEquals(ProjectLoad.Failed, s.load.value)
        assertEquals(id, s.requestSave(snap(2), null)) // old project still editable, same row
        s.flush()
        assertEquals(1, store.disk.size)
    }

    @Test fun closeStillWritesQueuedSaves() = runBlocking {
        val s = session()
        val id = s.requestSave(snap(), null)!!
        s.close()
        withTimeout(5000) { while (store.disk[id] == null) delay(5) }
    }
}
