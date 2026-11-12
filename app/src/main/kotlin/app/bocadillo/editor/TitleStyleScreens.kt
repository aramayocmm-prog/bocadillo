package app.bocadillo.editor

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Search
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.model.TextAlign
import app.bocadillo.core.model.TextObject
import app.bocadillo.core.model.TextStyle
import app.bocadillo.data.FontOption
import app.bocadillo.render.ObjectRenderer
import app.bocadillo.ui.classic.ClassicSection
import app.bocadillo.ui.theme.Classic
import kotlin.math.roundToInt

/**
 * Detailed title style (opened by tapping the preview of New Title): the light preview stays on
 * top and every option is grouped below in the classic dark list — Font › (own screen), colour
 * and gradient, outline and second outline, shadow, background, layout, curve and date/time.
 */
@Composable
fun TitleStyleScreen(
    draft: TextObject,
    renderer: ObjectRenderer,
    fonts: List<FontOption>,
    typeface: (String) -> Typeface,
    onDraft: (TextObject) -> Unit,
    onFonts: () -> Unit,
    onDateTime: () -> Unit,
) {
    val st = draft.style
    fun style(f: (TextStyle) -> TextStyle) = onDraft(draft.copy(style = f(st)))
    val hint = stringResource(R.string.title_preview_hint)
    Column(Modifier.fillMaxSize().background(Classic.Menu)) {
        TitlePreview(draft, hint, renderer, Modifier.fillMaxWidth().height(84.dp))
        Column(Modifier.fillMaxWidth().weight(1f).imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // font: its own screen, as in the original
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onFonts).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tab_font), color = Classic.Text, fontSize = 19.sp, modifier = Modifier.weight(1f))
                val name = fonts.firstOrNull { it.ref == st.font }?.displayName ?: st.font.substringAfter(':')
                Text(name, color = Classic.TextDim, fontSize = 18.sp, fontFamily = FontFamily(typeface(st.font)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  ›", color = Classic.TextDim, fontSize = 20.sp)
            }
            HorizontalDivider(color = Classic.Divider)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(st.bold, { style { it.copy(bold = !it.bold) } }, label = { Text(stringResource(R.string.bold)) })
                FilterChip(st.italic, { style { it.copy(italic = !it.italic) } }, label = { Text(stringResource(R.string.italic)) })
            }

            ClassicSection(stringResource(R.string.tab_color))
            Column(Modifier.padding(horizontal = 12.dp)) {
                ColorPicker(stringResource(R.string.text_color), st.color, { c -> style { it.copy(color = c) } })
                SwitchRow(stringResource(R.string.gradient), (st.gradientColor ushr 24) != 0) { on -> style { it.copy(gradientColor = if (on) 0xFF1565C0.toInt() else 0) } }
                if ((st.gradientColor ushr 24) != 0) ColorPicker(stringResource(R.string.gradient_bottom), st.gradientColor, { c -> style { it.copy(gradientColor = c) } })
            }

            ClassicSection(stringResource(R.string.tab_outline))
            Column(Modifier.padding(horizontal = 12.dp)) {
                LabeledSlider(stringResource(R.string.outline_width), st.outlineWidth, 0f..0.4f, { "${(it * 100).roundToInt()}%" }) { v -> style { it.copy(outlineWidth = v) } }
                ColorPicker(stringResource(R.string.outline_color), st.outlineColor, { c -> style { it.copy(outlineColor = c) } })
                LabeledSlider(stringResource(R.string.outline2_width), st.outline2Width, 0f..0.3f, { "${(it * 100).roundToInt()}%" }) { v ->
                    style { it.copy(outline2Width = v, outline2Color = if ((it.outline2Color ushr 24) == 0) 0xFFFFFFFF.toInt() else it.outline2Color) }
                }
                if (st.outline2Width > 0f) ColorPicker(stringResource(R.string.outline2_color), st.outline2Color, { c -> style { it.copy(outline2Color = c) } })
            }

            ClassicSection(stringResource(R.string.tab_shadow))
            Column(Modifier.padding(horizontal = 12.dp)) {
                val on = (st.shadowColor ushr 24) != 0
                SwitchRow(stringResource(R.string.shadow), on) { enable -> style { it.copy(shadowColor = if (enable) 0x99000000.toInt() else 0) } }
                if (on) {
                    ColorPicker(stringResource(R.string.shadow_color), st.shadowColor, { c -> style { it.copy(shadowColor = c) } })
                    LabeledSlider(stringResource(R.string.shadow_x), st.shadowDx, -0.5f..0.5f) { v -> style { it.copy(shadowDx = v) } }
                    LabeledSlider(stringResource(R.string.shadow_y), st.shadowDy, -0.5f..0.5f) { v -> style { it.copy(shadowDy = v) } }
                    LabeledSlider(stringResource(R.string.shadow_blur), st.shadowBlur, 0f..0.6f) { v -> style { it.copy(shadowBlur = v) } }
                }
                ColorPicker(stringResource(R.string.background_box), st.backgroundColor, { c -> style { it.copy(backgroundColor = c) } })
            }

            ClassicSection(stringResource(R.string.tab_layout))
            Column(Modifier.padding(horizontal = 12.dp)) {
                LabeledSlider(stringResource(R.string.text_size), st.size, 0.02f..0.4f, { "${(it * 100).roundToInt()}" }) { v -> style { it.copy(size = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((a, label) in listOf(TextAlign.LEFT to R.string.align_left, TextAlign.CENTER to R.string.align_center, TextAlign.RIGHT to R.string.align_right))
                        FilterChip(st.align == a, { style { it.copy(align = a) } }, label = { Text(stringResource(label)) })
                }
                LabeledSlider(stringResource(R.string.line_height), st.lineHeight, 0.6f..2.5f) { v -> style { it.copy(lineHeight = v) } }
                LabeledSlider(stringResource(R.string.letter_spacing), st.letterSpacing, -0.1f..0.5f) { v -> style { it.copy(letterSpacing = v) } }
                LabeledSlider(stringResource(R.string.circular_wrap), draft.curve, -360f..360f, { "${it.roundToInt()}°" }) { v -> onDraft(draft.copy(curve = v.roundToInt().toFloat())) }
                LabeledSlider(stringResource(R.string.opacity), draft.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { v -> onDraft(draft.copy(opacity = v)) }
            }

            ClassicSection(stringResource(R.string.more_options))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onDateTime).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(stringResource(R.string.insert_datetime), color = Classic.Text, fontSize = 19.sp) }
            HorizontalDivider(color = Classic.Divider)
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Classic.Text, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

/** Font screen of the style tools: light list, each font drawn in itself, "More Fonts ›" imports. */
@Composable
fun FontScreen(fonts: List<FontOption>, selected: String, typeface: (String) -> Typeface, onPick: (String) -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Classic.LightRow).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        FontList(fonts, selected, typeface, onPick, onImport)
    }
}

