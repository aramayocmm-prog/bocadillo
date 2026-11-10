package app.bocadillo.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * External sticker library: the app ships the 32 classic sticker categories empty and the user
 * imports the pictures later (ZIP with a manifest, a SQLite catalogue or plain folders), without
 * recompiling. Pure Kotlin so the rules can be unit-tested; the Android side only does I/O.
 */
object StickerCategories {
    /** Classic category list: stable id, English name, Spanish name. Order = menu order. */
    val all: List<Category> = listOf(
        Category("hats_helmets", "Hats & Helmets", "Sombreros y cascos"),
        Category("hairstyles", "Hairstyles", "Peinados"),
        Category("eyes", "Eyes", "Ojos"),
        Category("masks_glasses", "Masks & Glasses", "Antifaces y gafas"),
        Category("beards_moustaches", "Beards & Moustaches", "Barbas y bigotes"),
        Category("ears_nose_mouth", "Ears, Nose & Mouth", "Orejas, nariz y boca"),
        Category("masks_pointers", "Masks & Pointers", "Máscaras y señaladores"),
        Category("emoticons", "Emoticons", "Emoticonos"),
        Category("celebrations", "Celebrations", "Celebraciones"),
        Category("hands", "Hands", "Manos"),
        Category("nature", "Nature", "Naturaleza"),
        Category("animals", "Animals", "Animales"),
        Category("prints_stamps", "Prints & Stamps", "Huellas y sellos"),
        Category("romance", "Romance", "Romance"),
        Category("signs_symbols", "Signs & Symbols", "Señales y símbolos"),
        Category("comic", "Comic", "Cómic"),
        Category("food_drink", "Food & Drink", "Comida y bebida"),
        Category("accessories", "Accessories", "Accesorios"),
        Category("sport", "Sport", "Deporte"),
        Category("basic_shapes", "Basic Shapes", "Formas básicas"),
        Category("summer", "Summer", "Verano"),
        Category("halloween", "Halloween", "Halloween"),
        Category("thanksgiving", "Thanksgiving", "Acción de Gracias"),
        Category("christmas_holiday", "Christmas Holiday", "Navidad"),
        Category("happy_new_year", "Happy New Year", "Feliz Año Nuevo"),
        Category("easter_spring", "Easter & Spring", "Pascua y primavera"),
        Category("st_patricks_day", "St. Patrick's Day", "Día de San Patricio"),
        Category("independence_day", "Independence Day", "Día de la Independencia"),
        Category("travel_places", "Travel & Places", "Viajes y lugares"),
        Category("objects", "Objects", "Objetos"),
        Category("people", "People", "Personas"),
        Category("activity", "Activity", "Actividad"),
    )

    data class Category(val id: String, val nameEn: String, val nameEs: String) {
        fun name(language: String) = if (language == "es") nameEs else nameEn
        val position get() = all.indexOf(this) + 1
    }

    private fun norm(s: String) = s.lowercase()
        .replace('’', '\'').replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "")

    /**
     * Resolves a category reference: id ("hats_helmets"), menu position ("1"), English or Spanish
     * name ("Hats & Helmets"), or a folder name with a numeric prefix ("01_hats_helmets").
     */
    fun resolve(ref: String?): Category? {
        if (ref.isNullOrBlank()) return null
        val r = ref.trim()
        r.toIntOrNull()?.let { return all.getOrNull(it - 1) }
        all.firstOrNull { it.id == r }?.let { return it }
        val n = norm(r)
        all.firstOrNull { norm(it.id) == n || norm(it.nameEn) == n || norm(it.nameEs) == n }?.let { return it }
        // "01_hats_helmets", "01 - Hats & Helmets"
        val m = Regex("^(\\d{1,2})[ _.-]+(.+)$").find(r)
        if (m != null) {
            val byName = resolve(m.groupValues[2])
            if (byName != null) return byName
            return all.getOrNull(m.groupValues[1].toInt() - 1)
        }
        return null
    }
}

/** Picture formats accepted for stickers. */
enum class StickerFormat(val ext: String) { PNG("png"), WEBP("webp"), SVG("svg");
    companion object {
        fun fromExtension(name: String): StickerFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> PNG; "webp" -> WEBP; "svg" -> SVG; else -> null
        }

        /** Checks the content, not only the extension (magic bytes / SVG root element). */
        fun sniff(bytes: ByteArray): StickerFormat? {
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) return PNG
            if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return WEBP
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
            if (head.contains("<svg", ignoreCase = true)) return SVG
            return null
        }
    }
}

