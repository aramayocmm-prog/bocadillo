package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.effects.EffectKit.unit
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distortions (menu Effect › Distort and Misc). Centre, radius and amount follow the fields of the
 * original (`twrl`, `sphr`, `bulg`, `strc`, `strh`, `strv`, `cspl`, `mirr`, `ofoc`, `pixl`).
 * Radii are fractions of u = min(width, height). Curves are own equivalents [P calibration].
 */
object DistortOps {

    private fun radius(def: Float = 0.5f) = slider("radius", "radius", 0.05f, 1.5f, 0.01f, def, ParamSpec.Unit.PERCENT, 100f)

    /** Radial warp inside a circle: [f] maps normalized distance d (0..1) to the source distance. */
    private fun radial(src: PixelBuffer, p: Params, ctx: RenderContext, f: (Double) -> Double): PixelBuffer {
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width.toDouble(); val cy = cyn * src.height.toDouble()
        val rad = max(1.0, p.float("radius", 0.5f) * unit(src).toDouble())
        return EffectKit.warp(src, ctx) { x, y, o ->
            val dx = x - cx; val dy = y - cy
            val d = sqrt(dx * dx + dy * dy) / rad
            if (d >= 1.0 || d < 1e-9) { o[0] = x; o[1] = y } else {
                val k = f(d) / d
                o[0] = cx + dx * k; o[1] = cy + dy * k
            }
        }
    }

    /** Twirl: rotation decreasing from the centre to the radius. Amount −90…90 [V]; 90 = one full turn at the centre. */
    val Twirl = Fx(
        "twirl", OpCategory.DISTORT, Origin.PICSAY_BOTH,
        listOf(slider("amount", "amount", -90f, 90f, 1f, 45f, ParamSpec.Unit.DEGREES), radius(), center()),
        identity = { it.float("amount", 45f) == 0f },
    ) { src, p, ctx ->
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width.toDouble(); val cy = cyn * src.height.toDouble()
        val rad = max(1.0, p.float("radius", 0.5f) * unit(src).toDouble())
        val maxAngle = p.float("amount", 45f) / 90.0 * 2 * PI
        EffectKit.warp(src, ctx) { x, y, o ->
            val dx = x - cx; val dy = y - cy
            val d = sqrt(dx * dx + dy * dy) / rad
            if (d >= 1.0) { o[0] = x; o[1] = y } else {
                val t = (1 - d) * (1 - d)
                val a = -maxAngle * t
                val c = cos(a); val s = sin(a)
                o[0] = cx + dx * c - dy * s; o[1] = cy + dx * s + dy * c
            }
        }
    }

    /** Spherize: refraction through a sphere (positive) or its inverse (negative); k = 0.99·amount [V]. */
    val Spherize = Fx(
        "spherize", OpCategory.DISTORT, Origin.PICSAY_BOTH,
        listOf(percent("amount", "amount", 0.5f, -1f, 1f), radius(), center()),
        identity = { it.float("amount", 0.5f) == 0f },
    ) { src, p, ctx ->
        val k = 0.99 * p.float("amount", 0.5f)
        radial(src, p, ctx) { d ->
            if (k >= 0) d + (asin(d) * 2 / PI - d) * k else d + (sin(d * PI / 2) - d) * -k
        }
    }

    /** Bulge: smooth magnification (positive) or pinch (negative) fading to zero at the radius. */
    val Bulge = Fx(
        "bulge", OpCategory.DISTORT, Origin.PICSAY_BOTH,
        listOf(percent("amount", "amount", 0.5f, -1f, 1f), radius(), center()),
        identity = { it.float("amount", 0.5f) == 0f },
    ) { src, p, ctx ->
        val k = 0.99 * p.float("amount", 0.5f)
        radial(src, p, ctx) { d -> d * (1 - k * (1 - d) * (1 - d)) }
    }

    /** Stretch: directional magnification inside the circle along [angle]. */
    val Stretch = Fx(
        "stretch", OpCategory.DISTORT, Origin.PICSAY_BOTH,
        listOf(percent("amount", "amount", 0.5f, -1f, 1f), slider("angle", "angle", -90f, 90f, 1f, 0f, ParamSpec.Unit.DEGREES), radius(), center()),
        identity = { it.float("amount", 0.5f) == 0f },
    ) { src, p, ctx ->
        val k = 0.99 * p.float("amount", 0.5f)
        val ang = p.float("angle", 0f) * PI / 180
        val ux = cos(ang); val uy = sin(ang)
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width.toDouble(); val cy = cyn * src.height.toDouble()
        val rad = max(1.0, p.float("radius", 0.5f) * unit(src).toDouble())
        EffectKit.warp(src, ctx) { x, y, o ->
            val dx = x - cx; val dy = y - cy
            val d = sqrt(dx * dx + dy * dy) / rad
            if (d >= 1.0) { o[0] = x; o[1] = y } else {
                val along = dx * ux + dy * uy; val across = -dx * uy + dy * ux
                val a2 = along * (1 - k * (1 - d) * (1 - d))
                o[0] = cx + a2 * ux - across * uy; o[1] = cy + a2 * uy + across * ux
            }
        }
    }

