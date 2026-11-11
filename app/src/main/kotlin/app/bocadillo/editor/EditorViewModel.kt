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
import PaintOps
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.imaging.paint.Stroke
import app.bocadillo.data.FontRepository
import app.bocadillo.core.imaging.paint.StrokeCodec
import app.bocadillo.core.imaging.ops.PaintOps
import app.bocadillo.core.imaging.paint.StrokePainter
import app.bocadillo.render.processPicture
import app.bocadillo.core.imaging.objects.Cutout
import StrokeCodec
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
            val count = (_state.value.tool as? ToolState.Draw)?.strokes?.size ?: -1
            val result = renderer.render(proxy, PROXY_TOKEN, previewOps(), masks, ctx)
            val bmp = ImageIo.toBitmap(result).asImageBitmap()
            lastPreview = result
            previewStrokeCount = count
            _state.update { it.copy(preview = bmp) }
            // the rendered picture now contains every finished stroke: the live view can go
            val now = (_state.value.tool as? ToolState.Draw)?.strokes?.size ?: -1
            if (count == now) _livePaint.value = null
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
        _livePaint.value = null; paintBaseCount = -1; historySource = null
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

    // ---- Cut Out: live mask (same code as the export: processPicture / core Cutout)
    private var insertRaw: android.graphics.Bitmap? = null
    private val _insertLive = MutableStateFlow<Pair<String, android.graphics.Bitmap>?>(null)
    /** Processed picture of the draft (crop + mask incl. the stroke in progress) for the screen. */
    val insertLive: StateFlow<Pair<String, android.graphics.Bitmap>?> = _insertLive
    private var liveJob: Job? = null
    private val liveStrokes = kotlinx.coroutines.channels.Channel<Stroke?>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun rawPicture(d: StickerObject): android.graphics.Bitmap? {
        insertRaw?.let { return it }
        val bytes = assets[d.source.removePrefix("asset:")] ?: return null
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { insertRaw = it }
    }

    private fun ensureLiveWorker() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch(Dispatchers.Default) {
            for (extra in liveStrokes) {
                val t = insertTool() ?: continue
                val raw = rawPicture(t.draft) ?: continue
                val bmp = runCatching { processPicture(raw, t.draft.crop, t.draft.cutout, extra) }.getOrNull() ?: continue
                _insertLive.value = t.draft.id to bmp
            }
        }
    }

    private fun refreshInsertLive() {
        val t = insertTool() ?: return
        if (t.draft.cutout.isEmpty() && t.stage != InsertStage.CUTOUT) { _insertLive.value = null; return }
        ensureLiveWorker(); liveStrokes.trySend(null)
    }

    private fun clearInsertLive() { _insertLive.value = null; insertRaw = null }

    /** Stroke in progress (image coordinates): the screen shows its real effect at once. */
    fun cutStrokeLive(points: FloatArray, imageSize: Float) {
        val t = insertTool() ?: return
        ensureLiveWorker()
        liveStrokes.trySend(if (points.isEmpty()) null else cutStroke(t, points, imageSize))
    }

    /** The picked picture, whole (for the Crop step). */
    suspend fun insertSourceBitmap(): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.Default) {
        val t = insertTool() ?: return@withContext null
        val bytes = assets[t.draft.source.removePrefix("asset:")] ?: return@withContext null
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /** End of a stroke: one undo step; the mask stays editable in the project. */
    fun cutStrokeCommit(points: FloatArray, imageSize: Float) {
        val t = insertTool() ?: return
        val strokes = Cutout.strokes(t.draft.cutout) + cutStroke(t, points, imageSize)
        pushInsert(t.draft.copy(cutout = Cutout.encode(strokes)))
        refreshInsertLive()
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
    fun importFonts(uris: List<Uri>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val items = uris.map { u -> ImageIo.read(app.contentResolver, u).let { it.displayName to it.bytes } }
                    app.fonts.importAll(items)
                }
                report.firstRef?.let { ref -> app.fonts.libraryBytes(ref)?.let { assets[ref.removePrefix("asset:")] = it } }
                bumpAssets()
                _fontReport.value = report
                report.firstRef?.let(onDone)
            } catch (e: Exception) {
                _messages.send(UiMessage.Res(R.string.font_import_failed))
            }
        }
    }

    fun deleteFont(ref: String) { app.fonts.delete(ref); bumpAssets() }

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
                _state.update { it.copy(tool = t.copy(strokes = t.strokes + s, redo = emptyList())) }
                // the live view finishes this stroke with the same painter, then the render confirms it
                livePaintMsgs.trySend(LivePaint(s, t.strokes.size, final = true))
                requestRender()
            }
            is ToolState.Mask -> {
                val s = maskStroke(t, points)
                _state.update { it.copy(tool = t.copy(strokes = t.strokes + s, redo = emptyList())) }
                updateMaskOverlay()
            }
            else -> Unit
        }
    }

    // ---- live strokes (change 17): what is seen while the finger moves is the real result
    private class LivePaint(val stroke: Stroke?, val baseCount: Int, val final: Boolean)
    private val livePaintMsgs = Channel<LivePaint>(Channel.UNLIMITED)
    private var livePaintJob: Job? = null
    private val _livePaint = MutableStateFlow<ImageBitmap?>(null)
    /** Preview with the stroke in progress painted by the same code as the final render (null = none). */
    val livePaint: StateFlow<ImageBitmap?> = _livePaint
    /** Buffer that already contains the committed strokes, and how many. */
    @Volatile private var paintBase: PixelBuffer? = null
    @Volatile private var paintBaseCount = -1
    @Volatile private var previewStrokeCount = -1
    private var historySource: Pair<Int, PixelBuffer>? = null
    private val liveBitmaps = arrayOfNulls<Bitmap>(2)
    private var liveFlip = 0

    /** Stroke in progress (one finger moving). */
    fun strokeLive(points: FloatArray, zoom: Float = 1f) {
        when (val t = _state.value.tool) {
            is ToolState.Draw -> {
                if (t.eyedropper) return
                ensureLivePaint()
                livePaintMsgs.trySend(LivePaint(if (points.isEmpty()) null else drawStroke(t, points, zoom), t.strokes.size, final = false))
            }
            is ToolState.Mask -> updateMaskOverlay(if (points.isEmpty()) null else maskStroke(t, points))
            else -> Unit
        }
    }

    private fun ensureLivePaint() {
        if (livePaintJob?.isActive == true) return
        livePaintJob = viewModelScope.launch(Dispatchers.Default) {
            var work: IntArray? = null
            var workFor = -1
            while (true) {
                var m = livePaintMsgs.receive()
                // drop stale intermediate frames, never a final one
                while (!m.final) { m = livePaintMsgs.tryReceive().getOrNull() ?: break }
                val t = _state.value.tool as? ToolState.Draw ?: continue
                val base = when {
                    previewStrokeCount == m.baseCount -> lastPreview
                    paintBaseCount == m.baseCount -> paintBase
                    else -> lastPreview
                } ?: continue
                val w = base.width; val h = base.height
                if (work == null || work.size != base.pixels.size || workFor != m.baseCount || m.final) { work = base.pixels.copyOf(); workFor = m.baseCount }
                else System.arraycopy(base.pixels, 0, work, 0, work.size)
                val st = m.stroke
                if (st != null) {
                    if (t.kind == DrawKind.HISTORY) historyLive(work, w, h, base, st, t.stage)
                    else StrokePainter.paintInPlace(work, w, h, st)
                }
                if (m.final) {
                    paintBase = PixelBuffer(w, h, work.copyOf()); paintBaseCount = m.baseCount + 1
                    workFor = -1
                }
                val bmp = liveBitmaps[liveFlip]?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { liveBitmaps[liveFlip] = it }
                liveFlip = 1 - liveFlip
                bmp.setPixels(work, 0, w, 0, 0, w, h)
                _livePaint.value = if (st == null && !m.final) null else bmp.asImageBitmap()
            }
        }
    }

    /** History Paint, live: the same lerp towards the chosen stage as PaintOps.HistoryPaint. */
    private fun historyLive(px: IntArray, w: Int, h: Int, base: PixelBuffer, st: Stroke, stage: Int) {
        val src = historySource?.takeIf { it.first == stage && it.second.width == w && it.second.height == h }?.second ?: run {
            val ops = doc.ops.take(stage)
            val r = kotlinx.coroutines.runBlocking { renderer.render(proxy, PROXY_TOKEN, ops, masks, RenderContext(scale = proxyScale)) }
            historySource = stage to r; r
        }
        if (src.width != w || src.height != h) return
        val out = PaintOps.HistoryPaint.apply(
            base, Params.of("stage" to stage).with("strokes", StrokeCodec.encode(listOf(st))), RenderContext(scale = 1f),
        ) { if (it == stage) src else null }
        System.arraycopy(out.pixels, 0, px, 0, px.size)
    }

    fun undoStroke() {
        _state.update { s ->
            when (val t = s.tool) {
                is ToolState.Draw -> if (t.strokes.isEmpty()) s else s.copy(tool = t.copy(strokes = t.strokes.dropLast(1), redo = t.redo + t.strokes.last()))
                is ToolState.Mask -> if (t.strokes.isEmpty()) s else s.copy(tool = t.copy(strokes = t.strokes.dropLast(1), redo = t.redo + t.strokes.last()))
                else -> s
            }
        }
        _livePaint.value = null; paintBaseCount = -1
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

    /** Renders the mask as a translucent red (protected) / green (selected) overlay. */
    private fun updateMaskOverlay(live: Stroke? = null) {
        val t = _state.value.tool as? ToolState.Mask ?: return
        val p = lastPreview ?: return
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch(Dispatchers.Default) {
            val w = p.width; val h = p.height
            var m = StrokeRasterizer.maskCoverage(t.strokes + listOfNotNull(live), w, h, if (t.protect) 1f else 0f)
            m = StrokeRasterizer.feather(m, w, h, (t.feather * minOf(w, h)).roundToInt())
            val tint = if (t.protect) 0xE53935 else 0x43A047
            val px = IntArray(w * h) { i ->
                val v = if (t.invert) 1f - m[i] else m[i]
                (((v * 0.55f) * 255).toInt().coerceIn(0, 255) shl 24) or tint
            }
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            _state.update { it.copy(maskOverlay = bmp) }
        }
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

    /** Draws [objects] over [base] with a private renderer (safe off the UI thread). */
    private fun composite(base: PixelBuffer, objects: List<CanvasObject>): Bitmap {
        val bmp = ImageIo.toMutableBitmap(base)
        if (objects.isNotEmpty()) {
            val r = ObjectRenderer(app.fonts, app.stickers, app.noto).also { it.assets = HashMap(assets) }
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

    fun export(options: ExportOptions, target: ExportTarget, documentUri: Uri? = null, shareTo: android.content.ComponentName? = null) {
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
