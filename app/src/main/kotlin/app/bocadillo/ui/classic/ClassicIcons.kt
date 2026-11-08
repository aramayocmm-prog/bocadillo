package app.bocadillo.ui.classic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Menu icons drawn in code for this app (own drawings of generic symbols: aperture, colour
 * wheel, crop marks…). No icon from the original APK is used.
 */
enum class ClassicIcon {
    EXPOSURE, SATURATION, TEMPERATURE, BOOST, SHARPEN, SMOOTH,
    CROP, RESIZE, ROTATE_FLIP, ROTATE_FREE, CURVES, RGB, BRIGHTNESS, BLUR, MASK, HISTORY, RESTORE,
    BALLOON, TITLE, BRUSH, MARKER, ERASER,
}

private val Gray1 = Color(0xFFE6E6E6)
private val Gray2 = Color(0xFF8C8C8C)

@Composable
fun ClassicIconView(icon: ClassicIcon, size: Dp = ClassicDims.RowIcon) {
    Canvas(Modifier.size(size)) { draw(icon) }
}

private fun DrawScope.draw(icon: ClassicIcon) {
    val w = size.width; val h = size.height; val c = Offset(w / 2, h / 2); val r = min(w, h) / 2 * 0.92f
    val line = r * 0.16f
    when (icon) {
        ClassicIcon.EXPOSURE -> {
            drawCircle(Brush.radialGradient(listOf(Gray1, Gray2), c, r), r, c)
            // six aperture blades
            for (i in 0 until 6) {
                val a = i * PI / 3
                val p = Offset(c.x + (r * 0.42f * cos(a)).toFloat(), c.y + (r * 0.42f * sin(a)).toFloat())
                val q = Offset(c.x + (r * 0.95f * cos(a + 0.9)).toFloat(), c.y + (r * 0.95f * sin(a + 0.9)).toFloat())
                drawLine(Color(0xFF333333), p, q, line * 0.55f)
            }
            drawCircle(Color(0xFF2B2B2B), r * 0.38f, c)
        }
        ClassicIcon.SATURATION -> {
            drawArc(Gray2, 90f, 180f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            val cols = listOf(0xFF3D5AFE, 0xFF7C4DFF, 0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF00E676, 0xFF00B0FF)
            cols.forEachIndexed { i, col -> drawArc(Color(col), -90f + i * 180f / cols.size, 180f / cols.size + 0.5f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r)) }
        }
        ClassicIcon.TEMPERATURE -> {
            drawArc(Color(0xFF64B5F6), 90f, 180f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            drawArc(Color(0xFFFF7043), -90f, 180f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        }
        ClassicIcon.BOOST -> {
            drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red), c), r, c)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), c, r * 0.8f), r, c)
        }
        ClassicIcon.SHARPEN -> {
            val p = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.62f, c.y + r * 0.9f); lineTo(c.x - r * 0.62f, c.y + r * 0.9f); close() }
            drawPath(p, Brush.horizontalGradient(listOf(Gray2, Color.White, Gray2), c.x - r, c.x + r))
        }
        ClassicIcon.SMOOTH -> drawCircle(Brush.radialGradient(listOf(Color.White, Gray2, Color(0xFF555555)), Offset(c.x - r * 0.35f, c.y - r * 0.35f), r * 1.6f), r, c)
        ClassicIcon.CROP -> {
            val s = Stroke(line, cap = StrokeCap.Square)
            drawPath(Path().apply { moveTo(c.x - r * 0.55f, c.y - r); lineTo(c.x - r * 0.55f, c.y + r * 0.55f); lineTo(c.x + r, c.y + r * 0.55f) }, Gray1, style = s)
            drawPath(Path().apply { moveTo(c.x - r, c.y - r * 0.55f); lineTo(c.x + r * 0.55f, c.y - r * 0.55f); lineTo(c.x + r * 0.55f, c.y + r) }, Gray1, style = s)
        }
        ClassicIcon.RESIZE -> {
            drawRect(Gray1, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(line * 0.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.25f, r * 0.15f))))
            drawRect(Gray1, Offset(c.x - r, c.y - r * 0.1f), Size(r * 1.1f, r * 1.1f), style = Stroke(line * 0.8f))
            drawLine(Gray1, Offset(c.x + r * 0.75f, c.y - r * 0.75f), Offset(c.x + r * 0.2f, c.y - r * 0.2f), line * 0.7f)
        }
        ClassicIcon.ROTATE_FLIP, ClassicIcon.ROTATE_FREE, ClassicIcon.RESTORE -> {
            val sweep = if (icon == ClassicIcon.RESTORE) 290f else 200f
            drawArc(Gray1, -200f, sweep, false, Offset(c.x - r * 0.75f, c.y - r * 0.55f), Size(r * 1.5f, r * 1.5f), style = Stroke(line * 1.3f, cap = StrokeCap.Round))
            val tip = Offset(c.x - r * 0.95f, c.y + r * 0.05f)
            drawPath(Path().apply { moveTo(tip.x - r * 0.35f, tip.y - r * 0.2f); lineTo(tip.x + r * 0.35f, tip.y - r * 0.2f); lineTo(tip.x, tip.y + r * 0.35f); close() }, Gray1)
            if (icon == ClassicIcon.ROTATE_FREE) drawCircle(Color(0xFF64B5F6), r * 0.18f, Offset(c.x + r * 0.1f, c.y + r * 0.2f))
        }
        ClassicIcon.CURVES -> {
            val p = Path().apply { moveTo(c.x - r, c.y + r * 0.8f); cubicTo(c.x - r * 0.2f, c.y - r * 0.9f, c.x + r * 0.1f, c.y + r * 0.9f, c.x + r, c.y - r * 0.8f) }
            drawPath(p, Gray1, style = Stroke(line * 0.7f))
            for (o in listOf(Offset(c.x - r, c.y + r * 0.8f), Offset(c.x, c.y), Offset(c.x + r, c.y - r * 0.8f))) drawCircle(Gray1, line * 0.8f, o)
        }
        ClassicIcon.RGB -> {
            drawCircle(Color(0xFFFF1744), r * 0.45f, Offset(c.x, c.y - r * 0.45f))
            drawCircle(Color(0xFF00E676), r * 0.45f, Offset(c.x - r * 0.5f, c.y + r * 0.4f))
            drawCircle(Color(0xFF2979FF), r * 0.45f, Offset(c.x + r * 0.5f, c.y + r * 0.4f))
        }
        ClassicIcon.BRIGHTNESS -> {
            drawCircle(Color(0xFFFFC107), r * 0.45f, c)
            for (i in 0 until 8) { val a = i * PI / 4; drawLine(Color(0xFFFFC107), Offset(c.x + (r * 0.62f * cos(a)).toFloat(), c.y + (r * 0.62f * sin(a)).toFloat()), Offset(c.x + (r * 0.95f * cos(a)).toFloat(), c.y + (r * 0.95f * sin(a)).toFloat()), line * 0.6f, cap = StrokeCap.Round) }
        }
        ClassicIcon.BLUR -> for (k in 4 downTo 1) drawCircle(Gray1.copy(alpha = 0.25f), r * k / 4f, c)
        ClassicIcon.MASK -> {
            drawCircle(Color(0x99E53935), r * 0.7f, Offset(c.x - r * 0.25f, c.y))
            drawCircle(Color(0x9943A047), r * 0.7f, Offset(c.x + r * 0.25f, c.y))
        }
        ClassicIcon.HISTORY -> {
            drawCircle(Gray1, r * 0.9f, c, style = Stroke(line * 0.8f))
            drawLine(Gray1, c, Offset(c.x, c.y - r * 0.55f), line * 0.8f, cap = StrokeCap.Round)
            drawLine(Gray1, c, Offset(c.x + r * 0.4f, c.y + r * 0.2f), line * 0.8f, cap = StrokeCap.Round)
        }
        ClassicIcon.BALLOON -> {
            drawOval(Color.White, Offset(c.x - r, c.y - r * 0.7f), Size(2 * r, r * 1.3f))
            drawPath(Path().apply { moveTo(c.x - r * 0.4f, c.y + r * 0.4f); lineTo(c.x - r * 0.75f, c.y + r * 0.95f); lineTo(c.x - r * 0.05f, c.y + r * 0.5f); close() }, Color.White)
            drawIntoCanvas {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK; textSize = r * 0.55f; textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create("casual", android.graphics.Typeface.BOLD)
                }
                it.nativeCanvas.drawText("ABC", c.x, c.y + r * 0.12f, paint)
            }
        }
        ClassicIcon.TITLE -> drawIntoCanvas {
            val base = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = r * 2.1f; textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
            }
            val y = c.y + r * 0.75f
            base.style = android.graphics.Paint.Style.STROKE; base.strokeWidth = r * 0.28f; base.color = android.graphics.Color.WHITE
            base.strokeJoin = android.graphics.Paint.Join.ROUND
            it.nativeCanvas.drawText("T", c.x, y, base)
            base.style = android.graphics.Paint.Style.FILL; base.color = 0xFF5E35B1.toInt()
            it.nativeCanvas.drawText("T", c.x, y, base)
        }
        ClassicIcon.BRUSH -> {
            drawLine(Color(0xFFBCAAA4), Offset(c.x + r * 0.8f, c.y - r * 0.8f), Offset(c.x - r * 0.1f, c.y + r * 0.1f), line * 1.1f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFE53935), r * 0.38f, Offset(c.x - r * 0.4f, c.y + r * 0.45f))
        }
        ClassicIcon.MARKER -> drawCircle(Color(0xFFE53935), r * 0.75f, c, style = Stroke(line * 1.4f))
        ClassicIcon.ERASER -> rotate(-35f, c) {
            drawRoundRect(Color(0xFFF48FB1), Offset(c.x - r * 0.9f, c.y - r * 0.4f), Size(r * 1.1f, r * 0.8f), CornerRadius(r * 0.1f))
            drawRoundRect(Color.White, Offset(c.x + r * 0.2f, c.y - r * 0.4f), Size(r * 0.7f, r * 0.8f), CornerRadius(r * 0.1f))
        }
    }
}

@Suppress("unused") private val keepJoin = StrokeJoin.Round
