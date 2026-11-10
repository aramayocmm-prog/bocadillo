package app.bocadillo.data

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.bocadillo.core.model.LibrarySticker
import app.bocadillo.core.model.ManifestEntry
import app.bocadillo.core.model.StickerFormat
import app.bocadillo.core.model.StickerImporter
import app.bocadillo.core.model.StickerLibraryIndex
import app.bocadillo.core.model.StickerManifest
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * External sticker library installed by the user (Stage 2): pictures live in
 * `files/sticker_packs/files/<sha256>.<ext>` and the catalogue in `index.json`. Importing a ZIP
 * merges into what is installed (nothing is lost), detects duplicates and reports errors.
 * Works offline; nothing is downloaded.
 */
class StickerPacks(private val context: Context) {
    private val root = File(context.filesDir, "sticker_packs").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val lock = Mutex()

    /** Observable for Compose: changes after every import or deletion. */
    var index by mutableStateOf(load()); private set

    private fun load(): StickerLibraryIndex = runCatching { StickerLibraryIndex.fromJson(indexFile.readText()) }.getOrElse { StickerLibraryIndex() }

    private fun save(i: StickerLibraryIndex) {
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(i.toJson())
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
        index = i
    }

    fun file(s: LibrarySticker) = File(filesDir, s.file)

    /** Summary shown after an import. */
    data class Report(
        val source: String, val added: Int, val updated: Int, val unchanged: Int,
        val duplicates: List<String>, val errors: List<String>, val total: Int,
    )