/**
 * Detailed Word Balloon style (tap on the balloon preview): preview on top, then shape, balloon
 * colours (solid or gradient), outline, shadow, pointer, Font › and text options.
 */
@Composable
fun BalloonStyleScreen(
    draft: app.bocadillo.core.model.BalloonObject,
    renderer: ObjectRenderer,
    fonts: List<FontOption>,
    typeface: (String) -> Typeface,
    onDraft: (app.bocadillo.core.model.BalloonObject) -> Unit,
    onFonts: () -> Unit,
) {
    val b = draft
    val st = b.style
    val hint = stringResource(R.string.balloon_preview_hint)
    Column(Modifier.fillMaxSize().background(Classic.Menu)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(130.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Classic.LightPreview, Classic.LightHeader)))) {
            val preview = b.copy(
                transform = app.bocadillo.core.model.ObjectTransform(0.5f, 0.42f), width = 0.9f, height = 0.62f, tailX = -0.22f, tailY = 0.5f,
                text = b.text.ifBlank { hint }, style = st.copy(size = 0.11f),
            )
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).height(120.dp)) {
                drawIntoCanvas { renderer.draw(it.nativeCanvas, preview, size.width, size.height) }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            ClassicSection(stringResource(R.string.balloon_shape))
            BalloonShapeRow(b.shape, renderer) { onDraft(b.copy(shape = it)) }
            ClassicSection(stringResource(R.string.tab_balloon))
            Column(Modifier.padding(horizontal = 12.dp)) {
                ColorPicker(stringResource(R.string.fill_color), b.fillColor, { onDraft(b.copy(fillColor = it)) })
                SwitchRow(stringResource(R.string.gradient), (b.fillColor2 ushr 24) != 0) { on -> onDraft(b.copy(fillColor2 = if (on) 0xFF90CAF9.toInt() else 0)) }
                if ((b.fillColor2 ushr 24) != 0) ColorPicker(stringResource(R.string.gradient_bottom), b.fillColor2, { onDraft(b.copy(fillColor2 = it)) })
                ColorPicker(stringResource(R.string.outline_color), b.strokeColor, { onDraft(b.copy(strokeColor = it)) })
                LabeledSlider(stringResource(R.string.outline_width), b.strokeWidth, 0f..0.03f, { "${(it * 1000).roundToInt()}" }) { onDraft(b.copy(strokeWidth = it)) }
                SwitchRow(stringResource(R.string.shadow), (b.shadowColor ushr 24) != 0) { on -> onDraft(b.copy(shadowColor = if (on) 0x77000000 else 0)) }
                SwitchRow(stringResource(R.string.balloon_pointer), b.hasTail) { onDraft(b.copy(hasTail = it)) }
                if (b.hasTail) LabeledSlider(stringResource(R.string.pointer_width), b.tailWidth, 0.05f..0.9f, { "${(it * 100).roundToInt()}%" }) { onDraft(b.copy(tailWidth = it)) }
                LabeledSlider(stringResource(R.string.opacity), b.opacity, 0f..1f, { "${(it * 100).roundToInt()}%" }) { onDraft(b.copy(opacity = it)) }
                Text(stringResource(R.string.balloon_tail_hint), color = Classic.TextDim, fontSize = 13.sp)
            }
            ClassicSection(stringResource(R.string.tab_text))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onFonts).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tab_font), color = Classic.Text, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Text(fonts.firstOrNull { it.ref == st.font }?.displayName ?: st.font.substringAfter(':'), color = Classic.TextDim, fontSize = 18.sp, fontFamily = FontFamily(typeface(st.font)), maxLines = 1)
                Text("  ›", color = Classic.TextDim, fontSize = 20.sp)
            }
            Column(Modifier.padding(horizontal = 12.dp)) {
                ColorPicker(stringResource(R.string.text_color), st.color, { onDraft(b.copy(style = st.copy(color = it))) })
                LabeledSlider(stringResource(R.string.text_size), st.size, 0.02f..0.2f, { "${(it * 100).roundToInt()}" }) { onDraft(b.copy(style = st.copy(size = it))) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((a, l) in listOf(TextAlign.LEFT to R.string.align_left, TextAlign.CENTER to R.string.align_center, TextAlign.RIGHT to R.string.align_right))
                        FilterChip(st.align == a, { onDraft(b.copy(style = st.copy(align = a))) }, label = { Text(stringResource(l)) })
                }
                LabeledSlider(stringResource(R.string.line_height), st.lineHeight, 0.6f..2.5f) { onDraft(b.copy(style = st.copy(lineHeight = it))) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(st.bold, { onDraft(b.copy(style = st.copy(bold = !st.bold))) }, label = { Text(stringResource(R.string.bold)) })
                    FilterChip(st.italic, { onDraft(b.copy(style = st.copy(italic = !st.italic))) }, label = { Text(stringResource(R.string.italic)) })
                }
            }
        }
    }
}

