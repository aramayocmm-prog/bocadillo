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

    /** Insert Picture's step guides were turned off ("Don't show again"). */
    var insertGuideOff by mutableStateOf(prefs.getBoolean("insert_guide_off", false)); private set
    fun updateInsertGuideOff(v: Boolean) { insertGuideOff = v; prefs.edit().putBoolean("insert_guide_off", v).apply() }

    /** Colours the user added to the Paint palette (most recent first, at most 14). */
    var paintColors by mutableStateOf(
        (prefs.getString("paint_colors", "") ?: "").split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    ); private set

    fun addPaintColor(c: Int) {
        paintColors = (listOf(c) + paintColors.filter { it != c }).take(14)
        prefs.edit().putString("paint_colors", paintColors.joinToString(",") { (it.toLong() and 0xffffffffL).toString() }).apply()
    }

    // ---- unified colour picker (1.2.0, decision 5): recent colours and favourites, kept across sessions
    private fun ints(key: String) = (prefs.getString(key, "") ?: "").split(',').mapNotNull { it.toLongOrNull()?.toInt() }
    private fun putInts(key: String, v: List<Int>) = prefs.edit().putString(key, v.joinToString(",") { (it.toLong() and 0xffffffffL).toString() }).apply()

    var recentColors by mutableStateOf(ints("recent_colors")); private set
    /** Favourites; the colours added to the 1.1 Paint palette become the first favourites (kept, not lost). */
    var favoriteColors by mutableStateOf(if (prefs.contains("favorite_colors")) ints("favorite_colors") else ints("paint_colors")); private set

    fun addRecentColor(c: Int) {
        if (recentColors.firstOrNull() == c) return
        recentColors = app.bocadillo.core.imaging.color.ColorMath.pushRecent(recentColors, c, 12); putInts("recent_colors", recentColors)
    }
    fun toggleFavoriteColor(c: Int) {
        favoriteColors = if (c in favoriteColors) favoriteColors - c else (listOf(c) + favoriteColors).take(24)
        putInts("favorite_colors", favoriteColors)
    }

    init { current = this }

    companion object {
        /** The app's settings, for components without access to the application (colour picker). */
        var current: AppSettings? = null; private set
    }

    /** Forgets which tool panels were minimized. */
    fun resetPanels() = panels.edit().clear().apply()
}
