package app.bocadillo.editor

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.preview.SampleScene
import app.bocadillo.core.imaging.preview.ToolPreviews
import app.bocadillo.core.model.ObjectTransform
import app.bocadillo.core.model.TextObject
import app.bocadillo.core.model.TextStyle
import app.bocadillo.render.ObjectRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures for the tool menus: a fixed sample landscape with each tool applied. Computed once
 * per app run in the background and kept in memory (≈ 77 KB each, about 70 tiles).
 */
object ToolTileCache {
    val sample: PixelBuffer by lazy { SampleScene.render(160, 120) }
    private val cache = LruCache<String, ImageBitmap>(160)

    /**
     * The menus of the original show each tool applied to the photo being edited. When a photo
     * is set, menu thumbnails ([photoKey]) use a small portrait crop of it instead of the sample.
     */
    @Volatile private var photo: PixelBuffer? = null
    @Volatile var photoVersion = 0; private set

    fun setPhoto(src: PixelBuffer?) {
        if (src == null || src.width < 2 || src.height < 2) { photo = null; photoVersion++; return }
        // centre crop with the 26:40 proportion of the menu thumbnails, 64×98 px
        val tw = 64; val th = 98
        val dstRatio = tw.toFloat() / th
        val (cw, ch) = if (src.width.toFloat() / src.height > dstRatio) (src.height * dstRatio).toInt().coerceAtLeast(1) to src.height
            else src.width to (src.width / dstRatio).toInt().coerceAtLeast(1)
        val ox = (src.width - cw) / 2; val oy = (src.height - ch) / 2
        val crop = PixelBuffer(cw, ch, IntArray(cw * ch) { i -> src.pixels[(oy + i / cw) * src.width + ox + i % cw] })
        photo = app.bocadillo.core.imaging.Resample.resize(crop, tw, th)
        photoVersion++
    }

    /** Key of a thumbnail computed on the current photo. */
    fun photoKey(key: String) = "p$photoVersion:$key"

    suspend fun get(key: String, compute: (PixelBuffer) -> PixelBuffer): ImageBitmap? {
        cache.get(key)?.let { return it }
        val source = if (key.startsWith("p")) photo ?: sample else sample
        val bmp = withContext(Dispatchers.Default) {
            runCatching {
                val p = compute(source)
                Bitmap.createBitmap(p.pixels, p.width, p.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)
}

private val TILE_W = 80.dp
private val PIC_W = 72.dp
private val PIC_H = 54.dp

/** Tile = picture + one or two lines of label, same look as the balloon shapes. */
@Composable
fun ToolTile(
    label: String,
    previewKey: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    badge: ImageVector? = null,
    compute: (PixelBuffer) -> PixelBuffer,
) {
    val bmp by produceState(ToolTileCache.cached(previewKey), previewKey) {
        if (value == null) value = ToolTileCache.get(previewKey, compute)
    }
    TileFrame(label, selected, onClick) {
        Checker()
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (badge != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).background(Color(0x99000000), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(badge, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun OpTile(op: ImageOp, label: String, onClick: () -> Unit) =
    ToolTile(label, "op:${op.id}", onClick) { ToolPreviews.forOp(op, it) }

@Composable
fun DrawTile(demo: ToolPreviews.DrawDemo, label: String, onClick: () -> Unit, badge: ImageVector? = null) =
    ToolTile(label, "draw:${demo.name}", onClick, badge = badge) { ToolPreviews.forDraw(demo, it) }

/** A text style shown as real rendered text (font, outline, shadow, background) on the sample. */
@Composable
fun TextStyleTile(style: TextStyle, label: String, renderer: ObjectRenderer, onClick: () -> Unit, selected: Boolean = false) {
    val bmp by produceState(ToolTileCache.cached("sample"), Unit) {
        if (value == null) value = ToolTileCache.get("sample") { it }
    }
    val obj = TextObject("tile", ObjectTransform(0.5f, 0.5f), "Aa", style.copy(size = 0.42f))
    TileFrame(label, selected, onClick) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = 0.55f) }
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { runCatching { renderer.draw(it.nativeCanvas, obj, size.width, size.height) } }
        }
    }
}

@Composable
private fun TileFrame(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.width(TILE_W).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(PIC_W, PIC_H).clip(RoundedCornerShape(6.dp))) { content() }
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun Checker() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 6.dp.toPx()
        drawRect(Color(0xFFDDDDDD))
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < size.width) { drawRect(Color(0xFFBBBBBB), Offset(x, y), Size(cell, cell)); x += 2 * cell }
            y += cell; row++
        }
    }
}
