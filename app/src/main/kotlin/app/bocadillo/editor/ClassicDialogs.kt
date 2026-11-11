package app.bocadillo.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.bocadillo.R
import app.bocadillo.ui.theme.Classic
import java.util.Locale

/** "Rotate and Flip" pop-up list (shown over the Adjust menu, as in the original). */
@Composable
fun RotateFlipDialog(onDismiss: () -> Unit, onPick: (rotation: Int, flipH: Boolean, flipV: Boolean) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).background(Color(0xFFFAFAFA))) {
            Text(stringResource(R.string.op_orient), color = Classic.Accent, fontSize = 20.sp, modifier = Modifier.padding(18.dp, 16.dp))
            HorizontalDivider(color = Classic.Accent, thickness = 2.dp)
            for ((label, v) in listOf(
                R.string.rotate_90_left to Triple(270, false, false), R.string.rotate_90_right to Triple(90, false, false),
                R.string.rotate_180_menu to Triple(180, false, false), R.string.flip_horizontal to Triple(0, true, false),
                R.string.flip_vertical to Triple(0, false, true),
            )) {
                Text(
                    stringResource(label), color = Color(0xFF212121), fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(v.first, v.second, v.third) }.padding(18.dp, 14.dp),
                )
                HorizontalDivider(color = Color(0xFFDDDDDD))
            }
        }
    }
}

/** Picture Location: the GPS position from the photo's EXIF data, with a link to a maps app. */
@Composable
fun PictureLocationDialog(location: Pair<Double, Double>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_title)) },
        text = {
            Text(
                if (location == null) stringResource(R.string.location_none)
                else stringResource(R.string.location_found, "%.6f".format(Locale.US, location.first), "%.6f".format(Locale.US, location.second)),
            )
        },
        confirmButton = {
            if (location != null) TextButton({
                val (lat, lon) = location
                val geo = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                onDismiss()
            }) { Text(stringResource(R.string.location_open_map)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/**
 * Classic "Set Size" (reference WA0112): a dark panel with the two sizes and Keep Proportions,
 * a short explanation, then the light list Scale… · Expand Edges… · Quick Fit, each applying the
 * size its own way. "More options…" opens the full dialog (fill and crop modes) of earlier versions.
 */
@Composable
fun SetSizeDialog(currentW: Int, currentH: Int, onApply: (Int, Int, String, Int) -> Unit, onMore: () -> Unit, onDismiss: () -> Unit) {
    var w by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(currentW.toString()) }
    var h by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(currentH.toString()) }
    var keep by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var expand by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val ratio = currentW.toFloat() / currentH
    val wv = w.toIntOrNull(); val hv = h.toIntOrNull()
    val valid = wv != null && hv != null && wv in 1..20000 && hv in 1..20000
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF262626)).padding(14.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.set_size), color = app.bocadillo.ui.theme.Classic.Text, fontSize = 18.sp, modifier = androidx.compose.ui.Modifier.weight(1f))
                    SizeField(w, androidx.compose.ui.res.stringResource(R.string.p_width)) { s -> w = s; if (keep) s.toIntOrNull()?.let { h = maxOf(1, Math.round(it / ratio)).toString() } }
                    androidx.compose.material3.Text("  ×  ", color = app.bocadillo.ui.theme.Classic.Text)
                    SizeField(h, androidx.compose.ui.res.stringResource(R.string.p_height)) { s -> h = s; if (keep) s.toIntOrNull()?.let { w = maxOf(1, Math.round(it * ratio)).toString() } }
                }
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
                        keep = !keep
                        if (keep) w.toIntOrNull()?.let { h = maxOf(1, Math.round(it / ratio)).toString() }
                    },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.keep_proportions), color = app.bocadillo.ui.theme.Classic.Text, fontSize = 17.sp)
                    androidx.compose.material3.Checkbox(keep, null)
                }
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.set_size_help), color = app.bocadillo.ui.theme.Classic.TextDim, fontSize = 13.sp)
            }
            val light = androidx.compose.ui.graphics.Color(0xFFFAFAFA)
            val enabledText = androidx.compose.ui.graphics.Color(0xFF212121)
            val disabledText = androidx.compose.ui.graphics.Color(0xFFAAAAAA)
            for ((label, action) in listOf(
                R.string.resize_scale to { onApply(wv!!, hv!!, "stretch", 0xFFFFFFFF.toInt()) },
                R.string.resize_expand_edges to { expand = true },
                R.string.resize_quick_fit to { onApply(wv!!, hv!!, "fit", 0xFFFFFFFF.toInt()) },
            )) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(label), color = if (valid) enabledText else disabledText, fontSize = 18.sp,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().background(light).clickable(enabled = valid) { action() }.padding(horizontal = 18.dp, vertical = 14.dp),
                )
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE0E0E0))
            }
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(R.string.more_options), color = app.bocadillo.ui.theme.Classic.Accent, fontSize = 16.sp,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().background(light).clickable { onMore() }.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
    if (expand) {
        var bg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0xFFFFFFFF.toInt()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { expand = false },
            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.resize_expand_edges)) },
            text = { ColorRow(androidx.compose.ui.res.stringResource(R.string.p_background_color), bg) { bg = it } },
            confirmButton = { androidx.compose.material3.TextButton({ expand = false; onApply(wv!!, hv!!, "expand", bg) }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.apply)) } },
            dismissButton = { androidx.compose.material3.TextButton({ expand = false }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SizeField(value: String, label: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value, { onChange(it.filter(Char::isDigit).take(5)) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = app.bocadillo.ui.theme.Classic.Text, fontSize = 18.sp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(app.bocadillo.ui.theme.Classic.Accent),
        modifier = androidx.compose.ui.Modifier.width(72.dp).semantics { contentDescription = label },
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Column {
                inner()
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(2.dp).background(app.bocadillo.ui.theme.Classic.Accent))
            }
        },
    )
}
