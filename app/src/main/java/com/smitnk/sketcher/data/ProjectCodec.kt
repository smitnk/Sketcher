package com.smitnk.sketcher.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Pure-Kotlin mirror of a stroke (brush stored as BrushType ordinal). */
class StrokeSnapshot(
    val points: FloatArray,
    val color: Int,
    val width: Float,
    val brush: Int,
    val alpha: Float,
)

class LayerSnapshot(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val locked: Boolean,
    val inAnimation: Boolean,
    val animPriority: Int,
    val strokes: List<StrokeSnapshot>,
)

class ProjectSnapshot(
    val width: Int,
    val height: Int,
    val layers: List<LayerSnapshot>,
    val selectedIndex: Int,
    val backgroundVisible: Boolean,
    val backgroundOpacity: Float,
    /** Animation duration in seconds; <= 0 means "unknown / use default". */
    val animationSeconds: Float = 0f,
)

/** Binary format: "SKCH", version int, then fields (see write/read). */
object ProjectCodec {
    const val VERSION = 1
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte())
    private const val MAX_LAYERS = 10_000
    private const val MAX_STROKES = 5_000_000
    private const val MAX_POINT_FLOATS = 50_000_000

    fun encode(s: ProjectSnapshot): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(s.width)
        out.writeInt(s.height)
        out.writeInt(s.selectedIndex)
        out.writeBoolean(s.backgroundVisible)
        out.writeFloat(s.backgroundOpacity)
        out.writeFloat(s.animationSeconds)
        out.writeInt(s.layers.size)
        for (l in s.layers) {
            out.writeLong(l.id)
            out.writeUTF(l.name)
            out.writeBoolean(l.visible)
            out.writeFloat(l.opacity)
            out.writeBoolean(l.locked)
            out.writeBoolean(l.inAnimation)
            out.writeInt(l.animPriority)
            out.writeInt(l.strokes.size)
            for (st in l.strokes) {
                out.writeInt(st.color)
                out.writeFloat(st.width)
                out.writeInt(st.brush)
                out.writeFloat(st.alpha)
                out.writeInt(st.points.size)
                for (f in st.points) out.writeFloat(f)
            }
        }
        out.flush()
        return bos.toByteArray()
    }

    /** @throws IOException on bad magic, unsupported version or corrupt data. */
    fun decode(bytes: ByteArray): ProjectSnapshot {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(4)
        inp.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Sketcher project")
        val version = inp.readInt()
        if (version != VERSION) throw IOException("Unsupported project version $version")
        val width = inp.readInt()
        val height = inp.readInt()
        val selected = inp.readInt()
        val bgVisible = inp.readBoolean()
        val bgOpacity = inp.readFloat()
        val animSeconds = inp.readFloat()
        val layerCount = inp.readInt()
        if (layerCount !in 0..MAX_LAYERS) throw IOException("Corrupt layer count")
        val layers = ArrayList<LayerSnapshot>(layerCount)
        repeat(layerCount) {
            val id = inp.readLong()
            val name = inp.readUTF()
            val visible = inp.readBoolean()
            val opacity = inp.readFloat()
            val locked = inp.readBoolean()
            val inAnim = inp.readBoolean()
            val prio = inp.readInt()
            val strokeCount = inp.readInt()
            if (strokeCount !in 0..MAX_STROKES) throw IOException("Corrupt stroke count")
            val strokes = ArrayList<StrokeSnapshot>(minOf(strokeCount, 1024))
            repeat(strokeCount) {
                val color = inp.readInt()
                val w = inp.readFloat()
                val brush = inp.readInt()
                val alpha = inp.readFloat()
                val n = inp.readInt()
                if (n !in 0..MAX_POINT_FLOATS) throw IOException("Corrupt point count")
                val pts = FloatArray(n)
                for (i in 0 until n) pts[i] = inp.readFloat()
                strokes.add(StrokeSnapshot(pts, color, w, brush, alpha))
            }
            layers.add(LayerSnapshot(id, name, visible, opacity, locked, inAnim, prio, strokes))
        }
        return ProjectSnapshot(width, height, layers, selected, bgVisible, bgOpacity, animSeconds)
    }
}
