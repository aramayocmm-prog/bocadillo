package app.bocadillo.editor

import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.model.TextAlign
import app.bocadillo.core.model.TextObject
import app.bocadillo.core.model.TextStyle
import app.bocadillo.data.FontOption
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Ready-made title styles (original designs). Applying one keeps size and alignment. */
val TEXT_PRESETS: List<Pair<Int, TextStyle>> = listOf(
    R.string.preset_classic to TextStyle(font = "system:sans-serif-black", color = 0xFFFFFFFF.toInt(), outlineColor = 0xFF000000.toInt(), outlineWidth = 0.14f),
    R.string.preset_comic to TextStyle(font = "system:casual", bold = true, color = 0xFFFFD23F.toInt(), outlineColor = 0xFF000000.toInt(), outlineWidth = 0.16f),
    R.string.preset_clean to TextStyle(font = "bundled:Poppins-Bold.ttf", color = 0xFF111111.toInt(), outlineWidth = 0f),
    R.string.preset_soft_shadow to TextStyle(font = "bundled:Poppins-Bold.ttf", color = 0xFFFFFFFF.toInt(), outlineWidth = 0f, shadowColor = 0x99000000.toInt(), shadowDx = 0.05f, shadowDy = 0.06f, shadowBlur = 0.10f),
    R.string.preset_neon to TextStyle(font = "system:sans-serif-medium", color = 0xFFE8FFE0.toInt(), outlineWidth = 0.04f, outlineColor = 0xFF39FF14.toInt(), shadowColor = 0xFF39FF14.toInt(), shadowDx = 0f, shadowDy = 0f, shadowBlur = 0.35f),
    R.string.preset_label to TextStyle(font = "system:sans-serif-medium", color = 0xFFFFFFFF.toInt(), outlineWidth = 0f, backgroundColor = 0xCC000000.toInt()),
    R.string.preset_impact to TextStyle(font = "system:sans-serif-black", color = 0xFFE53935.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.18f, shadowColor = 0x66000000, shadowDx = 0.05f, shadowDy = 0.05f, shadowBlur = 0.04f),
    R.string.preset_elegant to TextStyle(font = "bundled:Lora-Variable.ttf", italic = true, color = 0xFFFFFFFF.toInt(), outlineWidth = 0f, shadowColor = 0x80000000.toInt(), shadowDx = 0.03f, shadowDy = 0.03f, shadowBlur = 0.06f),
    R.string.preset_typewriter to TextStyle(font = "system:serif-monospace", color = 0xFF222222.toInt(), outlineWidth = 0f, backgroundColor = 0xEEFFFFFF.toInt()),
    R.string.preset_pastel to TextStyle(font = "bundled:Poppins-Bold.ttf", color = 0xFFF48FB1.toInt(), outlineColor = 0xFFFFFFFF.toInt(), outlineWidth = 0.12f),
)

fun TextStyle.applyPreset(p: TextStyle) = p.copy(size = size, align = align, lineHeight = lineHeight)

