package app.bocadillo.core.imaging.preview

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.Resample
import app.bocadillo.core.imaging.effects.EffectKit
import app.bocadillo.core.imaging.effects.FrameOps
import app.bocadillo.core.imaging.ops.ColorOps
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.imaging.paint.Stroke
import app.bocadillo.core.imaging.paint.StrokeMode
import app.bocadillo.core.imaging.paint.StrokePainter
import app.bocadillo.core.imaging.paint.StrokeRasterizer
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import app.bocadillo.core.model.defaults
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixed sample picture for the tool tiles: a small landscape drawn procedurally (no photo or
 * resource from any other app), supersampled 3× so edges are smooth.
 */
object SampleScene {
    fun render(w: Int = 160, h: Int = 120): PixelBuffer {
        val ss = 3
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val c = at((x + (sx + 0.5f) / ss) / w, (y + (sy + 0.5f) / ss) / h, w.toFloat() / h)
                r += EffectKit.r(c); g += EffectKit.g(c); b += EffectKit.b(c)
            }
            val n = (ss * ss).toFloat()
            out[y * w + x] = EffectKit.packF(255, r / n, g / n, b / n)
        }
        return PixelBuffer(w, h, out)
    }

    /** Colour at normalized (u, v); [aspect] = width / height keeps round shapes round. */
    private fun at(u: Float, v: Float, aspect: Float): Int {
        // sky
        var c = EffectKit.mixColor(0xFF2F7BD8.toInt(), 0xFFBFE3FF.toInt(), v / 0.62f)
        // sun with halo
        val sd = sqrt(((u - 0.80f) * aspect).let { it * it } + (v - 0.20f).let { it * it })
        c = EffectKit.mixColor(c, 0xFFFFF4C2.toInt(), (1f - sd / 0.25f).coerceIn(0f, 1f) * 0.6f)
        if (sd < 0.105f) c = 0xFFFFD43B.toInt()
        // cloud
        for ((cx, cy, cr) in listOf(Triple(0.22f, 0.17f, 0.075f), Triple(0.30f, 0.14f, 0.09f), Triple(0.38f, 0.18f, 0.07f))) {
            val d = sqrt(((u - cx) * aspect).let { it * it } + (v - cy).let { it * it })
            if (d < cr) c = EffectKit.mixColor(c, 0xFFFFFFFF.toInt(), 0.92f)
        }
        // far mountains
        val m = 0.47f - 0.16f * abs(((u * 2.3f) % 1f) - 0.5f) * 2f + 0.02f * sin(u * 40f)
        if (v > m) c = EffectKit.mixColor(0xFF6E86B5.toInt(), 0xFF8EA4CC.toInt(), ((v - m) / 0.2f).coerceIn(0f, 1f))
        // snow caps
        if (v > m && v < m + 0.035f && m < 0.36f) c = 0xFFF2F6FF.toInt()
        // hills
        val hl = 0.62f + 0.06f * sin(u * 7.0f + 1.2f)
        if (v > hl) c = EffectKit.mixColor(0xFF4CAF50.toInt(), 0xFF2E7D32.toInt(), ((v - hl) / 0.38f).coerceIn(0f, 1f))
        // house: wall, roof, door, window
        val hx0 = 0.16f; val hx1 = 0.40f; val wallTop = 0.60f; val ground = 0.84f
        if (u in hx0..hx1 && v in wallTop..ground) c = 0xFFF5EBDD.toInt()
        val roofH = 0.17f; val mid = (hx0 + hx1) / 2
        if (v in (wallTop - roofH)..wallTop && abs(u - mid) < (hx1 - hx0 + 0.04f) / 2 * (v - (wallTop - roofH)) / roofH) c = 0xFFD84343.toInt()
        if (u in 0.21f..0.26f && v in 0.70f..ground) c = 0xFF7A4A2A.toInt()
        if (u in 0.30f..0.36f && v in 0.66f..0.73f) c = 0xFF64B5F6.toInt()
        // tree
        if (u in 0.655f..0.675f && v in 0.66f..0.86f) c = 0xFF6D4C41.toInt()
        val td = sqrt(((u - 0.665f) * aspect).let { it * it } + (v - 0.60f).let { it * it })
        if (td < 0.10f) c = EffectKit.mixColor(0xFF66BB6A.toInt(), 0xFF1B5E20.toInt(), (td / 0.10f) * 0.8f + (v - 0.5f).coerceAtLeast(0f))
        // flowers
        for (i in 0 until 9) {
            val fx = 0.48f + i * 0.055f; val fy = 0.90f + 0.03f * sin(i * 2.1f)
            val d = sqrt(((u - fx) * aspect).let { it * it } + (v - fy).let { it * it })
            if (d < 0.022f) c = intArrayOf(0xFFFF5252.toInt(), 0xFFFFEB3B.toInt(), 0xFFE040FB.toInt())[i % 3]
        }
        return c
    }
}

