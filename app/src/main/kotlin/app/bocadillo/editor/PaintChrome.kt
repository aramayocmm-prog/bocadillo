package app.bocadillo.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush as GBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.ui.classic.ClassicIcon
import app.bocadillo.ui.classic.ClassicIconView
import app.bocadillo.ui.theme.Classic
import kotlin.math.roundToInt

/** Drop-down panels of the classic Paint bar. */
enum class PaintPanel { SETTINGS, BRUSH, COLOR }

private val PanelBg = Color(0xF0262626)
private val PillBg = Color(0xFF2C2C2C)
private val Active = Color(0xFF2F8CFF)

/**
 * Classic Paint top bar (reference: Paint captures): ↶ (and ↷) in dark rounded squares on the
 * left, the central pill "Paint ■ brush eraser", and ✕ — which becomes a green ✓ once something
 * was painted. Tapping "Paint" opens Brush Settings, the colour square the colour panel, the
 * brush the brush panel; the eraser switches to the transparent eraser.
 */
@Composable
fun PaintTopBar(
    tool: ToolState.Draw,
    panel: PaintPanel?,
    onPanel: (PaintPanel?) -> Unit,
    onKind: (DrawKind) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val painted = tool.strokes.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(ToolChrome.Work).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareButton(stringResource(R.string.undo), enabled = painted, onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, null, tint = if (painted) Classic.Text else Classic.Cell) }
        SquareButton(stringResource(R.string.redo), enabled = tool.redo.isNotEmpty(), onClick = onRedo) { Icon(Icons.AutoMirrored.Filled.Redo, null, tint = if (tool.redo.isNotEmpty()) Classic.Text else Classic.Cell) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(46.dp).clip(RoundedCornerShape(8.dp)).background(PillBg).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = stringResource(when (tool.kind) {
                DrawKind.BRUSH -> R.string.paint; DrawKind.MARKER -> R.string.marker
                DrawKind.ERASER -> R.string.eraser; DrawKind.HISTORY -> R.string.history_paint
            })
            Text(
                name, color = Classic.Text, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { onPanel(if (panel == PaintPanel.SETTINGS) null else PaintPanel.SETTINGS) }
                    .semantics { contentDescription = name + ", " + "Brush Settings" }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            // active colour
            val colorLabel = stringResource(R.string.brush_color)
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { onPanel(if (panel == PaintPanel.COLOR) null else PaintPanel.COLOR) }
                    .semantics { contentDescription = colorLabel },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(26.dp).background(Color(tool.brush.color or 0xFF000000.toInt())).border(1.dp, Color(0x55FFFFFF))) }
            val brushSel = tool.kind != DrawKind.ERASER
            val brushLabel = stringResource(R.string.brush)
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) {
                    if (tool.kind == DrawKind.ERASER) onKind(DrawKind.BRUSH)
                    onPanel(if (panel == PaintPanel.BRUSH) null else PaintPanel.BRUSH)
                }.semantics { contentDescription = brushLabel; selected = brushSel },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Brush, null, tint = if (brushSel) Active else Classic.TextDim, modifier = Modifier.size(28.dp)) }
            val eraserLabel = stringResource(R.string.eraser_transparent)
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { onKind(if (tool.kind == DrawKind.ERASER) DrawKind.BRUSH else DrawKind.ERASER); onPanel(null) }
                    .semantics { contentDescription = eraserLabel; selected = !brushSel },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(if (!brushSel) Active.copy(alpha = 0.35f) else Color.Transparent), contentAlignment = Alignment.Center) {
                    ClassicIconView(ClassicIcon.ERASER, 30.dp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (painted) SquareButton(stringResource(R.string.apply), onClick = onApply) { Icon(Icons.Filled.Check, null, tint = Classic.Confirm, modifier = Modifier.size(30.dp)) }
        else SquareButton(stringResource(R.string.cancel), onClick = onCancel) { Icon(Icons.Filled.Close, null, tint = Classic.Text) }
    }
}

