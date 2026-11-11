package app.bocadillo.home

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.IconButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bocadillo.BocadilloApp
import app.bocadillo.R
import app.bocadillo.core.model.ProjectEntry
import app.bocadillo.editor.ColorRow
import app.bocadillo.editor.text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import app.bocadillo.ui.classic.ClassicButton
import app.bocadillo.ui.classic.ClassicMenuRow
import app.bocadillo.ui.classic.ClassicTopBar
import app.bocadillo.ui.theme.Classic
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Menu
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BocadilloApp
    val index by vm.index.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    val largeImport by vm.largeImport.collectAsStateWithLifecycle()
    largeImport?.let { LargeImportDialog(it, vm::chooseImportSize) }
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var blankDialog by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val untitled = stringResource(R.string.untitled)
    var aboutOpen by remember { mutableStateOf(false) }
    // Crash report from the previous run, if any (read once, then deleted).
    var crash by remember { mutableStateOf(runCatching { app.crashFile.takeIf { it.exists() }?.readText() }.getOrNull()) }

    LaunchedEffect(vm) { vm.openProject.collect { onOpen(it) } }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.text(context)) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.importPicture(uri, untitled)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) vm.importPicture(Uri.parse(u), untitled)
    }

    // Pages of the home screen (reference screens 1 and 18 plus the recent-projects list)
    var page by rememberSaveable { mutableStateOf(HomePage.MAIN) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var getPicture by rememberSaveable { mutableStateOf(false) }
    var pickToSave by rememberSaveable { mutableStateOf(false) }
    var saveId by rememberSaveable { mutableStateOf<String?>(null) }
    val openArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importProject(uri) }
    val saveArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = saveId; if (uri != null && id != null) vm.exportProject(id, uri); saveId = null
    }
    BackHandler(enabled = page != HomePage.MAIN || settingsOpen) {
        when { settingsOpen -> settingsOpen = false; pickToSave -> { pickToSave = false; page = HomePage.MORE }; else -> page = HomePage.MAIN }
    }

    Scaffold(containerColor = Classic.Background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                settingsOpen -> SettingsPage(app.settings) { settingsOpen = false }
                page == HomePage.MAIN -> Box(Modifier.fillMaxSize().background(Classic.Home)) {
                    // menu (≡) at the top left opens the list of other actions; the last edited
                    // picture sits at the top right; logo and "Get a Picture" in the middle
                    IconButton(onClick = { page = HomePage.MORE }, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                        Icon(Icons.Filled.Menu, stringResource(R.string.home_more), tint = Classic.TextDim, modifier = Modifier.size(30.dp))
                    }
                    index.recent().firstOrNull()?.let { last ->
                        val thumb = vm.thumbFile(last.id)
                        val bmp = remember(last.id, last.modifiedAt) { runCatching { android.graphics.BitmapFactory.decodeFile(thumb.path)?.asImageBitmap() }.getOrNull() }
                        if (bmp != null) androidx.compose.foundation.Image(
                            bmp, last.title, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp, 58.dp)
                                .border(2.dp, Classic.Text).clickable { onOpen(last.id) },
                        )
                    }
                    // logo, two-line title, glass button and, at the bottom, the short description;
                    // scrolls when space is short (landscape, very large text) instead of overlapping
                    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 56.dp)) {
                        val minH = maxHeight
                        val logo = minOf(maxWidth * 0.62f, maxHeight * 0.36f, 320.dp)
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minH).navigationBarsPadding().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Spacer(Modifier.height(4.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AppLogo(Modifier.size(logo))
                                Spacer(Modifier.height(14.dp))
                                AppTitle(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(28.dp))
                                GlassOpenButton(stringResource(R.string.open_image), enabled = !busy) { getPicture = true }
                                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(0.66f).padding(top = 16.dp))
                            }
                            Text(
                                stringResource(R.string.home_tagline_mac), color = Classic.TextDim, fontSize = 13.sp, lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.widthIn(max = 460.dp).padding(top = 24.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
                page == HomePage.MORE -> {
                    ClassicTopBar(stringResource(R.string.home_more), onBack = { page = HomePage.MAIN })
                    Column(Modifier.fillMaxSize().background(Classic.Menu).verticalScroll(rememberScrollState())) {
                        val ic = Modifier.size(28.dp)
                        ClassicMenuRow(stringResource(R.string.more_new_blank), { blankDialog = true }) { Icon(Icons.Filled.NoteAdd, null, ic, tint = Classic.Text) }
                        ClassicMenuRow(stringResource(R.string.more_open_project), { openArchive.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Icon(Icons.Filled.FolderOpen, null, ic, tint = Classic.Text) }
                        ClassicMenuRow(stringResource(R.string.more_save_project), { pickToSave = true; page = HomePage.RECENT }) { Icon(Icons.Filled.Save, null, ic, tint = Classic.Text) }
                        ClassicMenuRow(stringResource(R.string.more_recent_projects), { page = HomePage.RECENT }) { Icon(Icons.Filled.History, null, ic, tint = Classic.Text) }
                        ClassicMenuRow(stringResource(R.string.settings), { settingsOpen = true }) { Icon(Icons.Filled.Settings, null, ic, tint = Classic.Text) }
                        ClassicMenuRow(stringResource(R.string.licenses), { aboutOpen = true }) { Icon(Icons.Filled.Info, null, ic, tint = Classic.Text) }
                    }
                }
                else -> {
                    ClassicTopBar(stringResource(if (pickToSave) R.string.more_pick_project else R.string.home_recent_photos),
                        onBack = { if (pickToSave) { pickToSave = false; page = HomePage.MORE } else page = HomePage.MAIN })
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    TabRow(selectedTabIndex = tab, containerColor = Classic.Bar) {
                        Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.home_recent)) })
                        Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.home_favorites)) })
                    }
                    val items = if (tab == 0) index.recent() else index.favorites()
                    if (items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(if (tab == 0) R.string.home_empty else R.string.home_empty_favorites), Modifier.padding(32.dp), color = Classic.TextDim)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(140.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(items, key = { it.id }) { e ->
                                ProjectCard(e, vm.thumbFile(e.id), onOpen = {
                                    if (pickToSave) { pickToSave = false; page = HomePage.MORE; saveId = e.id; saveArchive.launch(e.title.ifBlank { "project" } + ".bcproj") }
                                    else onOpen(e.id)
                                },
                                    onFavorite = { vm.setFavorite(e.id, !e.favorite) },
                                    onDuplicate = { vm.duplicate(e.id, " (2)") },
                                    onDelete = { deleteId = e.id })
                            }
                        }
                    }
                }
            }
        }
    }

    recovery?.let {
        AlertDialog(
            onDismissRequest = vm::dismissRecovery,
            title = { Text(stringResource(R.string.home_recover_title)) },
            text = { Text(stringResource(R.string.home_recover_message)) },
            confirmButton = { TextButton(onClick = vm::acceptRecovery) { Text(stringResource(R.string.action_open)) } },
            dismissButton = { TextButton(onClick = vm::dismissRecovery) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = { TextButton(onClick = { vm.delete(id); deleteId = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { crash = null; app.crashFile.delete() },
            title = { Text(stringResource(R.string.crash_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.crash_message), style = MaterialTheme.typography.bodyMedium)
                    Text(report, Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report)
                    context.startActivity(android.content.Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.crash_share)) }
            },
            dismissButton = { TextButton(onClick = { crash = null; app.crashFile.delete() }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (aboutOpen) androidx.compose.ui.window.Dialog(
        onDismissRequest = { aboutOpen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // full screen also in landscape: let the dialog window reach the display-cutout side
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            }
        }
        CreditsScreen(onClose = { aboutOpen = false })
    }
    if (getPicture) GetPictureDialog(
        onDismiss = { getPicture = false },
        onGallery = { getPicture = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onCamera = { getPicture = false; val u = app.exports.newCameraUri(); cameraUri = u.toString(); camera.launch(u) },
        onImport = { getPicture = false; openArchive.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        onBlank = { getPicture = false; blankDialog = true },
    )
    if (blankDialog) BlankDialog(onDismiss = { blankDialog = false }) { w, h, c ->
        blankDialog = false; vm.createBlank(w, h, c, untitled)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(e: ProjectEntry, thumb: File, onOpen: () -> Unit, onFavorite: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val bmp by produceState<ImageBitmap?>(null, thumb, e.modifiedAt) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumb.path)?.asImageBitmap() }
    }
    Card(Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (e.favorite) Icon(Icons.Filled.Star, null, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(e.title, Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_open)) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text(stringResource(if (e.favorite) R.string.action_favorite_remove else R.string.action_favorite_add)) }, onClick = { menu = false; onFavorite() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
        }
    }
}

private enum class HomePage { MAIN, RECENT, MORE }

/** "Import large picture…" (reference WA0130): Fast / Normal / Maximum with the resulting size. */
@Composable
private fun LargeImportDialog(info: HomeViewModel.LargeImport, onChoose: (Pair<Int, Int>?) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { onChoose(null) }) {
        Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFFAFAFA)).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.import_large_title), color = Classic.Accent, fontSize = 21.sp, modifier = Modifier.padding(20.dp, 18.dp))
            androidx.compose.material3.HorizontalDivider(color = Classic.Accent, thickness = 2.dp)
            val dark = androidx.compose.ui.graphics.Color(0xFF212121)
            val dim = androidx.compose.ui.graphics.Color(0xFF616161)
            for ((label, size, desc) in listOf(
                Triple(R.string.import_fast, info.fast, R.string.import_fast_desc),
                Triple(R.string.import_normal, info.normal, R.string.import_normal_desc),
                Triple(R.string.import_maximum, info.maximum, R.string.import_maximum_desc),
            )) {
                Column(Modifier.fillMaxWidth().clickable { onChoose(size) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), color = dark, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Text("${size.first}×${size.second}", color = dark, fontSize = 20.sp)
                    }
                    Text(stringResource(desc), color = dim, fontSize = 14.sp)
                }
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE0E0E0))
            }
            if (info.maximum.first < info.fullW) Text(
                stringResource(R.string.import_maximum_limited, info.fullW, info.fullH), color = dim, fontSize = 12.sp, modifier = Modifier.padding(20.dp, 8.dp),
            )
        }
    }
}

