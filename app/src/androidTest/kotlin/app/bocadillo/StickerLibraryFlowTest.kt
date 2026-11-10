package app.bocadillo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.core.model.StickerObject
import app.bocadillo.data.ImageIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * External sticker library end to end on an emulator: import a ZIP (manifest + PNG + SVG, plus a
 * duplicate and a broken file), insert a sticker from the classic menu, move, scale, rotate,
 * duplicate and delete it, and check the saved project. Test pictures are drawn here.
 */
@RunWith(AndroidJUnit4::class)
class StickerLibraryFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = rule.activity
    private fun s(id: Int) = ctx.getString(id)
    private val shots by lazy { File(ctx.filesDir, "screens").apply { mkdirs() } }

    private fun shot(name: String, settle: Long = 1500) {
        rule.waitForIdle(); Thread.sleep(settle); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun tap(text: String) { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun waitFor(text: String, ms: Long = 30_000) {
        rule.waitUntil(ms) { rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun testPng(): ByteArray {
        val b = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 90, 200) }
            drawRoundRect(10f, 60f, 190f, 150f, 30f, 30f, p)
            p.color = Color.rgb(250, 200, 40); drawCircle(100f, 60f, 50f, p)
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    private val testSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><polygon points="50,5 61,38 95,38 67,58 78,92 50,72 22,92 33,58 5,38 39,38" fill="#e53935" stroke="#000" stroke-width="3"/></svg>"""

    private fun buildZip(): File {
        val png = testPng()
        val f = File(ctx.cacheDir, "test_library.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("manifest.json", """{"format":"bocadillo-sticker-library","version":1,"stickers":[
                {"id":"t_hat","category":"hats_helmets","name":"Test hat","position":1,"file":"01_hats_helmets/hat.png"},
                {"id":"t_star","category":"Basic Shapes","name":"Test star","position":1,"file":"20_basic_shapes/star.svg"},
                {"id":"t_copy","category":"objects","name":"Copy","file":"30_objects/copy.png"},
                {"id":"t_bad","category":"objects","name":"Broken","file":"30_objects/broken.png"}]}""".toByteArray())
            put("01_hats_helmets/hat.png", png)
            put("20_basic_shapes/star.svg", testSvg.toByteArray())
            put("30_objects/copy.png", png)
            put("30_objects/broken.png", "this is not a picture".toByteArray())
        }
        return f
    }

    @Test fun importInsertTransformDuplicateDelete() {
        val app = ctx.application as BocadilloApp
        runBlocking { app.stickerPacks.clear(null) }

        // 1. import
        val report = runBlocking { app.stickerPacks.importZip(ctx.contentResolver, Uri.fromFile(buildZip())) }
        assertEquals("manifest.json", report.source)
        assertEquals(2, report.added)
        assertEquals(1, report.duplicates.size)
        assertEquals(1, report.errors.size)
        // importing again keeps everything and changes nothing
        val again = runBlocking { app.stickerPacks.importZip(ctx.contentResolver, Uri.fromFile(buildZip())) }
        assertEquals(0, again.added); assertEquals(2, again.unchanged); assertEquals(2, again.total)

        // 2. a project to work on
        val sample = SampleScene.render(1200, 900)
        val png = ByteArrayOutputStream().also { ImageIo.toBitmap(sample).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val doc = runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", "Stickers"), 1200, 900, false, "Stickers", ImageIo.thumbnail(sample)) }
        rule.waitForIdle()
        tap(s(R.string.home_recent_photos)); tap("Stickers"); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)

        // 3. classic sticker menu: 32 categories, empty ones pending
        tap(s(R.string.menu_sticker)); waitFor(s(R.string.word_balloon)); shot("20_sticker_menu")
        tap("Eyes"); waitFor(s(R.string.sticker_pending_long).lines().first()); shot("21_sticker_empty_category")
        rule.onNodeWithContentDescription(s(R.string.back)).performClick(); rule.waitForIdle()
        tap("Hats & Helmets"); shot("22_sticker_category")
        rule.onAllNodesWithContentDescription("Test hat").onFirst().performClick(); rule.waitForIdle()
        shot("23_sticker_inserted", 2000)

        // 4. move, scale and rotate the selected sticker on the canvas
        rule.onRoot().performTouchInput {
            val c = center
            swipe(c, c + Offset(-width * 0.2f, height * 0.08f), 600)
        }
        rule.waitForIdle(); Thread.sleep(500)
        rule.onRoot().performTouchInput {
            val c = center + Offset(-width * 0.2f, height * 0.08f)
            pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 600)
        }
        rule.waitForIdle(); Thread.sleep(500)
        rule.onRoot().performTouchInput {
            val c = center + Offset(-width * 0.2f, height * 0.08f)
            down(0, c + Offset(-120f, 0f)); down(1, c + Offset(120f, 0f))
            for (k in 1..10) {
                val a = Math.toRadians(4.0 * k)
                moveTo(0, c + Offset((-120 * Math.cos(a)).toFloat(), (-120 * Math.sin(a)).toFloat()))
                moveTo(1, c + Offset((120 * Math.cos(a)).toFloat(), (120 * Math.sin(a)).toFloat()))
            }
            up(0); up(1)
        }
        rule.waitForIdle(); shot("24_sticker_transformed")

        // 5. duplicate, then delete the copy
        tap(s(R.string.action_duplicate)); rule.waitForIdle(); shot("25_sticker_duplicated")
        tap(s(R.string.action_delete)); rule.waitForIdle()

        // 6. leave the editor (saves) and check the stored project
        rule.onNodeWithContentDescription(s(R.string.back)).performClick(); rule.waitForIdle(); Thread.sleep(2000)
        val saved = runBlocking { app.projects.load(doc.id) }.document
        val stickers = saved.objects.filterIsInstance<StickerObject>()
        assertEquals("one sticker left after duplicate + delete", 1, stickers.size)
        val t = stickers.single().transform
        assertTrue("moved (cx=${t.cx})", t.cx < 0.45f)
        assertTrue("scaled (scale=${t.scale})", t.scale > 1.2f)
        assertNotEquals("rotated", 0f, t.rotation)
        assertTrue("rotation ≈ 40° (${t.rotation})", abs(t.rotation) > 10f)
        assertTrue(stickers.single().source.startsWith("asset:stickers/"))
    }
}
