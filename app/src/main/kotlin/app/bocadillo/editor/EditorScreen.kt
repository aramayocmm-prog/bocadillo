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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private enum class Menu(val label: Int, val icon: ImageVector) {
    ADJUST(R.string.menu_adjust, Icons.Filled.Tune),
    EFFECTS(R.string.menu_effects, Icons.Filled.AutoAwesome),
    COLOR(R.string.menu_color, Icons.Filled.Palette),
    TRANSFORM(R.string.menu_transform, Icons.Filled.Transform),
    TEXT(R.string.menu_text, Icons.Filled.TextFields),
    BALLOON(R.string.menu_balloon, Icons.Filled.ChatBubble),
    STICKER(R.string.menu_sticker, Icons.Filled.EmojiEmotions),
    DRAW(R.string.menu_draw, Icons.Filled.Brush),
    MORE(R.string.menu_more, Icons.Filled.MoreHoriz),
}

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
    var menu by rememberSaveable { mutableStateOf(Menu.ADJUST) }
    var family by rememberSaveable { mutableStateOf(OpCategory.DISTORT) }
    var confirmRestore by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.text(context)) } }
    LaunchedEffect(vm) {
        vm.shares.collect { s ->
            val send = Intent(Intent.ACTION_SEND).setType(s.mimeType).putExtra(Intent.EXTRA_STREAM, s.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }

    val leave = { vm.closeEditor(); onBack() }
    BackHandler { if (state.tool != null) vm.cancelTool() else leave() }

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
    val phraseOps = remember {
        PhraseOps(
            add = { p -> app.phrases.add(p).also { phrasesVersion++ } },
            update = { i, p -> app.phrases.update(i, p).also { phrasesVersion++ } },
            remove = { i -> app.phrases.remove(i).also { phrasesVersion++ } },
        )
    }

    Scaffold(
        topBar = {
            // hidden while a tool is open: more room for the picture
            if (state.tool == null || state.tool == ToolState.Export || state.tool == ToolState.Resize) TopAppBar(
                title = { Text(state.title.ifEmpty { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo && state.tool == null) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = vm::redo, enabled = state.canRedo && state.tool == null) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                    IconButton(onClick = { vm.openPanel(ToolState.Export) }, enabled = !state.loading && !state.exporting && state.fatalError == null) {
                        Icon(Icons.Filled.IosShare, stringResource(R.string.export))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EditorCanvas(
                    image = if (comparing) state.before ?: state.preview else state.preview,
                    crop = state.tool as? ToolState.Crop,
                    cropPixelSize = state.outputWidth to state.outputHeight,
                    onCropRect = vm::setCropRect,
                    twoFingerNavigation = state.tool is ToolState.Draw || state.tool is ToolState.Mask ||
                        (state.tool as? ToolState.Adjust)?.op?.params?.any { it is ParamSpec.Point } == true,
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
                        is ToolState.Adjust -> PointHandles(t.op.params, t.params, zoom, vm::updateParam)
                        else -> Unit
                    }
                }
                if (state.loading || state.exporting) CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.fatalError?.let { Text(it.text(context), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (!state.loading && state.fatalError == null) BottomPanel {
                Column(Modifier.navigationBarsPadding()) {
                    val info = panelInfo(state, context, vm)
                    val expanded = info == null || panelPrefs.expanded(info)
                    if (info != null && !expanded) CompactToolBar(info) { panelPrefs.set(info, true) }
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
                        is ToolState.PickSticker -> StickerPickerPanel(
                            app.stickers, objRenderer, t.replaceId != null, library, app.noto,
                            onPick = vm::pickSticker, onPickNoto = vm::pickNoto, onPickLibrary = { vm.placeLibrarySticker(it) }, onDeleteLibrary = vm::deleteLibrarySticker,
                            onImport = { stickerImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onCancel = vm::cancelTool,
                        )
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
                        } ?: MainMenu(menu, { m ->
                            menu = m
                            when (m) {
                                Menu.STICKER -> vm.openStickerPicker()
                                else -> Unit
                            }
                        }, family, { family = it }, app.registry.all, objRenderer, onBalloon = vm::newBalloon, onText = { vm.newText(style = it) }, onDraw = vm::openDraw, onMask = { vm.openMask(protect = false) }, onTool = { op ->
                            when (op.id) {
                                TransformOps.Orient.id -> vm.openPanel(ToolState.Orient)
                                TransformOps.Resize.id -> vm.openPanel(ToolState.Resize)
                                else -> vm.openTool(op)
                            }
                        }, onRestore = { confirmRestore = true })
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
        ExportSheet(state.outputWidth, state.outputHeight, state.sourceHasAlpha, onDismiss = vm::cancelTool) { options, target ->
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

private val EFFECT_FAMILIES = listOf(OpCategory.DISTORT, OpCategory.ARTISTIC, OpCategory.PHOTO, OpCategory.FRAMES, OpCategory.MISC)

/**
 * Effects offered after painting a mask, as in the original: adjustments, colour, artistic, photo
 * and misc, plus "Make transparent"; no distortions, frames or geometric tools.
 */
private fun maskEffects(all: List<ImageOp>): List<ImageOp> {
    val cats = listOf(OpCategory.ADJUST, OpCategory.COLOR, OpCategory.ARTISTIC, OpCategory.PHOTO, OpCategory.MISC)
    return all.filter { !it.geometric && (it.category in cats || it.id == "make_transparent") }
        .sortedBy { if (it.id == "make_transparent") -1 else cats.indexOf(it.category) }
}

@Composable
private fun MainMenu(menu: Menu, onMenu: (Menu) -> Unit, family: OpCategory, onFamily: (OpCategory) -> Unit, ops: List<ImageOp>, renderer: ObjectRenderer, onBalloon: (BalloonShape) -> Unit, onText: (TextStyle) -> Unit, onDraw: (DrawKind) -> Unit, onMask: () -> Unit, onTool: (ImageOp) -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (menu == Menu.BALLOON) BalloonShapeRow(null, renderer, onBalloon)
        if (menu == Menu.EFFECTS) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in EFFECT_FAMILIES) androidx.compose.material3.FilterChip(
                selected = c == family, onClick = { onFamily(c) }, label = { Text(stringResource(Labels.category(c))) },
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tools: List<ImageOp> = when (menu) {
                Menu.ADJUST -> ops.filter { it.category == OpCategory.ADJUST }
                Menu.EFFECTS -> ops.filter { it.category == family }
                Menu.COLOR -> ops.filter { it.category == OpCategory.COLOR }
                Menu.TRANSFORM -> ops.filter { it.category == OpCategory.TRANSFORM }
                Menu.MORE -> ops.filter { it.category == OpCategory.MODERN }
                Menu.TEXT, Menu.BALLOON, Menu.STICKER, Menu.DRAW -> emptyList()
            }
            if (menu == Menu.TEXT) {
                TextStyleTile(TextStyle(), stringResource(R.string.new_text), renderer, { onText(TextStyle()) })
                for ((label, p) in TEXT_PRESETS) TextStyleTile(p, stringResource(label), renderer, { onText(p) })
            }
            if (menu == Menu.DRAW) {
                for ((k, label) in listOf(
                    DrawKind.BRUSH to R.string.brush, DrawKind.MARKER to R.string.marker,
                    DrawKind.ERASER to R.string.eraser_transparent, DrawKind.HISTORY to R.string.history_paint,
                )) DrawTile(ToolPreviews.DrawDemo.valueOf(k.name), stringResource(label), { onDraw(k) })
                DrawTile(ToolPreviews.DrawDemo.MASK, stringResource(R.string.mask_title), onMask)
            }
            if (menu == Menu.MORE) DrawTile(ToolPreviews.DrawDemo.RESTORE, stringResource(R.string.restore_original), onRestore)
            for (op in tools) OpTile(op, stringResource(Labels.op(op.id))) { onTool(op) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (m in Menu.entries) {
                val selected = m == menu
                Column(
                    Modifier.clickable { onMenu(m) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(m.icon, null, tint = tint)
                    Text(stringResource(m.label), style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
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