/** One installed sticker. [file] is the stored file name (content hash + extension). */
data class LibrarySticker(
    val id: String,
    val category: String,
    val name: String,
    val position: Int,
    val file: String,
    val sha256: String,
    val format: StickerFormat,
    val importedAt: Long,
)

/** The installed library (stored as index.json on the device). */
data class StickerLibraryIndex(val stickers: List<LibrarySticker> = emptyList()) {
    /** Stickers of a category in menu order (position, then name). */
    fun inCategory(categoryId: String): List<LibrarySticker> =
        stickers.filter { it.category == categoryId }.sortedWith(compareBy({ it.position }, { it.name.lowercase() }, { it.id }))

    fun byId(id: String) = stickers.firstOrNull { it.id == id }

    fun counts(): Map<String, Int> = stickers.groupingBy { it.category }.eachCount()

    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", "bocadillo-sticker-index"); put("version", 1)
        put("stickers", buildJsonArray {
            for (s in stickers) add(buildJsonObject {
                put("id", s.id); put("category", s.category); put("name", s.name); put("position", s.position)
                put("file", s.file); put("sha256", s.sha256); put("format", s.format.ext); put("importedAt", s.importedAt)
            })
        })
    })

    companion object {
        fun fromJson(text: String): StickerLibraryIndex {
            val o = Json.parseToJsonElement(text).jsonObject
            val list = o["stickers"]?.jsonArray?.mapNotNull { e ->
                val s = e.jsonObject
                fun str(k: String) = s[k]?.jsonPrimitive?.contentOrNull
                LibrarySticker(
                    id = str("id") ?: return@mapNotNull null,
                    category = str("category") ?: return@mapNotNull null,
                    name = str("name") ?: "",
                    position = s["position"]?.jsonPrimitive?.intOrNull ?: 0,
                    file = str("file") ?: return@mapNotNull null,
                    sha256 = str("sha256") ?: "",
                    format = StickerFormat.fromExtension("x." + (str("format") ?: "png")) ?: StickerFormat.PNG,
                    importedAt = s["importedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            } ?: emptyList()
            return StickerLibraryIndex(list)
        }
    }
}

/** One sticker described by a manifest (or derived from folders), before import. */
data class ManifestEntry(val id: String?, val category: String?, val name: String?, val position: Int?, val file: String)

/** Manifest file inside a library ZIP. */
object StickerManifest {
    const val FILE_NAME = "manifest.json"
    const val FORMAT = "bocadillo-sticker-library"

    /**
     * Parses manifest.json. Accepted shape:
     * `{"format":"bocadillo-sticker-library","version":1,"stickers":[{"id","category","name","position","file"}]}`.
     * Also accepts `"categories":[{"category":…, "stickers":[…]}]` (category given once per group).
     */
    fun parse(text: String): List<ManifestEntry> {
        val root = Json.parseToJsonElement(text)
        val o = root as? JsonObject ?: throw IllegalArgumentException("manifest.json: the root must be an object")
        val out = ArrayList<ManifestEntry>()
        fun entry(e: JsonObject, cat: String?) {
            fun str(k: String) = (e[k] as? JsonPrimitive)?.contentOrNull
            val file = str("file") ?: str("path") ?: str("asset_path") ?: throw IllegalArgumentException("manifest.json: a sticker has no \"file\"")
            out += ManifestEntry(str("id"), str("category") ?: cat, str("name"), (e["position"] as? JsonPrimitive)?.intOrNull ?: (e["display_order"] as? JsonPrimitive)?.intOrNull, file)
        }
        (o["stickers"] as? JsonArray)?.forEach { entry(it.jsonObject, null) }
        (o["categories"] as? JsonArray)?.forEach { g ->
            val go = g.jsonObject
            val cat = (go["category"] as? JsonPrimitive)?.contentOrNull ?: (go["id"] as? JsonPrimitive)?.contentOrNull ?: (go["name"] as? JsonPrimitive)?.contentOrNull
            (go["stickers"] as? JsonArray)?.forEach { entry(it.jsonObject, cat) }
        }
        return out
    }

    /**
     * Without a manifest: each picture's first folder is its category ("01_hats_helmets/x.png",
     * "Hats & Helmets/x.png"); the order is the natural order of the file names.
     */
    fun fromFolders(paths: Collection<String>): List<ManifestEntry> =
        paths.filter { StickerFormat.fromExtension(it) != null && !isJunk(it) }
            .map { p ->
                val parts = p.split('/').filter { it.isNotEmpty() }
                // ignore a single wrapping folder ("MyLibrary/01_hats/x.png")
                val cat = parts.dropLast(1).lastOrNull { StickerCategories.resolve(it) != null }
                ManifestEntry(null, cat, null, null, p)
            }
            .groupBy { it.category }
            .flatMap { (_, list) -> list.sortedWith { a, b -> naturalCompare(a.file.substringAfterLast('/'), b.file.substringAfterLast('/')) }
                .mapIndexed { i, e -> e.copy(position = i + 1) } }

    fun isJunk(path: String) = path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith(".")

    /** "2.png" < "10.png". */
    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()).takeIf { it != 0 } ?: x.length.compareTo(y.length) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }
}

