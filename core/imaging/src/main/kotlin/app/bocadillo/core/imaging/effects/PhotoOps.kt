package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.effects.EffectKit.a
import app.bocadillo.core.imaging.effects.EffectKit.b
import app.bocadillo.core.imaging.effects.EffectKit.g
import app.bocadillo.core.imaging.effects.EffectKit.lum
import app.bocadillo.core.imaging.effects.EffectKit.mix
import app.bocadillo.core.imaging.effects.EffectKit.packF
import app.bocadillo.core.imaging.effects.EffectKit.r
import app.bocadillo.core.imaging.effects.EffectKit.unit
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Photo effects (Effect › Photo effects) and the Adjust extras of Pro (Boost, Sharpen, Smooth,
 * Blur). Own formulas; names, ranges and defaults from the original where verified.
 */
object PhotoOps {

    /** Normalized distance to the centre: 0 at the centre, 1 at the corners. */
    internal fun cornerDistance(x: Int, y: Int, w: Int, h: Int): Float {
        val dx = (x + 0.5f) / w * 2f - 1f; val dy = (y + 0.5f) / h * 2f - 1f
        return sqrt((dx * dx + dy * dy) / 2f)
    }

    private fun amount(def: Float = 1f) = percent("amount", "amount", def)
    private fun Params.amt(def: Float = 1f) = float("amount", def)

    /** Mix of [src] with [fx] by amount, a common shape for the photo looks. */
    private inline fun look(src: PixelBuffer, k: Float, ctx: RenderContext, crossinline f: (x: Int, y: Int, c: Int) -> Int) =
        EffectKit.mapXY(src, ctx) { x, y, c -> EffectKit.mixColor(c, f(x, y, c), k) }

