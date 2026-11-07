package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params

/**
 * Draggable centres for effects with [ParamSpec.Point] params (twirl, spotlight, focal blur…).
 * One finger anywhere on the photo moves the nearest point (so the finger never hides a tiny
 * handle); two fingers keep zooming and panning the canvas.
 */
@Composable
fun PointHandles(specs: List<ParamSpec>, params: Params, zoom: Float, onChange: ((Params) -> Params) -> Unit) {
    val points = specs.filterIsInstance<ParamSpec.Point>()
    if (points.isEmpty()) return
    val current by rememberUpdatedState(params)
    val change by rememberUpdatedState(onChange)
    fun valueOf(p: Params, s: ParamSpec.Point): Offset =
        p.floats(s.key)?.takeIf { it.size >= 2 }?.let { Offset(it[0], it[1]) } ?: Offset(s.defaultX, s.defaultY)

    Canvas(
        Modifier.fillMaxSize().pointerInput(points) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun norm(o: Offset) = Offset((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                val start = norm(down.position)
                val target = points.minBy { s ->
                    val v = valueOf(current, s); (v.x - start.x) * (v.x - start.x) + (v.y - start.y) * (v.y - start.y)
                }
                // a tap places the point; a drag moves it relative to where it was
                val origin = valueOf(current, target)
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } > 1) break // pinch: leave it to the canvas
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        if (!moved) change { it.with(target.key, floatArrayOf(start.x, start.y)) }
                        break
                    }
                    val now = norm(c.position)
                    if (!moved && (now - start).getDistance() * size.width < viewConfiguration.touchSlop) continue
                    moved = true
                    c.consume()
                    val v = Offset((origin.x + now.x - start.x).coerceIn(0f, 1f), (origin.y + now.y - start.y).coerceIn(0f, 1f))
                    change { it.with(target.key, floatArrayOf(v.x, v.y)) }
                }
            }
        },
    ) {
        val r = 14.dp.toPx() / zoom
        val w = 2.dp.toPx() / zoom
        for (s in points) {
            val v = valueOf(params, s)
            val c = Offset(v.x * size.width, v.y * size.height)
            drawCircle(Color(0x66000000), r + w, c)
            drawCircle(Color.White, r, c, style = Stroke(w * 1.5f))
            drawCircle(Color.White, w * 1.6f, c)
            drawLine(Color.White, c - Offset(r * 1.6f, 0f), c - Offset(r * 1.15f, 0f), w)
            drawLine(Color.White, c + Offset(r * 1.15f, 0f), c + Offset(r * 1.6f, 0f), w)
            drawLine(Color.White, c - Offset(0f, r * 1.6f), c - Offset(0f, r * 1.15f), w)
            drawLine(Color.White, c + Offset(0f, r * 1.15f), c + Offset(0f, r * 1.6f), w)
        }
    }
}
