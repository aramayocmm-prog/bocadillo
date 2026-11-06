package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.ops.ColorOps
import app.bocadillo.core.imaging.ops.MaskParams
import app.bocadillo.core.imaging.ops.PaintOps
import app.bocadillo.core.imaging.paint.Stroke
import app.bocadillo.core.imaging.paint.StrokeCodec
import app.bocadillo.core.imaging.paint.StrokeMode
import app.bocadillo.core.imaging.paint.StrokeRasterizer
import app.bocadillo.core.model.DocumentJson
import app.bocadillo.core.model.OpInstance
import app.bocadillo.core.model.Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class PaintTest {
    private val ctx = RenderContext.DEFAULT
    private fun opaque(r: Int, g: Int, b: Int) = argb(255, r, g, b)
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 12) =
        FloatArray(n * 3) { i -> val t = (i / 3) / (n - 1f); when (i % 3) { 0 -> x0 + (x1 - x0) * t; 1 -> y0 + (y1 - y0) * t; else -> 1f } }
    private fun gradient(w: Int = 200, h: Int = 120) = PixelBuffer(w, h, IntArray(w * h) { opaque(it % w * 255 / w, (it / w) * 255 / h, 128) })

    @Test fun codecRoundTripIncludingColorAndFlagsAndJson() {
        val s1 = Stroke(line(0.1f, 0.2f, 0.8f, 0.9f), 0.05f, 0.3f, 0.7f, 0xFFE91E63.toInt(), StrokeMode.MARKER, pressureSize = false, pressureOpacity = true)
        val s2 = Stroke(floatArrayOf(0.5f, 0.5f, 0.4f), 0.1f, color = 0x80123456.toInt(), mode = StrokeMode.ERASE)
        val enc = StrokeCodec.encode(listOf(s1, s2))
        assertEquals(listOf(s1, s2), StrokeCodec.decode(enc))
        // survives the project JSON (stored as doubles)
        val p = DocumentJson.paramsFromJson(DocumentJson.paramsToJson(Params.EMPTY.with("strokes", enc)))
        assertEquals(listOf(s1, s2), StrokeCodec.decode(p.floats("strokes")))
    }

    @Test fun dabCoverageHardAndSoft() {
        val hard = StrokeRasterizer.coverage(Stroke(floatArrayOf(0.5f, 0.5f, 1f), 0.2f, hardness = 1f), 100, 100)!!
        val soft = StrokeRasterizer.coverage(Stroke(floatArrayOf(0.5f, 0.5f, 1f), 0.2f, hardness = 0f), 100, 100)!!
        fun at(c: StrokeRasterizer.Coverage, x: Int, y: Int) = c.values[(y - c.y) * c.w + (x - c.x)]
        assertEquals(1f, at(hard, 50, 50), 1e-3f)
        assertEquals(1f, at(hard, 50, 42), 1e-3f)     // inside radius 10
        assertEquals(0f, at(hard, 50, 39), 0.05f)     // just outside
        assertTrue(at(soft, 50, 45) in 0.2f..0.9f)    // soft falloff
    }

    @Test fun selfOverlappingStrokeDoesNotDarken() {
        val zig = floatArrayOf(0.2f, 0.5f, 1f, 0.8f, 0.5f, 1f, 0.2f, 0.5f, 1f) // back and forth
        val img = PixelBuffer.filled(100, 100, opaque(255, 255, 255))
        val out = StrokePainterHelper.paint(img, Stroke(zig, 0.1f, 1f, 0.5f, opaque(0, 0, 0)))
        val v = red(out[50, 50])
        assertTrue("50% black over white once ≈ 128, got $v", v in 120..136)
    }

    @Test fun paintMarkerAndErase() {
        val img = PixelBuffer.filled(100, 100, opaque(200, 200, 200))
        val dot = floatArrayOf(0.5f, 0.5f, 1f)
        val red = StrokePainterHelper.paint(img, Stroke(dot, 0.2f, 1f, 1f, opaque(255, 0, 0)))
        assertEquals(opaque(255, 0, 0), red[50, 50]); assertEquals(img[5, 5], red[5, 5])
        val marked = StrokePainterHelper.paint(img, Stroke(dot, 0.2f, 1f, 1f, opaque(255, 255, 0), StrokeMode.MARKER))
        assertEquals(200, red(marked[50, 50])); assertTrue(blue(marked[50, 50]) < 10) // multiply by yellow
        val erased = StrokePainterHelper.paint(img, Stroke(dot, 0.2f, 1f, 1f, mode = StrokeMode.ERASE))
        assertEquals(0, alpha(erased[50, 50])); assertEquals(255, alpha(erased[5, 5]))
    }

    @Test fun resolutionIndependentPreviewVsExport() {
        val s = listOf(Stroke(line(0.1f, 0.5f, 0.9f, 0.5f), 0.1f, 1f, 1f, opaque(0, 0, 255)))
        val p = Params.EMPTY.with("strokes", StrokeCodec.encode(s))
        val small = PaintOps.Paint.apply(PixelBuffer.filled(200, 100, opaque(255, 255, 255)), p, ctx)
        val big = PaintOps.Paint.apply(PixelBuffer.filled(800, 400, opaque(255, 255, 255)), p, ctx)
        assertEquals(small[100, 50], big[400, 200])
        assertEquals(small[100, 10], big[400, 40])
    }

    @Test fun selectAndProtectMasksLimitTheEffect() {
        val g = gradient()
        val leftHalf = Stroke(floatArrayOf(0.25f, 0.5f, 1f), 0.9f, 1f) // big dot on the left
        val sat = Params.of("amount" to -1.0).with(MaskParams.STROKES, StrokeCodec.encode(listOf(leftHalf)))
        val renderer = PipelineRenderer()
        val sel = renderer.render(g, "a", listOf(OpInstance("1", "saturation", sat.with(MaskParams.MODE, "select"))), emptyMap(), ctx)
        val c1 = sel[50, 60]; val c2 = sel[190, 10]
        assertTrue("painted area grey", abs(red(c1) - blue(c1)) <= 2)
        assertEquals("unpainted area untouched", g[190, 10], c2)
        // protect ("red mask"): everything starts protected; ERASE strokes uncover where the effect goes
        val uncover = Params.of("amount" to -1.0).with(MaskParams.STROKES, StrokeCodec.encode(listOf(leftHalf.copy(mode = StrokeMode.ERASE))))
        val prot = renderer.render(g, "b", listOf(OpInstance("2", "saturation", uncover.with(MaskParams.MODE, "protect"))), emptyMap(), ctx)
        assertTrue("uncovered area grey", abs(red(prot[50, 60]) - blue(prot[50, 60])) <= 2)
        assertEquals("still-protected area untouched", g[190, 10], prot[190, 10])
        val allProtected = renderer.render(g, "b2", listOf(OpInstance("2b", "saturation", sat.with(MaskParams.MODE, "protect"))), emptyMap(), ctx)
        assertEquals("painting in protect mode keeps protection", g[190, 10], allProtected[190, 10])
        val inv = renderer.render(g, "c", listOf(OpInstance("3", "saturation", sat.with(MaskParams.MODE, "select").with(MaskParams.INVERT, true))), emptyMap(), ctx)
        assertEquals(g[50, 60], inv[50, 60])
    }

    @Test fun featherSoftensMaskEdge() {
        val m0 = StrokeRasterizer.maskCoverage(listOf(Stroke(floatArrayOf(0.5f, 0.5f, 1f), 0.4f, 1f)), 100, 100, 0f)
        val m1 = StrokeRasterizer.feather(m0.copyOf(), 100, 100, 6)
        // at the edge (r = 20) the feathered mask is in between
        assertTrue(m1[50 * 100 + 70] in 0.2f..0.8f)
        assertEquals(1f, m1[50 * 100 + 50], 0.01f)
        val erased = StrokeRasterizer.maskCoverage(listOf(Stroke(floatArrayOf(0.5f, 0.5f, 1f), 0.4f, 1f, mode = StrokeMode.ERASE)), 100, 100, 1f)
        assertEquals(0f, erased[50 * 100 + 50], 1e-3f); assertEquals(1f, erased[0], 0f)
    }

    @Test fun historyPaintRestoresEarlierStage() {
        val g = gradient()
        val ops = listOf(
            OpInstance("a", "invert", Params.EMPTY),
            OpInstance("b", "history_paint", Params.of("stage" to 0).with("strokes", StrokeCodec.encode(listOf(Stroke(floatArrayOf(0.25f, 0.5f, 1f), 0.4f, 1f))))),
        )
        val out = PipelineRenderer().render(g, "h", ops, emptyMap(), ctx)
        assertEquals("painted back to original", g[50, 60], out[50, 60])
        assertEquals("rest stays inverted", ColorOps.Invert.apply(g, Params.EMPTY, ctx)[190, 10], out[190, 10])
    }

    @Test fun visualSheet() {
        val out = File(System.getProperty("bocadillo.visualOut") ?: "build/visual").apply { mkdirs() }
        val g = gradient(400, 240)
        val wave = FloatArray(40 * 3) { i -> val k = i / 3; when (i % 3) { 0 -> 0.05f + k * 0.022f; 1 -> 0.3f + 0.12f * kotlin.math.sin(k * 0.45f); else -> 0.3f + 0.7f * k / 39f } }
        val strokes = listOf(
            Stroke(wave, 0.06f, 0.9f, 1f, opaque(255, 255, 255)),
            Stroke(line(0.05f, 0.55f, 0.95f, 0.55f), 0.09f, 0.2f, 0.8f, opaque(20, 20, 160)),
            Stroke(line(0.05f, 0.75f, 0.95f, 0.75f), 0.08f, 1f, 1f, opaque(255, 235, 0), StrokeMode.MARKER),
            Stroke(line(0.6f, 0.05f, 0.95f, 0.45f), 0.08f, 1f, 1f, mode = StrokeMode.ERASE),
        )
        val painted = PaintOps.Paint.apply(g, Params.EMPTY.with("strokes", StrokeCodec.encode(strokes)), ctx)
        val masked = PipelineRenderer().render(g, "m", listOf(OpInstance("x", "saturation",
            Params.of("amount" to -1.0, MaskParams.MODE to "select", MaskParams.FEATHER to 0.03).with(MaskParams.STROKES, StrokeCodec.encode(listOf(Stroke(line(0.1f, 0.5f, 0.9f, 0.5f), 0.4f, 1f)))))), emptyMap(), ctx)
        val sheet = BufferedImage(820, 260, BufferedImage.TYPE_INT_ARGB)
        val gr = sheet.createGraphics(); gr.color = java.awt.Color(200, 200, 200); gr.fillRect(0, 0, 820, 260)
        for ((i, b) in listOf(painted, masked).withIndex()) {
            val im = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB); im.setRGB(0, 0, b.width, b.height, b.pixels, 0, b.width)
            gr.drawImage(im, 10 + i * 410, 10, null)
        }
        gr.dispose()
        ImageIO.write(sheet, "png", File(out, "stage4_paint_mask.png"))
    }
}

/** Test helper: paints a single stroke through the public op. */
object StrokePainterHelper {
    fun paint(src: PixelBuffer, s: Stroke): PixelBuffer =
        PaintOps.Paint.apply(src, Params.EMPTY.with("strokes", StrokeCodec.encode(listOf(s))), RenderContext.DEFAULT)
}