/** "Get Picture from" chooser: white dialog with a blue title and four rows. */
@Composable
private fun GetPictureDialog(onDismiss: () -> Unit, onGallery: () -> Unit, onCamera: () -> Unit, onImport: () -> Unit, onBlank: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFFAFAFA))) {
            Text(stringResource(R.string.get_picture_from), color = Classic.Accent, fontSize = 21.sp, modifier = Modifier.padding(20.dp, 18.dp))
            androidx.compose.material3.HorizontalDivider(color = Classic.Accent, thickness = 2.dp)
            val dark = androidx.compose.ui.graphics.Color(0xFF212121)
            for ((label, icon, action) in listOf(
                Triple(R.string.get_from_gallery, Icons.Filled.PhotoLibrary, onGallery),
                Triple(R.string.get_from_camera, Icons.Filled.PhotoCamera, onCamera),
                Triple(R.string.get_from_import, Icons.Filled.FolderOpen, onImport),
                Triple(R.string.more_new_blank, Icons.Filled.NoteAdd, onBlank),
            )) {
                Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = androidx.compose.ui.graphics.Color(0xFF616161), modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(label), color = dark, fontSize = 18.sp)
                }
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE0E0E0))
            }
        }
    }
}

/**
 * Official MAC PRODUCCIONES logo (supplied by the owner), shown unchanged. The circular variant
 * only has the black outside of the ring made transparent.
 */
