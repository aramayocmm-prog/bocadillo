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
import app.bocadillo.core.imaging.ops.MaskParams
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.imaging.paint.Stroke
import app.bocadillo.data.FontRepository
import app.bocadillo.core.imaging.paint.StrokeCodec
import app.bocadillo.core.imaging.ops.PaintOps
import app.bocadillo.core.imaging.paint.StrokePainter
import app.bocadillo.core.imaging.paint.IncrementalStroke
import app.bocadillo.render.LiveTiles
import app.bocadillo.render.LivePump
import app.bocadillo.render.processPicture
import app.bocadillo.core.imaging.objects.Cutout
import app.bocadillo.core.imaging.paint.StrokeMode
import app.bocadillo.core.imaging.paint.StrokeRasterizer
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
    data class PickSticker(val replaceId: String?, val tab: Int = 1, val category: String? = null) : ToolState
    /** Sticker colours / outline / opacity. */
    data class StyleSticker(val draft: StickerObject, val vector: VectorStyleKind? = null) : ToolState

    /**
     * Insert Picture in the classic three steps: CROP the picked image (its own crop, the canvas is
     * untouched), PLACE it (move, scale, rotate, perspective, opacity) and CUT OUT parts with the
     * Eraser / Restore Brush. The document only changes at the final ✓ of Cut Out.
     * [undo]/[redo] hold earlier drafts; going back to an earlier step keeps everything.
     */
    data class InsertPicture(
        val stage: InsertStage,
        val draft: StickerObject,
        val imageW: Int,
        val imageH: Int,
        val cropRect: List<Float> = listOf(0f, 0f, 1f, 1f),
        val perspective: Boolean = false,
        val showStickers: Boolean = true,
        val erase: Boolean = true,
        /** Brush diameter as a fraction of the canvas' smaller side (as in Paint). */
        val brushSize: Float = 0.06f,
        val brushHardness: Float = 0.5f,
        val brushSquare: Boolean = false,
        val undo: List<StickerObject> = emptyList(),
        val redo: List<StickerObject> = emptyList(),
        val isNew: Boolean = true,
    ) : ToolState

    /** Brush, Marker, Eraser (make transparent) or History Paint; strokes are pending until Apply. */
    data class Draw(
        val kind: DrawKind,
        val strokes: List<Stroke> = emptyList(),
        val redo: List<Stroke> = emptyList(),
        val brush: BrushSettings = BrushSettings(),
        /** History Paint: number of document ops whose result is painted back (0 = opened picture). */
        val stage: Int = 0,
        val eyedropper: Boolean = false,
    ) : ToolState

    /** "Paint Mask / Selection": paint where the next effect applies, then pick the effect. */
    data class Mask(
        val protect: Boolean,
        val strokes: List<Stroke> = emptyList(),
        val redo: List<Stroke> = emptyList(),
        val brush: BrushSettings = BrushSettings(size = 0.08f, hardness = 0.7f),
        val erasing: Boolean = false,
        val feather: Float = 0.01f,
        val invert: Boolean = false,
        /** true once the user tapped "Next": choosing the effect. */
        val choosingEffect: Boolean = false,
    ) : ToolState
}

enum class DrawKind { BRUSH, MARKER, ERASER, HISTORY }

enum class InsertStage { CROP, PLACE, CUTOUT }

/** "Set Shape Style" (fill + outline) or "Set Line Style" (line colour, width and ends). */
enum class VectorStyleKind { SHAPE, LINE }

/** Brush settings; [size] is the diameter in u (fraction of the picture's smaller side). */
data class BrushSettings(
    val size: Float = 0.03f,
    val hardness: Float = 0.8f,
    val opacity: Float = 1f,
    val color: Int = 0xFFE53935.toInt(),
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    /** "Brush size is relative to screen": the size stays the same on screen at any zoom. */
    val relativeToScreen: Boolean = false,
)

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
    /** What the selected object's blue handle does (Scale & Rotate, Stretch, Skew, Perspective…). */
    val transformMode: TransformMode = TransformMode.SCALE_ROTATE,
    /** The object as it was when a transformation mode was chosen (for Cancel). */
    val transformStart: CanvasObject? = null,
    /** Bumped whenever project assets (fonts, imported stickers) change. */
    val assetsVersion: Int = 0,
    /** Capture date from EXIF (ms), for "Insert date and time". */
    val photoDate: Long? = null,
    /** Bumped when "My stickers" changes. */
    val libraryVersion: Int = 0,
    /** Red/green overlay showing the mask being painted. */
    val maskOverlay: ImageBitmap? = null,
) {
    /** Objects to draw: drafts of the open object tool replace (or are added to) the list. */
    /** Corner handle stretches instead of scale/rotate ("Stretch Tool"). */
    val stretchMode: Boolean get() = transformMode == TransformMode.STRETCH

    fun displayObjects(): List<CanvasObject> {
        val draft: CanvasObject = when (val t = tool) {
            is ToolState.EditText -> t.draft
            is ToolState.EditBalloon -> t.draft
            is ToolState.StyleSticker -> t.draft
            is ToolState.InsertPicture -> {
                if (t.stage == InsertStage.CROP) return objects.filter { it.id != t.draft.id }
                val others = if (t.showStickers) objects.filter { it.id != t.draft.id } else emptyList()
                return if (objects.any { it.id == t.draft.id }) {
                    if (t.showStickers) objects.map { if (it.id == t.draft.id) t.draft else it } else listOf(t.draft)
                } else others + t.draft
            }
            else -> return objects
        }
        return if (objects.any { it.id == draft.id }) objects.map { if (it.id == draft.id) draft else it } else objects + draft
    }

    val selected: CanvasObject? get() = selectedId?.let { id -> objects.firstOrNull { it.id == id } }
}

/** Share requests are one-off events delivered to the screen. */
/** [attach] = "Set picture as" (ACTION_ATTACH_DATA) instead of sharing. */
data class ShareRequest(val uri: Uri, val mimeType: String, val attach: Boolean = false, val component: android.content.ComponentName? = null)

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

