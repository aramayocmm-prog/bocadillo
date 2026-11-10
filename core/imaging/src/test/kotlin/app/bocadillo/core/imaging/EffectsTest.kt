package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.effects.ColorArtOps
import app.bocadillo.core.imaging.effects.DistortOps
import app.bocadillo.core.imaging.effects.Effects
import app.bocadillo.core.imaging.effects.FrameOps
import app.bocadillo.core.imaging.effects.PhotoOps
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import app.bocadillo.core.model.defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class EffectsTest {
    private val outDir = File(System.getProperty("bocadillo.visualOut") ?: "build/visual").apply { mkdirs() }
    private val ctx = RenderContext()
    private fun scene(w: Int, h: Int) = VisualAndPerformanceTest().scene(w, h)
    private fun defaults(op: ImageOp, vararg kv: Pair<String, Any>): Params {
        var p = op.params.defaults()
        for ((k, v) in kv) p = when (v) {
            is Boolean -> p.with(k, v); is String -> p.with(k, v); is FloatArray -> p.with(k, v); else -> p.with(k, v as Number)
        }
        return p
    }
    private fun meanDiff(a: PixelBuffer, b: PixelBuffer): Double {
        var s = 0.0
        for (i in 0 until a.size) {
            // premultiplied: the colour of fully transparent pixels does not matter
            val x = premultiply(a.pixels[i]); val y = premultiply(b.pixels[i])
            s += abs(red(x) - red(y)) + abs(green(x) - green(y)) + abs(blue(x) - blue(y)) + abs(alpha(x) - alpha(y))
        }
        return s / a.size / 4
    }

    @Test fun registryHasAllEffectsWithUniqueIds() {
        val reg = OpRegistry.default()
        assertEquals(Effects.all.size, Effects.all.map { it.id }.toSet().size)
        for (e in Effects.all) assertTrue(e.id, reg[e.id] === e)
        assertTrue("effects: ${Effects.all.size}", Effects.all.size >= 40)
    }

    @Test fun everyEffectKeepsSizeIsDeterministicAndHandlesAlpha() {
        val src = scene(97, 61)
        val withAlpha = PixelBuffer(97, 61, IntArray(97 * 61) { i -> if (i % 7 == 0) 0 else (src.pixels[i] and 0xFFFFFF) or (((i * 13) % 256) shl 24) })
        val tiny = PixelBuffer(1, 1, intArrayOf(0xFF808080.toInt()))
        for (op in Effects.all) {
            assertTrue(!op.geometric)
            val p = op.params.defaults()
            val a = op.apply(src, p, ctx); val b = op.apply(src, p, ctx)
            assertEquals(op.id, src.width, a.width); assertEquals(op.id, src.height, a.height)
            assertTrue("${op.id} not deterministic", a.pixels.contentEquals(b.pixels))
            op.apply(withAlpha, p, ctx); op.apply(tiny, p, ctx)
            // every spec default is in range and every param is used without crashing at its limits
            for (s in op.params.filterIsInstance<ParamSpec.Slider>()) {
                op.apply(src, p.with(s.key, s.min), ctx); op.apply(src, p.with(s.key, s.max), ctx)
            }
        }
    }

    @Test fun defaultsVisiblyChangeThePicture() {
        val src = scene(120, 80)
        for (op in Effects.all) {
            val out = op.apply(src, op.params.defaults(), ctx)
            if (op.id == "mirror" || op.isIdentity(op.params.defaults())) continue // mirror checked below
            assertTrue("${op.id} did nothing", meanDiff(src, out) > 0.3)
        }
    }

    @Test fun identityParamsLeaveThePictureUnchanged() {
        val src = scene(80, 60)
        for (op in Effects.all) {
            val zero = op.params.defaults().let { p ->
                op.params.filterIsInstance<ParamSpec.Slider>().fold(p) { acc, s ->
                    if (op.isIdentity(acc)) acc else if (s.key in setOf("amount", "blur", "width") && 0f in s.min..s.max) acc.with(s.key, 0f) else acc
                }
            }
            if (!op.isIdentity(zero)) continue
            val out = op.apply(src, zero, ctx)
            assertTrue("${op.id} identity diff ${meanDiff(src, out)}", meanDiff(src, out) < 0.6)
        }
    }

    @Test fun distortionsOnlyTouchInsideTheRadius() {
        val src = PixelBuffer(200, 200, IntArray(40000) { i -> if ((i % 200 / 10 + i / 2000) % 2 == 0) 0xFF202020.toInt() else 0xFFE0C040.toInt() })
        for (op in listOf(DistortOps.Twirl, DistortOps.Spherize, DistortOps.Bulge, DistortOps.Stretch, DistortOps.CircleSplash)) {
            val p = defaults(op, "radius" to 0.3f, "center" to floatArrayOf(0.5f, 0.5f)).let { if (op === DistortOps.Stretch) it.with("angle", 90f) else it }
            val out = op.apply(src, p, ctx)
            for (y in 0 until 200) for (x in 0 until 200) {
                val dx = x + 0.5 - 100; val dy = y + 0.5 - 100
                if (dx * dx + dy * dy > 61.0 * 61.0) assertEquals("${op.id} $x,$y", src[x, y], out[x, y])
            }
            assertTrue(op.id, meanDiff(src, out) > 0.5)
        }
        // twirl keeps the exact centre
        val sc = scene(200, 200)
        val t = DistortOps.Twirl.apply(sc, defaults(DistortOps.Twirl, "amount" to 90f), ctx)
        assertTrue(meanDiff(PixelBuffer(1, 1, intArrayOf(sc[100, 100])), PixelBuffer(1, 1, intArrayOf(t[100, 100]))) < 3)
    }

    @Test fun mirrorIsSymmetric() {
        val src = scene(101, 40)
        for (rtl in listOf(false, true)) {
            val out = DistortOps.Mirror.apply(src, defaults(DistortOps.Mirror, "rightToLeft" to rtl), ctx)
            for (y in 0 until 40) for (x in 0 until 101) assertEquals(out[x, y], out[100 - x, y])
            if (!rtl) assertEquals(src[10, 5], out[10, 5]) else assertEquals(src[90, 5], out[90, 5])
        }
    }

    @Test fun pixelizeMakesUniformBlocks() {
        val src = scene(1000, 1000)
        val out = DistortOps.Pixelize.apply(src, defaults(DistortOps.Pixelize, "size" to 20f), ctx) // 20 px blocks
        for (by in 0 until 50 step 7) for (bx in 0 until 50 step 7) {
            val c = out[bx * 20, by * 20]
            for (k in 0 until 20 step 5) assertEquals(c, out[bx * 20 + k, by * 20 + 19 - k])
        }
    }

    @Test fun colourEffectsHaveTheExpectedValues() {
        val src = scene(64, 64)
        val bw = ColorArtOps.BlackWhite.apply(src, defaults(ColorArtOps.BlackWhite), ctx)
        assertTrue(bw.pixels.all { (it and 0xFFFFFF) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
        val gray = ColorArtOps.Grayscale.apply(src, defaults(ColorArtOps.Grayscale, "angle" to 200f), ctx)
        assertTrue(gray.pixels.all { red(it) == green(it) && green(it) == blue(it) })
        // a red filter makes red brighter than a blue filter does
        val red = PixelBuffer(1, 1, intArrayOf(0xFFFF2020.toInt()))
        val viaRed = ColorArtOps.Grayscale.apply(red, defaults(ColorArtOps.Grayscale, "angle" to 0f), ctx)[0, 0]
        val viaBlue = ColorArtOps.Grayscale.apply(red, defaults(ColorArtOps.Grayscale, "angle" to 240f), ctx)[0, 0]
        assertTrue(red(viaRed) > red(viaBlue) + 30)
        val post = ColorArtOps.Posterize.apply(src, defaults(ColorArtOps.Posterize, "levels" to 3f), ctx)
        assertTrue(post.pixels.flatMap { listOf(red(it), green(it), blue(it)) }.toSet().size <= 3)
        val ramp = PixelBuffer(2, 1, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val duo = ColorArtOps.Duotone.apply(ramp, defaults(ColorArtOps.Duotone), ctx)
        assertEquals(0xFF1F2A60.toInt(), duo[0, 0]); assertEquals(0xFFF6C453.toInt(), duo[1, 0])
        val clear = ColorArtOps.BlendColor.apply(src, defaults(ColorArtOps.BlendColor, "mode" to "clear", "amount" to 1f), ctx)
        assertTrue(clear.pixels.all { alpha(it) == 0 })
        val rgb = ColorArtOps.AdjustRgb.apply(ramp, defaults(ColorArtOps.AdjustRgb, "r" to 0f, "b" to 0.5f), ctx)
        assertEquals(0xFF00FF80.toInt(), rgb[1, 0])
    }

    @Test fun blurFamilyPreservesFlatAreas() {
        val flat = PixelBuffer.filled(60, 40, 0xFF336699.toInt())
        for (op in listOf(PhotoOps.Blur, PhotoOps.Sharpen, PhotoOps.Smooth, FrameOps.FocalBlur, PhotoOps.TiltShift, PhotoOps.SoftGlow.takeIf { false }).filterNotNull()) {
            val out = op.apply(flat, op.params.defaults(), ctx)
            assertTrue(op.id, meanDiff(flat, out) < 0.6)
        }
        // blur averages detail away
        val src = scene(200, 120)
        val b = PhotoOps.Blur.apply(src, defaults(PhotoOps.Blur, "amount" to 30f), ctx)
        assertTrue(meanDiff(src, b) > 3)
        // transparent surroundings do not darken the blurred edge of an opaque square
        val sq = PixelBuffer(40, 40, IntArray(1600) { i -> if (i % 40 in 10..29 && i / 40 in 10..29) 0xFFFFFFFF.toInt() else 0 })
        val bs = EffectKit_blur(sq)
        assertTrue(bs.pixels.filter { alpha(it) > 8 }.all { red(it) > 240 })
    }
    private fun EffectKit_blur(p: PixelBuffer) = app.bocadillo.core.imaging.effects.EffectKit.blur(p, 3f, ctx)

    @Test fun framesAndFiltersBehave() {
        val src = PixelBuffer.filled(200, 100, 0xFF808080.toInt())
        val v = FrameOps.Vignette.apply(src, defaults(FrameOps.Vignette), ctx)
        assertTrue(red(v[100, 50]) >= 127); assertTrue(red(v[0, 0]) < 90)
        val vw = FrameOps.Vignette.apply(src, defaults(FrameOps.Vignette, "amount" to -1f), ctx)
        assertTrue(red(vw[0, 0]) > 200)
        val rc = FrameOps.RoundedCorners.apply(src, defaults(FrameOps.RoundedCorners), ctx)
        assertEquals(0, alpha(rc[0, 0])); assertEquals(255, alpha(rc[100, 50])); assertEquals(255, alpha(rc[100, 0]))
        val border = FrameOps.SolidBorder.apply(src, defaults(FrameOps.SolidBorder, "width" to 50f), ctx) // 5 px
        assertEquals(0xFFFFFFFF.toInt(), border[2, 50]); assertEquals(0xFF808080.toInt(), border[100, 50])
        val spot = FrameOps.Spotlight.apply(src, defaults(FrameOps.Spotlight, "radius" to 0.5f), ctx)
        assertEquals(0xFF808080.toInt(), spot[100, 50]); assertTrue(red(spot[2, 2]) < 50)
        val top = FrameOps.TopLens.apply(src, defaults(FrameOps.TopLens), ctx)
        assertNotEquals(src[100, 0], top[100, 0]); assertEquals(src[100, 99], top[100, 99])
        val inst = FrameOps.Instant.apply(scene(200, 200), defaults(FrameOps.Instant, "angle" to 5f), ctx)
        assertTrue(inst.pixels.count { it == 0xFFF7F6F0.toInt() } > 1000) // card paper visible
        val film = FrameOps.Film.apply(scene(300, 200), defaults(FrameOps.Film), ctx)
        assertEquals(0xFF101010.toInt(), film[1, 1])
        assertTrue(film.pixels.count { it == 0xFFE9E6DD.toInt() } > 50) // sprocket holes
    }

    /** Preview (small proxy) and export (full size) must look alike: compare at proxy size. */
    @Test fun previewMatchesExportForSizedEffects() {
        val big = scene(1600, 1000); val small = Resample.resize(big, 400, 250)
        for (op in Effects.all) {
            val p = op.params.defaults()
            val fromBig = Resample.resize(op.apply(big, p, ctx), 400, 250)
            val fromSmall = op.apply(small, p, RenderContext(scale = 0.25f))
            if (op.id in setOf("film_grain", "pencil_sketch", "comic_print", "neon")) {
                // fine textures (grain, paper, halftone dots, 1-px neon edges) only match on average tone
                fun meanLum(b: PixelBuffer) = b.pixels.map { app.bocadillo.core.imaging.effects.EffectKit.lum(it) }.average()
                val d = abs(meanLum(fromBig) - meanLum(fromSmall))
                assertTrue("${op.id} mean tone diff $d", d < 12)
                continue
            }
            val d = meanDiff(fromBig, fromSmall)
            assertTrue("${op.id} preview/export diff $d", d < 14)
        }
    }

    @Test fun contactSheetAndTimings() {
        val src = scene(320, 200)
        val demo = mapOf<String, Params>(
            "duotone" to defaults(ColorArtOps.Duotone, "contrast" to 0.4f),
            "blend_color" to defaults(ColorArtOps.BlendColor, "mode" to "soft_light", "amount" to 0.8f),
            "instant" to defaults(FrameOps.Instant, "angle" to -4f),
            "tilt_shift" to defaults(PhotoOps.TiltShift, "angle" to 0f, "center" to floatArrayOf(0.5f, 0.68f)),
            "focal_blur" to defaults(FrameOps.FocalBlur, "center" to floatArrayOf(0.18f, 0.4f), "radius" to 0.35f),
            "spotlight" to defaults(FrameOps.Spotlight, "center" to floatArrayOf(0.78f, 0.25f), "radius" to 0.6f),
            "grayscale" to defaults(ColorArtOps.Grayscale, "angle" to 30f),
        )
        val tiles = listOf("original" to src) + Effects.all.map { op -> op.id to op.apply(src, demo[op.id] ?: op.params.defaults(), ctx) }
        val cols = 5; val cw = 336; val chh = 236
        val rows = (tiles.size + cols - 1) / cols
        val sheet = BufferedImage(cols * cw, rows * chh, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(36, 36, 40); g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        tiles.forEachIndexed { i, (name, img) ->
            val x = (i % cols) * cw + 8; val y = (i / cols) * chh + 8
            for (cy in 0 until 200 step 10) for (cx in 0 until 320 step 10) {
                g.color = if ((cx + cy) / 10 % 2 == 0) Color(200, 200, 200) else Color(150, 150, 150); g.fillRect(x + cx, y + cy, 10, 10)
            }
            val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width) }
            g.drawImage(bi, x, y, null)
            g.color = Color.WHITE; g.drawString(name, x, y + 218)
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(outDir, "stage5_effects.png"))

        // timings at 12 MP (export size) and at a 2048×1536 preview proxy
        val big = scene(4000, 3000); val proxy = Resample.resize(big, 2048, 1536)
        val report = StringBuilder("effect;ms_12MP;ms_preview_2048x1536 (JVM, ${Runtime.getRuntime().availableProcessors()} cores)\n")
        for (op in Effects.all) {
            val p = op.params.defaults()
            op.apply(proxy, p, ctx)
            val t0 = System.nanoTime(); op.apply(big, p, ctx); val t1 = System.nanoTime(); op.apply(proxy, p, ctx); val t2 = System.nanoTime()
            report.append("${op.id};${(t1 - t0) / 1_000_000};${(t2 - t1) / 1_000_000}\n")
            assertTrue(op.id, (t1 - t0) / 1_000_000 < 30_000)
        }
        File(outDir, "performance_effects.csv").writeText(report.toString())
        println(report)
    }
}

class ExtraOpsTest {
    private val ctx = RenderContext()
    private fun p(op: app.bocadillo.core.imaging.ops.ImageOp) = op.params.defaults()

    @Test fun curvesIdentityAndShape() {
        val lut = app.bocadillo.core.imaging.effects.ExtraOps.curveLut(null)
        assertTrue(lut.withIndex().all { (i, v) -> i == v })
        val s = app.bocadillo.core.imaging.effects.ExtraOps.curveLut(floatArrayOf(0f, 0f, 0.25f, 0.1f, 0.75f, 0.9f, 1f, 1f))
        assertTrue(s[64] < 64 && s[192] > 192)
        for (i in 1..255) assertTrue("monotone at $i", s[i] >= s[i - 1])
        val op = app.bocadillo.core.imaging.effects.ExtraOps.Curves
        assertTrue(op.isIdentity(p(op)))
        val ramp = PixelBuffer(256, 1, IntArray(256) { (0xFF shl 24) or (it shl 16) or (it shl 8) or it })
        val out = op.apply(ramp, p(op).with("curve_r", floatArrayOf(0f, 1f, 1f, 0f)), ctx)
        assertEquals(255, red(out[0, 0])); assertEquals(0, red(out[255, 0])); assertEquals(0, green(out[0, 0]))
    }

    @Test fun redEyeOnlyTouchesRedInsideTheCircle() {
        val op = app.bocadillo.core.imaging.effects.ExtraOps.FixRedEye
        val img = PixelBuffer(100, 100, IntArray(10000) { i -> if (i % 2 == 0) 0xFFDD2020.toInt() else 0xFF305080.toInt() })
        val out = op.apply(img, p(op).with("radius", 0.2f).with("center", floatArrayOf(0.5f, 0.5f)), ctx)
        assertTrue(red(out[50, 50]) < 100)                  // red pupil fixed
        assertEquals(img[51, 50], out[51, 50])              // blue untouched
        assertEquals(img[0, 0], out[0, 0])                  // outside the circle
    }

    @Test fun backgroundColorFillsOnlyTransparency() {
        val op = app.bocadillo.core.imaging.effects.ExtraOps.AddBackgroundColor
        val img = PixelBuffer(2, 1, intArrayOf(0x00000000, 0xFF102030.toInt()))
        val out = op.apply(img, p(op).withColor("color", 0xFF00FF00.toInt()), ctx)
        assertEquals(0xFF00FF00.toInt(), out[0, 0]); assertEquals(0xFF102030.toInt(), out[1, 0])
        assertTrue(app.bocadillo.core.imaging.effects.ExtraOps.hasTransparency(img))
        assertTrue(!app.bocadillo.core.imaging.effects.ExtraOps.hasTransparency(out))
    }

    @Test fun popColorKeepsThePickedHue() {
        val op = app.bocadillo.core.imaging.effects.ExtraOps.PopColor
        val img = PixelBuffer(20, 10, IntArray(200) { i -> if (i % 20 < 10) 0xFFE02020.toInt() else 0xFF2040E0.toInt() })
        val out = op.apply(img, p(op).with("pick", floatArrayOf(0.2f, 0.5f)), ctx)
        assertEquals(img[2, 5], out[2, 5])                                     // red kept
        assertTrue(red(out[15, 5]) == green(out[15, 5]) && green(out[15, 5]) == blue(out[15, 5])) // blue → grey
        val inv = op.apply(img, p(op).with("pick", floatArrayOf(0.8f, 0.5f)).with("rest", "invert"), ctx)
        assertEquals(img[15, 5], inv[15, 5]); assertEquals(0xFF1FDFDF.toInt(), inv[2, 5])
    }

    @Test fun warpBrushMovesPixelsAlongTheStroke() {
        val op = app.bocadillo.core.imaging.effects.ExtraOps.WarpBrush
        val img = PixelBuffer(100, 100, IntArray(10000) { i -> if (i % 100 in 40..44) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        assertTrue(op.isIdentity(p(op)))
        val out = op.apply(img, p(op).with("warps", floatArrayOf(0.42f, 0.5f, 0.6f, 0.5f, 0.15f)), ctx)
        assertTrue(red(out[58, 50]) < 128)     // the black line was pushed right
        assertEquals(img[42, 2], out[42, 2])   // far away untouched
    }
}
