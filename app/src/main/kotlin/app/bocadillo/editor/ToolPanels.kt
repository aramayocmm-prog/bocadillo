package app.bocadillo.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import app.bocadillo.core.imaging.ops.Geometry
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import java.util.Locale
import kotlin.math.roundToInt

/** Header row shared by all tool panels: cancel · title · (compare) · apply. */
@Composable
fun PanelHeader(title: String, onCancel: () -> Unit, onApply: () -> Unit, onCompare: ((Boolean) -> Unit)? = null) {
    if (LocalClassicChrome.current) {
        // the classic top bar already shows ← title ✓; keep only the press-to-compare button
        if (onCompare != null) Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) { CompareButton(onCompare) }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onCompare != null) {
            // press and hold to see the picture without this tool
            Box(
                Modifier.size(48.dp).pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(); onCompare(true)
                        waitForUpOrCancellation(); onCompare(false)
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Compare, null) }
        }
        IconButton(onClick = onApply) { Icon(Icons.Filled.Check, stringResource(R.string.apply)) }
    }
}

/** True inside the classic editor: panels drop their own header (the top bar has it). */
val LocalClassicChrome = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit) {
    Box(
        Modifier.size(40.dp).pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); onCompare(true); waitForUpOrCancellation(); onCompare(false) }
        },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Compare, stringResource(R.string.compare)) }
}

/** Builds controls from the op's [ParamSpec]s — new tools need no UI code. */
@Composable
fun ParamControls(specs: List<ParamSpec>, params: Params, onChange: ((Params) -> Params) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        for (spec in specs) when (spec) {
            is ParamSpec.Slider -> SliderRow(spec, params.float(spec.key, spec.default)) { v -> onChange { it.with(spec.key, v) } }
            is ParamSpec.Toggle -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Labels.param(spec.labelKey)), Modifier.weight(1f))
                Switch(checked = params.bool(spec.key, spec.default), onCheckedChange = { v -> onChange { it.with(spec.key, v) } })
            }
            is ParamSpec.Choice -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (opt in spec.options) FilterChip(
                    selected = params.string(spec.key, spec.default) == opt,
                    onClick = { onChange { it.with(spec.key, opt) } },
                    label = { Text(Labels.choice(opt)?.let { stringResource(it) } ?: opt) },
                )
            }
            is ParamSpec.Color -> ColorRow(stringResource(Labels.param(spec.labelKey)), params.color(spec.key, spec.default)) { c ->
                onChange { it.withColor(spec.key, c) }
            }
            is ParamSpec.Data -> Unit // edited by the tool's own control (curve, warp, red-eye)
            is ParamSpec.Point -> Text(
                stringResource(R.string.drag_point_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SliderRow(spec: ParamSpec.Slider, value: Float, onValue: (Float) -> Unit) {
    val shown = value * spec.displayFactor
    val unit = when (spec.unit) { ParamSpec.Unit.DEGREES -> "°"; ParamSpec.Unit.PERCENT -> "%"; ParamSpec.Unit.PIXELS -> " px"; else -> "" }
    val text = if (spec.step * spec.displayFactor >= 1f) "${shown.roundToInt()}$unit" else String.format(Locale.getDefault(), "%.2f%s", shown, unit)
    // signed value like the reference ("+0.50") when the range goes below zero
    val signed = if (spec.min < 0f && shown > 0f) "+$text" else text
    app.bocadillo.ui.classic.ClassicSlider(
        stringResource(Labels.param(spec.labelKey)), value, spec.min..spec.max, spec.step, signed, onChange = onValue,
    )
}

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0x00000000, 0xFF808080, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFF6D4C41,
).map { it.toInt() }

@Composable
fun ColorRow(label: String, selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in SWATCHES) {
                val isSel = c == selected
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(if (c == 0) Color.LightGray else Color(c))
                        .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { onPick(c) },
                    contentAlignment = Alignment.Center,
                ) { if (c == 0) Text("∅", color = Color.DarkGray) }
            }
        }
    }
}

@Composable
fun AdjustPanel(tool: ToolState.Adjust, onChange: ((Params) -> Params) -> Unit, onReset: () -> Unit, onApply: () -> Unit, onCancel: () -> Unit, onCompare: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        PanelHeader(stringResource(Labels.op(tool.op.id)), onCancel, onApply, onCompare)
        // long effects (tilt-shift, focal blur…) scroll instead of covering the photo
        when (tool.op.id) {
            "curves" -> CurvesEditor(tool.params, onChange)
            else -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                toolHint(tool.op.id)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp, 2.dp)) }
                ParamControls(tool.op.params, tool.params, onChange)
            }
        }
        if (tool.op.params.isNotEmpty()) TextButton(onClick = onReset, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.reset)) }
    }
}

/** Short instructions for tools that are used on the photo itself. */
private fun toolHint(id: String): Int? = when (id) {
    "warp_brush" -> R.string.warp_hint
    "fix_red_eye" -> R.string.red_eye_hint
    "pop_color" -> R.string.pop_hint
    else -> null
}

