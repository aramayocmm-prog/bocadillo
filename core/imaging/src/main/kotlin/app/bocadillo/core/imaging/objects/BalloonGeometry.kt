package app.bocadillo.core.imaging.objects

import app.bocadillo.core.model.BalloonShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Outline of a word balloon in local pixels (centre at 0,0).
 * @property contour closed polygon [x0,y0,x1,y1,…] of the body with the tail merged in, so a
 *  single stroke has no seam between body and tail.
 * @property bubbles extra circles [x,y,r,…] (the "thought" tail).
 * @property dashed stroke should be dashed (whisper).
 * @property textBox half-width/half-height of the box where text fits.
 */
class BalloonOutline(val contour: FloatArray, val bubbles: FloatArray, val dashed: Boolean, val textHalfW: Float, val textHalfH: Float)

/**
 * Builds the six balloon shapes of the original: Speech, Thought, Scream, Whisper, Rectangle and
 * Rounded. The tail tip can be anywhere; if it falls inside the body no tail is drawn.
 */
object BalloonGeometry {

    fun outline(shape: BalloonShape, w: Float, h: Float, tipX: Float, tipY: Float, tailWidth: Float, hasTail: Boolean): BalloonOutline {
        val rx = w / 2f; val ry = h / 2f
        val body: FloatArray = when (shape) {
            BalloonShape.SPEECH, BalloonShape.WHISPER -> ellipse(rx, ry, 144)
            BalloonShape.RECTANGLE, BalloonShape.CAPTION, BalloonShape.POINTER -> roundRect(rx, ry, 0f)
            BalloonShape.ROUNDED -> roundRect(rx, ry, min(rx, ry) * 0.45f)
            BalloonShape.SCREAM -> burst(rx, ry)
            BalloonShape.THOUGHT -> cloud(rx, ry)
        }
        val textScale = when (shape) {
            BalloonShape.SPEECH, BalloonShape.WHISPER -> 0.70f
            BalloonShape.RECTANGLE, BalloonShape.CAPTION, BalloonShape.POINTER -> 0.88f
            BalloonShape.ROUNDED -> 0.82f
            BalloonShape.SCREAM -> 0.58f
            BalloonShape.THOUGHT -> 0.64f
        }
        // a caption box never has a tail
        val tipOutside = hasTail && shape != BalloonShape.CAPTION && !inside(body, tipX, tipY) && hypot(tipX, tipY) > 1f
        val dashed = shape == BalloonShape.WHISPER
        if (!tipOutside) return BalloonOutline(body, FloatArray(0), dashed, rx * textScale, ry * textScale)
        if (shape == BalloonShape.THOUGHT) {
            return BalloonOutline(body, thoughtBubbles(body, rx, ry, tipX, tipY), false, rx * textScale, ry * textScale)
        }
        val baseHalf = tailWidth.coerceIn(0.05f, 0.9f) * min(rx, ry)
        if (shape == BalloonShape.POINTER) {
            return BalloonOutline(pointerTail(roundRect(rx, ry, 0f), tipX, tipY, baseHalf), FloatArray(0), false, rx * textScale, ry * textScale)
        }
        val merged = insertTail(body, tipX, tipY, baseHalf, jagged = shape == BalloonShape.SCREAM)
        return BalloonOutline(merged, FloatArray(0), dashed, rx * textScale, ry * textScale)
    }

