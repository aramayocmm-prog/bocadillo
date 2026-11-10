package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle as UiTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.BalloonShape
import app.bocadillo.core.model.ObjectTransform
import app.bocadillo.core.model.TextObject
import app.bocadillo.core.model.TextStyle
import app.bocadillo.render.ObjectRenderer
import app.bocadillo.ui.theme.Classic

/**
 * Word Balloon shape picker: two columns of white balloons with the shape name inside, on a
 * light-grey gradient (the classic picker has no cell frames and no title bar content).
 */
@Composable
fun BalloonShapeGrid(renderer: ObjectRenderer, onPick: (BalloonShape) -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Classic.ShapeTop, Classic.ShapeBottom))).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items(BalloonShape.pickerOrder) { sh ->
            val label = stringResource(balloonShapeLabel(sh)).uppercase()
            val sample = BalloonObject(
                "cell", ObjectTransform(0.5f, if (sh == BalloonShape.THOUGHT) 0.40f else 0.42f), sh,
                width = 0.86f, height = 0.62f, tailX = -0.2f, tailY = 0.48f, strokeWidth = 0.02f,
                text = label, style = TextStyle(color = 0xFF000000.toInt(), outlineWidth = 0f, size = 0.07f, font = "system:casual"),
            )
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.22f).semantics { contentDescription = label }.clickable { onPick(sh) }) {
                drawIntoCanvas { renderer.draw(it.nativeCanvas, sample, size.width, size.height) }
            }
        }
    }
}

/** Colour presets of the style grid (own combinations: fill, outline, outline width, text colour). */
private data class BalloonPreset(val fill: Int, val stroke: Int, val strokeW: Float, val text: Int, val bold: Boolean = false)

private val BALLOON_PRESETS = listOf(
    BalloonPreset(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.008f, 0xFF000000.toInt()),
    BalloonPreset(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.02f, 0xFF000000.toInt(), true),
    BalloonPreset(0xFF000000.toInt(), 0xFF000000.toInt(), 0.008f, 0xFFFFFFFF.toInt()),
    BalloonPreset(0xFFB3E5FC.toInt(), 0xFF0288D1.toInt(), 0.01f, 0xFF01579B.toInt()),
    BalloonPreset(0xFFFCE4EC.toInt(), 0xFFF06292.toInt(), 0.01f, 0xFFAD1457.toInt()),
    BalloonPreset(0xFFE1BEE7.toInt(), 0xFF6A1B9A.toInt(), 0.01f, 0xFF4A148C.toInt()),
    BalloonPreset(0xFFFF9800.toInt(), 0xFFBF360C.toInt(), 0.012f, 0xFFFFFFFF.toInt(), true),
    BalloonPreset(0xFFFFF59D.toInt(), 0xFF9E9D24.toInt(), 0.008f, 0xFF333333.toInt()),
    BalloonPreset(0xFF283593.toInt(), 0xFFC62828.toInt(), 0.012f, 0xFFFFFFFF.toInt(), true),
    BalloonPreset(0xFFC6FF00.toInt(), 0xFF000000.toInt(), 0.016f, 0xFF000000.toInt(), true),
    BalloonPreset(0xFFE91E63.toInt(), 0xFFFFFFFF.toInt(), 0.012f, 0xFFFFFFFF.toInt(), true),
    BalloonPreset(0xFFFFEB3B.toInt(), 0xFF000000.toInt(), 0.02f, 0xFF000000.toInt(), true),
    BalloonPreset(0xFF424242.toInt(), 0xFF212121.toInt(), 0.008f, 0xFFFFEB3B.toInt()),
    BalloonPreset(0xFFEEEEEE.toInt(), 0xFF9E9E9E.toInt(), 0.012f, 0xFF212121.toInt()),
    BalloonPreset(0xFFFFFFFF.toInt(), 0xFF3949AB.toInt(), 0.008f, 0xFF3949AB.toInt()),
    BalloonPreset(0xFFFFCDD2.toInt(), 0xFFB71C1C.toInt(), 0.01f, 0xFFB71C1C.toInt()),
    BalloonPreset(0xFF7E57C2.toInt(), 0xFF311B92.toInt(), 0.01f, 0xFFFFFFFF.toInt()),
    BalloonPreset(0xFFFFB74D.toInt(), 0xFFE65100.toInt(), 0.01f, 0xFF3E2723.toInt()),
    BalloonPreset(0xFF1B5E20.toInt(), 0xFF000000.toInt(), 0.008f, 0xFFFFFFFF.toInt(), true),
    BalloonPreset(0xFF80DEEA.toInt(), 0xFF006064.toInt(), 0.01f, 0xFF004D40.toInt()),
    BalloonPreset(0xFFB71C1C.toInt(), 0xFF000000.toInt(), 0.012f, 0xFFFFFFFF.toInt(), true),
    BalloonPreset(0xFFFFE0B2.toInt(), 0xFF795548.toInt(), 0.008f, 0xFF4E342E.toInt()),
    BalloonPreset(0xFF4A148C.toInt(), 0xFF000000.toInt(), 0.008f, 0xFFFFFFFF.toInt()),
    BalloonPreset(0xFF76FF03.toInt(), 0xFF33691E.toInt(), 0.01f, 0xFF1B5E20.toInt()),
    BalloonPreset(0xFF00BFA5.toInt(), 0xFF004D40.toInt(), 0.01f, 0xFFFFFFFF.toInt()),
)

