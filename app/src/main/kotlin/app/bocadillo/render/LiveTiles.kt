package app.bocadillo.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min

/**
 * A w×h picture drawn over another one in 256-px tiles (1.1.0, correction 1). While a stroke is
 * in progress only the tiles it touches exist and only the tiles that changed are uploaded to
 * the GPU, instead of the whole picture on every finger move as in 1.0.
 *
 * Each tile keeps a 1-px apron copied from its neighbours, so bilinear filtering at tile edges
 * reads the right pixels, and tiles are drawn without anti-aliasing, so adjacent tiles meet
 * exactly (no seams). Pixels outside the stroke equal the picture underneath, so the tile
 * borders are invisible. Main thread only.
 */
class LiveTiles(val width: Int, val height: Int) {
    private val cols = (width + T - 1) / T
    private val rows = (height + T - 1) / T
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    /** Changes on every update; read it while drawing so the view redraws. */
    var version by mutableIntStateOf(0)
        private set

    val isEmpty: Boolean get() = tiles.all { it == null }

    /**
     * Copies area [r] (x0, y0, x1, y1 exclusive) of [px] (the full w×h picture) into the tiles it
     * touches, creating them (filled from [px]) when needed.
     */
    fun update(px: IntArray, r: IntArray) {
        val x0 = max(0, r[0] - APRON); val y0 = max(0, r[1] - APRON)
        val x1 = min(width, r[2] + APRON); val y1 = min(height, r[3] + APRON)
        if (x1 <= x0 || y1 <= y0) return
        for (ty in max(0, (y0 - APRON) / T)..min(rows - 1, (y1 - 1 + APRON) / T)) {
            for (tx in max(0, (x0 - APRON) / T)..min(cols - 1, (x1 - 1 + APRON) / T)) {
                // tile area with its apron, clipped to the picture
                val ex0 = max(0, tx * T - APRON); val ey0 = max(0, ty * T - APRON)
                val ex1 = min(width, (tx + 1) * T + APRON); val ey1 = min(height, (ty + 1) * T + APRON)
                val k = ty * cols + tx
                var bmp = tiles[k]
                if (bmp == null) {
                    bmp = Bitmap.createBitmap(ex1 - ex0, ey1 - ey0, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(px, ey0 * width + ex0, width, 0, 0, ex1 - ex0, ey1 - ey0)
                    tiles[k] = bmp
                    continue
                }
                val ix0 = max(ex0, x0); val iy0 = max(ey0, y0); val ix1 = min(ex1, x1); val iy1 = min(ey1, y1)
                if (ix1 > ix0 && iy1 > iy0) bmp.setPixels(px, iy0 * width + ix0, width, ix0 - ex0, iy0 - ey0, ix1 - ix0, iy1 - iy0)
            }
        }
        version++
    }

    /** Fills every tile from [px] (used by overlays that cover the whole picture). */
    fun updateAll(px: IntArray) = update(px, intArrayOf(0, 0, width, height))

    fun clear() {
        if (tiles.any { it != null }) { tiles.fill(null); version++ }
    }

    /** Draws the tiles scaled to [dstW]×[dstH] (the picture's size on [canvas]). */
    fun draw(canvas: Canvas, dstW: Float, dstH: Float) {
        val sx = dstW / width; val sy = dstH / height
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val bmp = tiles[ty * cols + tx] ?: continue
            val ex0 = max(0, tx * T - APRON); val ey0 = max(0, ty * T - APRON)
            val ix0 = tx * T; val iy0 = ty * T
            val ix1 = min(width, ix0 + T); val iy1 = min(height, iy0 + T)
            src.set(ix0 - ex0, iy0 - ey0, ix1 - ex0, iy1 - ey0)
            dst.set(ix0 * sx, iy0 * sy, ix1 * sx, iy1 * sy)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }

    companion object {
        const val T = 256
        const val APRON = 1
    }
}