@Composable
fun TextEditorPanel(
    tool: ToolState.EditText,
    fonts: List<FontOption>,
    typeface: (String) -> Typeface,
    photoDate: Long?,
    phrases: () -> List<String>,
    onPhrasesChanged: (List<String>) -> Unit,
    phraseOps: PhraseOps,
    onChange: (TextObject) -> Unit,
    onImportFont: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    renderer: app.bocadillo.render.ObjectRenderer? = null,
) {
    val o = tool.draft
    val st = o.style
    fun style(f: (TextStyle) -> TextStyle) = onChange(o.copy(style = f(st)))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var phrasesOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        PanelHeader(stringResource(if (tool.isNew) R.string.title_new else R.string.title_edit), onCancel, onApply)
        OutlinedTextField(
            value = o.text, onValueChange = { onChange(o.copy(text = it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text(stringResource(R.string.hint_text)) }, minLines = 1, maxLines = 4,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { phrasesOpen = true }, label = { Text(stringResource(R.string.insert_phrase)) })
            AssistChip(onClick = { dateOpen = true }, label = { Text(stringResource(R.string.insert_datetime)) })
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf(R.string.tab_styles, R.string.tab_font, R.string.tab_color, R.string.tab_outline, R.string.tab_shadow, R.string.tab_layout)
                .forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) }) }
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            when (tab) {
                0 -> if (renderer != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, p) in TEXT_PRESETS) TextStyleTile(p, stringResource(label), renderer, { style { it.applyPreset(p) } })
                } else PresetRow(typeface) { p -> style { it.applyPreset(p) } }
                1 -> FontList(fonts, st.font, typeface, onPick = { ref -> style { it.copy(font = ref) } }, onImport = onImportFont) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(st.bold, { style { it.copy(bold = !it.bold) } }, label = { Text(stringResource(R.string.bold)) })
                        Box(Modifier.size(8.dp))
                        FilterChip(st.italic, { style { it.copy(italic = !it.italic) } }, label = { Text(stringResource(R.string.italic)) })
                    }
                }
                2 -> {
                    ColorPicker(stringResource(R.string.text_color), st.color, { c -> style { it.copy(color = c) } })
                    ColorPicker(stringResource(R.string.background_box), st.backgroundColor, { c -> style { it.copy(backgroundColor = c) } })
                }
                3 -> {
                    LabeledSlider(stringResource(R.string.outline_width), st.outlineWidth, 0f..0.4f, { "${(it * 100).roundToInt()}%" }) { v -> style { it.copy(outlineWidth = v) } }
                    ColorPicker(stringResource(R.string.outline_color), st.outlineColor, { c -> style { it.copy(outlineColor = c) } })
                }
                4 -> {
                    val on = (st.shadowColor ushr 24) != 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.shadow), Modifier.weight(1f))
                        Switch(on, { enable -> style { it.copy(shadowColor = if (enable) 0x99000000.toInt() else 0) } })
                    }
                    if (on) {
                        ColorPicker(stringResource(R.string.shadow_color), st.shadowColor, { c -> style { it.copy(shadowColor = c) } })
                        LabeledSlider(stringResource(R.string.shadow_x), st.shadowDx, -0.5f..0.5f) { v -> style { it.copy(shadowDx = v) } }
                        LabeledSlider(stringResource(R.string.shadow_y), st.shadowDy, -0.5f..0.5f) { v -> style { it.copy(shadowDy = v) } }
                        LabeledSlider(stringResource(R.string.shadow_blur), st.shadowBlur, 0f..0.6f) { v -> style { it.copy(shadowBlur = v) } }
                    }
                }
                else -> {
                    LabeledSlider(stringResource(R.string.text_size), st.size, 0.02f..0.4f, { "${(it * 100).roundToInt()}" }) { v -> style { it.copy(size = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((a, label) in listOf(TextAlign.LEFT to R.string.align_left, TextAlign.CENTER to R.string.align_center, TextAlign.RIGHT to R.string.align_right))
                            FilterChip(st.align == a, { style { it.copy(align = a) } }, label = { Text(stringResource(label)) })
                    }
                    LabeledSlider(stringResource(R.string.line_height), st.lineHeight, 0.6f..2.5f) { v -> style { it.copy(lineHeight = v) } }
                    LabeledSlider(stringResource(R.string.letter_spacing), st.letterSpacing, -0.1f..0.5f) { v -> style { it.copy(letterSpacing = v) } }
                    LabeledSlider(stringResource(R.string.circular_wrap), o.curve, -360f..360f, { "${it.roundToInt()}°" }) { v -> onChange(o.copy(curve = v.roundToInt().toFloat())) }
                    LabeledSlider(stringResource(R.string.opacity), o.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> onChange(o.copy(opacity = v)) }
                }
            }
        }
    }

    fun insert(s: String) = onChange(o.copy(text = if (o.text.isEmpty()) s else o.text + (if (o.text.endsWith("\n")) "" else " ") + s))
    if (phrasesOpen) PhrasesDialog(phrases(), phraseOps, onPhrasesChanged, onDismiss = { phrasesOpen = false }) { insert(it); phrasesOpen = false }
    if (dateOpen) DateTimeDialog(photoDate, onDismiss = { dateOpen = false }) { insert(it); dateOpen = false }
}