@Composable
private fun SquareButton(label: String, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(46.dp).clip(RoundedCornerShape(8.dp)).background(PillBg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The open drop-down panel, below the bar and over the picture (as in the reference). Its
 * content scrolls, so nothing is cut on small screens, in landscape or with large text.
 */
@Composable
fun PaintDropPanel(
    panel: PaintPanel,
    tool: ToolState.Draw,
    stages: List<Pair<Int, String>>,
    stageLabel: (String) -> String,
    customColors: List<Int>,
    onAddColor: (Int) -> Unit,
    onKind: (DrawKind) -> Unit,
    onBrush: (BrushSettings) -> Unit,
    onStage: (Int) -> Unit,
    onEyedropper: () -> Unit,
    onClose: () -> Unit,
    onCompare: (Boolean) -> Unit,
) {
    val b = tool.brush
    Column(
        Modifier.padding(horizontal = 10.dp).widthIn(max = 460.dp).fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)).background(PanelBg),
    ) {
        // header: title (or colour swatch) and ^ to fold the panel
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (panel) {
                PaintPanel.SETTINGS -> {
                    CompareSquare(onCompare)
                    Text(stringResource(R.string.brush_settings), color = Classic.Text, fontSize = 19.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                PaintPanel.BRUSH -> Text(stringResource(R.string.brush), color = Classic.Text, fontSize = 19.sp, modifier = Modifier.weight(1f))
                PaintPanel.COLOR -> {
                    // eyedropper: icon only, beside the active colour, never over the palette
                    IconButton(onClick = onEyedropper, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Colorize, stringResource(R.string.eyedropper), tint = if (tool.eyedropper) Active else Classic.Text)
                    }
                    Box(Modifier.padding(start = 6.dp).weight(1f).height(30.dp).background(Color(b.color or 0xFF000000.toInt())).border(1.dp, Color(0x55FFFFFF)))
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.collapse), tint = Classic.Text) }
        }
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            when (panel) {
                PaintPanel.SETTINGS -> {
                    Check(stringResource(R.string.brush_relative_screen), b.relativeToScreen) { onBrush(b.copy(relativeToScreen = it)) }
                    Check(stringResource(R.string.pressure_strength), b.pressureOpacity) { onBrush(b.copy(pressureOpacity = it)) }
                    Check(stringResource(R.string.pressure_size_long), b.pressureSize) { onBrush(b.copy(pressureSize = it)) }
                    Text(stringResource(R.string.paint_mode), color = Classic.SectionText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    // modes: wraps by scrolling sideways, never truncated
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((k, label) in listOf(DrawKind.BRUSH to R.string.brush, DrawKind.MARKER to R.string.marker, DrawKind.ERASER to R.string.eraser_transparent, DrawKind.HISTORY to R.string.history_paint))
                            ModeChip(stringResource(label), tool.kind == k) { onKind(k) }
                    }
                    if (tool.kind == DrawKind.HISTORY) {
                        if (stages.isEmpty()) Text(stringResource(R.string.history_paint_unavailable), color = Classic.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        else {
                            Text(stringResource(R.string.history_pick_stage), color = Classic.SectionText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((k, id) in stages) ModeChip(stageLabel(id), tool.stage == k) { onStage(k) }
                            }
                        }
                    }
                    if (tool.kind != DrawKind.HISTORY) ValueSlider(stringResource(R.string.opacity), b.opacity, 0.05f..1f, 0.05f, { "${(it * 100).roundToInt()}%" }) { onBrush(b.copy(opacity = it)) }
                }
                PaintPanel.BRUSH -> {
                    BrushPresets(b) { size, hard -> onBrush(b.copy(size = size, hardness = hard)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ValueSlider(stringResource(R.string.brush_size), b.size, 0.003f..0.25f, 0.002f, { "${(it * 100).roundToInt()}%" }) { onBrush(b.copy(size = it)) }
                            ValueSlider(stringResource(R.string.brush_hardness), b.hardness, 0f..1f, 0.05f, { "${(it * 100).roundToInt()}%" }) { onBrush(b.copy(hardness = it)) }
                        }
                        BrushPreview(b, Modifier.padding(start = 8.dp).size(72.dp))
                    }
                }
                PaintPanel.COLOR -> {
                    if (tool.eyedropper) Text(stringResource(R.string.eyedropper_hint), color = Active, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                    HsbSliders(b.color) { onBrush(b.copy(color = it)) }
                    Text(stringResource(R.string.palette), color = Classic.SectionText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    // horizontal palette carousel; + keeps the current colour in it
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val addLabel = stringResource(R.string.palette_add)
                        Box(
                            Modifier.size(44.dp).background(Color(0xFF333333)).clickable(role = Role.Button) { onAddColor(b.color or 0xFF000000.toInt()) }
                                .semantics { contentDescription = addLabel },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = Classic.Text) }
                        for (c in (customColors + PAINT_PALETTE).distinct()) {
                            val sel = (c or 0xFF000000.toInt()) == (b.color or 0xFF000000.toInt())
                            Box(
                                Modifier.size(44.dp).background(Color(c))
                                    .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color(0x33FFFFFF))
                                    .clickable(role = Role.Button) { onBrush(b.copy(color = c)) }
                                    .semantics { contentDescription = "#%06X".format(c and 0xFFFFFF); selected = sel },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PAINT_PALETTE = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFF5D4037, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF3949AB,
    0xFF1E88E5, 0xFF00ACC1, 0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFB8C00, 0xFFF4511E, 0xFFFFCCBC,
).map { it.toInt() }

@Composable
private fun CompareSquare(onCompare: (Boolean) -> Unit) {
    val label = stringResource(R.string.compare)
    Box(
        Modifier.size(40.dp).semantics { contentDescription = label }.pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); onCompare(true); waitForUpOrCancellation(); onCompare(false) }
        },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(18.dp).border(2.dp, Classic.TextDim)) }
}

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Checkbox) { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, null, colors = CheckboxDefaults.colors(checkedColor = Active, uncheckedColor = Classic.TextDim, checkmarkColor = Color.White))
        Text(label, color = Classic.Text, fontSize = 16.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.White else Classic.TextDim, fontSize = 15.sp, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) Active else Color(0xFF3A3A3A))
            .clickable(role = Role.Tab) { onClick() }.semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Name, value, − slider + (each button one step). */
