package app.bocadillo.data

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.security.MessageDigest

/** A font the user can pick. [ref] is stored in [app.bocadillo.core.model.TextStyle.font]. */
data class FontOption(val ref: String, val displayName: String, val kind: Kind) {
    enum class Kind { SYSTEM, BUNDLED, IMPORTED }
}

/**
 * Resolves font references to Typefaces:
 *  - "system:<family>"      Android system families (always available, no file)
 *  - "bundled:<file>"       OFL fonts shipped in assets/fonts
 *  - "asset:fonts/<file>"   fonts the user imported; stored in the app font library and copied
 *                           into each project that uses them, so projects stay portable.
 */
class FontRepository(private val context: Context) {

    private val library = File(context.filesDir, "fonts").apply { mkdirs() }
    private val projectCache = File(context.cacheDir, "project_fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()

    val systemFonts = listOf(
        FontOption("system:sans-serif-black", "Sans Black", FontOption.Kind.SYSTEM),
        FontOption("system:sans-serif", "Sans", FontOption.Kind.SYSTEM),
        FontOption("system:sans-serif-medium", "Sans Medium", FontOption.Kind.SYSTEM),
        FontOption("system:sans-serif-light", "Sans Light", FontOption.Kind.SYSTEM),
        FontOption("system:sans-serif-condensed", "Sans Condensed", FontOption.Kind.SYSTEM),
        FontOption("system:sans-serif-smallcaps", "Small Caps", FontOption.Kind.SYSTEM),
        FontOption("system:serif", "Serif", FontOption.Kind.SYSTEM),
        FontOption("system:monospace", "Monospace", FontOption.Kind.SYSTEM),
        FontOption("system:serif-monospace", "Typewriter", FontOption.Kind.SYSTEM),
        FontOption("system:casual", "Casual", FontOption.Kind.SYSTEM),
        FontOption("system:cursive", "Cursive", FontOption.Kind.SYSTEM),
    )

    /**
     * Fonts shipped in the APK. Only fonts whose licence allows redistribution: SIL Open Font
     * License (Poppins, Lora, Bebas Neue, Comfortaa, Creato Display) and CC0 (Bradley Gratis).
     * Licence texts: assets/licenses (shown in Licencias y créditos).
     */
    val bundledFonts = listOf(
        FontOption("bundled:Poppins-Bold.ttf", "Poppins Bold", FontOption.Kind.BUNDLED),
        FontOption("bundled:Poppins-Regular.ttf", "Poppins", FontOption.Kind.BUNDLED),
        FontOption("bundled:Lora-Variable.ttf", "Lora", FontOption.Kind.BUNDLED),
        FontOption("bundled:BebasNeue-Regular.ttf", "Bebas Neue", FontOption.Kind.BUNDLED),
        FontOption("bundled:Comfortaa-Light.ttf", "Comfortaa Light", FontOption.Kind.BUNDLED),
        FontOption("bundled:Comfortaa-Regular.ttf", "Comfortaa", FontOption.Kind.BUNDLED),
        FontOption("bundled:Comfortaa-Bold.ttf", "Comfortaa Bold", FontOption.Kind.BUNDLED),
        FontOption("bundled:Bradley Gratis.ttf", "Bradley Gratis", FontOption.Kind.BUNDLED),
    ) + listOf("Thin", "ThinItalic", "Light", "LightItalic", "Regular", "RegularItalic", "Medium", "MediumItalic", "Bold", "BoldItalic",
        "ExtraBold", "ExtraBoldItalic", "Black", "BlackItalic").map { st ->
        FontOption("bundled:CreatoDisplay-$st.otf", "Creato Display " + st.replace("Italic", " Italic").replace("RegularItalic", "Italic").replace("Regular ", "").trim(), FontOption.Kind.BUNDLED)
    }

    /** Index of imported fonts: file → family, style, SHA-1 (duplicates), original file name. */
    data class Entry(val file: String, val family: String, val style: String, val sha1: String, val original: String) {
        val displayName: String get() = if (style.isBlank() || style.equals("Regular", true)) family else "$family $style"
    }

    private val indexFile = File(library, "index.json")

    @Synchronized
    fun entries(): List<Entry> {
        val known = runCatching {
            val arr = org.json.JSONArray(indexFile.readText())
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Entry(it.getString("file"), it.optString("family"), it.optString("style"), it.optString("sha1"), it.optString("original")) } }
        }.getOrDefault(emptyList()).filter { File(library, it.file).exists() }
        // fonts imported by 0.9 or earlier have no index entry: describe them now (nothing is renamed)
        val files = (library.listFiles() ?: emptyArray()).filter { it.isFile && isFontName(it.name) }
        val missing = files.filter { f -> known.none { it.file == f.name } }.map { f ->
            val bytes = f.readBytes()
            val meta = FontMeta.read(bytes)
            Entry(f.name, meta?.family ?: f.nameWithoutExtension, meta?.style ?: "", sha1(bytes), f.name)
        }
        val all = known + missing
        if (missing.isNotEmpty()) saveIndex(all)
        return all
    }

    private fun saveIndex(list: List<Entry>) {
        val arr = org.json.JSONArray()
        for (e in list) arr.put(org.json.JSONObject().put("file", e.file).put("family", e.family).put("style", e.style).put("sha1", e.sha1).put("original", e.original))
        indexFile.writeText(arr.toString())
    }

    fun importedFonts(): List<FontOption> =
        entries().sortedWith(compareBy({ it.family.lowercase() }, { it.style.lowercase() }))
            .map { FontOption("asset:fonts/${it.file}", it.displayName, FontOption.Kind.IMPORTED) }

    /** Result of importing font files or a ZIP. */
    data class ImportReport(val added: List<String>, val duplicates: List<String>, val errors: List<Pair<String, String>>, val firstRef: String?)

    /**
     * Imports .ttf / .otf files and .zip archives of them. Each font is checked (sfnt header,
     * name table, Android can load it); identical files (SHA-1) are reported as duplicates and
     * not copied twice. Family and style come from the font's own name table.
     */
    @Synchronized
    fun importAll(items: List<Pair<String, ByteArray>>): ImportReport {
        val list = entries().toMutableList()
        val added = ArrayList<String>(); val dups = ArrayList<String>(); val errors = ArrayList<Pair<String, String>>()
        var first: String? = null
        fun one(name: String, bytes: ByteArray) {
            val base = name.substringAfterLast('/')
            if (!isFontName(base)) { errors += base to "not .ttf/.otf"; return }
            if (bytes.size < 100) { errors += base to "empty or truncated"; return }
            if (bytes.size > 25 * 1024 * 1024) { errors += base to "larger than 25 MB"; return }
            val tag = String(bytes, 0, 4, Charsets.ISO_8859_1)
            if (tag != "OTTO" && tag != "true" && !(bytes[0].toInt() == 0 && bytes[1].toInt() == 1 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0)) { errors += base to "not a TrueType/OpenType font"; return }
            val hash = sha1(bytes)
            list.firstOrNull { it.sha1 == hash }?.let { dups += base; if (first == null) first = "asset:fonts/${it.file}"; return }
            val meta = FontMeta.read(bytes)
            val ext = if (base.endsWith(".otf", true)) "otf" else "ttf"
            var stem = base.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.()-]"), "_").trim().ifEmpty { "font" }.take(60)
            var file = File(library, "$stem.$ext")
            var k = 2
            while (file.exists()) { file = File(library, "$stem ($k).$ext"); k++ }
            file.writeBytes(bytes)
            val tf = runCatching { Typeface.createFromFile(file) }.getOrNull()
            if (tf == null) { file.delete(); errors += base to "Android cannot load this font"; return }
            val e = Entry(file.name, meta?.family?.ifBlank { null } ?: stem, meta?.style ?: "", hash, base)
            list += e; added += e.displayName
            cache.remove("asset:fonts/${file.name}")
            if (first == null) first = "asset:fonts/${file.name}"
        }
        for ((name, bytes) in items) {
            if (name.endsWith(".zip", true)) {
                runCatching {
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                        while (true) {
                            val en = z.nextEntry ?: break
                            val n = en.name
                            if (en.isDirectory || n.contains("__MACOSX") || n.substringAfterLast('/').startsWith(".")) continue
                            if (n.contains("..")) { errors += n to "unsafe path"; continue }
                            if (!isFontName(n)) continue
                            one(n, z.readBytes())
                        }
                    }
                }.onFailure { errors += name to "ZIP could not be read" }
            } else one(name, bytes)
        }
        saveIndex(list)
        return ImportReport(added, dups, errors, first)
    }

    /** Removes an imported font from the library (projects keep their embedded copy). */
    @Synchronized
    fun delete(ref: String) {
        val name = ref.removePrefix("asset:fonts/")
        File(library, name).delete()
        cache.remove(ref)
        saveIndex(entries().filter { it.file != name })
    }

    /** False when a text uses a font that can be found neither here nor in the project. */
    fun isAvailable(ref: String, projectAssets: Map<String, ByteArray>): Boolean = when {
        ref.startsWith("system:") || ref.isBlank() -> true
        ref.startsWith("bundled:") -> runCatching { context.assets.open("fonts/" + ref.removePrefix("bundled:")).close() }.isSuccess
        ref.startsWith("asset:") -> File(library, ref.removePrefix("asset:fonts/")).exists() || projectAssets.containsKey(ref.removePrefix("asset:"))
        else -> false
    }

    private fun isFontName(n: String) = n.endsWith(".ttf", true) || n.endsWith(".otf", true)

    fun all(): List<FontOption> = systemFonts + bundledFonts + importedFonts()

    /**
     * Adds a TTF/OTF to the library. Validates it by loading it; returns its reference.
     * @throws IllegalArgumentException if Android cannot load the font.
     */
    fun import(displayName: String, bytes: ByteArray): String {
        val r = importAll(listOf(displayName to bytes))
        return r.firstRef ?: throw IllegalArgumentException(r.errors.firstOrNull()?.second ?: "Invalid font")
    }

    /** Bytes of a library font, to embed in the project archive. */
    fun libraryBytes(ref: String): ByteArray? {
        if (!ref.startsWith("asset:fonts/")) return null
        val f = File(library, ref.removePrefix("asset:fonts/"))
        return if (f.exists()) f.readBytes() else null
    }

    /**
     * Typeface for [ref]. [projectAssets] supplies fonts embedded in the open project (used when
     * a project comes from another device and the font is not in this device's library).
     */
    @Synchronized
    fun typeface(ref: String, bold: Boolean, italic: Boolean, projectAssets: Map<String, ByteArray>): Typeface {
        val base = cache.getOrPut(ref) { load(ref, projectAssets) }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }

    private fun load(ref: String, projectAssets: Map<String, ByteArray>): Typeface = runCatching {
        when {
            ref.startsWith("system:") -> Typeface.create(ref.removePrefix("system:"), Typeface.NORMAL)
            ref.startsWith("bundled:") -> Typeface.createFromAsset(context.assets, "fonts/" + ref.removePrefix("bundled:"))
            ref.startsWith("asset:") -> {
                val name = ref.removePrefix("asset:")
                val lib = File(library, name.removePrefix("fonts/"))
                if (lib.exists()) Typeface.createFromFile(lib) else {
                    val bytes = projectAssets[name] ?: return@runCatching Typeface.DEFAULT
                    val f = File(projectCache, sha1(bytes) + "_" + lib.name)
                    if (!f.exists()) f.writeBytes(bytes)
                    Typeface.createFromFile(f)
                }
            }
            else -> Typeface.DEFAULT
        }
    }.getOrDefault(Typeface.DEFAULT)

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)
}

