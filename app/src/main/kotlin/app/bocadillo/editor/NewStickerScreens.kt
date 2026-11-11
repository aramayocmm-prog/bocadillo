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
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
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

/**
 * Balloon style presets (own combinations): fill (optionally a vertical gradient), outline,
 * outline width, text colour, bold/italic, drop shadow.
 */
private data class BalloonPreset(
    val fill: Int, val stroke: Int, val strokeW: Float, val text: Int,
    val bold: Boolean = false, val fill2: Int = 0, val shadow: Int = 0, val italic: Boolean = false, val serif: Boolean = false,
)

private fun c(v: Long) = v.toInt()

private val BALLOON_PRESETS: List<BalloonPreset> = buildList {
    // plain classics
    add(BalloonPreset(c(0xFFFFFFFF), c(0xFF000000), 0.008f, c(0xFF000000)))
    add(BalloonPreset(c(0xFFFFFFFF), c(0xFF000000), 0.022f, c(0xFF000000), bold = true))
    add(BalloonPreset(c(0xFF000000), c(0xFF000000), 0.008f, c(0xFFFFFFFF)))
    add(BalloonPreset(c(0xFFFFFFFF), c(0xFF000000), 0.008f, c(0xFF000000), shadow = c(0x88000000)))
    add(BalloonPreset(c(0xFFFFFFFF), c(0xFF3949AB), 0.008f, c(0xFF1A237E), italic = true, serif = true))
    // soft pastel fills with matching outline
    val pastel = listOf(0xFFB3E5FC to 0xFF0288D1, 0xFFF8BBD0 to 0xFFD81B60, 0xFFD1C4E9 to 0xFF5E35B1, 0xFFDCEDC8 to 0xFF558B2F,
        0xFFFFF9C4 to 0xFFF9A825, 0xFFFFE0B2 to 0xFFEF6C00, 0xFFE0E0E0 to 0xFF616161)
    for ((f, o) in pastel) add(BalloonPreset(c(f), c(o), 0.01f, c(o)))
    // vertical gradients (light top → saturated bottom)
    val grads = listOf(
        Triple(0xFFFFFFFF, 0xFF64B5F6, 0xFF0D47A1), Triple(0xFFFFF59D, 0xFFFF9800, 0xFF6D4C41), Triple(0xFFFFCDD2, 0xFFE53935, 0xFF7F0000),
        Triple(0xFFE1BEE7, 0xFF7B1FA2, 0xFF311B92), Triple(0xFFC8E6C9, 0xFF2E7D32, 0xFF1B5E20), Triple(0xFFB2EBF2, 0xFF00838F, 0xFF004D40),
        Triple(0xFFFFFFFF, 0xFFBDBDBD, 0xFF424242), Triple(0xFFFFE082, 0xFFFFB300, 0xFF000000), Triple(0xFFF48FB1, 0xFF880E4F, 0xFF000000),
    )
    for ((top, bottom, line) in grads) add(BalloonPreset(c(top), c(line), 0.01f, if (bottom == 0xFFBDBDBDL || top == 0xFFFFE082L) c(0xFF212121) else c(0xFFFFFFFF), bold = true, fill2 = c(bottom)))
    // dark balloons with light text, some glowing outlines
    val dark = listOf(0xFF263238 to 0xFFFFFFFF, 0xFF1A237E to 0xFFFFEB3B, 0xFF4A148C to 0xFFFFFFFF, 0xFF1B5E20 to 0xFFFFFFFF, 0xFFB71C1C to 0xFFFFFFFF, 0xFF3E2723 to 0xFFFFCC80)
    for ((f, t) in dark) add(BalloonPreset(c(f), c(0xFF000000), 0.01f, c(t), bold = true))
    // bright fills with thick black outline (comic look)
    val bright = listOf(0xFFFFEB3B, 0xFFC6FF00, 0xFF00E5FF, 0xFFFF4081, 0xFFFF9100, 0xFF76FF03)
    for (f in bright) add(BalloonPreset(c(f), c(0xFF000000), 0.022f, c(0xFF000000), bold = true))
    // white balloons with coloured outline and drop shadow
    val outlines = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFF8E24AA, 0xFFFB8C00)
    for (o in outlines) add(BalloonPreset(c(0xFFFFFFFF), c(o), 0.016f, c(o), bold = true, shadow = c(0x66000000)))
    // no outline, gradient + shadow
    add(BalloonPreset(c(0xFF80D8FF), c(0x00000000), 0f, c(0xFF01579B), bold = true, fill2 = c(0xFF0091EA), shadow = c(0x55000000)))
    add(BalloonPreset(c(0xFFFFAB91), c(0x00000000), 0f, c(0xFFFFFFFF), bold = true, fill2 = c(0xFFD84315), shadow = c(0x55000000)))
    add(BalloonPreset(c(0xFFB9F6CA), c(0x00000000), 0f, c(0xFF1B5E20), italic = true, serif = true, fill2 = c(0xFF00C853)))
    add(BalloonPreset(c(0x00FFFFFF), c(0x00000000), 0f, c(0xFF000000)))
}

