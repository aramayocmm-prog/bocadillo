package app.bocadillo.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User preferences (Settings screen). Equivalents of the original's Sharing and Editor
 * preferences (FASE1 §2): default subject and signature when sharing, hide the status bar in the
 * editor. Stored locally in SharedPreferences.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val panels = context.getSharedPreferences("editor_ui", Context.MODE_PRIVATE)

    var hideStatusBar by mutableStateOf(prefs.getBoolean("hide_status_bar", false)); private set
    var shareSubject by mutableStateOf(prefs.getString("share_subject", "") ?: ""); private set
    var shareSignature by mutableStateOf(prefs.getString("share_signature", "") ?: ""); private set

    fun updateHideStatusBar(v: Boolean) { hideStatusBar = v; prefs.edit().putBoolean("hide_status_bar", v).apply() }
    fun updateShareSubject(v: String) { shareSubject = v; prefs.edit().putString("share_subject", v).apply() }
    fun updateShareSignature(v: String) { shareSignature = v; prefs.edit().putString("share_signature", v).apply() }

    /** Colours the user added to the Paint palette (most recent first, at most 14). */
    var paintColors by mutableStateOf(
        (prefs.getString("paint_colors", "") ?: "").split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    ); private set

    fun addPaintColor(c: Int) {
        paintColors = (listOf(c) + paintColors.filter { it != c }).take(14)
        prefs.edit().putString("paint_colors", paintColors.joinToString(",") { (it.toLong() and 0xffffffffL).toString() }).apply()
    }

    /** Forgets which tool panels were minimized. */
    fun resetPanels() = panels.edit().clear().apply()
}
