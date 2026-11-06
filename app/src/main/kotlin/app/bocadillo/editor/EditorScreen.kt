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
import androidx.compose.material.icons.filled.ChatBubble
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
import app.bocadillo.core.model.TextObject
import app.bocadillo.data.ImageIo
import app.bocadillo.render.ObjectRenderer

private enum class Menu(val label: Int, val icon: ImageVector) {
    ADJUST(R.string.menu_adjust, Icons.Filled.Tune),
    COLOR(R.string.menu_color, Icons.Filled.Palette),
    TRANSFORM(R.string.menu_transform, Icons.Filled.Transform),
    TEXT(R.string.menu_text, Icons.Filled.TextFields),
    BALLOON(R.string.menu_balloon, Icons.Filled.ChatBubble),
    STICKER(R.string.menu_sticker, Icons.Filled.EmojiEmotions),
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
    val objRenderer = remember { ObjectRenderer(app.fonts, app.stickers) }
    remember(state.assetsVersion) { objRenderer.assets = vm.assetsSnapshot(); 0 }
    val fonts = remember(state.assetsVersion) { app.fonts.all() }
    val typeface = { ref: String -> app.fonts.typeface(ref, false, false, objRenderer.assets) }
    var phrasesVersion by remember { mutableIntStateOf(0) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri) { ref ->
            when (val t = vm.state.value.tool) {
                is ToolState.EditText -> vm.updateDraft(t.draft.copy(style = t.draft.style.copy(font = ref)))
                is ToolState.EditBalloon -> vm.updateDraft(t.draft.copy(style = t.draft.style.copy(font = ref)))
                else -> Unit
            }
        }
    }
    val stickerImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importStickerImage(uri)
    }
    val phraseOps = remember {
        PhraseOps(
            add = { p -> app.phrases.add(p).also { phrasesVersion++ } },
            update = { i, p -> app.phrases.update(i, p).also { phrasesVersion++ } },
            remove = { i -> app.phrases.remove(i).also { phrasesVersion++ } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
                }
                if (state.loading || state.exporting) CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.fatalError?.let { Text(it.text(context), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (!state.loading && state.fatalError == null) BottomPanel {
                Column(Modifier.navigationBarsPadding()) {
                    when (val t = state.tool) {
                        is ToolState.Adjust -> AdjustPanel(t, vm::updateParam, vm::resetParams, vm::applyTool, vm::cancelTool) { on ->
                            if (on) vm.ensureBefore(); comparing = on
                        }
                        is ToolState.Crop -> CropPanel(t, vm::setCropPreset, vm::setStraighten, vm::applyTool, vm::cancelTool)
                        ToolState.Orient -> OrientPanel(vm::orient, vm::cancelTool)
                        is ToolState.EditText -> TextEditorPanel(
                            t, fonts, typeface, state.photoDate, phrases = { app.phrases.list() }, onPhrasesChanged = {}, phraseOps = phraseOps,
                            onChange = vm::updateDraft, onImportFont = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
                            onApply = vm::applyTool, onCancel = vm::cancelTool,
                        )
                        is ToolState.EditBalloon -> BalloonEditorPanel(
                            t, fonts, typeface, onChange = vm::updateDraft,
                            onImportFont = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
                            onApply = vm::applyTool, onCancel = vm::cancelTool,
                        )
                        is ToolState.PickSticker -> StickerPickerPanel(app.stickers, objRenderer, t.replaceId != null, onPick = vm::pickSticker,
                            onImport = { stickerImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onCancel = vm::cancelTool)
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
                                Menu.TEXT -> vm.newText()
                                Menu.STICKER -> vm.openStickerPicker()
                                else -> Unit
                            }
                        }, app.registry.all, onBalloon = vm::newBalloon, onTool = { op ->
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

@Composable
private fun MainMenu(menu: Menu, onMenu: (Menu) -> Unit, ops: List<ImageOp>, onBalloon: (BalloonShape) -> Unit, onTool: (ImageOp) -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tools: List<ImageOp> = when (menu) {
                Menu.ADJUST -> ops.filter { it.category == OpCategory.ADJUST }
                Menu.COLOR -> ops.filter { it.category == OpCategory.COLOR }
                Menu.TRANSFORM -> ops.filter { it.category == OpCategory.TRANSFORM }
                Menu.MORE -> ops.filter { it.category == OpCategory.MODERN }
                Menu.TEXT, Menu.BALLOON, Menu.STICKER -> emptyList()
            }
            if (menu == Menu.BALLOON) for (shape in BalloonShape.entries) {
                AssistChip(onClick = { onBalloon(shape) }, label = { Text(stringResource(balloonShapeLabel(shape))) })
            }
            if (menu == Menu.MORE) AssistChip(onClick = onRestore, label = { Text(stringResource(R.string.restore_original)) })
            for (op in tools) AssistChip(onClick = { onTool(op) }, label = { Text(stringResource(Labels.op(op.id))) })
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
