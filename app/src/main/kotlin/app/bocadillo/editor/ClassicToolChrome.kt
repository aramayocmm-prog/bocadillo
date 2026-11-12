package app.bocadillo.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.ui.theme.Classic
import kotlin.math.roundToInt

/** Colours of the classic tool screens (measured: bars #252525/#262626, work area #444444). */
object ToolChrome {
    val Bar = Color(0xFF252525)
    val Bottom = Color(0xFF262626)
    val Work = Color(0xFF444444)
}

/** Adjust-type tools shown with the classic chrome (a main slider below, options under ⋮). */
fun usesClassicChrome(t: ToolState.Adjust) = t.op.id != "curves" && t.op.params.any { it is ParamSpec.Slider }

/**
 * Top bar of a tool: ↶ (back to the values the tool opened with), "⋮ Name value" (menu with the
 * parameter the slider controls and all options), the compare square (hold), and ✕ while
 * nothing changed or a green ✓ once it did.
 */
@Composable
fun ClassicToolTopBar(
    tool: ToolState.Adjust,
    activeKey: String,
    onActive: (String) -> Unit,
    onReset: () -> Unit,
    onCompare: (Boolean) -> Unit,
    onMoreOptions: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val sliders = tool.op.params.filterIsInstance<ParamSpec.Slider>()
    val active = sliders.firstOrNull { it.key == activeKey } ?: sliders.first()
    // anything that changes the picture can be applied; otherwise ✕ just closes
    val changed = !tool.op.isIdentity(tool.params)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp).background(ToolChrome.Bar), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onReset) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.reset), tint = Classic.TextDim) }
        Box(Modifier.weight(1f)) {
            Row(Modifier.clickable { menu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MoreVert, null, tint = Classic.TextDim, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(Labels.op(tool.op.id)) + "  " + valueText(active, tool.params.float(active.key, active.default)),
                    color = Classic.Text, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(menu, { menu = false }) {
                if (sliders.size > 1) for (s in sliders) DropdownMenuItem(
                    text = { Text((if (s.key == active.key) "● " else "   ") + stringResource(Labels.param(s.labelKey))) },
                    onClick = { onActive(s.key); menu = false },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.more_options)) }, onClick = { menu = false; onMoreOptions() })
                DropdownMenuItem(text = { Text(stringResource(R.string.reset)) }, onClick = { menu = false; onReset() })
            }
        }
        // hold to see the picture without the tool
        Box(
            Modifier.size(44.dp).pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(); onCompare(true); waitForUpOrCancellation(); onCompare(false) }
            },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(18.dp).border(2.dp, Classic.TextDim)) }
        if (changed) app.bocadillo.ui.classic.ConfirmButton(stringResource(R.string.apply), onApply)
        else IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel), tint = Classic.Text) }
    }
}

/** Bottom bar of a tool: − slider + for the active parameter. */
@Composable
fun ClassicToolSliderBar(tool: ToolState.Adjust, activeKey: String, onChange: ((app.bocadillo.core.model.Params) -> app.bocadillo.core.model.Params) -> Unit) {
    val sliders = tool.op.params.filterIsInstance<ParamSpec.Slider>()
    val s = sliders.firstOrNull { it.key == activeKey } ?: sliders.first()
    val v = tool.params.float(s.key, s.default)
    fun set(x: Float) {
        val snapped = if (s.step > 0f) (((x - s.min) / s.step).roundToInt() * s.step + s.min) else x
        onChange { it.with(s.key, snapped.coerceIn(s.min, s.max)) }
    }
    val step = if (s.step > 0f) s.step else (s.max - s.min) / 100f
    Row(Modifier.fillMaxWidth().height(56.dp).background(ToolChrome.Bottom).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−") { set(v - step) }
        Slider(
            value = v, onValueChange = ::set, valueRange = s.min..s.max, modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF9E9E9E), inactiveTrackColor = Color(0xFF9E9E9E)),
        )
        StepButton("+") { set(v + step) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Classic.TextDim, fontSize = 28.sp)
    }
}

/** "+0.50", "+75°", "40%"… as the classic title shows it. */
fun valueText(s: ParamSpec.Slider, v: Float): String {
    val shown = v * s.displayFactor
    val num = if (s.step * s.displayFactor >= 1f) shown.roundToInt().toString() else "%.2f".format(shown)
    val sign = if (s.min < 0f && shown > 0f) "+" else ""
    val unit = when (s.unit) { ParamSpec.Unit.PERCENT -> "%"; ParamSpec.Unit.DEGREES -> "°"; ParamSpec.Unit.PIXELS -> "px"; else -> "" }
    return sign + num + unit
}


