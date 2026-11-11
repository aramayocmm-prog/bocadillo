package app.bocadillo

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures for the variant set by the workflow (instrumentation argument "variant": font scale
 * or landscape), and the check run right after installing 1.0 over 0.9.0.
 */
@RunWith(AndroidJUnit4::class)
class LayoutAndUpgradeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = rule.activity
    private fun s(id: Int) = ctx.getString(id)
    private val variant get() = InstrumentationRegistry.getArguments().getString("variant") ?: "default"

    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(1500); rule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(File(ctx.filesDir, "screens").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home, More… and Licencias y créditos in the current variant; the title is never cut. */
    @Test fun homeVariants() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (variant == "landscape") { ui.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90); Thread.sleep(2500); rule.waitForIdle() }
        try { homeVariantsChecks() } finally {
            if (variant == "landscape") ui.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
        }
    }

    private fun homeVariantsChecks() {
        rule.waitUntil(20_000) { rule.onAllNodesWithText(s(R.string.open_image), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // a previous test may have left the editor open: dismiss the recovery offer first
        if (rule.onAllNodesWithText(s(R.string.home_recover_title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText(s(R.string.cancel), useUnmergedTree = true).onFirst().performClick(); rule.waitForIdle()
        }
        val screenW = ctx.resources.displayMetrics.widthPixels
        for (t in listOf("MAC", s(R.string.app_title_line2), s(R.string.open_image))) {
            val n = rule.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().first()
            assertTrue("'$t' inside the screen (${n.boundsInRoot})", n.boundsInRoot.left >= -1f && n.boundsInRoot.right <= screenW + 1f)
        }
        if (variant == "landscape") assertTrue("landscape", ctx.resources.displayMetrics.widthPixels > ctx.resources.displayMetrics.heightPixels)
        shot("90_home_$variant")
        rule.onAllNodesWithContentDescription(s(R.string.home_more), useUnmergedTree = true).onFirst().performClick()
        shot("91_more_$variant")
        rule.onAllNodesWithText(s(R.string.licenses), useUnmergedTree = true).onFirst().performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Marcelo Aramayo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText(s(R.string.credits_role), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName!!
        assertTrue("real version shown", rule.onAllNodesWithText(version, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("92_credits_$variant")
    }

    /**
     * Run once after "adb install -r" of 1.0 over 0.9.0 (the workflow first creates data with
     * 0.9.0): the projects, the sticker library and the imported font are still there and open.
     */
    @Test fun dataSurvivedUpgrade() {
        val app = ctx.application as BocadilloApp
        val entries = app.projects.index.value.entries
        assertTrue("projects kept (${entries.size})", entries.isNotEmpty())
        for (e in entries) runBlocking { app.projects.load(e.id) } // every project still opens
        assertTrue("sticker library kept", app.stickerPacks.index.stickers.isNotEmpty())
        assertTrue("imported font kept", app.fonts.importedFonts().isNotEmpty())
        val marker = File(ctx.filesDir, "upgrade_marker.txt")
        assertTrue("files written by 0.9.0 kept", marker.exists())
        android.util.Log.i("Upgrade", "projects=${entries.size} stickers=${app.stickerPacks.index.stickers.size} fonts=${app.fonts.importedFonts().map { it.displayName }}")
    }
}
