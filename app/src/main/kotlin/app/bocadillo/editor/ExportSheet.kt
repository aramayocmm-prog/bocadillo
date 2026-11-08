package app.bocadillo.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bocadillo.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Export options: format (JPEG very high/high/medium, PNG, same as original — as in PicSay
 * Pro), JPEG quality, resolution and the background used to flatten transparency in JPEG.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    outputWidth: Int,
    outputHeight: Int,
    hasAlpha: Boolean,
    target: ExportTarget,
    onDismiss: () -> Unit,
    onExport: (ExportOptions, ExportTarget) -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(if (hasAlpha) ExportFormat.PNG else ExportFormat.JPEG_VERY_HIGH) }
    var quality by rememberSaveable { mutableStateOf(95) }
    var scale by rememberSaveable { mutableStateOf(100) }
    var bg by rememberSaveable { mutableStateOf(0xFFFFFFFF.toInt()) }
    val isJpeg = format == ExportFormat.JPEG_VERY_HIGH || format == ExportFormat.JPEG_HIGH || format == ExportFormat.JPEG_MEDIUM
    val options = ExportOptions(format, if (isJpeg) quality else 100, scale, bg)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((f, label) in listOf(
                    ExportFormat.JPEG_VERY_HIGH to R.string.fmt_jpeg_very_high, ExportFormat.JPEG_HIGH to R.string.fmt_jpeg_high,
                    ExportFormat.JPEG_MEDIUM to R.string.fmt_jpeg_medium, ExportFormat.PNG to R.string.fmt_png,
                    ExportFormat.ORIGINAL to R.string.fmt_original,
                )) FilterChip(selected = format == f, onClick = { format = f; if (f.quality < 100) quality = f.quality }, label = { Text(stringResource(label)) })
            }
            if (isJpeg) {
                Text(stringResource(R.string.export_quality, quality))
                Slider(value = quality.toFloat(), onValueChange = { quality = it.roundToInt().coerceIn(1, 100) }, valueRange = 1f..100f)
                if (hasAlpha) ColorRow(stringResource(R.string.export_background), bg) { bg = it }
            }
            Text(stringResource(R.string.export_size), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in listOf(100, 75, 50, 25)) {
                    val w = max(1, (outputWidth * p / 100f).roundToInt()); val h = max(1, (outputHeight * p / 100f).roundToInt())
                    FilterChip(selected = scale == p, onClick = { scale = p }, label = { Text(stringResource(R.string.export_size_option, p, w, h)) })
                }
            }
            // one action: the Export menu row already chose where the picture goes
            Button(onClick = { onExport(options, target) }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(if (target == ExportTarget.SHARE || target == ExportTarget.SET_AS) Icons.Filled.Share else Icons.Filled.SaveAlt, null)
                Text("  " + stringResource(when (target) {
                    ExportTarget.GALLERY -> R.string.export_menu_album; ExportTarget.DOCUMENT -> R.string.export_menu_file
                    ExportTarget.SHARE -> R.string.export_menu_share; ExportTarget.SET_AS -> R.string.export_menu_set_as
                }))
            }
        }
    }
}