/**
 * Classic Title Style panel (reference FUENTES_ORIGINAL_TITLE_STYLE): a white card over the ABC
 * grid with, in this order, Font · Text Size · Alignment · Line Height · Fill Color · Outline
 * Color. Numbers and colours open a small control under their row. "More options…" opens the
 * full style screen kept from 0.9 (gradient, shadow, curve…).
 */
@Composable
fun TitleStylePanel(
    draft: TextObject,
    fonts: List<FontOption>,
    typeface: (String) -> Typeface,
    onDraft: (TextObject) -> Unit,
    onFonts: () -> Unit,
    onMore: () -> Unit,
    onClose: () -> Unit,
) {
    val st = draft.style
    fun style(f: (TextStyle) -> TextStyle) = onDraft(draft.copy(style = f(st)))
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val ink = androidx.compose.ui.graphics.Color(0xFF111111)
    val line = androidx.compose.ui.graphics.Color(0xFFDDDDDD)
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x33000000)).clickable(
            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose,
        ),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).widthIn(max = 420.dp).fillMaxWidth()
                .androidxShadow().background(androidx.compose.ui.graphics.Color.White)
                .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }
                .heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        ) {
            @Composable fun row(label: String, key: String?, onClick: (() -> Unit)? = null, value: @Composable () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { if (onClick != null) onClick() else open = if (open == key) null else key }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    value()
                }
                HorizontalDivider(color = line)
            }
            val fontName = fonts.firstOrNull { it.ref == st.font }?.displayName ?: st.font.substringAfter(':')
            row(stringResource(R.string.tab_font), null, onClick = onFonts) {
                Text(fontName, color = ink, fontSize = 18.sp, fontFamily = FontFamily(typeface(st.font)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            }
            row(stringResource(R.string.text_size), "size") { Text("${(st.size * 100).roundToInt()}", color = ink, fontSize = 18.sp) }
            if (open == "size") Column(Modifier.padding(horizontal = 12.dp)) {
                LabeledSlider(stringResource(R.string.text_size), st.size, 0.02f..0.4f, { "${(it * 100).roundToInt()}" }) { v -> style { it.copy(size = v) } }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alignment), color = ink, fontSize = 18.sp, modifier = Modifier.weight(1f))
                for ((a, icon, label) in listOf(
                    Triple(TextAlign.LEFT, androidx.compose.material.icons.Icons.AutoMirrored.Filled.FormatAlignLeft, R.string.align_left),
                    Triple(TextAlign.CENTER, androidx.compose.material.icons.Icons.Filled.FormatAlignCenter, R.string.align_center),
                    Triple(TextAlign.RIGHT, androidx.compose.material.icons.Icons.AutoMirrored.Filled.FormatAlignRight, R.string.align_right),
                )) {
                    androidx.compose.material3.IconButton(onClick = { style { it.copy(align = a) } }) {
                        androidx.compose.material3.Icon(icon, stringResource(label), tint = if (st.align == a) androidx.compose.ui.graphics.Color(0xFF1E88E5) else ink)
                    }
                }
            }
            HorizontalDivider(color = line)
            row(stringResource(R.string.line_height), "line") { Text("${(st.lineHeight * 100).roundToInt()} %", color = ink, fontSize = 18.sp) }
            if (open == "line") Column(Modifier.padding(horizontal = 12.dp)) {
                LabeledSlider(stringResource(R.string.line_height), st.lineHeight, 0.6f..2.5f, { "${(it * 100).roundToInt()} %" }) { v -> style { it.copy(lineHeight = v) } }
            }
            row(stringResource(R.string.fill_color), "fill") { Swatch(st.color) }
            if (open == "fill") Column(Modifier.background(androidx.compose.ui.graphics.Color(0xFF262626)).padding(12.dp)) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides androidx.compose.ui.graphics.Color.White) { ColorEditor(st.color, { c -> style { it.copy(color = c) } }) } }
            row(stringResource(R.string.outline_color), "outline") { Swatch(st.outlineColor) }
            if (open == "outline") Column(Modifier.background(androidx.compose.ui.graphics.Color(0xFF262626)).padding(12.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides androidx.compose.ui.graphics.Color.White) {
                    ColorEditor(st.outlineColor, { c -> style { it.copy(outlineColor = c, outlineWidth = if (it.outlineWidth <= 0f) 0.08f else it.outlineWidth) } })
                }
                LabeledSlider(stringResource(R.string.outline_width), st.outlineWidth, 0f..0.4f, { "${(it * 100).roundToInt()}%" }) { v -> style { it.copy(outlineWidth = v) } }
            }
            row(stringResource(R.string.more_options), null, onClick = onMore) { Text("›", color = ink, fontSize = 22.sp) }
        }
    }
}

