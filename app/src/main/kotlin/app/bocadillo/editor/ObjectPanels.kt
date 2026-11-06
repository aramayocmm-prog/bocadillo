package app.bocadillo.editor

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import app.bocadillo.core.imaging.objects.StickerCatalog
import app.bocadillo.core.imaging.objects.StickerDef
import app.bocadillo.core.model.Arrange
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.BalloonShape
import app.bocadillo.core.model.CanvasObject
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.TextAlign
import app.bocadillo.data.FontOption
import app.bocadillo.render.ObjectRenderer
import java.util.Locale
import kotlin.math.roundToInt

fun balloonShapeLabel(s: BalloonShape) = when (s) {
    BalloonShape.SPEECH -> R.string.balloon_speech
    BalloonShape.THOUGHT -> R.string.balloon_thought
    BalloonShape.SCREAM -> R.string.balloon_scream
    BalloonShape.WHISPER -> R.string.balloon_whisper
    BalloonShape.RECTANGLE -> R.string.balloon_rectangle
    BalloonShape.ROUNDED -> R.string.balloon_rounded
}

@Composable
fun BalloonEditorPanel(
    tool: ToolState.EditBalloon,
    fonts: List<FontOption>,
    typeface: (String) -> Typeface,
    onChange: (BalloonObject) -> Unit,
    onImportFont: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val b = tool.draft
    val st = b.style
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(stringResource(if (tool.isNew) R.string.wordballoon_new else R.string.wordballoon_edit), onCancel, onApply)
        OutlinedTextField(
            value = b.text, onValueChange = { onChange(b.copy(text = it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text(stringResource(R.string.hint_text)) }, maxLines = 4,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in BalloonShape.entries) FilterChip(b.shape == s, { onChange(b.copy(shape = s)) }, label = { Text(stringResource(balloonShapeLabel(s))) })
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf(R.string.tab_balloon, R.string.tab_font, R.string.tab_text).forEachIndexed { i, l -> Tab(tab == i, { tab = i }, text = { Text(stringResource(l)) }) }
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            when (tab) {
                0 -> {
                    ColorPicker(stringResource(R.string.fill_color), b.fillColor, { onChange(b.copy(fillColor = it)) })
                    ColorPicker(stringResource(R.string.outline_color), b.strokeColor, { onChange(b.copy(strokeColor = it)) })
                    LabeledSlider(stringResource(R.string.outline_width), b.strokeWidth, 0f..0.03f, { "${(it * 1000).roundToInt()}" }) { onChange(b.copy(strokeWidth = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.balloon_pointer), Modifier.weight(1f))
                        Switch(b.hasTail, { onChange(b.copy(hasTail = it)) })
                    }
                    if (b.hasTail) LabeledSlider(stringResource(R.string.pointer_width), b.tailWidth, 0.05f..0.9f, { "${(it * 100).roundToInt()}%" }) { onChange(b.copy(tailWidth = it)) }
                    LabeledSlider(stringResource(R.string.opacity), b.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { onChange(b.copy(opacity = it)) }
                    Text(stringResource(R.string.balloon_tail_hint), style = MaterialTheme.typography.bodySmall)
                }
                1 -> FontList(fonts, st.font, typeface, onPick = { onChange(b.copy(style = st.copy(font = it))) }, onImport = onImportFont)
                else -> {
                    ColorPicker(stringResource(R.string.text_color), st.color, { onChange(b.copy(style = st.copy(color = it))) })
                    LabeledSlider(stringResource(R.string.text_size), st.size, 0.02f..0.2f, { "${(it * 100).roundToInt()}" }) { onChange(b.copy(style = st.copy(size = it))) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((a, l) in listOf(TextAlign.LEFT to R.string.align_left, TextAlign.CENTER to R.string.align_center, TextAlign.RIGHT to R.string.align_right))
                            FilterChip(st.align == a, { onChange(b.copy(style = st.copy(align = a))) }, label = { Text(stringResource(l)) })
                    }
                    LabeledSlider(stringResource(R.string.line_height), st.lineHeight, 0.6f..2.5f) { onChange(b.copy(style = st.copy(lineHeight = it))) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(st.bold, { onChange(b.copy(style = st.copy(bold = !st.bold))) }, label = { Text(stringResource(R.string.bold)) })
                        Text("  ")
                        FilterChip(st.italic, { onChange(b.copy(style = st.copy(italic = !st.italic))) }, label = { Text(stringResource(R.string.italic)) })
                    }
                }
            }
        }
    }
}

@Composable
fun StickerPickerPanel(catalog: StickerCatalog, renderer: ObjectRenderer, replacing: Boolean, onPick: (StickerDef) -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    var cat by rememberSaveable { mutableIntStateOf(0) }
    val lang = Locale.getDefault().language
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(stringResource(if (replacing) R.string.sticker_replace else R.string.sticker_new), onCancel, onCancel)
        TextButton(onClick = onImport, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.sticker_import_png)) }
        ScrollableTabRow(selectedTabIndex = cat, edgePadding = 8.dp) {
            catalog.categories.forEachIndexed { i, c -> Tab(cat == i, { cat = i }, text = { Text(c.name(lang)) }) }
        }
        LazyVerticalGrid(GridCells.Adaptive(72.dp), Modifier.fillMaxWidth().height(260.dp).padding(8.dp)) {
            items(catalog.categories[cat].stickers, key = { it.id }) { def ->
                Canvas(
                    Modifier.padding(4.dp).aspectRatio(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable { onPick(def) }
                        .padding(6.dp),
                ) { drawIntoCanvas { renderer.drawStickerPreview(it.nativeCanvas, def, size.width, size.height) } }
            }
        }
    }
}