/** Result of planning an import: what will change and every problem found. */
data class StickerImportPlan(
    val index: StickerLibraryIndex,
    /** Files to store: stored name → bytes (only new content). */
    val newFiles: Map<String, ByteArray>,
    val added: List<LibrarySticker>,
    val updated: List<LibrarySticker>,
    val unchanged: Int,
    val duplicates: List<String>,
    val errors: List<String>,
)

object StickerImporter {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cleanPath(p: String) = p.replace('\\', '/').trimStart('/').let { if (it.startsWith("./")) it.drop(2) else it }

    /**
     * Merges [entries] (from a manifest or folders) whose pictures are in [files] (ZIP path →
     * bytes) into [current]. Existing stickers are never removed; a known id with new content
     * is updated; identical pictures are detected by SHA-256 and skipped.
     * [validate] lets the platform check that a picture really decodes (null = valid).
     */
    fun plan(
        current: StickerLibraryIndex,
        entries: List<ManifestEntry>,
        files: Map<String, ByteArray>,
        now: Long,
        validate: (ByteArray, StickerFormat) -> String? = { _, _ -> null },
    ): StickerImportPlan {
        val byPath = files.mapKeys { cleanPath(it.key) }
        val lowerPath = byPath.keys.associateBy { it.lowercase() }
        val result = current.stickers.associateBy { it.id }.toMutableMap()
        val knownHashes = current.stickers.associateBy { it.sha256 }.toMutableMap()
        val newFiles = LinkedHashMap<String, ByteArray>()
        val added = ArrayList<LibrarySticker>(); val updated = ArrayList<LibrarySticker>()
        val duplicates = ArrayList<String>(); val errors = ArrayList<String>()
        var unchanged = 0
        val seenIds = HashSet<String>()
        val nextPos = HashMap<String, Int>()
        for (e in entries) {
            val path = cleanPath(e.file)
            if (path.split('/').any { it == ".." }) { errors += "$path: invalid path"; continue }
            val bytes = byPath[path] ?: lowerPath[path.lowercase()]?.let { byPath[it] }
            if (bytes == null) { errors += "$path: file not found in the ZIP"; continue }
            val cat = StickerCategories.resolve(e.category)
            if (cat == null) { errors += "$path: unknown category \"${e.category ?: "(none)"}\""; continue }
            if (bytes.size > MAX_BYTES) { errors += "$path: larger than 8 MB"; continue }
            val fmt = StickerFormat.sniff(bytes)
            if (fmt == null || StickerFormat.fromExtension(path) == null) { errors += "$path: not a PNG, WebP or SVG picture"; continue }
            val invalid = validate(bytes, fmt)
            if (invalid != null) { errors += "$path: $invalid"; continue }
            val base = path.substringAfterLast('/').substringBeforeLast('.')
            val id = (e.id?.trim()?.takeIf { it.isNotEmpty() } ?: "${cat.id}/$base")
            if (!seenIds.add(id)) { errors += "$path: repeated id \"$id\" in this import"; continue }
            val hash = sha256(bytes)
            val stored = "$hash.${fmt.ext}"
            val old = result[id]
            if (old != null && old.sha256 == hash && old.category == cat.id && (e.position == null || e.position == old.position) && (e.name == null || e.name == old.name)) {
                unchanged++; continue
            }
            val sameContent = knownHashes[hash]
            if (old == null && sameContent != null) { duplicates += "$path = ${sameContent.id}"; continue }
            val position = e.position ?: old?.position ?: run {
                val n = nextPos.getOrPut(cat.id) { (result.values.filter { it.category == cat.id }.maxOfOrNull { it.position } ?: 0) + 1 }
                nextPos[cat.id] = n + 1; n
            }
            val s = LibrarySticker(id, cat.id, e.name ?: old?.name ?: base, position, stored, hash, fmt, now)
            if (old == null) added += s else updated += s
            result[id] = s
            knownHashes[hash] = s
            if (old?.sha256 != hash) newFiles[stored] = bytes
        }
        return StickerImportPlan(StickerLibraryIndex(result.values.toList()), newFiles, added, updated, unchanged, duplicates, errors)
    }
}