    val Lomo = Fx("lomo", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount(), percent("vignette", "vignette", 0.6f)),
        identity = { it.amt() == 0f && it.float("vignette", 0.6f) == 0f }) { src, p, ctx ->
        val k = p.amt(); val v = p.float("vignette", 0.6f)
        val curve = EffectKit.lut { EffectKit.sCurve(it, 0.6f * k) }
        val w = src.width; val h = src.height
        EffectKit.mapXY(src, ctx) { x, y, c0 ->
            val c = EffectKit.saturate(packF(a(c0), curve[r(c0)].toFloat(), curve[g(c0)].toFloat(), curve[b(c0)].toFloat()), 1f + 0.45f * k)
            val m = 1f - v * 0.85f * EffectKit.smoothstep(0.3f, 1.05f, cornerDistance(x, y, w, h))
            packF(a(c), r(c) * m, g(c) * m, b(c) * m)
        }
    }

    val CrossProcess = Fx("cross_process", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount()), identity = { it.amt() == 0f }) { src, p, ctx ->
        val k = p.amt()
        val lr = EffectKit.lut { mix(it, EffectKit.sCurve(it, 0.9f), k) }
        val lg = EffectKit.lut { mix(it, EffectKit.sCurve(it, 0.45f) * 0.97f + 0.02f, k) }
        val lb = EffectKit.lut { mix(it, 0.22f + it * 0.56f, k) }
        EffectKit.applyLuts(src, lr, lg, lb, ctx)
    }

    val Gritty = Fx("gritty", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount(0.7f)), identity = { it.amt(0.7f) == 0f }) { src, p, ctx ->
        val k = p.amt(0.7f)
        val w = src.width; val h = src.height
        val l = EffectKit.luminance(src); val base = l.copyOf()
        EffectKit.blurGray(base, w, h, unit(src) / 90f, ctx)
        val curve = EffectKit.lut { EffectKit.sCurve(it, 0.5f * k) }
        look(src, 1f, ctx) { x, y, c ->
            val i = y * w + x
            val d = (l[i] - base[i]) * 2.2f * k
            val s = EffectKit.saturate(c, 1f - 0.55f * k)
            packF(a(c), curve[EffectKit.clampF(r(s) + d)].toFloat(), curve[EffectKit.clampF(g(s) + d)].toFloat(), curve[EffectKit.clampF(b(s) + d)].toFloat())
        }
    }

    val FadingColors = Fx("fading_colors", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount(0.7f)), identity = { it.amt(0.7f) == 0f }) { src, p, ctx ->
        val k = p.amt(0.7f)
        look(src, k, ctx) { _, _, c ->
            val s = EffectKit.saturate(c, 0.45f)
            fun f(v: Int, lift: Float) = 255f * (0.10f + lift) + v * (0.78f - lift)
            packF(a(c), f(r(s), 0.02f), f(g(s), 0f), f(b(s), -0.03f))
        }
    }

    /** Film grain: procedural monochrome grain (own noise, not the original `grain.jpg`). */
    val FilmGrain = Fx("film_grain", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount(0.5f)), identity = { it.amt(0.5f) == 0f }) { src, p, ctx ->
        val k = p.amt(0.5f)
        val cell = max(1f, unit(src) / 1100f)
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val n = EffectKit.valueNoise(x / cell, y / cell, 11) * 0.7f + EffectKit.valueNoise(x / cell * 0.5f, y / cell * 0.5f, 23) * 0.3f
            val l = lum(c) / 255f
            val d = n * 70f * k * (0.35f + 2.6f * l * (1f - l))
            packF(a(c), r(c) + d, g(c) + d, b(c) + d)
        }
    }

    val RedScale = Fx("red_scale", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount()), identity = { it.amt() == 0f }) { src, p, ctx ->
        val stops = intArrayOf(0xFF0A0000.toInt(), 0xFF4A0C02.toInt(), 0xFFAE3606.toInt(), 0xFFEE8A2A.toInt(), 0xFFFFE3A8.toInt())
        val pal = IntArray(256) { EffectKit.gradient(stops, it / 255f) }
        look(src, p.amt(), ctx) { _, _, c -> (c and (0xFF shl 24)) or (pal[EffectKit.clampF(lum(c))] and 0xFFFFFF) }
    }

    /** Faux HDR: compresses large-scale tones and boosts local detail; default 0.6 [V]. */
    val FauxHdr = Fx("faux_hdr", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount(0.6f)), identity = { it.amt(0.6f) == 0f }) { src, p, ctx ->
        val k = p.amt(0.6f)
        val w = src.width
        val l = EffectKit.luminance(src); val base = l.copyOf()
        EffectKit.blurGray(base, w, src.height, unit(src) / 22f, ctx)
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val i = y * w + x
            val nb = 128f + (base[i] - 128f) * (1f - 0.65f * k)
            val nl = nb + (l[i] - base[i]) * (1f + 1.6f * k)
            val d = nl - l[i]
            EffectKit.saturate(packF(a(c), r(c) + d, g(c) + d, b(c) + d), 1f + 0.3f * k)
        }
    }

    /** Tilt-shift (`blrl`): blur outside a band through the centre at an angle. */
    val TiltShift = Fx("tilt_shift", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(
        slider("blur", "blur", 0f, 40f, 0.1f, 15f), slider("angle", "angle", -90f, 90f, 1f, 0f, ParamSpec.Unit.DEGREES),
        percent("size", "band_size", 0.12f, 0f, 0.6f), percent("falloff", "falloff", 0.25f, 0.01f, 1f), center(),
    ), identity = { it.float("blur", 15f) == 0f }) { src, p, ctx ->
        val u = unit(src)
        val blurred = EffectKit.blur(src, p.float("blur", 15f) * u / 1000f * 1.5f, ctx)
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width; val cy = cyn * src.height
        val ang = p.float("angle", 0f) * PI.toFloat() / 180f
        val nx = -sin(ang); val ny = cos(ang)
        val band = p.float("size", 0.12f) * u; val fall = max(1f, p.float("falloff", 0.25f) * u)
        EffectKit.combine(src, blurred, ctx) { i, c, bc ->
            val x = i % src.width + 0.5f; val y = i / src.width + 0.5f
            val d = abs((x - cx) * nx + (y - cy) * ny)
            mixAll(c, bc, EffectKit.smoothstep(band, band + fall, d))
        }
    }

    val SoftGlow = Fx("soft_glow", OpCategory.PHOTO, Origin.PICSAY_PRO, listOf(amount(0.5f), percent("radius", "radius", 0.5f)),
        identity = { it.amt(0.5f) == 0f }) { src, p, ctx ->
        val k = p.amt(0.5f)
        val glow = EffectKit.blur(src, (0.004f + 0.03f * p.float("radius", 0.5f)) * unit(src), ctx)
        EffectKit.combine(src, glow, ctx) { _, c, gc -> EffectKit.blend(c, gc, k, EffectKit::screen) }
    }

    // ---- Adjust extras (Pro "Adjust" menu) ----

    /** Blur 0…40 step 0.1 [V]. 40 ≈ a gaussian of 6 % of the short side. */
    val Blur = Fx("blur", OpCategory.ADJUST, Origin.PICSAY_PRO, listOf(slider("amount", "amount", 0f, 40f, 0.1f, 5f)),
        identity = { it.float("amount", 5f) == 0f }) { src, p, ctx ->
        EffectKit.blur(src, p.float("amount", 5f) * unit(src) / 1000f * 1.5f, ctx)
    }

    /** Sharpen (unsharp mask): amount up to 2, default 1.5 [V]. */
    val Sharpen = Fx("sharpen", OpCategory.ADJUST, Origin.PICSAY_PRO, listOf(slider("amount", "amount", 0f, 2f, 0.01f, 1.5f, ParamSpec.Unit.PERCENT, 100f)),
        identity = { it.float("amount", 1.5f) == 0f }) { src, p, ctx ->
        val k = p.float("amount", 1.5f)
        val blurred = EffectKit.blur(src, max(0.8f, unit(src) / 1400f), ctx)
        EffectKit.combine(src, blurred, ctx) { _, c, bc ->
            packF(a(c), r(c) + (r(c) - r(bc)) * k, g(c) + (g(c) - g(bc)) * k, b(c) + (b(c) - b(bc)) * k)
        }
    }

    /** Smooth 0…10 step 0.1 [V]: edge-preserving smoothing (skin, noise). */
    val Smooth = Fx("smooth", OpCategory.ADJUST, Origin.PICSAY_PRO, listOf(slider("amount", "amount", 0f, 10f, 0.1f, 3f)),
        identity = { it.float("amount", 3f) == 0f }) { src, p, ctx ->
        val k = p.float("amount", 3f)
        val blurred = EffectKit.blur(src, max(0.6f, k * unit(src) / 1000f), ctx)
        val strength = min(1f, 0.35f + k / 8f)
        EffectKit.combine(src, blurred, ctx) { _, c, bc ->
            val diff = (abs(r(c) - r(bc)) + abs(g(c) - g(bc)) + abs(b(c) - b(bc))) / 3f
            val wgt = exp(-(diff / (10f + 2.5f * k)).let { it * it }) * strength
            mixAll(c, bc, wgt)
        }
    }

    /** Boost [P]: auto levels from the 0.5 %/99.5 % luminance percentiles plus vibrance. */
    val Boost = Fx("boost", OpCategory.ADJUST, Origin.PICSAY_PRO, listOf(amount(0.5f)), identity = { it.amt(0.5f) == 0f }) { src, p, ctx ->
        val k = p.amt(0.5f)
        val hist = IntArray(256)
        for (c in src.pixels) if (a(c) > 0) hist[EffectKit.clampF(lum(c))]++
        val total = hist.sum().coerceAtLeast(1)
        var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= total * 0.005) { lo = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc >= total * 0.005) { hi = i; break } }
        if (hi - lo < 16) { lo = 0; hi = 255 }
        val lut = EffectKit.lut { v -> EffectKit.sCurve(((v * 255f - lo) / (hi - lo)).coerceIn(0f, 1f), 0.15f) }
        EffectKit.map(src, ctx) { c ->
            val lv = packF(a(c), lut[r(c)].toFloat(), lut[g(c)].toFloat(), lut[b(c)].toFloat())
            val (_, s) = hueSat(lv)
            EffectKit.mixColor(c, EffectKit.saturate(lv, 1f + 0.6f * (1f - s)), k)
        }
    }

    private fun hueSat(c: Int): Pair<Float, Float> {
        val mx = max(r(c), max(g(c), b(c))); val mn = min(r(c), min(g(c), b(c)))
        return 0f to (if (mx == 0) 0f else (mx - mn).toFloat() / mx)
    }

    /** Mixes all four channels (used where the blurred copy may carry different alpha). */
    internal fun mixAll(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        return packF(EffectKit.clampF(mix(a(c0).toFloat(), a(c1).toFloat(), t)), mix(r(c0).toFloat(), r(c1).toFloat(), t), mix(g(c0).toFloat(), g(c1).toFloat(), t), mix(b(c0).toFloat(), b(c1).toFloat(), t))
    }

    val photo = listOf(Lomo, CrossProcess, Gritty, FadingColors, FilmGrain, RedScale, FauxHdr, TiltShift, SoftGlow)
    val adjust = listOf(Boost, Sharpen, Smooth, Blur)
    val all = photo + adjust
}
