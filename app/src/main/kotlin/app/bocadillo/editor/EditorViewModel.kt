package app.bocadillo.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.bocadillo.BocadilloApp
import app.bocadillo.R
import app.bocadillo.core.imaging.PipelineRenderer
import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.Resample
import app.bocadillo.core.imaging.objects.StickerDef
import app.bocadillo.core.imaging.ops.Geometry
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.model.Arrange
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.BalloonShape
import app.bocadillo.core.model.CanvasObject
import app.bocadillo.core.model.Document
import app.bocadillo.core.model.History
import app.bocadillo.core.model.MaskData
import app.bocadillo.core.model.ObjectTransform
import app.bocadillo.core.model.OpInstance
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params
import app.bocadillo.core.model.ProjectBundle
import app.bocadillo.core.model.StickerObject
import app.bocadillo.core.model.TextObject
import app.bocadillo.core.model.TextStyle
import app.bocadillo.core.model.addObject
import app.bocadillo.core.model.arrangeObject
import app.bocadillo.core.model.defaults
import app.bocadillo.core.model.duplicateObject
import app.bocadillo.core.model.newObjectId
import app.bocadillo.core.model.objectById
import app.bocadillo.core.model.referencedAssets
import app.bocadillo.core.model.removeObject
import app.bocadillo.core.model.updateObject
import app.bocadillo.data.ImageIo
import app.bocadillo.render.ObjectRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Tool currently open in the editor. */
sealed interface ToolState {
    /** Generic parameter tool (adjustments, colour, free rotation…). */
    data class Adjust(val op: ImageOp, val params: Params) : ToolState

    /** Crop & straighten. rect = [l,t,r,b] normalized to the input; ratio null = free. */
    data class Crop(val rect: FloatArray, val angle: Float, val preset: Geometry.CropPreset, val ratio: Float?) : ToolState {
        override fun equals(other: Any?) = other is Crop && rect.contentEquals(other.rect) && angle == other.angle && preset == other.preset && ratio == other.ratio
        override fun hashCode() = rect.contentHashCode() * 31 + angle.hashCode()
    }

    data object Orient : ToolState
    data object Resize : ToolState
    data object Export : ToolState

    /** Text ("title") editor working on a draft; [isNew] = not yet in the document. */
    data class EditText(val draft: TextObject, val isNew: Boolean) : ToolState
    data class EditBalloon(val draft: BalloonObject, val isNew: Boolean) : ToolState
    /** Sticker catalogue; [replaceId] set when replacing the selected sticker. */
    data class PickSticker(val replaceId: String?) : ToolState
    /** Sticker colours / outline / opacity. */
    data class StyleSticker(val draft: StickerObject) : ToolState
}

sealed interface UiMessage {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiMessage
}

data class EditorUiState(
    val loading: Boolean = true,
    val fatalError: UiMessage? = null,
    val title: String = "",
    val preview: ImageBitmap? = null,
    /** Rendering of the document without the pending tool, for press-and-hold comparison. */
    val before: ImageBitmap? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val historyStep: Int = 0,
    val tool: ToolState? = null,
    /** Full-resolution size of the current result (all committed ops). */
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val sourceHasAlpha: Boolean = false,
    val exporting: Boolean = false,
    /** Overlay objects as currently shown (committed, or live while dragging). */
    val objects: List<CanvasObject> = emptyList(),
    val selectedId: String? = null,
    /** Corner handle stretches instead of scale/rotate ("Stretch Tool"). */
    val stretchMode: Boolean = false,
    /** Bumped whenever project assets (fonts, imported stickers) change. */
    val assetsVersion: Int = 0,
    /** Capture date from EXIF (ms), for "Insert date and time". */
    val photoDate: Long? = null,
    /** Bumped when "My stickers" changes. */
    val libraryVersion: Int = 0,
) {
    /** Objects to draw: drafts of the open object tool replace (or are added to) the list. */
    fun displayObjects(): List<CanvasObject> {
        val draft: CanvasObject = when (val t = tool) {
            is ToolState.EditText -> t.draft
            is ToolState.EditBalloon -> t.draft
            is ToolState.StyleSticker -> t.draft
            else -> return objects
        }
        return if (objects.any { it.id == draft.id }) objects.map { if (it.id == draft.id) draft else it } else objects + draft
    }

    val selected: CanvasObject? get() = selectedId?.let { id -> objects.firstOrNull { it.id == id } }
}

