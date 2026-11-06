package app.bocadillo.core.imaging.ops

import app.bocadillo.core.imaging.ColorMatrix
import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.Srgb
import app.bocadillo.core.imaging.alpha
import app.bocadillo.core.imaging.applyLut
import app.bocadillo.core.imaging.argb
import app.bocadillo.core.imaging.blue
import app.bocadillo.core.imaging.clamp255
import app.bocadillo.core.imaging.green
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.imaging.red
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.ParamSpec.Unit.DEGREES
import app.bocadillo.core.model.ParamSpec.Unit.PERCENT
import app.bocadillo.core.model.Params
import kotlin.math.abs
import kotlin.math.pow

/**
 * Colour adjustments. Slider ranges/defaults follow the values verified in the original
 * (see docs/FASE1_INFORME_TECNICO.md §3.1 and B2). The original pixel math lives in native
 * code that was not decompiled; the formulas here are standard equivalents and are marked
 * "pending visual calibration" where the original curve is unknown.
 */
object ColorOps {

    private abstract class MatrixOp : ImageOp {
        override val geometric = false
        abstract fun matrix(p: Params): ColorMatrix
        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext) = matrix(p).apply(src, ctx)
    }

    /** Brightness −100…100 step 1 [verified range]. Additive offset of up to ±50 % (calibration pending). */
    object Brightness : ImageOp by object : MatrixOp() {
        override val id = "brightness"
        override val category = OpCategory.ADJUST
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(ParamSpec.Slider("amount", "brightness", -100f, 100f, 1f, 0f))
        override fun isIdentity(p: Params) = p.float("amount", 0f) == 0f
        override fun matrix(p: Params): ColorMatrix {
            val off = p.float("amount", 0f) / 100f * 127.5f
            return ColorMatrix.scaleOffset(1f, 1f, 1f, off)
        }
    }

    /**
     * Exposure −2…2 EV (applied in linear light) and contrast −0.5…0.5 around mid grey
     * [both ranges verified, one filter "expo" with two params, as in the original].
     */
    object ExposureContrast : ImageOp {
        override val id = "exposure_contrast"
        override val category = OpCategory.ADJUST
        override val geometric = false
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(
            ParamSpec.Slider("exposure", "exposure", -2f, 2f, 0.01f, 0f),
            ParamSpec.Slider("contrast", "contrast", -0.5f, 0.5f, 0.01f, 0f, PERCENT, displayFactor = 200f),
        )

        override fun isIdentity(p: Params) = p.float("exposure", 0f) == 0f && p.float("contrast", 0f) == 0f

        fun lut(exposure: Float, contrast: Float): IntArray {
            val gain = 2.0.pow(exposure.toDouble()).toFloat()
            val c = contrast.coerceIn(-0.5f, 0.5f)
            val k = (1f + c) / (1f - c) // 1/3 … 3
            return IntArray(256) { v ->
                val e = Srgb.fromLinear(Srgb.toLinear(v) * gain) * 255f
                clamp255((e - 127.5f) * k + 127.5f)
            }
        }

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val t = lut(p.float("exposure", 0f), p.float("contrast", 0f))
            return applyLut(src, t, t, t, ctx)
        }
    }

    /** Saturation −1…1 (−1 = grey, 1 = double) [min verified; max inferred]. */
    object Saturation : ImageOp by object : MatrixOp() {
        override val id = "saturation"
        override val category = OpCategory.ADJUST
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(ParamSpec.Slider("amount", "saturation", -1f, 1f, 0.01f, 0f, PERCENT, 100f))
        override fun isIdentity(p: Params) = p.float("amount", 0f) == 0f
        override fun matrix(p: Params) = ColorMatrix.saturation(1f + p.float("amount", 0f))
    }

    /**
     * Temperature and tint −1…1 [min verified; max inferred]. Positive temperature warms
     * (more red, less blue); positive tint shifts toward magenta. Gains applied in linear light.
     */
    object TemperatureTint : ImageOp {
        override val id = "temperature_tint"
        override val category = OpCategory.ADJUST
        override val geometric = false
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(
            ParamSpec.Slider("temperature", "temperature", -1f, 1f, 0.01f, 0f, PERCENT, 100f),
            ParamSpec.Slider("tint", "tint", -1f, 1f, 0.01f, 0f, PERCENT, 100f),
        )

        override fun isIdentity(p: Params) = p.float("temperature", 0f) == 0f && p.float("tint", 0f) == 0f

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val t = p.float("temperature", 0f); val n = p.float("tint", 0f)
            fun lut(gain: Float) = IntArray(256) { clamp255(Srgb.fromLinear(Srgb.toLinear(it) * gain) * 255f) }
            val gr = 1f + 0.35f * t + 0.12f * n
            val gg = 1f - 0.30f * n
            val gb = 1f - 0.35f * t + 0.12f * n
            return applyLut(src, lut(gr), lut(gg), lut(gb), ctx)
        }
    }

    /** Hue rotation −180°…180° step 1, original default 90° [verified]. */
    object Hue : ImageOp by object : MatrixOp() {
        override val id = "hue"
        override val category = OpCategory.COLOR
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(ParamSpec.Slider("amount", "hue", -180f, 180f, 1f, 90f, DEGREES))
        override fun isIdentity(p: Params) = p.float("amount", 0f) == 0f
        override fun matrix(p: Params) = ColorMatrix.hueRotation(p.float("amount", 0f))
    }

    /** Invert colours (no parameters) [verified]. */
    object Invert : ImageOp by object : MatrixOp() {
        override val id = "invert"
        override val category = OpCategory.COLOR
        override val origin = Origin.PICSAY_BOTH
        override val params = emptyList<ParamSpec>()
        override fun matrix(p: Params) = ColorMatrix.invert()
    }

    /** Sepia toning, amount 0…1, default 1 [default verified]. */
    object Sepia : ImageOp by object : MatrixOp() {
        override val id = "sepia"
        override val category = OpCategory.COLOR
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(ParamSpec.Slider("amount", "amount", 0f, 1f, 0.01f, 1f, PERCENT, 100f))
        override fun isIdentity(p: Params) = p.float("amount", 1f) == 0f
        override fun matrix(p: Params) = ColorMatrix.lerp(ColorMatrix.identity(), ColorMatrix.sepia(), p.float("amount", 1f))
    }

    /**
     * Colorize: replaces hue and saturation keeping luminance. [hue param and "colr" filter
     * verified; exact formula pending calibration].
     */
    object Colorize : ImageOp {
        override val id = "colorize"
        override val category = OpCategory.COLOR
        override val geometric = false
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(
            ParamSpec.Slider("hue", "hue", 0f, 360f, 1f, 30f, DEGREES),
            ParamSpec.Slider("saturation", "saturation", 0f, 1f, 0.01f, 0.5f, PERCENT, 100f),
            ParamSpec.Slider("amount", "amount", 0f, 1f, 0.01f, 1f, PERCENT, 100f),
        )

        override fun isIdentity(p: Params) = p.float("amount", 1f) == 0f

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val hue = p.float("hue", 30f); val sat = p.float("saturation", 0.5f); val amt = p.float("amount", 1f)
            // Precompute the colourized value for each of 256 luminance levels.
            val table = IntArray(256) { l -> hslToRgb(hue, sat, l / 255f) }
            val out = IntArray(src.size); val px = src.pixels
            ctx.forRows(src.height) { y0, y1 ->
                for (i in y0 * src.width until y1 * src.width) {
                    val c = px[i]
                    val r = red(c); val g = green(c); val b = blue(c)
                    val l = clamp255(ColorMatrix.LR * r + ColorMatrix.LG * g + ColorMatrix.LB * b)
                    val t = table[l]
                    out[i] = argb(alpha(c), mix(r, red(t), amt), mix(g, green(t), amt), mix(b, blue(t), amt))
                }
            }
            return PixelBuffer(src.width, src.height, out)
        }

        private fun mix(a: Int, b: Int, t: Float) = clamp255(a + (b - a) * t)
    }

    /** HSL → packed RGB (alpha 255). h in degrees, s and l in 0…1. */
    fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f + 360f) % 360f) / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return argb(255, clamp255((r1 + m) * 255f), clamp255((g1 + m) * 255f), clamp255((b1 + m) * 255f))
    }

    val all: List<ImageOp> = listOf(Brightness, ExposureContrast, Saturation, TemperatureTint, Hue, Colorize, Invert, Sepia)
}
