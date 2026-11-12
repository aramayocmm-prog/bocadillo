package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.objects.BalloonGeometry
import app.bocadillo.core.imaging.objects.ObjectGeometry
import app.bocadillo.core.imaging.objects.Cutout
import app.bocadillo.core.imaging.objects.PaintRef
import app.bocadillo.core.imaging.objects.PathCmd
import app.bocadillo.core.imaging.objects.PathData
import app.bocadillo.core.imaging.objects.StickerCatalog
import app.bocadillo.core.model.BalloonShape
import app.bocadillo.core.model.ObjectTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

class ObjectGeometryTest {

    @Test fun transformRoundTripAndHitTest() {
        val t = ObjectTransform(cx = 0.25f, cy = 0.5f, scale = 2f, stretchX = 1.5f, rotation = 30f, flipH = true)
        val m = ObjectGeometry.toCanvas(t, 800f, 600f)
        val (x, y) = m.map(10.0, -20.0)
        val (lx, ly) = ObjectGeometry.toLocal(t, 800f, 600f, x.toFloat(), y.toFloat())
        assertEquals(10f, lx, 1e-3f); assertEquals(-20f, ly, 1e-3f)
        // centre always hits; far point misses
        assertTrue(ObjectGeometry.hit(t, 800f, 600f, 50f, 20f, 200f, 300f, 0f))
        assertFalse(ObjectGeometry.hit(t, 800f, 600f, 50f, 20f, 790f, 10f, 0f))
    }

    @Test fun handleDragScalesAndRotatesAroundCentre() {
        val t = ObjectTransform(0.5f, 0.5f)
        // centre (100,100) on a 200x200 canvas; drag from (150,100) to (100,200): ×2, +90°
        val r = ObjectGeometry.dragHandle(t, 200f, 200f, 150f to 100f, 100f to 200f)
        assertEquals(2f, r.scale, 1e-4f)
        assertEquals(90f, r.rotation, 1e-3f)
        assertEquals(0.5f, r.cx, 0f)
    }

    @Test fun rotationAndGuideSnapping() {
        assertEquals(90f, ObjectGeometry.snapRotation(87f), 0f)
        assertEquals(80f, ObjectGeometry.snapRotation(80f), 0f)
        val (sx, sy, g) = ObjectGeometry.snapCenter(0.505f, 0.34f)
        assertEquals(0.5f, sx, 0f); assertEquals(1f / 3f, sy, 1e-6f); assertEquals(2, g.size)
        assertEquals(-170f, ObjectGeometry.normalizeDeg(190f), 1e-4f)
    }

    @Test fun pathParserHandlesRelativeAndShorthand() {
        val p = PathData.parse("M10 10 l5 0 h5 v5 c0 5 -5 5 -5 5 s-5 0 -5 -5 Q0 0 10 10 t10 0 Z m1 1 2 2")
        assertEquals(PathCmd.Move(10f, 10f), p[0])
        assertEquals(PathCmd.Line(15f, 10f), p[1])
        assertEquals(PathCmd.Line(20f, 10f), p[2])
        assertEquals(PathCmd.Line(20f, 15f), p[3])
        assertEquals(PathCmd.Cubic(20f, 20f, 15f, 20f, 15f, 20f), p[4])
        assertEquals(PathCmd.Cubic(15f, 20f, 10f, 20f, 10f, 15f), p[5]) // reflected control point
        assertEquals(PathCmd.Quad(20f, 20f, 20f, 10f), p[7]) // T reflects Q control: (2·10−0, 2·10−0), end (10+10, 10+0)
        assertEquals(PathCmd.Close, p[8])
        assertEquals(PathCmd.Move(11f, 11f), p[9]) // after Z current point = subpath start
        assertEquals(PathCmd.Line(13f, 13f), p[10]) // implicit lineto
    }

