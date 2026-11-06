package app.bocadillo.core.model

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Coverage mask: one byte per pixel, 0 = effect not applied, 255 = fully applied. */
class MaskData(val width: Int, val height: Int, val data: ByteArray) {
    init { require(data.size == width * height) { "Mask data size mismatch" } }

    fun copy(): MaskData = MaskData(width, height, data.copyOf())

    companion object {
        fun filled(width: Int, height: Int, value: Int): MaskData =
            MaskData(width, height, ByteArray(width * height) { value.toByte() })
    }
}

/** Everything stored in a project file. */
class ProjectBundle(
    val document: Document,
    /** Original picture bytes exactly as imported (never re-encoded). */
    val sourceBytes: ByteArray,
    val masks: Map<String, MaskData> = emptyMap(),
    val thumbnailJpeg: ByteArray? = null,
)

/**
 * Project file format ".bcproj": a ZIP archive with
 *  - manifest.json      {"format":"bocadillo-project","version":1}
 *  - document.json      see [DocumentJson]
 *  - source/<fileName>  original picture bytes
 *  - masks/<id>.mask    "BMSK" + int width + int height + raw coverage bytes
 *  - thumbnail.jpg      optional preview for the gallery
 */
object ProjectArchive {
    const val EXTENSION = "bcproj"
    const val MIME_TYPE = "application/x-bocadillo-project"
    private const val FORMAT = "bocadillo-project"
    private const val MASK_MAGIC = 0x424D534B // "BMSK"

    fun write(bundle: ProjectBundle, out: OutputStream) {
        val doc = bundle.document
        val used = doc.referencedMaskIds()
        ZipOutputStream(out).use { zip ->
            zip.entry("manifest.json", """{"format":"$FORMAT","version":${Document.FORMAT_VERSION}}""".toByteArray())
            zip.entry("document.json", DocumentJson.encode(doc.copy(masks = doc.masks.filter { it.id in used })).toByteArray())
            zip.entry("source/${safeName(doc.source.fileName)}", bundle.sourceBytes)
            for ((id, mask) in bundle.masks) {
                if (id !in used) continue
                zip.entry("masks/${safeName(id)}.mask", encodeMask(mask))
            }
            bundle.thumbnailJpeg?.let { zip.entry("thumbnail.jpg", it) }
        }
    }

    fun read(input: InputStream): ProjectBundle {
        var manifest: String? = null
        var docText: String? = null
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name.contains("..")) throw ProjectFormatException("Invalid entry name $name")
                val bytes = zip.readBytes()
                when (name) {
                    "manifest.json" -> manifest = bytes.decodeToString()
                    "document.json" -> docText = bytes.decodeToString()
                    else -> files[name] = bytes
                }
            }
        }
        val m = manifest ?: throw ProjectFormatException("Not a project file: manifest.json missing")
        if (!m.contains("\"$FORMAT\"")) throw ProjectFormatException("Not a Bocadillo project")
        val doc = DocumentJson.decode(docText ?: throw ProjectFormatException("document.json missing"))
        val source = files["source/${safeName(doc.source.fileName)}"]
            ?: throw ProjectFormatException("Source picture missing")
        val masks = doc.masks.associate { ref ->
            val bytes = files["masks/${safeName(ref.id)}.mask"] ?: throw ProjectFormatException("Mask ${ref.id} missing")
            val mask = decodeMask(bytes)
            if (mask.width != ref.width || mask.height != ref.height) throw ProjectFormatException("Mask ${ref.id} size mismatch")
            ref.id to mask
        }
        return ProjectBundle(doc, source, masks, files["thumbnail.jpg"])
    }

    fun encodeMask(mask: MaskData): ByteArray {
        val bos = ByteArrayOutputStream(mask.data.size + 12)
        DataOutputStream(bos).use { d ->
            d.writeInt(MASK_MAGIC); d.writeInt(mask.width); d.writeInt(mask.height); d.write(mask.data)
        }
        return bos.toByteArray()
    }

    fun decodeMask(bytes: ByteArray): MaskData {
        DataInputStream(bytes.inputStream()).use { d ->
            if (d.readInt() != MASK_MAGIC) throw ProjectFormatException("Bad mask header")
            val w = d.readInt(); val h = d.readInt()
            if (w <= 0 || h <= 0 || w.toLong() * h > 400_000_000L) throw ProjectFormatException("Bad mask size")
            val data = ByteArray(w * h)
            d.readFully(data)
            return MaskData(w, h, data)
        }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