private fun BalloonObject.withPreset(p: BalloonPreset) = copy(
    fillColor = p.fill, strokeColor = p.stroke, strokeWidth = p.strokeW, fillColor2 = p.fill2, shadowColor = p.shadow,
    style = style.copy(color = p.text, bold = p.bold, italic = p.italic, font = if (p.serif) "system:serif" else style.font),
)

/**
 * "New Word Balloon": light preview area (tap it for every style option), ▶ to try the next
 * shape, the text field with + (common phrases) and the style grid. The grid scrolls sideways
 * with 4 rows, as in the reference captures (WA0011/WA0012/WA0015).
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
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Classic.LightPreview, Classic.LightHeader)))) {
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
        HorizontalDivider(color = Color(0xFF9E9E9E))
        val styleLabel = stringResource(R.string.style_n)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellH = maxHeight / 4
            val cellW = (maxWidth / 4.8f).coerceAtLeast(64.dp)
            LazyHorizontalGrid(GridCells.Fixed(4), Modifier.fillMaxSize()) {
                itemsIndexed(BALLOON_PRESETS) { i, p ->
                    val cell = BalloonObject(
                        "p", ObjectTransform(0.5f, 0.44f), draft.shape, width = 0.88f, height = 0.5f, tailX = -0.2f, tailY = 0.36f,
                        text = "ABC", style = TextStyle(color = 0xFF000000.toInt(), outlineWidth = 0f, size = 0.17f, font = "system:sans-serif"),
                    ).withPreset(p)
                    Canvas(Modifier.size(cellW, cellH).semantics { contentDescription = styleLabel.format(i + 1) }.clickable { onDraft(draft.withPreset(p)) }) {
                        drawIntoCanvas { renderer.draw(it.nativeCanvas, cell, size.width, size.height) }
                    }
                }
            }
        }
    }
}

/**
 * Title style presets: own combinations of font, fill (solid or vertical gradient), one or two
 * outlines and shadow, generated from small palettes (none copied from the original).
 */
