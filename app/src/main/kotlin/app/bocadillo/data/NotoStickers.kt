package app.bocadillo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Noto Emoji sticker pack (Apache 2.0, see assets/noto/NOTICE.txt): 1 757 vector emoji in the
 * 9 Unicode groups. Sticker objects reference them as "noto:<id>".
 */
class NotoStickers(private val context: Context) {

    class Item(val id: String, val keywords: String)
    class Group(val id: String, val names: Map<String, String>, val items: List<Item>) {
        fun name(lang: String) = names[lang] ?: names["en"] ?: id
    }

    val groups: List<Group> by lazy {
        val root = Json.parseToJsonElement(context.assets.open("noto/index.json").bufferedReader().use { it.readText() }).jsonObject
        root["groups"]!!.jsonArray.map { g ->
            val o = g.jsonObject
            Group(
                o["id"]!!.jsonPrimitive.content,
                o["names"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                o["items"]!!.jsonArray.map { i -> Item(i.jsonObject["id"]!!.jsonPrimitive.content, i.jsonObject["k"]?.jsonPrimitive?.content ?: "") },
            )
        }
    }

    /** Search by English shortcode words (e.g. "cat", "heart", "flag"). */
    fun search(query: String, limit: Int = 300): List<Item> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return groups.asSequence().flatMap { it.items }.distinctBy { it.id }.filter { it.keywords.contains(q) }.take(limit).toList()
    }

    /** Parses the SVG (callers cache it; SVG objects are not shared between threads). */
    fun load(id: String): SVG? = runCatching {
        context.assets.open("noto/svg/${id.replace(Regex("[^0-9a-f_]"), "")}.svg").use { SVG.getFromInputStream(it) }
    }.getOrNull()

    /** Width / height of the emoji artwork (1 for almost all). */
    fun aspect(id: String): Float {
        val r = load(id)?.documentAspectRatio ?: -1f
        return if (r > 0f) r else 1f
    }

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Small preview bitmap for the picker grid (call off the main thread). */
    fun thumbnail(id: String, px: Int): Bitmap? {
        val key = "$id@$px"
        thumbs.get(key)?.let { return it }
        val svg = load(id) ?: return null
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp), RectF(0f, 0f, px.toFloat(), px.toFloat()))
        thumbs.put(key, bmp)
        return bmp
    }
}
