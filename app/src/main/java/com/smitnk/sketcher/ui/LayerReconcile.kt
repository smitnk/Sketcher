package com.smitnk.sketcher.ui

import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind

/** Pure layer-list rules for (re)generation; JVM-testable. */
internal object LayerReconcile {
    /**
     * Layers to keep before applying a new generation. Generated (AUTO_*) layers are kept only when they were
     * produced from the same source photo ([autoSource] == [currentSource]); otherwise they are dropped.
     * Manual layers are always kept (they belong to this project).
     */
    fun keepForGeneration(layers: List<Layer>, autoSource: Int, currentSource: Int): List<Layer> =
        if (autoSource == currentSource) layers else layers.filter { it.kind == LayerKind.MANUAL }
}
