package app.bocadillo.core.imaging.ops

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.ops.ImageOp.Origin
import app.bocadillo.core.imaging.paint.StrokeCodec
import app.bocadillo.core.imaging.paint.StrokePainter
import app.bocadillo.core.imaging.paint.StrokeRasterizer
import app.bocadillo.core.model.MaskData
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An op that needs the rendered result of an earlier stage of the same pipeline
 * (History Paint). [earlier] renders the document's first n ops (null if not available).
 */
interface StagedOp : ImageOp {
    fun apply(src: PixelBuffer, p: Params, ctx: RenderContext, earlier: (Int) -> PixelBuffer?): PixelBuffer
    override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer = apply(src, p, ctx) { null }
}

object PaintOps {

    /** Brush / Marker / Eraser strokes painted into the picture (original "Marker" = pntb). */
    object Paint : ImageOp {
        override val id = "paint"
        override val category = OpCategory.DRAW
        override val geometric = false
        override val origin = Origin.PICSAY_BOTH
        override val params = emptyList<ParamSpec>() // strokes are drawn on the canvas
        override fun isIdentity(p: Params) = StrokeCodec.decode(p.floats("strokes")).isEmpty()
        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer =
            StrokePainter.paint(src, StrokeCodec.decode(p.floats("strokes")), ctx)
    }

    /**
     * History Paint (Pro): paints back pixels from an earlier stage. "stage" = number of the
     * document's ops whose result is used (0 = the opened picture). Only works if that stage
     * has the same size as the current picture (as in the original).
     */
    object HistoryPaint : StagedOp {
        override val id = "history_paint"
        override val category = OpCategory.DRAW
        override val geometric = false
        override val origin = Origin.PICSAY_PRO
        override val params = emptyList<ParamSpec>()
        override fun isIdentity(p: Params) = StrokeCodec.decode(p.floats("strokes")).isEmpty()

        override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext, earlier: (Int) -> PixelBuffer?): PixelBuffer {
            val strokes = StrokeCodec.decode(p.floats("strokes"))
            val from = earlier(p.int("stage", 0)) ?: return src
            if (from.width != src.width || from.height != src.height) return src
            val cov = StrokeRasterizer.maskCoverage(strokes, src.width, src.height, 0f, ctx)
            val out = IntArray(src.size)
            val a = src.pixels; val b = from.pixels
            for (i in out.indices) {
                val k = (cov[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[i] = if (k == 0) a[i] else if (k == 255) b[i] else lerp(a[i], b[i], k)
            }
            return PixelBuffer(src.width, src.height, out)
        }
    }

    internal fun lerp(c0: Int, c1: Int, k: Int): Int {
        fun ch(s: Int) = (((c0 ushr s) and 0xFF) * (255 - k) + ((c1 ushr s) and 0xFF) * k + 127) / 255
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    val all: List<ImageOp> = listOf(Paint, HistoryPaint)
}

/**
 * Painted mask attached to any non-geometric op through its params ("Paint Mask / Selection"):
 *  - "mask": strokes (PAINT adds, ERASE removes)
 *  - "maskMode": "select" (green: effect only where painted) or "protect" (red: painted areas
 *    are protected; the layer starts fully protected and the user erases where the effect goes)
 *  - "maskInvert": swaps affected/unaffected areas
 *  - "maskFeather": edge softness in u (fraction of the smaller side)
 */
object MaskParams {
    const val STROKES = "mask"
    const val MODE = "maskMode"
    const val INVERT = "maskInvert"
    const val FEATHER = "maskFeather"

    fun has(p: Params) = p.raw(MODE) != null

    /** Effect coverage (255 = full effect) at w×h, or null if the op has no mask. */
    fun build(p: Params, w: Int, h: Int, ctx: RenderContext? = null): MaskData? {
        if (!has(p)) return null
        val protect = p.string(MODE, "select") == "protect"
        val strokes = StrokeCodec.decode(p.floats(STROKES))
        var m = StrokeRasterizer.maskCoverage(strokes, w, h, if (protect) 1f else 0f, ctx)
        val r = (p.float(FEATHER, 0f) * min(w, h)).roundToInt()
        m = StrokeRasterizer.feather(m, w, h, r)
        val invert = p.bool(INVERT, false) xor protect
        return MaskData(w, h, ByteArray(w * h) { i ->
            val v = if (invert) 1f - m[i] else m[i]
            (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        })
    }

    /** Removes all mask keys (e.g. when the user drops the mask). */
    fun strip(p: Params): Params = p.without(STROKES).without(MODE).without(INVERT).without(FEATHER)
}
