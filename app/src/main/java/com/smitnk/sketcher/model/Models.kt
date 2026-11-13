package com.smitnk.sketcher.model

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smitnk.sketcher.image.Stroke

class Layer(val id: Long, name: String) {
    var name by mutableStateOf(name)
    val strokes = mutableStateListOf<Stroke>()
    var visible by mutableStateOf(true)
    var opacity by mutableFloatStateOf(1f)
    var locked by mutableStateOf(false)

    /** Whether this layer takes part in the draw-along animation. */
    var inAnimation by mutableStateOf(true)

    fun totalLength(): Float {
        var total = 0f
        for (s in strokes) total += s.length
        return total
    }
}

class Project {
    var width by mutableIntStateOf(1024)
    var height by mutableIntStateOf(1024)

    /** Optional reference photo (same size as the canvas). Never part of exports. */
    var background by mutableStateOf<Bitmap?>(null)
    var backgroundVisible by mutableStateOf(true)
    var backgroundOpacity by mutableFloatStateOf(1f)

    val layers = mutableStateListOf<Layer>()
    var selectedIndex by mutableIntStateOf(0)

    private var nextId = 1L

    val selectedLayer: Layer?
        get() = layers.getOrNull(selectedIndex)

    fun newLayer(name: String): Layer = Layer(nextId++, name)
}
