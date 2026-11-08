package app.bocadillo.ui.classic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.ui.theme.Classic
import kotlin.math.roundToInt

/** Shared sizes of the classic screens (measured on the reference, scaled to dp). */
object ClassicDims {
    val TopBar = 52.dp
    val BottomBar = 64.dp
    val Row = 52.dp
    val RowIcon = 36.dp
    val Thumb = 46.dp
}

/**
 * Top bar: back arrow on the left, white centred title, check mark on the right. [onConfirm] null
 * hides the check (no buttons without a function). Extra [actions] sit before the check.
 */
@Composable
fun ClassicTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onConfirm: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        Modifier.fillMaxWidth().height(ClassicDims.TopBar)
            .background(Brush.verticalGradient(listOf(Classic.BarTop, Classic.Bar))),
    ) {
        Text(
            title, color = Classic.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
        )
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(backIcon, stringResource(R.string.back), tint = Classic.Text)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            actions()
            if (onConfirm != null) IconButton(onClick = onConfirm) { Icon(Icons.Filled.Check, stringResource(R.string.apply), tint = Classic.Text) }
        }
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = Classic.Background, thickness = 1.dp)
    }
}

/** One item of the editor's bottom bar: icon above a short label. */
class BarItem(val label: String, val icon: @Composable () -> Unit, val onClick: () -> Unit, val enabled: Boolean = true)

@Composable
fun ClassicBottomBar(items: List<BarItem>) {
    Row(Modifier.fillMaxWidth().height(ClassicDims.BottomBar).background(Classic.Bar), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(Classic.Divider))
            Column(
                Modifier.weight(1f).height(ClassicDims.BottomBar).clickable(enabled = item.enabled, onClick = item.onClick),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { item.icon() }
                Text(item.label, color = if (item.enabled) Classic.Text else Classic.TextDim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Menu row: picture/icon on the left, white name, thin separator below. */
@Composable
fun ClassicMenuRow(label: String, onClick: () -> Unit, height: Dp = ClassicDims.Row, icon: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Classic.Menu).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 44.dp).padding(end = 14.dp), contentAlignment = Alignment.Center) { icon() }
            Text(label, color = Classic.Text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = Classic.Divider, thickness = 1.dp)
    }
}

/** Grey section header inside a menu list ("Transform Picture", "Misc"…). */
@Composable
fun ClassicSection(text: String) {
    Text(
        text, color = Classic.SectionText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(Classic.Section).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Round −/+ button beside the sliders. */
@Composable
private fun RoundStep(plus: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).border(1.5.dp, Classic.TextDim, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val s = 2.dp.toPx()
            drawLine(Classic.Text, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), s)
            if (plus) drawLine(Classic.Text, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), s)
        }
    }
}

/**
 * Classic value control: optional label on the left, value text on the right above a blue slider
 * framed by − and + buttons (one step each).
 */
@Composable
fun ClassicSlider(
    label: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    valueText: String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit = {},
) {
    fun snap(v: Float): Float {
        val s = if (step > 0f) ((v - range.start) / step).roundToInt() * step + range.start else v
        return s.coerceIn(range.start, range.endInclusive)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label != null) Text(label, color = Classic.Text, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = Classic.Text, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundStep(false) { onChange(snap(value - (if (step > 0f) step else (range.endInclusive - range.start) / 100f))); onFinished() }
            Slider(
                value = value, onValueChange = { onChange(snap(it)) }, onValueChangeFinished = onFinished, valueRange = range,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Classic.Text, activeTrackColor = Classic.Accent, inactiveTrackColor = Classic.Cell,
                    activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            RoundStep(true) { onChange(snap(value + (if (step > 0f) step else (range.endInclusive - range.start) / 100f))); onFinished() }
        }
    }
}

/** Dark rectangular button with a grey border (OK / Cancel, home buttons). */
@Composable
fun ClassicButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null) {
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp)).background(Classic.ButtonFill)
            .border(1.dp, Classic.ButtonBorder, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (icon != null) Arrangement.Start else Arrangement.Center,
    ) {
        if (icon != null) { Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }; Spacer(Modifier.width(14.dp)) }
        Text(text, color = if (enabled) Classic.Text else Classic.TextDim, fontSize = 18.sp)
    }
}

