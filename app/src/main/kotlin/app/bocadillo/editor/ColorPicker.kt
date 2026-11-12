package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.imaging.color.ColorMath
import app.bocadillo.data.AppSettings
import kotlin.math.roundToInt

/** Ready colours of the picker (white, black, greys, rainbow, pastels, transparent). */
val PRESET_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFF5D4037, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1,
    0xFF1E88E5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00,
    0xFFFFD23F, 0xFFF48FB1, 0xFF90CAF9, 0xFFA5D6A7, 0xFFFFCC80, 0xFF263238, 0xFF39FF14, 0x00000000,
).map { it.toInt() }

/**
 * Eyedropper available where the colour picker is shown (1.2.0, decision 5): the editor provides
 * it; calling it opens the picture (all visible layers) to pick a colour with zoom and a loupe,
 * and the picked colour is passed to the callback. Null where there is no picture (home screen).
 */
val LocalEyedropper = compositionLocalOf<(((Int) -> Unit) -> Unit)?> { null }

/**
 * Colour picker used by every tool (1.2.0, decision 5). Compact: name, current colour, eyedropper,
 * ready colours and recent colours in one row each. «Personalizar…» opens the advanced part: HEX,
 * favourites, saturation/brightness square, hue and opacity bars, and the HSB / RGB values.
 */
@Composable
fun ColorPicker(label: String, color: Int, onColor: (Int) -> Unit, allowAlpha: Boolean = true) {
    var custom by remember { mutableStateOf(false) }
    val eyedropper = LocalEyedropper.current
    val emit by rememberUpdatedState(onColor)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Swatch(color, selected = true, size = 24) {}
            if (eyedropper != null) IconButton(onClick = { eyedropper { c -> emit(if (allowAlpha) c else c or 0xFF000000.toInt()) } }) {
                Icon(Icons.Filled.Colorize, stringResource(R.string.eyedropper))
            }
            TextButton(onClick = { custom = !custom }) { Text(stringResource(if (custom) R.string.color_less else R.string.color_custom)) }
        }
        ColorEditor(color, onColor, allowAlpha, advanced = custom)
    }
}

/**
 * The picker's body, also used by Paint's colour panel and the new blank image. [advanced] shows
 * HEX, favourites, the square and the bars. Colours chosen here go to the recent colours.
 */
@Composable
fun ColorEditor(color: Int, onColor: (Int) -> Unit, allowAlpha: Boolean = true, advanced: Boolean = true, minAlpha: Int = 0) {
    val settings = AppSettings.current
    val current by rememberUpdatedState(color)
    val start = remember { color }
    fun fix(c: Int): Int = if (!allowAlpha) c or 0xFF000000.toInt() else if (ColorMath.alpha(c) < minAlpha) (c and 0xFFFFFF) or (minAlpha shl 24) else c
    fun pick(c: Int, remember: Boolean) { val f = fix(c); onColor(f); if (remember) settings?.addRecentColor(f) }
    // a colour changed with the bars goes to the recent colours when the picker closes
    DisposableEffect(Unit) { onDispose { if (current != start) settings?.addRecentColor(current) } }

    SwatchRow(stringResource(R.string.color_presets), PRESET_COLORS.filter { allowAlpha || ColorMath.alpha(it) == 0xFF }, color) { pick(it, true) }
    val recents = settings?.recentColors.orEmpty().filter { allowAlpha || ColorMath.alpha(it) == 0xFF }
    if (recents.isNotEmpty()) SwatchRow(stringResource(R.string.color_recent), recents, color) { pick(it, false) }
    if (!advanced) return
    val favorites = settings?.favoriteColors.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        HexField(color, allowAlpha) { pick(it, true) }
        Spacer(Modifier.width(8.dp))
        if (settings != null) {
            val fav = color in favorites
            IconButton(onClick = { settings.toggleFavoriteColor(color) }, modifier = Modifier.semantics { selected = fav }) {
                Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.color_favorite_toggle), tint = if (fav) Color(0xFFFFC107) else LocalContentColor.current)
            }
        }
        Text(rgbText(color), fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.75f))
    }
    if (favorites.isNotEmpty()) SwatchRow(stringResource(R.string.color_favorites), favorites.filter { allowAlpha || ColorMath.alpha(it) == 0xFF }, color) { pick(it, true) }
    HsbEditor(color, allowAlpha, minAlpha) { onColor(fix(it)) }
}

private fun rgbText(c: Int) = "R ${ColorMath.red(c)}  G ${ColorMath.green(c)}  B ${ColorMath.blue(c)}  A ${(ColorMath.alpha(c) * 100 / 255f).roundToInt()}%"

@Composable
private fun SwatchRow(title: String, colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Text(title, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in colors) Swatch(c, selected = c == selected) { onPick(c) }
    }
}

/** HEX text: #RRGGBB or #AARRGGBB; applied when it is a valid colour (Done or each valid edit). */
@Composable
private fun HexField(color: Int, allowAlpha: Boolean, onColor: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(ColorMath.toHex(color)) }
    val valid = ColorMath.parseHex(text) != null
    val fg = LocalContentColor.current
    val desc = stringResource(R.string.color_hex)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("HEX", fontSize = 12.sp, color = fg.copy(alpha = 0.75f))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            text,
            onValueChange = { t ->
                text = t.uppercase().filter { it == '#' || it in '0'..'9' || it in 'A'..'F' }.take(9)
                val len = text.removePrefix("#").length
                ColorMath.parseHex(text)?.takeIf { len == 6 || len == 8 }?.let { c -> onColor(if (allowAlpha) c else c or 0xFF000000.toInt()) }
            },
            singleLine = true,
            textStyle = TextStyle(color = if (valid) fg else Color(0xFFE57373), fontSize = 15.sp),
            cursorBrush = SolidColor(fg),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { ColorMath.parseHex(text)?.let(onColor) }),
            modifier = Modifier.width(110.dp).border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics { contentDescription = desc },
        )
    }
}

