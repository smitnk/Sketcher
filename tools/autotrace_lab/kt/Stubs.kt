package com.smitnk.sketcher.image

// Android-free stand-ins for the two app types LineArtVectorizer.kt touches.
enum class BrushType { PEN, PENCIL, MARKER, SOFT, HATCH }

data class Stroke(
    val points: FloatArray,
    val color: Int,
    val width: Float,
    val brush: BrushType = BrushType.PEN,
    val alpha: Float = 1f,
)
