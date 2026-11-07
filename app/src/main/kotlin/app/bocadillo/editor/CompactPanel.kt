package app.bocadillo.editor

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bocadillo.R

/** Description of the open tool for the minimized bar. */
class PanelInfo(
    /** Preference key: the expanded/minimized choice is remembered per tool. */
    val key: String,
    val title: String,
    val defaultExpanded: Boolean,
    val onCancel: () -> Unit,
    val onApply: (() -> Unit)?,
    val quick: @Composable RowScope.() -> Unit = {},
)

/** Remembers per tool whether its panel is expanded (persisted across sessions). */
class PanelPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("editor_ui", Context.MODE_PRIVATE)
    private val cache = mutableStateMapOf<String, Boolean>()
    fun expanded(info: PanelInfo): Boolean = cache[info.key] ?: prefs.getBoolean("panel_${info.key}", info.defaultExpanded)
    fun set(info: PanelInfo, value: Boolean) { cache[info.key] = value; prefs.edit().putBoolean("panel_${info.key}", value).apply() }
}

@Composable
fun rememberPanelPrefs(): PanelPrefs {
    val ctx = LocalContext.current
    return remember { PanelPrefs(ctx.applicationContext) }
}

/** One-line bar shown while a tool's panel is minimized: ✕ · title · quick actions · ▲ · ✓ */
@Composable
fun CompactToolBar(info: PanelInfo, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = info.onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
        Text(
            info.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onExpand),
        )
        info.quick(this)
        IconButton(onClick = onExpand) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.show_options)) }
        info.onApply?.let { IconButton(onClick = it) { Icon(Icons.Filled.Check, stringResource(R.string.apply), tint = MaterialTheme.colorScheme.primary) } }
    }
}

/** Small handle above an expanded panel to minimize it. */
@Composable
fun CollapseHandle(onCollapse: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(22.dp).clickable(onClick = onCollapse), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.hide_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
