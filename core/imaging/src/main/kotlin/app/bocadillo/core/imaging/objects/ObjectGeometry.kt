package app.bocadillo.core.imaging.objects

import app.bocadillo.core.model.ObjectTransform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 2D affine matrix [a c tx; b d ty] mapping (x,y) → (a x + c y + tx, b x + d y + ty). */
data class Affine2D(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    fun map(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + tx) to (b * x + d * y + ty)

    fun inverse(): Affine2D {
        val det = a * d - b * c
        require(kotlin.math.abs(det) > 1e-12) { "Singular matrix" }
        val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
        return Affine2D(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    /** this ∘ o (o applied first). */
    operator fun times(o: Affine2D) = Affine2D(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.tx + c * o.ty + tx, b * o.tx + d * o.ty + ty,
    )

    /** Values in android.graphics.Matrix.setValues order (3x3 row-major). */
    fun toMatrixValues(): FloatArray = floatArrayOf(a.toFloat(), c.toFloat(), tx.toFloat(), b.toFloat(), d.toFloat(), ty.toFloat(), 0f, 0f, 1f)

    companion object {
        val IDENTITY = Affine2D(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double) = Affine2D(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(sx: Double, sy: Double) = Affine2D(sx, 0.0, 0.0, sy, 0.0, 0.0)
        fun rotateDeg(deg: Double): Affine2D {
            val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Affine2D(c, s, -s, c, 0.0, 0.0)
        }
    }
}

/**
 * Object placement math shared by the renderer (Android) and the touch handling.
 * Local object space: origin at the object's centre, units in canvas pixels before the
 * object's own scale/stretch/rotation.
 */
object ObjectGeometry {

    /** The u unit in pixels for a canvas of w×h. */
    fun unit(w: Float, h: Float): Float = minOf(w, h)

    /** Local → canvas pixels. */
    fun toCanvas(t: ObjectTransform, w: Float, h: Float): Affine2D =
        Affine2D.translate((t.cx * w).toDouble(), (t.cy * h).toDouble()) *
            Affine2D.rotateDeg(t.rotation.toDouble()) *
            Affine2D.scale(
                (t.scale * t.stretchX * (if (t.flipH) -1 else 1)).toDouble(),
                (t.scale * t.stretchY * (if (t.flipV) -1 else 1)).toDouble(),
            ) *
            Affine2D(1.0, t.skewY.toDouble(), t.skewX.toDouble(), 1.0, 0.0, 0.0)

    /** Local box corners (TL, TR, BR, BL) with the perspective offsets applied, local px. */
    fun localCorners(t: ObjectTransform, hw: Float, hh: Float): List<Pair<Float, Float>> {
        val base = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        if (!t.hasPerspective) return base
        return base.mapIndexed { i, (x, y) -> (x + t.perspective[2 * i] * hw) to (y + t.perspective[2 * i + 1] * hh) }
    }

    /** Canvas positions of the four corners (TL, TR, BR, BL), skew and perspective included. */
    fun corners(t: ObjectTransform, w: Float, h: Float, hw: Float, hh: Float): List<Pair<Float, Float>> {
        val m = toCanvas(t, w, h)
        return localCorners(t, hw, hh).map { (x, y) -> m.map(x.toDouble(), y.toDouble()).let { it.first.toFloat() to it.second.toFloat() } }
    }

    /** True if the four points make a convex, non-degenerate quadrilateral (a valid perspective). */
    fun isConvexQuad(q: List<Pair<Float, Float>>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val (ax, ay) = q[i]; val (bx, by) = q[(i + 1) % 4]; val (cx, cy) = q[(i + 2) % 4]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (kotlin.math.abs(cross) < 1e-3f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Point inside the (convex) quad, or within [slop] px of it. */
    fun hitQuad(q: List<Pair<Float, Float>>, x: Float, y: Float, slop: Float): Boolean {
        var inside = true
        var sign = 0
        for (i in 0 until 4) {
            val (ax, ay) = q[i]; val (bx, by) = q[(i + 1) % 4]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) inside = false
        }
        if (inside) return true
        // near an edge
        for (i in 0 until 4) {
            val (ax, ay) = q[i]; val (bx, by) = q[(i + 1) % 4]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val k = if (len2 < 1e-6f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(x - (ax + k * dx), y - (ay + k * dy)) <= slop) return true
        }
        return false
    }

    /**
     * Skew handle: the finger at canvas (x,y) becomes the new place of the box's bottom-right
     * corner, solved for the shear (other parts of the transform unchanged).
     */
    fun dragSkew(t: ObjectTransform, w: Float, h: Float, hw: Float, hh: Float, x: Float, y: Float): ObjectTransform {
        val (lx, ly) = toLocal(t.copy(skewX = 0f, skewY = 0f), w, h, x, y)
        if (hw < 1f || hh < 1f) return t
        val kx = ((lx - hw) / hh).coerceIn(-2f, 2f); val ky = ((ly - hh) / hw).coerceIn(-2f, 2f)
        // the shear must stay invertible (1 − kx·ky far from 0), or the object would collapse to a line
        return if (1f - kx * ky < 0.15f) t else t.copy(skewX = kx, skewY = ky)
    }

    /** Perspective: moves corner [index] (0 TL, 1 TR, 2 BR, 3 BL) to canvas (x,y); invalid (non-convex) results are refused. */
    fun dragCorner(t: ObjectTransform, w: Float, h: Float, hw: Float, hh: Float, index: Int, x: Float, y: Float): ObjectTransform {
        if (hw < 1f || hh < 1f) return t
        val noPersp = t.copy(perspective = emptyList())
        val (lx, ly) = toLocal(noPersp, w, h, x, y)
        val base = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        val p = (if (t.perspective.size == 8) t.perspective else List(8) { 0f }).toMutableList()
        p[2 * index] = ((lx - base[index].first) / hw).coerceIn(-1.6f, 1.6f)
        p[2 * index + 1] = ((ly - base[index].second) / hh).coerceIn(-1.6f, 1.6f)
        val next = t.copy(perspective = p)
        return if (isConvexQuad(localCorners(next, hw, hh))) next else t
    }

    /** Canvas point → local coordinates. */
    fun toLocal(t: ObjectTransform, w: Float, h: Float, x: Float, y: Float): Pair<Float, Float> {
        val (lx, ly) = toCanvas(t, w, h).inverse().map(x.toDouble(), y.toDouble())
        return lx.toFloat() to ly.toFloat()
    }

    /**
     * True if canvas point (x,y) is inside the object's local box of half-size (hw,hh),
     * enlarged by [slopPx] screen pixels (constant regardless of the object's scale).
     */
    fun hit(t: ObjectTransform, w: Float, h: Float, hw: Float, hh: Float, x: Float, y: Float, slopPx: Float): Boolean {
        val (lx, ly) = toLocal(t, w, h, x, y)
        val sx = kotlin.math.abs(t.scale * t.stretchX).coerceAtLeast(1e-4f)
        val sy = kotlin.math.abs(t.scale * t.stretchY).coerceAtLeast(1e-4f)
        return kotlin.math.abs(lx) <= hw + slopPx / sx && kotlin.math.abs(ly) <= hh + slopPx / sy
    }

    /** Canvas position of the scale/rotate handle (bottom-right corner of the box). */
    fun handlePosition(t: ObjectTransform, w: Float, h: Float, hw: Float, hh: Float): Pair<Float, Float> {
        val (x, y) = toCanvas(t, w, h).map(hw.toDouble(), hh.toDouble())
        return x.toFloat() to y.toFloat()
    }

    /**
     * Dragging the corner handle from [from] to [to] (canvas px) rotates and scales the object
     * around its centre — the original "Scale & Rotate Tool" behaviour.
     */
    fun dragHandle(t: ObjectTransform, w: Float, h: Float, from: Pair<Float, Float>, to: Pair<Float, Float>): ObjectTransform {
        val cx = t.cx * w; val cy = t.cy * h
        val d0 = hypot(from.first - cx, from.second - cy); val d1 = hypot(to.first - cx, to.second - cy)
        if (d0 < 1f || d1 < 1f) return t
        val a0 = atan2(from.second - cy, from.first - cx); val a1 = atan2(to.second - cy, to.first - cx)
        val scale = (t.scale * d1 / d0).coerceIn(ObjectTransform.MIN_SCALE, ObjectTransform.MAX_SCALE)
        return t.copy(scale = scale, rotation = normalizeDeg(t.rotation + Math.toDegrees((a1 - a0).toDouble()).toFloat()))
    }

    /** Applies a two-finger gesture: pan (px), zoom factor and rotation (degrees). */
    fun gesture(t: ObjectTransform, w: Float, h: Float, panX: Float, panY: Float, zoom: Float, rotationDeg: Float): ObjectTransform =
        t.copy(
            cx = t.cx + panX / w, cy = t.cy + panY / h,
            scale = (t.scale * zoom).coerceIn(ObjectTransform.MIN_SCALE, ObjectTransform.MAX_SCALE),
            rotation = normalizeDeg(t.rotation + rotationDeg),
        )

    /** Snaps rotation to multiples of 90° when within [toleranceDeg]. */
    fun snapRotation(deg: Float, toleranceDeg: Float = 4f): Float {
        val n = Math.round(deg / 90f) * 90f
        return if (kotlin.math.abs(deg - n) <= toleranceDeg) normalizeDeg(n) else deg
    }

    /**
     * "Title guides": snaps the centre to the canvas centre lines and thirds when within
     * [tolerance] (normalized). Returns the snapped centre and which guides are active.
     */
    fun snapCenter(cx: Float, cy: Float, tolerance: Float = 0.015f): Triple<Float, Float, List<Float>> {
        val guides = floatArrayOf(1f / 3f, 0.5f, 2f / 3f)
        var sx = cx; var sy = cy
        val active = ArrayList<Float>()
        guides.firstOrNull { kotlin.math.abs(it - cx) <= tolerance }?.let { sx = it; active += it }
        guides.firstOrNull { kotlin.math.abs(it - cy) <= tolerance }?.let { sy = it; active += -it }
        return Triple(sx, sy, active)
    }

    fun normalizeDeg(d: Float): Float {
        var r = d % 360f
        if (r > 180f) r -= 360f
        if (r <= -180f) r += 360f
        return r
    }
}
