package app.bocadillo.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.bocadillo.core.imaging.objects.BalloonGeometry
import app.bocadillo.core.imaging.objects.ObjectGeometry
import app.bocadillo.core.imaging.objects.PathCmd
import app.bocadillo.core.imaging.objects.StickerCatalog
import app.bocadillo.core.imaging.objects.StickerDef
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.CanvasObject
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.TextAlign
import app.bocadillo.core.model.TextObject
import app.bocadillo.core.model.TextStyle
import android.util.LruCache
import app.bocadillo.data.FontRepository
import app.bocadillo.data.NotoStickers
import com.caverock.androidsvg.SVG
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws overlay objects with android.graphics. The same code renders the on-screen preview and
 * the exported picture, so what you see is what you export. Not thread-safe: use one instance
 * per thread (UI vs export).
 *
 * All sizes derive from the canvas size: u = min(w, h).
 */
class ObjectRenderer(private val fonts: FontRepository, private val catalog: StickerCatalog, private val noto: NotoStickers) {

    /** Fonts and imported stickers of the open project. */
    var assets: Map<String, ByteArray> = emptyMap()
        set(value) { if (value !== field) { field = value; bitmaps.clear(); layouts.clear() } }

    private val bitmaps = HashMap<String, Bitmap?>()
    private val stickerPaths = HashMap<String, List<Path>>()
    private val layouts = object : LinkedHashMap<Any, StaticLayout>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, StaticLayout>?) = size > 48
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Parsed Noto SVGs, private to this renderer (SVG objects are not shared across threads). */
    private val notoSvgs = LruCache<String, SVG>(48)

    fun drawAll(canvas: Canvas, objects: List<CanvasObject>, w: Float, h: Float) {
        for (o in objects) draw(canvas, o, w, h)
    }

    fun draw(canvas: Canvas, o: CanvasObject, w: Float, h: Float) {
        val u = ObjectGeometry.unit(w, h)
        val m = Matrix().apply { setValues(ObjectGeometry.toCanvas(o.transform, w, h).toMatrixValues()) }
        val save = canvas.save()
        canvas.concat(m)
        if (o.opacity < 0.999f) canvas.saveLayerAlpha(null, (o.opacity * 255).toInt().coerceIn(0, 255))
        when (o) {
            is TextObject -> drawText(canvas, o, u)
            is BalloonObject -> drawBalloon(canvas, o, u)
            is StickerObject -> drawSticker(canvas, o, u)
        }
        canvas.restoreToCount(save)
    }

    /** Half extents of the object's box in local px (before its transform). */
    fun halfSize(o: CanvasObject, w: Float, h: Float): Pair<Float, Float> {
        val u = ObjectGeometry.unit(w, h)
        return when (o) {
            is TextObject -> {
                if (o.curve != 0f) curvedBox(o, u) else {
                    val l = titleLayout(o.text, o.style, u)
                    val pad = o.style.outlineWidth * o.style.size * u
                    (l.width / 2f + pad) to (l.height / 2f + pad)
                }
            }
            is BalloonObject -> (o.width * u / 2f) to (o.height * u / 2f)
            is StickerObject -> stickerSize(o, u).let { it.first / 2f to it.second / 2f }
        }
    }

    // ------------------------------------------------------------------ text

    private fun textPaint(style: TextStyle, sizePx: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts.typeface(style.font, style.bold, style.italic, assets)
        textSize = max(1f, sizePx)
        letterSpacing = style.letterSpacing
        color = style.color
        isSubpixelText = true
    }

    private fun alignment(a: TextAlign) = when (a) {
        TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
        TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }

    private fun build(text: String, paint: TextPaint, width: Int, style: TextStyle): StaticLayout {
        val t = text.ifEmpty { " " }
        return StaticLayout.Builder.obtain(t, 0, t.length, paint, max(1, width))
            .setAlignment(alignment(style.align))
            .setLineSpacing(0f, style.lineHeight.coerceIn(0.5f, 3f))
            .setIncludePad(true)
            .build()
    }

    /** Title layout: each line keeps its natural width (no wrapping). */
    private fun titleLayout(text: String, style: TextStyle, u: Float): StaticLayout =
        layouts.getOrPut(Triple("title", text, style) to u) {
            val p = textPaint(style, style.size * u)
            val width = ceil(text.ifEmpty { " " }.split('\n').maxOf { p.measureText(it) }).toInt() + 2
            build(text, p, width, style)
        }

    private fun drawText(c: Canvas, o: TextObject, u: Float) {
        val sizePx = o.style.size * u
        if (o.curve != 0f) { drawCurved(c, o, u); return }
        val l = titleLayout(o.text, o.style, u)
        c.save()
        c.translate(-l.width / 2f, -l.height / 2f)
        if (alpha(o.style.backgroundColor) > 0) {
            val pad = sizePx * 0.25f
            fill.color = o.style.backgroundColor
            c.drawRoundRect(RectF(-pad, -pad * 0.5f, l.width + pad, l.height + pad * 0.5f), pad, pad, fill)
        }
        drawStyled(c, l, o.style, sizePx)
        c.restore()
    }

    /** Shadow pass, outline pass, fill pass. */
    private fun drawStyled(c: Canvas, l: StaticLayout, style: TextStyle, sizePx: Float) {
        val p = l.paint
        val outline = style.outlineWidth * sizePx
        if (alpha(style.shadowColor) > 0) {
            c.save()
            c.translate(style.shadowDx * sizePx, style.shadowDy * sizePx)
            p.style = Paint.Style.FILL_AND_STROKE
            p.strokeWidth = outline * 2; p.strokeJoin = Paint.Join.ROUND
            p.color = style.shadowColor
            p.maskFilter = if (style.shadowBlur > 0f) BlurMaskFilter(style.shadowBlur * sizePx, BlurMaskFilter.Blur.NORMAL) else null
            l.draw(c)
            p.maskFilter = null
            c.restore()
        }
        if (outline > 0f && alpha(style.outlineColor) > 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = outline * 2; p.strokeJoin = Paint.Join.ROUND
            p.color = style.outlineColor
            l.draw(c)
        }
        p.style = Paint.Style.FILL
        p.color = style.color
        l.draw(c)
    }

    private fun curvedGeometry(o: TextObject, u: Float): Triple<TextPaint, String, Float> {
        val p = textPaint(o.style, o.style.size * u)
        val line = o.text.replace('\n', ' ').ifEmpty { " " }
        return Triple(p, line, p.measureText(line))
    }

    private fun curvedBox(o: TextObject, u: Float): Pair<Float, Float> {
        val (p, _, width) = curvedGeometry(o, u)
        val sweep = Math.toRadians(abs(o.curve).toDouble().coerceAtLeast(1.0))
        val r = (width / sweep).toFloat()
        val half = if (abs(o.curve) >= 180f) r else (r * kotlin.math.sin(sweep / 2)).toFloat()
        val sag = r * (1 - kotlin.math.cos(sweep / 2).toFloat())
        return (half + p.textSize) to (sag / 2f + p.textSize)
    }

    /**
     * Circular Wrap: the line is laid on an arc whose length equals the text width.
     * Positive curve arches upward (text on top of the circle), negative bends downward.
     */
    private fun drawCurved(c: Canvas, o: TextObject, u: Float) {
        val (p, line, width) = curvedGeometry(o, u)
        val sizePx = p.textSize
        val sweepDeg = abs(o.curve).coerceIn(1f, 360f)
        val r = width / Math.toRadians(sweepDeg.toDouble()).toFloat()
        val path = Path()
        val (_, hh) = curvedBox(o, u)
        if (o.curve > 0f) {
            // circle centre below the text; arc around the top, left → right (clockwise)
            val cy = r - hh + sizePx
            path.addArc(RectF(-r, cy - r, r, cy + r), 270f - sweepDeg / 2f, sweepDeg)
        } else {
            val cy = -r + hh - sizePx * 0.2f
            path.addArc(RectF(-r, cy - r, r, cy + r), 90f + sweepDeg / 2f, -sweepDeg)
        }
        val outline = o.style.outlineWidth * sizePx
        p.textAlign = Paint.Align.LEFT
        if (alpha(o.style.shadowColor) > 0) {
            c.save(); c.translate(o.style.shadowDx * sizePx, o.style.shadowDy * sizePx)
            p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = outline * 2; p.color = o.style.shadowColor
            p.maskFilter = if (o.style.shadowBlur > 0f) BlurMaskFilter(o.style.shadowBlur * sizePx, BlurMaskFilter.Blur.NORMAL) else null
            c.drawTextOnPath(line, path, 0f, 0f, p)
            p.maskFilter = null; c.restore()
        }
        if (outline > 0f && alpha(o.style.outlineColor) > 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = outline * 2; p.strokeJoin = Paint.Join.ROUND; p.color = o.style.outlineColor
            c.drawTextOnPath(line, path, 0f, 0f, p)
        }
        p.style = Paint.Style.FILL; p.color = o.style.color
        c.drawTextOnPath(line, path, 0f, 0f, p)
    }

    // ------------------------------------------------------------------ balloons

    private fun drawBalloon(c: Canvas, o: BalloonObject, u: Float) {
        val w = o.width * u; val h = o.height * u
        val geo = BalloonGeometry.outline(o.shape, w, h, o.tailX * u, o.tailY * u, o.tailWidth, o.hasTail)
        val path = Path()
        val pts = geo.contour
        path.moveTo(pts[0], pts[1])
        for (i in 1 until pts.size / 2) path.lineTo(pts[2 * i], pts[2 * i + 1])
        path.close()
        for (b in 0 until geo.bubbles.size / 3) path.addCircle(geo.bubbles[3 * b], geo.bubbles[3 * b + 1], geo.bubbles[3 * b + 2], Path.Direction.CW)
        fill.color = o.fillColor
        c.drawPath(path, fill)
        val sw = o.strokeWidth * u
        if (sw > 0f && alpha(o.strokeColor) > 0) {
            stroke.color = o.strokeColor; stroke.strokeWidth = sw
            stroke.pathEffect = if (geo.dashed) DashPathEffect(floatArrayOf(sw * 3f, sw * 2.2f), 0f) else null
            c.drawPath(path, stroke)
            stroke.pathEffect = null
        }
        if (o.text.isNotBlank()) {
            val boxW = (geo.textHalfW * 2).toInt(); val boxH = geo.textHalfH * 2
            fun layoutFor(sz: Float) = layouts.getOrPut(Triple("balloon", o.text, o.style) to (sz to boxW)) { build(o.text, textPaint(o.style, sz), boxW, o.style) }
            // auto-fit: shrink the caption until it fits the balloon
            var size = o.style.size * u
            var l = layoutFor(size)
            while (l.height > boxH && size >= 6f) { size *= 0.92f; l = layoutFor(size) }
            c.save()
            c.translate(-geo.textHalfW, -l.height / 2f)
            drawStyled(c, l, o.style, size)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ stickers

    /** Sticker box size in px (width, height). */
    fun stickerSize(o: StickerObject, u: Float): Pair<Float, Float> {
        val longest = o.size * u
        return if (o.aspect >= 1f) longest to longest / o.aspect else longest * o.aspect to longest
    }

    private fun drawSticker(c: Canvas, o: StickerObject, u: Float) {
        val (bw, bh) = stickerSize(o, u)
        if (o.source.startsWith("noto:")) {
            val id = o.source.removePrefix("noto:")
            val svg = notoSvgs.get(id) ?: noto.load(id)?.also { notoSvgs.put(id, it) } ?: return
            svg.renderToCanvas(c, RectF(-bw / 2, -bh / 2, bw / 2, bh / 2))
        } else if (o.isVector) {
            val def = catalog[o.source.removePrefix("builtin:")] ?: return
            val s = min(bw / def.width, bh / def.height)
            c.save()
            c.scale(s, s)
            c.translate(-def.width / 2f, -def.height / 2f)
            val paths = stickerPaths.getOrPut(def.id) { def.layers.map { toPath(it.path, it.evenOdd) } }
            def.layers.forEachIndexed { i, layer ->
                def.resolve(layer.fill, o.colors)?.let { fill.color = it; c.drawPath(paths[i], fill) }
                def.resolve(layer.stroke, o.colors)?.let {
                    if (layer.strokeWidth > 0f) {
                        stroke.color = it; stroke.strokeWidth = layer.strokeWidth * o.outlineScale
                        c.drawPath(paths[i], stroke)
                    }
                }
            }
            c.restore()
        } else {
            val bmp = bitmaps.getOrPut(o.source) {
                assets[o.source.removePrefix("asset:")]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } ?: return
            c.drawBitmap(bmp, null, RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), bmpPaint)
        }
    }

    /** Draws a catalogue sticker into a w×h box (for the picker grid). */
    fun drawStickerPreview(c: Canvas, def: StickerDef, w: Float, h: Float, colors: List<Int> = emptyList()) {
        val o = StickerObject("preview", source = "builtin:${def.id}", size = 1f, aspect = def.aspect, colors = colors)
        val save = c.save()
        c.translate(w / 2f, h / 2f)
        drawSticker(c, o, min(w, h) * 0.9f)
        c.restoreToCount(save)
    }

    companion object {
        fun toPath(cmds: List<PathCmd>, evenOdd: Boolean): Path = Path().apply {
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            for (cmd in cmds) when (cmd) {
                is PathCmd.Move -> moveTo(cmd.x, cmd.y)
                is PathCmd.Line -> lineTo(cmd.x, cmd.y)
                is PathCmd.Quad -> quadTo(cmd.x1, cmd.y1, cmd.x, cmd.y)
                is PathCmd.Cubic -> cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x, cmd.y)
                PathCmd.Close -> close()
            }
        }

        private fun alpha(c: Int) = c ushr 24
    }
}
