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

    fun setHideStatusBar(v: Boolean) { hideStatusBar = v; prefs.edit().putBoolean("hide_status_bar", v).apply() }
    fun setShareSubject(v: String) { shareSubject = v; prefs.edit().putString("share_subject", v).apply() }
    fun setShareSignature(v: String) { shareSignature = v; prefs.edit().putString("share_signature", v).apply() }

    /** Forgets which tool panels were minimized. */
    fun resetPanels() = panels.edit().clear().apply()
}