/**
 * Small "what does this tool do" pictures for the menus: the sample with the tool applied at a
 * value that makes the change visible.
 */
object ToolPreviews {

    /** Demonstration values for tools whose defaults change nothing (or too little to see). */
    fun demoParams(op: ImageOp): Params {
        var p = op.params.defaults()
        fun set(k: String, v: Any) { p = when (v) { is Boolean -> p.with(k, v); is String -> p.with(k, v); is FloatArray -> p.with(k, v); else -> p.with(k, v as Number) } }
        when (op.id) {
            "brightness" -> set("amount", 45f)
            "exposure_contrast" -> { set("contrast", 0.4f); set("exposure", 0.3f) }
            "saturation" -> set("amount", 0.9f)
            "temperature_tint" -> set("temperature", 0.8f)
            "orient" -> set("rotation", "90")
            "rotate" -> { set("angle", 15f); set("expand", true) }
            "adjust_rgb" -> { set("r", 1.5f); set("b", 0.6f) }
            "spotlight" -> { set("radius", 0.7f); set("center", floatArrayOf(0.3f, 0.6f)) }
            "focal_blur" -> { set("radius", 0.45f); set("center", floatArrayOf(0.3f, 0.65f)) }
            "blur" -> set("amount", 12f)
            "sharpen" -> set("amount", 2f)
            "smooth" -> set("amount", 8f)
            "mirror" -> set("rightToLeft", false)
            "make_transparent" -> set("amount", 0.7f)
            "pixelize" -> set("size", 45f)
            "dizziness" -> set("amount", 25f)
            "comic_print" -> set("dot", 10f)
            "film_grain" -> set("amount", 1f)
            "solid_border" -> set("width", 50f)
            "rounded_corners" -> set("amount", 1f)
            "bulge" -> { set("amount", 1f); set("radius", 0.6f); set("center", floatArrayOf(0.3f, 0.65f)) }
            "spherize" -> { set("amount", 1f); set("radius", 0.6f); set("center", floatArrayOf(0.3f, 0.65f)) }
            "stretch" -> { set("amount", 1f); set("angle", 90f); set("radius", 0.6f); set("center", floatArrayOf(0.3f, 0.65f)) }
            "stretch_h", "stretch_v" -> { set("amount", 1f); set("radius", 0.45f); set("center", floatArrayOf(0.28f, 0.68f)) }
            "tilt_shift" -> { set("blur", 30f); set("center", floatArrayOf(0.5f, 0.7f)) }
            "soft_glow" -> set("amount", 1f)
            "top_lens", "edge_lens" -> set("amount", 1f)
        }
        if (op.isIdentity(p)) {
            // generic fallback: move the first slider three quarters of the way to its maximum
            op.params.filterIsInstance<ParamSpec.Slider>().firstOrNull()?.let { s -> set(s.key, s.default + (s.max - s.default) * 0.75f) }
        }
        return p
    }

    /** The sample with [op] applied (geometric tools may change the size). */
    fun forOp(op: ImageOp, sample: PixelBuffer, ctx: RenderContext = RenderContext()): PixelBuffer = when (op.id) {
        TransformOps.Crop.id -> cropPicture(sample)
        TransformOps.Resize.id -> resizePicture(sample)
        else -> op.apply(sample, demoParams(op), ctx)
    }

    enum class DrawDemo { BRUSH, MARKER, ERASER, HISTORY, MASK, RESTORE }

