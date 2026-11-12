package app.bocadillo.core.imaging.objects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.paint.Stroke
import app.bocadillo.core.imaging.paint.StrokeCodec
import app.bocadillo.core.imaging.paint.StrokeMode
import app.bocadillo.core.imaging.paint.StrokeRasterizer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Insert Picture's crop and Cut Out mask, shared by the live editor and the export so both give
 * the same pixels. The mask is computed at a fixed raster size derived from the cropped image
 * (longest side at most [MAX_SIDE]), in the image's own coordinates, so it does not depend on
 * zoom, rotation, perspective or the screen.
 */
object Cutout {
    /**
     * Longest side of the mask raster. 1.1.0 raised it from 1024 to 2048: fine detail (hair,
     * thin edges) of a large picture was coarse, and the exported picture was limited to it.
     * Strokes are normalized, so masks saved by 1.0 give the same shape, only sharper.
     */
    const val MAX_SIDE = 2048

    /** Pixel rectangle (x, y, w, h) of [crop] (fractions) on a srcW×srcH image. */
    fun cropRect(crop: List<Float>, srcW: Int, srcH: Int): IntArray {
        if (crop.size != 4) return intArrayOf(0, 0, srcW, srcH)
        val l = (crop[0].coerceIn(0f, 1f) * srcW).roundToInt(); val t = (crop[1].coerceIn(0f, 1f) * srcH).roundToInt()
        val r = (crop[2].coerceIn(0f, 1f) * srcW).roundToInt(); val b = (crop[3].coerceIn(0f, 1f) * srcH).roundToInt()
        val w = max(1, r - l); val h = max(1, b - t)
        return intArrayOf(l.coerceAtMost(srcW - 1), t.coerceAtMost(srcH - 1), w.coerceAtMost(srcW - l), h.coerceAtMost(srcH - t))
    }

    /** Raster size of the mask (and of the processed picture) for a cropped image of cw×ch px. */
    fun rasterSize(cw: Int, ch: Int): Pair<Int, Int> {
        val longest = max(cw, ch)
        if (longest <= MAX_SIDE) return cw to ch
        val k = MAX_SIDE.toFloat() / longest
        return max(1, (cw * k).roundToInt()) to max(1, (ch * k).roundToInt())
    }

    fun strokes(cutout: List<Float>): List<Stroke> = StrokeCodec.decode(cutout.toFloatArray())

    fun encode(strokes: List<Stroke>): List<Float> = if (strokes.isEmpty()) emptyList() else StrokeCodec.encode(strokes).toList()

    /** Alpha mask 0..1 (1 = kept). Eraser strokes (ERASE) remove, Restore Brush strokes (PAINT) bring back. */
    fun mask(strokes: List<Stroke>, w: Int, h: Int): FloatArray = StrokeRasterizer.maskCoverage(strokes, w, h, base = 1f)

    /** Multiplies the picture's alpha by the mask (same size). Colours are kept, so restoring brings them back. */
    fun apply(src: PixelBuffer, mask: FloatArray): PixelBuffer {
        require(mask.size == src.size)
        val out = IntArray(src.size)
        for (i in out.indices) out[i] = pixel(src.pixels[i], mask[i])
        return PixelBuffer(src.width, src.height, out)
    }

    /** One pixel of [apply]: alpha × mask, colour kept. */
    fun pixel(p: Int, m: Float): Int {
        val a = (((p ushr 24) and 0xFF) * m.coerceIn(0f, 1f) + 0.5f).toInt()
        return (a shl 24) or (p and 0xFFFFFF)
    }

    /** Mask value with the stroke in progress on top of the settled mask (same step as [mask]). */
    fun liveMask(m: Float, coverage: Float, s: Stroke): Float {
        val v = coverage * s.opacity
        return if (v <= 0f) m else StrokeRasterizer.maskCombine(m, v, s.mode != StrokeMode.ERASE)
    }

    fun eraser(points: FloatArray, size: Float, hardness: Float, square: Boolean = false) = Stroke(points, size, hardness, 1f, 0xFF000000.toInt(), StrokeMode.ERASE, pressureSize = true, square = square)
    fun restore(points: FloatArray, size: Float, hardness: Float, square: Boolean = false) = Stroke(points, size, hardness, 1f, 0xFF000000.toInt(), StrokeMode.PAINT, pressureSize = true, square = square)
}