/**
 * Classic Crop bar (reference WA0117): ↶ (back to the whole picture), "Crop W × H" with the size
 * of the result in pixels, the frame button (proportions menu) and ✕ — or a green ✓ once the
 * frame was changed.
 */
@Composable
fun CropTopBar(
    tool: ToolState.Crop,
    pixelW: Int,
    pixelH: Int,
    wallpaperRatio: Float,
    onReset: () -> Unit,
    onPreset: (app.bocadillo.core.imaging.ops.Geometry.CropPreset, Float?) -> Unit,
    onCustomRatio: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val r = tool.rect
    val w = ((r[2] - r[0]) * pixelW).roundToInt(); val h = ((r[3] - r[1]) * pixelH).roundToInt()
    val changed = tool.angle != 0f || r[0] > 0.0005f || r[1] > 0.0005f || r[2] < 0.9995f || r[3] < 0.9995f
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).background(ToolChrome.Work).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ChromeSquare(stringResource(R.string.reset), onReset) { Icon(Icons.AutoMirrored.Filled.Undo, null, tint = Classic.Text) }
        Row(
            Modifier.weight(1f).padding(horizontal = 4.dp).height(46.dp).background(ToolChrome.Bar, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.crop_short) + "  $w × $h", color = Classic.Text, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.CropFree, stringResource(R.string.crop_proportions), tint = Classic.Text) }
                DropdownMenu(menu, { menu = false }) {
                    val presets = listOf(
                        app.bocadillo.core.imaging.ops.Geometry.CropPreset.FREEFORM to R.string.crop_free,
                        app.bocadillo.core.imaging.ops.Geometry.CropPreset.ORIGINAL to R.string.crop_original,
                        app.bocadillo.core.imaging.ops.Geometry.CropPreset.GOLDEN to R.string.crop_golden,
                        app.bocadillo.core.imaging.ops.Geometry.CropPreset.SQUARE to R.string.crop_square,
                        app.bocadillo.core.imaging.ops.Geometry.CropPreset.WALLPAPER to R.string.crop_wallpaper,
                        app.bocadillo.core.imaging.ops.Geometry.CropPreset.CUSTOM to R.string.crop_custom,
                    )
                    for ((p, label) in presets) DropdownMenuItem(
                        text = { Text((if (tool.preset == p) "● " else "   ") + stringResource(label)) },
                        onClick = {
                            menu = false
                            when (p) {
                                app.bocadillo.core.imaging.ops.Geometry.CropPreset.CUSTOM -> onCustomRatio()
                                app.bocadillo.core.imaging.ops.Geometry.CropPreset.WALLPAPER -> onPreset(p, wallpaperRatio)
                                else -> onPreset(p, p.ratio)
                            }
                        },
                    )
                }
            }
        }
        if (changed) ChromeSquare(stringResource(R.string.apply), onApply) { app.bocadillo.ui.classic.CheckWithLabel(stringResource(R.string.apply)) }
        else ChromeSquare(stringResource(R.string.cancel), onCancel) { Icon(Icons.Filled.Close, null, tint = Classic.Text) }
    }
}

@Composable
private fun ChromeSquare(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(46.dp).background(ToolChrome.Bar, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Bottom bar of Crop: − slider + straightening the picture (−45…45°, centre = 0°). */
@Composable
fun CropSliderBar(tool: ToolState.Crop, onStraighten: (Float) -> Unit) {
    val spec = app.bocadillo.core.imaging.ops.TransformOps.Crop.params.first() as ParamSpec.Slider
    val step = if (spec.step > 0f) spec.step else 0.5f
    val label = stringResource(R.string.straighten)
    Row(Modifier.fillMaxWidth().height(56.dp).background(ToolChrome.Bottom).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−") { onStraighten((tool.angle - step).coerceIn(spec.min, spec.max)) }
        Slider(
            value = tool.angle, onValueChange = onStraighten, valueRange = spec.min..spec.max,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp).semantics { contentDescription = label + " " + valueText(spec, tool.angle) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF9E9E9E), inactiveTrackColor = Color(0xFF9E9E9E)),
        )
        StepButton("+") { onStraighten((tool.angle + step).coerceIn(spec.min, spec.max)) }
    }
}
