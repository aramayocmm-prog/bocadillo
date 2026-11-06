package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.ops.CropMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    private val full = floatArrayOf(0f, 0f, 1f, 1f)
    private val mid = floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f)

    @Test fun hitDetectsCornersEdgesAndMove() {
        assertEquals(CropMath.LEFT or CropMath.TOP, CropMath.hit(mid, 0.21f, 0.19f, 0.03f, 0.03f))
        assertEquals(CropMath.RIGHT, CropMath.hit(mid, 0.8f, 0.5f, 0.03f, 0.03f))
        assertEquals(CropMath.MOVE, CropMath.hit(mid, 0.5f, 0.5f, 0.03f, 0.03f))
        assertEquals(0, CropMath.hit(mid, 0.05f, 0.05f, 0.03f, 0.03f))
    }

    @Test fun freeDragClampsToBoundsAndMinSize() {
        val r = CropMath.drag(mid, CropMath.LEFT, -0.5f, 0f, null, 100, 100)
        assertArrayEquals(floatArrayOf(0f, 0.2f, 0.8f, 0.8f), r, 1e-6f)
        val tiny = CropMath.drag(mid, CropMath.RIGHT, -0.9f, 0f, null, 100, 100)
        assertEquals(0.05f, tiny[2] - tiny[0], 1e-6f)
    }

    @Test fun moveStaysInside() {
        val r = CropMath.drag(mid, CropMath.MOVE, 0.5f, -0.5f, null, 100, 100)
        assertArrayEquals(floatArrayOf(0.4f, 0f, 1f, 0.6f), r, 1e-6f)
    }

    @Test fun ratioIsKeptInPixelSpace() {
        // 400x200 picture, square crop: normalized height must be twice the normalized width
        val start = floatArrayOf(0.3f, 0.2f, 0.5f, 0.6f)
        val r = CropMath.drag(start, CropMath.RIGHT or CropMath.BOTTOM, 0.1f, 0f, 1f, 400, 200)
        val pw = (r[2] - r[0]) * 400; val ph = (r[3] - r[1]) * 200
        assertEquals(pw, ph, 0.5f)
        assertEquals(0.3f, r[0], 1e-6f); assertEquals(0.2f, r[1], 1e-6f) // anchored top-left
        for (v in r) assertTrue(v in 0f..1f)
    }

    @Test fun ratioDragNeverLeavesBounds() {
        val r = CropMath.drag(floatArrayOf(0.1f, 0.1f, 0.6f, 0.6f), CropMath.RIGHT or CropMath.BOTTOM, 2f, 2f, 1.618f, 300, 300, full)
        for (v in r) assertTrue("$v", v in -1e-5f..1.00001f)
        assertEquals(1.618f, ((r[2] - r[0]) * 300) / ((r[3] - r[1]) * 300), 0.01f)
    }
}
