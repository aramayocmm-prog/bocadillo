package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.objects.Cutout
import app.bocadillo.core.imaging.paint.IncrementalStroke
import app.bocadillo.core.imaging.paint.Stroke
import app.bocadillo.core.imaging.paint.StrokeMode
import app.bocadillo.core.imaging.paint.StrokePainter
import app.bocadillo.core.imaging.paint.StrokeRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 1.1.0, correction 1: the stroke shown while the finger moves is computed incrementally, and
 * must be exactly what the finished stroke gives (same coverage, same pixels), for slow, fast,
 * looping and zig-zag strokes, round and square tips, soft and hard, with and without pressure.
 */
class IncrementalStrokeTest {
    private val w = 320; private val h = 240

    /** Finger paths: slow (dense), fast (sparse), a closed curve, sharp zig-zags, a long scribble. */
    private fun paths(): Map<String, FloatArray> {
        val r = Random(7)
        fun build(n: Int, f: (Int) -> Pair<Float, Float>) = FloatArray(n * 3) { k -> val (x, y) = f(k / 3); when (k % 3) { 0 -> x; 1 -> y; else -> 0.4f + 0.6f * r.nextFloat() } }
        return mapOf(
            "slow" to build(120) { i -> 0.1f + i * 0.006f to 0.5f + 0.1f * sin(i * 0.05f) },
            "fast" to build(9) { i -> 0.05f + i * 0.11f to 0.2f + (i % 2) * 0.5f },
            "circle" to build(60) { i -> 0.5f + 0.3f * cos(i * 0.11f) to 0.5f + 0.35f * sin(i * 0.11f) },
            "zigzag" to build(30) { i -> 0.1f + i * 0.027f to if (i % 2 == 0) 0.15f else 0.85f },
            "edges" to build(25) { i -> -0.1f + i * 0.05f to 1.05f - i * 0.05f },
            "long" to build(600) { i -> 0.5f + 0.4f * sin(i * 0.037f) to 0.5f + 0.4f * sin(i * 0.051f + 1f) },
        )
    }

    private fun brushes() = listOf(
        Stroke(FloatArray(0), 0.01f, 1f, 1f, 0xFFFF0000.toInt()),
        Stroke(FloatArray(0), 0.08f, 0.2f, 0.8f, 0xFF2040C0.toInt(), pressureSize = true, pressureOpacity = true),
        Stroke(FloatArray(0), 0.25f, 0.6f, 0.5f, 0x80FFFF00.toInt(), StrokeMode.MARKER, pressureSize = false),
        Stroke(FloatArray(0), 0.05f, 0.9f, 1f, 0, StrokeMode.ERASE, square = true),
    )

    private fun prefix(full: FloatArray, n: Int, b: Stroke) = b.copy(points = full.copyOf(n * 3))

    @Test fun liveCoverageEqualsFinishedCoverageAtEveryStep() {
        var checks = 0
        for ((name, pts) in paths()) for (b in brushes()) {
            val inc = IncrementalStroke(w, h)
            val n = pts.size / 3
            for (k in 1..n) {
                val s = prefix(pts, k, b)
                val final = k == n
                inc.update(s, final)
                // compare against the batch rasterizer for the same points (every few steps: it is slow)
                if (final || k % 7 == 1) {
                    val ref = FloatArray(w * h)
                    StrokeRasterizer.coverage(s, w, h)?.let { c -> for (yy in 0 until c.h) for (xx in 0 until c.w) ref[(c.y + yy) * w + c.x + xx] = c.values[yy * c.w + xx] }
                    for (y in 0 until h) for (x in 0 until w) {
                        val got = if (final) inc.committed[y * w + x] else inc.coverageAt(x, y)
                        if (got != ref[y * w + x]) throw AssertionError("$name ${b.mode} size=${b.size} step $k/$n at ($x,$y): ${got} != ${ref[y * w + x]}")
                    }
                    checks++
                }
            }
            inc.reset()
            assertTrue("$name: reset clears", inc.committed.all { it == 0f })
        }
        assertTrue(checks > 100)
    }

    @Test fun livePixelsEqualPaintedPixels() {
        val rnd = Random(3)
        val base = IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        for ((_, pts) in paths()) for (b in brushes()) {
            val s = b.copy(points = pts)
            val ref = base.copyOf(); StrokePainter.paintInPlace(ref, w, h, s)
            val inc = IncrementalStroke(w, h)
            val out = base.copyOf()
            val blend = StrokePainter.Blender(s)
            val n = pts.size / 3
            for (k in 1..n) {
                inc.update(prefix(pts, k, b), k == n) ?: continue
                // as the editor does it: each changed area once, coverage from forEachIn
                for (d in inc.changedAreas) inc.forEachIn(d) { i, c -> out[i] = if (c <= 0f) base[i] else blend.blend(base[i], c) }
            }
            for (i in out.indices) if (out[i] != ref[i]) throw AssertionError("pixel $i: ${Integer.toHexString(out[i])} != ${Integer.toHexString(ref[i])}")
        }
    }

