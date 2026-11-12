package app.bocadillo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.data.ImageIo
import app.bocadillo.editor.EditorViewModel
import app.bocadillo.editor.Labels
import app.bocadillo.editor.ToolState
import app.bocadillo.home.BlankLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MAC Editor 1.2.0 decisions on an emulator (not a phone): 4 new blank image, 5 unified colour
 * picker (HEX, favourites and recents kept, eyedropper with zoom on every picture layer).
 */
@RunWith(AndroidJUnit4::class)
class Mac12Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = rule.activity
    private val app get() = ctx.application as BocadilloApp
    private fun s(id: Int) = ctx.getString(id)
    private val shots by lazy { File(ctx.filesDir, "screens").apply { mkdirs() } }
    private fun log(msg: String) { android.util.Log.i("Mac12", msg); runCatching { File(shots, "mac12_log.txt").appendText(msg + "\n") } }
    private fun shot(name: String, settle: Long = 900) {
        rule.waitForIdle(); Thread.sleep(settle); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasDesc(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(text: String, substring: Boolean = false) { rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun desc(d: String) { rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun waitFor(text: String, ms: Long = 30_000) { rule.waitUntil(ms) { has(text) } }
    private fun vm() = EditorViewModel.lastInstance!!
    private fun ui(f: () -> Unit) = rule.runOnUiThread(f)
    private fun statusText(): String = rule.onAllNodesWithContentDescription("blank_status", useUnmergedTree = true).fetchSemanticsNodes().first()
        .config.let { c -> c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } ?: "" }
    private fun dismissRecovery() { if (has(s(R.string.home_recover_title))) { tap(s(R.string.cancel)); Thread.sleep(300) } }
    private fun okEnabled(): Boolean = rule.onAllNodesWithText(s(R.string.ok), useUnmergedTree = true).fetchSemanticsNodes().first()
        .let { n -> generateSequence(n) { it.parent }.none { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }

    private fun openBlankDialog() {
        dismissRecovery()
        desc(s(R.string.home_more)); tap(s(R.string.more_new_blank)); waitFor(s(R.string.blank_title))
    }

    @Test fun newBlankImageFormatsAndLimits() {
        openBlankDialog()
        shot("200_nueva_imagen")
        log("BLANK default: ${statusText()}")
        assertTrue("default 1080 × 1920", statusText().contains("1080 × 1920"))
        for ((name, expect) in listOf("4:5" to "1080 × 1350", "1:1" to "1080 × 1080", "16:9" to "1920 × 1080", "9:16" to "1080 × 1920")) {
            tap(name); log("BLANK $name: ${statusText()}"); assertTrue("$name → $expect", statusText().contains(expect))
        }
        // orientation: swap width and height
        tap("4:5"); desc(s(R.string.blank_swap)); log("BLANK swapped: ${statusText()}"); assertTrue(statusText().contains("1350 × 1080"))
        // custom sizes, validation
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("8"); assertTrue(statusText(), statusText() == s(R.string.blank_error_small).format(BlankLimits.MIN_SIDE)); assertFalse(okEnabled())
        fields[0].performTextReplacement("20000"); assertFalse(okEnabled()); log("BLANK 20000: ${statusText()}")
        fields[0].performTextReplacement("9000"); fields[1].performTextReplacement("9000"); log("BLANK 9000×9000: ${statusText()} (max pixels ${BlankLimits.maxPixels(Runtime.getRuntime().maxMemory())})")
        assertFalse("9000 × 9000 refused on this heap", okEnabled())
        fields[0].performTextReplacement("1234"); fields[1].performTextReplacement("777"); assertTrue(statusText().contains("1234 × 777")); assertTrue(okEnabled())
        shot("201_nueva_imagen_personalizada")
        // background with the unified picker: HEX
        tap(s(R.string.color_custom))
        rule.onAllNodes(hasContentDescription(s(R.string.color_hex))).onFirst().performTextReplacement("#FF3366")
        rule.waitForIdle(); shot("202_nueva_imagen_color")
        tap(s(R.string.ok))
        rule.waitUntil(30_000) { EditorViewModel.lastInstance?.state?.value?.loading == false && has(s(R.string.menu_adjust)) }
        Thread.sleep(2000)
        val st = vm().state.value
        val px = vm().previewBitmap()!!
        val c = px.pixels[px.height / 2 * px.width + px.width / 2]
        log("BLANK created ${st.outputWidth}×${st.outputHeight} centre=#%08X".format(c))
        assertEquals(1234, st.outputWidth); assertEquals(777, st.outputHeight)
        assertEquals(0xFFFF3366.toInt(), c)
        shot("203_nueva_imagen_creada")
        assertTrue("background colour is a recent colour", 0xFFFF3366.toInt() in app.settings.recentColors)
    }

    /** Left half red, right half blue: the eyedropper must take the side under the loupe at any zoom. */
    private fun halves(): String {
        dismissRecovery()
        val b = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.RED); drawRect(500f, 0f, 1000f, 800f, Paint().apply { color = Color.BLUE }) }
        val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val doc = runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", "Mitades"), 1000, 800, false, "Mitades", ImageIo.thumbnail(b.copy(Bitmap.Config.ARGB_8888, false))) }
        app.projects.clearDraft(doc.id)
        return doc.id
    }

    @Test fun colorPickerHexFavouritesRecentsEyedropper() {
        halves()
        if (!has("Mitades")) { desc(s(R.string.home_more)); tap(s(R.string.more_recent_projects)) }
        tap("Mitades"); waitFor(s(R.string.menu_adjust)); Thread.sleep(2500)
        // an effect with a colour (Solid border), all options: the unified picker with its eyedropper
        ui { vm().openTool(app.registry["solid_border"]!!) }
        rule.waitForIdle(); Thread.sleep(800)
        tap(s(Labels.op("solid_border")), substring = true); tap(s(R.string.more_options)); Thread.sleep(600)
        assertTrue("eyedropper in the colour picker", hasDesc(s(R.string.eyedropper)))
        shot("210_selector_color_efecto")
        // HEX
        tap(s(R.string.color_custom))
        rule.onAllNodes(hasContentDescription(s(R.string.color_hex))).onFirst().performTextReplacement("#80112233")
        rule.waitForIdle()
        fun param() = ((vm().state.value.tool as ToolState.Adjust).params.color("color", 0))
        log("PICK hex → #%08X".format(param()))
        assertEquals(0x80112233.toInt(), param())
        // favourite
        desc(s(R.string.color_favorite_toggle))
        assertTrue(0x80112233.toInt() in app.settings.favoriteColors)
        val stored = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("favorite_colors", "") ?: ""
        assertTrue("favourite kept in the preferences ($stored)", (0x80112233L).toString() in stored.split(','))
        shot("211_selector_color_avanzado")
        // eyedropper at zoom ×4: left of the centre line is red, right is blue
        desc(s(R.string.eyedropper))
        rule.waitUntil(10_000) { vm().lastEyedropperImage != null && hasDesc(s(R.string.eyedropper_picture)) }
        Thread.sleep(500)
        val area = rule.onAllNodesWithContentDescription(s(R.string.eyedropper_picture), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val img = vm().lastEyedropperImage!!.asAndroidBitmap()
        // the centre of the picture on screen (fitted, then zoomed about the screen centre: same point)
        val k = minOf(area.width / img.width, area.height / img.height)
        val c = area.center
        rule.onRoot().performTouchInput { pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 400) }
        rule.waitForIdle(); Thread.sleep(500)
        shot("212_cuentagotas_zoom")
        // after a symmetric pinch the centre stays; at ×4 one picture pixel is 4·k screen px
        rule.onRoot().performTouchInput { down(c - Offset(12f, 0f)); moveTo(c - Offset(10f, 0f)) }
        rule.waitForIdle(); Thread.sleep(400)
        shot("213_cuentagotas_lupa")
        assertTrue("loupe shows the colour", hasDesc(s(R.string.eyedropper_loupe)))
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle(); Thread.sleep(500)
        log("PICK eyedropper left of centre (k=$k) → #%08X".format(param()))
        assertEquals(0xFFFF0000.toInt(), param())
        assertTrue("picked colour in the recent colours", 0xFFFF0000.toInt() in app.settings.recentColors)
        desc(s(R.string.eyedropper)); rule.waitUntil(10_000) { hasDesc(s(R.string.eyedropper_picture)) }; Thread.sleep(500)
        rule.onRoot().performTouchInput { pinch(c - Offset(40f, 0f), c - Offset(160f, 0f), c + Offset(40f, 0f), c + Offset(160f, 0f), 400) }
        rule.waitForIdle(); Thread.sleep(400)
        rule.onRoot().performTouchInput { down(c + Offset(12f, 0f)); moveTo(c + Offset(10f, 0f)); up() }
        rule.waitForIdle(); Thread.sleep(500)
        log("PICK eyedropper right of centre → #%08X".format(param()))
        assertEquals(0xFF0000FF.toInt(), param())
        // Back closes the eyedropper without picking
        desc(s(R.string.eyedropper)); rule.waitUntil(10_000) { hasDesc(s(R.string.eyedropper_picture)) }
        ui { ctx.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle(); Thread.sleep(400)
        assertFalse(hasDesc(s(R.string.eyedropper_picture)))
        assertEquals(0xFF0000FF.toInt(), param())
        ui { vm().cancelTool() }
    }
}
