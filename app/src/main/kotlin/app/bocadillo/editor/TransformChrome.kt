package app.bocadillo.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.ui.theme.Classic

fun transformModeLabel(m: TransformMode) = when (m) {
    TransformMode.SCALE_ROTATE -> R.string.tmode_scale_rotate
    TransformMode.SCALE -> R.string.tmode_scale
    TransformMode.ROTATE -> R.string.tmode_rotate
    TransformMode.STRETCH -> R.string.tmode_stretch
    TransformMode.SKEW -> R.string.tmode_skew
    TransformMode.PERSPECTIVE -> R.string.tmode_perspective
}

private fun transformModeHint(m: TransformMode) = when (m) {
    TransformMode.SCALE_ROTATE -> R.string.tmode_scale_rotate_hint
    TransformMode.SCALE -> R.string.tmode_scale_hint
    TransformMode.ROTATE -> R.string.tmode_rotate_hint
    TransformMode.STRETCH -> R.string.tmode_stretch_hint
    TransformMode.SKEW -> R.string.tmode_skew_hint
    TransformMode.PERSPECTIVE -> R.string.tmode_perspective_hint
}

/** Compact list of transformation modes, opened by tapping the blue handle. */
@Composable
fun TransformModeMenu(current: TransformMode, onPick: (TransformMode) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(10.dp).widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xF2202020))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transform_modes), color = Classic.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Classic.Text) }
        }
        HorizontalDivider(color = Classic.Divider)
        for (m in TransformMode.entries) {
            val sel = m == current
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (sel) Color(0xFF1F3B66) else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onPick(m) }.semantics { selected = sel }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(transformModeLabel(m)), color = Classic.Text, fontSize = 17.sp)
                    Text(stringResource(transformModeHint(m)), color = Classic.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (sel) Icon(Icons.Filled.Check, null, tint = Classic.Confirm)
            }
        }
        HorizontalDivider(color = Classic.Divider)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onReset).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Restore, null, tint = Classic.Text, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.transform_reset), color = Classic.Text, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** Bar shown while a mode other than the default is active: name and hint, Cancel ✕ and Done ✓. */
@Composable
fun TransformModeBar(mode: TransformMode, onMenu: () -> Unit, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.padding(8.dp).widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xE6202020)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.tmode_cancel), tint = Classic.Text) }
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onMenu).padding(vertical = 6.dp)) {
            Text(stringResource(transformModeLabel(mode)) + "  ▾", color = Classic.Text, fontSize = 16.sp, maxLines = 1)
            Text(stringResource(transformModeHint(mode)), color = Classic.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        app.bocadillo.ui.classic.ConfirmButton(stringResource(R.string.apply), onDone, description = stringResource(R.string.tmode_done))
    }
}