private fun BalloonObject.withPreset(p: BalloonPreset) = copy(
    fillColor = p.fill, strokeColor = p.stroke, strokeWidth = p.strokeW,
    style = style.copy(color = p.text, bold = p.bold),
)

/**
 * "New Word Balloon": light preview area (tap it for every style option), ▶ to try the next
 * shape, the text field with + (common phrases) and the grid of colour styles.
 */
@Composable
fun NewBalloonScreen(
    draft: BalloonObject,
    renderer: ObjectRenderer,
    onDraft: (BalloonObject) -> Unit,
    onStylePreview: () -> Unit,
    onPhrases: () -> Unit,
) {
    val hint = stringResource(R.string.balloon_preview_hint)
    Column(Modifier.fillMaxSize().background(Classic.LightGrid)) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(Classic.LightPreview)) {
            val preview = draft.copy(
                transform = ObjectTransform(0.5f, 0.42f), width = 0.9f, height = 0.62f, tailX = -0.22f, tailY = 0.5f,
                text = draft.text.ifBlank { hint }, style = draft.style.copy(size = 0.11f),
            )
            Canvas(Modifier.align(Alignment.Center).fillMaxWidth(0.62f).height(140.dp).semantics { contentDescription = hint }.clickable(onClick = onStylePreview)) {
                drawIntoCanvas { renderer.draw(it.nativeCanvas, preview, size.width, size.height) }
            }
            IconButton(
                onClick = {
                    val order = BalloonShape.pickerOrder
                    onDraft(draft.copy(shape = order[(order.indexOf(draft.shape) + 1) % order.size]))
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            ) {
                Box(Modifier.size(34.dp).background(Color(0xFF8E8E8E), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.next_shape), tint = Color(0xFFE6E6E6))
                }
            }
        }
        TextEntry(draft.text, onChange = { onDraft(draft.copy(text = it)) }, onPlus = onPhrases)
        LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(BALLOON_PRESETS) { p ->
                val cell = BalloonObject(
                    "p", ObjectTransform(0.5f, 0.42f), draft.shape, width = 0.92f, height = 0.62f, tailX = -0.2f, tailY = 0.5f,
                    text = "ABC", style = TextStyle(color = 0xFF000000.toInt(), outlineWidth = 0f, size = 0.16f, font = "system:sans-serif"),
                ).withPreset(p)
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f).clickable { onDraft(draft.withPreset(p)) }) {
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, cell, size.width, size.height) }
                }
            }
        }
    }
}

