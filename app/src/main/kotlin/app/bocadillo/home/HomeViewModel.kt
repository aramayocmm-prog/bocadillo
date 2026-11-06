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

    /** Imports a picture (picker, camera or share intent) as a new project and opens it. */
    fun importPicture(uri: Uri, title: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { ImageIo.read(app.contentResolver, uri) }
                val doc = withContext(Dispatchers.Default) {
                    // A small decode is enough for size, transparency check and gallery thumbnail.
                    val small = ImageIo.decode(imported.bytes, maxSide = 512)
                    val buf = ImageIo.toPixelBuffer(small.bitmap)
                    small.bitmap.recycle()
                    val (w, h) = ImageIo.workingSize(small.fullWidth, small.fullHeight)
                    Triple(w to h, buf.hasTransparency(), ImageIo.thumbnail(buf))
                }
                val name = imported.displayName.substringBeforeLast('.').ifBlank { title }
                val created = repo.create(imported, doc.first.first, doc.first.second, doc.second, name, doc.third)
                _openProject.send(created.id)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.error_import))
            } finally {
                _busy.value = false
            }
        }
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

    fun setFavorite(id: String, fav: Boolean) = viewModelScope.launch { repo.setFavorite(id, fav) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun duplicate(id: String, suffix: String) = viewModelScope.launch {
        runCatching { repo.duplicate(id, suffix) }.onFailure { _messages.send(UiMessage.Res(R.string.error_load, listOf(it.message ?: ""))) }
    }
}