@Composable
private fun AppLogo(modifier: Modifier) {
    Image(
        painterResource(R.drawable.logo_mac_circle), contentDescription = stringResource(R.string.logo_description),
        modifier = modifier.aspectRatio(1f), contentScale = ContentScale.Fit,
    )
}

/**
 * Title in two centred lines: "MAC" large with the logo's blue → violet → magenta glow, and
 * "Editor para MEMES" smaller on one line. Each line shrinks to fit its width (never cut or
 * ellipsized), whatever the screen size or system font scale.
 */
@Composable
private fun AppTitle(modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val maxPx = with(dens) { maxWidth.toPx() }
        fun fit(text: String, start: Float, style: androidx.compose.ui.text.TextStyle): androidx.compose.ui.unit.TextUnit {
            var sz = start
            while (sz > 10f) {
                val w = measurer.measure(text, style.copy(fontSize = sz.sp), maxLines = 1, softWrap = false).size.width
                if (w <= maxPx * 0.96f) break
                sz -= 1f
            }
            return sz.sp
        }
        val glow = androidx.compose.ui.graphics.Brush.linearGradient(listOf(
            androidx.compose.ui.graphics.Color(0xFF5EC8FF), androidx.compose.ui.graphics.Color(0xFFB07BFF), androidx.compose.ui.graphics.Color(0xFFFF4FD8),
        ))
        val big = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = 6.sp, brush = glow,
            shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color(0x996A3DE8), androidx.compose.ui.geometry.Offset(0f, 4f), 18f))
        val small = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 1.5.sp, color = androidx.compose.ui.graphics.Color(0xFFEDE7FF))
        val l1 = "MAC"; val l2 = stringResource(R.string.app_title_line2)
        val s1 = remember(maxPx, dens.fontScale) { fit(l1, 54f, big) }
        val s2 = remember(maxPx, dens.fontScale, l2) { fit(l2, 22f, small) }
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(l1, style = big.copy(fontSize = s1), maxLines = 1, softWrap = false)
            Text(l2, style = small.copy(fontSize = s2), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * "ABRIR IMAGEN" (approved option 1, luminous glass): dark glass with a sober blue → violet
 * gradient and a magenta touch, a thin glowing border, rounded corners, a pictures icon on the
 * left and a chevron on the right, white single-line text. A short press animation (skipped when
 * the system has animations turned off).
 */
@Composable
internal fun GlassOpenButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !reduceMotion) 0.97f else 1f, androidx.compose.animation.core.tween(if (reduceMotion) 0 else 110), label = "press",
    )
    val shape = RoundedCornerShape(18.dp)
    val violet = androidx.compose.ui.graphics.Color(0xFF6A3DE8)
    // keep the label on one line at large font scales
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val size = (19f / maxOf(1f, fontScale / 1.15f)).sp
    Row(
        Modifier
            .widthIn(max = 420.dp).fillMaxWidth(0.86f).heightIn(min = 56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(14.dp, shape, ambientColor = violet, spotColor = violet)
            .clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                androidx.compose.ui.graphics.Color(0xF0142B6B), androidx.compose.ui.graphics.Color(0xF0301A78), androidx.compose.ui.graphics.Color(0xF05A1670),
            )))
            // glass reflection on the upper half
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color(0x33FFFFFF), 0.48f to androidx.compose.ui.graphics.Color(0x0DFFFFFF), 0.5f to androidx.compose.ui.graphics.Color.Transparent,
            ))
            .border(1.2.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                androidx.compose.ui.graphics.Color(0xFF5EC8FF), androidx.compose.ui.graphics.Color(0xFF9B7BFF), androidx.compose.ui.graphics.Color(0xFFFF4FD8),
            )), shape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(color = androidx.compose.ui.graphics.Color.White), enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PhotoLibrary, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(26.dp))
        Text(
            text, color = androidx.compose.ui.graphics.Color.White, fontSize = size, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            letterSpacing = 1.2.sp, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = androidx.compose.ui.graphics.Color(0xFFE6DDFF), modifier = Modifier.size(28.dp))
    }
}

