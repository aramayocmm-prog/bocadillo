package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.imaging.objects.Cutout
import app.bocadillo.core.imaging.objects.ObjectGeometry
import app.bocadillo.core.imaging.ops.CropMath
import app.bocadillo.core.model.StickerObject
import app.bocadillo.render.ObjectRenderer
import app.bocadillo.ui.classic.ClassicIcon
import app.bocadillo.ui.classic.ClassicIconView
import app.bocadillo.ui.theme.Classic
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Pill = Color(0xFF2C2C2C)
private val Blue = Color(0xFF1F8BFF)

/** Actions of the Insert Picture bar and its ⋮ menu. */
class InsertActions(
    val undo: () -> Unit,
    val redo: () -> Unit,
    val resetCrop: () -> Unit,
    val next: () -> Unit,
    val goTo: (InsertStage) -> Unit,
    val stretchToFit: () -> Unit,
    val scaleToFill: () -> Unit,
    val mirror: () -> Unit,
    val perspective: (Boolean) -> Unit,
    val showStickers: () -> Unit,
    val erase: (Boolean) -> Unit,
    val resetCutout: () -> Unit,
    val guide: () -> Unit,
)

/**
 * Classic Insert Picture bars (reference captures INSERTAR_ORIGINAL_01…10):
 *  - Crop:          ↶ | Crop W × H | ✓
 *  - Place Picture: ↶ | ⋮ Place Picture 100% ▶|◀ | ✓
 *  - Cut Out:       ↶ | ⋮ Cut Out  restore-brush  eraser | ✓
 */
@Composable
fun InsertTopBar(t: ToolState.InsertPicture, panelOpen: Boolean, onPanel: (Boolean) -> Unit, a: InsertActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).background(ToolChrome.Work).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val undoLabel = stringResource(if (t.stage == InsertStage.CROP) R.string.reset else R.string.undo)
        val canUndo = t.stage == InsertStage.CROP || t.undo.isNotEmpty()
        Square(undoLabel, onClick = if (t.stage == InsertStage.CROP) a.resetCrop else a.undo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, null, tint = if (canUndo) Classic.Text else Classic.Cell)
        }
        Row(
            Modifier.weight(1f).padding(horizontal = 4.dp).height(46.dp).clip(RoundedCornerShape(8.dp)).background(Pill).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (t.stage != InsertStage.CROP) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options), tint = Classic.Text) }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Color(0xFF2A2A2A))) {
                    @Composable fun item(label: Int, checked: Boolean? = null, onClick: () -> Unit) = DropdownMenuItem(
                        text = { Text(stringResource(label), color = Classic.Text, fontSize = 18.sp) },
                        leadingIcon = if (checked == null) null else ({ Text(if (checked) "✓" else "", color = Classic.Text, fontSize = 18.sp, modifier = Modifier.width(18.dp)) }),
                        onClick = { menu = false; onClick() },
                    )
                    if (t.stage == InsertStage.PLACE) {
                        item(R.string.ins_stretch_to_fit, onClick = a.stretchToFit)
                        item(R.string.ins_scale_to_fill, onClick = a.scaleToFill)
                        item(R.string.ins_mirror, onClick = a.mirror)
                        item(R.string.ins_perspective_tool, t.perspective) { a.perspective(!t.perspective) }
                        item(R.string.ins_show_stickers, t.showStickers, onClick = a.showStickers)
                        HorizontalDivider(color = Classic.Divider)
                        item(R.string.ins_back_to_crop) { a.goTo(InsertStage.CROP) }
                    } else {
                        item(R.string.ins_back_to_place) { a.goTo(InsertStage.PLACE) }
                        item(R.string.ins_back_to_crop) { a.goTo(InsertStage.CROP) }
                        item(R.string.ins_reset_cutout, onClick = a.resetCutout)
                        item(R.string.ins_show_stickers, t.showStickers, onClick = a.showStickers)
                    }
                    if (t.redo.isNotEmpty()) item(R.string.redo, onClick = a.redo)
                    item(R.string.ins_guide, onClick = a.guide)
                }
            }
            val title = when (t.stage) {
                InsertStage.CROP -> {
                    val px = Cutout.cropRect(t.cropRect, t.imageW, t.imageH)
                    stringResource(R.string.crop_short) + "  ${px[2]} × ${px[3]}"
                }
                InsertStage.PLACE -> stringResource(R.string.ins_place_picture) + " ${(t.draft.opacity * 100).roundToInt()}%"
                InsertStage.CUTOUT -> stringResource(R.string.ins_cut_out)
            }
            Text(title, color = Classic.Text, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (t.stage == InsertStage.CROP) 10.dp else 0.dp))
            when (t.stage) {
                InsertStage.PLACE -> {
                    // ▶|◀ switches the handles: blue circles (normal) ↔ blue diamonds (perspective)
                    val label = stringResource(if (t.perspective) R.string.ins_mode_perspective else R.string.ins_mode_normal)
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(if (t.perspective) Blue.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable(role = Role.Switch) { a.perspective(!t.perspective) }
                            .semantics { contentDescription = label; selected = t.perspective },
                        contentAlignment = Alignment.Center,
                    ) { ToggleIcon() }
                }
                InsertStage.CUTOUT -> {
                    val rLabel = stringResource(R.string.ins_restore_brush)
                    val eLabel = stringResource(R.string.ins_eraser)
                    Box(
                        Modifier.size(44.dp).clickable(role = Role.Button) { if (!t.erase) onPanel(!panelOpen) else { a.erase(false); onPanel(true) } }
                            .semantics { contentDescription = rLabel; selected = !t.erase },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Brush, null, tint = if (!t.erase) Blue else Classic.TextDim, modifier = Modifier.size(28.dp)) }
                    Box(
                        Modifier.size(44.dp).clickable(role = Role.Button) { if (t.erase) onPanel(!panelOpen) else { a.erase(true); onPanel(true) } }
                            .semantics { contentDescription = eLabel; selected = t.erase },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(if (t.erase) Blue.copy(alpha = 0.35f) else Color.Transparent), contentAlignment = Alignment.Center) {
                            ClassicIconView(ClassicIcon.ERASER, 30.dp)
                        }
                    }
                }
                InsertStage.CROP -> Unit
            }
        }
        val next = stringResource(when (t.stage) { InsertStage.CROP -> R.string.ins_next_place; InsertStage.PLACE -> R.string.ins_next_cutout; InsertStage.CUTOUT -> R.string.ins_insert })
        Square(next, onClick = a.next) { app.bocadillo.ui.classic.CheckWithLabel(stringResource(if (t.stage == InsertStage.CUTOUT) R.string.confirm_insert else R.string.confirm_next)) }
    }
}