@Composable
fun StickerStylePanel(tool: ToolState.StyleSticker, catalog: StickerCatalog, onChange: (StickerObject) -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
    val s = tool.draft
    val def = if (s.isVector) catalog[s.source.removePrefix("builtin:")] else null
    Column(Modifier.fillMaxWidth()) {
        PanelHeader(stringResource(R.string.sticker_set_style), onCancel, onApply)
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (def != null) {
                val labels = listOf(R.string.color_main, R.string.color_secondary, R.string.outline_color)
                def.slots.forEachIndexed { i, default ->
                    val current = s.colors.getOrNull(i) ?: default
                    ColorPicker(stringResource(labels.getOrElse(i) { R.string.color_main }), current, { c ->
                        val list = MutableList(def.slots.size) { k -> s.colors.getOrNull(k) ?: def.slots[k] }
                        list[i] = c
                        onChange(s.copy(colors = list))
                    })
                }
                LabeledSlider(stringResource(R.string.outline_size), s.outlineScale, 0f..4f, { "×%.1f".format(it) }) { onChange(s.copy(outlineScale = it)) }
                TextButton(onClick = { onChange(s.copy(colors = emptyList(), outlineScale = 1f)) }) { Text(stringResource(R.string.reset_colors)) }
            }
            LabeledSlider(stringResource(R.string.opacity), s.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { onChange(s.copy(opacity = it)) }
        }
    }
}

class ObjectActions(
    val edit: () -> Unit,
    val duplicate: () -> Unit,
    val delete: () -> Unit,
    val arrange: (Arrange) -> Unit,
    val flip: (Boolean) -> Unit,
    val reset: () -> Unit,
    val toggleStretch: () -> Unit,
    val replace: () -> Unit,
    val opacityLive: (Float) -> Unit,
    val opacityCommit: () -> Unit,
    val done: () -> Unit,
)

/** Context menu of the selected object (Edit · Replace · Arrange · Duplicate · Delete …). */
@Composable
fun ObjectMenu(o: CanvasObject, stretchMode: Boolean, a: ObjectActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(Icons.Filled.Edit, R.string.edit, a.edit)
            if (o is StickerObject) Chip(Icons.Filled.SwapHoriz, R.string.replace, a.replace)
            Chip(Icons.Filled.ContentCopy, R.string.action_duplicate, a.duplicate)
            Chip(Icons.Filled.Delete, R.string.action_delete, a.delete)
            Chip(Icons.Filled.FlipToFront, R.string.bring_to_front) { a.arrange(Arrange.FRONT) }
            Chip(Icons.Filled.KeyboardArrowUp, R.string.bring_forward) { a.arrange(Arrange.FORWARD) }
            Chip(Icons.Filled.KeyboardArrowDown, R.string.send_backward) { a.arrange(Arrange.BACKWARD) }
            Chip(Icons.Filled.FlipToBack, R.string.send_to_back) { a.arrange(Arrange.BACK) }
            Chip(Icons.Filled.Flip, R.string.flip_h) { a.flip(true) }
            Chip(Icons.Filled.Flip, R.string.flip_v, rotate = true) { a.flip(false) }
            FilterChip(stretchMode, a.toggleStretch, label = { Text(stringResource(R.string.transform_stretch)) }, leadingIcon = { Icon(Icons.Filled.OpenInFull, null) })
            Chip(Icons.Filled.Restore, R.string.transform_reset, a.reset)
            TextButton(onClick = a.done) { Text(stringResource(R.string.done)) }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            LabeledSlider(stringResource(R.string.opacity), o.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }, onFinished = a.opacityCommit, onValue = a.opacityLive)
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: Int, rotate: Boolean = false, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.rotate(if (rotate) 90f else 0f)) })
}
