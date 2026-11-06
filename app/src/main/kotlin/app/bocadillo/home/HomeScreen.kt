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

    LaunchedEffect(vm) { vm.openProject.collect { onOpen(it) } }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.text(context)) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.importPicture(uri, untitled)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) vm.importPicture(Uri.parse(u), untitled)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { IconButton(onClick = { aboutOpen = true }) { Icon(Icons.Filled.Info, stringResource(R.string.licenses)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f), enabled = !busy,
                ) { Icon(Icons.Filled.AddPhotoAlternate, null); Text(" " + stringResource(R.string.home_open_photo), maxLines = 1) }
                FilledTonalButton(
                    onClick = { val u = app.exports.newCameraUri(); cameraUri = u.toString(); camera.launch(u) },
                    enabled = !busy,
                ) { Icon(Icons.Filled.PhotoCamera, stringResource(R.string.home_camera)) }
                FilledTonalButton(onClick = { blankDialog = true }, enabled = !busy) {
                    Icon(Icons.Filled.CropFree, stringResource(R.string.home_blank))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.home_recent)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.home_favorites)) })
            }
            val items = if (tab == 0) index.recent() else index.favorites()
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (tab == 0) R.string.home_empty else R.string.home_empty_favorites), Modifier.padding(32.dp))
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
                        ProjectCard(e, vm.thumbFile(e.id), onOpen = { onOpen(e.id) },
                            onFavorite = { vm.setFavorite(e.id, !e.favorite) },
                            onDuplicate = { vm.duplicate(e.id, " (2)") },
                            onDelete = { deleteId = e.id })
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
