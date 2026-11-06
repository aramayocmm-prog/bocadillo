package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.ops.ColorOps
import app.bocadillo.core.imaging.ops.Geometry
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.model.MaskData
import app.bocadillo.core.model.OpInstance
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import app.bocadillo.core.model.defaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class ImagingTest {
    private val ctx = RenderContext.DEFAULT

    private fun img(w: Int, h: Int, f: (Int, Int) -> Int) = PixelBuffer(w, h, IntArray(w * h) { f(it % w, it / w) })
    private fun opaque(r: Int, g: Int, b: Int) = argb(255, r, g, b)
    private fun gradient(w: Int = 64, h: Int = 48) = img(w, h) { x, y -> opaque(x * 255 / (w - 1), y * 255 / (h - 1), (x + y) * 255 / (w + h - 2)) }

    private fun maxDiff(a: PixelBuffer, b: PixelBuffer): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var m = 0
        for (i in 0 until a.size) for (s in intArrayOf(0, 8, 16, 24)) m = maxOf(m, abs(((a.pixels[i] ushr s) and 0xFF) - ((b.pixels[i] ushr s) and 0xFF)))
        return m
    }

    // ---------- Transform ----------

    @Test fun orientRotate90MapsPixelsClockwise() {
        // 3x2: a b c / d e f  → rotated CW (2x3): d a / e b / f c
        val src = PixelBuffer(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val r = TransformOps.Orient.apply(src, Params.of("rotation" to "90"), ctx)
        assertEquals(2, r.width); assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.pixels)
    }

    @Test fun orientFourRotationsAndDoubleFlipsAreIdentity() {
        val g = gradient()
        var r = g
        repeat(4) { r = TransformOps.Orient.apply(r, Params.of("rotation" to "90"), ctx) }
        assertArrayEquals(g.pixels, r.pixels)
        val f = TransformOps.Orient.apply(TransformOps.Orient.apply(g, Params.of("flipH" to true, "flipV" to true), ctx), Params.of("rotation" to "180"), ctx)
        assertArrayEquals("flipH+flipV == rotate 180", g.pixels, f.pixels)
        val h2 = TransformOps.Orient.apply(TransformOps.Orient.apply(g, Params.of("flipH" to true), ctx), Params.of("flipH" to true), ctx)
        assertArrayEquals(g.pixels, h2.pixels)
    }

    @Test fun cropWithoutAngleIsExact() {
        val g = gradient(100, 50)
        val c = TransformOps.Crop.apply(g, Params.of("rect" to listOf(0.1, 0.2, 0.5, 0.8)), ctx)
        assertEquals(40, c.width); assertEquals(30, c.height)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals(g[x + 10, y + 10], c[x, y])
    }

    @Test fun straightenKeepsCentreAndAutoCropIsInside() {
        val g = gradient(200, 150)
        val angle = 7.5f
        val k = Geometry.straightenScale(200, 150, angle)
        assertTrue(k in 0.7f..1f)
        val m = (1 - k) / 2
        val r = TransformOps.Crop.apply(g, Params.of("rect" to listOf(m, m, 1.0 - m, 1.0 - m), "angle" to angle), ctx)
        // auto-cropped straighten never shows transparent corners
        assertTrue("no transparent pixels", r.pixels.all { alpha(it) == 255 })
        // centre pixel stays (approximately) the centre of the source
        val c1 = r[r.width / 2, r.height / 2]; val c0 = g[100, 75]
        assertTrue(abs(red(c1) - red(c0)) <= 4 && abs(green(c1) - green(c0)) <= 4)
        assertEquals(1f, Geometry.straightenScale(10, 10, 0f), 1e-6f)
    }

    @Test fun resizeModesProduceExpectedSizesAndContent() {
        val g = gradient(400, 200)
        fun run(mode: String, w: Int, h: Int, bg: Int = opaque(0, 0, 255)) =
            TransformOps.Resize.apply(g, Params.of("width" to w, "height" to h, "mode" to mode).withColor("background", bg), ctx)
        assertEquals(100 to 100, run("stretch", 100, 100).let { it.width to it.height })
        assertEquals(100 to 50, run("fit", 100, 100).let { it.width to it.height }) // quick fit keeps aspect
        assertEquals(100 to 100, run("fill", 100, 100).let { it.width to it.height })
        val ex = run("expand", 500, 300)
        assertEquals(opaque(0, 0, 255), ex[0, 0])
        assertEquals(g[0, 0], ex[50, 50])
        val cr = run("crop", 100, 100)
        assertEquals(g[150, 50], cr[0, 0])
    }

    @Test fun resizeConstantImageStaysConstantAndDownscaleAverages() {
        val flat = PixelBuffer.filled(300, 200, opaque(10, 200, 77))
        val r = Resample.resize(flat, 97, 61)
        assertTrue(r.pixels.all { it == opaque(10, 200, 77) })
        val checker = img(200, 200) { x, y -> if ((x + y) % 2 == 0) opaque(0, 0, 0) else opaque(255, 255, 255) }
        val d = Resample.resize(checker, 50, 50)
        val mid = red(d[25, 25])
        assertTrue("checker averages to grey, got $mid", mid in 120..135)
    }

    @Test fun resizeHasNoColourHaloFromTransparentPixels() {
        // opaque red on the left, fully transparent GREEN on the right
        val src = img(40, 10) { x, _ -> if (x < 20) opaque(255, 0, 0) else argb(0, 0, 255, 0) }
        val r = Resample.resize(src, 17, 10)
        for (c in r.pixels) if (alpha(c) > 10) assertTrue("green leaked: ${Integer.toHexString(c)}", green(c) < 10)
    }

    @Test fun freeRotate90MatchesLosslessOrient() {
        val g = gradient(60, 40)
        val a = TransformOps.Rotate.apply(g, Params.of("angle" to 90.0, "expand" to true), ctx)
        val b = TransformOps.Orient.apply(g, Params.of("rotation" to "90"), ctx)
        assertEquals(b.width, a.width); assertEquals(b.height, a.height)
        assertTrue(maxDiff(a, b) <= 2)
    }

    // ---------- Colour ----------

    @Test fun neutralParamsAreIdentity() {
        val g = gradient()
        for (op in ColorOps.all) {
            if (op.id == "invert") continue
            val neutral = when (op.id) {
                "hue" -> Params.of("amount" to 0.0)
                "sepia", "colorize" -> Params.of("amount" to 0.0)
                else -> op.params.defaults()
            }
            assertTrue("${op.id} should report identity", op.isIdentity(neutral))
            assertTrue("${op.id} neutral must not change pixels", maxDiff(g, op.apply(g, neutral, ctx)) <= 1)
        }
    }

    @Test fun invertTwiceAndSaturationGrey() {
        val g = gradient()
        assertArrayEquals(g.pixels, ColorOps.Invert.apply(ColorOps.Invert.apply(g, Params.EMPTY, ctx), Params.EMPTY, ctx).pixels)
        val grey = ColorOps.Saturation.apply(g, Params.of("amount" to -1.0), ctx)
        for (c in grey.pixels) assertTrue(abs(red(c) - green(c)) <= 1 && abs(green(c) - blue(c)) <= 1)
    }

    @Test fun hueFullTurnIsIdentityAndPreservesLuma() {
        val g = gradient()
        assertTrue(maxDiff(g, ColorOps.Hue.apply(g, Params.of("amount" to 360.0), ctx)) <= 1)
        val grey = opaque(128, 128, 128)
        val r = ColorOps.Hue.apply(PixelBuffer.filled(2, 2, grey), Params.of("amount" to 77.0), ctx)
        assertTrue("grey has no hue", maxDiff(PixelBuffer.filled(2, 2, grey), r) <= 1)
    }

    @Test fun exposureAndContrastBehave() {
        val lut1 = ColorOps.ExposureContrast.lut(1f, 0f)
        // +1 EV doubles linear light: sRGB 128 (≈0.216 linear) → ≈0.431 linear ≈ sRGB 175
        assertTrue(lut1[128] in 172..178)
        val lutC = ColorOps.ExposureContrast.lut(0f, 0.5f)
        assertTrue(lutC[128] in 127..129)
        assertTrue(lutC[200] > 240 && lutC[60] < 10)
        val lutN = ColorOps.ExposureContrast.lut(0f, -0.5f)
        assertTrue(lutN[255] < 215 && lutN[0] > 40)
    }

    @Test fun brightnessTemperatureSepiaColorize() {
        val mid = PixelBuffer.filled(2, 2, opaque(100, 100, 100))
        assertTrue(red(ColorOps.Brightness.apply(mid, Params.of("amount" to 100.0), ctx)[0, 0]) in 226..229)
        val warm = ColorOps.TemperatureTint.apply(mid, Params.of("temperature" to 1.0, "tint" to 0.0), ctx)[0, 0]
        assertTrue(red(warm) > 100 && blue(warm) < 100)
        val sep = ColorOps.Sepia.apply(mid, Params.of("amount" to 1.0), ctx)[0, 0]
        assertTrue(red(sep) > green(sep) && green(sep) > blue(sep))
        val col = ColorOps.Colorize.apply(gradient(), Params.of("hue" to 240.0, "saturation" to 1.0, "amount" to 1.0), ctx)
        val c = col[40, 30]
        assertTrue("colorize to blue", blue(c) >= red(c) && blue(c) >= green(c))
    }

    @Test fun alphaIsPreservedByColourOps() {
        val src = PixelBuffer.filled(4, 4, argb(77, 200, 10, 30))
        for (op in ColorOps.all) assertEquals(op.id, 77, alpha(op.apply(src, op.params.defaults(), ctx)[1, 1]))
    }

    // ---------- Masks & pipeline ----------

    @Test fun maskBlendRespectsCoverageAndInversion() {
        val g = gradient(10, 10)
        val inv = ColorOps.Invert.apply(g, Params.EMPTY, ctx)
        val half = MaskData(10, 10, ByteArray(100) { if (it % 10 < 5) 0 else -1 })
        val r = MaskBlend.apply(g, inv, half, inverted = false, ctx)
        assertEquals(g[2, 3], r[2, 3]); assertEquals(inv[7, 3], r[7, 3])
        val ri = MaskBlend.apply(g, inv, half, inverted = true, ctx)
        assertEquals(inv[2, 3], ri[2, 3]); assertEquals(g[7, 3], ri[7, 3])
        val small = MaskBlend.scaleMask(MaskData.filled(3, 3, 255), 10, 10)
        assertTrue(small.data.all { it == (-1).toByte() })
    }

    private class CountingOp : ImageOp {
        val calls = AtomicInteger()
        override val id = "count"; override val category = OpCategory.MISC; override val geometric = false
        override val origin = ImageOp.Origin.NEW
        override val params = listOf<ParamSpec>(ParamSpec.Slider("v", "v", 0f, 10f, 1f, 1f))
        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer { calls.incrementAndGet(); return src.copy() }
    }

    @Test fun pipelineCachesPrefixSkipsUnknownAndIdentity() {
        val a = CountingOp()
        val renderer = PipelineRenderer(OpRegistry(listOf(a) + ColorOps.all + TransformOps.all))
        val g = gradient()
        val op1 = OpInstance("1", "count", Params.of("v" to 1.0))
        val op2 = OpInstance("2", "brightness", Params.of("amount" to 10.0))
        renderer.render(g, "src", listOf(op1, op2), emptyMap(), ctx)
        assertEquals(1, a.calls.get())
        // tweak last op: prefix [op1] must come from cache
        renderer.render(g, "src", listOf(op1, op2.copy(params = Params.of("amount" to 20.0))), emptyMap(), ctx)
        assertEquals(1, a.calls.get())
        val out = renderer.render(g, "src", listOf(OpInstance("x", "does_not_exist", Params.EMPTY), OpInstance("z", "brightness", Params.of("amount" to 0.0))), emptyMap(), ctx)
        assertArrayEquals(g.pixels, out.pixels)
        assertTrue("does_not_exist" in renderer.skippedOps)
    }

    @Test fun previewScaleMatchesExportGeometry() {
        val g = gradient(400, 300)
        val ops = listOf(
            OpInstance("c", "crop", Params.of("rect" to listOf(0.0, 0.0, 0.5, 1.0))),
            OpInstance("r", "resize", Params.of("width" to 100, "height" to 150, "mode" to "stretch")),
        )
        val renderer = PipelineRenderer()
        val full = renderer.render(g, "full", ops, emptyMap(), RenderContext(scale = 1f))
        val proxy = renderer.render(Resample.resize(g, 200, 150), "proxy", ops, emptyMap(), RenderContext(scale = 0.5f))
        assertEquals(100 to 150, full.width to full.height)
        assertEquals(50 to 75, proxy.width to proxy.height)
    }

    @Test(expected = CancellationException::class)
    fun cancellationStopsRender() {
        val g = gradient(500, 500)
        ColorOps.Saturation.apply(g, Params.of("amount" to 0.5), RenderContext(isCancelled = { true }))
    }

    @Test fun registryHasUniqueIdsAndValidSpecs() {
        val r = OpRegistry.default()
        assertTrue(r.all.size >= 12)
        for (op in r.all) op.params.defaults() // throws on invalid defaults
    }
}