/** Saturation / brightness square, hue bar and opacity bar, with their values. */
@Composable
fun HsbEditor(color: Int, allowAlpha: Boolean, minAlpha: Int = 0, onColor: (Int) -> Unit) {
    val hsbNow = ColorMath.toHsb(color)
    // the hue is kept while the colour is grey (saturation or brightness 0), so it is not lost
    var hue by remember { mutableFloatStateOf(hsbNow[0]) }
    if (hsbNow[1] > 0.001f && hsbNow[2] > 0.001f && ColorMath.hsb(hue, hsbNow[1], hsbNow[2]) and 0xFFFFFF != color and 0xFFFFFF) hue = hsbNow[0]
    val s = hsbNow[1]; val v = hsbNow[2]; val a = ColorMath.alpha(color) / 255f
    val cur by rememberUpdatedState(color)
    fun emit(h: Float = hue, ss: Float = s, vv: Float = v, aa: Float = a) = onColor(ColorMath.hsb(h, ss, vv, aa.coerceAtLeast(minAlpha / 255f)))
    val pure = Color(ColorMath.hsb(hue, 1f, 1f))
    val squareLabel = stringResource(R.string.color_square)
    Canvas(
        Modifier.fillMaxWidth().height(132.dp).padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = squareLabel }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun set(p: Offset) {
                        val ss = (p.x / size.width).coerceIn(0f, 1f); val vv = 1f - (p.y / size.height).coerceIn(0f, 1f)
                        onColor(ColorMath.hsb(hue, ss, vv, ColorMath.alpha(cur) / 255f))
                    }
                    set(down.position); down.consume()
                    while (true) {
                        val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        set(c.position); c.consume()
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val p = Offset(s * size.width, (1f - v) * size.height)
        drawCircle(Color.Black, 8.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
    }
    GradientBar(stringResource(R.string.hue), "${hue.roundToInt()}°", hue / 360f,
        Brush.horizontalGradient((0..6).map { Color(ColorMath.hsb(it * 60f, 1f, 1f)) })) { f -> hue = f * 360f; emit(h = hue) }
    GradientBar(stringResource(R.string.saturation), "${(s * 100).roundToInt()}%", s, Brush.horizontalGradient(listOf(Color(ColorMath.hsb(hue, 0f, v)), Color(ColorMath.hsb(hue, 1f, v))))) { emit(ss = it) }
    GradientBar(stringResource(R.string.brightness), "${(v * 100).roundToInt()}%", v, Brush.horizontalGradient(listOf(Color.Black, Color(ColorMath.hsb(hue, s, 1f))))) { emit(vv = it) }
    if (allowAlpha) {
        val opaque = Color(color or 0xFF000000.toInt())
        GradientBar(stringResource(R.string.opacity), "${(a * 100).roundToInt()}%", a, Brush.horizontalGradient(listOf(opaque.copy(alpha = 0f), opaque)), checker = true) { emit(aa = it) }
    }
}

/** A bar with a gradient track: drag or tap anywhere on it. */
@Composable
private fun GradientBar(label: String, value: String, fraction: Float, track: Brush, checker: Boolean = false, onValue: (Float) -> Unit) {
    val fg = LocalContentColor.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp, color = fg.copy(alpha = 0.8f))
        }
        Canvas(
            Modifier.fillMaxWidth().height(26.dp).padding(vertical = 3.dp).semantics { contentDescription = label }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onValue((down.position.x / size.width).coerceIn(0f, 1f)); down.consume()
                        while (true) {
                            val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            onValue((c.position.x / size.width).coerceIn(0f, 1f)); c.consume()
                        }
                    }
                },
        ) {
            if (checker) drawChecker(6.dp.toPx())
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawCircle(Color.Black, size.height / 2f, Offset(x, size.height / 2f), style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, size.height / 2f, Offset(x, size.height / 2f), style = Stroke(1.5.dp.toPx()))
        }
    }
}

private fun DrawScope.drawChecker(cell: Float) {
    var y = 0f; var row = 0
    while (y < size.height) {
        var x = 0f; var col = row % 2
        while (x < size.width) {
            drawRect(if (col % 2 == 0) Color(0xFFDDDDDD) else Color(0xFF9E9E9E), Offset(x, y), Size(cell, cell)); x += cell; col++
        }
        y += cell; row++
    }
}

@Composable
private fun Swatch(c: Int, selected: Boolean, size: Int = 30, onClick: () -> Unit) {
    val desc = ColorMath.toHex(c)
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if ((c ushr 24) == 0) Color.LightGray else Color(c))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { if ((c ushr 24) == 0) Text("∅", color = Color.DarkGray) }
}

/** Compact slider with a label and a formatted value. */
@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: (Float) -> String = { "%.2f".format(it) }, onFinished: (() -> Unit)? = null, onValue: (Float) -> Unit) {
    app.bocadillo.ui.classic.ClassicSlider(
        label, value.coerceIn(range.start, range.endInclusive), range, 0f, display(value), onChange = onValue, onFinished = { onFinished?.invoke() },
    )
}
