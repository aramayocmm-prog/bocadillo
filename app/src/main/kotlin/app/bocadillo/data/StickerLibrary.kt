package app.bocadillo.data

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * "My stickers": PNG pictures the user imported once and can reuse in every project.
 * When placed in a project the PNG is also copied into the project file (portable).
 */
class StickerLibrary(context: Context) {
    private val dir = File(context.filesDir, "sticker_library").apply { mkdirs() }

    fun list(): List<File> = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".png") }.sortedByDescending { it.lastModified() }

    /** Normalizes to PNG (≤ 1024 px, keeps transparency) and stores it. Returns the file. */
    fun add(bitmap: Bitmap): File {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val f = File(dir, "${UUID.randomUUID()}.png")
        f.writeBytes(out.toByteArray())
        return f
    }

    fun delete(file: File) { if (file.parentFile == dir) file.delete() }
}
