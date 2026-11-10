package app.bocadillo.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.model.LibrarySticker
import app.bocadillo.core.model.StickerCategories
import app.bocadillo.data.StickerPacks
import app.bocadillo.ui.classic.ClassicButton
import app.bocadillo.ui.classic.ClassicDims
import app.bocadillo.ui.classic.ClassicIcon
import app.bocadillo.ui.classic.ClassicIconView
import app.bocadillo.ui.classic.ClassicMenuRow
import app.bocadillo.ui.theme.Classic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Id used for "My Stickers" (personal pictures) in the sticker menu. */
const val MY_STICKERS = "__mine__"

/**
 * "New Sticker" menu: Word Balloon, Title, the 32 classic categories (always visible, even when
 * still empty), My Stickers and the library import.
 */
@Composable
fun StickerMenu(
    packs: StickerPacks,
    replacing: Boolean,
    onBalloon: () -> Unit,
    onTitle: () -> Unit,
    onCategory: (String) -> Unit,
    onImportZip: () -> Unit,
) {
    val lang = Locale.getDefault().language
    val index = packs.index
    val counts = remember(index) { index.counts() }
    LazyColumn(Modifier.fillMaxSize().background(Classic.Menu)) {
        if (!replacing) {
            item { ClassicMenuRow(stringResource(R.string.word_balloon), onBalloon) { ClassicIconView(ClassicIcon.BALLOON) } }
            item { ClassicMenuRow(stringResource(R.string.title_sticker), onTitle) { ClassicIconView(ClassicIcon.TITLE) } }
        }
        items(StickerCategories.all, key = { it.id }) { cat ->
            val n = counts[cat.id] ?: 0
            ClassicMenuRow(cat.name(lang), { onCategory(cat.id) }, note = if (n == 0) stringResource(R.string.sticker_pending_short) else null) {
                val first = remember(index, cat.id) { index.inCategory(cat.id).firstOrNull() }
                if (first != null) PackThumb(packs, first, ClassicDims.RowIcon) else EmptyIcon()
            }
        }
        item { ClassicMenuRow(stringResource(R.string.my_stickers), { onCategory(MY_STICKERS) }) { Icon(Icons.Filled.Collections, null, tint = Classic.Text, modifier = Modifier.size(30.dp)) } }
        item { ClassicMenuRow(stringResource(R.string.sticker_import_library), onImportZip) { Icon(Icons.Filled.Unarchive, null, tint = Classic.Text, modifier = Modifier.size(30.dp)) } }
    }
}

@Composable
private fun EmptyIcon() {
    Box(Modifier.size(ClassicDims.RowIcon).border(1.dp, Classic.ButtonBorder, RoundedCornerShape(6.dp)))
}

@Composable
fun PackThumb(packs: StickerPacks, s: LibrarySticker, size: androidx.compose.ui.unit.Dp) {
    val bmp by produceState<ImageBitmap?>(null, s.file) {
        value = withContext(Dispatchers.IO) { packs.thumbnail(s, 192)?.asImageBitmap() }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, s.name, Modifier.fillMaxSize()) }
    }
}

/** Grid of one category; empty categories say they are pending import. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerCategoryGrid(
    packs: StickerPacks,
    categoryId: String,
    onPick: (LibrarySticker) -> Unit,
    onDelete: (LibrarySticker) -> Unit,
    onImportZip: () -> Unit,
) {
    val list = remember(packs.index, categoryId) { packs.index.inCategory(categoryId) }
    var confirm by remember { mutableStateOf<LibrarySticker?>(null) }
    if (list.isEmpty()) {
        Column(
            Modifier.fillMaxSize().background(Classic.Menu).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.sticker_pending_long), color = Classic.TextDim, textAlign = TextAlign.Center, fontSize = 15.sp)
            ClassicButton(stringResource(R.string.sticker_import_library), onImportZip, Modifier.padding(top = 16.dp))
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(84.dp), Modifier.fillMaxSize().background(Classic.Menu).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(list, key = { it.id }) { s ->
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Classic.Cell)
                    .combinedClickable(onClick = { onPick(s) }, onLongClick = { confirm = s }).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) { PackThumb(packs, s, 72.dp) }
        }
    }
    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.sticker_delete_from_library, s.name)) },
            confirmButton = { TextButton({ onDelete(s); confirm = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "My Stickers": pictures the user imported one by one (kept from earlier versions). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyStickersGrid(files: List<File>, onPick: (File) -> Unit, onDelete: (File) -> Unit, onImport: () -> Unit) {
    var confirm by remember { mutableStateOf<File?>(null) }
    LazyVerticalGrid(
        GridCells.Adaptive(84.dp), Modifier.fillMaxSize().background(Classic.Menu).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Classic.Cell).combinedClickable(onClick = onImport).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, null, tint = Classic.Text, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.sticker_import), color = Classic.Text, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
        items(files, key = { it.path }) { f ->
            val bmp by produceState<ImageBitmap?>(null, f.path) {
                value = withContext(Dispatchers.IO) { runCatching { StickerPacks.renderToBitmap(f.readBytes(), app.bocadillo.core.model.StickerFormat.PNG, 192)?.asImageBitmap() }.getOrNull() }
            }
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Classic.Cell)
                    .combinedClickable(onClick = { onPick(f) }, onLongClick = { confirm = f }).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) { bmp?.let { Image(it, null, Modifier.fillMaxSize()) } }
        }
    }
    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.sticker_delete_mine)) },
            confirmButton = { TextButton({ onDelete(f); confirm = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Result of a library import: counts, duplicates and every error, as reported by the importer. */
@Composable
fun StickerImportReportDialog(report: StickerPacks.Report?, error: String?, onDismiss: () -> Unit) {
    if (report == null && error == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sticker_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (error != null) Text(stringResource(R.string.sticker_import_failed, error))
                if (report != null) {
                    Text(stringResource(R.string.sticker_import_summary, report.source, report.added, report.updated, report.unchanged, report.duplicates.size, report.errors.size, report.total))
                    if (report.duplicates.isNotEmpty()) {
                        Text(stringResource(R.string.sticker_import_duplicates), modifier = Modifier.padding(top = 10.dp), color = Classic.Accent)
                        report.duplicates.take(50).forEach { Text("• $it", fontSize = 12.sp) }
                        if (report.duplicates.size > 50) Text("…", fontSize = 12.sp)
                    }
                    if (report.errors.isNotEmpty()) {
                        Text(stringResource(R.string.sticker_import_errors), modifier = Modifier.padding(top = 10.dp), color = Classic.Accent)
                        report.errors.take(100).forEach { Text("• $it", fontSize = 12.sp) }
                        if (report.errors.size > 100) Text("…", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
