package app.bocadillo.core.imaging.paint

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Coverage of the stroke in progress, updated as points arrive, without repainting what is
 * already settled (1.1.0, correction 1).
 *
 * A segment of the Catmull-Rom spline between points i and i+1 depends only on points i-1..i+2,
 * so once point i+2 exists it is final: its dabs go once into [committed]. The last one or two
 * segments and the dab at the finger are the "tail": recomputed on every update into a small
 * window. Dabs combine with max, so committed ∪ tail is exactly the coverage
 * [StrokeRasterizer.coverage] gives for the same points — the picture under the finger is the
 * picture after lifting it.
 *
 * Work per update is proportional to the new segment, not to the length of the stroke.
 */
class IncrementalStroke(val width: Int, val height: Int) {
    /** Coverage of the final segments (full raster, 0..1). */
    val committed = FloatArray(width * height)
    private var tail = FloatArray(0)
    private var tailBox: IntArray? = null // x0, y0, x1, y1 (exclusive)
    private var doneSegments = 0
    private var lastDone = false
    /** Everything this stroke touched (to clear it afterwards). */
    private var touched: IntArray? = null
    private val buf = StrokeRasterizer.FloatList(256)

    /** Coverage at pixel (x, y): settled segments and the tail. */
    fun coverageAt(x: Int, y: Int): Float {
        val c = committed[y * width + x]
        val b = tailBox ?: return c
        if (x < b[0] || x >= b[2] || y < b[1] || y >= b[3]) return c
        return max(c, tail[(y - b[1]) * (b[2] - b[0]) + (x - b[0])])
    }

    /** Area touched by the stroke so far (x0, y0, x1, y1 exclusive), or null. */
    fun touchedArea(): IntArray? = touched?.copyOf()

    /**
     * Feeds the stroke so far ([s] keeps its brush, its points only grow). With [final] the whole
     * stroke is settled (finger lifted). Returns the area whose coverage may have changed
     * (x0, y0, x1, y1 exclusive), or null.
     */
    fun update(s: Stroke, final: Boolean): IntArray? {
        val n = s.pointCount
        if (n == 0) return null
        val maxR = StrokeRasterizer.maxRadius(s, width, height)
        val step = StrokeRasterizer.spacing(maxR)
        val hard = s.hardness.coerceIn(0f, 1f)
        var dirty: IntArray? = null
        fun grow(b: IntArray?) { if (b != null) dirty = union(dirty, b) }

        // segments that can no longer change
        val finalSegments = if (final) n - 1 else max(0, n - 3)
        if (finalSegments > doneSegments) {
            buf.clear()
            for (i in doneSegments until finalSegments) StrokeRasterizer.emitSegment(s.points, n, width, height, i, step, buf)
            doneSegments = finalSegments
            grow(dabInto(committed, 0, 0, width, height, buf, s, maxR, hard))
        }
        // the old tail is replaced
        grow(tailBox)
        tailBox = null
        buf.clear()
        if (final) {
            if (!lastDone) { StrokeRasterizer.emitLast(s.points, n, width, height, buf); lastDone = true }
            grow(dabInto(committed, 0, 0, width, height, buf, s, maxR, hard))
        } else {
            for (i in doneSegments until n - 1) StrokeRasterizer.emitSegment(s.points, n, width, height, i, step, buf)
            StrokeRasterizer.emitLast(s.points, n, width, height, buf)
            val box = bounds(buf, maxR)
            if (box != null) {
                val bw = box[2] - box[0]; val bh = box[3] - box[1]
                if (tail.size < bw * bh) tail = FloatArray(bw * bh) else tail.fill(0f, 0, bw * bh)
                for (k in 0 until buf.size / 3) StrokeRasterizer.sampleDab(tail, box[0], box[1], bw, bh, buf.data[3 * k], buf.data[3 * k + 1], buf.data[3 * k + 2], s, maxR, hard)
                tailBox = box
                grow(box)
            }
        }
        dirty?.let { touched = union(touched, it) }
        return dirty
    }

    /** Clears what the stroke touched, ready for the next one. */
    fun reset() {
        touched?.let { b -> for (y in b[1] until b[3]) committed.fill(0f, y * width + b[0], y * width + b[2]) }
        touched = null; tailBox = null; doneSegments = 0; lastDone = false
    }

    private fun bounds(pts: StrokeRasterizer.FloatList, maxR: Float): IntArray? {
        if (pts.size == 0) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (k in 0 until pts.size / 3) {
            x0 = min(x0, pts.data[3 * k]); x1 = max(x1, pts.data[3 * k]); y0 = min(y0, pts.data[3 * k + 1]); y1 = max(y1, pts.data[3 * k + 1])
        }
        val bx0 = max(0, floor(x0 - maxR - 1).toInt()); val by0 = max(0, floor(y0 - maxR - 1).toInt())
        val bx1 = min(width, ceil(x1 + maxR + 1).toInt()); val by1 = min(height, ceil(y1 + maxR + 1).toInt())
        return if (bx1 <= bx0 || by1 <= by0) null else intArrayOf(bx0, by0, bx1, by1)
    }

    private fun dabInto(cov: FloatArray, ox: Int, oy: Int, bw: Int, bh: Int, pts: StrokeRasterizer.FloatList, s: Stroke, maxR: Float, hard: Float): IntArray? {
        val box = bounds(pts, maxR) ?: return null
        for (k in 0 until pts.size / 3) StrokeRasterizer.sampleDab(cov, ox, oy, bw, bh, pts.data[3 * k], pts.data[3 * k + 1], pts.data[3 * k + 2], s, maxR, hard)
        return box
    }

    companion object {
        fun union(a: IntArray?, b: IntArray): IntArray =
            if (a == null) b.copyOf() else intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    }
}