@Composable
fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, display: (Float) -> String, onValue: (Float) -> Unit) {
    fun set(v: Float) = onValue(v.coerceIn(range.start, range.endInclusive))
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Classic.Text, fontSize = 14.sp, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
            Text(display(value), color = Classic.Text, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepCircle("−", stringResource(R.string.decrease) + " " + label) { set(value - step) }
            Slider(
                value, ::set, valueRange = range, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFE0E0E0), activeTrackColor = Active, inactiveTrackColor = Color(0xFF555555)),
            )
            StepCircle("+", stringResource(R.string.increase) + " " + label) { set(value + step) }
        }
    }
}

@Composable
private fun StepCircle(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(32.dp).border(1.5.dp, Classic.TextDim, CircleShape), contentAlignment = Alignment.Center) { Text(text, color = Classic.Text, fontSize = 20.sp) } }
}

/** Grid of ready brushes: soft and hard tips of decreasing size. */
@Composable
private fun BrushPresets(b: BrushSettings, onPick: (Float, Float) -> Unit) {
    val sizes = listOf(0.16f, 0.1f, 0.06f, 0.035f, 0.02f, 0.008f)
    for (hard in listOf(0.15f, 1f)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in sizes) {
                val sel = kotlin.math.abs(b.size - s) < 0.001f && kotlin.math.abs(b.hardness - hard) < 0.01f
                val label = "${(s * 100).roundToInt()}% · ${(hard * 100).roundToInt()}%"
                Box(
                    Modifier.size(48.dp).clickable(role = Role.Button) { onPick(s, hard) }.semantics { contentDescription = label; selected = sel },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(44.dp)) {
                        val r = size.minDimension / 2f * (0.25f + 0.75f * s / sizes.first())
                        val col = if (sel) Active else Color(0xFFDDDDDD)
                        drawCircle(GBrush.radialGradient(
                            0f to col, hard.coerceAtMost(0.99f) to col, 1f to col.copy(alpha = 0f),
                            center = center, radius = r,
                        ), r)
                    }
                }
            }
        }
    }
}

/** Circle with the brush diameter (relative to the preview box) and its soft edge. */
@Composable
private fun BrushPreview(b: BrushSettings, modifier: Modifier) {
    val label = stringResource(R.string.brush_preview)
    Canvas(modifier.background(Color(0xFF3A3A3A), RoundedCornerShape(6.dp)).semantics { contentDescription = label }) {
        val r = (size.minDimension / 2f) * (b.size / 0.25f).coerceIn(0.04f, 1f)
        val c = Color(b.color or 0xFF000000.toInt()).copy(alpha = b.opacity)
        drawCircle(GBrush.radialGradient(0f to c, b.hardness.coerceIn(0.01f, 0.99f) to c, 1f to c.copy(alpha = 0f), center = center, radius = r), r)
        drawCircle(Color(0x88FFFFFF), r, style = Stroke(1.dp.toPx()))
    }
}

/** Hue, Saturation, Brightness and Opacity sliders with values, on gradient tracks. */
@Composable
fun HsbSliders(color: Int, onColor: (Int) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
    val alpha = (color ushr 24) / 255f
    fun emit(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2], a: Float = alpha) =
        onColor(android.graphics.Color.HSVToColor((a * 255).roundToInt().coerceIn(1, 255), floatArrayOf(h, s, v)))
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    val opaque = Color(color or 0xFF000000.toInt())
    GradientSlider(stringResource(R.string.hue), "${hsv[0].roundToInt()}°", hsv[0] / 360f,
        GBrush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) })) { emit(h = it * 360f) }
    GradientSlider(stringResource(R.string.saturation), "${(hsv[1] * 100).roundToInt()}%", hsv[1], GBrush.horizontalGradient(listOf(Color.White, pure))) { emit(s = it) }
    GradientSlider(stringResource(R.string.brightness), "${(hsv[2] * 100).roundToInt()}%", hsv[2], GBrush.horizontalGradient(listOf(Color.Black, pure))) { emit(v = it) }
    GradientSlider(stringResource(R.string.opacity), "${(alpha * 100).roundToInt()}%", alpha, GBrush.horizontalGradient(listOf(opaque.copy(alpha = 0.05f), opaque))) { emit(a = it.coerceAtLeast(0.05f)) }
}

@Composable
private fun GradientSlider(label: String, value: String, fraction: Float, track: GBrush, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Classic.Text, fontSize = 13.sp, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
            Text(value, color = Classic.Text, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(12.dp).background(track))
            Slider(
                fraction, onValue, modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFE0E0E0), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
            )
        }
    }
}