@Composable
fun CropPanel(tool: ToolState.Crop, onPreset: (Geometry.CropPreset, Float?) -> Unit, onStraighten: (Float) -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
    val cfg = LocalConfiguration.current
    val wallpaper = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat() / maxOf(cfg.screenWidthDp, cfg.screenHeightDp)
    var customDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        PanelHeader(stringResource(R.string.op_crop), onCancel, onApply)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presets = listOf(
                Geometry.CropPreset.FREEFORM to R.string.crop_free,
                Geometry.CropPreset.ORIGINAL to R.string.crop_original,
                Geometry.CropPreset.GOLDEN to R.string.crop_golden,
                Geometry.CropPreset.SQUARE to R.string.crop_square,
                Geometry.CropPreset.WALLPAPER to R.string.crop_wallpaper,
                Geometry.CropPreset.CUSTOM to R.string.crop_custom,
            )
            for ((p, label) in presets) FilterChip(
                selected = tool.preset == p,
                onClick = {
                    when (p) {
                        Geometry.CropPreset.CUSTOM -> customDialog = true
                        Geometry.CropPreset.WALLPAPER -> onPreset(p, wallpaper)
                        else -> onPreset(p, p.ratio)
                    }
                },
                label = { Text(stringResource(label)) },
            )
        }
        val spec = TransformOps.Crop.params.first() as ParamSpec.Slider
        Column(Modifier.padding(horizontal = 16.dp)) { SliderRow(spec, tool.angle, onStraighten) }
    }
    if (customDialog) RatioDialog(onDismiss = { customDialog = false }) { w, h ->
        customDialog = false; onPreset(Geometry.CropPreset.CUSTOM, w / h)
    }
}

@Composable
internal fun RatioDialog(onDismiss: () -> Unit, onSet: (Float, Float) -> Unit) {
    var w by remember { mutableStateOf("4") }
    var h by remember { mutableStateOf("3") }
    val wv = w.toFloatOrNull(); val hv = h.toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crop_custom_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(w, { w = it.take(5) }, Modifier.width(96.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("  :  ")
                OutlinedTextField(h, { h = it.take(5) }, Modifier.width(96.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton(enabled = wv != null && hv != null && wv > 0 && hv > 0, onClick = { onSet(wv!!, hv!!) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun OrientPanel(onOrient: (rotation: Int, flipH: Boolean, flipV: Boolean) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        PanelHeader(stringResource(R.string.op_orient), onClose, onClose)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, v) in listOf(
                R.string.rotate_left to Triple(270, false, false), R.string.rotate_right to Triple(90, false, false),
                R.string.rotate_180 to Triple(180, false, false), R.string.flip_h to Triple(0, true, false), R.string.flip_v to Triple(0, false, true),
            )) {
                val (rot, fh, fv) = v
                ToolTile(stringResource(label), "orient:$rot:$fh:$fv", { onOrient(rot, fh, fv) }) { sample ->
                    val op = app.bocadillo.core.imaging.ops.TransformOps.Orient
                    op.apply(sample, app.bocadillo.core.model.Params.of("rotation" to rot.toString(), "flipH" to fh, "flipV" to fv), app.bocadillo.core.imaging.RenderContext())
                }
            }
        }
    }
}

@Composable
private fun OrientButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, rotateIcon: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp).width(84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(28.dp).rotate(if (rotateIcon) 90f else 0f))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}


/** "Resize" dialog: width × height with keep-proportions and the five original modes. */
@Composable
fun ResizeDialog(currentW: Int, currentH: Int, onApply: (Int, Int, String, Int) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(currentW.toString()) }
    var h by remember { mutableStateOf(currentH.toString()) }
    var keep by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf("stretch") }
    var bg by remember { mutableStateOf(0xFFFFFFFF.toInt()) }
    val ratio = currentW.toFloat() / currentH
    val wv = w.toIntOrNull(); val hv = h.toIntOrNull()
    val valid = wv != null && hv != null && wv in 1..20000 && hv in 1..20000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resize_title)) },
        text = {
            Column {
                Text(stringResource(R.string.resize_current, currentW, currentH), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(w, { s ->
                        w = s.filter(Char::isDigit).take(5)
                        if (keep) w.toIntOrNull()?.let { h = maxOf(1, (it / ratio).roundToInt()).toString() }
                    }, Modifier.width(110.dp), label = { Text(stringResource(R.string.p_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("  ×  ")
                    OutlinedTextField(h, { s ->
                        h = s.filter(Char::isDigit).take(5)
                        if (keep) h.toIntOrNull()?.let { w = maxOf(1, (it * ratio).roundToInt()).toString() }
                    }, Modifier.width(110.dp), label = { Text(stringResource(R.string.p_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }); Text(stringResource(R.string.keep_proportions))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((m, label) in listOf(
                        "stretch" to R.string.resize_mode_stretch, "fit" to R.string.resize_mode_fit, "fill" to R.string.resize_mode_fill,
                        "crop" to R.string.resize_mode_crop, "expand" to R.string.resize_mode_expand,
                    )) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(stringResource(label)) })
                }
                if (mode == "expand" || mode == "crop" || mode == "fill") ColorRow(stringResource(R.string.p_background_color), bg) { bg = it }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(wv!!, hv!!, mode, bg) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Small surface used as the bottom panel container. */
@Composable
fun BottomPanel(content: @Composable () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) { content() }
}