/** Share requests are one-off events delivered to the screen. */
data class ShareRequest(val uri: Uri, val mimeType: String)

enum class ExportFormat(val quality: Int) {
    JPEG_VERY_HIGH(95), JPEG_HIGH(85), JPEG_MEDIUM(70), PNG(100), ORIGINAL(95)
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG_VERY_HIGH,
    val quality: Int = 95,
    /** 1..100, percentage of the full output size. */
    val scalePercent: Int = 100,
    val jpegBackground: Int = 0xFFFFFFFF.toInt(),
)

enum class ExportTarget { GALLERY, SHARE, DOCUMENT }

class EditorViewModel(private val app: BocadilloApp, private val projectId: String) : ViewModel() {

    private val repo = app.projects
    private val registry = app.registry
    private val renderer = PipelineRenderer(registry)

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _shares = Channel<ShareRequest>(Channel.BUFFERED)
    val shares = _shares.receiveAsFlow()

    private lateinit var history: History<Document>
    private lateinit var sourceBytes: ByteArray
    private var masks: Map<String, MaskData> = emptyMap()
    /** Fonts and imported stickers of this project (path → bytes). */
    private val assets = HashMap<String, ByteArray>()
    private lateinit var proxy: PixelBuffer
    private var proxyScale = 1f
    private var lastPreview: PixelBuffer? = null

