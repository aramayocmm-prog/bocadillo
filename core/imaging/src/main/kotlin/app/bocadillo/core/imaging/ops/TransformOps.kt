package app.bocadillo.core.imaging.ops

import app.bocadillo.core.imaging.AffineSampler
import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.Resample
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.ParamSpec.Unit.DEGREES
import app.bocadillo.core.model.ParamSpec.Unit.PIXELS
import app.bocadillo.core.model.Params
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Geometry helpers shared by the ops and the crop UI. */
object Geometry {
    /**
     * Largest factor s such that a centred w*s × h*s rectangle fits inside the w × h image
     * rotated by [degrees]. Used to auto-crop when straightening.
     */
    fun straightenScale(w: Int, h: Int, degrees: Float): Float {
        val t = Math.toRadians(abs(degrees).toDouble())
        val c = cos(t); val s = sin(t)
        val k = min(w / (w * c + h * s), h / (w * s + h * c))
        return k.toFloat().coerceAtMost(1f)
    }

    /** Aspect-ratio presets of the original crop tool (ratio = width / height, null = free). */
    enum class CropPreset(val ratio: Float?) {
        FREEFORM(null), ORIGINAL(null), GOLDEN(1.618034f), SQUARE(1f), WALLPAPER(null), CUSTOM(null)
    }

    /** Biggest centred normalized rect [l,t,r,b] with the given pixel aspect inside w×h. */
    fun centeredRect(w: Int, h: Int, ratio: Float): FloatArray {
        val imgRatio = w.toFloat() / h
        return if (ratio > imgRatio) {
            val nh = imgRatio / ratio
            floatArrayOf(0f, (1 - nh) / 2, 1f, (1 + nh) / 2)
        } else {
            val nw = ratio / imgRatio
            floatArrayOf((1 - nw) / 2, 0f, (1 + nw) / 2, 1f)
        }
    }
}

object TransformOps {

    /** Lossless 90° rotations and flips ("Rotate and Flip"). rotation ∈ {0,90,180,270}. */
    object Orient : ImageOp {
        override val id = "orient"
        override val category = OpCategory.TRANSFORM
        override val geometric = true
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(
            ParamSpec.Choice("rotation", "rotation", listOf("0", "90", "180", "270"), "0"),
            ParamSpec.Toggle("flipH", "flip_horizontal", false),
            ParamSpec.Toggle("flipV", "flip_vertical", false),
        )

        private fun rot(p: Params) = p.string("rotation", "0").toInt()

        override fun isIdentity(p: Params) = rot(p) == 0 && !p.bool("flipH", false) && !p.bool("flipV", false)