    @Test fun balloonTailsAttachOutsideAndVanishInside() {
        for (shape in BalloonShape.entries - BalloonShape.CAPTION) { // a caption has no tail
            val with = BalloonGeometry.outline(shape, 200f, 120f, -60f, 140f, 0.3f, true)
            val without = BalloonGeometry.outline(shape, 200f, 120f, 5f, 5f, 0.3f, true)
            if (shape == BalloonShape.THOUGHT) {
                assertEquals(9, with.bubbles.size)
                assertEquals(0, without.bubbles.size)
            } else {
                // the tip is part of the merged contour
                var best = Float.MAX_VALUE
                for (i in 0 until with.contour.size / 2) best = minOf(best, hypot(with.contour[2 * i] + 60f, with.contour[2 * i + 1] - 140f))
                assertTrue("$shape tip missing ($best)", best < 1f)
                assertTrue("$shape tail adds area", BalloonGeometry.area(with.contour) > BalloonGeometry.area(without.contour))
            }
            assertTrue(with.textHalfW in 40f..100f && with.textHalfH in 20f..60f)
            assertEquals(shape == BalloonShape.WHISPER, with.dashed)
        }
    }

    @Test fun ellipseBodyAreaIsCorrect() {
        val e = BalloonGeometry.ellipse(100f, 60f, 144)
        assertEquals(Math.PI.toFloat() * 100f * 60f, BalloonGeometry.area(e), 100f * 60f * 0.01f)
        val r = BalloonGeometry.roundRect(100f, 60f, 0f)
        assertEquals(200f * 120f, BalloonGeometry.area(r), 1f)
    }

    private fun catalogFile(): File {
        val candidates = listOfNotNull(System.getProperty("bocadillo.catalog"), "app/src/main/assets/stickers/catalog.json", "../../app/src/main/assets/stickers/catalog.json")
        return candidates.map(::File).first { it.exists() }
    }

    @Test fun stickerCatalogParsesAndResolvesSlots() {
        val cat = StickerCatalog.parse(catalogFile().readText())
        assertTrue(cat.size >= 50)
        assertEquals(6, cat.categories.size)
        for (c in cat.categories) {
            assertTrue(c.name("es").isNotBlank())
            for (s in c.stickers) {
                assertTrue(s.id, s.layers.isNotEmpty() && s.slots.size == 3 && s.width > 0 && s.height > 0)
                for (l in s.layers) assertTrue(l.path.first() is PathCmd.Move)
            }
        }
        val heart = cat["heart"]!!
        assertEquals(PaintRef.Slot(0), heart.layers[0].fill)
        assertEquals(0xFF00FF00.toInt(), heart.resolve(PaintRef.Slot(0), listOf(0xFF00FF00.toInt())))
        assertEquals(heart.slots[2], heart.resolve(PaintRef.Slot(2), listOf(0xFF00FF00.toInt())))
    }

    // ---------------------------------------------------------------- visual sheet

