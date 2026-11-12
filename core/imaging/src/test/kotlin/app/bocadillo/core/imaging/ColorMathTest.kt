package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.color.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/** 1.2.0, decision 5: conversions of the unified colour picker. */
class ColorMathTest {
    @Test fun hsbRoundTripForEveryRgbStep() {
        var n = 0
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val c = ColorMath.argb(255, r, g, b)
            val (h, s, v) = ColorMath.toHsb(c).let { Triple(it[0], it[1], it[2]) }
            assertEquals("round trip #%06X".format(c and 0xFFFFFF), c, ColorMath.hsb(h, s, v))
            n++
        }
        assertEquals(18 * 18 * 18, n)
    }

    @Test fun knownHsbValues() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), ColorMath.hsb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), ColorMath.hsb(240f, 1f, 1f))
        assertEquals(0xFFFFFF00.toInt(), ColorMath.hsb(60f, 1f, 1f))
        assertEquals(0xFF000000.toInt(), ColorMath.hsb(200f, 0.7f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.hsb(10f, 0f, 1f))
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsb(360f, 1f, 1f))
        val hsb = ColorMath.toHsb(0xFF336699.toInt())
        assertEquals(210f, hsb[0], 0.5f); assertEquals(0.667f, hsb[1], 0.01f); assertEquals(0.6f, hsb[2], 0.01f)
    }

    @Test fun transparencyIsKept() {
        for (a in listOf(0f, 0.25f, 0.5f, 1f)) {
            val c = ColorMath.hsb(30f, 0.5f, 0.5f, a)
            assertEquals((a * 255).toInt().toFloat(), ColorMath.alpha(c).toFloat(), 1f)
            assertEquals(c, ColorMath.parseHex(ColorMath.toHex(c)))
        }
        assertEquals("#80FF0000", ColorMath.toHex(0x80FF0000.toInt()))
        assertEquals("#00000000", ColorMath.toHex(0))
    }

    @Test fun hexParsing() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.parseHex("#ff0000"))
        assertEquals(0xFFFF0000.toInt(), ColorMath.parseHex("FF0000"))
        assertEquals(0xFFFFAA00.toInt(), ColorMath.parseHex("#fa0"))
        assertEquals(0x80123456.toInt(), ColorMath.parseHex(" #80123456 "))
        assertEquals("#123456", ColorMath.toHex(0xFF123456.toInt()))
        for (bad in listOf("", "#", "#12", "#12345", "#1234567", "#GG0000", "#123456789")) assertNull(bad, ColorMath.parseHex(bad))
    }

    @Test fun recentsHaveNoDuplicatesAndAreBounded() {
        var l = emptyList<Int>()
        for (c in listOf(1, 2, 3, 2, 1, 4)) l = ColorMath.pushRecent(l, c, 3)
        assertEquals(listOf(4, 1, 2), l)
        assertEquals(l.distinct(), l)
        assertEquals(true, abs(0) == 0)
    }
}
