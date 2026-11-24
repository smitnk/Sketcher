package com.smitnk.sketcher.export

import com.smitnk.sketcher.image.Stroke
import com.smitnk.sketcher.image.pipeline.RegionMap
import com.smitnk.sketcher.model.Layer
import com.smitnk.sketcher.model.LayerKind
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class SvgExporterTest {
    @Test fun buildsValidXml() {
        val lines = Layer(1, "Lines & <outline>").also {
            it.kind = LayerKind.AUTO_OUTLINE
            it.strokes.add(Stroke(floatArrayOf(1f, 1f, 10f, 10f, 20f, 5f), 0xFF000000.toInt(), 2f))
        }
        val colour = Layer(2, "Colour").also { it.kind = LayerKind.AUTO_FILL }
        val raster = Layer(3, "Raster").also { it.kind = LayerKind.AUTO_FILL }
        val l = IntArray(16 * 16) { if (it % 16 < 8) 0 else 1 }
        val svg = SvgExporter.build(
            16, 16, listOf(colour, lines, raster),
            mapOf(2L to SvgExporter.FillData(RegionMap(16, 16, l, 2), intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))),
            rasterPng = { byteArrayOf(1, 2, 3) },
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals("svg", doc.documentElement.tagName)
        assertEquals(3, doc.getElementsByTagName("g").length)
        assertEquals(3, doc.getElementsByTagName("path").length) // 2 regions + 1 stroke
        assertEquals(1, doc.getElementsByTagName("image").length)
    }
}
