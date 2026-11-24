package com.smitnk.sketcher.export

import android.content.Context
import android.graphics.Bitmap
import com.smitnk.sketcher.image.pipeline.RegionMap
import com.smitnk.sketcher.image.pipeline.VectorTrace
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * SVG export: one `<g>` per visible layer (bottom to top). Plain strokes become stroked paths, a colour layer
 * with region data becomes filled paths ([VectorTrace]); anything else (pencil-reveal ink, colour layer
 * without regions) is embedded as a base64 PNG `<image>`.
 */
object SvgExporter {

    /** Region data of a colour layer: the map and one ARGB per region id. */
    class FillData(val map: RegionMap, val colours: IntArray)

    /**
     * Builds the SVG text. [fills] maps layer id -> region data. [rasterPng] returns PNG bytes of one layer
     * (null to skip it); the default renders it with [Exporter.render] (Android only).
     */
    fun build(
        width: Int,
        height: Int,
        layers: List<Layer>,
        fills: Map<Long, FillData> = emptyMap(),
        rasterPng: (Layer) -> ByteArray? = { layerPng(width, height, it) },
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
            .append("width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">\n")
        for (layer in layers) {
            if (!layer.visible) continue
            sb.append("<g id=\"").append(xmlId(layer)).append("\" opacity=\"").append(VectorTrace.f(layer.opacity.toDouble())).append("\">\n")
            val fill = fills[layer.id]
            when {
                fill != null ->
                    for (s in VectorTrace.trace(fill.map, fill.colours)) {
                        if (s.pathData.isEmpty() || (s.argb ushr 24) == 0) continue
                        sb.append("<path fill-rule=\"evenodd\" fill=\"").append(hex(s.argb)).append('"')
                        alphaAttr(sb, "fill-opacity", (s.argb ushr 24) / 255f)
                        sb.append(" d=\"").append(s.pathData).append("\"/>\n")
                    }
                layer.inkTexture == null && layer.kind != LayerKind.AUTO_FILL ->
                    // shortcut: brush textures (BrushType) are drawn as plain round pen strokes in SVG.
                    for (s in layer.strokes) {
                        val pts = s.points
                        if (pts.size < 2) continue
                        sb.append("<path fill=\"none\" stroke=\"").append(hex(s.color)).append('"')
                        alphaAttr(sb, "stroke-opacity", s.alpha * (s.color ushr 24) / 255f)
                        sb.append(" stroke-width=\"").append(VectorTrace.f(s.width.toDouble()))
                            .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"M")
                        sb.append(VectorTrace.f(pts[0].toDouble())).append(' ').append(VectorTrace.f(pts[1].toDouble()))
                        if (pts.size < 4) sb.append("l0 0")
                        var i = 2
                        while (i + 1 < pts.size) {
                            sb.append('L').append(VectorTrace.f(pts[i].toDouble())).append(' ').append(VectorTrace.f(pts[i + 1].toDouble()))
                            i += 2
                        }
                        sb.append("\"/>\n")
                    }
                else -> rasterPng(layer)?.let { png ->
                    sb.append("<image x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" xlink:href=\"data:image/png;base64,")
                        .append(Base64.getEncoder().encodeToString(png)).append("\"/>\n")
                }
            }
            sb.append("</g>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    /**
     * Builds and saves an SVG to Pictures/Sketcher. On any failure it saves a PNG of the whole drawing
     * instead and returns ok = true with [ExportResult.error] explaining the fallback (ok = false if both fail).
     */
    fun save(
        context: Context,
        width: Int,
        height: Int,
        layers: List<Layer>,
        fills: Map<Long, FillData>,
        baseName: String,
    ): ExportResult {
        val reason = try {
            val svg = build(width, height, layers, fills).toByteArray(Charsets.UTF_8)
            val target = OutputTarget.create(context, false, exportFileName(baseName, ExportFormat.SVG.extension), ExportFormat.SVG.mime)
                ?: throw IllegalStateException("cannot create output file")
            var ok = false
            try {
                target.openStream()?.use { it.write(svg); ok = true }
            } finally {
                if (ok) target.commit() else target.abort()
            }
            if (ok) return ExportResult(ExportKind.IMAGE, null, target.displayPath, true)
            "cannot write SVG file"
        } catch (e: OutOfMemoryError) {
            "out of memory"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        val path = try {
            val bmp = Exporter.render(width, height, layers, 1, transparent = true)
            try { Exporter.save(context, bmp, ExportFormat.PNG, baseName) } finally { bmp.recycle() }
        } catch (e: Exception) {
            null
        }
        return ExportResult(
            ExportKind.IMAGE, null, path, path != null,
            if (path != null) "SVG export failed ($reason); saved as PNG instead" else "SVG export failed ($reason); PNG fallback failed too",
        )
    }

    private fun layerPng(width: Int, height: Int, layer: Layer): ByteArray? {
        val bmp = Exporter.render(width, height, listOf(layer), 1, transparent = true, ignoreLayerState = true)
        return try {
            ByteArrayOutputStream().use { out -> if (bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null }
        } finally {
            bmp.recycle()
        }
    }

    private fun alphaAttr(sb: StringBuilder, name: String, a: Float) {
        if (a < 1f) sb.append(' ').append(name).append("=\"").append(VectorTrace.f(a.toDouble())).append('"')
    }

    private fun hex(argb: Int) = "#%06x".format(argb and 0xFFFFFF)

    /** XML-safe, unique id from the layer name. */
    private fun xmlId(l: Layer): String =
        l.name.replace(Regex("[^A-Za-z0-9_-]"), "_").let { if (it.firstOrNull()?.isLetter() == true) it else "L$it" } + "_" + l.id
}