@Composable
private fun Square(label: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Pill).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** ▶|◀ glyph drawn in code. */
@Composable
private fun ToggleIcon() {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width; val h = size.height
        val l = Path().apply { moveTo(w * 0.05f, h * 0.2f); lineTo(w * 0.42f, h * 0.5f); lineTo(w * 0.05f, h * 0.8f); close() }
        val r = Path().apply { moveTo(w * 0.95f, h * 0.2f); lineTo(w * 0.58f, h * 0.5f); lineTo(w * 0.95f, h * 0.8f); close() }
        drawPath(l, Color.White); drawPath(r, Color.White)
        drawLine(Color.White, Offset(w / 2, h * 0.12f), Offset(w / 2, h * 0.88f), 2.5f)
    }
}

/** Bottom bar of Place Picture: − slider + (the picture's opacity, shown in the title). */
@Composable
fun InsertOpacityBar(t: ToolState.InsertPicture, onValue: (Float, Boolean) -> Unit) {
    val label = stringResource(R.string.opacity)
    Row(Modifier.fillMaxWidth().height(56.dp).background(ToolChrome.Bottom).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("−", color = Classic.TextDim, fontSize = 28.sp, modifier = Modifier.size(44.dp).clickable { onValue((t.draft.opacity - 0.05f).coerceAtLeast(0.05f), false); onValue(0f, true) }.padding(start = 12.dp))
        Slider(
            t.draft.opacity, { onValue(it.coerceAtLeast(0.05f), false) }, onValueChangeFinished = { onValue(0f, true) }, valueRange = 0f..1f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF9E9E9E), inactiveTrackColor = Color(0xFF9E9E9E)),
        )
        Text("+", color = Classic.TextDim, fontSize = 28.sp, modifier = Modifier.size(44.dp).clickable { onValue((t.draft.opacity + 0.05f).coerceAtMost(1f), false); onValue(0f, true) }.padding(start = 12.dp))
    }
}

/**
 * Step 1: the picked image alone, fitted in the grey work area, with the crop frame (dimmed
 * outside, light-grey corner brackets) and its size in pixels in the bar.
 */
