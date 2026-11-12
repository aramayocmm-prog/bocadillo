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
        if (o.transform.hasPerspective) {
            // four-point perspective inside the object's own frame
            val (hw, hh) = halfSize(o, w, h)
            val q = ObjectGeometry.localCorners(o.transform, hw, hh)
            val pm = Matrix()
            if (pm.setPolyToPoly(floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh), 0, q.flatMap { listOf(it.first, it.second) }.toFloatArray(), 0, 4)) canvas.concat(pm)
        }
        if (o.opacity < 0.999f) canvas.saveLayerAlpha(null, (o.opacity * 255).toInt().coerceIn(0, 255))
        try {
            when (o) {
                is TextObject -> drawText(canvas, o, u)
                is BalloonObject -> drawBalloon(canvas, o, u)
                is StickerObject -> drawSticker(canvas, o, u)
            }
        } catch (e: Exception) {
            // a broken object must never take the whole editor down
            android.util.Log.w("ObjectRenderer", "Cannot draw object ${o.id}", e)
        } finally {
            canvas.restoreToCount(save)
        }
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
        val outer = style.outline2Width * sizePx
        if (outer > 0f && alpha(style.outline2Color) > 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = (outline + outer) * 2; p.strokeJoin = Paint.Join.ROUND
            p.color = style.outline2Color
            l.draw(c)
        }
        if (outline > 0f && alpha(style.outlineColor) > 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = outline * 2; p.strokeJoin = Paint.Join.ROUND
            p.color = style.outlineColor
            l.draw(c)
        }
        p.style = Paint.Style.FILL
        p.color = style.color
        if (alpha(style.gradientColor) > 0) {
            // top → bottom of each line's glyphs
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, l.height.toFloat(), style.color, style.gradientColor, android.graphics.Shader.TileMode.CLAMP)
            p.color = 0xFF000000.toInt()
            l.draw(c)
            p.shader = null
        } else l.draw(c)
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
        val outer = o.style.outline2Width * sizePx
        if (outer > 0f && alpha(o.style.outline2Color) > 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = (outline + outer) * 2; p.strokeJoin = Paint.Join.ROUND; p.color = o.style.outline2Color
            c.drawTextOnPath(line, path, 0f, 0f, p)
        }
        if (outline > 0f && alpha(o.style.outlineColor) > 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = outline * 2; p.strokeJoin = Paint.Join.ROUND; p.color = o.style.outlineColor
            c.drawTextOnPath(line, path, 0f, 0f, p)
        }
        p.style = Paint.Style.FILL; p.color = o.style.color
        if (alpha(o.style.gradientColor) > 0) {
            val (_, hh2) = curvedBox(o, u)
            p.shader = android.graphics.LinearGradient(0f, -hh2, 0f, hh2, o.style.color, o.style.gradientColor, android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawTextOnPath(line, path, 0f, 0f, p)
        p.shader = null
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
        if (alpha(o.shadowColor) > 0) {
            c.save(); c.translate(w * 0.035f, h * 0.05f)
            fill.color = o.shadowColor
            fill.maskFilter = BlurMaskFilter(max(1f, min(w, h) * 0.03f), BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path, fill)
            fill.maskFilter = null
            c.restore()
        }
        fill.color = o.fillColor
        if (alpha(o.fillColor2) > 0) {
            fill.shader = android.graphics.LinearGradient(0f, -h / 2f, 0f, h / 2f, o.fillColor, o.fillColor2, android.graphics.Shader.TileMode.CLAMP)
            fill.color = 0xFF000000.toInt()
        }
        c.drawPath(path, fill)
        fill.shader = null
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
            drawNoto(c, o, bw, bh)
        } else if (o.isVector) {
            val def = catalog[o.source.removePrefix("builtin:")] ?: return
            val s = min(bw / def.width, bh / def.height)
            c.save()
            c.scale(s, s)
            c.translate(-def.width / 2f, -def.height / 2f)
            val paths = stickerPaths.getOrPut(def.id) { def.layers.map { toPath(it.path, it.evenOdd) } }
            val vs = o.vectorStyle
            def.layers.forEachIndexed { i, layer ->
                def.resolve(layer.fill, o.colors)?.let { fill.color = if (vs != null && alpha(vs.fill) > 0) vs.fill else it; c.drawPath(paths[i], fill) }
                def.resolve(layer.stroke, o.colors)?.let {
                    val width = if (vs != null && vs.strokeWidth > 0f) vs.strokeWidth * max(def.width, def.height) else layer.strokeWidth * o.outlineScale
                    if (width > 0f) {
                        stroke.color = if (vs != null && alpha(vs.stroke) > 0) vs.stroke else it; stroke.strokeWidth = width
                        if (vs != null) stroke.strokeCap = if (vs.roundCap) Paint.Cap.ROUND else Paint.Cap.SQUARE
                        c.drawPath(paths[i], stroke)
                        stroke.strokeCap = Paint.Cap.ROUND
                    }
                }
            }
            c.restore()
        } else if (o.source.endsWith(".svg", ignoreCase = true)) {
            drawSvgAsset(c, o, bw, bh)
        } else {
            val live = liveBitmaps[o.id]
            val tl = if (live != null) liveTiles[o.id] else null
            if (live != null && tl != null && !tl.isEmpty) {
                // Cut Out in progress: the changed tiles replace the picture there (erased pixels
                // must show what is underneath at once, not the old opaque ones through them)
                c.save(); c.translate(-bw / 2, -bh / 2); tl.drawOver(c, live, bw, bh, bmpPaint); c.restore()
            } else {
                val bmp = live ?: pictureBitmap(o) ?: return
                c.drawBitmap(bmp, null, RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), bmpPaint)
            }
        }
    }

    /**
     * While Insert Picture's Cut Out is being edited, the editor supplies the processed picture
     * (crop + mask incl. the stroke in progress) computed with [processPicture] — the same code
     * the export uses — so the live view and the result are the same pixels.
     */
    val liveBitmaps = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    val liveTiles = java.util.concurrent.ConcurrentHashMap<String, LiveTiles>()
    private val processed = LruCache<String, Bitmap>(12)

    /** Raster picture with its crop and Cut Out mask applied (cached). */
    private fun pictureBitmap(o: StickerObject): Bitmap? {
        val raw = bitmaps.getOrPut(o.source) {
            assets[o.source.removePrefix("asset:")]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } ?: return null
        if (!o.hasCrop && o.cutout.isEmpty()) return raw
        val key = o.source + "|" + o.crop.hashCode() + "|" + o.cutout.hashCode()
        processed.get(key)?.let { return it }
        return runCatching { processPicture(raw, o.crop, o.cutout) }.getOrNull()?.also { processed.put(key, it) }
    }

    /**
     * Noto Emoji. On hardware-accelerated (screen) canvases the SVG is rasterized into a cached
     * software bitmap first — AndroidSVG is only drawn directly on software canvases (export,
     * thumbnails). Any failure draws nothing instead of crashing the app.
     */
    private fun drawNoto(c: Canvas, o: StickerObject, bw: Float, bh: Float) {
        val id = o.source.removePrefix("noto:")
        val dst = RectF(-bw / 2, -bh / 2, bw / 2, bh / 2)
        try {
            if (!c.isHardwareAccelerated) {
                val svg = notoSvgs.get(id) ?: noto.load(id)?.also { notoSvgs.put(id, it) } ?: return
                svg.renderToCanvas(c, dst)
                return
            }
            // resolution of the on-screen box (object scale included), in 64 px steps, capped
            val t = o.transform
            val longest = max(bw, bh) * abs(t.scale) * max(abs(t.stretchX), abs(t.stretchY))
            val px = (ceil(longest / 64f) * 64f).toInt().coerceIn(64, 1024)
            val key = "$id@$px"
            val bmp = notoBitmaps.get(key) ?: run {
                val svg = noto.load(id) ?: return
                val w = if (bw >= bh) px else max(1, (px * bw / bh).toInt())
                val h = if (bh >= bw) px else max(1, (px * bh / bw).toInt())
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    svg.renderToCanvas(Canvas(b), RectF(0f, 0f, w.toFloat(), h.toFloat()))
                    notoBitmaps.put(key, b)
                }
            }
            c.drawBitmap(bmp, null, dst, bmpPaint)
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) notoBitmaps.evictAll()
            android.util.Log.w("ObjectRenderer", "Cannot draw emoji $id", e)
        }
    }

    /** SVG sticker stored in the project (external library): vector on export, cached raster on screen. */
    private fun drawSvgAsset(c: Canvas, o: StickerObject, bw: Float, bh: Float) {
        val path = o.source.removePrefix("asset:")
        val dst = RectF(-bw / 2, -bh / 2, bw / 2, bh / 2)
        try {
            // with a Shape/Line Style, inline style="fill:…; stroke:…" declarations would win over
            // the override, so a copy without them is parsed (the file itself is not changed)
            val vs = o.vectorStyle
            val svgKey = if (vs != null) "$path#restyle" else path
            val svg = svgAssets.get(svgKey) ?: assets[path]?.let {
                val text = it.toString(Charsets.UTF_8)
                com.caverock.androidsvg.SVG.getFromString(if (vs != null) stripInlinePaint(text) else text)
            }?.also { svgAssets.put(svgKey, it) } ?: return
            val options = svgOptions(svg, vs)
            if (!c.isHardwareAccelerated) {
                if (options == null) svg.renderToCanvas(c, dst) else svg.renderToCanvas(c, options.viewPort(dst.left, dst.top, dst.width(), dst.height()))
                return
            }
            val t = o.transform
            val longest = max(bw, bh) * abs(t.scale) * max(abs(t.stretchX), abs(t.stretchY))
            val px = (ceil(longest / 64f) * 64f).toInt().coerceIn(64, 1536)
            val key = "$path@$px#${o.vectorStyle?.hashCode() ?: 0}"
            val bmp = notoBitmaps.get(key) ?: run {
                val w = if (bw >= bh) px else max(1, (px * bw / bh).toInt())
                val h = if (bh >= bw) px else max(1, (px * bh / bw).toInt())
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    if (options == null) svg.renderToCanvas(Canvas(b), RectF(0f, 0f, w.toFloat(), h.toFloat()))
                    else svg.renderToCanvas(Canvas(b), options.viewPort(0f, 0f, w.toFloat(), h.toFloat()))
                    notoBitmaps.put(key, b)
                }
            }
            c.drawBitmap(bmp, null, dst, bmpPaint)
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) notoBitmaps.evictAll()
            android.util.Log.w("ObjectRenderer", "Cannot draw SVG sticker $path", e)
        }
    }

    private val svgAssets = LruCache<String, com.caverock.androidsvg.SVG>(64)

    /**
     * Shape / line style of an SVG sticker as a CSS override applied while rendering (the file is
     * not modified). Width is a fraction of the sticker's longer side, in viewBox units.
     */
    /** Removes paint properties from inline style attributes (only those a Shape/Line Style sets). */
    internal fun stripInlinePaint(svg: String): String {
        val props = Regex("""(?i)\b(fill|fill-opacity|stroke|stroke-opacity|stroke-width|stroke-linecap|stroke-linejoin)\s*:[^;"']*;?""")
        return Regex("""style\s*=\s*(["'])(.*?)\1""", RegexOption.DOT_MATCHES_ALL).replace(svg) { m ->
            val q = m.groupValues[1]
            "style=" + q + props.replace(m.groupValues[2], "").trim() + q
        }
    }

    private fun svgOptions(svg: com.caverock.androidsvg.SVG, vs: app.bocadillo.core.model.VectorStyle?): com.caverock.androidsvg.RenderOptions? {
        if (vs == null) return null
        fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
        val rules = ArrayList<String>()
        if (alpha(vs.fill) > 0) rules += "fill:${hex(vs.fill)};fill-opacity:${"%.3f".format(java.util.Locale.US, alpha(vs.fill) / 255f)}"
        if (alpha(vs.stroke) > 0) rules += "stroke:${hex(vs.stroke)};stroke-opacity:${"%.3f".format(java.util.Locale.US, alpha(vs.stroke) / 255f)}"
        if (vs.strokeWidth > 0f) {
            val box = svg.documentViewBox
            val side = if (box != null) max(box.width(), box.height()) else max(svg.documentWidth, svg.documentHeight).coerceAtLeast(1f)
            rules += "stroke-width:${"%.3f".format(java.util.Locale.US, vs.strokeWidth * side)}"
            rules += "stroke-linecap:${if (vs.roundCap) "round" else "square"};stroke-linejoin:${if (vs.roundCap) "round" else "miter"}"
        }
        if (rules.isEmpty()) return null
        return com.caverock.androidsvg.RenderOptions().css("path,rect,circle,ellipse,polygon,polyline,line{" + rules.joinToString(";") + "}")
    }

    private val notoBitmaps = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
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