@Composable
private fun PresetRow(typeface: (String) -> Typeface, onPick: (TextStyle) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, p) in TEXT_PRESETS) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(p) }) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF7A8A99)),
                    contentAlignment = Alignment.Center,
                ) {
                    if ((p.backgroundColor ushr 24) != 0) Box(Modifier.size(48.dp, 30.dp).background(Color(p.backgroundColor), RoundedCornerShape(6.dp)))
                    Text("Aa", color = Color(p.color), fontSize = 24.sp, fontFamily = FontFamily(typeface(p.font)))
                }
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
fun FontList(fonts: List<FontOption>, selected: String, typeface: (String) -> Typeface, onPick: (String) -> Unit, onImport: () -> Unit, extra: @Composable () -> Unit = {}) {
    // light list of the classic Font screen: grey group headers, white rows with the name drawn
    // in its own font, the selected row highlighted, "More Fonts ›" (import) at the end
    val light = app.bocadillo.ui.theme.Classic
    val ctxApp = androidx.compose.ui.platform.LocalContext.current.applicationContext
    Column(Modifier.background(light.LightRow)) {
        extra()
        var lastKind: FontOption.Kind? = null
        for (f in fonts) {
            if (f.kind != lastKind) {
                lastKind = f.kind
                Text(
                    stringResource(when (f.kind) { FontOption.Kind.SYSTEM -> R.string.fonts_system; FontOption.Kind.BUNDLED -> R.string.fonts_bundled; FontOption.Kind.IMPORTED -> R.string.fonts_imported }),
                    color = light.LightHeaderText, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(light.LightHeader).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            val sel = f.ref == selected
            Text(
                f.displayName, fontFamily = FontFamily(typeface(f.ref)), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = androidx.compose.ui.graphics.Color(0xFF111111),
                modifier = Modifier.fillMaxWidth().background(if (sel) light.LightMore else light.LightRow).clickable { onPick(f.ref); (ctxApp as? app.bocadillo.BocadilloApp)?.settings?.addFontRecent(f.ref) }.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = light.LightDivider)
        }
        Row(
            Modifier.fillMaxWidth().background(light.LightMore).clickable(onClick = onImport).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.more_fonts), color = androidx.compose.ui.graphics.Color(0xFF111111), fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("›", color = androidx.compose.ui.graphics.Color(0xFF555555), fontSize = 20.sp)
        }
    }
}

/** Add / edit / delete saved phrases. */
class PhraseOps(val add: (String) -> List<String>, val update: (Int, String) -> List<String>, val remove: (Int) -> List<String>)

@Composable
internal fun PhrasesDialog(initial: List<String>, ops: PhraseOps, onChanged: (List<String>) -> Unit, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var list by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) } // index -1 = new
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phrases_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(list) { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p, Modifier.weight(1f).clickable { onPick(p) }.padding(vertical = 10.dp))
                        IconButton(onClick = { editing = i to p }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                        IconButton(onClick = { list = ops.remove(i); onChanged(list) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { editing = -1 to "" }) { Text(stringResource(R.string.phrase_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    editing?.let { (i, text) ->
        var t by remember(i) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (i < 0) R.string.phrase_new else R.string.phrase_edit)) },
            text = { OutlinedTextField(t, { t = it }, singleLine = false, maxLines = 3) },
            confirmButton = {
                TextButton(enabled = t.isNotBlank(), onClick = {
                    list = if (i < 0) ops.add(t) else ops.update(i, t); onChanged(list); editing = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Localized date/time formats, like the original's list (month-day, weekday, © year, …). */
private val DATE_SKELETONS = listOf("MMMMd", "EEEE", "yyyy", "EEEEMMMMd", "EEEEMMMMdyyyy", "jmm", "yMd", "yMMMdjmm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimeDialog(photoDate: Long?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var source by remember { mutableIntStateOf(if (photoDate != null) 0 else 1) } // 0 photo, 1 today, 2 custom
    var custom by remember { mutableStateOf<Long?>(null) }
    var picker by remember { mutableStateOf(false) }
    val time = when (source) { 0 -> photoDate ?: System.currentTimeMillis(); 2 -> custom ?: System.currentTimeMillis(); else -> System.currentTimeMillis() }
    val locale = Locale.getDefault()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_datetime)) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (photoDate != null) FilterChip(source == 0, { source = 0 }, label = { Text(stringResource(R.string.date_photo)) })
                    FilterChip(source == 1, { source = 1 }, label = { Text(stringResource(R.string.date_today)) })
                    FilterChip(source == 2, { source = 2; picker = true }, label = { Text(stringResource(R.string.date_custom)) })
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(DATE_SKELETONS) { _, sk ->
                        val pattern = DateFormat.getBestDateTimePattern(locale, sk)
                        var s = java.text.SimpleDateFormat(pattern, locale).format(Date(time))
                        if (sk == "yyyy") s = "© $s"
                        Text(s, Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    if (picker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = custom ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = { custom = state.selectedDateMillis; picker = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}
