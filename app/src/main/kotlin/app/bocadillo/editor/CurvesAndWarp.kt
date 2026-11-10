package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import app.bocadillo.core.imaging.effects.ExtraOps
import app.bocadillo.core.model.Params
import app.bocadillo.ui.theme.Classic
import kotlin.math.hypot

/**
 * Curves: one graph per channel (RGB master, R, G, B). Tap adds a point, drag moves it, long
 * press removes it (the two end points can move but not be removed).
 */
@Composable
fun CurvesEditor(params: Params, onChange: ((Params) -> Params) -> Unit) {
    var channel by rememberSaveable { mutableStateOf("curve_rgb") }
    val pts = (params.floats(channel) ?: ExtraOps.IDENTITY_CURVE.toFloatArray()).toList().chunked(2).map { it[0] to it[1] }.sortedBy { it.first }
    val current by rememberUpdatedState(pts)
    val ch by rememberUpdatedState(channel)
    fun store(list: List<Pair<Float, Float>>) {
        val flat = list.sortedBy { it.first }.flatMap { listOf(it.first, it.second) }.toFloatArray()
        onChange { it.with(ch, flat) }
    }
    val lineColor = when (channel) { "curve_r" -> Color(0xFFFF5252); "curve_g" -> Color(0xFF69F0AE); "curve_b" -> Color(0xFF448AFF); else -> Color.White }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.curves_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in listOf("curve_rgb" to R.string.channel_all, "curve_r" to R.string.channel_r, "curve_g" to R.string.channel_g, "curve_b" to R.string.channel_b)) {
                FilterChip(channel == key, { channel = key }, label = { Text(stringResource(label)) })
            }
            TextButton({ store(listOf(0f to 0f, 1f to 1f)) }) { Text(stringResource(R.string.curve_reset)) }
        }
        var dragging by remember { mutableStateOf(-1) }
        Canvas(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).aspectRatio(1.4f).background(Classic.Background)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            val x = (o.x / size.width).coerceIn(0f, 1f); val y = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            if (current.none { kotlin.math.abs(it.first - x) < 0.03f }) store(current + (x to y))
                        },
                        onLongPress = { o ->
                            val i = nearest(current, o.x / size.width, 1f - o.y / size.height)
                            if (i > 0 && i < current.size - 1) store(current.filterIndexed { k, _ -> k != i })
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val i = nearest(current, down.position.x / size.width, 1f - down.position.y / size.height)
                        if (i < 0) return@awaitEachGesture
                        dragging = i
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            val list = current.toMutableList()
                            if (dragging !in list.indices) break
                            val lo = if (dragging == 0) 0f else list[dragging - 1].first + 0.01f
                            val hi = if (dragging == list.size - 1) 1f else list[dragging + 1].first - 0.01f
                            val x = (c.position.x / size.width).coerceIn(lo, maxOf(lo, hi))
                            val y = 1f - (c.position.y / size.height).coerceIn(0f, 1f)
                            list[dragging] = x to y
                            store(list)
                        }
                        dragging = -1
                    }
                },
        ) {
            val grid = Color(0xFF3A3A3A)
            for (k in 1..3) {
                drawLine(grid, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height))
                drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
            }
            drawLine(Color(0xFF555555), Offset(0f, size.height), Offset(size.width, 0f))
            val lut = ExtraOps.curveLut(pts.flatMap { listOf(it.first, it.second) }.toFloatArray())
            val path = Path()
            for (i in 0..255) {
                val px = i / 255f * size.width; val py = (1f - lut[i] / 255f) * size.height
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
            for ((i, p) in pts.withIndex()) {
                val c = Offset(p.first * size.width, (1f - p.second) * size.height)
                drawCircle(if (i == dragging) Classic.Accent else Color.White, 7.dp.toPx(), c)
                drawCircle(Color.Black, 7.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Index of the control point near (x, y) in graph units, or -1. */
private fun nearest(pts: List<Pair<Float, Float>>, x: Float, y: Float): Int {
    var best = -1; var bd = 0.08f
    pts.forEachIndexed { i, p -> val d = hypot(p.first - x, p.second - y); if (d < bd) { bd = d; best = i } }
    return best
}

/**
 * Warp Brush on the canvas: every one-finger drag is a push from where it started to where the
 * finger is (updated live); two fingers keep zooming. Pushes accumulate in the "warps" list.
 */
@Composable
fun WarpOverlay(params: Params, onChange: ((Params) -> Params) -> Unit) {
    val current by rememberUpdatedState(params)
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val base = current.floats("warps") ?: FloatArray(0)
                val size0 = current.float("size", 0.15f)
                val sx = (down.position.x / size.width).coerceIn(0f, 1f); val sy = (down.position.y / size.height).coerceIn(0f, 1f)
                var lastX = sx; var lastY = sy
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } > 1) { if (moved) change { it.with("warps", base) }; break } // pinch: undo this push
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val x = (c.position.x / size.width).coerceIn(0f, 1f); val y = (c.position.y / size.height).coerceIn(0f, 1f)
                    if (!moved && hypot((x - sx) * size.width, (y - sy) * size.height) < viewConfiguration.touchSlop) continue
                    moved = true
                    c.consume()
                    if (hypot((x - lastX) * size.width, (y - lastY) * size.height) < 3f) continue
                    lastX = x; lastY = y
                    change { it.with("warps", base + floatArrayOf(sx, sy, x, y, size0)) }
                }
            }
        },
    ) {}
}
