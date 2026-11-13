package com.smitnk.sketcher.image.models

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(sha: String? = null, bytes: ByteArray = ByteArray(300_000) { (it % 251).toByte() }): Triple<ModelManager, ModelSpec, ByteArray> {
        val src = tmp.newFile("remote.onnx").apply { writeBytes(bytes) }
        val spec = ModelSpecs.REALISTIC.copy(downloadUrl = src.toURI().toURL().toString(), sha256 = sha)
        val m = ModelManager(File(tmp.root, "models"), listOf(ModelSpecs.INFORMATIVE, spec)) { true }
        return Triple(m, spec, bytes)
    }

    @Test fun bundledAvailableViaAssetCheck() {
        val m = ModelManager(File(tmp.root, "m"), ModelSpecs.ALL) { false }
        assertFalse(m.isAvailable("informative"))
        assertFalse(m.isAvailable("nope"))
        assertFalse(m.delete("informative"))
    }

    @Test fun downloadWritesFileReportsProgressAndDeletes() {
        val (m, spec, bytes) = setup()
        assertFalse(m.isAvailable(spec.id))
        val progress = mutableListOf<Long>()
        val f = m.download(spec.id, { done, _ -> progress += done })
        assertTrue(m.isAvailable(spec.id))
        assertEquals(bytes.toList(), f.readBytes().toList())
        assertEquals(bytes.size.toLong(), progress.last())
        assertFalse(File(f.parentFile, spec.fileName + ".part").exists())
        assertTrue(m.delete(spec.id))
        assertFalse(m.isAvailable(spec.id))
    }

    @Test fun shaMatchAccepted() {
        val bytes = ByteArray(1000) { it.toByte() }
        val ref = tmp.newFile("ref").apply { writeBytes(bytes) }
        val (m, spec, _) = setup(ModelManager.sha256Hex(ref).uppercase(), bytes)
        m.download(spec.id)
        assertTrue(m.isAvailable(spec.id))
    }

    @Test fun shaMismatchFailsAndLeavesNothing() {
        val (m, spec, _) = setup("00".repeat(32))
        try {
            m.download(spec.id)
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("sha256"))
        }
        assertFalse(m.isAvailable(spec.id))
        assertEquals(0, File(tmp.root, "models").listFiles()!!.size)
    }

    @Test fun cancelRemovesTemp() {
        val (m, spec, _) = setup()
        try {
            m.download(spec.id, shouldCancel = { true })
            org.junit.Assert.fail("expected IOException")
        } catch (_: IOException) {}
        assertFalse(m.isAvailable(spec.id))
        assertEquals(0, File(tmp.root, "models").listFiles()!!.size)
    }

    @Test fun sha256KnownVector() {
        val f = tmp.newFile("abc").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelManager.sha256Hex(f))
    }
}
