package app.bocadillo.core.imaging.paint

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How a stroke is applied. */
enum class StrokeMode(val code: Int) {
    /** Normal paint (Brush). */
    PAINT(0),
    /** Translucent multiply, like a highlighter (Marker). */
    MARKER(1),
    /** Removes pixels to transparency (Eraser / "Make Transparent"); in masks: subtracts coverage. */
    ERASE(2);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: PAINT }
}

/**
 * A free-hand stroke. Points are normalized to the picture (x,y in 0..1) with pen pressure
 * (0..1, 1 for fingers). [size] is the brush diameter in u = min(width, height); [hardness] 0 =
 * soft edge, 1 = hard edge. Resolution independent, so preview and export match.
 */
data class Stroke(
    val points: FloatArray,
    val size: Float,
    val hardness: Float = 0.8f,
    val opacity: Float = 1f,
    val color: Int = 0xFF000000.toInt(),
    val mode: StrokeMode = StrokeMode.PAINT,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    /** Square tip (Cut Out brushes); round otherwise. */
    val square: Boolean = false,
) {
    init { require(points.size % 3 == 0) { "points must be x,y,p triplets" } }
    val pointCount: Int get() = points.size / 3

    override fun equals(other: Any?) = other is Stroke && points.contentEquals(other.points) && size == other.size &&
        hardness == other.hardness && opacity == other.opacity && color == other.color && mode == other.mode &&
        pressureSize == other.pressureSize && pressureOpacity == other.pressureOpacity && square == other.square

    override fun hashCode() = points.contentHashCode() * 31 + size.hashCode() * 7 + color
}

/**
 * Encodes strokes into a flat float list so they fit in [app.bocadillo.core.model.Params]:
 * [count, then per stroke: n, size, hardness, opacity, colorHi, colorLo, mode, flags, x,y,p * n].
 * The colour is split in two 16-bit halves so it survives float precision.
 */
object StrokeCodec {
    private const val HEADER = 8

    fun encode(strokes: List<Stroke>): FloatArray {
        val total = 1 + strokes.sumOf { HEADER + it.points.size }
        val out = FloatArray(total)
        out[0] = strokes.size.toFloat()
        var i = 1
        for (s in strokes) {
            out[i] = s.pointCount.toFloat(); out[i + 1] = s.size; out[i + 2] = s.hardness; out[i + 3] = s.opacity
            out[i + 4] = ((s.color ushr 16) and 0xFFFF).toFloat(); out[i + 5] = (s.color and 0xFFFF).toFloat()
            out[i + 6] = s.mode.code.toFloat()
            out[i + 7] = ((if (s.pressureSize) 1 else 0) or (if (s.pressureOpacity) 2 else 0) or (if (s.square) 4 else 0)).toFloat()
            System.arraycopy(s.points, 0, out, i + HEADER, s.points.size)
            i += HEADER + s.points.size
        }
        return out
    }

    fun decode(data: FloatArray?): List<Stroke> {
        if (data == null || data.isEmpty()) return emptyList()
        val count = data[0].toInt()
        val out = ArrayList<Stroke>(count)
        var i = 1
        repeat(count) {
            require(i + HEADER <= data.size) { "Corrupt stroke data" }
            val n = data[i].toInt()
            val color = (data[i + 4].toInt() shl 16) or data[i + 5].toInt()
            val flags = data[i + 7].toInt()
            require(i + HEADER + n * 3 <= data.size) { "Corrupt stroke points" }
            out += Stroke(
                points = data.copyOfRange(i + HEADER, i + HEADER + n * 3),
                size = data[i + 1], hardness = data[i + 2], opacity = data[i + 3], color = color,
                mode = StrokeMode.of(data[i + 6].toInt()), pressureSize = flags and 1 != 0, pressureOpacity = flags and 2 != 0,
                square = flags and 4 != 0,
            )
            i += HEADER + n * 3
        }
        return out
    }
}

/** Rasterizes strokes into coverage (0..1 per pixel). */
object StrokeRasterizer {