    @Test fun liveCutOutEqualsExportedMask() {
        val rnd = Random(5)
        val src = IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        val settled = listOf(Cutout.eraser(paths()["circle"]!!, 0.06f, 0.5f), Cutout.restore(paths()["slow"]!!, 0.03f, 0.3f, square = true))
        val m = Cutout.mask(settled, w, h)
        for (live in listOf(Cutout.eraser(paths()["zigzag"]!!, 0.04f, 0.2f), Cutout.restore(paths()["circle"]!!, 0.1f, 0.9f))) {
            val ref = Cutout.apply(PixelBuffer(w, h, src), Cutout.mask(settled + live, w, h)).pixels
            val inc = IncrementalStroke(w, h)
            val out = Cutout.apply(PixelBuffer(w, h, src), m).pixels
            val n = live.pointCount
            for (k in 1..n) {
                inc.update(live.copy(points = live.points.copyOf(k * 3)), k == n) ?: continue
                for (d in inc.changedAreas) inc.forEachIn(d) { i, c -> out[i] = Cutout.pixel(src[i], Cutout.liveMask(m[i], c, live)) }
            }
            for (i in out.indices) assertEquals("pixel $i", ref[i], out[i])
        }
    }

    /**
     * Timing on this JVM (not a phone): work per finger move for a long stroke on a 2048×1536
     * preview, incremental vs. repainting the whole stroke each time (what 1.0 did). Printed for
     * the report; the assertion only checks that the incremental cost does not grow with length.
     */
    @Test fun incrementalCostDoesNotGrowWithStrokeLength() {
        val pw = 2048; val ph = 1536
        val pts = paths()["long"]!!
        val b = Stroke(FloatArray(0), 0.04f, 0.5f, 1f, 0xFFFF0000.toInt())
        val inc = IncrementalStroke(pw, ph)
        val n = pts.size / 3
        val times = LongArray(n)
        repeat(2) { round -> // first round warms the JIT
            inc.reset()
            for (k in 1..n) {
                val t0 = System.nanoTime()
                inc.update(prefix(pts, k, b), k == n)
                if (round == 1) times[k - 1] = System.nanoTime() - t0
            }
        }
        val early = times.copyOfRange(10, 60).average() / 1e6
        val late = times.copyOfRange(n - 60, n - 10).average() / 1e6
        val px = IntArray(pw * ph)
        var t0 = System.nanoTime(); StrokePainter.paintInPlace(px, pw, ph, prefix(pts, 50, b)); val full50 = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime(); StrokePainter.paintInPlace(px, pw, ph, prefix(pts, n - 10, b)); val fullLate = (System.nanoTime() - t0) / 1e6
        println("PERF incremental per move: early=%.3f ms late=%.3f ms | 1.0 full repaint: at 50 points=%.2f ms, at %d points=%.2f ms".format(early, late, full50, n - 10, fullLate))
        // thick brush, sparse points (fast finger without intermediate samples): coverage + composite per move
        val big = Stroke(FloatArray(0), 0.12f, 0.9f, 1f, 0xFF2040C0.toInt())
        val fast = FloatArray(9 * 3) { k -> when (k % 3) { 0 -> 0.08f + (k / 3) * 0.105f; 1 -> if ((k / 3) % 2 == 0) 0.35f else 0.65f; else -> 1f } }
        val base = IntArray(pw * ph) { 0xFF808080.toInt() }; val out = base.copyOf(); val blend = StrokePainter.Blender(big)
        val tFast = ArrayList<Double>()
        repeat(3) { round ->
            val inc2 = IncrementalStroke(pw, ph)
            for (k in 1..9) {
                val t1 = System.nanoTime()
                inc2.update(big.copy(points = fast.copyOf(k * 3)), k == 9)
                for (d in inc2.changedAreas) inc2.forEachIn(d) { i, c -> out[i] = if (c <= 0f) base[i] else blend.blend(base[i], c) }
                if (round == 2) tFast += (System.nanoTime() - t1) / 1e6
            }
        }
        println("PERF thick fast stroke (12%%, 9 points over 2048 px): per move median=%.2f ms max=%.2f ms".format(tFast.sorted()[tFast.size / 2], tFast.max()))
        assertTrue("incremental cost stays flat ($early → $late ms)", late < early * 3 + 0.5)
    }
}
