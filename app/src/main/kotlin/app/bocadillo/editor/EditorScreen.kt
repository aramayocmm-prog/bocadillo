package app.bocadillo.editor

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import app.bocadillo.ui.classic.BarItem
import app.bocadillo.ui.classic.ClassicBottomBar
import app.bocadillo.ui.classic.ClassicTopBar
import app.bocadillo.ui.theme.Classic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bocadillo.BocadilloApp
import app.bocadillo.R
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.imaging.ops.TransformOps
import app.bocadillo.core.imaging.ops.Geometry
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.mutableIntStateOf
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.BalloonShape
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.TextStyle
import app.bocadillo.core.imaging.preview.ToolPreviews
import app.bocadillo.core.model.TextObject
import app.bocadillo.data.ImageIo
import app.bocadillo.render.ObjectRenderer

fun UiMessage.text(context: Context): String = when (this) {
    is UiMessage.Res -> context.getString(id, *args.toTypedArray())
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The editor, with the eyedropper of the unified colour picker (1.2.0, decision 5): any colour
 * picker inside can open the picture as it is seen (image and all visible objects) to pick a colour.
 */
@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BocadilloApp
    val vm: EditorViewModel = viewModel(factory = EditorViewModel.factory(app, projectId))
    var pickTo by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    var pickImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val open = remember { { f: (Int) -> Unit -> pickTo = f; pickImage = null; scope.launch { pickImage = vm.eyedropperImage() }; Unit } }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalEyedropper provides open) { EditorScreenBody(projectId, onBack) }
        val to = pickTo
        if (to != null) EyedropperOverlay(pickImage, onPick = { c -> pickTo = null; pickImage = null; to(c) }, onCancel = { pickTo = null; pickImage = null })
    }
}