    /**
     * Coverage of one stroke on a w×h grid. Dabs are combined with max (not added), so a stroke
     * crossing itself does not get darker — like a real marker pass.
     * Returns null if the stroke is entirely outside.
     */
    fun coverage(s: Stroke, w: Int, h: Int): Coverage? {
        val u = min(w, h).toFloat()
        val maxR = s.size * u / 2f
        // dab spacing: ~12% of the radius (≥ 1 px) keeps edges smooth without wasting work
        val pts = smooth(s.points, w, h, spacing = max(1f, maxR * 0.12f))
        if (pts.isEmpty()) return null
        // bounding box
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (k in 0 until pts.size / 3) {
            x0 = min(x0, pts[3 * k]); x1 = max(x1, pts[3 * k]); y0 = min(y0, pts[3 * k + 1]); y1 = max(y1, pts[3 * k + 1])
        }
        val bx0 = max(0, floor(x0 - maxR - 1).toInt()); val by0 = max(0, floor(y0 - maxR - 1).toInt())
        val bx1 = min(w, ceil(x1 + maxR + 1).toInt()); val by1 = min(h, ceil(y1 + maxR + 1).toInt())
        if (bx1 <= bx0 || by1 <= by0) return null
        val bw = bx1 - bx0; val bh = by1 - by0
        val cov = FloatArray(bw * bh)
        val hard = s.hardness.coerceIn(0f, 1f)
        for (k in 0 until pts.size / 3) {
            val p = pts[3 * k + 2].coerceIn(0.05f, 1f)
            val r = max(0.5f, maxR * (if (s.pressureSize) p else 1f))
            val a = if (s.pressureOpacity) p else 1f
            dab(cov, bw, bh, pts[3 * k] - bx0, pts[3 * k + 1] - by0, r, hard, a, s.square)
        }
        return Coverage(bx0, by0, bw, bh, cov)
    }

    class Coverage(val x: Int, val y: Int, val w: Int, val h: Int, val values: FloatArray)

    /** Round dab: full inside r*hardness, smooth falloff to 0 at r. */
    private fun dab(cov: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, hardness: Float, alpha: Float, square: Boolean = false) {
        val inner = r * hardness
        val xa = max(0, floor(cx - r).toInt()); val xb = min(w - 1, ceil(cx + r).toInt())
        val ya = max(0, floor(cy - r).toInt()); val yb = min(h - 1, ceil(cy + r).toInt())
        val r2 = (r + 0.5f) * (r + 0.5f)
        for (y in ya..yb) {
            val dy = y + 0.5f - cy
            for (x in xa..xb) {
                val dx = x + 0.5f - cx
                val d: Float
                if (square) {
                    d = max(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    if (d > r + 0.5f) continue
                } else {
                    val d2 = dx * dx + dy * dy
                    if (d2 > r2) continue
                    d = sqrt(d2)
                }
                var v = when {
                    d <= inner - 0.5f -> 1f
                    r - inner < 1f -> (r + 0.5f - d).coerceIn(0f, 1f) // hard brush: 1 px antialiasing
                    else -> { val t = ((d - inner) / (r - inner)).coerceIn(0f, 1f); 1f - t * t * (3f - 2f * t) }
                }
                v *= alpha
                val i = y * w + x
                if (v > cov[i]) cov[i] = v
            }
        }
    }

    /**
     * Converts normalized points to pixels and resamples them along a Catmull-Rom spline with
     * spacing of ~1/8 of the brush diameter (smooth curves from coarse touch input).
     */
    fun smooth(points: FloatArray, w: Int, h: Int, spacing: Float = 1.2f): FloatArray {
        val n = points.size / 3
        if (n == 0) return FloatArray(0)
        val px = FloatArray(n * 3) { i -> when (i % 3) { 0 -> points[i] * w; 1 -> points[i] * h; else -> points[i] } }
        if (n == 1) return px
        val out = ArrayList<Float>(n * 6)
        val step = spacing
        fun p(i: Int, c: Int) = px[3 * i.coerceIn(0, n - 1) + c]
        for (i in 0 until n - 1) {
            val seg = hypot(p(i + 1, 0) - p(i, 0), p(i + 1, 1) - p(i, 1))
            val steps = max(1, ceil(seg / step).toInt())
            for (sIdx in 0 until steps) {
                val t = sIdx.toFloat() / steps
                val t2 = t * t; val t3 = t2 * t
                for (c in 0..1) {
                    val p0 = p(i - 1, c); val p1 = p(i, c); val p2 = p(i + 1, c); val p3 = p(i + 2, c)
                    out += 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
                }
                out += p(i, 2) + (p(i + 1, 2) - p(i, 2)) * t
            }
        }
        out += p(n - 1, 0); out += p(n - 1, 1); out += p(n - 1, 2)
        return out.toFloatArray()
    }

    /**
     * Builds a mask (0..1) from mask strokes: PAINT/MARKER strokes add coverage, ERASE strokes
     * remove it, in order. [base] is the starting value (0 = nothing, 1 = everything).
     */
    fun maskCoverage(strokes: List<Stroke>, w: Int, h: Int, base: Float, ctx: RenderContext? = null): FloatArray {
        val m = FloatArray(w * h) { base }
        for (s in strokes) {
            ctx?.checkCancelled()
            val c = coverage(s, w, h) ?: continue
            val add = s.mode != StrokeMode.ERASE
            for (yy in 0 until c.h) {
                val row = (c.y + yy) * w + c.x
                for (xx in 0 until c.w) {
                    val v = c.values[yy * c.w + xx] * s.opacity
                    if (v <= 0f) continue
                    val i = row + xx
                    m[i] = if (add) m[i] + (1f - m[i]) * v else m[i] * (1f - v)
                }
            }
        }
        return m
    }

    /** Separable box blur ×3 (≈ gaussian) used to feather masks. radius in pixels. */
    fun feather(m: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return m
        var a = m; var b = FloatArray(m.size)
        repeat(3) {
            for (y in 0 until h) boxLine(a, b, y * w, 1, w, radius)
            for (x in 0 until w) boxLine(b, a, x, w, h, radius)
        }
        return a
    }

    private fun boxLine(src: FloatArray, dst: FloatArray, start: Int, stride: Int, len: Int, r: Int) {
        var sum = 0f
        val inv = 1f / (2 * r + 1)
        for (k in -r..r) sum += src[start + k.coerceIn(0, len - 1) * stride]
        for (i in 0 until len) {
            dst[start + i * stride] = sum * inv
            sum += src[start + (i + r + 1).coerceAtMost(len - 1) * stride] - src[start + (i - r).coerceAtLeast(0) * stride]
        }
    }
}

/** Composites strokes onto pixels (Brush, Marker, Eraser). */
object StrokePainter {
    fun paint(src: PixelBuffer, strokes: List<Stroke>, ctx: RenderContext): PixelBuffer {
        val out = src.copy()
        for (s in strokes) {
            ctx.checkCancelled()
            paintInPlace(out.pixels, src.width, src.height, s)
        }
        return out
    }

