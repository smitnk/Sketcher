package com.smitnk.sketcher.model

/**
 * Cheap signature of what a frame looks like: changes whenever a layer is added/removed/reordered, toggled, faded,
 * renamed-irrelevant props aside, when strokes are added/erased/replaced, or an ink texture changes.
 * Used to invalidate thumbnails and onion-skin bitmaps. Reads Compose state (call on the main thread).
 *
 * @param withPhoto also fold in the reference photo (visibility, opacity, identity), as the frame thumbnail shows it.
 */
fun Frame.contentSignature(withPhoto: Boolean = false): Long {
    var h = id
    h = h * 31 + layers.size
    for (l in layers) {
        h = h * 31 + l.id
        h = h * 31 + (if (l.visible) 1 else 0)
        h = h * 31 + l.opacity.toBits()
        val st = l.strokes
        h = h * 31 + st.size
        if (st.isNotEmpty()) {
            h = h * 31 + System.identityHashCode(st[0])
            h = h * 31 + System.identityHashCode(st[st.lastIndex])
        }
        val ink = l.inkTexture
        h = h * 31 + (if (ink != null) System.identityHashCode(ink).toLong() * 1_000_003L + l.inkRev else 0L)
    }
    if (withPhoto) {
        h = h * 31 + (if (backgroundVisible) 1 else 0)
        h = h * 31 + backgroundOpacity.toBits()
        h = h * 31 + (background?.let { System.identityHashCode(it) } ?: 0)
    }
    return h
}