    private val renderTrigger = MutableStateFlow(0L)
    private var autosaveJob: Job? = null
    private val saveMutex = Mutex()
    private var dirty = false

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch(Dispatchers.Default) {
            renderTrigger.collectLatest { if (::proxy.isInitialized) renderPreview() }
        }
    }

    private val doc: Document get() = history.current

    /** Snapshot of the project assets for the UI renderer. */
    fun assetsSnapshot(): Map<String, ByteArray> = HashMap(assets)

    // ------------------------------------------------------------------ loading

    private suspend fun load() {
        try {
            val bundle = repo.load(projectId)
            sourceBytes = bundle.sourceBytes
            masks = bundle.masks
            assets.putAll(bundle.assets)
            val d = bundle.document
            history = History(d, "opened")
            val decoded = withContext(Dispatchers.Default) {
                // Preview proxy: ≤ 2048 px on the long side (never larger than the working size).
                val maxSide = minOf(2048, max(d.source.width, d.source.height))
                val k = maxSide.toFloat() / max(d.source.width, d.source.height)
                ImageIo.decodeExact(sourceBytes, max(1, (d.source.width * k).roundToInt()), max(1, (d.source.height * k).roundToInt()))
            }
            proxy = ImageIo.toPixelBuffer(decoded.bitmap)
            decoded.bitmap.recycle()
            proxyScale = proxy.width.toFloat() / d.source.width
            if (decoded.fullWidth.toLong() * decoded.fullHeight > d.source.width.toLong() * d.source.height) {
                _messages.send(UiMessage.Res(R.string.picture_scaled, listOf(d.source.width, d.source.height)))
            }
            val photoDate = withContext(Dispatchers.IO) { exifDate(sourceBytes) }
            repo.markOpen(projectId)
            _state.update { it.copy(loading = false, title = d.title, sourceHasAlpha = d.source.hasAlpha, photoDate = photoDate, assetsVersion = it.assetsVersion + 1) }
            syncHistoryState()
            requestRender()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, fatalError = UiMessage.Res(R.string.error_load, listOf(e.message ?: e.javaClass.simpleName))) }
        }
    }

    private fun exifDate(bytes: ByteArray): Long? = runCatching {
        val exif = ExifInterface(bytes.inputStream())
        val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time
    }.getOrNull()

    // ------------------------------------------------------------------ rendering

    private fun requestRender() { renderTrigger.value = renderTrigger.value + 1 }

    private fun previewOps(): List<OpInstance> {
        val base = doc.ops
        return when (val t = _state.value.tool) {
            is ToolState.Adjust -> base + OpInstance(PENDING_UID, t.op.id, t.params)
            // While cropping we show the straightened full frame; the overlay draws the rect.
            is ToolState.Crop -> if (t.angle != 0f) base + OpInstance(PENDING_UID, TransformOps.Crop.id, Params.of("angle" to t.angle.toDouble())) else base
            else -> base
        }
    }

    private suspend fun renderPreview() {
        val job = coroutineContext[Job]
        val ctx = RenderContext(scale = proxyScale, isCancelled = { job?.isActive == false })
        try {
            val result = renderer.render(proxy, PROXY_TOKEN, previewOps(), masks, ctx)
            val bmp = ImageIo.toBitmap(result).asImageBitmap()
            lastPreview = result
            _state.update { it.copy(preview = bmp) }
        } catch (_: CancellationException) {
            // superseded by a newer render request
        }
    }

    private suspend fun renderBefore(): ImageBitmap {
        val r = renderer.render(proxy, PROXY_TOKEN, doc.ops, masks, RenderContext(scale = proxyScale))
        return ImageIo.toBitmap(r).asImageBitmap()
    }

    // ------------------------------------------------------------------ history

    private fun syncHistoryState() {
        val (w, h) = outputSize(doc.ops)
        _state.update {
            val sel = it.selectedId?.takeIf { id -> doc.objectById(id) != null }
            it.copy(
                canUndo = history.canUndo, canRedo = history.canRedo, historyStep = history.index,
                outputWidth = w, outputHeight = h, title = doc.title, objects = doc.objects, selectedId = sel,
            )
        }
    }

    /** Full-resolution size after applying [ops] to the source. */
    fun outputSize(ops: List<OpInstance>): Pair<Int, Int> {
        val one = RenderContext(scale = 1f)
        return ops.fold(doc.source.width to doc.source.height) { (w, h), op ->
            registry[op.opId]?.outputSize(w, h, op.params, one) ?: (w to h)
        }
    }

    private fun commit(newDoc: Document, label: String) {
        history.push(newDoc, label)
        dirty = true
        syncHistoryState()
        requestRender()
        scheduleAutosave()
    }

    fun undo() { if (::history.isInitialized && history.canUndo && _state.value.tool == null) { history.undo(); afterJump() } }
    fun redo() { if (::history.isInitialized && history.canRedo && _state.value.tool == null) { history.redo(); afterJump() } }

    private fun afterJump() { dirty = true; syncHistoryState(); requestRender(); scheduleAutosave() }

    fun restoreOriginal() {
        if (doc.ops.isEmpty() && doc.objects.isEmpty()) return
        _state.update { it.copy(selectedId = null) }
        commit(doc.resetToOriginal(now()), "restore_original")
    }

    // ------------------------------------------------------------------ generic tools

    fun openTool(op: ImageOp) {
        if (op.id == TransformOps.Crop.id) { openCrop(); return }
        _state.update { it.copy(tool = ToolState.Adjust(op, op.params.defaults()), before = it.preview, selectedId = null) }
        requestRender()
    }

    fun updateParam(transform: (Params) -> Params) {
        val t = _state.value.tool as? ToolState.Adjust ?: return
        _state.update { it.copy(tool = t.copy(params = transform(t.params))) }
        requestRender()
    }

    fun resetParams() {
        val t = _state.value.tool as? ToolState.Adjust ?: return
        _state.update { it.copy(tool = t.copy(params = t.op.params.defaults())) }
        requestRender()
    }

    fun applyTool() {
        when (val t = _state.value.tool) {
            is ToolState.Adjust -> {
                closeTool(render = false)
                if (!t.op.isIdentity(t.params)) commit(doc.withOp(OpInstance.create(t.op.id, t.params), now()), t.op.id) else requestRender()
            }
            is ToolState.Crop -> applyCrop(t)
            is ToolState.EditText -> applyObjectDraft(t.draft, t.isNew, blank = t.draft.text.isBlank(), label = "text")
            is ToolState.EditBalloon -> applyObjectDraft(t.draft, t.isNew, blank = false, label = "balloon")
            is ToolState.StyleSticker -> applyObjectDraft(t.draft, isNew = false, blank = false, label = "sticker_style")
            else -> closeTool()
        }
    }

    fun cancelTool() = closeTool()

    private fun closeTool(render: Boolean = true) {
        _state.update { it.copy(tool = null, before = null) }
        if (render) requestRender()
    }

    fun openPanel(tool: ToolState) { _state.update { it.copy(tool = tool) } }

    // ------------------------------------------------------------------ orient / resize

    fun orient(rotation: Int = 0, flipH: Boolean = false, flipV: Boolean = false) {
        val p = Params.of("rotation" to rotation.toString(), "flipH" to flipH, "flipV" to flipV)
        commit(doc.withOp(OpInstance.create(TransformOps.Orient.id, p), now()), TransformOps.Orient.id)
    }

    /** Returns false (and shows a message) when the size would not change. */
    fun applyResize(width: Int, height: Int, mode: String, background: Int): Boolean {
        val (w, h) = outputSize(doc.ops)
        if (width == w && height == h) {
            _messages.trySend(UiMessage.Res(R.string.resize_same_size)); return false
        }
        val p = Params.of("width" to width, "height" to height, "mode" to mode).withColor("background", background)
        closeTool(render = false)
        commit(doc.withOp(OpInstance.create(TransformOps.Resize.id, p), now()), TransformOps.Resize.id)
        return true
    }

    // ------------------------------------------------------------------ crop & straighten

    private fun openCrop() {
        _state.update { it.copy(tool = ToolState.Crop(floatArrayOf(0f, 0f, 1f, 1f), 0f, Geometry.CropPreset.FREEFORM, null), before = null, selectedId = null) }
        requestRender()
    }

    /** Area the crop rect must stay within for the current straighten angle. */
    private fun allowedInset(angle: Float): Float {
        val (w, h) = outputSize(doc.ops)
        return (1f - Geometry.straightenScale(w, h, angle)) / 2f
    }

    fun setCropRect(rect: FloatArray) {
        val t = _state.value.tool as? ToolState.Crop ?: return
        val m = allowedInset(t.angle)
        val r = floatArrayOf(rect[0].coerceIn(m, 1 - m), rect[1].coerceIn(m, 1 - m), rect[2].coerceIn(m, 1 - m), rect[3].coerceIn(m, 1 - m))
        if (r[2] - r[0] < 0.02f || r[3] - r[1] < 0.02f) return
        _state.update { it.copy(tool = t.copy(rect = r)) }
    }

    fun setStraighten(angle: Float) {
        val t = _state.value.tool as? ToolState.Crop ?: return
        val a = (TransformOps.Crop.params.first { it.key == "angle" } as ParamSpec.Slider).snap(angle)
        _state.update { it.copy(tool = t.copy(angle = a, rect = fitRect(t.ratio, a))) }
        requestRender()
    }

    /** ratio = output pixel aspect (w/h) or null for freeform. */
    fun setCropPreset(preset: Geometry.CropPreset, ratio: Float?) {
        val t = _state.value.tool as? ToolState.Crop ?: return
        val (w, h) = outputSize(doc.ops)
        val r = if (preset == Geometry.CropPreset.ORIGINAL) w.toFloat() / h else ratio
        _state.update { it.copy(tool = t.copy(preset = preset, ratio = r, rect = fitRect(r, t.angle))) }
    }

    private fun fitRect(ratio: Float?, angle: Float): FloatArray {
        val (w, h) = outputSize(doc.ops)
        val base = if (ratio == null) floatArrayOf(0f, 0f, 1f, 1f) else Geometry.centeredRect(w, h, ratio)
        val k = Geometry.straightenScale(w, h, angle)
        val cx = (base[0] + base[2]) / 2; val cy = (base[1] + base[3]) / 2
        val hw = (base[2] - base[0]) / 2 * k; val hh = (base[3] - base[1]) / 2 * k
        return floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    private fun applyCrop(t: ToolState.Crop) {
        closeTool(render = false)
        val p = Params.of("angle" to t.angle.toDouble()).with("rect", t.rect)
        if (TransformOps.Crop.isIdentity(p)) { requestRender(); return }
        commit(doc.withOp(OpInstance.create(TransformOps.Crop.id, p), now()), TransformOps.Crop.id)
    }

    // ------------------------------------------------------------------ comparison

    /** Press-and-hold "compare" needs the picture without the pending tool. */
    fun ensureBefore() {
        if (_state.value.before != null) return
        viewModelScope.launch(Dispatchers.Default) { val b = renderBefore(); _state.update { it.copy(before = b) } }
    }

    // ------------------------------------------------------------------ objects: selection & gestures

    fun selectObject(id: String?) {
        if (_state.value.tool != null) return
        _state.update { it.copy(selectedId = id) }
    }

    /** Live update while dragging (no history step yet). */
    fun liveObject(o: CanvasObject) {
        _state.update { s -> s.copy(objects = s.objects.map { if (it.id == o.id) o else it }) }
    }

    /** End of a gesture: one undoable step for the whole drag. */
    fun commitLive(label: String) {
        val objs = _state.value.objects
        if (objs != doc.objects) commit(doc.copy(objects = objs, modifiedAt = now()), label)
    }

    fun toggleStretchMode() { _state.update { it.copy(stretchMode = !it.stretchMode) } }

    private fun selected(): CanvasObject? = _state.value.selectedId?.let { doc.objectById(it) }

    fun duplicateSelected() {
        val o = selected() ?: return
        val (d, newId) = doc.duplicateObject(o.id, now())
        _state.update { it.copy(selectedId = newId) }
        commit(d, "duplicate")
    }

    fun deleteSelected() {
        val o = selected() ?: return
        _state.update { it.copy(selectedId = null) }
        commit(doc.removeObject(o.id, now()), "delete")
    }

    fun arrangeSelected(how: Arrange) {
        val o = selected() ?: return
        commit(doc.arrangeObject(o.id, how, now()), "arrange")
    }

    fun flipSelected(horizontal: Boolean) {
        val o = selected() ?: return
        val t = o.transform
        commit(doc.updateObject(o.withTransform(if (horizontal) t.copy(flipH = !t.flipH) else t.copy(flipV = !t.flipV)), now()), "flip")
    }

    fun resetTransformSelected() {
        val o = selected() ?: return
        commit(doc.updateObject(o.withTransform(o.transform.reset()), now()), "transform_reset")
    }

    fun setOpacityLive(value: Float) { _state.value.selected?.let { liveObject(it.withOpacity(value)) } }

    // ------------------------------------------------------------------ objects: create & edit

    private fun center(): ObjectTransform = ObjectTransform(0.5f, 0.5f)

    fun newText(text: String = "") {
        val draft = TextObject(newObjectId(), center(), text, TextStyle())
        _state.update { it.copy(tool = ToolState.EditText(draft, true), selectedId = null) }
    }

    fun newBalloon(shape: BalloonShape = BalloonShape.SPEECH) {
        val draft = BalloonObject(newObjectId(), ObjectTransform(0.5f, 0.35f), shape)
        _state.update { it.copy(tool = ToolState.EditBalloon(draft, true), selectedId = null) }
    }

    fun openStickerPicker(replace: Boolean = false) {
        _state.update { it.copy(tool = ToolState.PickSticker(if (replace) it.selectedId else null)) }
    }

    /** Opens the editor that fits the selected object. */
    fun editSelected() {
        when (val o = selected()) {
            is TextObject -> _state.update { it.copy(tool = ToolState.EditText(o, false)) }
            is BalloonObject -> _state.update { it.copy(tool = ToolState.EditBalloon(o, false)) }
            is StickerObject -> _state.update { it.copy(tool = ToolState.StyleSticker(o)) }
            null -> Unit
        }
    }

    fun updateDraft(o: CanvasObject) {
        _state.update { s ->
            s.copy(tool = when (val t = s.tool) {
                is ToolState.EditText -> if (o is TextObject) t.copy(draft = o) else t
                is ToolState.EditBalloon -> if (o is BalloonObject) t.copy(draft = o) else t
                is ToolState.StyleSticker -> if (o is StickerObject) t.copy(draft = o) else t
                else -> t
            })
        }
    }

    private fun applyObjectDraft(draft: CanvasObject, isNew: Boolean, blank: Boolean, label: String) {
        closeTool(render = false)
        if (blank) return
        ensureFontAsset(draft)
        val d = if (isNew || doc.objectById(draft.id) == null) doc.addObject(draft, now()) else doc.updateObject(draft, now())
        _state.update { it.copy(selectedId = draft.id) }
        commit(d, label)
    }

    /** Embeds imported library fonts in the project so it stays portable. */
    private fun ensureFontAsset(o: CanvasObject) {
        val font = when (o) { is TextObject -> o.style.font; is BalloonObject -> o.style.font; else -> return }
        if (!font.startsWith("asset:")) return
        val path = font.removePrefix("asset:")
        if (path !in assets) app.fonts.libraryBytes(font)?.let { assets[path] = it; bumpAssets() }
    }

    fun pickSticker(def: StickerDef) {
        val t = _state.value.tool as? ToolState.PickSticker
        val replace = t?.replaceId?.let { doc.objectById(it) as? StickerObject }
        closeTool(render = false)
        if (replace != null) {
            commit(doc.updateObject(replace.copy(source = "builtin:${def.id}", aspect = def.aspect, colors = emptyList()), now()), "replace")
        } else {
            val o = StickerObject(newObjectId(), center(), "builtin:${def.id}", aspect = def.aspect)
            _state.update { it.copy(selectedId = o.id) }
            commit(doc.addObject(o, now()), "sticker")
        }
    }

    /**
     * Imports pictures (ideally transparent PNGs) into "My stickers". With a single picture it is
     * also placed on the canvas (or replaces the selected sticker).
     */
    fun importStickerImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val replaceId = (_state.value.tool as? ToolState.PickSticker)?.replaceId
        viewModelScope.launch {
            var last: java.io.File? = null
            var failed = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val imported = ImageIo.read(app.contentResolver, uri)
                        val dec = ImageIo.decode(imported.bytes, maxSide = 1024)
                        last = app.stickerLibrary.add(dec.bitmap)
                        dec.bitmap.recycle()
                    } catch (e: Exception) { failed++ }
                }
            }
            _state.update { it.copy(libraryVersion = it.libraryVersion + 1) }
            if (failed > 0) _messages.send(UiMessage.Res(R.string.error_import))
            if (uris.size == 1) last?.let { placeLibrarySticker(it, replaceId) }
            else _messages.send(UiMessage.Res(R.string.stickers_imported, listOf(uris.size - failed)))
        }
    }

    /** Places a "My stickers" picture: copies it into the project assets and adds the object. */
    fun placeLibrarySticker(file: java.io.File, replaceId: String? = (_state.value.tool as? ToolState.PickSticker)?.replaceId) {
        closeTool(render = false)
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return@launch
            val aspect = withContext(Dispatchers.Default) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1f
            }
            val path = "stickers/${file.name}"
            assets[path] = bytes
            bumpAssets()
            val replace = replaceId?.let { doc.objectById(it) as? StickerObject }
            if (replace != null) {
                commit(doc.updateObject(replace.copy(source = "asset:$path", aspect = aspect, colors = emptyList()), now()), "replace")
            } else {
                val o = StickerObject(newObjectId(), center(), "asset:$path", aspect = aspect)
                _state.update { it.copy(selectedId = o.id) }
                commit(doc.addObject(o, now()), "sticker")
            }
        }
    }

    fun deleteLibrarySticker(file: java.io.File) {
        app.stickerLibrary.delete(file)
        _state.update { it.copy(libraryVersion = it.libraryVersion + 1) }
    }

    /** Imports a TTF/OTF into the font library. Returns the font reference via [onDone]. */
    fun importFont(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val ref = withContext(Dispatchers.IO) {
                    val f = ImageIo.read(app.contentResolver, uri)
                    app.fonts.import(f.displayName, f.bytes).also { r -> assets[r.removePrefix("asset:")] = f.bytes }
                }
                bumpAssets()
                onDone(ref)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.font_import_failed))
            }
        }
    }

    private fun bumpAssets() { _state.update { it.copy(assetsVersion = it.assetsVersion + 1) } }

    // ------------------------------------------------------------------ saving

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch { delay(1200); saveNow() }
    }

    /** Draws [objects] over [base] with a private renderer (safe off the UI thread). */
    private fun composite(base: PixelBuffer, objects: List<CanvasObject>): Bitmap {
        val bmp = ImageIo.toMutableBitmap(base)
        if (objects.isNotEmpty()) {
            val r = ObjectRenderer(app.fonts, app.stickers).also { it.assets = HashMap(assets) }
            r.drawAll(Canvas(bmp), objects, bmp.width.toFloat(), bmp.height.toFloat())
        }
        return bmp
    }

    /** Persists the project. Safe to call from any lifecycle callback. */
    suspend fun saveNow() {
        if (!::history.isInitialized || !dirty) return
        saveMutex.withLock {
            if (!dirty) return
            val d = history.current
            val thumb = lastPreview?.let { p -> withContext(Dispatchers.Default) { runCatching { ImageIo.thumbnail(composite(p, d.objects)) }.getOrNull() } }
            try {
                val used = d.referencedAssets()
                repo.save(ProjectBundle(d, sourceBytes, masks, thumb, assets.filterKeys { it in used }))
                dirty = false
            } catch (e: Exception) {
                _messages.trySend(UiMessage.Res(R.string.error_save, listOf(e.message ?: "")))
            }
        }
    }

    /** Called on ON_STOP: save outside the ViewModel scope so it completes even if cleared. */
    fun flush() { app.appScope.launch { saveNow() } }

    /** User left the editor normally: no recovery needed next start. */
    fun closeEditor() {
        app.appScope.launch { saveNow(); repo.clearOpen() }
    }

    override fun onCleared() { flush() }

    // ------------------------------------------------------------------ export

    fun export(options: ExportOptions, target: ExportTarget, documentUri: Uri? = null) {
        if (_state.value.exporting) return
        val d = doc
        _state.update { it.copy(exporting = true, tool = null) }
        app.appScope.launch {
            try {
                val (fmt, quality) = resolveFormat(options, d)
                val bytes = withContext(Dispatchers.Default) {
                    val full = if (proxyScale >= 1f) proxy else {
                        val dec = ImageIo.decodeExact(sourceBytes, d.source.width, d.source.height)
                        ImageIo.toPixelBuffer(dec.bitmap).also { dec.bitmap.recycle() }
                    }
                    val s = options.scalePercent.coerceIn(1, 100) / 100f
                    val src = if (s < 1f) Resample.resize(full, max(1, (full.width * s).roundToInt()), max(1, (full.height * s).roundToInt())) else full
                    val out = PipelineRenderer(registry, cacheSize = 1).render(src, "export", d.ops, masks, RenderContext(scale = s))
                    ImageIo.encodeBitmap(composite(out, d.objects), fmt, quality, options.jpegBackground)
                }
                val name = app.exports.suggestedName(d.title, fmt.extension)
                when (target) {
                    ExportTarget.GALLERY -> { app.exports.saveToGallery(bytes, fmt.mimeType, name); _messages.send(UiMessage.Res(R.string.export_done_gallery)) }
                    ExportTarget.DOCUMENT -> { app.exports.saveToUri(requireNotNull(documentUri), bytes); _messages.send(UiMessage.Res(R.string.export_done_file)) }
                    ExportTarget.SHARE -> _shares.send(ShareRequest(app.exports.prepareShare(bytes, name), fmt.mimeType))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { // includes OutOfMemoryError on huge pictures
                _messages.send(UiMessage.Res(R.string.export_failed, listOf(e.message ?: e.javaClass.simpleName)))
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun exportFormatFor(options: ExportOptions): ImageIo.Format = resolveFormat(options, doc).first

    private fun resolveFormat(o: ExportOptions, d: Document): Pair<ImageIo.Format, Int> = when (o.format) {
        ExportFormat.PNG -> ImageIo.Format.PNG to 100
        ExportFormat.ORIGINAL ->
            if (d.source.mimeType == "image/png" || d.source.hasAlpha) ImageIo.Format.PNG to 100 else ImageIo.Format.JPEG to 95
        else -> ImageIo.Format.JPEG to o.quality
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val PENDING_UID = "pending"
        private const val PROXY_TOKEN = "proxy"

        fun factory(app: BocadilloApp, projectId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, projectId) as T
        }
    }
}
