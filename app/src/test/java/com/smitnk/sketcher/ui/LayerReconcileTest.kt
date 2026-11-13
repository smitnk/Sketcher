package com.smitnk.sketcher.ui

import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerReconcileTest {
    private fun layer(id: Long, k: LayerKind) = Layer(id, "L$id").also { it.kind = k }

    @Test fun differentSourceDropsAuto() {
        val ls = listOf(layer(1, LayerKind.AUTO_OUTLINE), layer(2, LayerKind.MANUAL), layer(3, LayerKind.AUTO_SHADING))
        assertEquals(listOf(2L), LayerReconcile.keepForGeneration(ls, 1, 2).map { it.id })
    }

    @Test fun sameSourceKeepsAll() {
        val ls = listOf(layer(1, LayerKind.AUTO_OUTLINE), layer(2, LayerKind.MANUAL))
        assertEquals(2, LayerReconcile.keepForGeneration(ls, 5, 5).size)
    }
}