@Composable
fun InsertCropView(t: ToolState.InsertPicture, image: ImageBitmap?, onRect: (FloatArray) -> Unit) {
    val rect by rememberUpdatedState(t.cropRect.toFloatArray())
    BoxWithConstraints(Modifier.fillMaxSize().background(ToolChrome.Work).padding(28.dp), contentAlignment = Alignment.Center) {
        if (image == null) return@BoxWithConstraints
        val fit = min(constraints.maxWidth.toFloat() / image.width, constraints.maxHeight.toFloat() / image.height)
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val w = with(dens) { (image.width * fit).toDp() }; val h = with(dens) { (image.height * fit).toDp() }
        Box(Modifier.size(w, h)) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            var handles by remember { mutableStateOf(0) }
            var working by remember { mutableStateOf(rect) }
            val label = stringResource(R.string.ins_crop_frame)
            Canvas(
                Modifier.fillMaxSize().semantics { contentDescription = label }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            working = rect
                            val tol = 32.dp.toPx()
                            handles = CropMath.hit(rect, p.x / size.width, p.y / size.height, tol / size.width, tol / size.height)
                        },
                        onDragEnd = { handles = 0 }, onDragCancel = { handles = 0 },
                        onDrag = { change, d ->
                            if (handles != 0) {
                                change.consume()
                                working = CropMath.drag(working, handles, d.x / size.width, d.y / size.height, null, t.imageW, t.imageH)
                                onRect(working)
                            }
                        },
                    )
                },
            ) {
                val l = rect[0] * size.width; val tp = rect[1] * size.height; val r = rect[2] * size.width; val b = rect[3] * size.height
                val shade = Color(0x99000000)
                drawRect(shade, Offset.Zero, Size(size.width, tp)); drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(shade, Offset(0f, tp), Size(l, b - tp)); drawRect(shade, Offset(r, tp), Size(size.width - r, b - tp))
                drawRect(Color.White, Offset(l, tp), Size(r - l, b - tp), style = Stroke(1.5.dp.toPx()))
                val hs = 24.dp.toPx(); val hw = 8.dp.toPx()
                for ((cx, cy, sx, sy) in listOf(floatArrayOf(l, tp, 1f, 1f), floatArrayOf(r, tp, -1f, 1f), floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f)).map { it.toList() }) {
                    val ox = cx - sx * hw / 2; val oy = cy - sy * hw / 2
                    drawLine(Color(0xFFD6D6D6), Offset(ox - sx * hw / 2, oy), Offset(ox + hs * sx, oy), hw)
                    drawLine(Color(0xFFD6D6D6), Offset(ox, oy - sy * hw / 2), Offset(ox, oy + hs * sy), hw)
                }
            }
        }
    }
}

/**
 * Cut Out touch layer (inside the zoomed picture box). One finger erases / restores; two fingers
 * zoom and pan without painting (the stroke in progress is dropped). Points are converted to the
 * picture's own coordinates through the inverse of its full transform (rotation, flip, skew and
 * perspective), so the mask lands under the finger at any zoom or angle.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CutoutLayer(
    draft: StickerObject,
    renderer: ObjectRenderer,
    zoom: Float,
    brushSize: Float,
    square: Boolean,
    onLive: (FloatArray, Float) -> Unit,
    onCommit: (FloatArray, Float) -> Unit,
) {
    val obj by rememberUpdatedState(draft)
    val size by rememberUpdatedState(brushSize)
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val label = stringResource(R.string.ins_cut_area)
    Canvas(
        Modifier.fillMaxSize().semantics { contentDescription = label }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = this.size.width.toFloat(); val h = this.size.height.toFloat()
                val o = obj
                val (hw, hh) = renderer.halfSize(o, w, h)
                val t = o.transform
                // brush diameter on the canvas → fraction of the picture's smaller side
                val canvasD = size * min(w, h)
                val localScale = t.scale * sqrt(abs(t.stretchX * t.stretchY))
                val imgSize = (canvasD / max(1e-3f, localScale)) / max(1f, 2f * min(hw, hh))
                val data = ArrayList<Float>()
                fun add(p: Offset, pressure: Float, stylus: Boolean) {
                    val (u, v) = ObjectGeometry.canvasToUnit(t, w, h, hw, hh, p.x, p.y)
                    data += u.coerceIn(-0.5f, 1.5f); data += v.coerceIn(-0.5f, 1.5f); data += if (stylus) pressure.coerceIn(0.05f, 1f) else 1f
                }
                add(down.position, down.pressure, down.type == PointerType.Stylus)
                cursor = down.position
                down.consume()
                onLive(data.toFloatArray(), imgSize)
                var cancelled = false
                var last = down.position
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size > 1) { if (!cancelled) { cancelled = true; cursor = null; onLive(FloatArray(0), 0f) }; continue }
                    if (cancelled) continue
                    val c = pressed.first()
                    // every intermediate sample of the event (fast moves, sharp turns keep their shape)
                    var moved = false
                    for (hp in c.historical) if ((hp.position - last).getDistance() >= 1f) { add(hp.position, c.pressure, c.type == PointerType.Stylus); last = hp.position; moved = true }
                    if ((c.position - last).getDistance() >= 1f) { add(c.position, c.pressure, c.type == PointerType.Stylus); last = c.position; moved = true }
                    if (moved) { cursor = c.position; onLive(data.toFloatArray(), imgSize) }
                    c.consume()
                }
                cursor = null
                if (!cancelled && data.isNotEmpty()) onCommit(data.toFloatArray(), imgSize)
            }
        },
    ) {
        // brush outline under the finger (same diameter as the stroke)
        cursor?.let { p ->
            val r = size * min(this.size.width, this.size.height) / 2f
            if (square) drawRect(Color.White, Offset(p.x - r, p.y - r), Size(2 * r, 2 * r), style = Stroke(1.5.dp.toPx() / zoom))
            else drawCircle(Color.White, r, p, style = Stroke(1.5.dp.toPx() / zoom))
        }
    }
}

/**
 * Eraser / Restore Brush panel (reference INSERTAR_ORIGINAL_01/02): list icon, title and ^ to
 * fold; a grid of tips — soft to hard circles and squares in four sizes, the chosen one in blue —
 * and the Size and Hardness sliders.
 */
