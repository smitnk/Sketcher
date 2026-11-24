import com.smitnk.sketcher.image.pipeline.LineArtVectorizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// usage: Main <in_dir with *.bin (int32 w,h + float32 ink)> <out_dir>
fun main(a: Array<String>) {
    val out = File(a[1]).also { it.mkdirs() }
    val files = File(a[0]).listFiles { f -> f.name.endsWith(".bin") }!!.sortedBy { it.name }
    fun run(v: FloatArray, w: Int, h: Int) = LineArtVectorizer.vectorize(v, w, h, 0.5f, 2.2f, 4000) // app defaults
    for ((i, f) in files.withIndex()) {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val w = b.int; val h = b.int
        val v = FloatArray(w * h); b.asFloatBuffer().get(v)
        if (i == 0) repeat(3) { run(v, w, h) } // JIT warm-up
        var best = Long.MAX_VALUE
        var strokes = run(v, w, h)
        repeat(3) { val t = System.nanoTime(); strokes = run(v, w, h); best = minOf(best, System.nanoTime() - t) }
        val n = f.name.removeSuffix(".bin")
        File(out, "$n.ms").writeText((best / 1e6).toString())
        File(out, "$n.txt").writeText(strokes.joinToString("\n") { s -> s.points.joinToString(" ") })
        println("$n strokes=${strokes.size} ms=${best / 1e6}")
    }
}