/** Extra title presets (own combinations) appended to the editor's presets for a fuller grid. */
private val TITLE_GRID_EXTRA = listOf(
    TextStyle(font = "system:sans-serif-black", color = 0xFFFFC107.toInt(), outlineColor = 0xFF000000.toInt(), outlineWidth = 0.14f),
    TextStyle(font = "system:sans-serif", color = 0xFF29B6F6.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.1f),
    TextStyle(font = "system:sans-serif", color = 0xFFF48FB1.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.1f),
    TextStyle(font = "system:sans-serif-black", color = 0xFFFFFFFF.toInt(), outlineColor = 0xFF000000.toInt(), outlineWidth = 0.18f),
    TextStyle(font = "system:casual", bold = true, color = 0xFF8E24AA.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.12f),
    TextStyle(font = "system:sans-serif", color = 0xFF9CCC65.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.08f),
    TextStyle(font = "system:serif", bold = true, italic = true, color = 0xFF1A237E.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.12f),
    TextStyle(font = "bundled:Lora-Variable.ttf", bold = true, color = 0xFF6D4C41.toInt(), outlineWidth = 0f),
    TextStyle(font = "system:sans-serif-black", color = 0xFF00C853.toInt(), outlineColor = 0xFF1B5E20.toInt(), outlineWidth = 0.1f),
    TextStyle(font = "system:serif", color = 0xFFFF7043.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.1f),
    TextStyle(font = "bundled:Poppins-Bold.ttf", color = 0xFFFFFFFF.toInt(), outlineColor = 0xFFD50000.toInt(), outlineWidth = 0.14f),
    TextStyle(font = "system:monospace", bold = true, color = 0xFF212121.toInt(), outlineColor = 0xFFFFEB3B.toInt(), outlineWidth = 0.12f),
    TextStyle(font = "system:sans-serif-condensed", bold = true, color = 0xFF5E35B1.toInt(), outlineWidth = 0f, shadowColor = 0x88000000.toInt(), shadowDx = 0.04f, shadowDy = 0.04f, shadowBlur = 0.04f),
    TextStyle(font = "system:cursive", bold = true, color = 0xFFD81B60.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.1f),
    TextStyle(font = "system:sans-serif", color = 0xFF9E9E9E.toInt(), outlineColor = 0xFF424242.toInt(), outlineWidth = 0.06f),
)

/** "New Title": preview area (tap to style), the text field with + and the grid of title styles. */
@Composable
fun NewTitleScreen(
    draft: TextObject,
    renderer: ObjectRenderer,
    onDraft: (TextObject) -> Unit,
    onStylePreview: () -> Unit,
    onPhrases: () -> Unit,
) {
    val hint = stringResource(R.string.title_preview_hint)
    val presets = remember { TEXT_PRESETS.map { it.second } + TITLE_GRID_EXTRA }
    Column(Modifier.fillMaxSize().background(Classic.LightGrid)) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(Classic.LightPreview).semantics { contentDescription = hint }.clickable(onClick = onStylePreview)) {
            val preview = draft.copy(transform = ObjectTransform(0.5f, 0.5f), text = draft.text.ifBlank { hint }, style = draft.style.copy(size = 0.42f))
            Canvas(Modifier.fillMaxSize()) { drawIntoCanvas { renderer.draw(it.nativeCanvas, preview, size.width, size.height) } }
        }
        TextEntry(draft.text, onChange = { onDraft(draft.copy(text = it)) }, onPlus = onPhrases)
        LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(presets) { p ->
                val cell = TextObject("p", ObjectTransform(0.5f, 0.5f), "ABC", p.copy(size = 0.5f))
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f).clickable { onDraft(draft.copy(style = draft.style.applyPreset(p))) }) {
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, cell, size.width, size.height) }
                }
            }
        }
    }
}

/** White text field with a blue border and the + button (common phrases), as in the classic tools. */
@Composable
private fun TextEntry(text: String, onChange: (String) -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).background(Classic.LightField).border(2.dp, Classic.LightFieldBorder).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (text.isEmpty()) Text(stringResource(R.string.enter_text_here), color = Color(0xFF9E9E9E), fontSize = 16.sp)
            BasicTextField(text, onChange, textStyle = UiTextStyle(color = Color(0xFF212121), fontSize = 16.sp), modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, stringResource(R.string.phrases), tint = Color(0xFF9E9E9E)) }
    }
}