    /**
     * Paints one stroke directly into [px] (w×h) and returns the touched area (x, y, w, h) or
     * null. Used by the final render above and by the editor's live preview, so the stroke in
     * progress and the applied stroke are computed by the very same code.
     */
    fun paintInPlace(px: IntArray, w: Int, h: Int, s: Stroke): IntArray? {
        val c = StrokeRasterizer.coverage(s, w, h) ?: return null
        val ca = ((s.color ushr 24) and 0xFF) / 255f
        val cr = (s.color shr 16) and 0xFF; val cg = (s.color shr 8) and 0xFF; val cb = s.color and 0xFF
        for (yy in 0 until c.h) {
            val row = (c.y + yy) * w + c.x
            for (xx in 0 until c.w) {
                val cov = c.values[yy * c.w + xx]
                if (cov <= 0f) continue
                val i = row + xx
                px[i] = when (s.mode) {
                    StrokeMode.ERASE -> eraseOver(px[i], cov * s.opacity)
                    StrokeMode.PAINT -> over(px[i], cr, cg, cb, cov * s.opacity * ca)
                    StrokeMode.MARKER -> multiply(px[i], cr, cg, cb, cov * s.opacity * ca)
                }
            }
        }
        return intArrayOf(c.x, c.y, c.w, c.h)
    }

    private fun over(d: Int, r: Int, g: Int, b: Int, a: Float): Int {
        if (a <= 0f) return d
        val da = ((d ushr 24) and 0xFF) / 255f
        val oa = a + da * (1 - a)
        if (oa <= 0f) return 0
        fun ch(s: Int, dc: Int) = ((s * a + dc * da * (1 - a)) / oa).toInt().coerceIn(0, 255)
        return ((oa * 255 + 0.5f).toInt() shl 24) or (ch(r, (d shr 16) and 0xFF) shl 16) or (ch(g, (d shr 8) and 0xFF) shl 8) or ch(b, d and 0xFF)
    }

    private fun multiply(d: Int, r: Int, g: Int, b: Int, a: Float): Int {
        fun ch(s: Int, dc: Int) = (dc + (dc * s / 255f - dc) * a).toInt().coerceIn(0, 255)
        return (d and -0x1000000) or (ch(r, (d shr 16) and 0xFF) shl 16) or (ch(g, (d shr 8) and 0xFF) shl 8) or ch(b, d and 0xFF)
    }

    private fun eraseOver(d: Int, a: Float): Int {
        val na = (((d ushr 24) and 0xFF) * (1 - a.coerceIn(0f, 1f)) + 0.5f).toInt()
        return (na shl 24) or (d and 0xFFFFFF)
    }
}
