package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Colour, artistic and misc effects (menu Effect › Color / Artistic / Misc). Own formulas. */
object ColorArtOps {

    // ---------------- Color ----------------

    /** Grayscale with a colour-filter angle 0…360 [V] (like a coloured lens on B&W film). */
    val Grayscale = Fx(
        "grayscale", OpCategory.COLOR, Origin.PICSAY_PRO,
        listOf(slider("angle", "filter_hue", 0f, 360f, 1f, 0f, ParamSpec.Unit.DEGREES), percent("strength", "filter_strength", 0.5f)),
    ) { src, p, ctx ->
        val f = EffectKit.hueColor(p.float("angle", 0f))
        val s = p.float("strength", 0.5f) * 2f
        var wr = 0.2126f * (1f - s / 2f + s * r(f) / 255f)
        var wg = 0.7152f * (1f - s / 2f + s * g(f) / 255f)
        var wb = 0.0722f * (1f - s / 2f + s * b(f) / 255f)
        val sum = wr + wg + wb; wr /= sum; wg /= sum; wb /= sum
        EffectKit.map(src, ctx) { c -> val v = wr * r(c) + wg * g(c) + wb * b(c); packF(a(c), v, v, v) }
    }

    /** Black & white threshold 0…255 [V]. */
    val BlackWhite = Fx(
        "black_white", OpCategory.COLOR, Origin.PICSAY_PRO,
        listOf(slider("threshold", "threshold", 0f, 255f, 1f, 128f)),
    ) { src, p, ctx ->
        val t = p.float("threshold", 128f)
        EffectKit.map(src, ctx) { c -> if (lum(c) >= t) (c and (0xFF shl 24)) or 0xFFFFFF else c and (0xFF shl 24) }
    }

    /** Duotone: shadows → colorDark, highlights → colorLight, with contrast [V fields]. */
    val Duotone = Fx(
        "duotone", OpCategory.COLOR, Origin.PICSAY_PRO,
        listOf(
            ParamSpec.Color("colorDark", "color_dark", 0xFF1F2A60.toInt()),
            ParamSpec.Color("colorLight", "color_light", 0xFFF6C453.toInt()),
            percent("contrast", "contrast", 0f, -1f, 1f),
        ),
    ) { src, p, ctx ->
        val dark = p.color("colorDark", 0xFF1F2A60.toInt()); val light = p.color("colorLight", 0xFFF6C453.toInt())
        val k = p.float("contrast", 0f) * 0.9f
        val curve = EffectKit.lut { x -> EffectKit.sCurve(x, if (k >= 0) k * 1.5f else 0f).let { if (k < 0) 0.5f + (it - 0.5f) * (1 + k) else it } }
        val pal = IntArray(256) { EffectKit.mixColor(dark or (0xFF shl 24), light or (0xFF shl 24), curve[it] / 255f) }
        EffectKit.map(src, ctx) { c -> (c and (0xFF shl 24)) or (pal[EffectKit.clampF(lum(c))] and 0xFFFFFF) }
    }

    /** Blend with colour (`mix `): amount 50 % [V], blend mode [V field]. "clear" makes it transparent. */
    val BlendColor = Fx(
        "blend_color", OpCategory.COLOR, Origin.PICSAY_PRO,
        listOf(
            ParamSpec.Color("color", "color", 0xFFFF8A00.toInt()),
            percent("amount", "amount", 0.5f),
            ParamSpec.Choice("mode", "blend_mode", listOf("normal", "multiply", "screen", "overlay", "soft_light", "color", "clear"), "normal"),
        ),
        identity = { it.float("amount", 0.5f) == 0f },
    ) { src, p, ctx ->
        val col = p.color("color", 0xFFFF8A00.toInt()) or (0xFF shl 24)
        val amt = p.float("amount", 0.5f)
        when (p.string("mode", "normal")) {
            "multiply" -> EffectKit.map(src, ctx) { c -> EffectKit.blend(c, col, amt, EffectKit::multiply) }
            "screen" -> EffectKit.map(src, ctx) { c -> EffectKit.blend(c, col, amt, EffectKit::screen) }
            "overlay" -> EffectKit.map(src, ctx) { c -> EffectKit.blend(c, col, amt, EffectKit::overlay) }
            "soft_light" -> EffectKit.map(src, ctx) { c -> EffectKit.blend(c, col, amt, EffectKit::softLight) }
            "color" -> EffectKit.map(src, ctx) { c -> EffectKit.mixColor(c, EffectKit.colorBlend(c, col), amt) }
            "clear" -> EffectKit.map(src, ctx) { c -> (EffectKit.clampF(a(c) * (1f - amt)) shl 24) or (c and 0xFFFFFF) }
            else -> EffectKit.map(src, ctx) { c -> EffectKit.mixColor(c, col, amt) }
        }
    }

