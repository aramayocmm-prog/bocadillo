package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.model.OpInstance
import app.bocadillo.core.model.Params
import app.bocadillo.core.model.defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Writes a contact sheet of every registered op applied to a synthetic test scene, plus the
 * 12 MP timing table. Output dir: system property bocadillo.visualOut (default build/visual).
 */
class VisualAndPerformanceTest {
    private val outDir = File(System.getProperty("bocadillo.visualOut") ?: "build/visual").apply { mkdirs() }

    /** Synthetic "photo": sky gradient, sun, hills, colour bars, skin-tone patch and a grey ramp. */
    private fun scene(w: Int, h: Int): PixelBuffer {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x / w.toFloat(); val fy = y / h.toFloat()
            var r = (90 + 100 * fy).toInt(); var g = (150 + 70 * fy).toInt(); var b = (235 - 20 * fy).toInt()
            val hill = 0.62f + 0.06f * sin(fx * 9f) + 0.03f * cos(fx * 23f)
            if (fy > hill) { r = (60 + 50 * fx).toInt(); g = (120 + 40 * sin(fx * 30f)).toInt(); b = 50 }
            val dx = fx - 0.78f; val dy = (fy - 0.22f) * h / w
            val d = sqrt(dx * dx + dy * dy)
            if (d < 0.07f) { r = 255; g = 220; b = 90 } else if (d < 0.1f) { val t = (d - 0.07f) / 0.03f; r = (255 * (1 - t) + r * t).toInt(); g = (220 * (1 - t) + g * t).toInt(); b = (90 * (1 - t) + b * t).toInt() }
            if (fy > 0.86f) {
                val bar = (fx * 8).toInt()
                val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
                val c = if (fy > 0.93f) { val v = (fx * 255).toInt(); (v shl 16) or (v shl 8) or v } else bars[bar]
                r = (c shr 16) and 255; g = (c shr 8) and 255; b = c and 255
            }
            if (fx in 0.08f..0.28f && fy in 0.25f..0.55f) { r = 224; g = 172; b = 140 }
            px[y * w + x] = argb(255, clamp255(r), clamp255(g), clamp255(b))
        }
        return PixelBuffer(w, h, px)
    }

    private fun toImage(p: PixelBuffer): BufferedImage =
        BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, p.width, p.height, p.pixels, 0, p.width) }

    /** Example parameters that make each op visibly do something. */
    private fun demoParams(id: String, base: Params): Params = when (id) {
        "brightness" -> base.with("amount", 40)
        "exposure_contrast" -> base.with("exposure", 0.7).with("contrast", 0.3)
        "saturation" -> base.with("amount", 0.8)
        "temperature_tint" -> base.with("temperature", 0.7).with("tint", 0.3)
        "hue" -> base.with("amount", 90)
        "colorize" -> base.with("hue", 200)
        "crop" -> base.with("rect", floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)).with("angle", 8)
        "resize" -> base.with("width", 300).with("height", 150).with("mode", "expand").withColor("background", 0xFFFF8800.toInt())
        "orient" -> base.with("rotation", "90").with("flipH", true)
        "rotate" -> base.with("angle", 25).withColor("background", 0xFF333333.toInt())
        else -> base
    }

    @Test fun contactSheetOfAllOps() {
        val src = scene(320, 200)
        val registry = OpRegistry.default()
        val cell = 340; val cols = 4
        val tiles = listOf("original" to src) + registry.all.map { op ->
            val p = demoParams(op.id, op.params.defaults())
            op.id to op.apply(src, p, RenderContext(scale = 1f))
        }
        val rows = (tiles.size + cols - 1) / cols
        val sheet = BufferedImage(cols * cell, rows * (cell - 100), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 40); g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        tiles.forEachIndexed { i, (name, img) ->
            val x = (i % cols) * cell + 10; val y = (i / cols) * (cell - 100) + 10
            // checkerboard to make transparency visible
            for (cy in 0 until 200 step 10) for (cx in 0 until 320 step 10) {
                g.color = if ((cx + cy) / 10 % 2 == 0) Color(200, 200, 200) else Color(150, 150, 150); g.fillRect(x + cx, y + cy, 10, 10)
            }
            val k = minOf(320.0 / img.width, 200.0 / img.height, 1.0)
            g.drawImage(toImage(img), x, y, (img.width * k).toInt(), (img.height * k).toInt(), null)
            g.color = Color.WHITE; g.drawString("$name  ${img.width}×${img.height}", x, y + 220)
        }
        g.dispose()
        val f = File(outDir, "contact_sheet_stage2.png")
        ImageIO.write(sheet, "png", f)
        assertTrue(f.length() > 10_000)
    }

    @Test fun performanceOn12Megapixels() {
        val w = 4000; val h = 3000
        val src = scene(w, h)
        val registry = OpRegistry.default()
        val report = StringBuilder("op;ms (JVM, ${Runtime.getRuntime().availableProcessors()} cores)\n")
        val ctx = RenderContext(scale = 1f)
        for (op in registry.all) {
            val p = demoParams(op.id, op.params.defaults())
            op.apply(src, p, ctx) // warm-up JIT
            val t0 = System.nanoTime()
            val out = op.apply(src, p, ctx)
            val ms = (System.nanoTime() - t0) / 1_000_000
            report.append("${op.id};$ms;${out.width}x${out.height}\n")
            assertTrue("${op.id} took $ms ms", ms < 20_000)
        }
        run {
            Resample.resize(src, 2000, 1500)
            val t0 = System.nanoTime()
            val half = Resample.resize(src, 2000, 1500)
            report.append("lanczos_downscale_4000x3000_to_2000x1500;${(System.nanoTime() - t0) / 1_000_000};${half.width}x${half.height}\n")
            val t1 = System.nanoTime()
            Resample.resize(src, 1000, 750)
            report.append("lanczos_preview_proxy_to_1000x750;${(System.nanoTime() - t1) / 1_000_000}\n")
        }
        // full pipeline at preview scale 0.25 (1000x750) — this is what the UI re-runs live
        val proxy = Resample.resize(src, 1000, 750)
        val renderer = PipelineRenderer(registry)
        val ops = registry.all.filter { !it.geometric }.map { OpInstance.create(it.id, demoParams(it.id, it.params.defaults())) }
        renderer.render(proxy, "warm", ops, emptyMap(), RenderContext(scale = 0.25f))
        val t0 = System.nanoTime()
        val out = renderer.render(proxy, "p", ops, emptyMap(), RenderContext(scale = 0.25f))
        report.append("pipeline_${ops.size}_colour_ops_preview_1000x750;${(System.nanoTime() - t0) / 1_000_000}\n")
        assertEquals(1000, out.width)
        File(outDir, "performance_12mp.csv").writeText(report.toString())
        println(report)
    }
}
