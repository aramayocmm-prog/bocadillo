package app.bocadillo.data

import android.content.Context
import app.bocadillo.core.model.Document
import app.bocadillo.core.model.ProjectArchive
import app.bocadillo.core.model.ProjectBundle
import app.bocadillo.core.model.ProjectEntry
import app.bocadillo.core.model.ProjectIndex
import app.bocadillo.core.model.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Stores projects as `.bcproj` files in app-private storage (no permissions needed) and keeps
 * the gallery index. Writes are atomic (temp file + rename) so a crash never corrupts a project.
 */
class ProjectRepository(context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val thumbs = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val sessionFile = File(root, "open_session")
    private val mutex = Mutex()

    private val _index = MutableStateFlow(loadIndex())
    val index: StateFlow<ProjectIndex> = _index.asStateFlow()

    private fun projectFile(id: String) = File(root, "$id.${ProjectArchive.EXTENSION}")
    fun thumbFile(id: String) = File(thumbs, "$id.jpg")

    private fun loadIndex(): ProjectIndex {
        val fromFile = runCatching { ProjectIndex.decode(indexFile.readText()) }.getOrNull()
        // Self-heal: drop entries whose file vanished.
        return (fromFile ?: ProjectIndex()).let { idx ->
            ProjectIndex(idx.entries.filter { projectFile(it.id).exists() })
        }
    }

    private fun writeIndex(idx: ProjectIndex) {
        atomicWrite(indexFile, idx.encode().toByteArray())
        _index.value = idx
    }

    /** Creates a project from an imported picture. [width]/[height] are the working size. */
    suspend fun create(imported: ImageIo.Imported, width: Int, height: Int, hasAlpha: Boolean, title: String, thumb: ByteArray?): Document =
        withContext(Dispatchers.IO) {
            val ext = when (imported.mimeType) {
                "image/png" -> "png"; "image/webp" -> "webp"; "image/heic", "image/heif" -> "heic"; "image/gif" -> "gif"
                else -> "jpg"
            }
            val doc = Document.create(
                title = title,
                source = SourceInfo("source.$ext", imported.mimeType, width, height, hasAlpha),
                now = System.currentTimeMillis(),
            )
            save(ProjectBundle(doc, imported.bytes, emptyMap(), thumb))
            doc
        }

    suspend fun save(bundle: ProjectBundle) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = bundle.document
            val tmp = File(root, "${doc.id}.tmp")
            tmp.outputStream().buffered().use { ProjectArchive.write(bundle, it) }
            if (!tmp.renameTo(projectFile(doc.id))) {
                projectFile(doc.id).delete()
                if (!tmp.renameTo(projectFile(doc.id))) throw IOException("Cannot replace project file")
            }
            bundle.thumbnailJpeg?.let { atomicWrite(thumbFile(doc.id), it) }
            writeIndex(_index.value.upsert(ProjectEntry(doc.id, doc.title, doc.modifiedAt)))
        }
    }

    suspend fun load(id: String): ProjectBundle = withContext(Dispatchers.IO) {
        projectFile(id).inputStream().buffered().use { ProjectArchive.read(it) }
    }

    suspend fun duplicate(id: String, copySuffix: String): String = withContext(Dispatchers.IO) {
        val b = load(id)
        val now = System.currentTimeMillis()
        val newDoc = b.document.copy(id = UUID.randomUUID().toString(), title = b.document.title + copySuffix, createdAt = now, modifiedAt = now)
        save(ProjectBundle(newDoc, b.sourceBytes, b.masks, b.thumbnailJpeg, b.assets))
        newDoc.id
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            projectFile(id).delete(); thumbFile(id).delete()
            writeIndex(_index.value.remove(id))
        }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { writeIndex(_index.value.setFavorite(id, favorite)) }
    }

    /** Copies a project's `.bcproj` file ("Save project…"). */
    suspend fun exportArchive(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        projectFile(id).inputStream().use { it.copyTo(out) }
    }

    /** Adds a `.bcproj` file as a new project ("Open project…"); returns its id. */
    suspend fun importArchive(input: java.io.InputStream): String = withContext(Dispatchers.IO) {
        val b = ProjectArchive.read(input.buffered())
        // new id so importing twice (or a copy of an existing project) never overwrites anything
        val doc = b.document.copy(id = UUID.randomUUID().toString(), modifiedAt = System.currentTimeMillis())
        save(ProjectBundle(doc, b.sourceBytes, b.masks, b.thumbnailJpeg, b.assets))
        doc.id
    }

    // ---- crash recovery: a marker exists while a project is open in the editor ----

    fun markOpen(id: String) = runCatching { sessionFile.writeText(id) }
    fun clearOpen() = runCatching { sessionFile.delete() }

    /** Project that was open when the app died, if it still exists. */
    fun pendingRecovery(): String? =
        runCatching { sessionFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && projectFile(it).exists() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
