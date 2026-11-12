package app.bocadillo

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick

/** Shared navigation for the instrumented tests. */
object TestNav {
    /**
     * Leaves the editor with its ← (1.1.0): with unsaved changes the finish dialog appears and
     * «Guardar proyecto» is chosen, so the project is kept as before 1.1.
     */
    fun leaveEditor(rule: AndroidComposeTestRule<*, *>, s: (Int) -> String) {
        rule.onNodeWithContentDescription(s(R.string.back)).performClick(); rule.waitForIdle(); Thread.sleep(700)
        val save = rule.onAllNodesWithText(s(R.string.finish_save_project), useUnmergedTree = true)
        if (save.fetchSemanticsNodes().isNotEmpty()) save.onFirst().performClick()
        rule.waitForIdle(); Thread.sleep(2000)
    }
}