    private fun wave(size: Float, color: Int, mode: StrokeMode, opacity: Float = 1f, hardness: Float = 0.8f): Stroke {
        val n = 40
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = 0.12f + 0.76f * t
            pts[i * 3 + 1] = 0.55f + 0.17f * sin(t * 2 * PI.toFloat() * 1.1f)
            pts[i * 3 + 2] = 1f
        }
        return Stroke(pts, size, hardness, opacity, color, mode, pressureSize = false)
    }

    fun forDraw(kind: DrawDemo, sample: PixelBuffer, ctx: RenderContext = RenderContext()): PixelBuffer = when (kind) {
        DrawDemo.BRUSH -> StrokePainter.paint(sample, listOf(wave(0.08f, 0xFFE53935.toInt(), StrokeMode.PAINT)), ctx)
        DrawDemo.MARKER -> StrokePainter.paint(sample, listOf(wave(0.16f, 0xFFFFEB3B.toInt(), StrokeMode.MARKER, 0.6f, 1f)), ctx)
        DrawDemo.ERASER -> StrokePainter.paint(sample, listOf(wave(0.16f, 0xFF000000.toInt(), StrokeMode.ERASE)), ctx)
        DrawDemo.HISTORY -> {
            // a toned-down picture whose original colours come back under the stroke
            val faded = ColorOps.Sepia.apply(sample, ColorOps.Sepia.params.defaults(), ctx)
            revealUnder(faded, sample, wave(0.2f, 0xFF000000.toInt(), StrokeMode.PAINT, hardness = 0.6f))
        }
        DrawDemo.MASK -> {
            // the red mask overlay the mask tool shows while painting
            val tint = PixelBuffer(sample.width, sample.height, IntArray(sample.size) { EffectKit.mixColor(sample.pixels[it], 0xFFFF1744.toInt(), 0.55f) })
            revealUnder(sample, tint, wave(0.22f, 0xFF000000.toInt(), StrokeMode.PAINT, hardness = 0.9f))
        }
        DrawDemo.RESTORE -> {
            // left half: edited, right half: back to the original
            val edited = ColorOps.Sepia.apply(sample, ColorOps.Sepia.params.defaults(), ctx)
            val split = sample.width / 2
            EffectKit.mapXY(sample, ctx) { x, y, c ->
                when {
                    x == split -> 0xFFFFFFFF.toInt()
                    x < split -> edited.pixels[y * sample.width + x]
                    else -> c
                }
            }
        }
    }

    /** [top] with [bottom] showing through where [stroke] covers. */
    private fun revealUnder(top: PixelBuffer, bottom: PixelBuffer, stroke: Stroke): PixelBuffer {
        val cov = StrokeRasterizer.maskCoverage(listOf(stroke), top.width, top.height, 0f)
        return PixelBuffer(top.width, top.height, IntArray(top.size) { EffectKit.mixColor(top.pixels[it], bottom.pixels[it], cov[it]) })
    }

    /** Crop: the area left out is dimmed, with the frame and rule-of-thirds lines. */
    private fun cropPicture(s: PixelBuffer): PixelBuffer {
        val w = s.width; val h = s.height
        val l = (w * 0.18f).toInt(); val r = (w * 0.82f).toInt(); val t = (h * 0.14f).toInt(); val b = (h * 0.86f).toInt()
        return EffectKit.mapXY(s, RenderContext()) { x, y, c ->
            val inside = x in l..r && y in t..b
            val frame = inside && (x <= l + 1 || x >= r - 1 || y <= t + 1 || y >= b - 1)
            val thirds = inside && (x == l + (r - l) / 3 || x == l + 2 * (r - l) / 3 || y == t + (b - t) / 3 || y == t + 2 * (b - t) / 3)
            when {
                frame -> 0xFFFFFFFF.toInt()
                thirds -> EffectKit.mixColor(c, 0xFFFFFFFF.toInt(), 0.6f)
                inside -> c
                else -> EffectKit.mixColor(c, 0xFF000000.toInt(), 0.6f)
            }
        }
    }

    /** Resize: the picture shrunk inside a dashed outline of its former size. */
    private fun resizePicture(s: PixelBuffer): PixelBuffer {
        val w = s.width; val h = s.height
        val sw = (w * 0.6f).toInt(); val sh = (h * 0.6f).toInt()
        val small = Resample.resize(s, sw, sh)
        val ox = (w - sw) / 2; val oy = (h - sh) / 2
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dash = ((x + y) / 4) % 2 == 0
            val border = (x <= 1 || x >= w - 2 || y <= 1 || y >= h - 2) && dash
            out[y * w + x] = when {
                x in ox until ox + sw && y in oy until oy + sh -> small.pixels[(y - oy) * sw + (x - ox)]
                border -> 0xFFFFFFFF.toInt()
                else -> 0
            }
        }
        return PixelBuffer(w, h, out)
    }
}