    /** Horizontal / vertical stretch of a band around the centre line (Pro `strh` / `strv`). */
    private fun bandStretch(id: String, vertical: Boolean) = Fx(
        id, OpCategory.DISTORT, Origin.PICSAY_PRO,
        listOf(percent("amount", "amount", 0.5f, -1f, 1f), radius(0.4f), center()),
        identity = { it.float("amount", 0.5f) == 0f },
    ) { src, p, ctx ->
        val k = 0.99 * p.float("amount", 0.5f)
        val (cxn, cyn) = p.point()
        val c = if (vertical) cyn * src.height.toDouble() else cxn * src.width.toDouble()
        val rad = max(1.0, p.float("radius", 0.4f) * unit(src).toDouble())
        EffectKit.warp(src, ctx) { x, y, o ->
            val v = if (vertical) y else x
            val d = abs(v - c) / rad
            val nv = if (d >= 1.0) v else c + (v - c) * (1 - k * (1 - d) * (1 - d))
            if (vertical) { o[0] = x; o[1] = nv } else { o[0] = nv; o[1] = y }
        }
    }
    val StretchH = bandStretch("stretch_h", vertical = false)
    val StretchV = bandStretch("stretch_v", vertical = true)

    /** Mirror: copies one half onto the other; field `rightToLeft` [V]. */
    val Mirror = Fx(
        "mirror", OpCategory.DISTORT, Origin.PICSAY_PRO,
        listOf(ParamSpec.Toggle("rightToLeft", "right_to_left", false)),
    ) { src, p, ctx ->
        val rtl = p.bool("rightToLeft", false)
        val w = src.width; val half = w / 2
        EffectKit.mapXY(src, ctx) { x, y, c ->
            when {
                !rtl && x >= w - half -> src.pixels[y * w + (w - 1 - x)]
                rtl && x < half -> src.pixels[y * w + (w - 1 - x)]
                else -> c
            }
        }
    }

    /** Circle splash: concentric ripples that fade toward the radius (own algorithm). */
    val CircleSplash = Fx(
        "circle_splash", OpCategory.DISTORT, Origin.PICSAY_PRO,
        listOf(percent("amount", "amount", 0.5f), slider("waves", "waves", 1f, 20f, 1f, 6f), radius(0.6f), center()),
        identity = { it.float("amount", 0.5f) == 0f },
    ) { src, p, ctx ->
        val waves = p.float("waves", 6f).toDouble()
        val k = p.float("amount", 0.5f)
        val amp = k * 0.6 / waves // displacement in units of the radius
        val warped = radial(src, p, ctx) { d -> d + amp * sin(2 * PI * waves * d) * (1 - d) * (1 - d) }
        // light and shade on the wave crests
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width; val cy = cyn * src.height
        val rad = max(1.0, p.float("radius", 0.6f) * unit(src).toDouble())
        EffectKit.mapXY(warped, ctx) { x, y, c ->
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
            val d = sqrt(dx * dx + dy * dy) / rad
            if (d >= 1.0) c else {
                val sh = (0.22 * k * kotlin.math.cos(2 * PI * waves * d) * (1 - d) * (1 - d)).toFloat()
                if (sh >= 0) EffectKit.mixColor(c, 0xFFFFFFFF.toInt(), sh) else EffectKit.mixColor(c, 0xFF000000.toInt(), -sh)
            }
        }
    }

    /** Dizziness: averaged offset copies (double vision). Amount −30…30 [V]; scale 1 [V]. */
    val Dizziness = Fx(
        "dizziness", OpCategory.MISC, Origin.PICSAY_BOTH,
        listOf(slider("amount", "amount", -30f, 30f, 1f, 10f)),
        identity = { it.float("amount", 10f) == 0f },
    ) { src, p, ctx ->
        val a = p.float("amount", 10f)
        val off = a * unit(src) / 1000.0 * 1.5
        val ox = off; val oy = abs(off) * 0.35
        val s = EffectKit.Sampler(src)
        EffectKit.mapXY(src, ctx) { x, y, _ ->
            val px = x + 0.5; val py = y + 0.5
            val c0 = s.premul(px, py); val c1 = s.premul(px + ox, py + oy); val c2 = s.premul(px - ox, py - oy)
            fun ch(sh: Int) = (((c0 ushr sh) and 0xFF) * 0.4f + ((c1 ushr sh) and 0xFF) * 0.3f + ((c2 ushr sh) and 0xFF) * 0.3f)
            app.bocadillo.core.imaging.unpremultiply(EffectKit.packF(EffectKit.clampF(ch(24)), ch(16), ch(8), ch(0)))
        }
    }

    /** Pixelize: average colour per square block. Size is the block side in thousandths of u. */
    val Pixelize = Fx(
        "pixelize", OpCategory.MISC, Origin.PICSAY_BOTH,
        listOf(slider("size", "block_size", 2f, 100f, 1f, 16f)),
    ) { src, p, ctx ->
        val w = src.width; val h = src.height
        val block = max(2, (p.float("size", 16f) * unit(src) / 1000f).roundToInt())
        val bw = (w + block - 1) / block; val bh = (h + block - 1) / block
        val avg = IntArray(bw * bh)
        ctx.forRows(bh) { b0, b1 ->
            for (by in b0 until b1) for (bx in 0 until bw) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                for (y in by * block until minOf(h, (by + 1) * block)) for (x in bx * block until minOf(w, (bx + 1) * block)) {
                    val c = src.pixels[y * w + x]; val al = c ushr 24
                    sa += al; sr += ((c shr 16) and 0xFF) * al; sg += ((c shr 8) and 0xFF) * al; sb += (c and 0xFF) * al; n++
                }
                avg[by * bw + bx] = if (sa == 0L) 0 else EffectKit.pack((sa / n).toInt(), (sr / sa).toInt(), (sg / sa).toInt(), (sb / sa).toInt())
            }
        }
        EffectKit.mapXY(src, ctx) { x, y, _ -> avg[(y / block) * bw + x / block] }
    }

    val all = listOf(Spherize, Twirl, Bulge, CircleSplash, Stretch, StretchV, StretchH, Mirror, Dizziness, Pixelize)
}
