package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import app.bocadillo.core.model.ObjectTransform
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.VectorStyle
import app.bocadillo.render.ObjectRenderer
import app.bocadillo.ui.theme.Classic

/** Own palette of shape styles: fills × outline treatments. */
private val SHAPE_STYLES: List<VectorStyle> = buildList {
    val fills = listOf(0xFFFFFFFF, 0xFF1E5BFF, 0xFF3DDC4C, 0xFFB200FF, 0xFF2F6CA8, 0xFF7A3B0E, 0xFF000000, 0xFF9AFAFF, 0xFFCCFF66, 0xFFFF1AC6,
        0xFFFF7A00, 0xFF7A7A7A, 0xFFFFBE8C, 0xFFB9A000, 0xFF990000, 0xFFFFF000, 0xFFF07070).map { it.toInt() }
    // white outline, dark outline, thick black outline, outline only
    for (f in fills) add(VectorStyle(fill = f, stroke = 0xFFFFFFFF.toInt(), strokeWidth = 0.035f))
    for (f in fills) add(VectorStyle(fill = f, stroke = 0xFF000000.toInt(), strokeWidth = 0.02f))
    for (f in fills) add(VectorStyle(fill = f, stroke = 0xFF000000.toInt(), strokeWidth = 0.05f))
    for (f in fills) add(VectorStyle(fill = 0x00000000, stroke = f, strokeWidth = 0.04f))
}

/**
 * Set Shape Style (reference WA0096/WA0099/WA0106): light preview of the sticker on top and a
 * sideways-scrolling grid (4 rows) of the same sticker in each style. Only for vector stickers:
 * PNG/WebP pictures cannot be restyled and never reach this screen.
 */
@Composable
fun SetShapeStyleScreen(draft: StickerObject, renderer: ObjectRenderer, onDraft: (StickerObject) -> Unit) {
    Column(Modifier.fillMaxSize().background(Classic.LightGrid)) {
        StylePreview(draft, renderer)
        HorizontalDivider(color = Color(0xFF9E9E9E))
        val label = stringResource(R.string.style_n)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellH = maxHeight / 4
            val cellW = (maxWidth / 4.8f).coerceAtLeast(64.dp)
            LazyHorizontalGrid(GridCells.Fixed(4), Modifier.fillMaxSize()) {
                itemsIndexed(SHAPE_STYLES) { i, st ->
                    val cell = draft.copy(id = "cell", transform = ObjectTransform(0.5f, 0.5f), size = 0.8f, opacity = 1f, vectorStyle = st)
                    Canvas(Modifier.size(cellW, cellH).semantics { contentDescription = label.format(i + 1) }.clickable { onDraft(draft.copy(vectorStyle = st)) }) {
                        drawIntoCanvas { renderer.draw(it.nativeCanvas, cell, size.width, size.height) }
                    }
                }
            }
        }
    }
}

/**
 * Set Line Style (reference WA0100): two columns — rounded ends and square ends — of growing
 * widths in the current line colour, plus a row of colours on top.
 */
@Composable
fun SetLineStyleScreen(draft: StickerObject, renderer: ObjectRenderer, onDraft: (StickerObject) -> Unit) {
    val vs = draft.vectorStyle ?: VectorStyle()
    val lineColor = if ((vs.stroke ushr 24) != 0) vs.stroke else 0xFFFF7A00.toInt()
    Column(Modifier.fillMaxSize().background(Classic.LightGrid)) {
        StylePreview(draft, renderer)
        HorizontalDivider(color = Color(0xFF9E9E9E))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf(0xFFFF7A00, 0xFFFF0000, 0xFF000000, 0xFFFFFFFF, 0xFF1E5BFF, 0xFF3DDC4C, 0xFFB200FF, 0xFFFFF000, 0xFF7A7A7A).map { it.toInt() }) {
                val sel = c == lineColor
                Box(
                    Modifier.size(44.dp).background(Color(c)).border(if (sel) 3.dp else 1.dp, if (sel) Color(0xFF1E88E5) else Color(0xFF9E9E9E))
                        .semantics { contentDescription = "#%06X".format(c and 0xFFFFFF) }
                        .clickable { onDraft(draft.copy(vectorStyle = vs.copy(stroke = c, strokeWidth = if (vs.strokeWidth > 0f) vs.strokeWidth else 0.03f))) },
                )
            }
        }
        val widths = listOf(0.004f, 0.008f, 0.014f, 0.02f, 0.03f, 0.045f, 0.06f, 0.08f)
        val label = stringResource(R.string.style_n)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            widths.forEachIndexed { i, wd ->
                Row(Modifier.fillMaxWidth().height(56.dp)) {
                    for (round in listOf(true, false)) {
                        val sel = vs.strokeWidth == wd && vs.roundCap == round
                        Canvas(
                            Modifier.weight(1f).height(56.dp).background(if (sel) Color(0x3329B6F6) else Color.Transparent)
                                .semantics { contentDescription = label.format(i * 2 + if (round) 1 else 2) }
                                .clickable { onDraft(draft.copy(vectorStyle = vs.copy(stroke = lineColor, strokeWidth = wd, roundCap = round))) },
                        ) {
                            val px = (wd * 600f).coerceAtLeast(2f)
                            drawLine(Color(lineColor), androidx.compose.ui.geometry.Offset(px + 6f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width - px - 6f, size.height / 2), px,
                                cap = if (round) StrokeCap.Round else StrokeCap.Square)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StylePreview(draft: StickerObject, renderer: ObjectRenderer) {
    Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Classic.LightPreview, Classic.LightHeader))), contentAlignment = Alignment.Center) {
        val shown = draft.copy(id = "preview", transform = ObjectTransform(0.5f, 0.5f), size = 0.85f, opacity = 1f)
        Canvas(Modifier.fillMaxSize()) { drawIntoCanvas { renderer.draw(it.nativeCanvas, shown, size.width, size.height) } }
    }
}
