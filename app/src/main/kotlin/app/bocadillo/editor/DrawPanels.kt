package app.bocadillo.editor

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import app.bocadillo.core.imaging.ops.ImageOp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Touch layer for drawing tools (inside the zoomed picture box). One finger draws; a second
 * finger cancels the stroke so the canvas can zoom/pan. Stylus pressure is recorded; fingers
 * always count as full pressure. In eyedropper mode a tap samples a colour instead.
 *
 * This layer draws nothing itself: every move is sent to [onLive] and the editor paints the
 * stroke in progress with the same brush engine as the final result (same colour, opacity,
 * size, hardness, blending and smoothing from the first touch to the release).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DrawLayer(
    eyedropper: Boolean,
    maskOverlay: ImageBitmap?,
    maskTiles: app.bocadillo.render.LiveTiles?,
    onLive: (FloatArray) -> Unit,
    onStroke: (FloatArray) -> Unit,
    onSample: (Float, Float) -> Unit,
) {
    val pick by rememberUpdatedState(eyedropper)
    val areaLabel = stringResource(R.string.draw_area)
    Box(Modifier.fillMaxSize().semantics { contentDescription = areaLabel }) {
        if (maskTiles == null) maskOverlay?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        else androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            maskTiles.version
            // 1.2.0: the tiles replace the overlay where they are, so erasing the mask shows at once
            drawIntoCanvas { c -> maskOverlay?.let { maskTiles.drawOver(c.nativeCanvas, it.asAndroidBitmap(), size.width, size.height, maskBasePaint) } ?: maskTiles.draw(c.nativeCanvas, size.width, size.height) }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = this.size.width.toFloat(); val h = this.size.height.toFloat()
                    if (pick) {
                        down.consume()
                        onSample(down.position.x / w, down.position.y / h)
                        return@awaitEachGesture
                    }
                    val data = ArrayList<Float>()
                    var last = down.position
                    fun add(o: Offset, pressure: Float, stylus: Boolean) {
                        last = o
                        data += (o.x / w).coerceIn(-0.2f, 1.2f); data += (o.y / h).coerceIn(-0.2f, 1.2f)
                        data += if (stylus) pressure.coerceIn(0.05f, 1f) else 1f
                    }
                    add(down.position, down.pressure, down.type == PointerType.Stylus)
                    down.consume()
                    onLive(data.toFloatArray()) // the dab under the finger shows at once
                    var cancelled = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) { if (!cancelled) { cancelled = true; onLive(FloatArray(0)) }; continue } // two fingers: navigate
                        if (cancelled) continue
                        val c = pressed.first()
                        // every historical sample of the event, so fast moves keep their shape
                        var moved = false
                        for (hp in c.historical) if ((hp.position - last).getDistance() >= 1.5f) { add(hp.position, c.pressure, c.type == PointerType.Stylus); moved = true }
                        if ((c.position - last).getDistance() >= 1.5f) { add(c.position, c.pressure, c.type == PointerType.Stylus); moved = true }
                        if (moved) onLive(data.toFloatArray())
                        c.consume()
                    }
                    if (!cancelled && data.isNotEmpty()) onStroke(data.toFloatArray())
                }
            },
        )
    }
}

@Composable
private fun StrokeHistoryButtons(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
    IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
}

@Composable
private fun BrushControls(b: BrushSettings, showOpacity: Boolean, onBrush: (BrushSettings) -> Unit) {
    LabeledSlider(stringResource(R.string.brush_size), b.size, 0.003f..0.25f, { "${(it * 100).roundToInt()}%" }) { onBrush(b.copy(size = it)) }
    LabeledSlider(stringResource(R.string.brush_hardness), b.hardness, 0f..1f, { "${(it * 100).roundToInt()}%" }) { onBrush(b.copy(hardness = it)) }
    if (showOpacity) LabeledSlider(stringResource(R.string.opacity), b.opacity, 0.05f..1f, { "${(it * 100).roundToInt()}%" }) { onBrush(b.copy(opacity = it)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pressure_size), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(b.pressureSize, { onBrush(b.copy(pressureSize = it)) })
    }
    if (showOpacity) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pressure_opacity), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(b.pressureOpacity, { onBrush(b.copy(pressureOpacity = it)) })
    }
}