private fun Modifier.androidxShadow() = this.shadow(8.dp, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))

@Composable
private fun Swatch(c: Int) {
    androidx.compose.foundation.layout.Box(
        Modifier.padding(end = 2.dp).size(26.dp).background(androidx.compose.ui.graphics.Color(c or 0xFF000000.toInt()))
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFF9E9E9E)),
    )
}

/**
 * «More Fonts» (1.2.0, decisions 7–10). At the top, compact: «Importar fuentes», one short line
 * and a help button with the details; then the search box, Favourites, Recently used and the
 * whole list in alphabetical order (families with several styles open to show each style).
 * Every name is drawn in its own font, loaded in the background and kept in memory, so the list
 * and the search stay fast with hundreds of fonts. A tap applies the font at once (the caller
 * returns to the text); the star keeps it in Favourites; a long press deletes an imported font
 * (only those; the fonts inside the app cannot be deleted), warning about projects that use it.
 */
@Composable
fun MoreFontsScreen(
    fonts: List<FontOption>,
    selected: String,
    typeface: (String) -> Typeface,
    onPick: (String) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    cached: (String) -> Typeface? = { null },
    favorites: List<String> = emptyList(),
    recents: List<String> = emptyList(),
    onToggleFavorite: (String) -> Unit = {},
    importing: Pair<Int, Int>? = null,
    usage: suspend (String) -> List<String> = { emptyList() },
) {
    val L = Classic
    val ink = androidx.compose.ui.graphics.Color(0xFF111111)
    val dim = androidx.compose.ui.graphics.Color(0xFF666666)
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<FontOption?>(null) }
    var help by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val open = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // search keys computed once per font list, not on every keystroke
    val keys = androidx.compose.runtime.remember(fonts) { fonts.associate { it.ref to (it.displayName + " " + it.family).lowercase() } }
    val byRef = androidx.compose.runtime.remember(fonts) { fonts.associateBy { it.ref } }
    val alphabetical = androidx.compose.runtime.remember(fonts) {
        fonts.sortedWith(compareBy<FontOption>({ it.family.lowercase() }, { it.style.lowercase() }, { it.displayName.lowercase() }))
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) alphabetical else alphabetical.filter { keys[it.ref]?.contains(q) == true }
    val selectedFamily = byRef[selected]?.family?.lowercase()

    @Composable fun FontName(f: FontOption, text: String, size: Int, modifier: Modifier) {
        val tf by androidx.compose.runtime.produceState(cached(f.ref), f.ref) {
            if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { typeface(f.ref) }.getOrNull() }
        }
        Text(text, fontFamily = tf?.let { FontFamily(it) }, fontSize = size.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink, modifier = modifier)
    }

    @Composable fun FontRow(f: FontOption, text: String, size: Int, indent: Boolean, sectionKey: String) {
        val sel = f.ref == selected
        val fav = f.ref in favorites
        Row(
            Modifier.fillMaxWidth().background(if (sel) L.LightMore else if (indent) androidx.compose.ui.graphics.Color(0xFFF3F3F3) else L.LightRow)
                .combinedClickableCompat(onClick = { onPick(f.ref) }, onLongClick = if (f.kind == FontOption.Kind.IMPORTED) ({ confirm = f }) else null)
                .semantics { this.selected = sel; contentDescription = "$sectionKey ${f.displayName}" }
                .padding(start = if (indent) 30.dp else 10.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FontName(f, text, size, Modifier.weight(1f).padding(vertical = 10.dp))
            if (f.kind == FontOption.Kind.IMPORTED) Text(stringResource(R.string.fonts_imported_tag), color = dim, fontSize = 11.sp)
            val favLabel = stringResource(R.string.fonts_favorite_toggle, f.displayName)
            androidx.compose.material3.IconButton(onClick = { onToggleFavorite(f.ref) }, modifier = Modifier.semantics { contentDescription = favLabel; this.selected = fav }) {
                androidx.compose.material3.Icon(
                    if (fav) androidx.compose.material.icons.Icons.Filled.Star else androidx.compose.material.icons.Icons.Filled.StarBorder, null,
                    tint = if (fav) androidx.compose.ui.graphics.Color(0xFFF9A825) else androidx.compose.ui.graphics.Color(0xFF9E9E9E),
                )
            }
        }
        HorizontalDivider(color = L.LightDivider)
    }

    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().background(L.LightRow).navigationBarsPadding()) {
        @Composable fun header(t: String) = Text(t, color = L.LightHeaderText, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().background(L.LightHeader).padding(horizontal = 8.dp, vertical = 4.dp))
        // 1. import button, 2. one short line, 3. help (details in a dialog)
        item(key = "import") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Button(onClick = onImport, enabled = importing == null, modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, null)
                        Text(stringResource(R.string.fonts_import_button), fontSize = 17.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                    androidx.compose.material3.IconButton(onClick = { help = true }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.fonts_help_title), tint = dim)
                    }
                }
                Text(stringResource(R.string.fonts_import_short), color = dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (importing != null) {
                    val (d, t) = importing
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (t > 0) d.toFloat() / t else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "fonts_import_progress" },
                    )
                    Text(stringResource(R.string.fonts_importing, d, t), color = dim, fontSize = 12.sp)
                }
            }
        }
        item(key = "search") {
            androidx.compose.material3.OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.fonts_search)) },
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = ink, unfocusedTextColor = ink),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = "fonts_search" },
            )
        }
        if (q.isEmpty()) {
            val favs = favorites.mapNotNull { byRef[it] }.sortedBy { it.displayName.lowercase() }
            if (favs.isNotEmpty()) {
                item(key = "hfav") { header(stringResource(R.string.fonts_favorites) + "  (" + favs.size + ")") }
                for (f in favs) item(key = "fav" + f.ref) { FontRow(f, f.displayName, 22, false, "fav") }
            }
            val recs = recents.mapNotNull { byRef[it] }
            if (recs.isNotEmpty()) {
                item(key = "hrec") { header(stringResource(R.string.fonts_recent) + "  (" + recs.size + ")") }
                for (f in recs) item(key = "rec" + f.ref) { FontRow(f, f.displayName, 22, false, "rec") }
            }
        }
        item(key = "hall") { header(stringResource(R.string.fonts_all) + "  (" + shown.size + ")") }
        if (shown.isEmpty()) item { Text(stringResource(R.string.fonts_none_found), color = dim, fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
        // families in alphabetical order; a family of one font is a plain row
        val families = LinkedHashMap<String, MutableList<FontOption>>()
        for (f in shown) families.getOrPut(f.family.lowercase()) { ArrayList() } += f
        for ((fam, members) in families) {
            if (members.size == 1) {
                val f = members[0]
                item(key = "all" + f.ref) { FontRow(f, f.displayName, 22, false, "all") }
            } else {
                val expanded = open[fam] ?: (q.isNotEmpty() || fam == selectedFamily)
                val lead = members.firstOrNull { it.style.isEmpty() || it.style.equals("Regular", true) } ?: members[0]
                item(key = "g$fam") {
                    Row(
                        Modifier.fillMaxWidth().background(if (fam == selectedFamily) L.LightMore else L.LightRow).clickable { open[fam] = !expanded }
                            .padding(horizontal = 10.dp, vertical = 10.dp).semantics { contentDescription = "family ${lead.family}" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FontName(lead, lead.family, 22, Modifier.weight(1f))
                        Text(stringResource(R.string.fonts_styles_count, members.size) + if (expanded) "  ▴" else "  ▾", color = dim, fontSize = 13.sp)
                    }
                    HorizontalDivider(color = L.LightDivider)
                }
                if (expanded) for (f in members) item(key = "s" + f.ref) { FontRow(f, f.style.ifEmpty { f.displayName }, 19, true, "all") }
            }
        }
    }
    if (help) androidx.compose.material3.AlertDialog(
        onDismissRequest = { help = false },
        title = { Text(stringResource(R.string.fonts_help_title)) },
        text = { Text(stringResource(R.string.fonts_help_text), modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) },
        confirmButton = { androidx.compose.material3.TextButton({ help = false }) { Text(stringResource(R.string.ok)) } },
    )
    confirm?.let { f ->
        val used by androidx.compose.runtime.produceState<List<String>?>(null, f.ref) { value = usage(f.ref) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            text = {
                Column {
                    Text(stringResource(R.string.fonts_delete_q, f.displayName))
                    val u = used
                    if (u == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    else if (u.isNotEmpty()) Text(
                        stringResource(R.string.fonts_delete_used, u.size, u.take(5).joinToString(", ")),
                        color = androidx.compose.ui.graphics.Color(0xFFC62828), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "fonts_delete_used" },
                    )
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ onDelete(f.ref); confirm = null }, enabled = used != null) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