    private fun toAwt(cmds: List<PathCmd>, ox: Double, oy: Double, s: Double, evenOdd: Boolean = false): Path2D.Double {
        val p = Path2D.Double(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
        for (c in cmds) when (c) {
            is PathCmd.Move -> p.moveTo(ox + c.x * s, oy + c.y * s)
            is PathCmd.Line -> p.lineTo(ox + c.x * s, oy + c.y * s)
            is PathCmd.Quad -> p.quadTo(ox + c.x1 * s, oy + c.y1 * s, ox + c.x * s, oy + c.y * s)
            is PathCmd.Cubic -> p.curveTo(ox + c.x1 * s, oy + c.y1 * s, ox + c.x2 * s, oy + c.y2 * s, ox + c.x * s, oy + c.y * s)
            PathCmd.Close -> p.closePath()
        }
        return p
    }

    @Test fun visualSheetOfBalloonsAndStickers() {
        val out = File(System.getProperty("bocadillo.visualOut") ?: "build/visual").apply { mkdirs() }
        val cat = StickerCatalog.parse(catalogFile().readText())
        val all = cat.categories.flatMap { it.stickers }
        val cols = 10; val cell = 110
        val rows = (all.size + cols - 1) / cols
        val img = BufferedImage(cols * cell, 300 + rows * cell, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color(0xE8F0F8); g.fillRect(0, 0, img.width, img.height)
        // balloons row
        BalloonShape.entries.forEachIndexed { i, shape ->
            val cx = 90.0 + i * 180; val cy = 110.0
            val o = BalloonGeometry.outline(shape, 150f, 95f, if (i % 2 == 0) -40f else 50f, 105f, 0.3f, true)
            val p = Path2D.Double()
            for (k in 0 until o.contour.size / 2) { val x = cx + o.contour[2 * k]; val y = cy + o.contour[2 * k + 1]; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.closePath()
            g.color = Color.WHITE; g.fill(p)
            g.color = Color.BLACK
            g.stroke = if (o.dashed) BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(9f, 7f), 0f) else BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(p)
            for (b in 0 until o.bubbles.size / 3) {
                val e = Ellipse2D.Double(cx + o.bubbles[3 * b] - o.bubbles[3 * b + 2], cy + o.bubbles[3 * b + 1] - o.bubbles[3 * b + 2], 2.0 * o.bubbles[3 * b + 2], 2.0 * o.bubbles[3 * b + 2])
                g.color = Color.WHITE; g.fill(e); g.color = Color.BLACK; g.stroke = BasicStroke(3f); g.draw(e)
            }
            g.color = Color(0x1F6FEB); g.drawRect((cx - o.textHalfW).toInt(), (cy - o.textHalfH).toInt(), (2 * o.textHalfW).toInt(), (2 * o.textHalfH).toInt())
            g.color = Color.BLACK; g.drawString(shape.name, cx.toInt() - 30, 285)
        }
        // stickers grid
        all.forEachIndexed { i, s ->
            val sc = 90.0 / maxOf(s.width, s.height)
            val ox = (i % cols) * cell + 10.0 + (90 - s.width * sc) / 2; val oy = 300 + (i / cols) * cell + 6.0 + (90 - s.height * sc) / 2
            for (l in s.layers) {
                val p = toAwt(l.path, ox, oy, sc, l.evenOdd)
                s.resolve(l.fill, emptyList())?.let { g.color = Color(it, true); g.fill(p) }
                s.resolve(l.stroke, emptyList())?.let {
                    g.color = Color(it, true); g.stroke = BasicStroke((l.strokeWidth * sc).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(p)
                }
            }
            g.color = Color.DARK_GRAY; g.font = g.font.deriveFont(9f)
            g.drawString(s.id, (i % cols) * cell + 6, 300 + (i / cols) * cell + 106)
        }
        g.dispose()
        ImageIO.write(img, "png", File(out, "stage3_balloons_stickers.png"))
        assertTrue(abs(img.width - cols * cell) == 0)
    }
}

class BalloonShapesExtraTest {
    @org.junit.Test fun captionHasNoTailAndPointerHasAnArrowHead() {
        val cap = app.bocadillo.core.imaging.objects.BalloonGeometry.outline(app.bocadillo.core.model.BalloonShape.CAPTION, 200f, 100f, 0f, 150f, 0.3f, true)
        val rect = app.bocadillo.core.imaging.objects.BalloonGeometry.outline(app.bocadillo.core.model.BalloonShape.RECTANGLE, 200f, 100f, 0f, 0f, 0.3f, false)
        org.junit.Assert.assertArrayEquals(rect.contour, cap.contour, 0.001f)
        val ptr = app.bocadillo.core.imaging.objects.BalloonGeometry.outline(app.bocadillo.core.model.BalloonShape.POINTER, 200f, 100f, 40f, 160f, 0.3f, true)
        // the farthest vertex is the arrow tip
        val far = (0 until ptr.contour.size / 2).maxBy { kotlin.math.hypot(ptr.contour[it * 2], ptr.contour[it * 2 + 1]) }
        org.junit.Assert.assertEquals(40f, ptr.contour[far * 2], 0.5f)
        org.junit.Assert.assertEquals(160f, ptr.contour[far * 2 + 1], 0.5f)
        org.junit.Assert.assertTrue(ptr.contour.size > rect.contour.size)
    }

    @Test fun skewHandleLandsOnTheFingerAndPerspectiveStaysConvex() {
        val t = ObjectTransform(0.5f, 0.5f, scale = 1.3f, rotation = 20f)
        val w = 1000f; val h = 800f; val hw = 100f; val hh = 60f
        // skew: the bottom-right corner follows the finger
        val target = 640f to 590f
        val s = ObjectGeometry.dragSkew(t, w, h, hw, hh, target.first, target.second)
        val br = ObjectGeometry.corners(s, w, h, hw, hh)[2]
        assertEquals(target.first, br.first, 0.5f); assertEquals(target.second, br.second, 0.5f)
        // perspective: corner 1 moves to the finger, others stay
        val before = ObjectGeometry.corners(t, w, h, hw, hh)
        val p = ObjectGeometry.dragCorner(t, w, h, hw, hh, 1, before[1].first + 30f, before[1].second - 20f)
        val after = ObjectGeometry.corners(p, w, h, hw, hh)
        assertEquals(before[1].first + 30f, after[1].first, 0.5f)
        assertEquals(before[0].first, after[0].first, 0.01f); assertEquals(before[3].second, after[3].second, 0.01f)
        assertTrue(ObjectGeometry.hitQuad(after, t.cx * w, t.cy * h, 0f))
        // dragging a corner across the opposite one would fold the quad: refused
        val folded = ObjectGeometry.dragCorner(t, w, h, hw, hh, 0, before[2].first + 50f, before[2].second + 50f)
        assertEquals(t, folded)
        assertFalse(ObjectGeometry.hitQuad(after, 0f, 0f, 4f))
    }

    @Test fun canvasToUnitInvertsRotationFlipAndPerspective() {
        val w = 1000f; val h = 800f; val hw = 120f; val hh = 80f
        val base = ObjectTransform(0.4f, 0.55f, scale = 1.4f, rotation = 33f, flipH = true)
        val p = ObjectGeometry.dragCorner(base, w, h, hw, hh, 1, 0f, 0f).let { ObjectGeometry.dragCorner(it, w, h, hw, hh, 3,
            ObjectGeometry.corners(it, w, h, hw, hh)[3].first - 25f, ObjectGeometry.corners(it, w, h, hw, hh)[3].second + 40f) }
        for (t in listOf(base, base.copy(skewX = 0.3f), p)) {
            val cs = ObjectGeometry.corners(t, w, h, hw, hh)
            val expect = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
            for (i in 0 until 4) {
                val (u, v) = ObjectGeometry.canvasToUnit(t, w, h, hw, hh, cs[i].first, cs[i].second)
                assertEquals(expect[i].first, u, 0.002f); assertEquals(expect[i].second, v, 0.002f)
            }
            // centre of the box maps to (0.5, 0.5) for affine transforms
            if (!t.hasPerspective) {
                val (u, v) = ObjectGeometry.canvasToUnit(t, w, h, hw, hh, t.cx * w, t.cy * h)
                assertEquals(0.5f, u, 0.002f); assertEquals(0.5f, v, 0.002f)
            }
        }
    }

    @Test fun cutoutEraseThenRestore() {
        val w = 200; val h = 100
        val pic = app.bocadillo.core.imaging.PixelBuffer(w, h, IntArray(w * h) { 0xFF336699.toInt() })
        val line = floatArrayOf(0.1f, 0.5f, 1f, 0.9f, 0.5f, 1f)
        val erased = Cutout.mask(listOf(Cutout.eraser(line, 0.3f, 1f)), w, h)
        assertEquals(0f, erased[50 * w + 100], 0.001f)      // centre of the line: gone
        assertEquals(1f, erased[5 * w + 100], 0.001f)       // far from it: kept
        val restored = Cutout.mask(listOf(Cutout.eraser(line, 0.3f, 1f), Cutout.restore(floatArrayOf(0.5f, 0.5f, 1f), 0.2f, 1f)), w, h)
        assertEquals(1f, restored[50 * w + 100], 0.001f)    // restored spot
        assertEquals(0f, restored[50 * w + 30], 0.001f)     // rest of the line still cut
        val out = Cutout.apply(pic, restored)
        assertEquals(0, out.pixels[50 * w + 30] ushr 24)
        assertEquals(0x336699, out.pixels[50 * w + 30] and 0xFFFFFF) // colour kept for restoring
        // soft edge: intermediate alpha exists
        val soft = Cutout.mask(listOf(Cutout.eraser(line, 0.3f, 0f)), w, h)
        assertTrue(soft.any { it > 0.05f && it < 0.95f })
        // crop rect and raster size
        assertTrue(Cutout.cropRect(listOf(0.25f, 0f, 0.75f, 0.5f), 400, 200).contentEquals(intArrayOf(100, 0, 200, 100)))
        assertEquals(2048 to 1024, Cutout.rasterSize(3000, 1500)); assertEquals(2048 to 1365, Cutout.rasterSize(4500, 3000))
        // encode / decode round trip
        val enc = Cutout.encode(listOf(Cutout.eraser(line, 0.3f, 0.5f)))
        assertEquals(1, Cutout.strokes(enc).size)
    }
}
