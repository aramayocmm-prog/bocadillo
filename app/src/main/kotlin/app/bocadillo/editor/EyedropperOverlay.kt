package app.bocadillo.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.imaging.color.ColorMath
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Eyedropper of the unified colour picker (1.2.0, decision 5). Shows the picture as it is seen
 * (image + visible objects), fitted to the screen. One finger: a loupe follows the finger and
 * shows the pixel under it and its colour; lifting the finger uses that colour. Two fingers: zoom
 * and move the picture (nothing is picked). The screen → picture mapping is the inverse of the
 * drawing transform, so the picked pixel is the one under the loupe's centre at any zoom.
 */
@Composable
fun EyedropperOverlay(image: ImageBitmap?, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val pick by rememberUpdatedState(onPick)
    val pictureLabel = stringResource(R.string.eyedropper_picture)
    val loupeLabel = stringResource(R.string.eyedropper_loupe)
    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        if (image == null) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return@Box }
        val bmp = remember(image) { image.asAndroidBitmap() }
        // fitted rectangle (whole px) and the inverse mapping, shared by drawing and picking
        var fit by remember { mutableStateOf(IntArray(4)) }
        fun toPixel(p: Offset, size: Size): Pair<Int, Int>? {
            val c = Offset(size.width / 2f, size.height / 2f)
            val q = (p - pan - c) / scale + c
            val f = fit
            if (f[2] <= 0 || f[3] <= 0) return null
            val x = floor((q.x - f[0]) / f[2] * bmp.width).toInt(); val y = floor((q.y - f[1]) / f[3] * bmp.height).toInt()
            return if (x in 0 until bmp.width && y in 0 until bmp.height) x to y else null
        }
        var canvasSize by remember { mutableStateOf(Size.Zero) }
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = pictureLabel }.pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    finger = down.position; down.consume()
                    var multi = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) {
                            multi = true; finger = null
                            val z = ev.calculateZoom(); val pn = ev.calculatePan(); val ctr = ev.calculateCentroid()
                            val ns = (scale * z).coerceIn(1f, 16f)
                            val c = Offset(size.width / 2f, size.height / 2f)
                            // zoom around the fingers' centre
                            pan = (pan - (ctr - c)) * (ns / scale) + (ctr - c) + pn
                            scale = ns
                        } else if (!multi) finger = pressed.first().position
                        ev.changes.forEach { it.consume() }
                    }
                    val p = finger
                    finger = null
                    if (!multi && p != null) toPixel(p, Size(size.width.toFloat(), size.height.toFloat()))?.let { (x, y) -> pick(bmp.getPixel(x, y)) }
                }
            },
        ) {
            canvasSize = size
            val k = min(size.width / bmp.width, size.height / bmp.height)
            val fw = max(1, (bmp.width * k).roundToInt()); val fh = max(1, (bmp.height * k).roundToInt())
            val fx = ((size.width - fw) / 2f).roundToInt(); val fy = ((size.height - fh) / 2f).roundToInt()
            if (fit[0] != fx || fit[1] != fy || fit[2] != fw || fit[3] != fh) fit = intArrayOf(fx, fy, fw, fh)
            withTransform({
                translate(pan.x, pan.y)
                scale(scale, scale, Offset(size.width / 2f, size.height / 2f))
            }) {
                drawImage(image, dstOffset = IntOffset(fx, fy), dstSize = IntSize(fw, fh), filterQuality = if (scale > 3f) FilterQuality.None else FilterQuality.Low)
            }
            // loupe: 11×11 pixels around the finger, above it, with the centre pixel framed
            val p = finger ?: return@Canvas
            val px = toPixel(p, size)
            val r = 56.dp.toPx()
            val lc = Offset(p.x, max(r + 8f, p.y - r - 48.dp.toPx()))
            val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(lc, r)) }
            clipPath(path) {
                drawRect(Color.Black, lc - Offset(r, r), Size(2 * r, 2 * r))
                if (px != null) {
                    val n = 11; val cell = 2 * r / n
                    for (j in 0 until n) for (i in 0 until n) {
                        val x = px.first + i - n / 2; val y = px.second + j - n / 2
                        if (x in 0 until bmp.width && y in 0 until bmp.height)
                            drawRect(Color(bmp.getPixel(x, y)), Offset(lc.x - r + i * cell, lc.y - r + j * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                    drawRect(Color.White, Offset(lc.x - cell / 2, lc.y - cell / 2), Size(cell, cell), style = Stroke(2.dp.toPx()))
                }
            }
            drawCircle(Color.White, r, lc, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 3.dp.toPx(), p)
        }
        // colour under the finger, and the hint
        val p = finger
        val px = p?.let { toPixel(it, canvasSize) }
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().background(Color(0xCC1E1E1E)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (px != null) {
                val c = bmp.getPixel(px.first, px.second)
                Box(Modifier.align(Alignment.CenterStart).padding(end = 8.dp)) {
                    Box(Modifier.padding(0.dp).background(Color(c)).padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = loupeLabel })
                }
                Text(ColorMath.toHex(c), color = Color.White, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
            } else Text(stringResource(R.string.eyedropper_overlay_hint), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(end = 40.dp))
            IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Filled.Close, stringResource(R.string.cancel), tint = Color.White) }
        }
    }
}
