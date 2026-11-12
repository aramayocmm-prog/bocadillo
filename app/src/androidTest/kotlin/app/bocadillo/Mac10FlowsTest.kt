package app.bocadillo

import app.bocadillo.render.processPicture
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.core.imaging.objects.Cutout
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.TextObject
import app.bocadillo.data.ImageIo
import app.bocadillo.editor.EditorViewModel
import app.bocadillo.editor.InsertStage
import app.bocadillo.editor.ToolState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * MAC Editor 1.0 flows on an emulator:
 *  - Insert Picture: Crop → Place Picture (every ⋮ entry, ▶|◀, perspective) → Cut Out (eraser,
 *    restore, undo, redo) → insert; saved, reopened, exported; mask with soft edges.
 *  - Live strokes: the picture seen while the finger is still down equals the applied result.
 *  - Fonts: ZIP import report, More Fonts, pick a font for a title, persistence after reopening.
 */
@RunWith(AndroidJUnit4::class)
class Mac10FlowsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = rule.activity
    private val app get() = ctx.application as BocadilloApp
    private fun s(id: Int) = ctx.getString(id)
    private val shots by lazy { File(ctx.filesDir, "screens").apply { mkdirs() } }

    private fun shot(name: String, settle: Long = 1200) {
        rule.waitForIdle(); Thread.sleep(settle); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun tap(text: String) { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun desc(d: String) { rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasDesc(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, ms: Long = 30_000) { rule.waitUntil(ms) { has(text) } }
    private fun waitDesc(d: String, ms: Long = 30_000) { rule.waitUntil(ms) { hasDesc(d) } }
    private fun boundsOf(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
    private fun vm() = EditorViewModel.lastInstance!!
    private fun slowSwipe(from: Offset, to: Offset, steps: Int = 30) {
        rule.onRoot().performTouchInput { down(from) }
        for (i in 1..steps) {
            val p = from + (to - from) * (i / steps.toFloat())
            rule.onRoot().performTouchInput { moveTo(p) }
            Thread.sleep(25)
        }
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
    }

    private fun newProject(title: String): String {
        // a previous test may have left the editor open: dismiss the recovery offer first
        if (has(s(R.string.home_recover_title))) { tap(s(R.string.cancel)); Thread.sleep(300) }
        val sample = SampleScene.render(1200, 900)
        val png = ByteArrayOutputStream().also { ImageIo.toBitmap(sample).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val doc = runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", title), 1200, 900, false, title, ImageIo.thumbnail(sample)) }.also { app.projects.clearDraft(it.id) }
        rule.waitForIdle()
        return doc.id
    }
    private fun openProject(title: String) {
        if (!has(title)) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap(title); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
    }
    private fun leaveEditor() {
        TestNav.leaveEditor(rule, ::s)
    }

    /** A 900×600 picture: a magenta frame around a cyan field with a yellow disc, opaque. */
    private fun testPicture(): File {
        val b = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.rgb(220, 30, 160))
            drawRect(60f, 60f, 840f, 540f, Paint().apply { color = Color.rgb(20, 190, 210) })
            drawCircle(450f, 300f, 170f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW })
        }
        val f = File(ctx.cacheDir, "insert_test.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    @Test fun insertPictureCropPlaceCutOut() {
        app.settings.updateInsertGuideOff(false)
        val id = newProject("Insert")
        openProject("Insert")
        val pic = testPicture()
        rule.runOnUiThread { vm().insertPicture(Uri.fromFile(pic)) }
        // step 1 guide, then Crop on the picture itself
        waitFor(s(R.string.ins_guide1_title)); shot("50_insert_guide1")
        tap(s(android.R.string.ok))
        waitDesc(s(R.string.ins_crop_frame)); shot("51_insert_crop")
        assertTrue("full size shown", has("900 × 600"))
        val f = boundsOf(s(R.string.ins_crop_frame))
        slowSwipe(Offset(f.left + 4f, f.top + 4f), Offset(f.left + f.width * 0.12f, f.top + f.height * 0.12f), 15)
        slowSwipe(Offset(f.right - 4f, f.bottom - 4f), Offset(f.right - f.width * 0.12f, f.bottom - f.height * 0.12f), 15)
        shot("52_insert_cropped")
        val crop = (vm().state.value.tool as ToolState.InsertPicture).cropRect
        assertTrue("crop changed $crop", crop[0] > 0.05f && crop[2] < 0.95f)
        assertTrue("background untouched while cropping", vm().state.value.objects.none { it.id == (vm().state.value.tool as ToolState.InsertPicture).draft.id })

        // step 2: Place Picture
        desc(s(R.string.ins_next_place)); waitFor(s(R.string.ins_guide2_title)); tap(s(android.R.string.ok))
        waitFor(s(R.string.ins_place_picture)); shot("53_place_picture")
        desc(s(R.string.more_options)); waitFor(s(R.string.ins_stretch_to_fit)); shot("54_place_menu")
        tap(s(R.string.ins_stretch_to_fit)); shot("55_stretch_to_fit")
        val stretched = (vm().state.value.tool as ToolState.InsertPicture).draft.transform
        assertTrue("stretch to fit", abs(stretched.stretchX - stretched.stretchY) > 0.01f || stretched.stretchX != 1f)
        desc(s(R.string.more_options)); tap(s(R.string.ins_scale_to_fill)); shot("56_scale_to_fill")
        val filled = (vm().state.value.tool as ToolState.InsertPicture).draft.transform
        assertEquals(1f, filled.stretchX, 0.0001f); assertTrue(filled.scale > 1f)
        desc(s(R.string.more_options)); tap(s(R.string.ins_mirror))
        assertTrue("mirrored", (vm().state.value.tool as ToolState.InsertPicture).draft.transform.flipH)
        desc(s(R.string.more_options)); tap(s(R.string.ins_show_stickers))
        assertTrue(!(vm().state.value.tool as ToolState.InsertPicture).showStickers)
        desc(s(R.string.more_options)); tap(s(R.string.ins_show_stickers))
        // make it smaller again with the blue handle, then perspective with the diamonds
        // undo is last-in first-out: two steps undo Mirror and Scale to Fill, back to Stretch to Fit
        rule.runOnUiThread { vm().insertUndo(); vm().insertUndo() }
        rule.waitForIdle(); Thread.sleep(500)
        val undone = (vm().state.value.tool as ToolState.InsertPicture).draft.transform
        assertTrue("undo removed the mirror", !undone.flipH); assertEquals(stretched, undone)
        rule.runOnUiThread { vm().insertRedo() } // redo brings Scale to Fill back
        rule.waitForIdle()
        assertEquals(filled, (vm().state.value.tool as ToolState.InsertPicture).draft.transform)
        rule.runOnUiThread { vm().insertUndo() }
        desc(s(R.string.more_options)); tap(s(R.string.ins_mirror)) // mirror again, kept to the end
        desc(s(R.string.more_options)); tap(s(R.string.ins_perspective_tool))
        waitDesc(s(R.string.transform_handle) + " 2"); shot("57_perspective_diamonds")
        val c2 = boundsOf(s(R.string.transform_handle) + " 2").center
        slowSwipe(c2, c2 + Offset(70f, -60f), 20)
        val persp = (vm().state.value.tool as ToolState.InsertPicture).draft.transform
        assertTrue("perspective ${persp.perspective}", persp.hasPerspective)
        desc(s(R.string.ins_mode_perspective)) // ▶|◀ back to circles
        assertTrue(!(vm().state.value.tool as ToolState.InsertPicture).perspective)
        shot("58_place_normal")

        // step 3: Cut Out
        desc(s(R.string.ins_next_cutout)); waitFor(s(R.string.ins_guide3_title)); shot("58b_guide3"); tap(s(android.R.string.ok))
        waitFor(s(R.string.ins_cut_out))
        desc(s(R.string.ins_eraser)); waitFor(s(R.string.brush_hardness)); shot("59_cutout_eraser_panel")
        desc(s(R.string.ins_tip_round) + " 8% · 40%")
        desc(s(R.string.collapse))
        val area = boundsOf(s(R.string.ins_cut_area))
        val d = (vm().state.value.tool as ToolState.InsertPicture).draft
        val cx = area.left + d.transform.cx * area.width; val cy = area.top + d.transform.cy * area.height
        slowSwipe(Offset(cx - 160f, cy - 40f), Offset(cx + 160f, cy + 40f), 35)
        shot("60_cutout_erased")
        desc(s(R.string.ins_restore_brush)); waitFor(s(R.string.ins_restore_brush)); shot("61_restore_panel")
        desc(s(R.string.collapse))
        slowSwipe(Offset(cx - 30f, cy - 8f), Offset(cx + 30f, cy + 8f), 15)
        shot("62_cutout_restored")
        val strokes2 = Cutout.strokes((vm().state.value.tool as ToolState.InsertPicture).draft.cutout)
        assertEquals(2, strokes2.size)
        // undo / redo
        desc(s(R.string.undo)); assertEquals(1, Cutout.strokes((vm().state.value.tool as ToolState.InsertPicture).draft.cutout).size)
        desc(s(R.string.more_options)); tap(s(R.string.redo)); assertEquals(2, Cutout.strokes((vm().state.value.tool as ToolState.InsertPicture).draft.cutout).size)
        // the screen shows the same processed picture the export will use
        Thread.sleep(800)
        val live = vm().insertLive.value
        assertNotNull(live)
        desc(s(R.string.ins_insert)); Thread.sleep(1200); shot("63_inserted")

        leaveEditor()
        val saved = runBlocking { app.projects.load(id) }
        val o = saved.document.objects.filterIsInstance<StickerObject>().single()
        assertTrue("crop saved", o.hasCrop)
        assertEquals("cut-out saved", 2, Cutout.strokes(o.cutout).size)
        assertTrue("mirror saved", o.transform.flipH)
        assertTrue("perspective saved", o.transform.hasPerspective)
        // mask: cut pixels, kept pixels and soft (semi-transparent) edges
        val raw = android.graphics.BitmapFactory.decodeByteArray(saved.assets[o.source.removePrefix("asset:")]!!, 0, saved.assets[o.source.removePrefix("asset:")]!!.size)
        val out = processPicture(raw, o.crop, o.cutout)
        val px = IntArray(out.width * out.height).also { out.getPixels(it, 0, out.width, 0, 0, out.width, out.height) }
        val alphas = px.map { it ushr 24 }
        assertTrue("erased area", alphas.count { it == 0 } > 100)
        assertTrue("kept area", alphas.count { it == 255 } > px.size / 3)
        assertTrue("soft edge", alphas.count { it in 20..235 } > 20)
        // live view = export pixels
        assertTrue("live == export", live!!.second.sameAs(out))

        openProject("Insert"); shot("64_insert_reopened", 2000)
        tap(s(R.string.export)); tap(s(R.string.export_menu_album))
        waitFor(s(R.string.export_done_gallery), 60_000)
        shot("65_insert_exported")
    }

    @Test fun liveStrokeEqualsAppliedStroke() {
        newProject("Live")
        openProject("Live")
        tap(s(R.string.menu_effect)); tap(s(R.string.paint)); Thread.sleep(800)
        // soft, half-transparent red: the case that looked different before (video ERROR_TRAZO_02)
        rule.runOnUiThread {
            val t = vm().state.value.tool as ToolState.Draw
            vm().setBrush(t.brush.copy(color = 0xFFE53935.toInt(), size = 0.05f, hardness = 0.2f, opacity = 0.8f))
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val a = Offset(root.width * 0.25f, root.height * 0.62f); val b = Offset(root.width * 0.78f, root.height * 0.40f)
        rule.onRoot().performTouchInput { down(a) }
        // slow enough for the screen recording to show the stroke growing under the finger
        for (i in 1..90) { val p = a + (b - a) * (i / 90f); rule.onRoot().performTouchInput { moveTo(p) }; Thread.sleep(45) }
        Thread.sleep(700)
        shot("70_live_finger_down", 300)
        var live: Pair<Int, IntArray>? = null
        rule.waitUntil(20_000) { var p = true; rule.runOnUiThread { p = vm().livePending() }; !p }
        rule.runOnUiThread { live = vm().liveSnapshot(); assertTrue("live tiles on screen while the finger is down", vm().liveTilesShown()) }
        assertNotNull("live preview while the finger is down", live)
        rule.onRoot().performTouchInput { up() }
        // wait for the real render to replace the live view
        rule.waitUntil(15_000) { var gone = false; rule.runOnUiThread { gone = !vm().liveTilesShown() }; gone }
        Thread.sleep(500)
        shot("71_after_release", 300)
        val fin = vm().state.value.preview!!.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        assertEquals(live!!.first, fin.width)
        val n = fin.width * fin.height
        val p1 = live!!.second
        val p2 = IntArray(n).also { fin.getPixels(it, 0, fin.width, 0, 0, fin.width, fin.height) }
        var diff = 0; var maxD = 0; var painted = 0
        val base = vm().previewBitmap()
        for (i in 0 until n) {
            var d = 0
            for (sh in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs(((p1[i] ushr sh) and 0xFF) - ((p2[i] ushr sh) and 0xFF)))
            if (d > 0) diff++
            maxD = maxOf(maxD, d)
            if ((p2[i] shr 16 and 0xFF) > 200 && (p2[i] shr 8 and 0xFF) < 120) painted++
        }
        android.util.Log.i("LiveStroke", "differing=$diff/$n max=$maxD painted=$painted base=${base?.width}")
        assertTrue("stroke visible", painted > 500)
        assertTrue("live and applied differ in $diff px (max $maxD)", diff.toFloat() / n < 0.002f && maxD <= 2)
        desc(s(R.string.apply))
    }

    private fun assetBytes(path: String) = ctx.assets.open(path).use { it.readBytes() }

    @Test fun fontsImportPickAndPersist() {
        // remove fonts left by other runs
        app.fonts.importedFonts().forEach { app.fonts.delete(it.ref) }
        val zip = File(ctx.cacheDir, "fonts_test.zip")
        // 1.1 skips fonts identical to ones the APK already ships: these copies differ by 16
        // trailing bytes (ignored by font loaders), so they count as new fonts with the same names
        val comfortaa = assetBytes("fonts/Comfortaa-Bold.ttf") + ByteArray(16)
        val creato = assetBytes("fonts/CreatoDisplay-Black.otf") + ByteArray(16)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("FUENTES/Comfortaa-Bold.ttf")); z.write(comfortaa); z.closeEntry()
            z.putNextEntry(ZipEntry("FUENTES/copy/Comfortaa-Bold.ttf")); z.write(comfortaa); z.closeEntry()
            z.putNextEntry(ZipEntry("FUENTES/CreatoDisplay-Black.otf")); z.write(creato); z.closeEntry()
            z.putNextEntry(ZipEntry("FUENTES/broken.ttf")); z.write(ByteArray(400) { 7 }); z.closeEntry()
            z.putNextEntry(ZipEntry("FUENTES/readme.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("__MACOSX/._Comfortaa-Bold.ttf")); z.write(ByteArray(200)); z.closeEntry()
        }
        val r = app.fonts.importAll(listOf("fonts_test.zip" to zip.readBytes()))
        assertEquals(listOf("Comfortaa Bold", "Creato Display Black"), r.added)
        assertEquals(1, r.duplicates.size)
        assertEquals(1, r.invalid.size + r.errors.size)
        assertTrue((r.invalid + r.errors).single().first == "broken.ttf")
        // importing the same ZIP again adds nothing
        assertEquals(0, app.fonts.importAll(listOf("fonts_test.zip" to zip.readBytes())).added.size)
        // a font identical to one shipped in the APK is reported as a duplicate, not copied
        val shipped = app.fonts.importAll(listOf("Comfortaa-Bold.ttf" to assetBytes("fonts/Comfortaa-Bold.ttf")))
        assertEquals(0, shipped.added.size); assertEquals(1, shipped.duplicates.size)

        val id = newProject("Fonts")
        openProject("Fonts")
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); waitFor(s(R.string.enter_text_here))
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Fuente MAC")
        rule.runOnUiThread { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0) }
        Thread.sleep(800)
        desc(s(R.string.title_preview_hint)); waitFor(s(R.string.alignment)); shot("80_title_style_panel")
        assertTrue(has(s(R.string.line_height)) && has(s(R.string.fill_color)) && has(s(R.string.outline_color)) && has(s(R.string.text_size)))
        tap(s(R.string.tab_font)); waitFor(s(R.string.fonts_custom_files)); shot("81_more_fonts")
        // the System Fonts header lies further down the list: scroll to it, then back up
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(s(R.string.fonts_system_header), substring = true))
        assertTrue("System Fonts header", has(s(R.string.fonts_system_header))); shot("81b_more_fonts_system")
        // back to the Custom Font Files section: the imported copy comes before the bundled one
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(s(R.string.fonts_import_files)))
        tap("Creato Display Black"); systemBack()
        Thread.sleep(500); shot("82_title_with_font")
        systemBack() // close the panel
        desc(s(R.string.apply)); Thread.sleep(1000)
        leaveEditor()
        val saved = runBlocking { app.projects.load(id) }
        val t = saved.document.objects.filterIsInstance<TextObject>().single()
        assertTrue("font ref ${t.style.font}", t.style.font.startsWith("asset:fonts/") && t.style.font.contains("CreatoDisplay-Black"))
        // the project keeps its own copy: still available after removing the font from the device
        app.fonts.delete(t.style.font)
        assertTrue(app.fonts.isAvailable(t.style.font, saved.assets))
        openProject("Fonts"); shot("83_reopened_font", 2500)
        assertTrue("no missing-font warning", !has("not available") && !has("no están disponibles"))
    }

    private fun systemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        rule.waitForIdle(); Thread.sleep(500)
    }

    @Suppress("unused") private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }
}