    /**
     * Imports a ZIP chosen by the user. The catalogue is read from `manifest.json`, from a SQLite
     * catalogue (`*.sqlite` / `*.db` with a `stickers` table) or, if neither exists, from the
     * folder names (one folder per category).
     */
    suspend fun importZip(resolver: ContentResolver, uri: Uri): Report = withContext(Dispatchers.IO) {
        lock.withLock {
            val files = LinkedHashMap<String, ByteArray>()
            var total = 0L
            val errors = ArrayList<String>()
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        val name = e.name.replace('\\', '/')
                        if (StickerManifest.isJunk(name)) continue
                        val lower = name.lowercase()
                        val wanted = StickerFormat.fromExtension(lower) != null || lower.endsWith(".json") || lower.endsWith(".sqlite") || lower.endsWith(".db")
                        if (!wanted) continue
                        val bytes = zip.readLimited(StickerImporter.MAX_BYTES * 8L)
                        if (bytes == null) { errors += "$name: too large"; continue }
                        total += bytes.size
                        if (total > 1_500_000_000L) throw IllegalStateException("ZIP larger than 1.5 GB")
                        files[name] = bytes
                    }
                }
            } ?: throw IllegalStateException("Cannot open the file")

            // catalogue: manifest.json (any folder level) › SQLite › folders
            val manifestPath = files.keys.filter { it.substringAfterLast('/').equals(StickerManifest.FILE_NAME, true) }.minByOrNull { it.count { c -> c == '/' } }
            val sqlitePath = files.keys.firstOrNull { it.lowercase().endsWith(".sqlite") || it.lowercase().endsWith(".db") }
            // an empty manifest/catalogue (as in the template) falls through to the next option
            val fromManifest = manifestPath?.let { StickerManifest.parse(files.getValue(it).toString(Charsets.UTF_8)) }.orEmpty()
            val fromSqlite = if (fromManifest.isEmpty() && sqlitePath != null) readSqlite(files.getValue(sqlitePath)) else emptyList()
            val (source, entries, base) = when {
                fromManifest.isNotEmpty() -> Triple("manifest.json", fromManifest, manifestPath!!.substringBeforeLast('/', ""))
                fromSqlite.isNotEmpty() -> Triple(sqlitePath!!.substringAfterLast('/'), fromSqlite, sqlitePath.substringBeforeLast('/', ""))
                else -> Triple("folders", StickerManifest.fromFolders(files.keys), "")
            }
            // paths in a manifest are relative to the manifest's folder
            val rel = if (base.isEmpty()) files else files.filterKeys { it.startsWith("$base/") }.mapKeys { it.key.removePrefix("$base/") }
            val plan = StickerImporter.plan(index, entries, rel, System.currentTimeMillis(), ::validate)
            for ((name, bytes) in plan.newFiles) File(filesDir, name).writeBytes(bytes)
            save(plan.index)
            thumbs.evictAll()
            Report(source, plan.added.size, plan.updated.size, plan.unchanged, plan.duplicates, errors + plan.errors, plan.index.stickers.size)
        }
    }

    /** The picture must really decode (not only have the right header). */
    private fun validate(bytes: ByteArray, fmt: StickerFormat): String? = try {
        when (fmt) {
            StickerFormat.SVG -> {
                val svg = SVG.getFromString(bytes.toString(Charsets.UTF_8))
                if (svg.documentViewBox == null && svg.documentWidth <= 0f) "SVG without size or viewBox" else null
            }
            else -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) "picture cannot be decoded" else null
            }
        }
    } catch (e: Exception) { "invalid ${fmt.ext.uppercase()} (${e.javaClass.simpleName})" }

    /**
     * SQLite catalogue. Accepted columns in table `stickers`: id, name, file | asset_path |
     * relative_path, category | category_id, position | display_order. When `category_id` is a
     * number and a `categories` table exists, its name is used.
     */
    private fun readSqlite(bytes: ByteArray): List<ManifestEntry> {
        val tmp = File.createTempFile("catalog", ".sqlite", context.cacheDir)
        try {
            tmp.writeBytes(bytes)
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun columns(t: String) = db.rawQuery("PRAGMA table_info($t)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
                val cols = columns("stickers")
                if (cols.isEmpty()) throw IllegalArgumentException("the SQLite file has no \"stickers\" table")
                val catNames = HashMap<String, String>()
                if (columns("categories").containsAll(listOf("id", "name"))) {
                    db.rawQuery("SELECT id, name FROM categories", null).use { c -> while (c.moveToNext()) catNames[c.getString(0)] = c.getString(1) }
                }
                fun pick(vararg n: String) = n.firstOrNull { it in cols }
                val fileCol = pick("file", "asset_path", "relative_path", "path") ?: throw IllegalArgumentException("the \"stickers\" table has no file column")
                val catCol = pick("category", "category_id") ?: throw IllegalArgumentException("the \"stickers\" table has no category column")
                val idCol = pick("id"); val nameCol = pick("name"); val posCol = pick("position", "display_order")
                val q = "SELECT ${listOfNotNull(fileCol, catCol, idCol, nameCol, posCol).joinToString()} FROM stickers"
                return db.rawQuery(q, null).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val file = c.getString(0) ?: continue
                            var i = 2
                            val id = idCol?.let { c.getString(i++) }
                            val name = nameCol?.let { c.getString(i++) }
                            val pos = posCol?.let { if (c.isNull(i)) null else c.getInt(i) }
                            val cat = c.getString(1)?.let { catNames[it] ?: it }
                            add(ManifestEntry(id, cat, name, pos, file))
                        }
                    }
                }
            }
        } finally { tmp.delete() }
    }

    /** Removes one sticker (its file too, when no other entry uses it). */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = index.byId(id) ?: return@withLock
            val rest = index.stickers.filter { it.id != id }
            if (rest.none { it.file == s.file }) File(filesDir, s.file).delete()
            save(StickerLibraryIndex(rest))
        }
    }

    /** Empties one category, or the whole library when [categoryId] is null. */
    suspend fun clear(categoryId: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val rest = if (categoryId == null) emptyList() else index.stickers.filter { it.category != categoryId }
            val keep = rest.map { it.file }.toSet()
            filesDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            save(StickerLibraryIndex(rest))
        }
    }

    /** Width / height of the picture. */
    fun aspect(s: LibrarySticker): Float = runCatching {
        val f = file(s)
        if (s.format == StickerFormat.SVG) {
            val svg = SVG.getFromInputStream(f.inputStream().buffered())
            val r = svg.documentAspectRatio
            if (r > 0f) r else svg.documentViewBox?.let { it.width() / it.height() } ?: 1f
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1f
        }
    }.getOrDefault(1f)

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Preview for the grids (call off the main thread). Transparency is kept. */
    fun thumbnail(s: LibrarySticker, px: Int): Bitmap? {
        val key = "${s.file}@$px"
        thumbs.get(key)?.let { return it }
        val bmp = runCatching { renderToBitmap(file(s).readBytes(), s.format, px) }.getOrNull() ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    companion object {
        /** Decodes a PNG/WebP (subsampled) or rasterizes an SVG so its longest side is about [px]. */
        fun renderToBitmap(bytes: ByteArray, fmt: StickerFormat, px: Int): Bitmap? {
            if (fmt == StickerFormat.SVG) {
                val svg = SVG.getFromString(bytes.toString(Charsets.UTF_8))
                val ar = svg.documentAspectRatio.takeIf { it > 0f } ?: svg.documentViewBox?.let { it.width() / it.height() } ?: 1f
                val w = if (ar >= 1f) px else max(1, (px * ar).toInt()); val h = if (ar >= 1f) max(1, (px / ar).toInt()) else px
                return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it), RectF(0f, 0f, w.toFloat(), h.toFloat())) }
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/** Reads the current ZIP entry, or null if it is bigger than [limit]. */
private fun ZipInputStream.readLimited(limit: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var n: Int; var total = 0L
    while (read(buf).also { n = it } > 0) {
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

