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

    val bundledFonts = listOf(
        FontOption("bundled:Poppins-Bold.ttf", "Poppins Bold", FontOption.Kind.BUNDLED),
        FontOption("bundled:Poppins-Regular.ttf", "Poppins", FontOption.Kind.BUNDLED),
        FontOption("bundled:Lora-Variable.ttf", "Lora", FontOption.Kind.BUNDLED),
    )

    fun importedFonts(): List<FontOption> =
        (library.listFiles() ?: emptyArray()).filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true)) }
            .sortedBy { it.name.lowercase() }
            .map { FontOption("asset:fonts/${it.name}", it.nameWithoutExtension, FontOption.Kind.IMPORTED) }

    fun all(): List<FontOption> = systemFonts + bundledFonts + importedFonts()

    /**
     * Adds a TTF/OTF to the library. Validates it by loading it; returns its reference.
     * @throws IllegalArgumentException if Android cannot load the font.
     */
    fun import(displayName: String, bytes: ByteArray): String {
        val base = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "font" }.take(40)
        val ext = if (displayName.endsWith(".otf", true)) "otf" else "ttf"
        val file = File(library, "$base.$ext")
        file.writeBytes(bytes)
        val tf = runCatching { Typeface.createFromFile(file) }.getOrNull()
        if (tf == null || bytes.size < 100) {
            file.delete(); throw IllegalArgumentException("Invalid font")
        }
        cache.remove("asset:fonts/${file.name}")
        return "asset:fonts/${file.name}"
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
