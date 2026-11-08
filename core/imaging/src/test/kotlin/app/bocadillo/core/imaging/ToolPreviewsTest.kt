package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.core.imaging.preview.ToolPreviews
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class ToolPreviewsTest {
    private val outDir = File(System.getProperty("bocadillo.visualOut") ?: "build/visual").apply { mkdirs() }

    private fun diff(a: PixelBuffer, b: PixelBuffer): Double {
        if (a.width != b.width || a.height != b.height) return 255.0
        var s = 0.0
        for (i in 0 until a.size) {
            val x = premultiply(a.pixels[i]); val y = premultiply(b.pixels[i])
            for (sh in intArrayOf(0, 8, 16, 24)) s += kotlin.math.abs(((x ushr sh) and 0xFF) - ((y ushr sh) and 0xFF))
        }
        return s / a.size / 4
    }

    @Test fun everyMenuToolHasAVisiblePreview() {
        val sample = SampleScene.render()
        val menuOps = OpRegistry.default().all.filter { it.category != OpCategory.DRAW || it.id == "make_transparent" }
        val tiles = ArrayList<Pair<String, PixelBuffer>>()
        tiles += "sample" to sample
        for (op in menuOps) {
            val t0 = System.nanoTime()
            val p = ToolPreviews.forOp(op, sample)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("${op.id} preview shows no change", diff(sample, p) > 1.0)
            assertTrue("${op.id} preview too slow: $ms ms", ms < 1500)
            tiles += op.id to p
        }
        for (k in ToolPreviews.DrawDemo.entries) {
            val p = ToolPreviews.forDraw(k, sample)
            assertTrue("$k preview shows no change", diff(sample, p) > 1.0)
            tiles += k.name.lowercase() to p
        }
        val cols = 8; val cw = 176; val ch = 150
        val sheet = BufferedImage(cols * cw, (tiles.size + cols - 1) / cols * ch, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(36, 36, 40); g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        tiles.forEachIndexed { i, (name, img) ->
            val x = (i % cols) * cw + 8; val y = (i / cols) * ch + 6
            for (cy in 0 until 120 step 8) for (cx in 0 until 160 step 8) {
                g.color = if ((cx + cy) / 8 % 2 == 0) Color(200, 200, 200) else Color(160, 160, 160); g.fillRect(x + cx, y + cy, 8, 8)
            }
            val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width) }
            val sc = minOf(160.0 / img.width, 120.0 / img.height)
            val dw = (img.width * sc).toInt(); val dh = (img.height * sc).toInt()
            g.drawImage(bi, x + (160 - dw) / 2, y + (120 - dh) / 2, dw, dh, null)
            g.color = Color.WHITE; g.drawString(name, x, y + 136)
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(outDir, "stage5b_tool_tiles.png"))
    }
}
