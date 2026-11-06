package app.bocadillo.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import android.graphics.Color as AColor

private val PALETTE = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFF5D4037, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1,
    0xFF1E88E5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00,
    0xFFFFD23F, 0xFFF48FB1, 0xFF90CAF9, 0xFFA5D6A7, 0xFFFFCC80, 0xFF263238, 0xFF39FF14, 0x00000000,
).map { it.toInt() }

/**
 * Colour picker: a palette row plus an optional custom editor (hue, saturation, brightness and
 * opacity sliders). Colours are ARGB ints.
 */
@Composable
fun ColorPicker(label: String, color: Int, onColor: (Int) -> Unit, allowAlpha: Boolean = true) {
    var custom by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Swatch(color, selected = true, size = 24) {}
            TextButton(onClick = { custom = !custom }) { Text(stringResource(if (custom) R.string.color_less else R.string.color_custom)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in PALETTE) if (allowAlpha || (c ushr 24) == 0xFF) {
                Swatch(c, selected = c == color) { onColor(c) }
            }
        }
        if (custom) {
            val hsv = remember(color) { FloatArray(3).also { AColor.colorToHSV(color, it) } }
            val alpha = (color ushr 24) / 255f
            fun emit(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2], a: Float = alpha) =
                onColor(AColor.HSVToColor((a * 255).toInt().coerceIn(0, 255), floatArrayOf(h, s, v)))
            LabeledSlider(stringResource(R.string.color_hue), hsv[0], 0f..360f) { emit(h = it) }
            LabeledSlider(stringResource(R.string.color_saturation), hsv[1], 0f..1f) { emit(s = it) }
            LabeledSlider(stringResource(R.string.color_brightness), hsv[2], 0f..1f) { emit(v = it) }
            if (allowAlpha) LabeledSlider(stringResource(R.string.opacity), alpha, 0f..1f) { emit(a = it) }
        }
    }
}

@Composable
private fun Swatch(c: Int, selected: Boolean, size: Int = 30, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if ((c ushr 24) == 0) Color.LightGray else Color(c))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if ((c ushr 24) == 0) Text("∅", color = Color.DarkGray) }
}

/** Compact slider with a label and a formatted value. */
@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: (Float) -> String = { "%.2f".format(it) }, onFinished: (() -> Unit)? = null, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(display(value), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, onValueChangeFinished = onFinished)
    }
}
