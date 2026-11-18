package com.smitnk.sketcher.look

import kotlin.math.cos
import kotlin.math.sin

/** Colour-fill layer offset (image px) for riso/woodblock misregistration: magnitude 1.5..2.5, direction from [seed]. */
object Misregister {
    fun offset(seed: Int): Pair<Float, Float> {
        val mag = 1.5f + PaperTexture.hash(seed, 11, 3)
        val ang = PaperTexture.hash(seed, 5, 9) * 2f * Math.PI.toFloat()
        return cos(ang) * mag to sin(ang) * mag
    }
}
