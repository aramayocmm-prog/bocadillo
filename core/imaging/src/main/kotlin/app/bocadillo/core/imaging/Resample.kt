package app.bocadillo.core.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * High-quality resampling.
 * - [resize]: separable Lanczos-3 in premultiplied space (proper prefiltering when downscaling).
 * - [AffineSampler]: bilinear inverse mapping for rotations / straightening.
 */
object Resample {

    fun resize(src: PixelBuffer, dstW: Int, dstH: Int, ctx: RenderContext = RenderContext.DEFAULT): PixelBuffer {
        require(dstW > 0 && dstH > 0) { "Invalid target ${dstW}x$dstH" }
        if (dstW == src.width && dstH == src.height) return src.copy()
        val pre = IntArray(src.size).also { a -> ctx.forRows(src.height) { y0, y1 -> for (i in y0 * src.width until y1 * src.width) a[i] = premultiply(src.pixels[i]) } }
        val horizontalFirst = dstW.toLong() * src.height <= src.width.toLong() * dstH
        val result = if (horizontalFirst) {
            val t = horizontal(pre, src.width, src.height, dstW, ctx)
            vertical(t, dstW, src.height, dstH, ctx)
        } else {
            val t = vertical(pre, src.width, src.height, dstH, ctx)
            horizontal(t, src.width, dstH, dstW, ctx)
        }
        ctx.forRows(dstH) { y0, y1 -> for (i in y0 * dstW until y1 * dstW) result[i] = unpremultiply(result[i]) }
        return PixelBuffer(dstW, dstH, result)
    }

    private class Kernel(val start: IntArray, val taps: Int, val weights: FloatArray)

    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    private fun kernel(srcLen: Int, dstLen: Int): Kernel {
        val scale = dstLen.toDouble() / srcLen
        val filterScale = max(1.0, 1.0 / scale)
        val support = 3.0 * filterScale
        val taps = ceil(support * 2).toInt() + 1
        val start = IntArray(dstLen)
        val weights = FloatArray(dstLen * taps)
        for (i in 0 until dstLen) {
            val center = (i + 0.5) / scale - 0.5
            val first = floor(center - support).toInt() + 1
            start[i] = first
            var sum = 0.0
            val tmp = DoubleArray(taps)
            for (t in 0 until taps) {
                val w = lanczos((first + t - center) / filterScale)
                tmp[t] = w; sum += w
            }
            for (t in 0 until taps) weights[i * taps + t] = (tmp[t] / sum).toFloat()
        }
        return Kernel(start, taps, weights)
    }

    private fun horizontal(src: IntArray, w: Int, h: Int, dstW: Int, ctx: RenderContext): IntArray {
        val k = kernel(w, dstW)
        val out = IntArray(dstW * h)
        ctx.forRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until dstW) {
                    var a = 0f; var r = 0f; var g = 0f; var b = 0f
                    val s = k.start[x]; val wo = x * k.taps
                    for (t in 0 until k.taps) {
                        val wt = k.weights[wo + t]
                        if (wt == 0f) continue
                        val c = src[row + min(w - 1, max(0, s + t))]
                        a += wt * alpha(c); r += wt * red(c); g += wt * green(c); b += wt * blue(c)
                    }
                    out[y * dstW + x] = packPremul(a, r, g, b)
                }
            }
        }
        return out
    }

    private fun vertical(src: IntArray, w: Int, h: Int, dstH: Int, ctx: RenderContext): IntArray {
        val k = kernel(h, dstH)
        val out = IntArray(w * dstH)
        ctx.forRows(dstH) { y0, y1 ->
            for (y in y0 until y1) {
                val s = k.start[y]; val wo = y * k.taps
                for (x in 0 until w) {
                    var a = 0f; var r = 0f; var g = 0f; var b = 0f
                    for (t in 0 until k.taps) {
                        val wt = k.weights[wo + t]
                        if (wt == 0f) continue
                        val c = src[min(h - 1, max(0, s + t)) * w + x]
                        a += wt * alpha(c); r += wt * red(c); g += wt * green(c); b += wt * blue(c)
                    }
                    out[y * w + x] = packPremul(a, r, g, b)
                }
            }
        }
        return out
    }

    /** Packs premultiplied channels, clamping ringing so colour never exceeds alpha. */
    private fun packPremul(a: Float, r: Float, g: Float, b: Float): Int {
        val ai = clamp255(a)
        return argb(ai, min(ai, clamp255(r)), min(ai, clamp255(g)), min(ai, clamp255(b)))
    }
}

/**
 * Renders a destination image by mapping each destination pixel center through an inverse
 * 2x3 affine matrix [inv] (dst → src) and sampling bilinearly. Outside pixels get [background].
 */
object AffineSampler {
    fun render(
        src: PixelBuffer, dstW: Int, dstH: Int, inv: DoubleArray, background: Int, ctx: RenderContext,
    ): PixelBuffer {
        require(inv.size == 6)
        val pre = IntArray(src.size) { premultiply(src.pixels[it]) }
        val bg = premultiply(background)
        val out = IntArray(dstW * dstH)
        val sw = src.width; val sh = src.height
        ctx.forRows(dstH) { y0, y1 ->
            for (y in y0 until y1) {
                val dy = y + 0.5
                for (x in 0 until dstW) {
                    val dx = x + 0.5
                    val sx = inv[0] * dx + inv[1] * dy + inv[2] - 0.5
                    val sy = inv[3] * dx + inv[4] * dy + inv[5] - 0.5
                    out[y * dstW + x] = unpremultiply(sampleBilinear(pre, sw, sh, sx, sy, bg))
                }
            }
        }
        return PixelBuffer(dstW, dstH, out)
    }

    /** Bilinear sample of premultiplied pixels; texels outside the image count as [bg]. */
    internal fun sampleBilinear(p: IntArray, w: Int, h: Int, sx: Double, sy: Double, bg: Int): Int {
        // Points within half a texel of the border clamp to the edge (no dark fringe);
        // points further out are background.
        if (sx < -0.5 || sy < -0.5 || sx > w - 0.5 || sy > h - 0.5) return bg
        val cx = sx.coerceIn(0.0, w - 1.0); val cy = sy.coerceIn(0.0, h - 1.0)
        val x0 = floor(cx).toInt(); val y0 = floor(cy).toInt()
        val fx = (cx - x0).toFloat(); val fy = (cy - y0).toFloat()
        fun at(x: Int, y: Int) = p[min(y, h - 1) * w + min(x, w - 1)]
        val c00 = at(x0, y0); val c10 = at(x0 + 1, y0); val c01 = at(x0, y0 + 1); val c11 = at(x0 + 1, y0 + 1)
        val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
        fun ch(shift: Int) = clamp255(
            w00 * ((c00 ushr shift) and 0xFF) + w10 * ((c10 ushr shift) and 0xFF) +
                w01 * ((c01 ushr shift) and 0xFF) + w11 * ((c11 ushr shift) and 0xFF),
        )
        return argb(ch(24), ch(16), ch(8), ch(0))
    }
}