    /** Rainbow: hue gradient overlaid on the picture; amount default 1 [V]. Direction [P]. */
    val Rainbow = Fx(
        "rainbow", OpCategory.COLOR, Origin.PICSAY_BOTH,
        listOf(percent("amount", "amount", 1f), slider("angle", "angle", -180f, 180f, 1f, 0f, ParamSpec.Unit.DEGREES)),
        identity = { it.float("amount", 1f) == 0f },
    ) { src, p, ctx ->
        val amt = p.float("amount", 1f) * 0.85f
        val ang = p.float("angle", 0f) * PI / 180
        val ux = cos(ang); val uy = sin(ang)
        val w = src.width.toDouble(); val h = src.height.toDouble()
        val ext = abs(ux) * w + abs(uy) * h
        val pal = IntArray(512) { EffectKit.hueColor(it / 512f * 300f) } // red → violet
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val t = (((x + 0.5 - w / 2) * ux + (y + 0.5 - h / 2) * uy) / ext + 0.5).coerceIn(0.0, 0.999)
            EffectKit.blend(c, pal[(t * 512).toInt()], amt, EffectKit::overlay)
        }
    }

    /** Adjust RGB (`rgbm`: fields r, g, b [I]): per-channel gain 0…2. */
    val AdjustRgb = Fx(
        "adjust_rgb", OpCategory.ADJUST, Origin.PICSAY_PRO,
        listOf(percent("r", "red", 1f, 0f, 2f), percent("g", "green", 1f, 0f, 2f), percent("b", "blue", 1f, 0f, 2f)),
        identity = { it.float("r", 1f) == 1f && it.float("g", 1f) == 1f && it.float("b", 1f) == 1f },
    ) { src, p, ctx ->
        fun l(k: Float) = EffectKit.lut { it * k }
        EffectKit.applyLuts(src, l(p.float("r", 1f)), l(p.float("g", 1f)), l(p.float("b", 1f)), ctx)
    }

    // ---------------- Artistic ----------------

    /** Posterize 2…20 levels, default 4 [V]. */
    val Posterize = Fx(
        "posterize", OpCategory.ARTISTIC, Origin.PICSAY_PRO,
        listOf(slider("levels", "levels", 2f, 20f, 1f, 4f)),
    ) { src, p, ctx ->
        val n = p.int("levels", 4).coerceIn(2, 20)
        val t = IntArray(256) { v -> ((v * n / 256).coerceAtMost(n - 1) * 255f / (n - 1)).roundToInt() }
        EffectKit.applyLuts(src, t, t, t, ctx)
    }

    /** Comic print: halftone ink dots on a rotated grid over flattened colours. Dot 4…30, default 7 [V]. */
    val ComicPrint = Fx(
        "comic_print", OpCategory.ARTISTIC, Origin.PICSAY_PRO,
        listOf(slider("dot", "dot_size", 4f, 30f, 1f, 7f)),
    ) { src, p, ctx ->
        val cell = max(3.0, p.float("dot", 7f) * unit(src) / 700.0)
        val soft = EffectKit.blur(src, (cell / 3).toFloat(), ctx)
        val s = EffectKit.Sampler(soft)
        val ca = cos(PI / 4); val sa = sin(PI / 4)
        val ink = 0xFF14141E.toInt()
        val levels = IntArray(256) { v -> min(255, ((v / 86) * 110 + 60)) }
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val px = x + 0.5; val py = y + 0.5
            val u = px * ca + py * sa; val v = -px * sa + py * ca
            val cu = (floor(u / cell) + 0.5) * cell; val cv = (floor(v / cell) + 0.5) * cell
            val sx = cu * ca - cv * sa; val sy = cu * sa + cv * ca
            val l = lum(s.at(sx, sy)) / 255.0
            val rad = cell * 0.62 * sqrt(max(0.0, 1 - l))
            val d = sqrt((u - cu) * (u - cu) + (v - cv) * (v - cv))
            val cov = (rad - d + 0.5).coerceIn(0.0, 1.0).toFloat()
            val sc = soft.pixels[y * src.width + x]
            val base = EffectKit.saturate(packF(a(c), levels[r(sc)].toFloat(), levels[g(sc)].toFloat(), levels[b(sc)].toFloat()), 1.3f)
            EffectKit.mixColor(base, ink, cov)
        }
    }

    /** Pop art: luminance bands mapped to a bold palette; contrast default 0.5 [V]. */
    val PopArt = Fx(
        "pop_art", OpCategory.ARTISTIC, Origin.PICSAY_PRO,
        listOf(percent("contrast", "contrast", 0.5f)),
    ) { src, p, ctx ->
        val k = p.float("contrast", 0.5f)
        val pal = intArrayOf(0xFF1A1A6E.toInt(), 0xFFE5007E.toInt(), 0xFF00B7EB.toInt(), 0xFFFFE600.toInt())
        val curve = EffectKit.lut { EffectKit.sCurve(it, k * 1.5f) }
        val soft = EffectKit.blur(src, max(0.6f, unit(src) / 1200f), ctx)
        EffectKit.combine(src, soft, ctx) { _, c, sc ->
            val t = curve[EffectKit.clampF(lum(sc))]
            (c and (0xFF shl 24)) or (pal[min(3, t * 4 / 256)] and 0xFFFFFF)
        }
    }

    /** Pencil sketch: colour-dodge of grey with its blurred negative; saturation field [V]. */
    val PencilSketch = Fx(
        "pencil_sketch", OpCategory.ARTISTIC, Origin.PICSAY_PRO,
        listOf(percent("saturation", "saturation", 0f)),
    ) { src, p, ctx ->
        val sat = p.float("saturation", 0f)
        val w = src.width; val h = src.height
        val gray = EffectKit.luminance(src)
        val inv = FloatArray(gray.size) { 255f - gray[it] }
        EffectKit.blurGray(inv, w, h, max(1f, unit(src) / 100f), ctx)
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val i = y * w + x
            val g0 = gray[i]
            var v = min(255f, g0 * 255f / max(1f, 255f - inv[i]))
            v = 255f - (255f - v) * 2.2f // darker strokes
            v *= 0.94f + 0.06f * EffectKit.valueNoise(x * 0.9f + y * 0.35f, y * 0.12f, 7) // paper grain
            if (sat <= 0f) packF(a(c), v, v, v) else {
                val l = max(1f, lum(c))
                val tint = packF(a(c), v * r(c) / l, v * g(c) / l, v * b(c) / l)
                EffectKit.mixColor(packF(a(c), v, v, v), tint, sat)
            }
        }
    }

    /** Neon: glowing coloured edges on black; colour and contrast fields [V]. */
    val Neon = Fx(
        "neon", OpCategory.ARTISTIC, Origin.PICSAY_PRO,
        listOf(ParamSpec.Color("color", "color", 0xFF00E5FF.toInt()), percent("contrast", "contrast", 0.5f)),
    ) { src, p, ctx ->
        val col = p.color("color", 0xFF00E5FF.toInt())
        val gain = 4f + 12f * p.float("contrast", 0.5f)
        val w = src.width; val h = src.height
        val l = EffectKit.luminance(src)
        EffectKit.blurGray(l, w, h, max(0.7f, unit(src) / 900f), ctx)
        val e = FloatArray(l.size)
        ctx.forRows(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                fun at(dx: Int, dy: Int) = l[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                val gx = at(1, -1) + 2 * at(1, 0) + at(1, 1) - at(-1, -1) - 2 * at(-1, 0) - at(-1, 1)
                val gy = at(-1, 1) + 2 * at(0, 1) + at(1, 1) - at(-1, -1) - 2 * at(0, -1) - at(1, -1)
                e[y * w + x] = min(1f, sqrt(gx * gx + gy * gy) / 1020f * gain * max(0.3f, unit(src) / 600f))
            }
        }
        val glow = e.copyOf()
        EffectKit.blurGray(glow, w, h, max(1.5f, unit(src) / 120f), ctx)
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val i = y * w + x
            val k = min(1f, e[i] + glow[i] * 1.6f)
            val core = e[i] * e[i] // white-hot core on strong edges
            packF(a(c), r(col) * k + (255 - r(col)) * core, g(col) * k + (255 - g(col)) * core, b(col) * k + (255 - b(col)) * core)
        }
    }

    // ---------------- Misc ----------------

    /** X-ray: inverted luminance through a cold palette (own palette, not the original `xray.png`). */
    val XRay = Fx("x_ray", OpCategory.MISC, Origin.PICSAY_PRO, emptyList()) { src, _, ctx ->
        val stops = intArrayOf(0xFF000000.toInt(), 0xFF0A1C38.toInt(), 0xFF2F6EA8.toInt(), 0xFFA9DCFF.toInt(), 0xFFFFFFFF.toInt())
        val pal = IntArray(256) { EffectKit.gradient(stops, EffectKit.sCurve(1f - it / 255f, 0.4f)) }
        EffectKit.map(src, ctx) { c -> (c and (0xFF shl 24)) or (pal[EffectKit.clampF(lum(c))] and 0xFFFFFF) }
    }

    /** Heat map: luminance through a thermal palette (own palette, not the original `heatmap.png`). */
    val HeatMap = Fx("heat_map", OpCategory.MISC, Origin.PICSAY_PRO, emptyList()) { src, _, ctx ->
        val stops = intArrayOf(0xFF10005A.toInt(), 0xFF2A3CFF.toInt(), 0xFF00D8FF.toInt(), 0xFF3CFF4E.toInt(), 0xFFFFF200.toInt(), 0xFFFF7A00.toInt(), 0xFFFF0000.toInt())
        val pal = IntArray(256) { EffectKit.gradient(stops, it / 255f) }
        EffectKit.map(src, ctx) { c -> (c and (0xFF shl 24)) or (pal[EffectKit.clampF(lum(c))] and 0xFFFFFF) }
    }

    /**
     * "Make Transparent": only offered after painting a mask (original `mix ` with mode clear [V]).
     * Category DRAW keeps it out of the effect menus.
     */
    val MakeTransparent = Fx("make_transparent", OpCategory.DRAW, Origin.PICSAY_PRO, listOf(percent("amount", "amount", 1f)),
        identity = { it.float("amount", 1f) == 0f }) { src, p, ctx ->
        val k = p.float("amount", 1f)
        EffectKit.map(src, ctx) { c -> (EffectKit.clampF(a(c) * (1f - k)) shl 24) or (c and 0xFFFFFF) }
    }

    val color = listOf(Grayscale, BlackWhite, Duotone, Rainbow, BlendColor)
    val artistic = listOf(ComicPrint, Posterize, PopArt, PencilSketch, Neon)
    val misc = listOf(XRay, HeatMap)
    val all = color + artistic + misc + AdjustRgb + MakeTransparent
}