/** Brush · Marker · Eraser · History Paint. */
@Composable
fun DrawPanel(
    tool: ToolState.Draw,
    stages: List<Pair<Int, String>>,
    stageLabel: (String) -> String,
    onKind: (DrawKind) -> Unit,
    onBrush: (BrushSettings) -> Unit,
    onStage: (Int) -> Unit,
    onEyedropper: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val b = tool.brush
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PanelHeader(stringResource(R.string.menu_draw), onCancel, onApply) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((k, label) in listOf(DrawKind.BRUSH to R.string.brush, DrawKind.MARKER to R.string.marker, DrawKind.ERASER to R.string.eraser_transparent, DrawKind.HISTORY to R.string.history_paint))
                FilterChip(tool.kind == k, { onKind(k) }, label = { Text(stringResource(label)) })
            StrokeHistoryButtons(tool.strokes.isNotEmpty(), tool.redo.isNotEmpty(), onUndo, onRedo)
        }
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            when (tool.kind) {
                DrawKind.BRUSH, DrawKind.MARKER -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ColorPicker(stringResource(R.string.brush_color), b.color, { onBrush(b.copy(color = it)) }, allowAlpha = false) }
                        FilterChip(tool.eyedropper, { onEyedropper(!tool.eyedropper) }, label = { Text(stringResource(R.string.eyedropper)) }, leadingIcon = { Icon(Icons.Filled.Colorize, null) })
                    }
                    if (tool.eyedropper) Text(stringResource(R.string.eyedropper_hint), style = MaterialTheme.typography.bodySmall)
                }
                DrawKind.ERASER -> Text(stringResource(R.string.eraser_hint), style = MaterialTheme.typography.bodySmall)
                DrawKind.HISTORY -> {
                    if (stages.isEmpty()) Text(stringResource(R.string.history_paint_unavailable), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(stringResource(R.string.history_pick_stage), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((k, id) in stages) FilterChip(tool.stage == k, { onStage(k) }, label = { Text(stageLabel(id)) })
                        }
                    }
                }
            }
            BrushControls(b, showOpacity = tool.kind != DrawKind.HISTORY, onBrush)
        }
    }
}

/** Paint Mask / Selection, then choose the effect. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MaskPanel(
    tool: ToolState.Mask,
    effects: List<ImageOp>,
    effectLabel: (ImageOp) -> String,
    onUpdate: ((ToolState.Mask) -> ToolState.Mask) -> Unit,
    onBrush: (BrushSettings) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNext: () -> Unit,
    onEffect: (ImageOp) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(stringResource(R.string.mask_title), onCancel, onNext)
        if (tool.choosingEffect) {
            Text(stringResource(R.string.mask_choose_effect), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            // grouped by family, wrapping, scrollable (about 40 effects)
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                for ((cat, list) in effects.groupBy { it.category }) {
                    Text(stringResource(Labels.category(cat)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (op in list) OpTile(op, effectLabel(op)) { onEffect(op) }
                    }
                }
            }
            TextButton(onClick = { onUpdate { it.copy(choosingEffect = false) } }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.mask_back_to_paint)) }
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!tool.protect, { onUpdate { ToolState.Mask(protect = false, brush = it.brush) } }, label = { Text(stringResource(R.string.mask_select)) })
            FilterChip(tool.protect, { onUpdate { ToolState.Mask(protect = true, brush = it.brush) } }, label = { Text(stringResource(R.string.mask_protect)) })
            StrokeHistoryButtons(tool.strokes.isNotEmpty(), tool.redo.isNotEmpty(), onUndo, onRedo)
        }
        Text(stringResource(if (tool.protect) R.string.mask_protect_hint else R.string.mask_select_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!tool.erasing, { onUpdate { it.copy(erasing = false) } }, label = { Text(stringResource(R.string.brush)) })
                FilterChip(tool.erasing, { onUpdate { it.copy(erasing = true) } }, label = { Text(stringResource(R.string.eraser)) })
            }
            BrushControls(tool.brush, showOpacity = false, onBrush)
            LabeledSlider(stringResource(R.string.mask_feather), tool.feather, 0f..0.1f, { "${(it * 1000).roundToInt()}" }) { v -> onUpdate { it.copy(feather = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.invert_mask), Modifier.weight(1f))
                Switch(tool.invert, { v -> onUpdate { it.copy(invert = v) } })
            }
            TextButton(onClick = onNext) { Text(stringResource(R.string.next)) }
        }
    }
}

private val maskBasePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
