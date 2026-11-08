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
import androidx.compose.foundation.layout.Box
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
import app.bocadillo.ui.classic.ClassicButton
import app.bocadillo.ui.classic.ClassicMenuRow
import app.bocadillo.ui.classic.ClassicTopBar
import app.bocadillo.ui.theme.Classic
import kotlinx.coroutines.Dispatchers
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
                page == HomePage.MAIN -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(28.dp))
                    AppLogo(Modifier.fillMaxWidth(0.82f))
                    Spacer(Modifier.height(24.dp))
                    val iconMod = Modifier.size(28.dp)
                    ClassicButton(stringResource(R.string.home_edit_photo), { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Filled.Image, null, iconMod, tint = Classic.Text) }
                    Spacer(Modifier.height(10.dp))
                    ClassicButton(stringResource(R.string.home_get_photo), { val u = app.exports.newCameraUri(); cameraUri = u.toString(); camera.launch(u) },
                        Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Filled.PhotoCamera, null, iconMod, tint = Classic.Text) }
                    Spacer(Modifier.height(10.dp))
                    ClassicButton(stringResource(R.string.home_recent_photos), { page = HomePage.RECENT },
                        Modifier.fillMaxWidth()) { Icon(Icons.Filled.PhotoLibrary, null, iconMod, tint = Classic.Text) }
                    Spacer(Modifier.height(10.dp))
                    ClassicButton(stringResource(R.string.home_more), { page = HomePage.MORE },
                        Modifier.fillMaxWidth()) { Icon(Icons.Filled.Settings, null, iconMod, tint = Classic.Text) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                page == HomePage.MORE -> {
                    ClassicTopBar(stringResource(R.string.home_more), onBack = { page = HomePage.MAIN })
                    Column(Modifier.fillMaxSize().background(Classic.Menu)) {
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
    if (aboutOpen) {
        val text = remember {
            listOf("fonts/CREDITS.txt", "noto/NOTICE.txt").joinToString("\n\n") { path ->
                runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
            } + "\n\nAndroidSVG (com.caverock:androidsvg) — Apache License 2.0.\n" +
                "Full license texts: assets/fonts/OFL.txt, assets/noto/LICENSE-APACHE-2.0.txt, assets/noto/LICENSE-region-flags.txt."
        }
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text(stringResource(R.string.licenses)) },
            text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text(stringResource(R.string.close)) } },
        )
    }
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

/** Own logo (not PicSay's): a speech balloon with the app name. */
@Composable
private fun AppLogo(modifier: Modifier) {
    Box(modifier.aspectRatio(1.6f), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val body = androidx.compose.ui.geometry.Rect(w * 0.04f, h * 0.06f, w * 0.96f, h * 0.78f)
            val tail = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.28f, h * 0.70f); lineTo(w * 0.18f, h * 0.98f); lineTo(w * 0.44f, h * 0.74f); close()
            }
            val shape = androidx.compose.ui.graphics.Path().apply { addOval(body); op(this, tail, androidx.compose.ui.graphics.PathOperation.Union) }
            drawPath(shape, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color(0xFFDCE6F0))))
            drawPath(shape, androidx.compose.ui.graphics.Color(0xFF1565C0), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.018f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 30.dp)) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1565C0))) { append("Boca") }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFFF9A825))) { append("dillo") }
                },
                fontSize = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            )
            Text(stringResource(R.string.home_tagline), color = androidx.compose.ui.graphics.Color(0xFF37474F), fontSize = 14.sp)
        }
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
                androidx.compose.material3.Switch(settings.hideStatusBar, settings::setHideStatusBar)
            }
            ClassicMenuRow(stringResource(if (reset) R.string.settings_reset_panels_done else R.string.settings_reset_panels), { settings.resetPanels(); reset = true }) {
                Icon(Icons.Filled.History, null, Modifier.size(26.dp), tint = Classic.Text)
            }
            app.bocadillo.ui.classic.ClassicSection(stringResource(R.string.settings_sharing))
            OutlinedTextField(settings.shareSubject, settings::setShareSubject, Modifier.fillMaxWidth().padding(14.dp, 8.dp),
                label = { Text(stringResource(R.string.settings_share_subject)) }, singleLine = true)
            OutlinedTextField(settings.shareSignature, settings::setShareSignature, Modifier.fillMaxWidth().padding(14.dp, 8.dp),
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
