package app.bocadillo

import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.data.ImageIo
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Opens each screen of the 18-screen reference in a real emulator and saves a device screenshot
 * (status bar included) to files/screens/NN_name.png. The comparison with the reference is done
 * afterwards (docs/ETAPA_6_*). Navigation uses string resources, so it works in any language.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = rule.activity
    private fun s(id: Int) = ctx.getString(id)
    private val out by lazy { File(ctx.filesDir, "screens").apply { mkdirs() } }

    private fun shot(name: String, settle: Long = 1200) {
        rule.waitForIdle(); Thread.sleep(settle); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun desc(d: String) { rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun tap(text: String) { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle() }
    private fun scrollTo(text: String) { rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)); rule.waitForIdle() }
    /** Waits until [text] is on screen (the editor loads its preview in the background). */
    private fun waitFor(text: String, ms: Long = 30_000) {
        rule.waitUntil(ms) { rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    /** Shows a minimized tool panel so its controls appear in the capture. */
    private fun expandPanel() {
        val n = rule.onAllNodesWithContentDescription(s(R.string.show_options), useUnmergedTree = true)
        if (n.fetchSemanticsNodes().isNotEmpty()) { n.onFirst().performClick(); rule.waitForIdle() }
    }
    /** The text editor opens the keyboard, which would cover the bottom bar. */
    private fun hideKeyboard() {
        rule.runOnUiThread {
            val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(ctx.window.decorView.windowToken, 0)
        }
        rule.waitForIdle(); Thread.sleep(800)
    }
    /** Taps a row of a (lazy) menu list, scrolling to it first. */
    private fun tapInList(text: String) {
        val lists = rule.onAllNodes(hasScrollAction())
        if (lists.fetchSemanticsNodes().isNotEmpty()) runCatching { lists.onFirst().performScrollToNode(hasText(text)) }
        rule.waitForIdle(); tap(text)
    }
    /** Classic tool chrome: ✕ (unchanged) or ✓ (changed); the system Back always cancels. */
    private fun cancelTool() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        rule.waitForIdle(); Thread.sleep(500)
    }
    private fun back() { rule.onNodeWithContentDescription(s(R.string.back)).performClick(); rule.waitForIdle() }

    @Test fun captureReferenceScreens() {
        // a project made from the app's own sample landscape (no third-party photo)
        val app = ctx.application as BocadilloApp
        val sample = SampleScene.render(1200, 900)
        val png = ByteArrayOutputStream().also { ImageIo.toBitmap(sample).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        runBlocking { app.projects.create(ImageIo.Imported(png, "image/png", "Sample"), 1200, 900, false, "Sample", ImageIo.thumbnail(sample)) }
        rule.waitForIdle()

        shot("01_home")
        tap(s(R.string.open_image)); shot("01b_get_picture"); cancelTool()
        desc(s(R.string.home_more)); shot("18_more")
        tap(s(R.string.more_recent_projects)); tap("Sample"); waitFor(s(R.string.menu_adjust)); shot("02_editor", 4000)

        tap(s(R.string.menu_adjust)); waitFor(s(R.string.op_boost)); shot("03_adjust", 2000)
        tap(s(R.string.op_exposure_contrast)); shot("04_exposure", 2000); cancelTool()

        tap(s(R.string.menu_effect)); shot("05_effect", 2500)
        tapInList(s(R.string.op_twirl)); shot("06_twirl", 2500); cancelTool()
        tap(s(R.string.menu_effect)); scrollTo(s(R.string.op_hue)); shot("17_color_filters", 2500); back()

        tap(s(R.string.menu_sticker)); shot("07_sticker", 1500)
        tap(s(R.string.word_balloon)); shot("08_balloon_shapes")
        desc(s(R.string.balloon_speech).uppercase()); shot("09_new_word_balloon", 2000)
        desc(s(R.string.balloon_preview_hint)); shot("09b_balloon_style", 2000); hideKeyboard(); cancelTool(); cancelTool()

        // classic Paint: bar, then each drop-down panel
        tap(s(R.string.menu_effect)); tapInList(s(R.string.paint)); shot("12_paint", 2000)
        tap(s(R.string.paint)); shot("12b_brush_settings", 1200)
        desc(s(R.string.brush)); shot("12c_brush", 1200)
        desc(s(R.string.brush_color)); shot("12d_color", 1200)
        cancelTool(); cancelTool()
        tap(s(R.string.export)); shot("13_export"); back()
        tap(s(R.string.menu_adjust)); tap(s(R.string.op_crop)); shot("14_crop", 2500); cancelTool()
        tap(s(R.string.menu_adjust)); tapInList(s(R.string.op_rotate)); shot("15_rotate", 2000); cancelTool()
        tap(s(R.string.menu_adjust)); tap(s(R.string.op_resize)); shot("16_resize"); cancelTool()
        // New Title: classic composer, its style screen and the Font screen
        tap(s(R.string.menu_sticker)); tap(s(R.string.title_sticker)); shot("10_new_title", 1500)
        desc(s(R.string.title_preview_hint)); hideKeyboard(); shot("11_title_style", 1500)
        tap(s(R.string.tab_font)); shot("10b_font_list", 1500)
    }
}