        override fun outputSize(w: Int, h: Int, p: Params, ctx: RenderContext) =
            if (rot(p) == 90 || rot(p) == 270) h to w else w to h

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val r = rot(p)
            val fh = p.bool("flipH", false); val fv = p.bool("flipV", false)
            val (ow, oh) = outputSize(src.width, src.height, p, ctx)
            val out = IntArray(ow * oh)
            val sw = src.width; val sh = src.height; val s = src.pixels
            ctx.forRows(oh) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until ow) {
                    // flips are applied after rotation, in output space
                    val ox = if (fh) ow - 1 - x else x
                    val oy = if (fv) oh - 1 - y else y
                    val (sx, sy) = when (r) {
                        90 -> oy to (sh - 1 - ox)
                        180 -> (sw - 1 - ox) to (sh - 1 - oy)
                        270 -> (sw - 1 - oy) to ox
                        else -> ox to oy
                    }
                    out[y * ow + x] = s[sy * sw + sx]
                }
            }
            return PixelBuffer(ow, oh, out)
        }
    }

    /**
     * "Crop and Straighten": rotates the picture by [angle] around its centre (−10°…10°, step
     * 0.1, as verified in the original) and keeps the normalized rect [l,t,r,b] measured in the
     * rotated frame. Pixels outside the rotated picture become transparent.
     */
    object Crop : ImageOp {
        override val id = "crop"
        override val category = OpCategory.TRANSFORM
        override val geometric = true
        override val origin = Origin.PICSAY_BOTH
        override val params = listOf(
            ParamSpec.Slider("angle", "straighten", -10f, 10f, 0.1f, 0f, DEGREES),
            // rect is a float list [l,t,r,b]; edited on canvas, not by slider
        )

        private fun rect(p: Params): FloatArray {
            val r = p.floats("rect") ?: floatArrayOf(0f, 0f, 1f, 1f)
            require(r.size == 4) { "crop rect must have 4 values" }
            val l = r[0].coerceIn(0f, 1f); val t = r[1].coerceIn(0f, 1f)
            return floatArrayOf(l, t, max(l + 1e-4f, r[2].coerceIn(0f, 1f)), max(t + 1e-4f, r[3].coerceIn(0f, 1f)))
        }

        override fun isIdentity(p: Params): Boolean {
            val r = rect(p)
            return p.float("angle", 0f) == 0f && r[0] == 0f && r[1] == 0f && r[2] == 1f && r[3] == 1f
        }

        override fun outputSize(w: Int, h: Int, p: Params, ctx: RenderContext): Pair<Int, Int> {
            val r = rect(p)
            return max(1, ((r[2] - r[0]) * w).roundToInt()) to max(1, ((r[3] - r[1]) * h).roundToInt())
        }

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val r = rect(p)
            val (ow, oh) = outputSize(src.width, src.height, p, ctx)
            val angle = p.float("angle", 0f)
            val w = src.width.toDouble(); val h = src.height.toDouble()
            if (angle == 0f) {
                val x0 = (r[0] * w).roundToInt().coerceIn(0, src.width - 1)
                val y0 = (r[1] * h).roundToInt().coerceIn(0, src.height - 1)
                val cw = min(ow, src.width - x0); val ch = min(oh, src.height - y0)
                val out = IntArray(cw * ch)
                for (y in 0 until ch) System.arraycopy(src.pixels, (y0 + y) * src.width + x0, out, y * cw, cw)
                return PixelBuffer(cw, ch, out)
            }
            // dst (x,y) → frame point (x + l*w, y + t*h) → rotate by −angle around centre → src
            val a = Math.toRadians(-angle.toDouble()); val c = cos(a); val s = sin(a)
            val cx = w / 2; val cy = h / 2
            val ox = r[0] * w - cx; val oy = r[1] * h - cy
            val inv = doubleArrayOf(c, -s, c * ox - s * oy + cx, s, c, s * ox + c * oy + cy)
            return AffineSampler.render(src, ow, oh, inv, 0, ctx)
        }
    }

    /**
     * "Resize" dialog. width/height are in full-resolution pixels (scaled by ctx.scale for
     * previews). Modes from the original: stretch, fit ("Quick Fit"), fill (scale & crop),
     * crop (centre crop, no scaling) and expand ("Expand Edges", centre, background colour).
     */
    object Resize : ImageOp {
        override val id = "resize"
        override val category = OpCategory.TRANSFORM
        override val geometric = true
        override val origin = Origin.PICSAY_BOTH
        val MODES = listOf("stretch", "fit", "fill", "crop", "expand")
        override val params = listOf(
            ParamSpec.Slider("width", "width", 1f, 20000f, 1f, 1024f, PIXELS),
            ParamSpec.Slider("height", "height", 1f, 20000f, 1f, 768f, PIXELS),
            ParamSpec.Choice("mode", "resize_mode", MODES, "stretch"),
            ParamSpec.Color("background", "background_color", 0xFFFFFFFF.toInt()),
        )

        private fun target(p: Params, ctx: RenderContext) =
            max(1, (p.float("width", 1f) * ctx.scale).roundToInt()) to max(1, (p.float("height", 1f) * ctx.scale).roundToInt())

        override fun outputSize(w: Int, h: Int, p: Params, ctx: RenderContext): Pair<Int, Int> {
            val (tw, th) = target(p, ctx)
            return when (p.string("mode", "stretch")) {
                "fit" -> {
                    val k = min(tw.toFloat() / w, th.toFloat() / h)
                    max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
                }
                else -> tw to th
            }
        }

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val (tw, th) = target(p, ctx)
            val bg = p.color("background", 0xFFFFFFFF.toInt())
            return when (p.string("mode", "stretch")) {
                "stretch" -> Resample.resize(src, tw, th, ctx)
                "fit" -> { val (ow, oh) = outputSize(src.width, src.height, p, ctx); Resample.resize(src, ow, oh, ctx) }
                "fill" -> {
                    val k = max(tw.toFloat() / src.width, th.toFloat() / src.height)
                    val sw = max(tw, (src.width * k).roundToInt()); val sh = max(th, (src.height * k).roundToInt())
                    place(Resample.resize(src, sw, sh, ctx), tw, th, bg)
                }
                "crop", "expand" -> place(src, tw, th, bg)
                else -> throw IllegalArgumentException("Unknown resize mode")
            }
        }

        /** Centres [src] on a tw×th canvas filled with [bg] (crops when larger). */
        private fun place(src: PixelBuffer, tw: Int, th: Int, bg: Int): PixelBuffer {
            val out = PixelBuffer.filled(tw, th, bg)
            val dx = (tw - src.width) / 2; val dy = (th - src.height) / 2
            for (y in 0 until src.height) {
                val ty = y + dy
                if (ty < 0 || ty >= th) continue
                val sx0 = max(0, -dx); val sx1 = min(src.width, tw - dx)
                if (sx1 <= sx0) continue
                System.arraycopy(src.pixels, y * src.width + sx0, out.pixels, ty * tw + sx0 + dx, sx1 - sx0)
            }
            return out
        }
    }

    /** Free rotation of the whole picture (new tool; original only rotated objects freely). */
    object Rotate : ImageOp {
        override val id = "rotate"
        override val category = OpCategory.MODERN
        override val geometric = true
        override val origin = Origin.NEW
        override val params = listOf(
            ParamSpec.Slider("angle", "angle", -180f, 180f, 0.1f, 0f, DEGREES),
            ParamSpec.Toggle("expand", "expand_canvas", true),
            ParamSpec.Color("background", "background_color", 0x00000000),
        )

        override fun isIdentity(p: Params) = p.float("angle", 0f) == 0f

        override fun outputSize(w: Int, h: Int, p: Params, ctx: RenderContext): Pair<Int, Int> {
            if (!p.bool("expand", true)) return w to h
            val a = Math.toRadians(p.float("angle", 0f).toDouble())
            val c = abs(cos(a)); val s = abs(sin(a))
            return max(1, (w * c + h * s).roundToInt()) to max(1, (w * s + h * c).roundToInt())
        }

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
            val (ow, oh) = outputSize(src.width, src.height, p, ctx)
            val a = Math.toRadians(-p.float("angle", 0f).toDouble()); val c = cos(a); val s = sin(a)
            val dcx = ow / 2.0; val dcy = oh / 2.0; val scx = src.width / 2.0; val scy = src.height / 2.0
            val inv = doubleArrayOf(c, -s, -c * dcx + s * dcy + scx, s, c, -s * dcx - c * dcy + scy)
            return AffineSampler.render(src, ow, oh, inv, p.color("background", 0), ctx)
        }
    }

    val all: List<ImageOp> = listOf(Crop, Resize, Orient, Rotate)
}