val TITLE_STYLES: List<TextStyle> = buildList {
    val black = c(0xFF000000); val white = c(0xFFFFFFFF)
    // 1. classic white/black
    add(TextStyle(font = "system:sans-serif", color = white, outlineColor = black, outlineWidth = 0.1f))
    add(TextStyle(font = "system:sans-serif-black", color = white, outlineColor = black, outlineWidth = 0.16f))
    add(TextStyle(font = "system:sans-serif-black", color = black, outlineColor = white, outlineWidth = 0.14f))
    add(TextStyle(font = "system:sans-serif-condensed", bold = true, color = white, outlineColor = black, outlineWidth = 0.12f))
    add(TextStyle(font = "system:serif", bold = true, color = black, outlineWidth = 0f))
    // 2. solid colours with black outline
    for (col in listOf(0xFFFFEB3B, 0xFFFF5252, 0xFF40C4FF, 0xFF69F0AE, 0xFFE040FB, 0xFFFFAB40))
        add(TextStyle(font = "system:sans-serif-black", color = c(col), outlineColor = black, outlineWidth = 0.14f))
    // 3. gradients with dark outline
    val grads = listOf(0xFFFFF176 to 0xFFFF6F00, 0xFF80D8FF to 0xFF2962FF, 0xFFFF80AB to 0xFFC51162, 0xFFB9F6CA to 0xFF00C853,
        0xFFEA80FC to 0xFF6200EA, 0xFFFFFFFF to 0xFF9E9E9E, 0xFFFFD180 to 0xFFDD2C00, 0xFFA7FFEB to 0xFF00BFA5)
    for ((top, bottom) in grads) add(TextStyle(font = "system:sans-serif-black", color = c(top), gradientColor = c(bottom), outlineColor = c(0xFF1A1A1A), outlineWidth = 0.1f))
    // 4. double outline (colour, then white or black)
    val doubles = listOf(Triple(0xFFFFFFFF, 0xFFE53935, 0xFF000000), Triple(0xFFFFEB3B, 0xFF6A1B9A, 0xFFFFFFFF), Triple(0xFF00E5FF, 0xFF01579B, 0xFFFFFFFF),
        Triple(0xFFFFFFFF, 0xFF2E7D32, 0xFF000000), Triple(0xFFFF4081, 0xFFFFFFFF, 0xFF880E4F), Triple(0xFFFFAB00, 0xFF000000, 0xFFFFFFFF))
    for ((fill, inner, outer) in doubles) add(TextStyle(font = "system:sans-serif-black", color = c(fill), outlineColor = c(inner), outlineWidth = 0.1f, outline2Color = c(outer), outline2Width = 0.08f))
    // 5. casual / comic
    for (col in listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFFFF7043, 0xFF7C4DFF, 0xFF00E676))
        add(TextStyle(font = "system:casual", bold = true, color = c(col), outlineColor = black, outlineWidth = 0.16f))
    // 6. serif / elegant
    add(TextStyle(font = "system:serif", italic = true, bold = true, color = c(0xFF8E0000), outlineWidth = 0f))
    add(TextStyle(font = "system:serif", color = c(0xFF455A64), outlineWidth = 0f))
    add(TextStyle(font = "bundled:Lora-Variable.ttf", bold = true, color = c(0xFFFFD54F), gradientColor = c(0xFFA1887F), outlineColor = c(0xFF3E2723), outlineWidth = 0.08f))
    add(TextStyle(font = "bundled:Lora-Variable.ttf", italic = true, color = white, outlineWidth = 0f, shadowColor = c(0xAA000000), shadowDx = 0.05f, shadowDy = 0.05f, shadowBlur = 0.06f))
    add(TextStyle(font = "system:cursive", bold = true, color = c(0xFFD81B60), outlineColor = white, outlineWidth = 0.1f))
    // 7. outline only
    for (col in listOf(0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFF1E88E5))
        add(TextStyle(font = "system:sans-serif-black", color = 0x00FFFFFF, outlineColor = c(col), outlineWidth = 0.08f))
    // 8. soft shadow / neon
    add(TextStyle(font = "bundled:Poppins-Bold.ttf", color = white, outlineWidth = 0f, shadowColor = c(0x99000000), shadowDx = 0.05f, shadowDy = 0.06f, shadowBlur = 0.1f))
    add(TextStyle(font = "system:sans-serif-medium", color = c(0xFFE8FFE0), outlineColor = c(0xFF39FF14), outlineWidth = 0.04f, shadowColor = c(0xFF39FF14), shadowDx = 0f, shadowDy = 0f, shadowBlur = 0.35f))
    add(TextStyle(font = "system:sans-serif-medium", color = c(0xFFFFE6FA), outlineColor = c(0xFFFF2DB4), outlineWidth = 0.04f, shadowColor = c(0xFFFF2DB4), shadowDx = 0f, shadowDy = 0f, shadowBlur = 0.35f))
    // 9. labels (background box)
    add(TextStyle(font = "system:sans-serif-medium", color = white, outlineWidth = 0f, backgroundColor = c(0xCC000000)))
    add(TextStyle(font = "system:serif-monospace", color = c(0xFF222222), outlineWidth = 0f, backgroundColor = c(0xEEFFFFFF)))
    add(TextStyle(font = "system:sans-serif-black", color = black, outlineWidth = 0f, backgroundColor = c(0xFFFFEB3B)))
    // 10. plain fonts in white / black (no outline)
    for (f in listOf("system:sans-serif", "system:sans-serif-light", "system:monospace", "bundled:Poppins-Regular.ttf", "system:sans-serif-smallcaps"))
        add(TextStyle(font = f, color = black, outlineWidth = 0f))
    add(TextStyle(font = "system:sans-serif-light", color = white, outlineWidth = 0f))
} + TEXT_PRESETS.map { it.second }