/** Export menu entries (FASE1 §3.6): album, file, share via, set picture as. */
enum class ExportTarget { GALLERY, SHARE, DOCUMENT, SET_AS }

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
        lastInstance = this
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
            // the save point «Salir sin guardar» returns to (1.1.0)
            savedBundle = ProjectBundle(bundle.document, bundle.sourceBytes, bundle.masks,
                bundle.thumbnailJpeg ?: runCatching { repo.thumbFile(projectId).takeIf { it.exists() }?.readBytes() }.getOrNull(), bundle.assets)
            savedDoc = bundle.document
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
            // never replace a user's font silently: say which ones are missing
            val missing = d.objects.mapNotNull { o -> when (o) { is TextObject -> o.style.font; is BalloonObject -> o.style.font; else -> null } }
                .distinct().filter { !app.fonts.isAvailable(it, assets) }
            if (missing.isNotEmpty()) _messages.send(UiMessage.Res(R.string.fonts_missing, listOf(missing.joinToString { it.substringAfterLast('/').substringAfter(':') })))
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
            is ToolState.Draw -> if (t.strokes.isEmpty()) base else base + OpInstance(PENDING_UID, drawOpId(t), drawParams(t))
            else -> base
        }
    }

    private suspend fun renderPreview() {
        val job = coroutineContext[Job]
        val ctx = RenderContext(scale = proxyScale, isCancelled = { job?.isActive == false })
        try {
            // read before the ops: a render started at the current value contains every finished stroke
            val seq = commitSeq
            val result = renderer.render(proxy, PROXY_TOKEN, previewOps(), masks, ctx)
            val bmp = ImageIo.toBitmap(result).asImageBitmap()
            lastPreview = result
            if (seq == commitSeq) completeImage = bmp
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
        // keep an attached mask when resetting the effect's own values
        var p = t.op.params.defaults()
        for (k in listOf(MaskParams.STROKES, MaskParams.MODE, MaskParams.INVERT, MaskParams.FEATHER)) t.params.raw(k)?.let { v -> p = Params.of(p.asMap() + (k to v)) }
        _state.update { it.copy(tool = t.copy(params = p)) }
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
            is ToolState.InsertPicture -> when (t.stage) {
                InsertStage.CROP -> insertGoTo(InsertStage.PLACE)
                InsertStage.PLACE -> insertGoTo(InsertStage.CUTOUT)
                InsertStage.CUTOUT -> { clearInsertLive(); applyObjectDraft(t.draft, t.isNew, blank = false, label = "insert_picture") }
            }
            is ToolState.Draw -> {
                closeTool(render = false)
                if (t.strokes.isNotEmpty()) commit(doc.withOp(OpInstance.create(drawOpId(t), drawParams(t)), now()), drawOpId(t)) else requestRender()
            }
            is ToolState.Mask -> if (!t.choosingEffect) _state.update { it.copy(tool = t.copy(choosingEffect = true)) } else Unit
            else -> closeTool()
        }
    }

    fun cancelTool() { if (_state.value.tool is ToolState.InsertPicture) clearInsertLive(); closeTool() }

    private fun closeTool(render: Boolean = true) {
        endLive(closing = true); historySource = null
        _state.update { it.copy(tool = null, before = null, maskOverlay = null) }
        if (render) requestRender()
    }

    fun openPanel(tool: ToolState) {
        // effects such as rounded corners or "make transparent" add transparency to an opaque photo
        val alpha = tool == ToolState.Export && (lastPreview?.hasTransparency() == true)
        _state.update { it.copy(tool = tool, sourceHasAlpha = it.sourceHasAlpha || alpha) }
    }

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

    /** ↶ of the Crop bar: whole picture, no straightening, free proportions. */
    fun resetCrop() {
        val t = _state.value.tool as? ToolState.Crop ?: return
        _state.update { it.copy(tool = t.copy(rect = floatArrayOf(0f, 0f, 1f, 1f), angle = 0f, preset = Geometry.CropPreset.FREEFORM, ratio = null)) }
        requestRender()
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
        _state.update { if (it.selectedId == id) it else it.copy(selectedId = id, transformMode = TransformMode.SCALE_ROTATE, transformStart = null) }
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

    fun toggleStretchMode() = setTransformMode(if (_state.value.stretchMode) TransformMode.SCALE_ROTATE else TransformMode.STRETCH)

    /** Chooses what the blue handle does; remembers the object for Cancel. */
    fun setTransformMode(m: TransformMode) {
        _state.update { s ->
            val sel = s.selectedId?.let { doc.objectById(it) }
            if (m == TransformMode.SCALE_ROTATE && s.transformStart == null) s.copy(transformMode = m)
            else s.copy(transformMode = m, transformStart = s.transformStart ?: sel)
        }
    }

    /** ✓ of the transformation bar: keep the result (each drag is already one undo step). */
    fun finishTransform() { _state.update { it.copy(transformMode = TransformMode.SCALE_ROTATE, transformStart = null) } }

    /** ✕ of the transformation bar: back to the object as it was when the mode was chosen. */
    fun cancelTransform() {
        val start = _state.value.transformStart
        finishTransform()
        if (start != null && doc.objectById(start.id) != null && doc.objectById(start.id) != start) commit(doc.updateObject(start, now()), "transform_cancel")
    }

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

    fun newText(text: String = "", style: TextStyle = TextStyle()) {
        val draft = TextObject(newObjectId(), center(), text, style)
        _state.update { it.copy(tool = ToolState.EditText(draft, true), selectedId = null) }
    }

    fun newBalloon(shape: BalloonShape = BalloonShape.SPEECH) {
        val draft = BalloonObject(newObjectId(), ObjectTransform(0.5f, 0.35f), shape)
        _state.update { it.copy(tool = ToolState.EditBalloon(draft, true), selectedId = null) }
    }

    fun openStickerPicker(replace: Boolean = false, tab: Int = 1) {
        _state.update { it.copy(tool = ToolState.PickSticker(if (replace) it.selectedId else null, tab)) }
    }

    /** Opens the editor that fits the selected object. */
    fun editSelected() {
        when (val o = selected()) {
            is TextObject -> _state.update { it.copy(tool = ToolState.EditText(o, false)) }
            is BalloonObject -> _state.update { it.copy(tool = ToolState.EditBalloon(o, false)) }
            // an inserted photo re-opens Insert Picture (crop, place and cut-out stay editable)
            is StickerObject -> if (o.source.contains("stickers/insert_")) editPicture(o) else _state.update { it.copy(tool = ToolState.StyleSticker(o)) }
            null -> Unit
        }
    }

    /** Opens Set Shape Style / Set Line Style for the selected vector sticker (SVG or built-in vector). */
    fun openVectorStyle(kind: VectorStyleKind) {
        val o = selected() as? StickerObject ?: return
        if (!o.isRestylable) return
        _state.update { it.copy(tool = ToolState.StyleSticker(o, kind)) }
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

    /**
     * Places a sticker of the external library. The picture is copied into the project (like
     * "My stickers"), so the project keeps working even if the library changes later.
     */
    fun placePackSticker(s: app.bocadillo.core.model.LibrarySticker) {
        val replaceId = (_state.value.tool as? ToolState.PickSticker)?.replaceId
        closeTool(render = false)
        viewModelScope.launch {
            val (bytes, aspect) = withContext(Dispatchers.IO) {
                runCatching { app.stickerPacks.file(s).readBytes() }.getOrNull() to app.stickerPacks.aspect(s)
            }
            if (bytes == null) { _messages.send(UiMessage.Res(R.string.error_import)); return@launch }
            val path = "stickers/${s.file}"
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

    /** Current preview (for the menu thumbnails). */
    fun previewBitmap(): app.bocadillo.core.imaging.PixelBuffer? = lastPreview

    /** True when the edited picture has transparent areas (Add Background Color needs it). */
    fun currentHasTransparency(): Boolean = lastPreview?.hasTransparency() == true

    /** GPS position stored in the photo's EXIF data (Picture Location), or null. */
    suspend fun pictureLocation(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(sourceBytes))
            exif.latLong?.let { it[0] to it[1] }
        }.getOrNull()
    }

    /**
     * Insert Picture: adds a photo from the gallery as a movable object (stored inside the
     * project, not in My Stickers). Transparency of PNG/WebP is kept.
     */
    fun insertPicture(uri: Uri) {
        viewModelScope.launch {
            try {
                val (bytes, ext, size) = withContext(Dispatchers.IO) {
                    val imported = ImageIo.read(app.contentResolver, uri)
                    val dec = ImageIo.decode(imported.bytes, maxSide = 2048)
                    val bmp = dec.bitmap
                    val alpha = bmp.hasAlpha()
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(if (alpha) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                    val wh = bmp.width to bmp.height
                    bmp.recycle()
                    Triple(out.toByteArray(), if (alpha) "png" else "jpg", wh)
                }
                val path = "stickers/insert_${java.util.UUID.randomUUID()}.$ext"
                assets[path] = bytes
                bumpAssets()
                // step 1: crop the picture first; nothing enters the document before Cut Out's ✓
                val o = StickerObject(newObjectId(), center(), "asset:$path", size = 0.6f, aspect = size.first.toFloat() / size.second)
                insertRaw = null
                _state.update { it.copy(tool = ToolState.InsertPicture(InsertStage.CROP, o, size.first, size.second), selectedId = null) }
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.error_import))
            }
        }
    }

    /** Re-opens Insert Picture (Place step) on an inserted picture: its crop and cut-out stay editable. */
    fun editPicture(o: StickerObject) {
        val bytes = assets[o.source.removePrefix("asset:")] ?: return
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        insertRaw = null
        _state.update {
            it.copy(tool = ToolState.InsertPicture(InsertStage.PLACE, o, opts.outWidth, opts.outHeight,
                cropRect = o.crop.takeIf { c -> c.size == 4 } ?: listOf(0f, 0f, 1f, 1f), isNew = false), selectedId = null)
        }
    }

    private fun insertTool() = _state.value.tool as? ToolState.InsertPicture

    private fun updateInsert(f: (ToolState.InsertPicture) -> ToolState.InsertPicture) {
        _state.update { s -> (s.tool as? ToolState.InsertPicture)?.let { s.copy(tool = f(it)) } ?: s }
    }

    /** New draft as one undoable step of the Insert Picture tool. */
    private fun pushInsert(next: StickerObject) = updateInsert { t ->
        if (next == t.draft) t else t.copy(draft = next, undo = (t.undo + t.draft).takeLast(60), redo = emptyList())
    }

    fun insertUndo() = updateInsert { t ->
        if (t.undo.isEmpty()) t else t.copy(draft = t.undo.last(), undo = t.undo.dropLast(1), redo = t.redo + t.draft)
    }.also { refreshInsertLive() }

    fun insertRedo() = updateInsert { t ->
        if (t.redo.isEmpty()) t else t.copy(draft = t.redo.last(), redo = t.redo.dropLast(1), undo = t.undo + t.draft)
    }.also { refreshInsertLive() }

    /** Step 1: the crop rectangle on the picked image (fractions). */
    fun setInsertCrop(rect: FloatArray) = updateInsert { t ->
        val r = listOf(rect[0].coerceIn(0f, 1f), rect[1].coerceIn(0f, 1f), rect[2].coerceIn(0f, 1f), rect[3].coerceIn(0f, 1f))
        if (r[2] - r[0] < 0.02f || r[3] - r[1] < 0.02f) t else t.copy(cropRect = r)
    }

    fun resetInsertCrop() = updateInsert { it.copy(cropRect = listOf(0f, 0f, 1f, 1f)) }

    /** Moves between the steps; leaving Crop applies the crop to the draft (aspect follows it). */
    fun insertGoTo(stage: InsertStage) {
        val t = insertTool() ?: return
        var d = t.draft
        if (t.stage == InsertStage.CROP && stage != InsertStage.CROP) {
            val r = t.cropRect
            val full = r[0] <= 0f && r[1] <= 0f && r[2] >= 1f && r[3] >= 1f
            val px = Cutout.cropRect(r, t.imageW, t.imageH)
            val newCrop = if (full) emptyList() else r
            // a different crop invalidates a cut-out made on the old one
            d = d.copy(crop = newCrop, aspect = px[2].toFloat() / px[3], cutout = if (newCrop == d.crop) d.cutout else emptyList())
        }
        updateInsert { it.copy(stage = stage, draft = d, undo = if (d != t.draft) it.undo + t.draft else it.undo) }
        refreshInsertLive()
    }

    /** Place step, during a drag (no undo step yet). */
    fun insertLive(o: CanvasObject) = updateInsert { t ->
        if (o is StickerObject && o.id == t.draft.id) { if (insertDragStart == null) insertDragStart = t.draft; t.copy(draft = o) } else t
    }

    private var insertDragStart: StickerObject? = null
    fun insertDragBegin() { insertDragStart = insertTool()?.draft }
    fun insertDragEnd() {
        val start = insertDragStart ?: return
        insertDragStart = null
        updateInsert { t -> if (t.draft == start) t else t.copy(undo = (t.undo + start).takeLast(60), redo = emptyList()) }
    }

    /** Box of the draft in picture pixels at stretch 1 (for Stretch to Fit / Scale to Fill). */
    private fun draftBox(d: StickerObject): Pair<Float, Float> {
        val (w, h) = outputSize(doc.ops)
        val longest = d.size * minOf(w, h)
        return if (d.aspect >= 1f) longest to longest / d.aspect else longest * d.aspect to longest
    }

    /** Stretch to Fit: the picture covers the whole canvas exactly (proportions not kept). */
    fun stretchToFit() {
        val t = insertTool() ?: return
        val (w, h) = outputSize(doc.ops)
        val (bw, bh) = draftBox(t.draft)
        pushInsert(t.draft.copy(transform = ObjectTransform(0.5f, 0.5f, scale = 1f, stretchX = w / bw, stretchY = h / bh, flipH = t.draft.transform.flipH)))
        updateInsert { it.copy(perspective = false) }
    }

    /** Scale to Fill: same proportions, scaled until the canvas is fully covered. */
    fun scaleToFill() {
        val t = insertTool() ?: return
        val (w, h) = outputSize(doc.ops)
        val (bw, bh) = draftBox(t.draft)
        pushInsert(t.draft.copy(transform = ObjectTransform(0.5f, 0.5f, scale = maxOf(w / bw, h / bh), flipH = t.draft.transform.flipH)))
        updateInsert { it.copy(perspective = false) }
    }

    fun insertMirror() { val t = insertTool() ?: return; pushInsert(t.draft.copy(transform = t.draft.transform.copy(flipH = !t.draft.transform.flipH))); refreshInsertLive() }

    /** ▶|◀ and "Perspective Tool": blue circles (move/scale/rotate) ↔ blue diamonds (four corners). */
    fun setInsertPerspective(on: Boolean) = updateInsert { it.copy(perspective = on) }

    /** Tap on the bottom-right handle of Place Picture: size mode ↔ perspective mode. The picture is not changed. */
    fun toggleInsertPerspective() = updateInsert { if (it.stage == InsertStage.PLACE) it.copy(perspective = !it.perspective) else it }

    fun toggleInsertStickers() = updateInsert { it.copy(showStickers = !it.showStickers) }

    /** Bottom slider of Place Picture: the picture's opacity ("Place Picture 100%"). */
    fun setInsertOpacity(v: Float, commit: Boolean) {
        val t = insertTool() ?: return
        if (!commit) { updateInsert { it.copy(draft = it.draft.copy(opacity = v.coerceIn(0f, 1f))) }; return }
        insertDragEnd()
    }

    fun setCutBrush(size: Float = insertTool()?.brushSize ?: 0.06f, hardness: Float = insertTool()?.brushHardness ?: 0.5f, square: Boolean = insertTool()?.brushSquare ?: false) =
        updateInsert { it.copy(brushSize = size.coerceIn(0.004f, 0.3f), brushHardness = hardness.coerceIn(0f, 1f), brushSquare = square) }

    fun setCutErase(erase: Boolean) = updateInsert { it.copy(erase = erase) }

    // ---- Cut Out: live mask (1.1.0, correction 3)
    // 1.0 re-cropped, re-scaled and re-masked the whole picture with every stroke on each finger
    // move. Now a session keeps the cropped pixels, the settled mask and the result; a stroke in
    // progress only recomputes its own area (IncrementalStroke, same numbers as the export) on the
    // main thread, and only the touched tiles are uploaded.
    private var insertRaw: android.graphics.Bitmap? = null
    private val _insertLive = MutableStateFlow<Pair<String, android.graphics.Bitmap>?>(null)
    /** Processed picture of the draft (crop + settled mask) for the screen. */
    val insertLive: StateFlow<Pair<String, android.graphics.Bitmap>?> = _insertLive
    private var liveJob: Job? = null
    private val liveRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private class CutSession(val id: String, val crop: List<Float>, var cutout: List<Float>, val w: Int, val h: Int, val src: IntArray, val m: FloatArray, val out: IntArray) {
        val inc = IncrementalStroke(w, h)
        val tiles = LiveTiles(w, h)
        /** A finger is erasing / restoring (main thread). */
        var active = false
    }
    private var cut: CutSession? = null
    private var cutRefreshPending = false
    private val _cutTiles = MutableStateFlow<LiveTiles?>(null)
    /** Tiles of the stroke in progress (and the strokes since the last full refresh) over [insertLive]. */
    val cutTiles: StateFlow<LiveTiles?> = _cutTiles

    private fun rawPicture(d: StickerObject): android.graphics.Bitmap? {
        insertRaw?.let { return it }
        val bytes = assets[d.source.removePrefix("asset:")] ?: return null
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { insertRaw = it }
    }

    private fun ensureLiveWorker() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            for (r in liveRequests) {
                val t = insertTool() ?: continue
                val d = t.draft
                val built = withContext(Dispatchers.Default) {
                    runCatching {
                        val raw = rawPicture(d) ?: return@runCatching null
                        val rc = Cutout.cropRect(d.crop, raw.width, raw.height)
                        val cropped = if (rc[0] == 0 && rc[1] == 0 && rc[2] == raw.width && rc[3] == raw.height) raw else android.graphics.Bitmap.createBitmap(raw, rc[0], rc[1], rc[2], rc[3])
                        val (w, h) = Cutout.rasterSize(cropped.width, cropped.height)
                        val scaled = if (w == cropped.width && h == cropped.height) cropped else android.graphics.Bitmap.createScaledBitmap(cropped, w, h, true)
                        val src = IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
                        val m = Cutout.mask(Cutout.strokes(d.cutout), w, h)
                        val out = IntArray(w * h) { i -> Cutout.pixel(src[i], m[i]) }
                        CutSession(d.id, d.crop, d.cutout, w, h, src, m, out) to android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    }.getOrNull()
                } ?: continue
                if (cut?.active == true || cutPump.pending) { cutRefreshPending = true; continue } // never under a finger
                val now = insertTool()?.draft ?: continue
                if (now.id != d.id || now.crop != d.crop || now.cutout != d.cutout) { liveRequests.trySend(Unit); continue }
                cut = built.first
                _insertLive.value = d.id to built.second
                _cutTiles.value = built.first.tiles
            }
        }
    }

    private fun refreshInsertLive() {
        val t = insertTool() ?: return
        if (t.draft.cutout.isEmpty() && t.stage != InsertStage.CUTOUT) { _insertLive.value = null; _cutTiles.value = null; cut = null; return }
        ensureLiveWorker(); liveRequests.trySend(Unit)
    }

    private fun clearInsertLive() { _insertLive.value = null; _cutTiles.value = null; cut = null; insertRaw = null }

    private fun cutSession(t: ToolState.InsertPicture): CutSession? =
        cut?.takeIf { it.id == t.draft.id && it.crop == t.draft.crop && it.cutout == t.draft.cutout }

    /** Stroke in progress (image coordinates): the screen shows its real effect at once. Main thread. */
    fun cutStrokeLive(points: FloatArray, imageSize: Float) {
        val t = insertTool() ?: return
        val c = cutSession(t) ?: run { refreshInsertLive(); return }
        if (points.isEmpty()) { // two fingers: the stroke is dropped
            if (!c.active) return
            c.active = false
            cutPump.submit(true) {
                val a = c.inc.touchedArea()
                if (a != null) for (y in a[1] until a[3]) for (x in a[0] until a[2]) { val i = y * c.w + x; c.out[i] = Cutout.pixel(c.src[i], c.m[i]) }
                c.inc.reset()
                ({ if (a != null) c.tiles.update(c.out, a) })
            }
            return
        }
        c.active = true
        val s = cutStroke(t, points, imageSize)
        cutPump.submit(false) { cutCompute(c, s, final = false) }
    }

    /** Grows the Cut Out stroke and composites the changed areas (any thread, one at a time). */
    private fun cutCompute(c: CutSession, s: Stroke, final: Boolean): (() -> Unit)? {
        var areas: List<IntArray> = emptyList()
        if (c.inc.update(s, final) != null) {
            val src = c.src; val m = c.m; val out = c.out
            for (d in c.inc.changedAreas) c.inc.forEachIn(d) { i, cv -> out[i] = if (cv <= 0f) Cutout.pixel(src[i], m[i]) else Cutout.pixel(src[i], Cutout.liveMask(m[i], cv, s)) }
            areas = c.inc.changedAreas.map { it.copyOf() }
        }
        if (final) {
            c.inc.touchedArea()?.let { a ->
                for (y in a[1] until a[3]) for (x in a[0] until a[2]) { val i = y * c.w + x; c.m[i] = Cutout.liveMask(c.m[i], c.inc.coverageAt(x, y), s) }
            }
            c.inc.reset()
        }
        return { for (d in areas) c.tiles.update(c.out, d) }
    }

    /** Test hook: the processed picture as shown (raster size). */
    fun cutSnapshot(): Triple<Int, Int, IntArray>? = cut?.let { Triple(it.w, it.h, it.out.copyOf()) }

    /** The picked picture, whole (for the Crop step). */
    suspend fun insertSourceBitmap(): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.Default) {
        val t = insertTool() ?: return@withContext null
        val bytes = assets[t.draft.source.removePrefix("asset:")] ?: return@withContext null
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /** End of a stroke: one undo step; the mask stays editable in the project. */
    fun cutStrokeCommit(points: FloatArray, imageSize: Float) {
        val t = insertTool() ?: return
        val s = cutStroke(t, points, imageSize)
        val c = cutSession(t)
        c?.let { cs -> cs.active = false; cutPump.submit(true) { cutCompute(cs, s, final = true) } }
        val cutout = Cutout.encode(Cutout.strokes(t.draft.cutout) + s)
        pushInsert(t.draft.copy(cutout = cutout))
        // the session already contains the stroke: no full recomputation
        if (c != null) c.cutout = cutout
        if (c == null || cutRefreshPending) { cutRefreshPending = false; refreshInsertLive() }
    }

    /** Removes the whole cut-out (one undo step). */
    fun resetCutout() { val t = insertTool() ?: return; pushInsert(t.draft.copy(cutout = emptyList())); refreshInsertLive() }

    private fun cutStroke(t: ToolState.InsertPicture, points: FloatArray, imageSize: Float) =
        if (t.erase) Cutout.eraser(points, imageSize, t.brushHardness, t.brushSquare)
        else Cutout.restore(points, imageSize, t.brushHardness, t.brushSquare)

    /** Last sticker-library import result (shown in a dialog). */
    private val _stickerImport = MutableStateFlow<Pair<app.bocadillo.data.StickerPacks.Report?, String?>?>(null)
    val stickerImport: StateFlow<Pair<app.bocadillo.data.StickerPacks.Report?, String?>?> = _stickerImport
    private val _importingStickers = MutableStateFlow(false)
    val importingStickers: StateFlow<Boolean> = _importingStickers

    /** Imports a sticker-library ZIP (manifest.json, SQLite catalogue or folders). */
    fun importStickerZip(uri: Uri) {
        if (_importingStickers.value) return
        _importingStickers.value = true
        viewModelScope.launch {
            _stickerImport.value = try {
                app.stickerPacks.importZip(app.contentResolver, uri) to null
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            } finally { _importingStickers.value = false }
        }
    }

    fun dismissStickerImport() { _stickerImport.value = null }

    fun deletePackSticker(s: app.bocadillo.core.model.LibrarySticker) = viewModelScope.launch { app.stickerPacks.delete(s.id) }

    /** Shows a category of the sticker menu (null = the category list). */
    fun showStickerCategory(id: String?) {
        _state.update { st -> (st.tool as? ToolState.PickSticker)?.let { st.copy(tool = it.copy(category = id)) } ?: st }
    }

    fun deleteLibrarySticker(file: java.io.File) {
        app.stickerLibrary.delete(file)
        _state.update { it.copy(libraryVersion = it.libraryVersion + 1) }
    }

    /** Places (or replaces the selected sticker with) a Noto Emoji sticker. */
    fun pickNoto(id: String) {
        val replaceId = (_state.value.tool as? ToolState.PickSticker)?.replaceId
        closeTool(render = false)
        viewModelScope.launch {
            val aspect = withContext(Dispatchers.Default) { app.noto.aspect(id) }
            val replace = replaceId?.let { doc.objectById(it) as? StickerObject }
            if (replace != null) {
                commit(doc.updateObject(replace.copy(source = "noto:$id", aspect = aspect, colors = emptyList()), now()), "replace")
            } else {
                val o = StickerObject(newObjectId(), center(), "noto:$id", aspect = aspect)
                _state.update { it.copy(selectedId = o.id) }
                commit(doc.addObject(o, now()), "sticker")
            }
        }
    }

    /** Imports a TTF/OTF into the font library. Returns the font reference via [onDone]. */
    private val _fontReport = MutableStateFlow<FontRepository.ImportReport?>(null)
    val fontReport: StateFlow<FontRepository.ImportReport?> = _fontReport
    fun dismissFontReport() { _fontReport.value = null }

    /** Imports .ttf / .otf files and .zip archives; shows a report; applies the first font. */
    private val _fontImport = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Font import in progress (done, total) — shown without blocking the screen (1.2.0, decision 10). */
    val fontImport: StateFlow<Pair<Int, Int>?> = _fontImport

    fun importFonts(uris: List<Uri>, onDone: (String) -> Unit) {
        if (_fontImport.value != null) return
        _fontImport.value = 0 to uris.size
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    // each file is streamed when its turn comes (a big ZIP is never read twice)
                    val sources = uris.map { u -> FontRepository.Source(ImageIo.displayName(app.contentResolver, u)) { app.contentResolver.openInputStream(u) ?: throw java.io.IOException("cannot open") } }
                    app.fonts.importSources(sources) { d, t -> _fontImport.value = d to maxOf(t, d) }
                }
                report.firstRef?.let { ref -> app.fonts.libraryBytes(ref)?.let { assets[ref.removePrefix("asset:")] = it } }
                bumpAssets()
                _fontReport.value = report
                report.firstRef?.takeIf { report.added.isNotEmpty() }?.let(onDone)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.font_import_failed))
            } finally {
                _fontImport.value = null
            }
        }
    }

    fun deleteFont(ref: String) { app.fonts.delete(ref); app.settings.forgetFont(ref); bumpAssets() }

    /** Titles of the saved projects whose texts use font [ref] (reads only each project's document). */
    suspend fun projectsUsingFont(ref: String): List<String> = withContext(Dispatchers.IO) { app.projects.projectsUsingFont(ref) }

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

    // ------------------------------------------------------------------ drawing

    private fun drawOpId(t: ToolState.Draw) = if (t.kind == DrawKind.HISTORY) PaintOps.HistoryPaint.id else PaintOps.Paint.id

    private fun drawParams(t: ToolState.Draw): Params {
        val p = Params.EMPTY.with("strokes", StrokeCodec.encode(t.strokes))
        return if (t.kind == DrawKind.HISTORY) p.with("stage", t.stage) else p
    }

    fun openDraw(kind: DrawKind) {
        val previous = (_state.value.tool as? ToolState.Draw)?.brush ?: lastBrush
        _state.update { it.copy(tool = ToolState.Draw(kind, brush = previous, stage = historyStages().lastOrNull()?.first ?: 0), selectedId = null) }
        (_state.value.tool as? ToolState.Draw)?.let { if (it.kind == DrawKind.HISTORY) ensureHistorySource(it.stage) }
        requestRender()
    }

    private var lastBrush = BrushSettings()

    fun setDrawKind(kind: DrawKind) {
        val t = _state.value.tool as? ToolState.Draw ?: return
        if (t.strokes.isNotEmpty() && (kind == DrawKind.HISTORY) != (t.kind == DrawKind.HISTORY)) {
            // paint and history-paint are different operations: apply what was drawn first
            applyTool(); openDraw(kind); return
        }
        _state.update { it.copy(tool = t.copy(kind = kind, eyedropper = false)) }
        if (kind == DrawKind.HISTORY) ensureHistorySource(t.stage)
    }

    fun setBrush(b: BrushSettings) {
        lastBrush = b
        _state.update { s ->
            when (val t = s.tool) {
                is ToolState.Draw -> s.copy(tool = t.copy(brush = b))
                is ToolState.Mask -> s.copy(tool = t.copy(brush = b))
                else -> s
            }
        }
    }

    /** Adds a finished stroke (normalized points x,y,pressure) from the canvas. */
    /** The stroke exactly as it will be applied (shared by the live preview and Apply). */
    private fun drawStroke(t: ToolState.Draw, points: FloatArray, zoom: Float): Stroke {
        val b = t.brush.let { if (it.relativeToScreen && zoom > 0f) it.copy(size = (it.size / zoom).coerceAtLeast(0.0005f)) else it }
        val mode = when (t.kind) { DrawKind.MARKER -> StrokeMode.MARKER; DrawKind.ERASER -> StrokeMode.ERASE; else -> StrokeMode.PAINT }
        val color = if (t.kind == DrawKind.MARKER) (b.color and 0x00FFFFFF) or 0xFF000000.toInt() else b.color
        val opacity = if (t.kind == DrawKind.MARKER) b.opacity * 0.6f else b.opacity
        return Stroke(points, b.size, b.hardness, opacity, color, mode, b.pressureSize, b.pressureOpacity)
    }

    private fun maskStroke(t: ToolState.Mask, points: FloatArray): Stroke {
        val b = t.brush
        return Stroke(points, b.size, b.hardness, 1f, 0xFFFFFFFF.toInt(), if (t.erasing) StrokeMode.ERASE else StrokeMode.PAINT, b.pressureSize, false)
    }

    fun addStroke(points: FloatArray, zoom: Float = 1f) {
        when (val t = _state.value.tool) {
            is ToolState.Draw -> {
                val s = drawStroke(t, points, zoom)
                // the live view settles this stroke with the same numbers, then the render confirms it
                if (!t.eyedropper) liveSession()?.let { l ->
                    l.active = false
                    val history = historyFor(t, l)
                    drawPump.submit(true) { liveCompute(l, s, true, history, t.kind == DrawKind.HISTORY) }
                }
                _state.update { it.copy(tool = t.copy(strokes = t.strokes + s, redo = emptyList())) }
                commitSeq++ // after the state: a render that sees the new value has the stroke
                requestRender()
            }
            is ToolState.Mask -> {
                val s = maskStroke(t, points)
                maskLive(s, final = true)
                val settled = maskSession?.takeIf { it.key == maskKey(t) }
                _state.update { it.copy(tool = t.copy(strokes = t.strokes + s, redo = emptyList())) }
                // the session already contains the stroke: only its key moves on
                val nt = _state.value.tool as ToolState.Mask
                if (settled != null) settled.key = maskKey(nt) else updateMaskOverlay()
            }
            else -> Unit
        }
    }

    // ---- live strokes (1.1.0, correction 1)
    // 1.0 repainted the whole stroke on a full copy of the picture and uploaded the whole picture
    // on every finger move, on a worker thread: the cost grew with the stroke and the result
    // arrived frames late. Now the stroke grows incrementally (IncrementalStroke: only the new
    // segment, same numbers as the final render), on the main thread inside the touch event, and
    // only the 256-px tiles it touches are uploaded (LiveTiles), so it shows in the same frame.
    private class LiveSession(val w: Int, val h: Int, val work: IntArray, val out: IntArray) {
        val inc = IncrementalStroke(w, h)
        val tiles = LiveTiles(w, h)
        /** A finger is drawing (main thread). */
        var active = false
        var blender: StrokePainter.Blender? = null
        var closing = false
    }
    private var live: LiveSession? = null
    private val _liveTiles = MutableStateFlow<LiveTiles?>(null)
    /** Tiles drawn over the picture while strokes are not yet in the rendered preview. */
    val liveTiles: StateFlow<LiveTiles?> = _liveTiles
    /** Increments when the strokes change; a render that started at the current value contains them all. */
    @Volatile private var commitSeq = 0
    @Volatile private var completeImage: ImageBitmap? = null
    private var historySource: Pair<Int, PixelBuffer>? = null
    private var historyJob: Job? = null
    /** Timing of the live stroke work per finger move (ms), for the performance test and report. */
    val liveTimings = java.util.concurrent.ConcurrentLinkedQueue<Float>()

    private fun liveSession(): LiveSession? {
        val p = lastPreview ?: return null
        live?.let { if (it.w == p.width && it.h == p.height && !it.closing) return it }
        val work = p.pixels.copyOf()
        val l = LiveSession(p.width, p.height, work, work.copyOf())
        live?.tiles?.clear()
        live = l
        _liveTiles.value = l.tiles
        return l
    }

    /** Runs the live work: in the touch event while cheap, in the background when expensive. */
    private val drawPump = LivePump().also { it.onTiming = ::recordTiming }
    private val cutPump = LivePump().also { it.onTiming = ::recordTiming }
    private val maskPump = LivePump().also { it.onTiming = ::recordTiming }
    /** How many live updates ran in the background (expensive ones), for the report. */
    @Volatile var liveBackgroundCount = 0
    private fun recordTiming(ms: Float, background: Boolean) {
        liveTimings.add(ms); if (background) liveBackgroundCount++
        while (liveTimings.size > 4000) liveTimings.poll()
    }
    private var lastShownPreview: ImageBitmap? = null

    /** Stroke in progress (one finger moving). Main thread. */
    fun strokeLive(points: FloatArray, zoom: Float = 1f) {
        when (val t = _state.value.tool) {
            is ToolState.Draw -> {
                if (t.eyedropper) return
                val l = liveSession() ?: return
                if (points.isEmpty()) { if (l.active) { l.active = false; drawPump.submit(true) { cancelLiveStroke(l) } }; return }
                val s = drawStroke(t, points, zoom)
                val history = historyFor(t, l)
                if (t.kind == DrawKind.HISTORY && history == null) { ensureHistorySource(t.stage); return }
                l.active = true
                drawPump.submit(false) { liveCompute(l, s, false, history, t.kind == DrawKind.HISTORY) }
            }
            is ToolState.Mask -> maskLive(if (points.isEmpty()) null else maskStroke(t, points), final = false)
            else -> Unit
        }
    }

    private fun historyFor(t: ToolState.Draw, l: LiveSession): PixelBuffer? =
        if (t.kind == DrawKind.HISTORY) historySource?.takeIf { it.first == t.stage && it.second.width == l.w && it.second.height == l.h }?.second else null

    /**
     * Grows the stroke and composites only the changed areas into [LiveSession.out] (any thread,
     * one at a time). Returns the tile upload to do on the main thread.
     */
    private fun liveCompute(l: LiveSession, s: Stroke, final: Boolean, history: PixelBuffer?, historyKind: Boolean): (() -> Unit)? {
        if (historyKind && history == null) { if (final) { l.inc.reset(); l.blender = null }; return null }
        val b = l.blender ?: StrokePainter.Blender(s).also { l.blender = it }
        var areas: List<IntArray> = emptyList()
        if (l.inc.update(s, final) != null) {
            val work = l.work; val out = l.out
            val hp = history?.pixels
            // only the areas that changed (new settled dabs, old and new tail), each once
            for (d in l.inc.changedAreas) {
                l.inc.forEachIn(d) { i, c ->
                    out[i] = when {
                        c <= 0f -> work[i]
                        hp != null -> {
                            val k = (c * s.opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
                            if (k == 0) work[i] else if (k == 255) hp[i] else PaintOps.lerp(work[i], hp[i], k)
                        }
                        else -> b.blend(work[i], c)
                    }
                }
            }
            areas = l.inc.changedAreas.map { it.copyOf() }
        }
        if (final) {
            // the finished stroke becomes the base of the next one
            l.inc.touchedArea()?.let { a -> for (y in a[1] until a[3]) System.arraycopy(l.out, y * l.w + a[0], l.work, y * l.w + a[0], a[2] - a[0]) }
            l.inc.reset(); l.blender = null
        }
        return {
            for (d in areas) l.tiles.update(l.out, d)
            if (final) previewShown(lastShownPreview) // a render may have arrived meanwhile
        }
    }

    /** Test hooks: the picture shown under the finger (preview size) and whether tiles are on screen. */
    fun liveSnapshot(): Pair<Int, IntArray>? = live?.let { it.w to it.out.copyOf() }
    fun liveTilesShown(): Boolean = live?.tiles?.isEmpty == false
    fun livePending(): Boolean = drawPump.pending || cutPump.pending || maskPump.pending

    /** Two fingers went down: the stroke in progress is dropped. */
    private fun cancelLiveStroke(l: LiveSession): (() -> Unit)? {
        val a = l.inc.touchedArea()
        if (a != null) for (y in a[1] until a[3]) System.arraycopy(l.work, y * l.w + a[0], l.out, y * l.w + a[0], a[2] - a[0])
        l.inc.reset(); l.blender = null
        return { if (a != null) l.tiles.update(l.out, a) }
    }

    /** The picture of History Paint's stage, rendered once in the background. */
    private fun ensureHistorySource(stage: Int) {
        if (historySource?.first == stage || historyJob?.isActive == true) return
        historyJob = viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { renderer.render(proxy, PROXY_TOKEN, doc.ops.take(stage), masks, RenderContext(scale = proxyScale)) }
            historySource = stage to r
        }
    }

    /**
     * The editor shows [img] as the picture. Once it is a render containing every finished stroke,
     * the live tiles are no longer needed: removed now, after that picture is on screen, so
     * nothing flickers.
     */
    fun previewShown(img: ImageBitmap?) {
        lastShownPreview = img
        val l = live ?: return
        if (img == null || img !== completeImage || l.active || drawPump.pending) return
        l.tiles.clear()
        val p = lastPreview
        if (l.closing || p == null || p.width != l.w || p.height != l.h) { live = null; _liveTiles.value = null; return }
        System.arraycopy(p.pixels, 0, l.work, 0, l.work.size)
        System.arraycopy(p.pixels, 0, l.out, 0, l.out.size)
    }

    /** Tool closed, or strokes removed: the tiles go when the next complete render is shown. */
    private fun endLive(closing: Boolean) {
        commitSeq++
        live?.let { if (closing) it.closing = true }
        maskLiveReset()
    }

    fun undoStroke() {
        _state.update { s ->
            when (val t = s.tool) {
                is ToolState.Draw -> if (t.strokes.isEmpty()) s else s.copy(tool = t.copy(strokes = t.strokes.dropLast(1), redo = t.redo + t.strokes.last()))
                is ToolState.Mask -> if (t.strokes.isEmpty()) s else s.copy(tool = t.copy(strokes = t.strokes.dropLast(1), redo = t.redo + t.strokes.last()))
                else -> s
            }
        }
        endLive(closing = false)
        if (_state.value.tool is ToolState.Mask) updateMaskOverlay() else requestRender()
    }

    fun redoStroke() {
        _state.update { s ->
            when (val t = s.tool) {
                is ToolState.Draw -> if (t.redo.isEmpty()) s else s.copy(tool = t.copy(strokes = t.strokes + t.redo.last(), redo = t.redo.dropLast(1)))
                is ToolState.Mask -> if (t.redo.isEmpty()) s else s.copy(tool = t.copy(strokes = t.strokes + t.redo.last(), redo = t.redo.dropLast(1)))
                else -> s
            }
        }
        endLive(closing = false)
        if (_state.value.tool is ToolState.Mask) updateMaskOverlay() else requestRender()
    }

    fun setEyedropper(on: Boolean) {
        val t = _state.value.tool as? ToolState.Draw ?: return
        _state.update { it.copy(tool = t.copy(eyedropper = on)) }
    }

    /** Eyedropper: samples the preview at normalized (x, y). */
    fun sampleColor(x: Float, y: Float) {
        val t = _state.value.tool as? ToolState.Draw ?: return
        val p = lastPreview ?: return
        val px = (x * p.width).toInt().coerceIn(0, p.width - 1); val py = (y * p.height).toInt().coerceIn(0, p.height - 1)
        val c = p.pixels[py * p.width + px] or 0xFF000000.toInt()
        lastBrush = t.brush.copy(color = c)
        _state.update { it.copy(tool = t.copy(brush = lastBrush, eyedropper = false)) }
    }

    /**
     * Earlier stages available to History Paint: (number of ops, label of the last op), only
     * those with the same size as the current picture (as in the original).
     */
    fun historyStages(): List<Pair<Int, String>> {
        val ops = doc.ops
        val target = outputSize(ops)
        return (0 until ops.size).filter { k -> outputSize(ops.subList(0, k)) == target }
            .map { k -> k to (if (k == 0) "opened" else ops[k - 1].opId) }
            .reversed()
    }

    fun setHistoryStage(stage: Int) {
        val t = _state.value.tool as? ToolState.Draw ?: return
        _state.update { it.copy(tool = t.copy(stage = stage)) }
        requestRender()
        ensureHistorySource(stage)
    }

    // ------------------------------------------------------------------ masks

    fun openMask(protect: Boolean) {
        _state.update { it.copy(tool = ToolState.Mask(protect), selectedId = null) }
        updateMaskOverlay()
    }

    fun updateMask(transform: (ToolState.Mask) -> ToolState.Mask) {
        val t = _state.value.tool as? ToolState.Mask ?: return
        _state.update { it.copy(tool = transform(t)) }
        updateMaskOverlay()
    }

    private var overlayJob: Job? = null

    /** Overlay colour of a mask value (protected: red, selected: green; 55 % at full mask). */
    private fun overlayPixel(v0: Float, t: ToolState.Mask): Int {
        val v = if (t.invert) 1f - v0 else v0
        return (((v * 0.55f) * 255).toInt().coerceIn(0, 255) shl 24) or (if (t.protect) 0xE53935 else 0x43A047)
    }

    /** Settled mask (unfeathered) and its overlay, so a new stroke only recomputes its own area. */
    private class MaskSession(val w: Int, val h: Int, val m: FloatArray, val overlay: IntArray, var key: List<Any>) {
        val inc = IncrementalStroke(w, h)
        val tiles = LiveTiles(w, h)
    }
    private var maskSession: MaskSession? = null
    private val _maskTiles = MutableStateFlow<LiveTiles?>(null)
    val maskTiles: StateFlow<LiveTiles?> = _maskTiles
    private fun maskKey(t: ToolState.Mask) = listOf(t.strokes.size, t.strokes.lastOrNull() ?: 0, t.feather, t.invert, t.protect)

    /** Renders the mask as a translucent red (protected) / green (selected) overlay. */
    private fun updateMaskOverlay() {
        val t = _state.value.tool as? ToolState.Mask ?: return
        val p = lastPreview ?: return
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            val session = withContext(Dispatchers.Default) {
                val w = p.width; val h = p.height
                val m = StrokeRasterizer.maskCoverage(t.strokes, w, h, if (t.protect) 1f else 0f)
                val f = StrokeRasterizer.feather(m.copyOf(), w, h, (t.feather * minOf(w, h)).roundToInt())
                MaskSession(w, h, m, IntArray(w * h) { i -> overlayPixel(f[i], t) }, maskKey(t))
            }
            val bmp = Bitmap.createBitmap(session.overlay, session.w, session.h, Bitmap.Config.ARGB_8888).asImageBitmap()
            if (maskSession?.inc?.touchedArea() != null || maskPump.pending) return@launch // a stroke started meanwhile: keep its session
            maskSession?.tiles?.clear()
            maskSession = session; _maskTiles.value = session.tiles
            _state.update { it.copy(maskOverlay = bmp) }
        }
    }

    /**
     * Mask stroke in progress: the settled mask plus the stroke, feathered in a window around
     * the changed area only (the box blur reaches 3 × radius), shown through tiles. Main thread.
     */
    private fun maskLive(s: Stroke?, final: Boolean) {
        val t = _state.value.tool as? ToolState.Mask ?: return
        val ms = maskSession?.takeIf { it.key == maskKey(t) } ?: return
        val w = ms.w
        if (s == null) { // stroke cancelled: back to the settled overlay
            maskPump.submit(true) {
                val a = ms.inc.touchedArea(); ms.inc.reset()
                val r = a?.let { maskRegion(ms, t, it, null) }
                ({ if (r != null) ms.tiles.update(ms.overlay, r) })
            }
            return
        }
        maskPump.submit(final) {
            val areas = ArrayList<IntArray>()
            if (ms.inc.update(s, final) != null) for (d in ms.inc.changedAreas) areas += maskRegion(ms, t, d, s)
            if (final) {
                ms.inc.touchedArea()?.let { a ->
                    for (y in a[1] until a[3]) for (x in a[0] until a[2]) { val i = y * w + x; ms.m[i] = Cutout.liveMask(ms.m[i], ms.inc.coverageAt(x, y), s) }
                }
                ms.inc.reset()
            }
            ({ for (r in areas) ms.tiles.update(ms.overlay, r) })
        }
    }

    /** Recomputes the feathered overlay around [d] (window of 3 × feather radius); returns the area written. */
    private fun maskRegion(ms: MaskSession, t: ToolState.Mask, d: IntArray, s: Stroke?): IntArray {
        val w = ms.w; val h = ms.h
        val rad = (t.feather * minOf(w, h)).roundToInt().coerceAtLeast(0)
        val e = 3 * rad
        val ox0 = maxOf(0, d[0] - e); val oy0 = maxOf(0, d[1] - e); val ox1 = minOf(w, d[2] + e); val oy1 = minOf(h, d[3] + e)
        val wx0 = maxOf(0, ox0 - e); val wy0 = maxOf(0, oy0 - e); val wx1 = minOf(w, ox1 + e); val wy1 = minOf(h, oy1 + e)
        val ww = wx1 - wx0; val wh = wy1 - wy0
        val buf = FloatArray(ww * wh)
        for (y in wy0 until wy1) for (x in wx0 until wx1) {
            val i = y * w + x
            buf[(y - wy0) * ww + (x - wx0)] = if (s == null) ms.m[i] else Cutout.liveMask(ms.m[i], ms.inc.coverageAt(x, y), s)
        }
        val f = StrokeRasterizer.feather(buf, ww, wh, rad)
        for (y in oy0 until oy1) for (x in ox0 until ox1) ms.overlay[y * w + x] = overlayPixel(f[(y - wy0) * ww + (x - wx0)], t)
        return intArrayOf(ox0, oy0, ox1, oy1)
    }

    private fun maskLiveReset() {
        maskSession?.tiles?.clear(); maskSession = null; _maskTiles.value = null
    }

    /** Params carrying the painted mask, attached to the effect picked next. */
    private fun maskParams(t: ToolState.Mask): Params = Params.EMPTY
        .with(MaskParams.MODE, if (t.protect) "protect" else "select")
        .with(MaskParams.STROKES, StrokeCodec.encode(t.strokes))
        .with(MaskParams.INVERT, t.invert)
        .with(MaskParams.FEATHER, t.feather)

    /** Second step of the mask tool: open [op] with the painted mask attached. */
    fun applyEffectWithMask(op: ImageOp) {
        val t = _state.value.tool as? ToolState.Mask ?: return
        val params = Params.of(op.params.defaults().asMap() + maskParams(t).asMap())
        _state.update { it.copy(tool = ToolState.Adjust(op, params), before = it.preview, maskOverlay = null) }
        requestRender()
    }

    // ------------------------------------------------------------------ saving

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch { delay(1200); saveNow() }
    }

    /**
     * The picture as it is seen, for the eyedropper (1.2.0, decision 5): the preview with every
     * visible object drawn over it (the one being edited included).
     */
    suspend fun eyedropperImage(): ImageBitmap? = withContext(Dispatchers.Default) {
        val base = lastPreview ?: return@withContext null
        runCatching { composite(base, _state.value.displayObjects()).asImageBitmap() }.getOrNull()
    }.also { lastEyedropperImage = it }
    /** Test hook: the last picture given to the eyedropper. */
    @Volatile var lastEyedropperImage: ImageBitmap? = null

    /** Draws [objects] over [base] with a private renderer (safe off the UI thread). */
    private fun composite(base: PixelBuffer, objects: List<CanvasObject>): Bitmap {
        val bmp = ImageIo.toMutableBitmap(base)
        if (objects.isNotEmpty()) {
            val r = ObjectRenderer(app.fonts, app.stickers, app.noto).also { it.assets = HashMap(assets) }
            r.drawAll(Canvas(bmp), objects, bmp.width.toFloat(), bmp.height.toFloat())
        }
        return bmp
    }

    // ---- finishing (1.1.0, correction 5): save point, «Guardar proyecto», «Salir sin guardar»
    private var savedBundle: ProjectBundle? = null
    private var savedDoc: Document? = null
    @Volatile private var discarded = false

    /** Changes since the project was opened or last saved on purpose. */
    fun hasPendingChanges(): Boolean = ::history.isInitialized && history.current != savedDoc

    /** Created from a picture and never saved on purpose: leaving without saving deletes it. */
    fun isDraft(): Boolean = repo.isDraft(projectId)

    /** «Guardar proyecto»: writes the project and makes it the new save point. */
    fun saveProject(onDone: (Boolean) -> Unit) {
        app.appScope.launch(Dispatchers.Main) {
            dirty = true
            saveNow()
            val ok = !dirty
            if (ok) {
                val d = history.current
                savedDoc = d
                savedBundle = runCatching { repo.load(projectId) }.getOrNull() ?: savedBundle
                repo.clearDraft(projectId)
            }
            onDone(ok)
        }
    }

    /**
     * «Salir sin guardar»: drops the changes since the save point (a draft is deleted, an older
     * project goes back to how it was saved). Nothing is written afterwards.
     */
    fun discardAndClose(onDone: () -> Unit) {
        autosaveJob?.cancel()
        discarded = true
        app.appScope.launch(Dispatchers.Main) {
            saveMutex.withLock {
                runCatching {
                    if (repo.isDraft(projectId)) repo.delete(projectId)
                    else savedBundle?.let { repo.save(it) }
                }
            }
            repo.clearOpen()
            onDone()
        }
    }

    /** Leaving after the picture was saved: the project is kept as it is (no longer a draft). */
    fun keepAndClose() { repo.clearDraft(projectId); closeEditor() }

    /** Persists the project. Safe to call from any lifecycle callback. */
    suspend fun saveNow() {
        if (!::history.isInitialized || !dirty || discarded) return
        saveMutex.withLock {
            if (!dirty || discarded) return
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

    /** Result of «Guardar imagen» (where it went), shown as a confirmation; null when none. */
    private val _savedImage = MutableStateFlow<String?>(null)
    val savedImage: StateFlow<String?> = _savedImage
    fun dismissSavedImage() { _savedImage.value = null }

    /** «Guardar imagen» (1.1.0): PNG or JPG with its quality, a file name, gallery or a chosen place. */
    fun saveImage(name: String, png: Boolean, jpegQuality: Int, documentUri: Uri?) {
        val opts = if (png) ExportOptions(format = ExportFormat.PNG) else ExportOptions(format = ExportFormat.JPEG_HIGH, quality = jpegQuality.coerceIn(10, 100))
        export(opts, if (documentUri != null) ExportTarget.DOCUMENT else ExportTarget.GALLERY, documentUri, fileName = name, confirm = true)
    }

    fun export(
        options: ExportOptions, target: ExportTarget, documentUri: Uri? = null, shareTo: android.content.ComponentName? = null,
        fileName: String? = null, confirm: Boolean = false,
    ) {
        if (_state.value.exporting) return
        val d = doc
        _state.update { it.copy(exporting = true, tool = null) }
        // results and messages go out on the main thread: whoever collects them may touch the UI
        app.appScope.launch(Dispatchers.Main) {
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
                val name = fileName?.trim()?.replace(Regex("[\\\\/:*?\"<>|]"), "")?.takeIf { it.isNotEmpty() }?.let { n ->
                    if (n.lowercase().endsWith(".${fmt.extension}")) n else "$n.${fmt.extension}"
                } ?: app.exports.suggestedName(d.title, fmt.extension)
                when (target) {
                    ExportTarget.GALLERY -> {
                        app.exports.saveToGallery(bytes, fmt.mimeType, name)
                        if (confirm) _savedImage.value = app.getString(R.string.save_image_where_gallery, name) else _messages.send(UiMessage.Res(R.string.export_done_gallery))
                    }
                    ExportTarget.DOCUMENT -> {
                        app.exports.saveToUri(requireNotNull(documentUri), bytes)
                        if (confirm) _savedImage.value = app.getString(R.string.save_image_where_file, documentUri.lastPathSegment?.substringAfterLast('/') ?: name) else _messages.send(UiMessage.Res(R.string.export_done_file))
                    }
                    ExportTarget.SHARE -> _shares.send(ShareRequest(app.exports.prepareShare(bytes, name), fmt.mimeType, component = shareTo))
                    ExportTarget.SET_AS -> _shares.send(ShareRequest(app.exports.prepareShare(bytes, name), fmt.mimeType, attach = true))
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

    /** Suggested file name for «Guardar imagen» (without extension). */
    fun suggestedImageName(): String = app.exports.suggestedName(doc.title, "x").removeSuffix(".x")

    private fun resolveFormat(o: ExportOptions, d: Document): Pair<ImageIo.Format, Int> = when (o.format) {
        ExportFormat.PNG -> ImageIo.Format.PNG to 100
        ExportFormat.ORIGINAL ->
            if (d.source.mimeType == "image/png" || d.source.hasAlpha) ImageIo.Format.PNG to 100 else ImageIo.Format.JPEG to 95
        else -> ImageIo.Format.JPEG to o.quality
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** For instrumented tests: the editor most recently opened (no effect on the app). */
        @Volatile var lastInstance: EditorViewModel? = null
        private const val PENDING_UID = "pending"
        private const val PROXY_TOKEN = "proxy"

        fun factory(app: BocadilloApp, projectId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(app, projectId) as T
        }
    }
}