@Composable
fun CutoutBrushPanel(t: ToolState.InsertPicture, onBrush: (Float, Float, Boolean) -> Unit, onClose: () -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp).widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)).background(Color(0xF0262626))) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = Classic.Text)
            Text(stringResource(if (t.erase) R.string.ins_eraser else R.string.ins_restore_brush), color = Classic.Text, fontSize = 19.sp,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.collapse), tint = Classic.Text) }
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val sizes = listOf(0.12f, 0.08f, 0.05f, 0.025f)
            val kinds = listOf(0.05f to false, 0.4f to false, 0.75f to false, 1f to false, 1f to true)
            for (s in sizes) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((hard, sq) in kinds) {
                    val sel = abs(t.brushSize - s) < 0.001f && abs(t.brushHardness - hard) < 0.01f && t.brushSquare == sq
                    val desc = stringResource(if (sq) R.string.ins_tip_square else R.string.ins_tip_round) + " ${(s * 100).roundToInt()}% · ${(hard * 100).roundToInt()}%"
                    Box(
                        Modifier.size(52.dp).clickable(role = Role.Button) { onBrush(s, hard, sq) }.semantics { contentDescription = desc; selected = sel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(46.dp)) {
                            val r = size.minDimension / 2f * (0.3f + 0.7f * s / sizes.first())
                            val col = if (sel) Blue else Color(0xFFDDDDDD)
                            if (sq) drawRect(col, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r))
                            else drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(0f to col, hard.coerceAtMost(0.99f) to col, 1f to col.copy(alpha = 0f), center = center, radius = r), r)
                        }
                    }
                }
            }
            ValueSlider(stringResource(R.string.brush_size), t.brushSize, 0.004f..0.3f, 0.004f, { "${(it * 100).roundToInt()}%" }) { onBrush(it, t.brushHardness, t.brushSquare) }
            ValueSlider(stringResource(R.string.brush_hardness), t.brushHardness, 0f..1f, 0.05f, { "${(it * 100).roundToInt()}%" }) { onBrush(t.brushSize, it, t.brushSquare) }
        }
    }
}

/** Short guide for each step, shown the first time (can be turned off). */
@Composable
fun InsertGuideDialog(stage: InsertStage, onClose: (dontShowAgain: Boolean) -> Unit) {
    var never by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onClose(never) },
        title = { Text(stringResource(when (stage) { InsertStage.CROP -> R.string.ins_guide1_title; InsertStage.PLACE -> R.string.ins_guide2_title; InsertStage.CUTOUT -> R.string.ins_guide3_title })) },
        text = {
            Column {
                Text(stringResource(when (stage) { InsertStage.CROP -> R.string.ins_guide1; InsertStage.PLACE -> R.string.ins_guide2; InsertStage.CUTOUT -> R.string.ins_guide3 }))
                Row(Modifier.padding(top = 10.dp).clickable { never = !never }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(never, null); Text(stringResource(R.string.ins_guide_never), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton({ onClose(never) }) { Text(stringResource(android.R.string.ok)) } },
    )
}
