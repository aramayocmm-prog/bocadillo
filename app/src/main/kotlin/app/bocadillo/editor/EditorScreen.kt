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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bocadillo.BocadilloApp
import app.bocadillo.R
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.imaging.ops.TransformOps
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
@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
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

    val leave = { vm.closeEditor(); onBack() }
    BackHandler {
        when {
            openMenu == EditorMenu.BALLOON_SHAPES -> openMenu = EditorMenu.STICKER
            openMenu != null -> openMenu = null
            (state.tool as? ToolState.PickSticker)?.category != null -> vm.showStickerCategory(null)
            state.tool != null -> vm.cancelTool()
            state.selectedId != null -> vm.selectObject(null)
            else -> leave()
        }
    }
    var rotateFlipDialog by rememberSaveable { mutableStateOf(false) }
    var styleDetails by rememberSaveable { mutableStateOf(false) }
    var composerPhrases by remember { mutableStateOf(false) }
    // a new composer always starts in the light screen
    val toolKind = state.tool?.let { it::class.simpleName + ((it as? ToolState.EditBalloon)?.draft?.id ?: (it as? ToolState.EditText)?.draft?.id ?: "") }
    androidx.compose.runtime.LaunchedEffect(toolKind) { styleDetails = false }
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
    remember(state.assetsVersion) { objRenderer.assets = vm.assetsSnapshot(); 0 }
    val fonts = remember(state.assetsVersion) { app.fonts.all() }
    val typeface = { ref: String -> app.fonts.typeface(ref, false, false, objRenderer.assets) }
    var phrasesVersion by remember { mutableIntStateOf(0) }
    val panelPrefs = rememberPanelPrefs()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri) { ref ->
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
                bar != null && ((state.tool as? ToolState.EditBalloon)?.isNew == true || (state.tool as? ToolState.EditText)?.isNew == true) && !styleDetails ->
                    ClassicTopBar(bar.title, onBack = bar.onCancel, onConfirm = bar.onApply, backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, greenConfirm = true)
                bar != null -> ClassicTopBar(bar.title, onBack = bar.onCancel, onConfirm = bar.onApply, actions = { info?.quick?.invoke(this) },
                    backIcon = if (bar.onApply == null && (state.tool as? ToolState.PickSticker)?.category == null) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack)
                else -> ClassicTopBar(
                    state.title.ifEmpty { stringResource(R.string.untitled) }, onBack = leave, onConfirm = leave,
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
            // "New Word Balloon" / "New Title": light full-screen composer until the user taps the
            // preview for every style option (then the usual panel over the photo)
            val newTool = state.tool
            if (newTool is ToolState.EditBalloon && newTool.isNew && !styleDetails) {
                NewBalloonScreen(newTool.draft, objRenderer, vm::updateDraft, onStylePreview = { styleDetails = true }, onPhrases = { composerPhrases = true })
                return@Column
            }
            if (newTool is ToolState.EditText && newTool.isNew && !styleDetails) {
                NewTitleScreen(newTool.draft, objRenderer, vm::updateDraft, onStylePreview = { styleDetails = true }, onPhrases = { composerPhrases = true })
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EditorCanvas(
                    image = if (comparing) state.before ?: state.preview else state.preview,
                    crop = state.tool as? ToolState.Crop,
                    cropPixelSize = state.outputWidth to state.outputHeight,
                    onCropRect = vm::setCropRect,
                    twoFingerNavigation = state.tool is ToolState.Draw || state.tool is ToolState.Mask ||
                        (state.tool as? ToolState.Adjust)?.op?.let { op -> op.id == "warp_brush" || op.params.any { it is ParamSpec.Point } } == true,
                ) { zoom ->
                    ObjectLayer(
                        objects = state.displayObjects(),
                        selectedId = state.selectedId,
                        renderer = objRenderer,
                        assetsVersion = state.assetsVersion,
                        zoom = zoom,
                        interactive = state.tool == null,
                        stretchMode = state.stretchMode,
                        onSelect = vm::selectObject,
                        onLive = vm::liveObject,
                        onCommit = vm::commitLive,
                        onTapSelected = vm::editSelected,
                    )
                    when (val t = state.tool) {
                        is ToolState.Draw -> DrawLayer(
                            t.brush.size, androidx.compose.ui.graphics.Color(if (t.kind == DrawKind.ERASER) 0x88FFFFFF.toInt() else (t.brush.color and 0x00FFFFFF) or 0xAA000000.toInt()),
                            t.eyedropper, null, vm::addStroke, vm::sampleColor,
                        )
                        is ToolState.Mask -> if (!t.choosingEffect) DrawLayer(
                            t.brush.size, androidx.compose.ui.graphics.Color(if (t.protect) 0x88E53935.toInt() else 0x8843A047.toInt()),
                            false, state.maskOverlay, vm::addStroke, { _, _ -> },
                        )
                        is ToolState.Adjust -> if (t.op.id == "warp_brush") WarpOverlay(t.params, vm::updateParam) else PointHandles(t.op.params, t.params, zoom, vm::updateParam)
                        else -> Unit
                    }
                }
                if (state.loading || state.exporting) CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.fatalError?.let { Text(it.text(context), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (!state.loading && state.fatalError == null) Box(Modifier.fillMaxWidth().background(Classic.Menu)) {
                Column(Modifier.navigationBarsPadding()) {
                    val info = panelInfo(state, context, vm)
                    val expanded = info == null || panelPrefs.expanded(info)
                    if (info != null && !expanded) ExpandHandle { panelPrefs.set(info, true) }
                    else {
                    if (info != null) CollapseHandle { panelPrefs.set(info, false) }
                    when (val t = state.tool) {
                        is ToolState.Adjust -> AdjustPanel(t, vm::updateParam, vm::resetParams, vm::applyTool, vm::cancelTool) { on ->
                            if (on) vm.ensureBefore(); comparing = on
                        }
                        is ToolState.Crop -> CropPanel(t, vm::setCropPreset, vm::setStraighten, vm::applyTool, vm::cancelTool)
                        ToolState.Orient -> OrientPanel(vm::orient, vm::cancelTool)
                        is ToolState.EditText -> TextEditorPanel(
                            t, fonts, typeface, state.photoDate, phrases = { app.phrases.list() }, onPhrasesChanged = {}, phraseOps = phraseOps,
                            onChange = vm::updateDraft, onImportFont = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
                            onApply = vm::applyTool, onCancel = vm::cancelTool, renderer = objRenderer,
                        )
                        is ToolState.EditBalloon -> BalloonEditorPanel(
                            t, objRenderer, fonts, typeface, onChange = vm::updateDraft,
                            onImportFont = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
                            onApply = vm::applyTool, onCancel = vm::cancelTool,
                        )
                        is ToolState.PickSticker -> Unit // full-screen sticker menu above
                        is ToolState.Draw -> DrawPanel(
                            t, vm.historyStages(), { id -> context.getString(Labels.op(id)) },
                            onKind = vm::setDrawKind, onBrush = vm::setBrush, onStage = vm::setHistoryStage, onEyedropper = vm::setEyedropper,
                            onUndo = vm::undoStroke, onRedo = vm::redoStroke, onApply = vm::applyTool, onCancel = vm::cancelTool,
                        )
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
                                toggleStretch = vm::toggleStretchMode, replace = { vm.openStickerPicker(replace = true) },
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
        ResizeDialog(state.outputWidth, state.outputHeight, onApply = { w, h, m, bg -> vm.applyResize(w, h, m, bg) }, onDismiss = vm::cancelTool)
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
        is ToolState.StyleSticker -> ToolBarInfo(s(R.string.sticker_set_style), vm::cancelTool, vm::applyTool)
        is ToolState.Draw -> ToolBarInfo(s(when (t.kind) {
            DrawKind.BRUSH -> R.string.brush; DrawKind.MARKER -> R.string.marker
            DrawKind.ERASER -> R.string.eraser_transparent; DrawKind.HISTORY -> R.string.history_paint
        }), vm::cancelTool, vm::applyTool)
        is ToolState.Mask -> ToolBarInfo(s(R.string.mask_title), vm::cancelTool, if (t.choosingEffect) null else vm::applyTool)
        else -> null
    }
}
