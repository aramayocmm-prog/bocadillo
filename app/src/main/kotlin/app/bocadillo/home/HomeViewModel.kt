package app.bocadillo.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bocadillo.BocadilloApp
import app.bocadillo.R
import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.model.ProjectIndex
import app.bocadillo.data.ImageIo
import app.bocadillo.editor.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val app: BocadilloApp) : ViewModel() {
    private val repo = app.projects

    val index: StateFlow<ProjectIndex> = repo.index

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Project to offer for recovery after an unexpected close (null = none). */
    private val _recovery = MutableStateFlow(repo.pendingRecovery())
    val recovery: StateFlow<String?> = _recovery.asStateFlow()

    private val _openProject = Channel<String>(Channel.BUFFERED)
    val openProject = _openProject.receiveAsFlow()
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun thumbFile(id: String) = repo.thumbFile(id)

    fun dismissRecovery() { repo.clearOpen(); _recovery.value = null }
    fun acceptRecovery() { _recovery.value?.let { _openProject.trySend(it) }; _recovery.value = null }

    /**
     * "Import large picture…": offered when the photo has more pixels than the Normal size.
     * Sizes keep the photo's proportions: Fast ≈ 0.8 MP (768×1024 for 3:4), Normal ≈ 3.1 MP
     * (1536×2048), Maximum = the photo itself, limited only by the memory available on the device.
     * The full original file is stored in the project either way; the choice sets the working
     * (and export) resolution, so nothing is reduced without the user deciding it.
     */
    data class LargeImport(val fullW: Int, val fullH: Int, val fast: Pair<Int, Int>, val normal: Pair<Int, Int>, val maximum: Pair<Int, Int>)

    private val _largeImport = MutableStateFlow<LargeImport?>(null)
    val largeImport: StateFlow<LargeImport?> = _largeImport.asStateFlow()
    private var pendingImport: Triple<ImageIo.Imported, Boolean, ByteArray>? = null
    private var pendingTitle = ""

    /** Imports a picture (picker, camera or share intent) as a new project and opens it. */
    fun importPicture(uri: Uri, title: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { ImageIo.read(app.contentResolver, uri) }
                val info = withContext(Dispatchers.Default) {
                    // A small decode is enough for size, transparency check and gallery thumbnail.
                    val small = ImageIo.decode(imported.bytes, maxSide = 512)
                    val buf = ImageIo.toPixelBuffer(small.bitmap)
                    small.bitmap.recycle()
                    Triple(small.fullWidth to small.fullHeight, buf.hasTransparency(), ImageIo.thumbnail(buf))
                }
                val (fw, fh) = info.first
                val name = imported.displayName.substringBeforeLast('.').ifBlank { title }
                if (fw.toLong() * fh > NORMAL_PIXELS) {
                    pendingImport = Triple(imported, info.second, info.third); pendingTitle = name
                    _largeImport.value = LargeImport(
                        fw, fh, ImageIo.fitPixels(fw, fh, FAST_PIXELS), ImageIo.fitPixels(fw, fh, NORMAL_PIXELS),
                        ImageIo.fitPixels(fw, fh, ImageIo.maxWorkingPixels()),
                    )
                    return@launch // busy until the user chooses
                }
                val (w, h) = ImageIo.workingSize(fw, fh)
                val created = repo.create(imported, w, h, info.second, name, info.third)
                _openProject.send(created.id)
                _busy.value = false
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.error_import))
                _busy.value = false
            }
        }
    }

    /** Choice of the "Import large picture…" dialog (null = cancelled). */
    fun chooseImportSize(size: Pair<Int, Int>?) {
        val p = pendingImport
        _largeImport.value = null; pendingImport = null
        if (size == null || p == null) { _busy.value = false; return }
        viewModelScope.launch {
            try {
                val created = repo.create(p.first, size.first, size.second, p.second, pendingTitle, p.third)
                _openProject.send(created.id)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.error_import))
            } finally { _busy.value = false }
        }
    }

    companion object {
        const val FAST_PIXELS = 786_432L     // 768 × 1024
        const val NORMAL_PIXELS = 3_145_728L // 1536 × 2048
    }

    fun createBlank(width: Int, height: Int, argb: Int, title: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { ImageIo.blankPng(width, height, argb) }
                val imported = ImageIo.Imported(bytes, "image/png", title)
                val (w, h) = ImageIo.workingSize(width, height)
                val hasAlpha = (argb ushr 24) != 0xFF
                val thumb = withContext(Dispatchers.Default) {
                    ImageIo.thumbnail(PixelBuffer.filled(minOf(w, 360), minOf(h, 360), argb))
                }
                _openProject.send(repo.create(imported, w, h, hasAlpha, title, thumb).id)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.error_import))
            } finally {
                _busy.value = false
            }
        }
    }

    fun importProject(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { repo.importArchive(it) } }
                _openProject.send(id)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.error_open_project))
            } finally { _busy.value = false }
        }
    }

    fun exportProject(id: String, uri: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)!!.use { repo.exportArchive(id, it) } }
            _messages.send(UiMessage.Res(R.string.project_saved))
        } catch (e: Exception) {
            _messages.send(UiMessage.Res(R.string.error_save_project))
        }
    }

    fun setFavorite(id: String, fav: Boolean) = viewModelScope.launch { repo.setFavorite(id, fav) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun duplicate(id: String, suffix: String) = viewModelScope.launch {
        runCatching { repo.duplicate(id, suffix) }.onFailure { _messages.send(UiMessage.Res(R.string.error_load, listOf(it.message ?: ""))) }
    }
}