    /**
     * Pointer: a straight shaft that leaves the rectangle where the line centre→tip crosses it
     * and ends in an arrow head at the tip. Built directly on the rectangle outline.
     */
    private fun pointerTail(body: FloatArray, tipX: Float, tipY: Float, baseHalf: Float): FloatArray {
        val n = body.size / 2
        val len = hypot(tipX, tipY)
        val ux = tipX / len; val uy = tipY / len
        // where the centre→tip ray leaves the body: nearest vertex along that direction
        var k = 0; var best = -Float.MAX_VALUE
        for (i in 0 until n) {
            val d = body[i * 2] * ux + body[i * 2 + 1] * uy - abs(body[i * 2] * uy - body[i * 2 + 1] * ux) * 4f
            if (d > best) { best = d; k = i }
        }
        val hx = body[k * 2]; val hy = body[k * 2 + 1]
        val dist = hypot(tipX - hx, tipY - hy)
        if (dist < 4f) return body
        val vx = (tipX - hx) / dist; val vy = (tipY - hy) / dist   // shaft direction
        val w = baseHalf * 0.38f; val head = baseHalf * 1.05f
        val headLen = min(dist * 0.45f, baseHalf * 2.2f)
        val ex = tipX - vx * headLen; val ey = tipY - vy * headLen
        // edge direction at k (polygon order) and the matching side of the shaft
        val k1 = (k + 1) % n; val k0 = (k - 1 + n) % n
        var tx = body[k1 * 2] - body[k0 * 2]; var ty = body[k1 * 2 + 1] - body[k0 * 2 + 1]
        val tl = hypot(tx, ty).coerceAtLeast(1e-3f); tx /= tl; ty /= tl
        var mx = -vy; var my = vx
        if (mx * tx + my * ty < 0) { mx = -mx; my = -my }
        // drop the body vertices covered by the shaft base
        val keep = (0 until n).filter { i -> i == k || hypot(body[i * 2] - hx, body[i * 2 + 1] - hy) > w * 1.2f }
        val out = ArrayList<Float>(keep.size * 2 + 14)
        for (i in keep) {
            if (i == k) {
                floatArrayOf(
                    hx - tx * w, hy - ty * w, ex - mx * w, ey - my * w, ex - mx * head, ey - my * head, tipX, tipY,
                    ex + mx * head, ey + my * head, ex + mx * w, ey + my * w, hx + tx * w, hy + ty * w,
                ).forEach { out += it }
            } else { out += body[i * 2]; out += body[i * 2 + 1] }
        }
        return out.toFloatArray()
    }

    // ---------------------------------------------------------------- bodies

