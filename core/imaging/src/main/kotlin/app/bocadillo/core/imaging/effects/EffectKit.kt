package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.AffineSampler
import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.premultiply
import app.bocadillo.core.imaging.unpremultiply
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Toolkit shared by the effects. Pure Kotlin; every size is expressed relative to
 * u = min(width, height) so the screen preview and the full-size export look the same.
 */
object EffectKit {
    fun a(c: Int) = c ushr 24
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    fun clampF(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()
    fun pack(a: Int, r: Int, g: Int, b: Int) = (clamp(a) shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
    fun packF(a: Int, r: Float, g: Float, b: Float) = (clamp(a) shl 24) or (clampF(r) shl 16) or (clampF(g) shl 8) or clampF(b)
    fun lum(c: Int) = 0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)
    fun mix(x: Float, y: Float, t: Float) = x + (y - x) * t

    /** Mixes the colour channels of [c1] into [c0] by [t]; keeps the alpha of [c0]. */
    fun mixColor(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        val k = min(1f, t)
        return packF(a(c0), mix(r(c0).toFloat(), r(c1).toFloat(), k), mix(g(c0).toFloat(), g(c1).toFloat(), k), mix(b(c0).toFloat(), b(c1).toFloat(), k))
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Unit for sizes: the smaller side in pixels. */
    fun unit(p: PixelBuffer) = min(p.width, p.height).toFloat()

    // ---- blend modes on 0..1 values ----
    fun multiply(a: Float, b: Float) = a * b
    fun screen(a: Float, b: Float) = 1f - (1f - a) * (1f - b)
    fun overlay(a: Float, b: Float) = if (a < 0.5f) 2f * a * b else 1f - 2f * (1f - a) * (1f - b)
    fun softLight(a: Float, b: Float) = (1f - 2f * b) * a * a + 2f * b * a

    /** Applies [f] (0..1 per channel) between [base] and [top] and mixes the result by [amount]. */
    inline fun blend(base: Int, top: Int, amount: Float, f: (Float, Float) -> Float): Int {
        val r = f(r(base) / 255f, r(top) / 255f) * 255f
        val g = f(g(base) / 255f, g(top) / 255f) * 255f
        val b = f(b(base) / 255f, b(top) / 255f) * 255f
        return mixColor(base, packF(255, r, g, b), amount)
    }

    /** "Color" blend (W3C): hue and saturation of [top], luminosity of [base]. */
    fun colorBlend(base: Int, top: Int): Int {
        val lb = 0.3f * r(base) + 0.59f * g(base) + 0.11f * b(base)
        val lt = 0.3f * r(top) + 0.59f * g(top) + 0.11f * b(top)
        val d = lb - lt
        var cr = r(top) + d; var cg = g(top) + d; var cb = b(top) + d
        val l = 0.3f * cr + 0.59f * cg + 0.11f * cb
        val n = min(cr, min(cg, cb)); val x = max(cr, max(cg, cb))
        if (n < 0f) { cr = l + (cr - l) * l / (l - n); cg = l + (cg - l) * l / (l - n); cb = l + (cb - l) * l / (l - n) }
        if (x > 255f) { cr = l + (cr - l) * (255f - l) / (x - l); cg = l + (cg - l) * (255f - l) / (x - l); cb = l + (cb - l) * (255f - l) / (x - l) }
        return packF(a(base), cr, cg, cb)
    }

    /** Scales saturation around the pixel's luminance (s = 1 keeps it). */
    fun saturate(c: Int, s: Float): Int {
        val l = lum(c)
        return packF(a(c), l + (r(c) - l) * s, l + (g(c) - l) * s, l + (b(c) - l) * s)
    }

    /** Fully saturated colour of hue [deg] (HSV with s = v = 1). */
    fun hueColor(deg: Float): Int {
        val h = ((deg % 360f) + 360f) % 360f / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, x, 0f); 1 -> Triple(x, 1f, 0f); 2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f); 4 -> Triple(x, 0f, 1f); else -> Triple(1f, 0f, x)
        }
        return packF(255, r * 255f, g * 255f, b * 255f)
    }

    /** Linear interpolation through opaque colour stops, [t] in 0..1. */
    fun gradient(stops: IntArray, t: Float): Int {
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = min(stops.size - 2, x.toInt())
        return mixColor(stops[i] or (0xFF shl 24), stops[i + 1] or (0xFF shl 24), x - i)
    }

    /** Lookup table 0..255 → 0..255 from a curve on 0..1. */
    fun lut(f: (Float) -> Float) = IntArray(256) { clampF(f(it / 255f) * 255f) }

    /** Smooth S-curve with strength [k] (0 = identity); keeps 0, 0.5 and 1 fixed. */
    fun sCurve(x: Float, k: Float): Float {
        if (k == 0f) return x
        val e = 1f + k * 2f
        return if (x < 0.5f) 0.5f * Math.pow((2.0 * x), e.toDouble()).toFloat() else 1f - 0.5f * Math.pow((2.0 * (1 - x)), e.toDouble()).toFloat()
    }

    /** Deterministic per-coordinate noise in −1..1 (same seed ⇒ same pattern). */
    fun noise(x: Int, y: Int, seed: Int = 0): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /** Smooth value noise at continuous coordinates (bilinear between lattice points). */
    fun valueNoise(x: Float, y: Float, seed: Int = 0): Float {
        val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val a = mix(noise(x0, y0, seed), noise(x0 + 1, y0, seed), fx)
        val b = mix(noise(x0, y0 + 1, seed), noise(x0 + 1, y0 + 1, seed), fx)
        return mix(a, b, fy)
    }

    // ---- per-pixel maps ----

    inline fun map(src: PixelBuffer, ctx: RenderContext, crossinline f: (Int) -> Int): PixelBuffer {
        val out = IntArray(src.size); val p = src.pixels; val w = src.width
        ctx.forRows(src.height) { y0, y1 -> for (i in y0 * w until y1 * w) out[i] = f(p[i]) }
        return PixelBuffer(w, src.height, out)
    }

    inline fun mapXY(src: PixelBuffer, ctx: RenderContext, crossinline f: (x: Int, y: Int, c: Int) -> Int): PixelBuffer {
        val out = IntArray(src.size); val p = src.pixels; val w = src.width
        ctx.forRows(src.height) { y0, y1 -> for (y in y0 until y1) { val o = y * w; for (x in 0 until w) out[o + x] = f(x, y, p[o + x]) } }
        return PixelBuffer(w, src.height, out)
    }

    inline fun combine(a: PixelBuffer, b: PixelBuffer, ctx: RenderContext, crossinline f: (i: Int, ca: Int, cb: Int) -> Int): PixelBuffer {
        require(a.width == b.width && a.height == b.height)
        val out = IntArray(a.size); val pa = a.pixels; val pb = b.pixels; val w = a.width
        ctx.forRows(a.height) { y0, y1 -> for (i in y0 * w until y1 * w) out[i] = f(i, pa[i], pb[i]) }
        return PixelBuffer(w, a.height, out)
    }

    fun applyLuts(src: PixelBuffer, lr: IntArray, lg: IntArray, lb: IntArray, ctx: RenderContext) =
        map(src, ctx) { c -> (c and (0xFF shl 24)) or (lr[r(c)] shl 16) or (lg[g(c)] shl 8) or lb[b(c)] }

    // ---- sampling and warps ----

    /** Bilinear sampler; coordinates in pixels where pixel i covers [i, i+1). Edges clamp. */
    class Sampler(src: PixelBuffer) {
        val w = src.width; val h = src.height
        private val pre = IntArray(src.size) { premultiply(src.pixels[it]) }
        fun premul(x: Double, y: Double): Int =
            AffineSampler.sampleBilinear(pre, w, h, (x - 0.5).coerceIn(0.0, w - 1.0), (y - 0.5).coerceIn(0.0, h - 1.0), 0)
        fun at(x: Double, y: Double): Int = unpremultiply(premul(x, y))
    }

    /**
     * Inverse-mapping warp: for each output pixel centre (x, y) [f] writes the source position
     * into out[0], out[1].
     */
    fun warp(src: PixelBuffer, ctx: RenderContext, f: (x: Double, y: Double, out: DoubleArray) -> Unit): PixelBuffer {
        val s = Sampler(src); val w = src.width
        val out = IntArray(src.size)
        ctx.forRows(src.height) { y0, y1 ->
            val t = DoubleArray(2)
            for (y in y0 until y1) for (x in 0 until w) {
                f(x + 0.5, y + 0.5, t)
                out[y * w + x] = s.at(t[0], t[1])
            }
        }
        return PixelBuffer(w, src.height, out)
    }

    // ---- blur ----

    /** Box radius for three passes approximating a gaussian of [sigma]. */
    private fun boxRadius(sigma: Float): Int = max(1, ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt())

    /**
     * Fast approximately-gaussian blur (three box passes) of a colour image, premultiplied so
     * transparent areas do not bleed. Returns a copy when [sigma] < 0.5 px.
     */
    fun blur(src: PixelBuffer, sigma: Float, ctx: RenderContext): PixelBuffer {
        val r = boxRadius(sigma)
        if (sigma < 0.5f || r < 1) return src.copy()
        val w = src.width; val h = src.height
        val pre = IntArray(src.size) { premultiply(src.pixels[it]) }
        val acc = IntArray(src.size)
        val v = FloatArray(src.size)
        // one channel at a time keeps the peak memory at about two float planes
        for (shift in intArrayOf(24, 16, 8, 0)) {
            for (i in pre.indices) v[i] = ((pre[i] ushr shift) and 0xFF).toFloat()
            blurChannel(v, w, h, r, ctx)
            for (i in pre.indices) acc[i] = acc[i] or (clampF(v[i]) shl shift)
        }
        for (i in acc.indices) {
            // premultiplied colour can never exceed alpha after rounding
            val c = acc[i]; val al = a(c)
            acc[i] = unpremultiply(pack(al, min(al, r(c)), min(al, g(c)), min(al, b(c))))
        }
        return PixelBuffer(w, h, acc)
    }

    /** Blurs a single channel in place (three box passes). */
    fun blurGray(v: FloatArray, w: Int, h: Int, sigma: Float, ctx: RenderContext) {
        val r = boxRadius(sigma)
        if (sigma < 0.5f || r < 1) return
        blurChannel(v, w, h, r, ctx)
    }

    private fun blurChannel(v: FloatArray, w: Int, h: Int, r: Int, ctx: RenderContext) {
        val tmp = FloatArray(v.size)
        repeat(3) {
            ctx.forRows(h) { y0, y1 -> val line = FloatArray(w); for (y in y0 until y1) box(v, tmp, y * w, 1, w, r, line) }
            ctx.forRows(w) { x0, x1 -> val line = FloatArray(h); for (x in x0 until x1) box(tmp, v, x, w, h, r, line) }
        }
    }

    private fun box(src: FloatArray, dst: FloatArray, start: Int, stride: Int, len: Int, r: Int, line: FloatArray) {
        for (i in 0 until len) line[i] = src[start + i * stride]
        val div = 1f / (2 * r + 1)
        var s = 0f
        for (k in -r..r) s += line[k.coerceIn(0, len - 1)]
        for (i in 0 until len) {
            dst[start + i * stride] = s * div
            s += line[min(len - 1, i + r + 1)] - line[max(0, i - r)]
        }
    }

    /** Luminance channel 0..255. */
    fun luminance(src: PixelBuffer) = FloatArray(src.size) { lum(src.pixels[it]) }
}
