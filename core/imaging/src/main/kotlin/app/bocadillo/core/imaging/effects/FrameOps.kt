package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.effects.EffectKit.a
import app.bocadillo.core.imaging.effects.EffectKit.b
import app.bocadillo.core.imaging.effects.EffectKit.g
import app.bocadillo.core.imaging.effects.EffectKit.packF
import app.bocadillo.core.imaging.effects.EffectKit.r
import app.bocadillo.core.imaging.effects.EffectKit.unit
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.model.ParamSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Filters & frames (Effect › Filter & Frames). Frames are drawn inside the picture (same size). */
object FrameOps {

    private val WHITE = 0xFFFFFFFF.toInt()

    /** Vignette: darkens (positive) or lightens (negative) toward the corners. */
    val Vignette = Fx("vignette", OpCategory.FRAMES, Origin.PICSAY_PRO,
        listOf(percent("amount", "amount", 0.5f, -1f, 1f), percent("size", "size", 0.5f)),
        identity = { it.float("amount", 0.5f) == 0f }) { src, p, ctx ->
        val k = p.float("amount", 0.5f); val size = p.float("size", 0.5f)
        val inner = 0.15f + 0.6f * size
        val w = src.width; val h = src.height
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val m = EffectKit.smoothstep(inner, inner + 0.75f, PhotoOps.cornerDistance(x, y, w, h)) * abs(k)
            if (k >= 0) packF(a(c), r(c) * (1 - m), g(c) * (1 - m), b(c) * (1 - m))
            else packF(a(c), r(c) + (255 - r(c)) * m, g(c) + (255 - g(c)) * m, b(c) + (255 - b(c)) * m)
        }
    }

    /** Graduated lens filter from the top (`lfgr`, 0.75 [V]). */
    val TopLens = Fx("top_lens", OpCategory.FRAMES, Origin.PICSAY_PRO,
        listOf(percent("amount", "amount", 0.75f), ParamSpec.Color("color", "color", 0xFF4F7FC0.toInt())),
        identity = { it.float("amount", 0.75f) == 0f }) { src, p, ctx ->
        val k = p.float("amount", 0.75f); val col = p.color("color", 0xFF4F7FC0.toInt()) or (0xFF shl 24)
        EffectKit.mapXY(src, ctx) { _, y, c ->
            val t = 1f - EffectKit.smoothstep(0f, 0.6f, (y + 0.5f) / src.height)
            EffectKit.blend(c, col, k * t, EffectKit::multiply)
        }
    }

    /** Lens filter on the edges (`lfed`, 0.75 [V]). */
    val EdgeLens = Fx("edge_lens", OpCategory.FRAMES, Origin.PICSAY_PRO,
        listOf(percent("amount", "amount", 0.75f), ParamSpec.Color("color", "color", 0xFF8A5A3C.toInt())),
        identity = { it.float("amount", 0.75f) == 0f }) { src, p, ctx ->
        val k = p.float("amount", 0.75f); val col = p.color("color", 0xFF8A5A3C.toInt()) or (0xFF shl 24)
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val t = EffectKit.smoothstep(0.35f, 1.05f, PhotoOps.cornerDistance(x, y, src.width, src.height))
            EffectKit.blend(c, col, k * t, EffectKit::multiply)
        }
    }

    /** Spotlight (`spot`: centre, radius, falloff, colour [V]); radius 1 = half the short side. */
    val Spotlight = Fx("spotlight", OpCategory.FRAMES, Origin.PICSAY_BOTH, listOf(
        slider("radius", "radius", 0.1f, 2f, 0.01f, 1f, ParamSpec.Unit.PERCENT, 100f), percent("falloff", "falloff", 0.4f, 0.01f, 1f),
        percent("amount", "darkness", 0.75f), ParamSpec.Color("color", "color", 0xFF000000.toInt()), center(),
    )) { src, p, ctx ->
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width; val cy = cyn * src.height
        val u = unit(src)
        val rad = p.float("radius", 1f) * u / 2f; val fall = max(1f, p.float("falloff", 0.4f) * u / 2f)
        val k = p.float("amount", 0.75f); val col = p.color("color", 0xFF000000.toInt()) or (0xFF shl 24)
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            EffectKit.mixColor(c, col, k * EffectKit.smoothstep(rad, rad + fall, sqrt(dx * dx + dy * dy)))
        }
    }

    /** Focal blur (`blrf`: centre, radius 0.5, falloff 0.5 [V]). */
    val FocalBlur = Fx("focal_blur", OpCategory.FRAMES, Origin.PICSAY_PRO, listOf(
        slider("blur", "blur", 0f, 40f, 0.1f, 15f), percent("radius", "radius", 0.5f, 0.02f, 1.5f), percent("falloff", "falloff", 0.5f, 0.01f, 1.5f), center(),
    ), identity = { it.float("blur", 15f) == 0f }) { src, p, ctx ->
        val u = unit(src)
        val blurred = EffectKit.blur(src, p.float("blur", 15f) * u / 1000f * 1.5f, ctx)
        val (cxn, cyn) = p.point()
        val cx = cxn * src.width; val cy = cyn * src.height
        val rad = p.float("radius", 0.5f) * u / 2f; val fall = max(1f, p.float("falloff", 0.5f) * u / 2f)
        EffectKit.combine(src, blurred, ctx) { i, c, bc ->
            val dx = i % src.width + 0.5f - cx; val dy = i / src.width + 0.5f - cy
            PhotoOps.mixAll(c, bc, EffectKit.smoothstep(rad, rad + fall, sqrt(dx * dx + dy * dy)))
        }
    }

    /** Rounded corners (`rndc`, 0.75 [V]): corners become transparent (antialiased). */
    val RoundedCorners = Fx("rounded_corners", OpCategory.FRAMES, Origin.PICSAY_PRO, listOf(percent("amount", "amount", 0.75f)),
        identity = { it.float("amount", 0.75f) == 0f }) { src, p, ctx ->
        val rad = p.float("amount", 0.75f) * unit(src) * 0.2f
        val w = src.width; val h = src.height
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val cov = coverageRoundRect(x + 0.5f, y + 0.5f, 0f, 0f, w.toFloat(), h.toFloat(), rad)
            if (cov >= 1f) c else (EffectKit.clampF(a(c) * cov) shl 24) or (c and 0xFFFFFF)
        }
    }

    /** Solid border 0…80 [V], in thousandths of the short side, drawn inside the picture. */
    val SolidBorder = Fx("solid_border", OpCategory.FRAMES, Origin.PICSAY_PRO,
        listOf(slider("width", "border_width", 0f, 80f, 1f, 20f), ParamSpec.Color("color", "color", WHITE)),
        identity = { it.float("width", 20f) == 0f }) { src, p, ctx ->
        val bw = p.float("width", 20f) * unit(src) / 1000f
        val col = p.color("color", WHITE)
        val w = src.width; val h = src.height
        EffectKit.mapXY(src, ctx) { x, y, c ->
            val d = min(min(x + 0.5f, w - x - 0.5f), min(y + 0.5f, h - y - 0.5f))
            val t = (bw - d + 0.5f).coerceIn(0f, 1f)
            PhotoOps.mixAll(c, col, t)
        }
    }

    /** Matte (`matt`, 0.75 [V]): picture reduced onto a mat board with a thin bevel line. */
    val Matte = Fx("matte", OpCategory.FRAMES, Origin.PICSAY_PRO,
        listOf(percent("amount", "matte_size", 0.75f), ParamSpec.Color("color", "color", 0xFFF3EFE6.toInt()))) { src, p, ctx ->
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val m = (0.02f + 0.14f * p.float("amount", 0.75f)) * unit(src)
        val s = min((w - 2 * m) / w, (h - 2 * m) / h)
        val pw = w * s; val ph = h * s
        val ox = (w - pw) / 2f; val oy = (h - ph) / 2f
        val col = p.color("color", 0xFFF3EFE6.toInt())
        val line = max(1f, unit(src) * 0.004f)
        val sampler = EffectKit.Sampler(src)
        EffectKit.mapXY(src, ctx) { x, y, _ ->
            val px = x + 0.5f; val py = y + 0.5f
            val inPic = coverageRoundRect(px, py, ox, oy, ox + pw, oy + ph, 0f)
            val bevel = coverageRoundRect(px, py, ox - line, oy - line, ox + pw + line, oy + ph + line, 0f)
            var c = EffectKit.mixColor(col, 0xFFB9B2A4.toInt(), bevel) // bevel tone just around the window
            if (inPic > 0f) c = PhotoOps.mixAll(c, sampler.at(((px - ox) / s).toDouble(), ((py - oy) / s).toDouble()), inPic)
            c
        }
    }

    /** Instant photo frame (`inst`, −10…10 [V], read as the card's tilt in degrees). */
    val Instant = Fx("instant", OpCategory.FRAMES, Origin.PICSAY_PRO, listOf(
        slider("angle", "angle", -10f, 10f, 0.1f, 0f, ParamSpec.Unit.DEGREES), ParamSpec.Color("background", "background", 0xFF2A2A2E.toInt()),
    )) { src, p, ctx ->
        val w = src.width.toFloat(); val h = src.height.toFloat(); val u = unit(src)
        val ang = p.float("angle", 0f) * PI.toFloat() / 180f
        val ca = cos(ang); val sa = sin(ang)
        // card proportions: side/top border 6 %, bottom border 24 % of the card width
        val fit = 0.86f
        val ratio = 1.18f // card height / width
        var cw = w * fit; var ch = cw * ratio
        if (ch > h * fit) { ch = h * fit; cw = ch / ratio }
        val side = cw * 0.06f
        val pic = floatArrayOf(-cw / 2 + side, -ch / 2 + side, cw / 2 - side, ch / 2 - cw * 0.24f)
        val pw = pic[2] - pic[0]; val ph = pic[3] - pic[1]
        val cover = max(pw / w, ph / h) // centre-crop the source into the window
        val bg = p.color("background", 0xFF2A2A2E.toInt())
        val shadowOff = u * 0.012f; val shadowSoft = u * 0.02f
        val sampler = EffectKit.Sampler(src)
        EffectKit.mapXY(src, ctx) { x, y, _ ->
            val dx = x + 0.5f - w / 2; val dy = y + 0.5f - h / 2
            val lx = dx * ca + dy * sa; val ly = -dx * sa + dy * ca // into card space
            val shadow = coverageRoundRect(lx - shadowOff * 0.3f, ly - shadowOff, -cw / 2, -ch / 2, cw / 2, ch / 2, shadowSoft, shadowSoft)
            var c = EffectKit.mixColor(bg, 0xFF000000.toInt(), shadow * 0.55f)
            val card = coverageRoundRect(lx, ly, -cw / 2, -ch / 2, cw / 2, ch / 2, u * 0.004f)
            if (card > 0f) {
                var cc = 0xFFF7F6F0.toInt()
                val inPic = coverageRoundRect(lx, ly, pic[0], pic[1], pic[2], pic[3], 0f)
                if (inPic > 0f) {
                    val sx = (lx - (pic[0] + pic[2]) / 2) / cover + w / 2; val sy = (ly - (pic[1] + pic[3]) / 2) / cover + h / 2
                    cc = PhotoOps.mixAll(cc, sampler.at(sx.toDouble(), sy.toDouble()) or (0xFF shl 24), inPic)
                }
                c = EffectKit.mixColor(c, cc, card)
            }
            c
        }
    }

    /** Film strip frame (`film`): black strips with sprocket holes; picture centre-cropped. */
    val Film = Fx("film", OpCategory.FRAMES, Origin.PICSAY_PRO, emptyList()) { src, _, ctx ->
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val landscape = w >= h
        // long axis carries the holes
        val len = if (landscape) w else h; val across = if (landscape) h else w
        val strip = across * 0.13f
        val gap = across * 0.015f
        val win = floatArrayOf(gap, strip, len - gap, across - strip) // in (along, across) space
        val holeH = strip * 0.42f; val holeW = holeH * 0.75f; val pitch = holeH * 1.55f
        val count = max(1, (len / pitch).toInt()); val start = (len - (count - 1) * pitch) / 2f
        val pwin = win[2] - win[0]; val qwin = win[3] - win[1]
        val cover = max(pwin / len, qwin / across)
        val sampler = EffectKit.Sampler(src)
        val frame = 0xFF101010.toInt(); val hole = 0xFFE9E6DD.toInt()
        EffectKit.mapXY(src, ctx) { x, y, _ ->
            val pa = if (landscape) x + 0.5f else y + 0.5f
            val pc = if (landscape) y + 0.5f else x + 0.5f
            var c = frame
            val inWin = coverageRoundRect(pa, pc, win[0], win[1], win[2], win[3], across * 0.01f)
            if (inWin > 0f) {
                val sa = (pa - (win[0] + win[2]) / 2) / cover + len / 2; val sc = (pc - (win[1] + win[3]) / 2) / cover + across / 2
                val s = if (landscape) sampler.at(sa.toDouble(), sc.toDouble()) else sampler.at(sc.toDouble(), sa.toDouble())
                c = PhotoOps.mixAll(c, s or (0xFF shl 24), inWin)
            } else {
                val k = ((pa - start) / pitch + 0.5f).toInt().coerceIn(0, count - 1)
                val hc = start + k * pitch
                for (row in 0..1) {
                    val cc = if (row == 0) strip / 2 else across - strip / 2
                    val cov = coverageRoundRect(pa, pc, hc - holeW / 2, cc - holeH / 2, hc + holeW / 2, cc + holeH / 2, holeW * 0.2f)
                    if (cov > 0f) c = EffectKit.mixColor(c, hole, cov)
                }
            }
            c
        }
    }

    /**
     * Antialiased coverage (0..1) of point (x, y) by a rounded rectangle; [soft] widens the edge
     * into a smooth falloff (used for shadows).
     */
    internal fun coverageRoundRect(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float, radius: Float, soft: Float = 0f): Float {
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val hx = (r - l) / 2; val hy = (b - t) / 2
        val rad = min(radius, min(hx, hy))
        val qx = abs(x - cx) - (hx - rad); val qy = abs(y - cy) - (hy - rad)
        val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
        val inside = min(max(qx, qy), 0f)
        val sd = outside + inside - rad
        return if (soft > 0f) 1f - EffectKit.smoothstep(-soft, soft, sd) else (0.5f - sd).coerceIn(0f, 1f)
    }

    val all = listOf(Vignette, FocalBlur, TopLens, EdgeLens, Spotlight, Instant, Film, RoundedCorners, SolidBorder, Matte)
}