/**
 * "New Title": preview area ("Tap to style Title"), the text field with + and the grid of title
 * styles drawn as real "ABC" text. The grid scrolls sideways with 5 rows, as in the reference
 * captures (WA0013/WA0014/WA0021/WA0025).
 */
@Composable
fun NewTitleScreen(
    draft: TextObject,
    renderer: ObjectRenderer,
    onDraft: (TextObject) -> Unit,
    onStylePreview: () -> Unit,
    onPhrases: () -> Unit,
) {
    val hint = stringResource(R.string.title_preview_hint)
    Column(Modifier.fillMaxSize().background(Classic.LightGrid)) {
        TitlePreview(draft, hint, renderer, Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = hint }.clickable(onClick = onStylePreview))
        TextEntry(draft.text, onChange = { onDraft(draft.copy(text = it)) }, onPlus = onPhrases)
        HorizontalDivider(color = Color(0xFF9E9E9E))
        val styleLabel = stringResource(R.string.style_n)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellH = maxHeight / 5
            val cellW = (maxWidth / 4.8f).coerceAtLeast(64.dp)
            LazyHorizontalGrid(GridCells.Fixed(5), Modifier.fillMaxSize()) {
                itemsIndexed(TITLE_STYLES) { i, p ->
                    val cell = TextObject("p", ObjectTransform(0.5f, 0.5f), "ABC", p.copy(size = 0.34f))
                    val selected = draft.style.applyPreset(p) == draft.style
                    Canvas(
                        Modifier.size(cellW, cellH).background(if (selected) Color(0x3329B6F6) else Color.Transparent)
                            .semantics { contentDescription = styleLabel.format(i + 1) }
                            .clickable { onDraft(draft.copy(style = draft.style.applyPreset(p))) },
                    ) { drawIntoCanvas { renderer.draw(it.nativeCanvas, cell, size.width, size.height) } }
                }
            }
        }
    }
}

/** Light preview strip with the title in its current style ("Tap to style Title" when empty). */
@Composable
fun TitlePreview(draft: TextObject, hint: String, renderer: ObjectRenderer, modifier: Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Classic.LightPreview, Classic.LightHeader)))) {
        val preview = draft.copy(transform = ObjectTransform(0.5f, 0.5f), text = draft.text.ifBlank { hint }, style = draft.style.copy(size = 0.4f))
        Canvas(Modifier.fillMaxSize()) {
            // the longest line fits the strip width
            val (hw, _) = renderer.halfSize(preview, size.width, size.height)
            val k = if (hw * 2 > size.width * 0.94f) size.width * 0.94f / (hw * 2) else 1f
            drawIntoCanvas { renderer.draw(it.nativeCanvas, preview.copy(transform = preview.transform.copy(scale = k)), size.width, size.height) }
        }
    }
}

/** White text field with a blue border and the + button (common phrases), as in the classic tools. */
@Composable
private fun TextEntry(text: String, onChange: (String) -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).background(Classic.LightField).border(2.dp, Classic.LightFieldBorder).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (text.isEmpty()) Text(stringResource(R.string.enter_text_here), color = Color(0xFF9E9E9E), fontSize = 17.sp)
            BasicTextField(text, onChange, textStyle = UiTextStyle(color = Color(0xFF212121), fontSize = 17.sp), maxLines = 4, modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, stringResource(R.string.phrases), tint = Color(0xFF9E9E9E)) }
    }
}
