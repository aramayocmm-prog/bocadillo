package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.bocadillo.core.imaging.ops.CropMath
import kotlin.math.max
import kotlin.math.min

/**
 * Central editing area: fits the picture, supports pinch-zoom (1×–12×), pan and double-tap to
 * reset. While cropping, zoom is reset and one-finger drags go to the crop overlay.
 */
@Composable
fun EditorCanvas(
    image: ImageBitmap?,
    crop: ToolState.Crop?,
    cropPixelSize: Pair<Int, Int>,
    onCropRect: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn above the picture inside the zoomed box (objects layer); receives the zoom factor. */
    overlay: @Composable (zoom: Float) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val cropping = crop != null
    LaunchedEffect(cropping) { if (cropping) { scale = 1f; offset = Offset.Zero } }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        if (image == null) return@BoxWithConstraints
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val fit = min(boxW / image.width, boxH / image.height)
        val dw = image.width * fit
        val dh = image.height * fit
        val density = LocalDensity.current

        fun clampOffset(o: Offset, s: Float): Offset {
            val mx = max(0f, (dw * s - boxW) / 2f)
            val my = max(0f, (dh * s - boxH) / 2f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(cropping) {
                    if (!cropping) detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 12f)
                        val z = newScale / scale
                        val c = centroid - Offset(boxW / 2f, boxH / 2f)
                        offset = clampOffset((offset - c) * z + c + pan, newScale)
                        scale = newScale
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
                    .drawBehind { drawChecker() },
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.High,
                )
                if (crop != null) CropOverlay(crop, cropPixelSize, onCropRect) else overlay(scale)
            }
        }
    }
}

/** Checkerboard so transparent areas are visible. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChecker() {
    val cell = 12.dp.toPx()
    val light = Color(0xFFDDDDDD); val dark = Color(0xFFBBBBBB)
    drawRect(light)
    var y = 0f; var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(dark, topLeft = Offset(x, y), size = Size(min(cell, size.width - x), min(cell, size.height - y)))
            x += cell * 2
        }
        y += cell; row++
    }
}


@Composable
private fun CropOverlay(crop: ToolState.Crop, pixelSize: Pair<Int, Int>, onRect: (FloatArray) -> Unit) {
    val rect by rememberUpdatedState(crop.rect)
    val ratio by rememberUpdatedState(crop.ratio)
    val px by rememberUpdatedState(pixelSize)
    var handles by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(crop.rect) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        working = rect
                        val tol = 28.dp.toPx()
                        handles = CropMath.hit(rect, p.x / size.width, p.y / size.height, tol / size.width, tol / size.height)
                    },
                    onDragEnd = { handles = 0 },
                    onDragCancel = { handles = 0 },
                    onDrag = { change, d ->
                        if (handles != 0) {
                            change.consume()
                            working = CropMath.drag(working, handles, d.x / size.width, d.y / size.height, ratio, px.first, px.second)
                            onRect(working)
                        }
                    },
                )
            },
    ) {
        val l = rect[0] * size.width; val t = rect[1] * size.height
        val r = rect[2] * size.width; val b = rect[3] * size.height
        val shade = Color(0x99000000)
        drawRect(shade, Offset.Zero, Size(size.width, t))
        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t))
        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
        val line = 1.dp.toPx()
        for (i in 1..2) {
            val gx = l + (r - l) * i / 3f; val gy = t + (b - t) * i / 3f
            drawLine(Color(0x88FFFFFF), Offset(gx, t), Offset(gx, b), line)
            drawLine(Color(0x88FFFFFF), Offset(l, gy), Offset(r, gy), line)
        }
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
        val hs = 18.dp.toPx(); val hw = 4.dp.toPx()
        for ((cx, cy, sx, sy) in listOf(floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f), floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f)).map { it.toList() }) {
            drawLine(Color.White, Offset(cx, cy), Offset(cx + hs * sx, cy), hw)
            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + hs * sy), hw)
        }
    }
}
