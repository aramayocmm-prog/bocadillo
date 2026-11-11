package app.bocadillo.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes exported pictures to the gallery (MediaStore), a user-chosen file (SAF) or a share cache. */
class ExportRepository(private val context: Context) {

    fun suggestedName(title: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = title.replace(Regex("[^\\p{L}\\p{N}_ -]"), "").trim().ifEmpty { "MAC_Editor" }.take(40)
        return "${base}_$stamp.$ext"
    }

    /** Saves into Pictures/Bocadillo. No permission is needed for files the app creates (API 29+). */
    suspend fun saveToGallery(bytes: ByteArray, mimeType: String, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MAC Editor para MEMES")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Cannot open output")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }

    /** Writes to a document the user created with the system "Save as" dialog. */
    suspend fun saveToUri(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Cannot open $uri")
    }

    /** Writes into cache/share and returns a content:// Uri for the share sheet. */
    suspend fun prepareShare(bytes: ByteArray, displayName: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest shared picture
        val file = File(dir, displayName)
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    /** File the camera app writes into. */
    fun newCameraUri(): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
}
