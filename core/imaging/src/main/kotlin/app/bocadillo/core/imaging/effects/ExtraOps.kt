package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.effects.EffectKit.a
import app.bocadillo.core.imaging.effects.EffectKit.b
import app.bocadillo.core.imaging.effects.EffectKit.g
import app.bocadillo.core.imaging.effects.EffectKit.lum
import app.bocadillo.core.imaging.effects.EffectKit.packF
import app.bocadillo.core.imaging.effects.EffectKit.r
import app.bocadillo.core.imaging.effects.EffectKit.unit
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.model.ParamSpec
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tools of PicSay Pro 1.8.0.5 that were still missing (menus Adjust › Misc and Effect): Curves,
 * Fix Red-Eye, Add Background Color, Pop Color and Warp Brush. Own algorithms; only the tool
 * names and their place in the menus come from the original.
 */
object ExtraOps {

    // ---------------- Curves ----------------

    /** Control points x0,y0,x1,y1… (0..1). Identity = the two end points. */
    val IDENTITY_CURVE = listOf(0f, 0f, 1f, 1f)

    /**
     * Monotone cubic (Fritsch–Carlson) through the control points, sampled into a 256 LUT.
     * Points are sorted by x; at least two are needed (otherwise identity).
     */
    fun curveLut(points: FloatArray?): IntArray {
        val pts = (points ?: IDENTITY_CURVE.toFloatArray()).toList().chunked(2).filter { it.size == 2 }
            .map { it[0].coerceIn(0f, 1f) to it[1].coerceIn(0f, 1f) }.sortedBy { it.first }
            .fold(ArrayList<Pair<Float, Float>>()) { acc, p -> if (acc.isEmpty() || p.first - acc.last().first > 1e-4f) acc.add(p); acc }
        if (pts.size < 2) return IntArray(256) { it }
        val n = pts.size
        val xs = FloatArray(n) { pts[it].first }; val ys = FloatArray(n) { pts[it].second }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val al = m[i] / d[i]; val be = m[i + 1] / d[i]
            val s = al * al + be * be
            if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * al * d[i]; m[i + 1] = t * be * d[i] }
        }
        return IntArray(256) { v ->
            val x = v / 255f
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    var k = 0
                    while (k < n - 2 && x > xs[k + 1]) k++
                    val h = xs[k + 1] - xs[k]; val t = (x - xs[k]) / h
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
                }
            }
            EffectKit.clampF(y * 255f)
        }
    }

    private fun isIdentityCurve(f: FloatArray?) = f == null || f.toList() == IDENTITY_CURVE

    val Curves = Fx(
        "curves", OpCategory.ADJUST, Origin.PICSAY_PRO,
        listOf(ParamSpec.Data("curve_rgb", default = IDENTITY_CURVE), ParamSpec.Data("curve_r", default = IDENTITY_CURVE),
            ParamSpec.Data("curve_g", default = IDENTITY_CURVE), ParamSpec.Data("curve_b", default = IDENTITY_CURVE)),
        identity = { p -> listOf("curve_rgb", "curve_r", "curve_g", "curve_b").all { isIdentityCurve(p.floats(it)) } },
    ) { src, p, ctx ->
        val all = curveLut(p.floats("curve_rgb"))
        val lr = curveLut(p.floats("curve_r")); val lg = curveLut(p.floats("curve_g")); val lb = curveLut(p.floats("curve_b"))
        // master curve first, then the channel curve (as in usual curve tools)
        EffectKit.applyLuts(src, IntArray(256) { lr[all[it]] }, IntArray(256) { lg[all[it]] }, IntArray(256) { lb[all[it]] }, ctx)
    }

    // ---------------- Fix Red-Eye ----------------

    /**
     * Removes the red of the pupil inside a circle (centre + radius in fractions of u). Only
     * reddish pixels change: red is replaced by the mean of green and blue, with a soft edge.
     */
    val FixRedEye = Fx(
        "fix_red_eye", OpCategory.ADJUST, Origin.PICSAY_PRO,
        listOf(slider("radius", "radius", 0.005f, 0.2f, 0.001f, 0.04f, ParamSpec.Unit.PERCENT, 100f), center()),
    ) { src, p, ctx ->
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width; val cy = cyn * src.height
        val rad = max(1f, p.float("radius", 0.04f) * unit(src))
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d >= rad) c else {
                val gb = (g(c) + b(c)) / 2f
                val redness = ((r(c) - gb) / max(1f, r(c).toFloat())).coerceIn(0f, 1f)
                val k = EffectKit.smoothstep(0.1f, 0.35f, redness) * (1f - EffectKit.smoothstep(rad * 0.8f, rad, d))
                if (k <= 0f) c else packF(a(c), EffectKit.mix(r(c).toFloat(), gb * 0.85f, k), g(c) * (1f - 0.15f * k), b(c) * (1f - 0.15f * k))
            }
        }
    }

    // ---------------- Add Background Color ----------------

    /** Fills transparent areas with a colour (the original only offers it for transparent pictures). */
    val AddBackgroundColor = Fx(
        "add_background_color", OpCategory.ADJUST, Origin.PICSAY_PRO,
        listOf(ParamSpec.Color("color", "color", 0xFFFFFFFF.toInt())),
    ) { src, p, ctx ->
        val bg = p.color("color", 0xFFFFFFFF.toInt())
        EffectKit.map(src, ctx) { c ->
            val al = a(c) / 255f
            if (al >= 1f) c else packF(255, r(c) * al + r(bg) * (1 - al), g(c) * al + g(bg) * (1 - al), b(c) * al + b(bg) * (1 - al))
        }
    }

    fun hasTransparency(p: PixelBuffer) = p.pixels.any { (it ushr 24) < 255 }

    // ---------------- Pop Color ----------------

    /**
     * Keeps the colours close to the hue picked on the photo and turns the rest grey (or
     * inverts / blurs it, the options of the original's menu).
     */
    val PopColor = Fx(
        "pop_color", OpCategory.COLOR, Origin.PICSAY_PRO,
        listOf(
            ParamSpec.Point("pick", "pick_color", 0.5f, 0.5f),
            slider("tolerance", "tolerance", 5f, 90f, 1f, 30f, ParamSpec.Unit.DEGREES),
            ParamSpec.Choice("rest", "pop_rest", listOf("gray", "invert", "blur"), "gray"),
        ),
    ) { src, p, ctx ->
        val (px, py) = p.point("pick")
        val sx = (px * src.width).toInt().coerceIn(0, src.width - 1); val sy = (py * src.height).toInt().coerceIn(0, src.height - 1)
        // hue sampled from a small neighbourhood of the picked point
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        for (yy in max(0, sy - 2)..min(src.height - 1, sy + 2)) for (xx in max(0, sx - 2)..min(src.width - 1, sx + 2)) {
            val c = src[xx, yy]; sr += r(c); sg += g(c); sb += b(c); n++
        }
        val target = hue(sr / n, sg / n, sb / n)
        val tol = p.float("tolerance", 30f)
        val rest = when (p.string("rest", "gray")) {
            "invert" -> EffectKit.map(src, ctx) { c -> (c and (0xFF shl 24)) or (0xFFFFFF - (c and 0xFFFFFF)) }
            "blur" -> EffectKit.blur(src, unit(src) / 60f, ctx)
            else -> EffectKit.map(src, ctx) { c -> val l = lum(c); packF(a(c), l, l, l) }
        }
        EffectKit.combine(src, rest, ctx) { _, c, other ->
            val h = hue(r(c).toFloat(), g(c).toFloat(), b(c).toFloat())
            val sat = (max(r(c), max(g(c), b(c))) - min(r(c), min(g(c), b(c)))) / 255f
            val dh = abs(((h - target + 540f) % 360f) - 180f)
            val keep = (1f - EffectKit.smoothstep(tol * 0.7f, tol, dh)) * EffectKit.smoothstep(0.08f, 0.2f, sat)
            PhotoOps.mixAll(other, c, keep)
        }
    }

    private fun hue(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < 1e-3f) return 0f
        val h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        return (h + 360f) % 360f
    }

    // ---------------- Warp Brush ----------------

    /**
     * Each stroke pushes the picture from (x0,y0) to (x1,y1) inside a brush of the given radius
     * (fractions of the picture / of u): data = x0,y0,x1,y1,radius per push. Pushes are applied in
     * order, each as an inverse-mapped smooth displacement (no tearing).
     */
    val WarpBrush = Fx(
        "warp_brush", OpCategory.DISTORT, Origin.PICSAY_PRO,
        listOf(slider("size", "brush_size", 0.02f, 0.5f, 0.01f, 0.15f, ParamSpec.Unit.PERCENT, 100f), ParamSpec.Data("warps")),
        identity = { (it.floats("warps")?.size ?: 0) < 5 },
    ) { src, p, ctx ->
        val w = p.floats("warps") ?: FloatArray(0)
        var out: PixelBuffer = src
        val u = unit(src).toDouble()
        for (k in 0 until w.size / 5) {
            val x0 = w[k * 5] * src.width.toDouble(); val y0 = w[k * 5 + 1] * src.height.toDouble()
            val dx = (w[k * 5 + 2] - w[k * 5]) * src.width.toDouble(); val dy = (w[k * 5 + 3] - w[k * 5 + 1]) * src.height.toDouble()
            val rad = max(2.0, w[k * 5 + 4] * u)
            if (dx * dx + dy * dy < 0.25) continue
            val cur = out
            out = EffectKit.warp(cur, ctx) { x, y, o ->
                // the brush moved from p0 to p1: pixels near p1 come from where they were before the push
                val ex = x - (x0 + dx); val ey = y - (y0 + dy)
                val f = exp(-(ex * ex + ey * ey) / (rad * rad / 2.0))
                o[0] = x - dx * f; o[1] = y - dy * f
            }
        }
        out
    }

    val all: List<Fx> = listOf(Curves, FixRedEye, AddBackgroundColor, PopColor, WarpBrush)
}

