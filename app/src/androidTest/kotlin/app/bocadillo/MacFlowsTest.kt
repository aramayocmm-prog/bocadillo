package app.bocadillo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.TextObject
import app.bocadillo.data.ImageIo
import app.bocadillo.editor.TITLE_STYLES
import app.bocadillo.editor.applyPreset
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

/**
 * MAC Editor 0.9 flows on an emulator: home identity, classic New Title (type, pick a style from
 * the ABC grid, insert, re-edit), Paint drop-down panels, and the transformation modes of an
 * inserted picture (skew and four-point perspective), saved, reopened and exported.
 */
@RunWith(AndroidJUnit4::class)
class MacFlowsTest {
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
    private fun tapInListText(text: String) {
        val lists = rule.onAllNodes(androidx.compose.ui.test.hasScrollAction())
        if (lists.fetchSemanticsNodes().isNotEmpty()) runCatching { lists.onFirst().performScrollToNode(androidx.compose.ui.test.hasText(text)) }
        rule.waitForIdle(); tap(text)
    }
    private fun desc(d: String) { rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun waitFor(text: String, ms: Long = 30_000) {
        rule.waitUntil(ms) { rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun systemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        rule.waitForIdle(); Thread.sleep(500)
    }
    private fun hideKeyboard() {
        rule.runOnUiThread {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0)
        }
        rule.waitForIdle(); Thread.sleep(800)
    }
    /** Centre of a node found by content description, in root coordinates. */
    private fun centerOf(d: String): Offset {
        val n = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().first()
        return n.boundsInRoot.center
    }

    private fun newProject(title: String): String {
        val app = ctx.application as BocadilloApp
        val sample = SampleScene.render(1200, 900)
        val png = ByteArrayOutputStream().also { ImageIo.toBitmap(sample).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val doc = runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", title), 1200, 900, false, title, ImageIo.thumbnail(sample)) }
        rule.waitForIdle()
        return doc.id
    }
    private fun openProject(title: String) {
        // back from the editor the home screen may still show the recent-projects page
        if (rule.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            desc(s(R.string.home_more))
            tap(s(R.string.more_recent_projects))
        }
        tap(title); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
    }
    /** Deselects the object (its menu replaces the Adjust/Effect bar). */
    private fun deselect() {
        if (rule.onAllNodesWithText(s(R.string.action_duplicate), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithContentDescription(s(R.string.show_options), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) systemBack()
    }

    @Test fun homeTitleAndPaint() {
        val app = ctx.application as BocadilloApp
        // identity: name and logo on the home screen, glass button in one line
        waitFor(s(R.string.open_image))
        assertEquals("MAC Editor para MEMES", s(R.string.app_name))
        assertTrue(rule.onAllNodesWithContentDescription(s(R.string.logo_description)).fetchSemanticsNodes().isNotEmpty())
        val button = rule.onAllNodesWithText(s(R.string.open_image), useUnmergedTree = true).fetchSemanticsNodes().first()
        assertTrue("one line (height ${button.size.height})", button.size.height < 60 * ctx.resources.displayMetrics.density)
        shot("30_home_mac")
        tap(s(R.string.open_image)); assertTrue(rule.onAllNodesWithText(s(R.string.get_from_gallery)).fetchSemanticsNodes().isNotEmpty())
        systemBack()

        val id = newProject("Titles")
        openProject("Titles")

        // New Title: type, pick style 7 of the ABC grid, insert
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); waitFor(s(R.string.enter_text_here))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Hola MAC")
        hideKeyboard()
        desc(s(R.string.style_n).format(7)); shot("31_new_title_typed")
        desc(s(R.string.apply)); Thread.sleep(1000)
        // re-edit: tap the title (select), tap again (edit), change the text
        // (after inserting, the title is already selected: one tap opens it, otherwise two)
        rule.onRoot().performTouchInput { click(center) }; rule.waitForIdle(); Thread.sleep(600)
        if (rule.onAllNodesWithText(s(R.string.title_edit), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            rule.onRoot().performTouchInput { click(center) }; rule.waitForIdle()
        }
        waitFor(s(R.string.title_edit))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Hola MEMES")
        hideKeyboard(); shot("32_edit_title")
        desc(s(R.string.apply)); Thread.sleep(800)

        // Paint: panels open and close, eyedropper is an icon
        deselect()
        tap(s(R.string.menu_effect)); tap(s(R.string.paint)); Thread.sleep(800)
        desc(s(R.string.brush_color))
        assertTrue(rule.onAllNodesWithContentDescription(s(R.string.eyedropper)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("eyedropper has no text label", rule.onAllNodesWithText(s(R.string.eyedropper)).fetchSemanticsNodes().isEmpty())
        desc(s(R.string.eyedropper)); waitFor(s(R.string.eyedropper_hint)); shot("33_eyedropper")
        rule.onRoot().performTouchInput { click(center) }; rule.waitForIdle(); Thread.sleep(600)
        // after a pick the colour panel is back
        assertTrue(rule.onAllNodesWithText(s(R.string.hue), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("34_color_after_pick")
        systemBack() // close panel
        // paint one stroke and accept with the green ✓
        rule.onRoot().performTouchInput { swipe(center + Offset(-200f, 0f), center + Offset(200f, 60f), 500) }
        rule.waitForIdle(); Thread.sleep(800); shot("35_painted")
        desc(s(R.string.apply)); Thread.sleep(1500)

        // leave (saves) and check
        rule.onNodeWithContentDescription(s(R.string.back)).performClick(); rule.waitForIdle(); Thread.sleep(2000)
        val saved = runBlocking { app.projects.load(id) }.document
        val title = saved.objects.filterIsInstance<TextObject>().single()
        assertEquals("Hola MEMES", title.text)
        assertEquals(title.style.applyPreset(TITLE_STYLES[6]), title.style)
        assertTrue("paint applied", saved.ops.any { it.opId == "paint" })
    }

    private fun testPng(): ByteArray {
        val b = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 40, 120) }
            drawRect(0f, 0f, 240f, 180f, p)
            p.color = Color.WHITE; p.textSize = 60f; drawText("MAC", 50f, 110f, p)
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun insertedPictureSkewPerspectiveSaveReopenExport() {
        val app = ctx.application as BocadilloApp
        runBlocking { app.stickerPacks.clear(null) }
        val zip = File(ctx.cacheDir, "mac_test.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"stickers":[{"id":"mac_pic","category":"signs_symbols","name":"MAC pic","file":"pic.png"}]}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("pic.png")); z.write(testPng()); z.closeEntry()
        }
        runBlocking { app.stickerPacks.importZip(ctx.contentResolver, Uri.fromFile(zip)) }
        val id = newProject("Transform")
        openProject("Transform")
        tap(s(R.string.menu_sticker)); tapInListText("Signs & Symbols"); desc("MAC pic"); Thread.sleep(1500)

        // tap the blue handle: the mode menu opens
        val handle = s(R.string.transform_handle)
        rule.onRoot().performTouchInput { click(centerOf(handle)) }; rule.waitForIdle()
        waitFor(s(R.string.tmode_skew)); shot("36_transform_menu")
        tap(s(R.string.tmode_skew)); Thread.sleep(500)
        val h1 = centerOf(handle)
        rule.onRoot().performTouchInput { swipe(h1, h1 + Offset(140f, 0f), 700) }
        rule.waitForIdle(); shot("37_skew", 1200)
        desc(s(R.string.tmode_done))

        rule.onRoot().performTouchInput { click(centerOf(handle)) }; rule.waitForIdle()
        tap(s(R.string.tmode_perspective)); Thread.sleep(500)
        val c1 = centerOf("$handle 2")
        rule.onRoot().performTouchInput { swipe(c1, c1 + Offset(60f, -70f), 700) }
        rule.waitForIdle(); shot("38_perspective", 1200)
        desc(s(R.string.tmode_done))

        // leave (saves), reopen from disk, check, then export to the album
        rule.onNodeWithContentDescription(s(R.string.back)).performClick(); rule.waitForIdle(); Thread.sleep(2000)
        val saved = runBlocking { app.projects.load(id) }.document
        val t = saved.objects.filterIsInstance<StickerObject>().single().transform
        assertNotEquals("skewed", 0f, t.skewX)
        assertTrue("perspective ${t.perspective}", t.hasPerspective)

        openProject("Transform")
        shot("39_reopened", 2000)
        tap(s(R.string.export)); tap(s(R.string.export_menu_album))
        waitFor(s(R.string.export_done_gallery), 60_000)
        shot("40_exported")
    }

    @Test fun largePictureAsksForSize() {
        val app = ctx.application as BocadilloApp
        // a 4000×3000 photo (12 MP): more than Normal, so the dialog must appear
        val f = File(ctx.cacheDir, "big.jpg")
        val b = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.rgb(40, 120, 200)); drawCircle(2000f, 1500f, 1200f, Paint().apply { color = Color.YELLOW }) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        b.recycle()
        val before = app.projects.index.value.entries.map { it.id }.toSet()
        rule.runOnUiThread { ctx.incoming.value = Uri.fromFile(f) }
        waitFor(s(R.string.import_large_title), 30_000)
        assertTrue(rule.onAllNodesWithText("768×1024").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("1024×768").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("1773×1330").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("2048×1536").fetchSemanticsNodes().isNotEmpty())
        shot("41_import_large")
        tap(s(R.string.import_fast))
        waitFor(s(R.string.menu_adjust), 30_000)
        val created = app.projects.index.value.entries.first { it.id !in before }
        val doc = runBlocking { app.projects.load(created.id) }.document
        assertEquals(1024, doc.source.width); assertEquals(768, doc.source.height)
    }
}