@Composable
private fun EditorScreenBody(projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BocadilloApp
    val vm: EditorViewModel = viewModel(factory = EditorViewModel.factory(app, projectId))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var comparing by remember { mutableStateOf(false) }
    var openMenu by rememberSaveable { mutableStateOf<EditorMenu?>(null) }
    var exportTarget by rememberSaveable { mutableStateOf(ExportTarget.GALLERY) }
    val (adjustMenu, effectMenu) = remember { EditorMenus.withLeftovers(app.registry) }
    var confirmRestore by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.text(context)) } }
    LaunchedEffect(vm) {
        vm.shares.collect { s ->
            if (s.attach) {
                // "Set picture as" (wallpaper, contact photo…)
                val set = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(s.uri, s.mimeType).putExtra("mimeType", s.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(set, context.getString(R.string.export_menu_set_as)))
            } else {
                val send = Intent(Intent.ACTION_SEND).setType(s.mimeType).putExtra(Intent.EXTRA_STREAM, s.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                s.component?.let { send.component = it }
                // Settings › Sharing: default subject and signature (used by e-mail apps)
                app.settings.shareSubject.takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
                app.settings.shareSignature.takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
                if (s.component != null) runCatching { context.startActivity(send) }
                else context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    // Settings › Editor: hide the status bar while editing
    val hideBar = app.settings.hideStatusBar
    androidx.compose.runtime.DisposableEffect(hideBar) {
        val window = (context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (hideBar) {
            ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        onDispose { ctl?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars()) }
    }

    var transformMenu by remember { mutableStateOf(false) }
    // Insert Picture: brush panel of Cut Out, first-time guide and the processed picture
    var cutPanel by remember { mutableStateOf(false) }
    var guideStage by remember { mutableStateOf<InsertStage?>(null) }
    val insertTool = state.tool as? ToolState.InsertPicture
    val insertLive by vm.insertLive.collectAsStateWithLifecycle()
    val liveTiles by vm.liveTiles.collectAsStateWithLifecycle()
    val maskTiles by vm.maskTiles.collectAsStateWithLifecycle()
    // once a render with every finished stroke is on screen, the live tiles can go
    LaunchedEffect(state.preview) { vm.previewShown(state.preview) }
    LaunchedEffect(insertTool?.stage) {
        val st = insertTool?.stage
        if (st == null) cutPanel = false
        else if (!app.settings.insertGuideOff) guideStage = st
        if (st == InsertStage.CUTOUT) cutPanel = false
    }
    var cropRatioDialog by remember { mutableStateOf(false) }
    var resizeMore by remember { mutableStateOf(false) }
    if (cropRatioDialog) RatioDialog(onDismiss = { cropRatioDialog = false }) { w, h -> cropRatioDialog = false; vm.setCropPreset(Geometry.CropPreset.CUSTOM, w / h) }
    // Title / Word Balloon composers: detailed style screen and its Font screen
    var styleDetails by rememberSaveable { mutableStateOf(false) }
    var fontPage by rememberSaveable { mutableStateOf(false) }
    var titlePanel by rememberSaveable { mutableStateOf(false) }
    var composerDate by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedId) { if (state.selectedId == null) transformMenu = false }
    // classic Paint: open drop-down panel; eyedropper returns to the colour panel after a pick
    var paintPanel by rememberSaveable { mutableStateOf<PaintPanel?>(null) }
    var discardPaint by remember { mutableStateOf(false) }
    val drawTool = state.tool as? ToolState.Draw
    var wasPicking by remember { mutableStateOf(false) }
    LaunchedEffect(drawTool?.eyedropper) {
        if (drawTool?.eyedropper == true) { wasPicking = true; paintPanel = null }
        else if (wasPicking) { wasPicking = false; paintPanel = PaintPanel.COLOR }
    }
    LaunchedEffect(drawTool == null) { if (drawTool == null) paintPanel = null }
    if (discardPaint) AlertDialog(
        onDismissRequest = { discardPaint = false },
        text = { Text(stringResource(R.string.paint_discard)) },
        confirmButton = { TextButton({ discardPaint = false; vm.cancelTool() }) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton({ discardPaint = false }) { Text(stringResource(R.string.cancel)) } },
    )
    val leave = { vm.closeEditor(); onBack() }
    // ---- finishing (1.1.0, correction 5)
    var finishDialog by rememberSaveable { mutableStateOf(false) }
    var discardConfirm by rememberSaveable { mutableStateOf(false) }
    var saveImageDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<Triple<String, Boolean, Int>?>(null) }
    val savedImage by vm.savedImage.collectAsStateWithLifecycle()
    val pickPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        pendingSave?.let { (n, png, q) -> if (uri != null) vm.saveImage(n, png, q, uri) }; pendingSave = null
    }
    val pickJpg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        pendingSave?.let { (n, png, q) -> if (uri != null) vm.saveImage(n, png, q, uri) }; pendingSave = null
    }
    /** Leaving the editor (← of the editor, Back with nothing open): protects unsaved work. */
    val tryLeave = {
        when {
            state.loading || state.fatalError != null -> leave()
            vm.hasPendingChanges() -> finishDialog = true
            vm.isDraft() -> vm.discardAndClose(onBack) // opened a picture and changed nothing: nothing to keep
            else -> leave()
        }
    }
    val exitWithoutSaving = {
        finishDialog = false
        if (vm.hasPendingChanges()) discardConfirm = true
        else if (vm.isDraft()) vm.discardAndClose(onBack) else leave()
    }
    if (finishDialog) FinishDialog(
        pending = vm.hasPendingChanges(),
        onSaveImage = { finishDialog = false; saveImageDialog = true },
        onSaveProject = {
            finishDialog = false
            vm.saveProject { ok -> if (ok) { android.widget.Toast.makeText(context, R.string.project_saved, android.widget.Toast.LENGTH_SHORT).show(); leave() } }
        },
        onContinue = { finishDialog = false },
        onExit = exitWithoutSaving,
    )
    if (discardConfirm) DiscardConfirmDialog(vm.isDraft(), onDiscard = { discardConfirm = false; vm.discardAndClose(onBack) }, onCancel = { discardConfirm = false })
    if (saveImageDialog) SaveImageDialog(
        suggestedName = remember { vm.suggestedImageName() },
        hasTransparency = state.sourceHasAlpha || vm.currentHasTransparency(),
        onSave = { name, png, q, choose ->
            saveImageDialog = false
            if (choose) { pendingSave = Triple(name, png, q); (if (png) pickPng else pickJpg).launch(name + if (png) ".png" else ".jpg") }
            else vm.saveImage(name, png, q, null)
        },
        onDismiss = { saveImageDialog = false },
    )
    savedImage?.let { where ->
        ImageSavedDialog(where, onContinue = { vm.dismissSavedImage() }, onExit = { vm.dismissSavedImage(); vm.keepAndClose(); onBack() })
    }
    BackHandler {
        when {
            openMenu == EditorMenu.BALLOON_SHAPES -> openMenu = EditorMenu.STICKER
            openMenu != null -> openMenu = null
            (state.tool as? ToolState.PickSticker)?.category != null -> vm.showStickerCategory(null)
            insertTool != null && cutPanel -> cutPanel = false
            insertTool?.stage == InsertStage.CUTOUT -> vm.insertGoTo(InsertStage.PLACE)
            insertTool?.stage == InsertStage.PLACE && insertTool.isNew -> vm.insertGoTo(InsertStage.CROP)
            state.tool is ToolState.Draw && paintPanel != null -> paintPanel = null
            fontPage && (state.tool is ToolState.EditText || state.tool is ToolState.EditBalloon) -> fontPage = false
            titlePanel && state.tool is ToolState.EditText -> titlePanel = false
            styleDetails && (state.tool is ToolState.EditText || state.tool is ToolState.EditBalloon) -> styleDetails = false
            (state.tool as? ToolState.Draw)?.strokes?.isNotEmpty() == true -> discardPaint = true
            state.tool != null -> vm.cancelTool()
            transformMenu -> transformMenu = false
            state.transformStart != null -> vm.finishTransform()
            state.selectedId != null -> vm.selectObject(null)
            else -> tryLeave()
        }
    }
    var rotateFlipDialog by rememberSaveable { mutableStateOf(false) }
    // classic tool chrome: which slider the bottom bar controls, and whether all options show
    var activeParam by rememberSaveable { mutableStateOf("") }
    var allOptions by rememberSaveable { mutableStateOf(false) }
    var composerPhrases by remember { mutableStateOf(false) }
    // a new composer always starts in the light screen
    val toolKind = state.tool?.let { it::class.simpleName + ((it as? ToolState.EditBalloon)?.draft?.id ?: (it as? ToolState.EditText)?.draft?.id ?: "") }
    androidx.compose.runtime.LaunchedEffect(toolKind) {
        styleDetails = false; fontPage = false; titlePanel = false; allOptions = false
        activeParam = (state.tool as? ToolState.Adjust)?.op?.params?.filterIsInstance<ParamSpec.Slider>()?.firstOrNull()?.key ?: ""
    }
    var needsTransparency by rememberSaveable { mutableStateOf(false) }
    var locationOpen by rememberSaveable { mutableStateOf(false) }
    var location by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val insertPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.insertPicture(uri) }
    if (rotateFlipDialog) RotateFlipDialog(onDismiss = { rotateFlipDialog = false }) { rot, h, v -> rotateFlipDialog = false; vm.orient(rot, h, v) }
    if (needsTransparency) AlertDialog(
        onDismissRequest = { needsTransparency = false },
        text = { Text(stringResource(R.string.bg_needs_transparency)) },
        confirmButton = { TextButton({ needsTransparency = false }) { Text(stringResource(R.string.ok)) } },
    )
    if (locationOpen) PictureLocationDialog(location) { locationOpen = false }
    // menu thumbnails show the photo being edited (refreshed when a menu opens)
    androidx.compose.runtime.LaunchedEffect(openMenu) { if (openMenu == EditorMenu.ADJUST || openMenu == EditorMenu.EFFECT) withContext(Dispatchers.Default) { ToolTileCache.setPhoto(vm.previewBitmap()) } }
    fun runMenu(a: MenuAction) {
        openMenu = null
        when (a) {
            is MenuAction.Op -> when (a.id) {
                // the original shows Rotate and Flip as a small pop-up list
                TransformOps.Orient.id -> rotateFlipDialog = true
                "add_background_color" -> if (vm.currentHasTransparency()) app.registry[a.id]?.let { vm.openTool(it) } else needsTransparency = true
                TransformOps.Resize.id -> vm.openPanel(ToolState.Resize)
                else -> app.registry[a.id]?.let { vm.openTool(it) }
            }
            is MenuAction.Draw -> vm.openDraw(a.kind)
            MenuAction.Mask -> vm.openMask(protect = false)
            MenuAction.Restore -> confirmRestore = true
            MenuAction.InsertPicture -> insertPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            MenuAction.Location -> scope.launch { location = vm.pictureLocation(); locationOpen = true }
        }
    }

    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val o = pendingExport; if (uri != null && o != null) vm.export(o, ExportTarget.DOCUMENT, uri)
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val o = pendingExport; if (uri != null && o != null) vm.export(o, ExportTarget.DOCUMENT, uri)
    }

    // Objects renderer shared by the canvas and the pickers (UI thread only).
    val objRenderer = remember { ObjectRenderer(app.fonts, app.stickers, app.noto) }
    // Cut Out: the processed picture (incl. the stroke in progress) replaces the draft's pixels
    val cutTiles by vm.cutTiles.collectAsStateWithLifecycle()
    LaunchedEffect(insertLive, cutTiles) {
        objRenderer.liveBitmaps.clear(); objRenderer.liveTiles.clear()
        insertLive?.let { (id, bmp) -> objRenderer.liveBitmaps[id] = bmp; cutTiles?.let { objRenderer.liveTiles[id] = it } }
    }
    remember(state.assetsVersion) { objRenderer.assets = vm.assetsSnapshot(); 0 }
    val fonts = remember(state.assetsVersion) { app.fonts.all() }
    val typeface = { ref: String -> app.fonts.typeface(ref, false, false, objRenderer.assets) }
    var phrasesVersion by remember { mutableIntStateOf(0) }
    val panelPrefs = rememberPanelPrefs()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFonts(uris) { ref ->
            when (val t = vm.state.value.tool) {
                is ToolState.EditText -> vm.updateDraft(t.draft.copy(style = t.draft.style.copy(font = ref)))
                is ToolState.EditBalloon -> vm.updateDraft(t.draft.copy(style = t.draft.style.copy(font = ref)))
                else -> Unit
            }
        }
    }
    val stickerImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        vm.importStickerImages(uris)
    }
    val library = remember(state.libraryVersion) { app.stickerLibrary.list() }
    val stickerZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importStickerZip(uri) }
    val importingStickers by vm.importingStickers.collectAsStateWithLifecycle()
    val stickerImport by vm.stickerImport.collectAsStateWithLifecycle()
    StickerImportReportDialog(stickerImport?.first, stickerImport?.second, vm::dismissStickerImport)
    val phraseOps = remember {
        PhraseOps(
            add = { p -> app.phrases.add(p).also { phrasesVersion++ } },
            update = { i, p -> app.phrases.update(i, p).also { phrasesVersion++ } },
            remove = { i -> app.phrases.remove(i).also { phrasesVersion++ } },
        )
    }
    val fontReport by vm.fontReport.collectAsStateWithLifecycle()
    val fontImport by vm.fontImport.collectAsStateWithLifecycle()
    fontReport?.let { r -> FontImportReportDialog(r, vm::dismissFontReport) }
    if (composerDate) DateTimeDialog(state.photoDate, onDismiss = { composerDate = false }) { s ->
        composerDate = false
        (state.tool as? ToolState.EditText)?.let { t -> vm.updateDraft(t.draft.copy(text = if (t.draft.text.isEmpty()) s else t.draft.text + " " + s)) }
    }
    if (composerPhrases) PhrasesDialog(app.phrases.list(), phraseOps, {}, onDismiss = { composerPhrases = false }) { phrase ->
        composerPhrases = false
        when (val t = state.tool) {
            is ToolState.EditBalloon -> vm.updateDraft(t.draft.copy(text = (t.draft.text + if (t.draft.text.isEmpty()) phrase else " $phrase")))
            is ToolState.EditText -> vm.updateDraft(t.draft.copy(text = (t.draft.text + if (t.draft.text.isEmpty()) phrase else " $phrase")))
            else -> Unit
        }
    }

    Scaffold(
        containerColor = Classic.Background,
        topBar = {
            val info = panelInfo(state, context, vm)
            val bar = toolBar(state, context, vm)
            // edge-to-edge: keep the bar below the status bar (black strip behind it, as in the reference)
            Column(Modifier.background(Classic.Background).statusBarsPadding()) {
            when {
                openMenu != null -> ClassicTopBar(
                    stringResource(when (openMenu!!) {
                        EditorMenu.ADJUST -> R.string.menu_adjust; EditorMenu.EFFECT -> R.string.menu_effect
                        EditorMenu.STICKER -> R.string.sticker_new; EditorMenu.EXPORT -> R.string.export
                        EditorMenu.BALLOON_SHAPES -> R.string.word_balloon
                    }),
                    onBack = { openMenu = if (openMenu == EditorMenu.BALLOON_SHAPES) EditorMenu.STICKER else null },
                    // menus close with ✕ like the original; sub-screens go back with ←
                    backIcon = if (openMenu == EditorMenu.BALLOON_SHAPES) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                    // the Export list shows the file name instead of a title
                    actions = {},
                )
                state.tool is ToolState.InsertPicture -> InsertTopBar(state.tool as ToolState.InsertPicture, cutPanel, { cutPanel = it }, InsertActions(
                    undo = vm::insertUndo, redo = vm::insertRedo, resetCrop = vm::resetInsertCrop, next = vm::applyTool, goTo = vm::insertGoTo,
                    stretchToFit = vm::stretchToFit, scaleToFill = vm::scaleToFill, mirror = vm::insertMirror, perspective = vm::setInsertPerspective,
                    showStickers = vm::toggleInsertStickers, erase = vm::setCutErase, resetCutout = vm::resetCutout,
                    guide = { guideStage = (state.tool as ToolState.InsertPicture).stage },
                ))
                state.tool is ToolState.Crop -> {
                    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                    val wallpaper = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat() / maxOf(cfg.screenWidthDp, cfg.screenHeightDp)
                    CropTopBar(state.tool as ToolState.Crop, state.outputWidth, state.outputHeight, wallpaper, vm::resetCrop, vm::setCropPreset,
                        onCustomRatio = { cropRatioDialog = true }, onCancel = vm::cancelTool, onApply = vm::applyTool)
                }
                state.tool is ToolState.Draw -> PaintTopBar(
                    state.tool as ToolState.Draw, paintPanel, { paintPanel = it }, vm::setDrawKind, vm::undoStroke, vm::redoStroke,
                    onCancel = vm::cancelTool, onApply = vm::applyTool,
                )
                (state.tool as? ToolState.Adjust)?.takeIf { usesClassicChrome(it) } != null -> {
                    val t = state.tool as ToolState.Adjust
                    ClassicToolTopBar(t, activeParam, { activeParam = it }, vm::resetParams, { on -> if (on) vm.ensureBefore(); comparing = on },
                        onMoreOptions = { allOptions = !allOptions }, onCancel = vm::cancelTool, onApply = vm::applyTool)
                }
                bar != null && (state.tool as? ToolState.StyleSticker)?.vector != null ->
                    ClassicTopBar(bar.title, onBack = bar.onCancel, onConfirm = bar.onApply, backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, greenConfirm = true)
                bar != null && (state.tool is ToolState.EditBalloon || state.tool is ToolState.EditText) -> when {
                    fontPage -> ClassicTopBar(stringResource(R.string.more_fonts_title), onBack = { fontPage = false }, backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft)
                    styleDetails -> ClassicTopBar(
                        stringResource(if (state.tool is ToolState.EditText) R.string.title_style else R.string.balloon_style),
                        onBack = { styleDetails = false }, onConfirm = { styleDetails = false }, backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, greenConfirm = true,
                    )
                    else -> ClassicTopBar(bar.title, onBack = bar.onCancel, onConfirm = bar.onApply, backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, greenConfirm = true)
                }
                bar != null -> ClassicTopBar(bar.title, onBack = bar.onCancel, onConfirm = bar.onApply, actions = { info?.quick?.invoke(this) },
                    backIcon = if (bar.onApply == null && (state.tool as? ToolState.PickSticker)?.category == null) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack)
                else -> ClassicTopBar(
                    state.title.ifEmpty { stringResource(R.string.untitled) }, onBack = tryLeave, onConfirm = { finishDialog = true },
                    confirmLabel = stringResource(R.string.finish), greenConfirm = true,
                    actions = {
                        IconButton(onClick = vm::undo, enabled = state.canUndo && state.tool == null) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo), tint = if (state.canUndo) Classic.Text else Classic.Cell) }
                        IconButton(onClick = vm::redo, enabled = state.canRedo && state.tool == null) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo), tint = if (state.canRedo) Classic.Text else Classic.Cell) }
                    },
                )
            }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CompositionLocalProvider(LocalClassicChrome provides true) {
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (openMenu) {
                EditorMenu.ADJUST -> { MenuList(adjustMenu, app.registry, ::runMenu); return@Column }
                EditorMenu.EFFECT -> { MenuList(effectMenu, app.registry, ::runMenu); return@Column }
                EditorMenu.STICKER -> { openMenu = null; vm.openStickerPicker() }
                EditorMenu.BALLOON_SHAPES -> { BalloonShapeGrid(objRenderer) { sh -> openMenu = null; vm.newBalloon(sh) }; return@Column }
                EditorMenu.EXPORT -> {
                    // album / share / set-as go straight out with the default format (as in the
                    // original); "Save as file…" first asks for the format
                    ExportMenuList(state.title, onTarget = { t ->
                        openMenu = null
                        if (t == ExportTarget.DOCUMENT) { exportTarget = t; vm.openPanel(ToolState.Export) }
                        else vm.export(ExportOptions(format = if (vm.currentHasTransparency()) ExportFormat.PNG else ExportFormat.JPEG_VERY_HIGH), t)
                    }, onApp = { cn -> openMenu = null; vm.export(ExportOptions(format = if (vm.currentHasTransparency()) ExportFormat.PNG else ExportFormat.JPEG_VERY_HIGH), ExportTarget.SHARE, shareTo = cn) })
                    return@Column
                }
                null -> Unit
            }
            // "New Word Balloon" / "New Title" (also when re-editing): light full-screen composer;
            // tapping the preview opens the detailed style screen, Font › its own font list
            val newTool = state.tool
            if (newTool is ToolState.EditBalloon) {
                when {
                    fontPage -> MoreFontsScreen(fonts, newTool.draft.style.font, typeface,
                        // 1.2.0, decision 8: a tap applies the font and goes back to the text at once
                        onPick = { ref -> vm.updateDraft(newTool.draft.copy(style = newTool.draft.style.copy(font = ref))); app.settings.addFontRecent(ref); fontPage = false },
                        onImport = { fontPicker.launch(FONT_MIME_TYPES) }, onDelete = vm::deleteFont, cached = app.fonts::cachedTypeface,
                        favorites = app.settings.fontFavorites, recents = app.settings.fontRecents, onToggleFavorite = app.settings::toggleFontFavorite,
                        importing = fontImport, usage = vm::projectsUsingFont)
                    styleDetails -> BalloonStyleScreen(newTool.draft, objRenderer, fonts, typeface, vm::updateDraft, onFonts = { fontPage = true })
                    else -> NewBalloonScreen(newTool.draft, objRenderer, vm::updateDraft, onStylePreview = { styleDetails = true }, onPhrases = { composerPhrases = true })
                }
                return@Column
            }
            if (newTool is ToolState.EditText) {
                when {
                    fontPage -> MoreFontsScreen(fonts, newTool.draft.style.font, typeface,
                        // 1.2.0, decision 8: a tap applies the font and goes back to the text at once
                        onPick = { ref -> vm.updateDraft(newTool.draft.copy(style = newTool.draft.style.copy(font = ref))); app.settings.addFontRecent(ref); fontPage = false },
                        onImport = { fontPicker.launch(FONT_MIME_TYPES) }, onDelete = vm::deleteFont, cached = app.fonts::cachedTypeface,
                        favorites = app.settings.fontFavorites, recents = app.settings.fontRecents, onToggleFavorite = app.settings::toggleFontFavorite,
                        importing = fontImport, usage = vm::projectsUsingFont)
                    styleDetails -> TitleStyleScreen(newTool.draft, objRenderer, fonts, typeface, vm::updateDraft, onFonts = { fontPage = true }, onDateTime = { composerDate = true })
                    else -> Box(Modifier.fillMaxSize()) {
                        NewTitleScreen(newTool.draft, objRenderer, vm::updateDraft, onStylePreview = { titlePanel = true }, onPhrases = { composerPhrases = true })
                        // classic Title Style panel over the ABC grid
                        if (titlePanel) TitleStylePanel(newTool.draft, fonts, typeface, vm::updateDraft,
                            onFonts = { fontPage = true }, onMore = { titlePanel = false; styleDetails = true }, onClose = { titlePanel = false })
                    }
                }
                return@Column
            }
            // Set Shape Style / Set Line Style: full-screen light grids
            (state.tool as? ToolState.StyleSticker)?.takeIf { it.vector != null }?.let { t ->
                if (t.vector == VectorStyleKind.SHAPE) SetShapeStyleScreen(t.draft, objRenderer, vm::updateDraft)
                else SetLineStyleScreen(t.draft, objRenderer, vm::updateDraft)
                return@Column
            }
            // "New Sticker": the classic list, a category grid or My Stickers (full screen)
            (state.tool as? ToolState.PickSticker)?.let { t ->
                when (val cat = t.category) {
                    null -> StickerMenu(
                        app.stickerPacks, t.replaceId != null,
                        onBalloon = { vm.cancelTool(); openMenu = EditorMenu.BALLOON_SHAPES },
                        onTitle = { vm.cancelTool(); vm.newText() },
                        onCategory = vm::showStickerCategory,
                        onImportZip = { stickerZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    )
                    MY_STICKERS -> MyStickersGrid(library, onPick = { vm.placeLibrarySticker(it) }, onDelete = vm::deleteLibrarySticker,
                        onImport = { stickerImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    else -> StickerCategoryGrid(app.stickerPacks, cat, onPick = vm::placePackSticker, onDelete = { vm.deletePackSticker(it) },
                        onImportZip = { stickerZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) })
                }
                if (importingStickers) LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            if (insertTool?.stage == InsertStage.CROP) {
                val src by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, insertTool.draft.source) { value = vm.insertSourceBitmap() }
                Box(Modifier.weight(1f).fillMaxWidth()) { InsertCropView(insertTool, src, vm::setInsertCrop) }
                if (guideStage != null) InsertGuideDialog(guideStage!!) { off -> if (off) app.settings.updateInsertGuideOff(true); guideStage = null }
                return@Column
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(if (state.tool != null) ToolChrome.Work else Classic.Background)) {
                EditorCanvas(
                    image = if (comparing) state.before ?: state.preview else state.preview,
                    liveTiles = if (comparing) null else liveTiles,
                    crop = state.tool as? ToolState.Crop,
                    cropPixelSize = state.outputWidth to state.outputHeight,
                    onCropRect = vm::setCropRect,
                    twoFingerNavigation = state.tool is ToolState.Draw || state.tool is ToolState.Mask || insertTool?.stage == InsertStage.CUTOUT ||
                        (state.tool as? ToolState.Adjust)?.op?.let { op -> op.id == "warp_brush" || op.params.any { it is ParamSpec.Point } } == true,
                ) { zoom ->
                    if (insertTool != null) {
                        // Place Picture: blue circles or (Perspective Tool) blue diamonds on the picture
                        val placing = insertTool.stage == InsertStage.PLACE
                        ObjectLayer(
                            objects = state.displayObjects(),
                            selectedId = if (placing) insertTool.draft.id else null,
                            renderer = objRenderer,
                            assetsVersion = state.assetsVersion,
                            zoom = zoom,
                            interactive = placing,
                            mode = if (insertTool.perspective) TransformMode.PERSPECTIVE else TransformMode.SCALE_ROTATE,
                            onSelect = { },
                            onLive = vm::insertLive,
                            onCommit = { vm.insertDragEnd() },
                            onTapSelected = { },
                            // 1.1.0: a tap on the bottom-right handle (circle or diamond) switches size ↔ perspective
                            onHandleTap = { c -> if (c == 2) vm.toggleInsertPerspective() },
                            diamonds = insertTool.perspective,
                            redraw = insertLive,
                            liveTiles = cutTiles,
                        )
                        if (insertTool.stage == InsertStage.CUTOUT) CutoutLayer(
                            insertTool.draft, objRenderer, zoom, insertTool.brushSize, insertTool.brushSquare,
                            onLive = vm::cutStrokeLive, onCommit = vm::cutStrokeCommit,
                        )
                    } else ObjectLayer(
                        objects = state.displayObjects(),
                        selectedId = state.selectedId,
                        renderer = objRenderer,
                        assetsVersion = state.assetsVersion,
                        zoom = zoom,
                        interactive = state.tool == null,
                        mode = state.transformMode,
                        onHandleTap = { transformMenu = true },
                        onSelect = vm::selectObject,
                        onLive = vm::liveObject,
                        onCommit = vm::commitLive,
                        onTapSelected = vm::editSelected,
                    )
                    when (val t = state.tool) {
                        is ToolState.Draw -> DrawLayer(
                            t.eyedropper, null, null, onLive = { pts -> vm.strokeLive(pts, zoom) }, onStroke = { pts -> vm.addStroke(pts, zoom) }, onSample = vm::sampleColor,
                        )
                        is ToolState.Mask -> if (!t.choosingEffect) DrawLayer(
                            false, state.maskOverlay, maskTiles, onLive = { pts -> vm.strokeLive(pts) }, onStroke = { pts -> vm.addStroke(pts) }, onSample = { _, _ -> },
                        )
                        is ToolState.Adjust -> if (t.op.id == "warp_brush") WarpOverlay(t.params, vm::updateParam) else PointHandles(t.op.params, t.params, zoom, vm::updateParam)
                        else -> Unit
                    }
                }
                // transformation modes of the selected object (blue handle)
                if (state.tool == null && state.selectedId != null) {
                    if (transformMenu) TransformModeMenu(
                        state.transformMode, onPick = { vm.setTransformMode(it); transformMenu = false },
                        onReset = { vm.resetTransformSelected(); transformMenu = false }, onDismiss = { transformMenu = false },
                    )
                    else if (state.transformStart != null) TransformModeBar(state.transformMode, onMenu = { transformMenu = true }, onCancel = vm::cancelTransform, onDone = vm::finishTransform)
                }
                if (insertTool?.stage == InsertStage.CUTOUT && cutPanel) CutoutBrushPanel(insertTool, { sz, hd, sq -> vm.setCutBrush(sz, hd, sq) }, onClose = { cutPanel = false })
                if (insertTool != null && guideStage != null) InsertGuideDialog(guideStage!!) { off -> if (off) app.settings.updateInsertGuideOff(true); guideStage = null }
                (state.tool as? ToolState.Draw)?.let { t ->
                    val p = paintPanel
                    if (p != null && !t.eyedropper) PaintDropPanel(
                        p, t, vm.historyStages(), { id -> context.getString(Labels.op(id)) }, app.settings.paintColors, app.settings::addPaintColor,
                        onKind = vm::setDrawKind, onBrush = vm::setBrush, onStage = vm::setHistoryStage,
                        onEyedropper = { vm.setEyedropper(!t.eyedropper) }, onClose = { paintPanel = null },
                        onCompare = { on -> if (on) vm.ensureBefore(); comparing = on },
                    )
                    if (t.eyedropper) Text(
                        stringResource(R.string.eyedropper_hint), color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(androidx.compose.ui.graphics.Color(0xCC1E1E1E)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (state.loading || state.exporting) CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.fatalError?.let { Text(it.text(context), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (!state.loading && state.fatalError == null) Box(Modifier.fillMaxWidth().background(Classic.Menu)) {
                Column(Modifier.navigationBarsPadding()) {
                    // the classic slider bar is already minimal: no expand/collapse handle for it
                    val classicBar = (state.tool as? ToolState.Adjust)?.let { usesClassicChrome(it) && !allOptions } == true || state.tool is ToolState.Draw || state.tool is ToolState.Crop || state.tool is ToolState.InsertPicture
                    val info = if (classicBar) null else panelInfo(state, context, vm)
                    val expanded = info == null || panelPrefs.expanded(info)
                    if (info != null && !expanded) ExpandHandle { panelPrefs.set(info, true) }
                    else {
                    if (info != null) CollapseHandle { panelPrefs.set(info, false) }
                    when (val t = state.tool) {
                        is ToolState.Adjust -> if (usesClassicChrome(t) && !allOptions) ClassicToolSliderBar(t, activeParam, vm::updateParam)
                        else AdjustPanel(t, vm::updateParam, vm::resetParams, vm::applyTool, vm::cancelTool) { on ->
                            if (on) vm.ensureBefore(); comparing = on
                        }
                        is ToolState.Crop -> CropSliderBar(t, vm::setStraighten)
                        is ToolState.InsertPicture -> if (t.stage == InsertStage.PLACE) InsertOpacityBar(t, vm::setInsertOpacity)
                        ToolState.Orient -> OrientPanel(vm::orient, vm::cancelTool)
                        is ToolState.EditText -> TextEditorPanel(
                            t, fonts, typeface, state.photoDate, phrases = { app.phrases.list() }, onPhrasesChanged = {}, phraseOps = phraseOps,
                            onChange = vm::updateDraft, onImportFont = { fontPicker.launch(FONT_MIME_TYPES) },
                            onApply = vm::applyTool, onCancel = vm::cancelTool, renderer = objRenderer,
                        )
                        is ToolState.EditBalloon -> BalloonEditorPanel(
                            t, objRenderer, fonts, typeface, onChange = vm::updateDraft,
                            onImportFont = { fontPicker.launch(FONT_MIME_TYPES) },
                            onApply = vm::applyTool, onCancel = vm::cancelTool,
                        )
                        is ToolState.PickSticker -> Unit // full-screen sticker menu above
                        is ToolState.Draw -> Unit // classic Paint: bar and drop-down panels at the top
                        is ToolState.Mask -> MaskPanel(
                            t, maskEffects(app.registry.all),
                            { op -> context.getString(Labels.op(op.id)) },
                            onUpdate = vm::updateMask, onBrush = vm::setBrush, onUndo = vm::undoStroke, onRedo = vm::redoStroke,
                            onNext = vm::applyTool, onEffect = vm::applyEffectWithMask, onCancel = vm::cancelTool,
                        )
                        is ToolState.StyleSticker -> StickerStylePanel(t, app.stickers, onChange = vm::updateDraft, onApply = vm::applyTool, onCancel = vm::cancelTool)
                        else -> state.selected?.let { sel ->
                            ObjectMenu(sel, state.stretchMode, ObjectActions(
                                edit = vm::editSelected, duplicate = vm::duplicateSelected, delete = vm::deleteSelected,
                                arrange = vm::arrangeSelected, flip = vm::flipSelected, reset = vm::resetTransformSelected,
                                toggleStretch = vm::toggleStretchMode, replace = { vm.openStickerPicker(replace = true) }, transformMenu = { transformMenu = true }, vectorStyle = vm::openVectorStyle,
                                opacityLive = vm::setOpacityLive, opacityCommit = { vm.commitLive("opacity") }, done = { vm.selectObject(null) },
                            ))
                        } ?: ClassicBottomBar(listOf(
                            BarItem(stringResource(R.string.menu_adjust), { Icon(Icons.Filled.Tune, null, tint = Classic.Text) }, { openMenu = EditorMenu.ADJUST }),
                            BarItem(stringResource(R.string.menu_effect), { Icon(Icons.Filled.AutoFixHigh, null, tint = Classic.Text) }, { openMenu = EditorMenu.EFFECT }),
                            BarItem(stringResource(R.string.menu_sticker), { Icon(Icons.Filled.ChatBubble, null, tint = Classic.Text) }, { vm.openStickerPicker() }),
                            BarItem(stringResource(R.string.export), { Icon(Icons.Filled.Share, null, tint = Classic.Text) }, { openMenu = EditorMenu.EXPORT },
                                enabled = !state.exporting),
                        ))
                    }
                    }
                }
            }
        }
        }
    }

    if (state.tool == ToolState.Resize) {
        if (resizeMore) ResizeDialog(state.outputWidth, state.outputHeight, onApply = { w, h, m, bg -> resizeMore = false; vm.applyResize(w, h, m, bg) }, onDismiss = { resizeMore = false; vm.cancelTool() })
        else SetSizeDialog(state.outputWidth, state.outputHeight, onApply = { w, h, m, bg -> vm.applyResize(w, h, m, bg) }, onMore = { resizeMore = true }, onDismiss = vm::cancelTool)
    }
    if (state.tool == ToolState.Export) {
        ExportSheet(state.outputWidth, state.outputHeight, state.sourceHasAlpha, exportTarget, onDismiss = vm::cancelTool) { options, target ->
            if (target == ExportTarget.DOCUMENT) {
                pendingExport = options
                vm.cancelTool()
                val name = app.exports.suggestedName(state.title, vm.exportFormatFor(options).extension)
                if (vm.exportFormatFor(options) == ImageIo.Format.PNG) savePng.launch(name) else saveJpeg.launch(name)
            } else vm.export(options, target)
        }
    }
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.restore_original)) },
        text = { Text(stringResource(R.string.restore_original_confirm)) },
        confirmButton = { TextButton(onClick = { confirmRestore = false; vm.restoreOriginal() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
    )
}


/**
 * Effects offered after painting a mask, as in the original: adjustments, colour, artistic, photo
 * and misc, plus "Make transparent"; no distortions, frames or geometric tools.
 */
private fun maskEffects(all: List<ImageOp>): List<ImageOp> {
    val cats = listOf(OpCategory.ADJUST, OpCategory.COLOR, OpCategory.ARTISTIC, OpCategory.PHOTO, OpCategory.MISC)
    return all.filter { !it.geometric && (it.category in cats || it.id == "make_transparent") }
        .sortedBy { if (it.id == "make_transparent") -1 else cats.indexOf(it.category) }
}

/**
 * Minimizable panel description for the open tool (or the selected object). Canvas-centred tools
 * start minimized; tools where you type or pick from a list start expanded.
 */
@Composable
private fun panelInfo(state: EditorUiState, context: Context, vm: EditorViewModel): PanelInfo? {
    fun s(id: Int) = context.getString(id)
    val undoRedo: @Composable RowScope.() -> Unit = {
        IconButton(onClick = vm::undoStroke) { Icon(Icons.AutoMirrored.Filled.Undo, s(R.string.undo)) }
        IconButton(onClick = vm::redoStroke) { Icon(Icons.AutoMirrored.Filled.Redo, s(R.string.redo)) }
    }
    return when (val t = state.tool) {
        is ToolState.Adjust -> PanelInfo("adjust", s(Labels.op(t.op.id)), true, vm::cancelTool, vm::applyTool)
        is ToolState.Crop -> PanelInfo("crop", s(R.string.op_crop), false, vm::cancelTool, vm::applyTool)
        ToolState.Orient -> PanelInfo("orient", s(R.string.op_orient), true, vm::cancelTool, null)
        is ToolState.EditText -> PanelInfo("text", s(if (t.isNew) R.string.title_new else R.string.title_edit), true, vm::cancelTool, vm::applyTool)
        is ToolState.EditBalloon -> PanelInfo("balloon", s(if (t.isNew) R.string.wordballoon_new else R.string.wordballoon_edit), true, vm::cancelTool, vm::applyTool)
        is ToolState.PickSticker -> null // a list to pick from: always expanded
        is ToolState.StyleSticker -> PanelInfo("sticker_style", s(R.string.sticker_set_style), true, vm::cancelTool, vm::applyTool)
        is ToolState.Draw -> PanelInfo("draw", s(when (t.kind) {
            DrawKind.BRUSH -> R.string.brush; DrawKind.MARKER -> R.string.marker
            DrawKind.ERASER -> R.string.eraser_transparent; DrawKind.HISTORY -> R.string.history_paint
        }), false, vm::cancelTool, vm::applyTool, undoRedo)
        is ToolState.Mask -> if (t.choosingEffect) null else PanelInfo("mask", s(R.string.mask_title), false, vm::cancelTool, vm::applyTool, undoRedo)
        null -> state.selected?.let {
            PanelInfo("object", s(R.string.object_selected), false, { vm.selectObject(null) }, null) {
                IconButton(onClick = vm::editSelected) { Icon(Icons.Filled.Edit, s(R.string.edit)) }
                IconButton(onClick = vm::duplicateSelected) { Icon(Icons.Filled.ContentCopy, s(R.string.action_duplicate)) }
                IconButton(onClick = vm::deleteSelected) { Icon(Icons.Filled.Delete, s(R.string.action_delete)) }
            }
        }
        else -> null
    }
}

/** Title and ← / ✓ actions of the classic top bar while a tool is open. */
private class ToolBarInfo(val title: String, val onCancel: () -> Unit, val onApply: (() -> Unit)?)

private fun toolBar(state: EditorUiState, context: Context, vm: EditorViewModel): ToolBarInfo? {
    fun s(id: Int) = context.getString(id)
    return when (val t = state.tool) {
        null, ToolState.Export, ToolState.Resize -> null
        is ToolState.Adjust -> ToolBarInfo(s(Labels.op(t.op.id)), vm::cancelTool, vm::applyTool)
        is ToolState.Crop -> ToolBarInfo(s(R.string.op_crop), vm::cancelTool, vm::applyTool)
        ToolState.Orient -> ToolBarInfo(s(R.string.op_orient), vm::cancelTool, null)
        is ToolState.EditText -> ToolBarInfo(s(if (t.isNew) R.string.title_new else R.string.title_edit), vm::cancelTool, vm::applyTool)
        is ToolState.EditBalloon -> ToolBarInfo(s(if (t.isNew) R.string.wordballoon_new else R.string.wordballoon_edit), vm::cancelTool, vm::applyTool)
        is ToolState.PickSticker -> when (val c = t.category) {
            null -> ToolBarInfo(s(if (t.replaceId != null) R.string.sticker_replace else R.string.sticker_new), vm::cancelTool, null)
            MY_STICKERS -> ToolBarInfo(s(R.string.my_stickers), { vm.showStickerCategory(null) }, null)
            else -> ToolBarInfo(app.bocadillo.core.model.StickerCategories.all.firstOrNull { it.id == c }?.name(java.util.Locale.getDefault().language) ?: c, { vm.showStickerCategory(null) }, null)
        }
        is ToolState.StyleSticker -> ToolBarInfo(s(when (t.vector) { VectorStyleKind.SHAPE -> R.string.set_shape_style; VectorStyleKind.LINE -> R.string.set_line_style; null -> R.string.sticker_set_style }), vm::cancelTool, vm::applyTool)
        is ToolState.Draw -> ToolBarInfo(s(when (t.kind) {
            DrawKind.BRUSH -> R.string.brush; DrawKind.MARKER -> R.string.marker
            DrawKind.ERASER -> R.string.eraser_transparent; DrawKind.HISTORY -> R.string.history_paint
        }), vm::cancelTool, vm::applyTool)
        is ToolState.Mask -> ToolBarInfo(s(R.string.mask_title), vm::cancelTool, if (t.choosingEffect) null else vm::applyTool)
        else -> null
    }
}

/** Types offered by the font picker: TrueType, OpenType and ZIP archives of them. */
val FONT_MIME_TYPES = arrayOf("font/ttf", "font/otf", "font/sfnt", "font/*", "application/x-font-ttf", "application/x-font-otf",
    "application/vnd.ms-opentype", "application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
private fun FontImportReportDialog(r: app.bocadillo.data.FontRepository.ImportReport, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.fonts_report_title)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { Text(stringResource(R.string.fonts_report_summary4, r.added.size, r.duplicates.size, r.invalid.size, r.errors.size), modifier = Modifier.semantics { contentDescription = "fonts_report_summary" }) }
                if (r.added.isNotEmpty()) items(r.added.size) { i -> Text("✓ ${r.added[i]}", fontSize = 13.sp) }
                if (r.invalid.isNotEmpty()) {
                    item { Text(stringResource(R.string.fonts_report_invalid), modifier = Modifier.padding(top = 10.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    items(r.invalid.size) { i -> Text("• ${r.invalid[i].first}: ${r.invalid[i].second}", fontSize = 13.sp) }
                }
                if (r.errors.isNotEmpty()) {
                    item { Text(stringResource(R.string.fonts_report_errors), modifier = Modifier.padding(top = 10.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    items(r.errors.size) { i -> Text("• ${r.errors[i].first}: ${r.errors[i].second}", fontSize = 13.sp) }
                }
                if (r.duplicates.isNotEmpty()) {
                    item { Text(stringResource(R.string.fonts_report_dups), modifier = Modifier.padding(top = 10.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                    items(r.duplicates.size) { i -> Text("• ${r.duplicates[i]}", fontSize = 13.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) } },
    )
}
