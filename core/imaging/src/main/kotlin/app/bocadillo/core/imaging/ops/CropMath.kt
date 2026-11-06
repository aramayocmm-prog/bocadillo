package app.bocadillo.core.imaging.ops

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interaction math for the crop rectangle (kept free of UI types so it is unit-tested).
 * Rects are normalized [l,t,r,b]; [w]/[h] are the pixel size of the picture being cropped so
 * aspect ratios are respected in pixel space.
 */
object CropMath {
    const val LEFT = 1
    const val TOP = 2
    const val RIGHT = 4
    const val BOTTOM = 8
    const val MOVE = 16

    /**
     * Which handle a touch at normalized (x,y) grabs. [tolX]/[tolY] are the touch slop in
     * normalized units. Corners win over edges, edges over moving.
     */
    fun hit(rect: FloatArray, x: Float, y: Float, tolX: Float, tolY: Float): Int {
        var m = 0
        val inY = y in rect[1] - tolY..rect[3] + tolY
        val inX = x in rect[0] - tolX..rect[2] + tolX
        if (inY && abs(x - rect[0]) <= tolX) m = m or LEFT
        if (inY && abs(x - rect[2]) <= tolX) m = m or RIGHT
        if (inX && abs(y - rect[1]) <= tolY) m = m or TOP
        if (inX && abs(y - rect[3]) <= tolY) m = m or BOTTOM
        if (m and LEFT != 0 && m and RIGHT != 0) m = m and (if (abs(x - rect[0]) < abs(x - rect[2])) RIGHT else LEFT).inv()
        if (m and TOP != 0 && m and BOTTOM != 0) m = m and (if (abs(y - rect[1]) < abs(y - rect[3])) BOTTOM else TOP).inv()
        if (m == 0 && x in rect[0]..rect[2] && y in rect[1]..rect[3]) m = MOVE
        return m
    }

    /**
     * Applies a drag of (dx,dy) normalized units to the grabbed [handles].
     * With a [ratio] (pixel width / height) the rect keeps that aspect, anchored at the
     * opposite side. The result always stays inside [bounds] = [l,t,r,b] and is at least
     * [minSize] (normalized) wide/high.
     */
    fun drag(
        rect: FloatArray, handles: Int, dx: Float, dy: Float, ratio: Float?, w: Int, h: Int,
        bounds: FloatArray = floatArrayOf(0f, 0f, 1f, 1f), minSize: Float = 0.05f,
    ): FloatArray {
        var (l, t, r, b) = rect.toList()
        if (handles == MOVE) {
            val mdx = dx.coerceIn(bounds[0] - l, bounds[2] - r)
            val mdy = dy.coerceIn(bounds[1] - t, bounds[3] - b)
            return floatArrayOf(l + mdx, t + mdy, r + mdx, b + mdy)
        }
        if (handles and LEFT != 0) l = (l + dx).coerceIn(bounds[0], r - minSize)
        if (handles and RIGHT != 0) r = (r + dx).coerceIn(l + minSize, bounds[2])
        if (handles and TOP != 0) t = (t + dy).coerceIn(bounds[1], b - minSize)
        if (handles and BOTTOM != 0) b = (b + dy).coerceIn(t + minSize, bounds[3])
        if (ratio == null) return floatArrayOf(l, t, r, b)

        // normalized height that matches a normalized width for the pixel ratio
        val k = w.toFloat() / (h * ratio)
        val horizontalDriven = handles and (LEFT or RIGHT) != 0
        var nw = r - l
        var nh = b - t
        if (horizontalDriven) nh = nw * k else nw = nh / k
        // vertical anchor: opposite edge if a vertical handle is grabbed, else keep centre
        val cy = (rect[1] + rect[3]) / 2; val cx = (rect[0] + rect[2]) / 2
        fun place(nw: Float, nh: Float): FloatArray {
            val nl = when { handles and LEFT != 0 -> rect[2] - nw; handles and RIGHT != 0 -> rect[0]; else -> cx - nw / 2 }
            val nt = when { handles and TOP != 0 -> rect[3] - nh; handles and BOTTOM != 0 -> rect[1]; else -> cy - nh / 2 }
            return floatArrayOf(nl, nt, nl + nw, nt + nh)
        }
        var out = place(nw, nh)
        // shrink uniformly until it fits the bounds
        val over = max(
            max(bounds[0] - out[0], out[2] - bounds[2]) / max(1e-6f, nw),
            max(bounds[1] - out[1], out[3] - bounds[3]) / max(1e-6f, nh),
        )
        if (over > 0f) {
            val s = max(0f, 1f - 2f * over)
            nw *= s; nh *= s
            out = place(nw, nh)
            // final clamp by translation (numerical safety)
            val sx = min(0f, bounds[2] - out[2]) + max(0f, bounds[0] - out[0])
            val sy = min(0f, bounds[3] - out[3]) + max(0f, bounds[1] - out[1])
            out = floatArrayOf(out[0] + sx, out[1] + sy, out[2] + sx, out[3] + sy)
        }
        if (out[2] - out[0] < minSize || out[3] - out[1] < minSize) return rect.copyOf()
        return out
    }
}