/** Settings (FASE1 §2: Sharing and Editor preferences). */
@Composable
private fun SettingsPage(settings: app.bocadillo.data.AppSettings, onBack: () -> Unit) {
    var reset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Classic.Menu)) {
        ClassicTopBar(stringResource(R.string.settings), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            app.bocadillo.ui.classic.ClassicSection(stringResource(R.string.settings_editor))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_hide_status_bar), color = Classic.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(settings.hideStatusBar, settings::updateHideStatusBar)
            }
            ClassicMenuRow(stringResource(if (reset) R.string.settings_reset_panels_done else R.string.settings_reset_panels), { settings.resetPanels(); reset = true }) {
                Icon(Icons.Filled.History, null, Modifier.size(26.dp), tint = Classic.Text)
            }
            app.bocadillo.ui.classic.ClassicSection(stringResource(R.string.settings_sharing))
            OutlinedTextField(settings.shareSubject, settings::updateShareSubject, Modifier.fillMaxWidth().padding(14.dp, 8.dp),
                label = { Text(stringResource(R.string.settings_share_subject)) }, singleLine = true)
            OutlinedTextField(settings.shareSignature, settings::updateShareSignature, Modifier.fillMaxWidth().padding(14.dp, 8.dp),
                label = { Text(stringResource(R.string.settings_share_signature)) }, minLines = 2)
        }
    }
}