/** Crop, scale to the Cut Out raster size and apply the mask (see core Cutout). */
fun processPicture(raw: Bitmap, crop: List<Float>, cutout: List<Float>, extra: app.bocadillo.core.imaging.paint.Stroke? = null): Bitmap {
    val r = app.bocadillo.core.imaging.objects.Cutout.cropRect(crop, raw.width, raw.height)
    val cropped = if (r[0] == 0 && r[1] == 0 && r[2] == raw.width && r[3] == raw.height) raw else Bitmap.createBitmap(raw, r[0], r[1], r[2], r[3])
    val strokes = app.bocadillo.core.imaging.objects.Cutout.strokes(cutout) + listOfNotNull(extra)
    if (strokes.isEmpty()) return cropped
    val (w, h) = app.bocadillo.core.imaging.objects.Cutout.rasterSize(cropped.width, cropped.height)
    val scaled = if (w == cropped.width && h == cropped.height) cropped else Bitmap.createScaledBitmap(cropped, w, h, true)
    val px = IntArray(w * h); scaled.getPixels(px, 0, w, 0, 0, w, h)
    val mask = app.bocadillo.core.imaging.objects.Cutout.mask(strokes, w, h)
    val out = app.bocadillo.core.imaging.objects.Cutout.apply(app.bocadillo.core.imaging.PixelBuffer(w, h, px), mask)
    return Bitmap.createBitmap(out.pixels, w, h, Bitmap.Config.ARGB_8888)
}
