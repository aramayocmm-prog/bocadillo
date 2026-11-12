package app.bocadillo.ui.classic

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size

/**
 * Category illustrations of the sticker menu (1.2.0, decision 6): original vector drawings made
 * for this app (not taken from any other app), in one flat, colourful style — a dark outline,
 * a lighter highlight, and nothing behind the object (transparent background, no boxes or
 * scenes). Drawn on a 100 × 100 grid and scaled, so they are sharp at every screen density.
 */
@Composable
fun CategoryIcon(id: String, size: Dp, description: String? = null, dimmed: Boolean = false) {
    Canvas(
        Modifier.size(size).alpha(if (dimmed) 0.85f else 1f)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val k = this.size.minDimension / 100f
        scale(k, k, pivot = Offset.Zero) { draw(id) }
    }
}

private val Ink = Color(0xFF2B2B33)
private const val W = 3.2f

private fun DrawScope.shape(p: Path, fill: Color, outline: Boolean = true) {
    drawPath(p, fill)
    if (outline) drawPath(p, Ink, style = Stroke(W, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
private fun DrawScope.circle(c: Offset, r: Float, fill: Color, outline: Boolean = true) {
    drawCircle(fill, r, c)
    if (outline) drawCircle(Ink, r, c, style = Stroke(W))
}
private fun DrawScope.oval(l: Float, t: Float, r: Float, b: Float, fill: Color, outline: Boolean = true) {
    drawOval(fill, Offset(l, t), Size(r - l, b - t))
    if (outline) drawOval(Ink, Offset(l, t), Size(r - l, b - t), style = Stroke(W))
}
private fun DrawScope.rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, fill: Color, outline: Boolean = true) {
    drawRoundRect(fill, Offset(l, t), Size(r - l, b - t), CornerRadius(rad))
    if (outline) drawRoundRect(Ink, Offset(l, t), Size(r - l, b - t), CornerRadius(rad), style = Stroke(W))
}
private fun DrawScope.line(a: Offset, b: Offset, c: Color = Ink, w: Float = W) = drawLine(c, a, b, w, StrokeCap.Round)
private fun DrawScope.shine(l: Float, t: Float, r: Float, b: Float) = drawOval(Color.White.copy(alpha = 0.55f), Offset(l, t), Size(r - l, b - t))
private fun path(vararg pts: Float, close: Boolean = true) = Path().apply {
    moveTo(pts[0], pts[1]); var i = 2
    while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    if (close) close()
}
private fun star(cx: Float, cy: Float, r: Float, inner: Float, n: Int = 5, rot: Float = -90f) = Path().apply {
    for (i in 0 until n * 2) {
        val a = Math.toRadians((rot + i * 180f / n).toDouble())
        val rr = if (i % 2 == 0) r else inner
        val x = cx + (rr * Math.cos(a)).toFloat(); val y = cy + (rr * Math.sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
private fun heart(cx: Float, cy: Float, s: Float) = Path().apply {
    moveTo(cx, cy + 0.9f * s)
    cubicTo(cx - 1.4f * s, cy - 0.1f * s, cx - 0.9f * s, cy - 1.1f * s, cx, cy - 0.45f * s)
    cubicTo(cx + 0.9f * s, cy - 1.1f * s, cx + 1.4f * s, cy - 0.1f * s, cx, cy + 0.9f * s)
    close()
}

private fun DrawScope.draw(id: String) {
    when (id) {
        "word_balloon" -> {
            val p = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(10f, 14f, 90f, 66f), CornerRadius(24f))) }
            val tail = path(30f, 62f, 22f, 88f, 48f, 64f)
            drawPath(tail, Color.White); drawPath(p, Color.White)
            drawPath(tail, Ink, style = Stroke(W, join = StrokeJoin.Round)); drawPath(p, Ink, style = Stroke(W))
            drawLine(Color.White, Offset(32f, 64.5f), Offset(46f, 64.5f), 5f)
            for (x in listOf(34f, 50f, 66f)) circle(Offset(x, 40f), 5f, Color(0xFF4F8DF5), false)
        }
        "title" -> {
            rrect(8f, 26f, 92f, 74f, 10f, Color(0xFFFFC83D))
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(14f, 30f), Size(72f, 10f), CornerRadius(5f))
            // a bold "Aa" made of strokes
            line(Offset(22f, 64f), Offset(34f, 34f), Ink, 7f); line(Offset(34f, 34f), Offset(46f, 64f), Ink, 7f); line(Offset(27f, 53f), Offset(41f, 53f), Ink, 6f)
            drawCircle(Ink, 9f, Offset(64f, 55f), style = Stroke(6.5f)); line(Offset(73f, 44f), Offset(73f, 65f), Ink, 6.5f)
        }
        "hats_helmets" -> {
            oval(6f, 62f, 94f, 82f, Color(0xFF3B4C8C))
            shape(path(24f, 70f, 28f, 30f, 50f, 20f, 72f, 30f, 76f, 70f), Color(0xFF4F67C7))
            drawRect(Color(0xFFE94B5B), Offset(26f, 56f), Size(48f, 9f)); drawRect(Ink, Offset(26f, 56f), Size(48f, 9f), style = Stroke(2f))
            shine(34f, 28f, 46f, 44f)
        }
        "hairstyles" -> {
            val hair = Path().apply {
                moveTo(18f, 78f); cubicTo(6f, 40f, 24f, 12f, 52f, 12f); cubicTo(82f, 12f, 96f, 40f, 82f, 78f)
                cubicTo(76f, 60f, 72f, 50f, 64f, 40f); cubicTo(54f, 52f, 38f, 54f, 28f, 50f); cubicTo(24f, 60f, 22f, 70f, 18f, 78f); close()
            }
            shape(hair, Color(0xFFB8682E))
            line(Offset(40f, 22f), Offset(34f, 40f), Color(0xFF8A4A1E), 3f); line(Offset(58f, 20f), Offset(66f, 36f), Color(0xFF8A4A1E), 3f)
            shine(40f, 16f, 58f, 24f)
        }
        "eyes" -> {
            for (x in listOf(30f, 70f)) {
                oval(x - 18f, 30f, x + 18f, 70f, Color.White)
                circle(Offset(x + 3f, 54f), 10f, Color(0xFF3CA0E8))
                drawCircle(Ink, 5f, Offset(x + 3f, 54f)); drawCircle(Color.White, 2.5f, Offset(x + 6f, 50f))
            }
        }
        "masks_glasses" -> {
            val mask = Path().apply {
                moveTo(8f, 42f); cubicTo(20f, 30f, 40f, 32f, 50f, 40f); cubicTo(60f, 32f, 80f, 30f, 92f, 42f)
                cubicTo(94f, 60f, 80f, 70f, 66f, 66f); cubicTo(58f, 64f, 54f, 56f, 50f, 56f); cubicTo(46f, 56f, 42f, 64f, 34f, 66f)
                cubicTo(20f, 70f, 6f, 60f, 8f, 42f); close()
            }
            shape(mask, Color(0xFF8E4FD8))
            oval(20f, 42f, 40f, 56f, Color(0xFF2B2B33), false); oval(60f, 42f, 80f, 56f, Color(0xFF2B2B33), false)
            shine(18f, 36f, 34f, 41f)
        }
        "beards_moustaches" -> {
            val m = Path().apply {
                moveTo(50f, 44f); cubicTo(40f, 34f, 22f, 36f, 16f, 46f); cubicTo(10f, 56f, 4f, 52f, 4f, 44f)
                cubicTo(4f, 62f, 28f, 70f, 50f, 56f); cubicTo(72f, 70f, 96f, 62f, 96f, 44f); cubicTo(96f, 52f, 90f, 56f, 84f, 46f)
                cubicTo(78f, 36f, 60f, 34f, 50f, 44f); close()
            }
            shape(m, Color(0xFF5B3A23)); shine(22f, 42f, 36f, 47f)
        }
        "ears_nose_mouth" -> {
            val lips = Path().apply {
                moveTo(10f, 52f); cubicTo(26f, 30f, 40f, 34f, 50f, 40f); cubicTo(60f, 34f, 74f, 30f, 90f, 52f)
                cubicTo(74f, 76f, 26f, 76f, 10f, 52f); close()
            }
            shape(lips, Color(0xFFE53F5F)); line(Offset(14f, 52f), Offset(86f, 52f), Color(0xFF9E1F3B), 3f); shine(28f, 40f, 42f, 46f)
        }
        "masks_pointers" -> {
            val m = Path().apply { moveTo(20f, 18f); cubicTo(40f, 10f, 60f, 10f, 80f, 18f); cubicTo(84f, 50f, 72f, 84f, 50f, 90f); cubicTo(28f, 84f, 16f, 50f, 20f, 18f); close() }
            shape(m, Color(0xFFF2F2F2))
            shape(path(30f, 40f, 44f, 36f, 42f, 46f), Ink, false); shape(path(70f, 40f, 56f, 36f, 58f, 46f), Ink, false)
            val smile = Path().apply { moveTo(34f, 64f); quadraticTo(50f, 76f, 66f, 64f) }
            drawPath(smile, Ink, style = Stroke(4f, cap = StrokeCap.Round))
            drawOval(Color(0xFFFF8FA3), Offset(26f, 52f), Size(10f, 6f)); drawOval(Color(0xFFFF8FA3), Offset(64f, 52f), Size(10f, 6f))
        }
        "emoticons" -> {
            circle(Offset(50f, 50f), 40f, Color(0xFFFFD23F)); shine(26f, 18f, 46f, 30f)
            drawOval(Ink, Offset(33f, 34f), Size(9f, 14f)); drawOval(Ink, Offset(58f, 34f), Size(9f, 14f))
            val smile = Path().apply { moveTo(30f, 58f); quadraticTo(50f, 80f, 70f, 58f) }
            drawPath(smile, Ink, style = Stroke(5f, cap = StrokeCap.Round))
        }
        "celebrations" -> {
            shape(path(14f, 88f, 40f, 30f, 72f, 62f), Color(0xFFFF7A3D))
            line(Offset(24f, 66f), Offset(44f, 76f), Color(0xFFFFD23F), 4f); line(Offset(31f, 50f), Offset(56f, 66f), Color(0xFF4F8DF5), 4f)
            for ((c, p) in listOf(Color(0xFFE53F5F) to Offset(62f, 22f), Color(0xFF3CC08A) to Offset(82f, 36f), Color(0xFF8E4FD8) to Offset(76f, 14f), Color(0xFFFFD23F) to Offset(90f, 56f)))
                drawCircle(c, 5f, p)
            line(Offset(52f, 30f), Offset(58f, 12f), Color(0xFF4F8DF5), 3.5f); line(Offset(66f, 46f), Offset(88f, 44f), Color(0xFFE53F5F), 3.5f)
        }
        "hands" -> {
            val hand = Path().apply {
                moveTo(30f, 92f); lineTo(24f, 58f); cubicTo(22f, 50f, 30f, 48f, 33f, 56f); lineTo(36f, 64f); lineTo(36f, 22f)
                cubicTo(36f, 14f, 46f, 14f, 46f, 22f); lineTo(46f, 48f); lineTo(48f, 18f); cubicTo(48f, 10f, 58f, 10f, 58f, 18f)
                lineTo(58f, 48f); lineTo(60f, 24f); cubicTo(60f, 16f, 70f, 16f, 70f, 24f); lineTo(70f, 52f); lineTo(72f, 34f)
                cubicTo(72f, 26f, 82f, 26f, 81f, 34f); lineTo(78f, 70f); cubicTo(76f, 84f, 70f, 92f, 60f, 92f); close()
            }
            shape(hand, Color(0xFFF6C08F)); shine(38f, 18f, 44f, 34f)
        }
        "nature" -> {
            val leaf = Path().apply { moveTo(18f, 84f); cubicTo(10f, 40f, 40f, 12f, 88f, 12f); cubicTo(88f, 60f, 60f, 92f, 18f, 84f); close() }
            shape(leaf, Color(0xFF3CC08A))
            val vein = Path().apply { moveTo(18f, 84f); quadraticTo(50f, 50f, 80f, 20f) }
            drawPath(vein, Color(0xFF1E7A52), style = Stroke(3.5f, cap = StrokeCap.Round))
            shine(40f, 24f, 60f, 34f)
        }
        "animals" -> {
            circle(Offset(28f, 26f), 13f, Color(0xFFC98A4B)); circle(Offset(72f, 26f), 13f, Color(0xFFC98A4B))
            circle(Offset(50f, 56f), 34f, Color(0xFFDDA15E))
            oval(36f, 58f, 64f, 80f, Color(0xFFF3D3A6))
            drawCircle(Ink, 4.5f, Offset(38f, 48f)); drawCircle(Ink, 4.5f, Offset(62f, 48f)); drawOval(Ink, Offset(45f, 60f), Size(10f, 7f))
            shine(30f, 28f, 44f, 36f)
        }
        "prints_stamps" -> {
            oval(30f, 46f, 70f, 84f, Color(0xFF7B5BE0))
            for ((x, y) in listOf(22f to 40f, 38f to 24f, 62f to 24f, 78f to 40f)) oval(x - 8f, y - 10f, x + 8f, y + 10f, Color(0xFF7B5BE0))
            shine(38f, 52f, 50f, 60f)
        }
        "romance" -> { shape(heart(50f, 54f, 36f), Color(0xFFE53F5F)); shine(26f, 30f, 40f, 40f) }
        "signs_symbols" -> {
            shape(path(50f, 10f, 92f, 86f, 8f, 86f), Color(0xFFFFC83D))
            line(Offset(50f, 38f), Offset(50f, 62f), Ink, 7f); drawCircle(Ink, 4.5f, Offset(50f, 74f))
        }
        "comic" -> {
            shape(star(50f, 50f, 46f, 26f, 10, -90f), Color(0xFFFF6B3D))
            shape(star(50f, 50f, 30f, 18f, 10, -72f), Color(0xFFFFD23F), false)
            line(Offset(42f, 40f), Offset(58f, 60f), Ink, 5f); line(Offset(58f, 40f), Offset(42f, 60f), Ink, 5f)
        }
        "food_drink" -> {
            shape(path(28f, 30f, 72f, 30f, 66f, 90f, 34f, 90f), Color(0xFFFF8F5A))
            rrect(24f, 22f, 76f, 32f, 4f, Color(0xFFF2F2F2))
            line(Offset(56f, 22f), Offset(64f, 6f), Color(0xFF3CC08A), 5f)
            drawRect(Color.White.copy(alpha = 0.45f), Offset(36f, 38f), Size(6f, 42f))
        }
        "accessories" -> {
            val strap = Path().apply { moveTo(30f, 36f); cubicTo(30f, 10f, 70f, 10f, 70f, 36f) }
            drawPath(strap, Ink, style = Stroke(5f, cap = StrokeCap.Round))
            rrect(16f, 34f, 84f, 86f, 12f, Color(0xFFE94B9B)); drawRect(Color(0xFFFFD23F), Offset(44f, 50f), Size(12f, 10f))
            shine(22f, 40f, 40f, 48f)
        }
        "sport" -> {
            circle(Offset(50f, 50f), 40f, Color(0xFFF2F2F2))
            shape(path(50f, 34f, 64f, 44f, 59f, 60f, 41f, 60f, 36f, 44f), Ink, false)
            for ((a, b) in listOf(Offset(50f, 34f) to Offset(50f, 12f), Offset(64f, 44f) to Offset(86f, 38f), Offset(59f, 60f) to Offset(72f, 80f), Offset(41f, 60f) to Offset(28f, 80f), Offset(36f, 44f) to Offset(14f, 38f)))
                line(a, b, Ink, 3f)
            shine(26f, 20f, 40f, 30f)
        }
        "basic_shapes" -> {
            circle(Offset(32f, 34f), 20f, Color(0xFF4F8DF5))
            rrect(50f, 48f, 88f, 86f, 6f, Color(0xFF3CC08A))
            shape(path(28f, 54f, 48f, 88f, 8f, 88f), Color(0xFFFFC83D))
        }
        "summer" -> {
            circle(Offset(50f, 50f), 22f, Color(0xFFFFC83D))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                line(Offset(50f + 30f * Math.cos(a).toFloat(), 50f + 30f * Math.sin(a).toFloat()), Offset(50f + 42f * Math.cos(a).toFloat(), 50f + 42f * Math.sin(a).toFloat()), Color(0xFFFF9A2E), 6f)
            }
            shine(38f, 36f, 50f, 44f)
        }
        "halloween" -> {
            rrect(46f, 10f, 54f, 26f, 2f, Color(0xFF3CC08A))
            for ((l, r) in listOf(12f to 52f, 48f to 88f)) oval(l, 24f, r, 88f, Color(0xFFFF8A2E))
            oval(28f, 22f, 72f, 88f, Color(0xFFFF9A3E))
            shape(path(36f, 46f, 44f, 46f, 40f, 38f), Ink, false); shape(path(56f, 46f, 64f, 46f, 60f, 38f), Ink, false)
            shape(path(34f, 62f, 40f, 68f, 46f, 62f, 50f, 68f, 54f, 62f, 60f, 68f, 66f, 62f, 60f, 74f, 40f, 74f), Ink, false)
        }
        "thanksgiving" -> {
            for ((i, c) in listOf(Color(0xFFE53F5F), Color(0xFFFF9A2E), Color(0xFFFFC83D), Color(0xFF3CC08A), Color(0xFFFF9A2E)).withIndex()) {
                val a = Math.toRadians(-160.0 + i * 35.0)
                val cx = 50f + 26f * Math.cos(a).toFloat(); val cy = 58f + 26f * Math.sin(a).toFloat()
                oval(cx - 11f, cy - 18f, cx + 11f, cy + 18f, c)
            }
            circle(Offset(50f, 62f), 22f, Color(0xFF9C5B2E)); circle(Offset(50f, 40f), 11f, Color(0xFFB0703E))
            drawCircle(Ink, 2.5f, Offset(47f, 38f)); shape(path(54f, 40f, 62f, 43f, 54f, 46f), Color(0xFFFFC83D))
        }
        "christmas_holiday" -> {
            shape(path(50f, 8f, 80f, 48f, 66f, 48f, 88f, 80f, 12f, 80f, 34f, 48f, 20f, 48f), Color(0xFF2FA866))
            rrect(42f, 80f, 58f, 94f, 2f, Color(0xFF8A5A33))
            drawCircle(Color(0xFFE53F5F), 4.5f, Offset(40f, 62f)); drawCircle(Color(0xFFFFD23F), 4.5f, Offset(62f, 56f)); drawCircle(Color(0xFF4F8DF5), 4.5f, Offset(54f, 72f))
            shape(star(50f, 10f, 9f, 4f), Color(0xFFFFD23F))
        }
        "happy_new_year" -> {
            for ((c, p) in listOf(Color(0xFFFFC83D) to Offset(36f, 42f), Color(0xFFE94B9B) to Offset(66f, 34f), Color(0xFF4F8DF5) to Offset(58f, 70f))) {
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(Offset(p.x + 6f * Math.cos(a).toFloat(), p.y + 6f * Math.sin(a).toFloat()), Offset(p.x + 18f * Math.cos(a).toFloat(), p.y + 18f * Math.sin(a).toFloat()), c, 4f)
                }
                drawCircle(c, 4f, p)
            }
        }
        "easter_spring" -> {
            oval(22f, 10f, 78f, 90f, Color(0xFFB4E1FF))
            val zig = path(24f, 46f, 34f, 38f, 44f, 46f, 54f, 38f, 64f, 46f, 74f, 38f, 77f, 44f, 77f, 56f, 23f, 56f)
            drawPath(zig, Color(0xFFFF8FB8))
            drawOval(Ink, Offset(22f, 10f), Size(56f, 80f), style = Stroke(W))
            drawCircle(Color(0xFFFFC83D), 4f, Offset(40f, 72f)); drawCircle(Color(0xFFFFC83D), 4f, Offset(58f, 72f)); shine(32f, 18f, 44f, 30f)
        }
        "st_patricks_day" -> {
            for ((x, y) in listOf(50f to 30f, 30f to 50f, 70f to 50f)) shape(heart(x, y, 15f), Color(0xFF3CC08A))
            drawCircle(Color(0xFF3CC08A), 9f, Offset(50f, 48f))
            val stem = Path().apply { moveTo(50f, 56f); quadraticTo(52f, 76f, 64f, 90f) }
            drawPath(stem, Color(0xFF1E7A52), style = Stroke(5f, cap = StrokeCap.Round))
        }
        "independence_day" -> {
            line(Offset(20f, 10f), Offset(20f, 92f), Ink, 5f)
            val flag = Path().apply { moveTo(22f, 14f); cubicTo(44f, 6f, 60f, 22f, 88f, 14f); lineTo(88f, 58f); cubicTo(60f, 66f, 44f, 50f, 22f, 58f); close() }
            drawPath(flag, Brush.verticalGradient(listOf(Color(0xFF4F8DF5), Color(0xFF4F8DF5), Color.White, Color.White, Color(0xFFE53F5F), Color(0xFFE53F5F)), 10f, 64f))
            drawPath(flag, Ink, style = Stroke(W, join = StrokeJoin.Round))
        }
        "travel_places" -> {
            val pin = Path().apply { moveTo(50f, 94f); cubicTo(26f, 64f, 18f, 50f, 18f, 38f); cubicTo(18f, 18f, 34f, 6f, 50f, 6f); cubicTo(66f, 6f, 82f, 18f, 82f, 38f); cubicTo(82f, 50f, 74f, 64f, 50f, 94f); close() }
            shape(pin, Color(0xFFE53F5F)); circle(Offset(50f, 38f), 12f, Color.White); shine(28f, 16f, 40f, 26f)
        }
        "objects" -> {
            circle(Offset(50f, 40f), 28f, Color(0xFFFFD23F))
            rrect(38f, 64f, 62f, 86f, 4f, Color(0xFF9EA7B8))
            line(Offset(40f, 72f), Offset(60f, 72f), Ink, 2.5f); line(Offset(40f, 79f), Offset(60f, 79f), Ink, 2.5f)
            val fil = Path().apply { moveTo(42f, 50f); lineTo(46f, 40f); lineTo(50f, 50f); lineTo(54f, 40f); lineTo(58f, 50f) }
            drawPath(fil, Color(0xFFFF8A2E), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)); shine(34f, 20f, 46f, 30f)
        }
        "people" -> {
            circle(Offset(50f, 30f), 18f, Color(0xFFF6C08F))
            val body = Path().apply { moveTo(16f, 92f); cubicTo(16f, 60f, 30f, 52f, 50f, 52f); cubicTo(70f, 52f, 84f, 60f, 84f, 92f); close() }
            shape(body, Color(0xFF4F8DF5)); shine(40f, 18f, 50f, 26f)
        }
        "activity" -> {
            circle(Offset(50f, 50f), 40f, Color(0xFFFF8A2E))
            line(Offset(10f, 50f), Offset(90f, 50f), Ink, 3f); line(Offset(50f, 10f), Offset(50f, 90f), Ink, 3f)
            val c1 = Path().apply { moveTo(24f, 18f); quadraticTo(40f, 50f, 24f, 82f) }; val c2 = Path().apply { moveTo(76f, 18f); quadraticTo(60f, 50f, 76f, 82f) }
            drawPath(c1, Ink, style = Stroke(3f)); drawPath(c2, Ink, style = Stroke(3f)); shine(28f, 20f, 42f, 30f)
        }
        "my_stickers" -> {
            rrect(14f, 22f, 74f, 82f, 10f, Color(0xFF9EC9FF)); rrect(26f, 12f, 86f, 72f, 10f, Color(0xFFFFFFFF))
            shape(star(56f, 42f, 18f, 8f), Color(0xFFFFC83D))
        }
        "import" -> {
            rrect(14f, 40f, 86f, 88f, 8f, Color(0xFFFFC83D)); rrect(14f, 28f, 50f, 44f, 6f, Color(0xFFFFB020))
            line(Offset(50f, 10f), Offset(50f, 66f), Color(0xFF2B6FD6), 7f)
            shape(path(36f, 52f, 50f, 70f, 64f, 52f), Color(0xFF2B6FD6), false)
        }
        else -> { shape(star(50f, 50f, 40f, 18f), Color(0xFF9EA7B8)) }
    }
}