    fun ellipse(rx: Float, ry: Float, n: Int): FloatArray = FloatArray(n * 2).also { p ->
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            p[2 * i] = (rx * cos(a)).toFloat(); p[2 * i + 1] = (ry * sin(a)).toFloat()
        }
    }

    /** Rectangle (r = 0) or rounded rectangle sampled densely, clockwise from the right-middle. */
    fun roundRect(rx: Float, ry: Float, r: Float): FloatArray {
        if (r <= 0f) return rectExact(rx, ry)
        val pts = ArrayList<Float>()
        fun add(x: Float, y: Float) { pts += x; pts += y }
        val steps = 16
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            val len = hypot(x1 - x0, y1 - y0)
            val n = max(1, (len / (min(rx, ry) / 8f)).toInt())
            for (i in 0 until n) add(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n)
        }
        fun corner(cx: Float, cy: Float, a0: Double) {
            for (i in 0 until steps) {
                val a = a0 + (PI / 2) * i / steps
                add((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            }
        }
        edge(rx, 0f, rx, ry - r)
        corner(rx - r, ry - r, 0.0)
        edge(rx - r, ry, -rx + r, ry)
        corner(-rx + r, ry - r, PI / 2)
        edge(-rx, ry - r, -rx, -ry + r)
        corner(-rx + r, -ry + r, PI)
        edge(-rx + r, -ry, rx - r, -ry)
        corner(rx - r, -ry + r, 3 * PI / 2)
        edge(rx, -ry + r, rx, 0f)
        return pts.toFloatArray()
    }

    private fun rectExact(rx: Float, ry: Float): FloatArray {
        val pts = ArrayList<Float>()
        val corners = listOf(rx to ry, -rx to ry, -rx to -ry, rx to -ry)
        var prev = rx to 0f
        val n = 24
        for (c in corners + listOf(rx to 0f)) {
            for (i in 0 until n) { pts += prev.first + (c.first - prev.first) * i / n; pts += prev.second + (c.second - prev.second) * i / n }
            prev = c
        }
        return pts.toFloatArray()
    }

    /** Spiky "scream" burst: deterministic irregular spikes around an ellipse. */
    fun burst(rx: Float, ry: Float): FloatArray {
        val spikes = 16
        val jitter = floatArrayOf(0.0f, 0.06f, -0.04f, 0.08f, -0.02f, 0.05f, -0.06f, 0.03f)
        val p = FloatArray(spikes * 4)
        for (i in 0 until spikes * 2) {
            val a = 2 * PI * i / (spikes * 2) + jitter[i % jitter.size] * 0.15
            val k = if (i % 2 == 0) 1.0f + jitter[(i / 2) % jitter.size] else 0.74f
            p[2 * i] = (rx * k * cos(a)).toFloat(); p[2 * i + 1] = (ry * k * sin(a)).toFloat()
        }
        return p
    }

    /** "Thought" cloud: outward arcs (bumps) between points on an ellipse. */
    fun cloud(rx: Float, ry: Float): FloatArray {
        val bumps = 11
        val pts = ArrayList<Float>()
        val base = Array(bumps) { i -> val a = 2 * PI * i / bumps; (rx * 0.86 * cos(a)) to (ry * 0.86 * sin(a)) }
        for (i in 0 until bumps) {
            val (x0, y0) = base[i]; val (x1, y1) = base[(i + 1) % bumps]
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            val chord = hypot(x1 - x0, y1 - y0)
            val rr = chord / 2
            // outward normal
            val nl = hypot(mx, my).coerceAtLeast(1e-6)
            val nx = mx / nl; val ny = my / nl
            val a0 = atan2(y0 - my, x0 - mx)
            // sweep the half circle through the outward side
            val steps = 14
            val dir = if (nx * cos(a0 + PI / 2) + ny * sin(a0 + PI / 2) > 0) 1 else -1
            for (s in 0 until steps) {
                val a = a0 + dir * PI * s / steps
                pts += (mx + rr * 1.0 * cos(a)).toFloat(); pts += (my + rr * 1.0 * sin(a)).toFloat()
            }
        }
        return pts.toFloatArray()
    }

    // ---------------------------------------------------------------- tails

    /** Point-in-polygon (even-odd). */
    fun inside(poly: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]; val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Index of the contour point whose direction from the centre is closest to the tip's. */
    private fun nearestByAngle(poly: FloatArray, tipX: Float, tipY: Float): Int {
        val ta = atan2(tipY, tipX)
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in 0 until poly.size / 2) {
            var d = abs(atan2(poly[2 * i + 1], poly[2 * i]) - ta).toDouble()
            if (d > PI) d = 2 * PI - d
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * Cuts the body contour around the point facing the tip (±[baseHalf] along the perimeter)
     * and inserts a slightly curved (or jagged for screams) tail reaching the tip.
     */
    fun insertTail(body: FloatArray, tipX: Float, tipY: Float, baseHalf: Float, jagged: Boolean): FloatArray {
        val n = body.size / 2
        val c = nearestByAngle(body, tipX, tipY)
        fun walk(dir: Int): Int {
            var i = c; var acc = 0f
            while (acc < baseHalf) {
                val j = ((i + dir) % n + n) % n
                acc += hypot(body[2 * j] - body[2 * i], body[2 * j + 1] - body[2 * i + 1])
                i = j
                if (i == c) break
            }
            return i
        }
        val ia = walk(-1); val ib = walk(+1)
        val out = ArrayList<Float>(body.size + 64)
        // body from ib going forward to ia (skipping the cut-out part)
        var i = ib
        while (true) {
            out += body[2 * i]; out += body[2 * i + 1]
            if (i == ia) break
            i = (i + 1) % n
        }
        val ax = body[2 * ia]; val ay = body[2 * ia + 1]; val bx = body[2 * ib]; val by = body[2 * ib + 1]
        // ia → tip → ib, each side a quadratic curve bent slightly the same way (comic style)
        val bend = 0.18f
        fun side(x0: Float, y0: Float, x1: Float, y1: Float, sign: Float) {
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            val dx = x1 - x0; val dy = y1 - y0
            val cxp = mx - dy * bend * sign; val cyp = my + dx * bend * sign
            val steps = 14
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                var x = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * cxp + t * t * x1
                var y = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * cyp + t * t * y1
                if (jagged && s < steps && s % 3 == 0) { x += -dy * 0.05f * sign; y += dx * 0.05f * sign }
                out += x; out += y
            }
        }
        side(ax, ay, tipX, tipY, 1f)
        side(tipX, tipY, bx, by, 1f)
        out.removeAt(out.size - 1); out.removeAt(out.size - 1) // last point == ib (start)
        return out.toFloatArray()
    }

    /** Three shrinking circles from the cloud edge towards the tip. */
    private fun thoughtBubbles(body: FloatArray, rx: Float, ry: Float, tipX: Float, tipY: Float): FloatArray {
        val c = nearestByAngle(body, tipX, tipY)
        val ex = body[2 * c]; val ey = body[2 * c + 1]
        val r0 = min(rx, ry) * 0.16f
        val out = FloatArray(9)
        val ts = floatArrayOf(0.25f, 0.6f, 1.0f)
        val rs = floatArrayOf(r0, r0 * 0.66f, r0 * 0.42f)
        for (k in 0 until 3) {
            out[3 * k] = ex + (tipX - ex) * ts[k]; out[3 * k + 1] = ey + (tipY - ey) * ts[k]; out[3 * k + 2] = rs[k]
        }
        return out
    }

    /** Polygon area (positive) — used by tests to sanity-check shapes. */
    fun area(poly: FloatArray): Float {
        var s = 0.0
        val n = poly.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; s += poly[2 * i] * poly[2 * j + 1] - poly[2 * j] * poly[2 * i + 1] }
        return abs(s / 2).toFloat()
    }
}