@Composable
private fun BlankDialog(onDismiss: () -> Unit, onCreate: (Int, Int, Int) -> Unit) {
    var w by remember { mutableStateOf("1080") }
    var h by remember { mutableStateOf("1080") }
    var color by remember { mutableStateOf(0xFFFFFFFF.toInt()) }
    val wv = w.toIntOrNull(); val hv = h.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blank_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), label = { Text(stringResource(R.string.blank_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("  ×  ")
                    OutlinedTextField(h, { h = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), label = { Text(stringResource(R.string.blank_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                ColorRow(stringResource(R.string.blank_color), color) { color = it }
            }
        },
        confirmButton = {
            TextButton(enabled = wv != null && hv != null && wv in 16..10000 && hv in 16..10000, onClick = { onCreate(wv!!, hv!!, color) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * "Licencias y créditos" (☰ → Más… → Licencias y créditos): app name and real version from the
 * build, the project manager, the logo credit, a note that the app is independent, and the
 * third-party licences that must be shown (fonts, emoji artwork, libraries).
 */
@Composable
private fun CreditsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val info = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
    val version = info?.versionName ?: "?"
    val code = info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    var thirdParty by remember { mutableStateOf(false) }
    val notices = remember {
        listOf("fonts/CREDITS.txt", "noto/NOTICE.txt").joinToString("\n\n") { path ->
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().background(Classic.Menu).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        ClassicTopBar(stringResource(R.string.licenses), onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_mac_circle), stringResource(R.string.logo_description), Modifier.size(120.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), color = Classic.Text, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(stringResource(R.string.credits_version, version, code), color = Classic.TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Text("Marcelo Aramayo", color = Classic.Text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(stringResource(R.string.credits_role), color = Classic.TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.credits_logo), color = Classic.TextDim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.credits_independent), color = Classic.TextDim, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { thirdParty = !thirdParty }) {
                Text(stringResource(if (thirdParty) R.string.credits_hide_third_party else R.string.credits_third_party))
            }
            if (thirdParty) {
                Text(stringResource(R.string.credits_libraries), color = Classic.Text, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(notices, color = Classic.TextDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Text(stringResource(R.string.credits_full_texts), color = Classic.TextDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}
