package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import app.bocadillo.core.imaging.objects.ObjectGeometry
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.CanvasObject
import app.bocadillo.render.ObjectRenderer
import kotlin.math.abs
import kotlin.math.hypot

private enum class Target { BODY, HANDLE, TAIL, CORNER }

/**
 * What the blue handle does. SCALE_ROTATE and STRETCH follow the original's Scale & Rotate and
 * Stretch tools; SCALE, ROTATE, SKEW and PERSPECTIVE are additions of this app (their presence
 * in the original could not be verified from the reference captures).
 */
enum class TransformMode { SCALE_ROTATE, SCALE, ROTATE, STRETCH, SKEW, PERSPECTIVE }

/**
 * Draws the overlay objects and handles touch on them (inside the zoomed picture box, so all
 * coordinates are picture-box pixels).
 *  - tap an object: select it · tap the selected object: edit it · tap empty space: deselect
 *  - drag: move (snaps to centre/thirds guides) · two fingers on an object: move + scale + rotate
 *  - corner handle: scale & rotate (or stretch in stretch mode) · balloon tail handle: move the tail
 * Touches that do not hit an object are not consumed, so the picture can still be panned/zoomed.
 */
@Composable
fun ObjectLayer(
    objects: List<CanvasObject>,
    selectedId: String?,
    renderer: ObjectRenderer,
    assetsVersion: Int,
    zoom: Float,
    interactive: Boolean,
    mode: TransformMode,
    onSelect: (String?) -> Unit,
    onLive: (CanvasObject) -> Unit,
    onCommit: (String) -> Unit,
    onTapSelected: () -> Unit,
    /** Tap (no drag) on the blue handle: choose the transformation mode. */
    onHandleTap: () -> Unit = {},
) {
    val objs by rememberUpdatedState(objects)
    val sel by rememberUpdatedState(selectedId)
    val z by rememberUpdatedState(zoom)
    val tmode by rememberUpdatedState(mode)
    val enabled by rememberUpdatedState(interactive)
    var guides by remember { mutableStateOf<List<Float>>(emptyList()) }
    val handleColor = Color(0xFF1F6FEB)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val slop = 20.dp.toPx() / z
                    val handleR = 26.dp.toPx() / z

                    // 1. handles of the selected object
                    var target: Target? = null
                    var corner = 2
                    var obj: CanvasObject? = objs.firstOrNull { it.id == sel }
                    obj?.let { o ->
                        val (hw, hh) = renderer.halfSize(o, w, h)
                        val cs = ObjectGeometry.corners(o.transform, w, h, hw, hh)
                        if (tmode == TransformMode.PERSPECTIVE) {
                            val i = cs.indices.minByOrNull { hypot(down.position.x - cs[it].first, down.position.y - cs[it].second) }!!
                            if (hypot(down.position.x - cs[i].first, down.position.y - cs[i].second) <= handleR) { target = Target.CORNER; corner = i }
                        } else if (hypot(down.position.x - cs[2].first, down.position.y - cs[2].second) <= handleR) target = Target.HANDLE
                        if (target == null && o is BalloonObject && o.hasTail) {
                            val u = ObjectGeometry.unit(w, h)
                            val (tx, ty) = ObjectGeometry.toCanvas(o.transform, w, h).map((o.tailX * u).toDouble(), (o.tailY * u).toDouble())
                            if (hypot(down.position.x - tx.toFloat(), down.position.y - ty.toFloat()) <= handleR) target = Target.TAIL
                        }
                    }
                    // 2. object bodies, topmost first
                    if (target == null) {
                        obj = objs.asReversed().firstOrNull { o ->
                            val (hw, hh) = renderer.halfSize(o, w, h)
                            val t = o.transform
                            if (t.skewX != 0f || t.skewY != 0f || t.hasPerspective) ObjectGeometry.hitQuad(ObjectGeometry.corners(t, w, h, hw, hh), down.position.x, down.position.y, slop)
                            else ObjectGeometry.hit(t, w, h, hw, hh, down.position.x, down.position.y, slop * z)
                        }
                        if (obj != null) target = Target.BODY
                    }
                    if (target == null || obj == null) {
                        // empty space: deselect on a clean tap, otherwise let the picture pan/zoom
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size > 1 || ev.changes.any { it.isConsumed }) moved = true
                            val c = ev.changes.first()
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (ev.changes.none { it.pressed }) break
                        }
                        if (!moved && sel != null) onSelect(null)
                        return@awaitEachGesture
                    }

                    val wasSelected = obj!!.id == sel
                    if (!wasSelected) onSelect(obj!!.id)
                    down.consume()
                    var current: CanvasObject = obj!!
                    val start = current
                    var moved = false
                    val startLocal = ObjectGeometry.toLocal(start.transform, w, h, down.position.x, down.position.y)

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2 && target == Target.BODY) {
                            moved = true
                            val t = ObjectGeometry.gesture(current.transform, w, h, ev.calculatePan().x, ev.calculatePan().y, ev.calculateZoom(), ev.calculateRotation())
                            current = current.withTransform(t)
                        } else {
                            val ch = pressed.first()
                            if (!moved && (ch.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                                ev.changes.forEach { it.consume() }; continue
                            }
                            moved = true
                            val d = ch.positionChange()
                            current = when (target) {
                                Target.BODY -> {
                                    // the accumulator follows the finger; snapping is applied only to what is shown
                                    val t = current.transform.moved(d.x / w, d.y / h)
                                    guides = ObjectGeometry.snapCenter(t.cx, t.cy).third
                                    current.withTransform(t)
                                }
                                Target.HANDLE -> when (tmode) {
                                    TransformMode.STRETCH -> {
                                        val (lx, ly) = ObjectGeometry.toLocal(start.transform, w, h, ch.position.x, ch.position.y)
                                        val kx = if (abs(startLocal.first) > 1f) lx / startLocal.first else 1f
                                        val ky = if (abs(startLocal.second) > 1f) ly / startLocal.second else 1f
                                        current.withTransform(start.transform.copy(
                                            stretchX = (start.transform.stretchX * kx).coerceIn(0.1f, 10f),
                                            stretchY = (start.transform.stretchY * ky).coerceIn(0.1f, 10f),
                                        ))
                                    }
                                    TransformMode.SKEW -> {
                                        val (hw, hh) = renderer.halfSize(current, w, h)
                                        current.withTransform(ObjectGeometry.dragSkew(current.transform, w, h, hw, hh, ch.position.x, ch.position.y))
                                    }
                                    else -> {
                                        val prev = ch.previousPosition
                                        val t = ObjectGeometry.dragHandle(current.transform, w, h, prev.x to prev.y, ch.position.x to ch.position.y)
                                        current.withTransform(when (tmode) {
                                            TransformMode.SCALE -> current.transform.copy(scale = t.scale)
                                            TransformMode.ROTATE -> current.transform.copy(rotation = t.rotation)
                                            else -> t
                                        })
                                    }
                                }
                                Target.CORNER -> {
                                    val (hw, hh) = renderer.halfSize(current, w, h)
                                    current.withTransform(ObjectGeometry.dragCorner(current.transform, w, h, hw, hh, corner, ch.position.x, ch.position.y))
                                }
                                Target.TAIL -> {
                                    val b = current as BalloonObject
                                    val u = ObjectGeometry.unit(w, h)
                                    val (lx, ly) = ObjectGeometry.toLocal(b.transform, w, h, ch.position.x, ch.position.y)
                                    b.copy(tailX = lx / u, tailY = ly / u)
                                }
                                null -> current
                            }
                        }
                        ev.changes.forEach { it.consume() }
                        // apply guide snapping to what is shown (not to the drag accumulator)
                        val shown = if (target == Target.BODY && guides.isNotEmpty()) {
                            val (sx, sy, _) = ObjectGeometry.snapCenter(current.transform.cx, current.transform.cy)
                            current.withTransform(current.transform.copy(cx = sx, cy = sy))
                        } else current
                        onLive(shown)
                    }
                    guides = emptyList()
                    if (moved) {
                        onCommit(when (target) { Target.BODY -> "move"; Target.HANDLE, Target.CORNER -> "transform"; Target.TAIL -> "tail"; null -> "move" })
                    } else if (target == Target.HANDLE || target == Target.CORNER) onHandleTap()
                    else if (wasSelected) onTapSelected()
                }
            },
    ) {
        drawIntoCanvas { c -> renderer.drawAll(c.nativeCanvas, objects, size.width, size.height) }
        // guide lines
        for (g in guides) {
            if (g > 0) drawLine(Color(0xFFFF4081), Offset(g * size.width, 0f), Offset(g * size.width, size.height), 1.dp.toPx() / zoom)
            else drawLine(Color(0xFFFF4081), Offset(0f, -g * size.height), Offset(size.width, -g * size.height), 1.dp.toPx() / zoom)
        }
        // selection frame + handles
        val o = objects.firstOrNull { it.id == selectedId } ?: return@Canvas
        val w = size.width; val h = size.height
        val (hw, hh) = renderer.halfSize(o, w, h)
        val m = ObjectGeometry.toCanvas(o.transform, w, h)
        val corners = ObjectGeometry.corners(o.transform, w, h, hw, hh)
        val frame = Path().apply {
            moveTo(corners[0].first, corners[0].second)
            for (i in 1..3) lineTo(corners[i].first, corners[i].second)
            close()
        }
        val line = 1.5.dp.toPx() / zoom
        drawPath(frame, Color.White, style = Stroke(line * 2.2f))
        drawPath(frame, handleColor, style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / zoom * 2, 6f / zoom * 2))))
        val r = 13.dp.toPx() / zoom
        val modeColor = when (mode) {
            TransformMode.STRETCH -> Color(0xFFFF9800); TransformMode.SKEW -> Color(0xFF8E24AA)
            TransformMode.PERSPECTIVE -> Color(0xFF00897B); TransformMode.SCALE, TransformMode.ROTATE -> Color(0xFF3949AB)
            TransformMode.SCALE_ROTATE -> handleColor
        }
        val handles = if (mode == TransformMode.PERSPECTIVE) corners else listOf(corners[2])
        for ((x, y) in handles) {
            drawCircle(Color.White, r * 1.25f, Offset(x, y))
            drawCircle(modeColor, r, Offset(x, y))
        }
        if (o is BalloonObject && o.hasTail) {
            val u = ObjectGeometry.unit(w, h)
            val (tx, ty) = m.map((o.tailX * u).toDouble(), (o.tailY * u).toDouble())
            drawCircle(Color.White, r, Offset(tx.toFloat(), ty.toFloat()))
            drawCircle(Color(0xFF43A047), r * 0.75f, Offset(tx.toFloat(), ty.toFloat()))
        }
    }
    // accessibility / test anchors on the handles (they do not take touches: the canvas does)
    val sel = objects.firstOrNull { it.id == selectedId }
    if (sel != null && interactive) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val (hw, hh) = renderer.halfSize(sel, w, h)
        val cs = ObjectGeometry.corners(sel.transform, w, h, hw, hh)
        val label = androidx.compose.ui.res.stringResource(app.bocadillo.R.string.transform_handle)
        val points = if (mode == TransformMode.PERSPECTIVE) cs.indices.toList() else listOf(2)
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val half = with(dens) { 12.dp.toPx() / zoom }
        for (i in points) {
            val (x, y) = cs[i]
            androidx.compose.foundation.layout.Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((x - half).toInt(), (y - half).toInt()) }
                    .size(with(dens) { (half * 2).toDp() })
                    .semantics { contentDescription = if (points.size > 1) "$label ${i + 1}" else label },
            )
        }
    }
    }
}