/** Reads family / style names from a TrueType/OpenType 'name' table (no Android API needed). */
object FontMeta {
    data class Names(val family: String, val style: String)

    fun read(b: ByteArray): Names? = runCatching {
        fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        val numTables = u16(4)
        var nameOff = -1
        for (i in 0 until numTables) {
            val rec = 12 + 16 * i
            if (String(b, rec, 4, Charsets.ISO_8859_1) == "name") { nameOff = u32(rec + 8).toInt(); break }
        }
        if (nameOff < 0) return null
        val count = u16(nameOff + 2); val strings = nameOff + u16(nameOff + 4)
        val found = HashMap<Int, String>()
        for (i in 0 until count) {
            val r = nameOff + 6 + 12 * i
            val platform = u16(r); val encoding = u16(r + 2); val lang = u16(r + 4); val id = u16(r + 6); val len = u16(r + 8); val off = u16(r + 10)
            if (id !in listOf(1, 2, 16, 17)) continue
            val text = when {
                platform == 3 && (encoding == 1 || encoding == 0) -> String(b, strings + off, len, Charsets.UTF_16BE)
                platform == 0 -> String(b, strings + off, len, Charsets.UTF_16BE)
                platform == 1 && encoding == 0 -> String(b, strings + off, len, Charsets.ISO_8859_1)
                else -> continue
            }.trim()
            // prefer English (Windows 0x409) when several languages exist
            if (text.isNotEmpty() && (!found.containsKey(id) || (platform == 3 && lang == 0x409))) found[id] = text
        }
        val family = found[16] ?: found[1] ?: return null
        Names(family, found[17] ?: found[2] ?: "")
    }.getOrNull()
}
