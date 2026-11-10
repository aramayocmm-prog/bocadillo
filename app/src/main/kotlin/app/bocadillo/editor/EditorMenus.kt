package app.bocadillo.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bocadillo.R
import app.bocadillo.core.imaging.objects.StickerCatalog
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.imaging.preview.ToolPreviews
import app.bocadillo.core.model.BalloonObject
import app.bocadillo.core.model.BalloonShape
import app.bocadillo.core.model.ObjectTransform
import app.bocadillo.data.NotoStickers
import app.bocadillo.render.ObjectRenderer
import app.bocadillo.ui.classic.ClassicDims
import app.bocadillo.ui.classic.ClassicIcon
import app.bocadillo.ui.classic.ClassicIconView
import app.bocadillo.ui.classic.ClassicMenuRow
import app.bocadillo.ui.classic.ClassicSection
import app.bocadillo.ui.theme.Classic
import java.util.Locale

/** The four menus of the bottom bar plus the balloon-shape grid opened from Sticker. */
enum class EditorMenu { ADJUST, EFFECT, STICKER, EXPORT, BALLOON_SHAPES }

/** What a menu row does. */
sealed interface MenuAction {
    data class Op(val id: String) : MenuAction
    data class Draw(val kind: DrawKind) : MenuAction
    data object Mask : MenuAction
    data object Restore : MenuAction
    data object InsertPicture : MenuAction
    data object Location : MenuAction
}

/** A row: label and either a drawn icon or a preview thumbnail of the op. */
class MenuEntry(val label: Int, val action: MenuAction, val icon: ClassicIcon? = null)

/** A section; [title] null = no header (first block of a menu). */
class MenuSection(val title: Int?, val entries: List<MenuEntry>)

/**
 * Menu contents in the order of PicSay Pro 1.8.0.5 (FASE1 §B2.1–B2.2), keeping every tool that
 * already works. Tools of the original that are not implemented yet are not listed (no buttons
 * without a function).
 */
object EditorMenus {
    private fun op(id: String, icon: ClassicIcon? = null) = MenuEntry(Labels.op(id), MenuAction.Op(id), icon)

    val adjust = listOf(
        MenuSection(null, listOf(
            op("exposure_contrast", ClassicIcon.EXPOSURE), op("saturation", ClassicIcon.SATURATION),
            op("temperature_tint", ClassicIcon.TEMPERATURE), op("boost", ClassicIcon.BOOST),
            op("sharpen", ClassicIcon.SHARPEN), op("smooth", ClassicIcon.SMOOTH),
        )),
        MenuSection(R.string.section_transform_picture, listOf(
            op("crop", ClassicIcon.CROP), op("resize", ClassicIcon.RESIZE), op("orient", ClassicIcon.ROTATE_FLIP),
        )),
        MenuSection(R.string.cat_misc, listOf(
            op("curves", ClassicIcon.CURVES), op("adjust_rgb", ClassicIcon.RGB), op("brightness", ClassicIcon.BRIGHTNESS),
            op("fix_red_eye", ClassicIcon.RED_EYE), op("blur", ClassicIcon.BLUR),
            MenuEntry(R.string.mask_title, MenuAction.Mask, ClassicIcon.MASK),
            op("add_background_color", ClassicIcon.BACKGROUND),
            MenuEntry(R.string.history_paint, MenuAction.Draw(DrawKind.HISTORY), ClassicIcon.HISTORY),
            MenuEntry(R.string.picture_location, MenuAction.Location, ClassicIcon.LOCATION),
            // additions of this app (not in the original menu)
            op("rotate", ClassicIcon.ROTATE_FREE),
            MenuEntry(R.string.restore_original, MenuAction.Restore, ClassicIcon.RESTORE),
        )),
    )

    val effect = listOf(
        MenuSection(null, listOf(
            MenuEntry(R.string.paint, MenuAction.Draw(DrawKind.BRUSH)),
            MenuEntry(R.string.insert_picture, MenuAction.InsertPicture),
            op("pop_color"),
        )),
        MenuSection(R.string.cat_distort, listOf("warp_brush", "spherize", "twirl", "bulge", "circle_splash", "stretch", "stretch_v", "stretch_h", "mirror").map { op(it) }),
        MenuSection(R.string.section_color_adjustments, listOf("hue", "colorize", "grayscale", "black_white", "duotone", "rainbow", "blend_color").map { op(it) }),
        MenuSection(R.string.cat_artistic, listOf("comic_print", "posterize", "pop_art", "pencil_sketch", "neon").map { op(it) }),
        MenuSection(R.string.cat_photo, listOf("lomo", "cross_process", "gritty", "fading_colors", "film_grain", "red_scale", "faux_hdr", "sepia", "invert", "tilt_shift", "soft_glow").map { op(it) }),
        MenuSection(R.string.cat_frames, listOf("vignette", "focal_blur", "top_lens", "edge_lens", "spotlight", "instant", "film", "rounded_corners", "solid_border", "matte").map { op(it) }),
        MenuSection(R.string.cat_misc, listOf("pixelize", "x_ray", "dizziness", "heat_map").map { op(it) }),
    )

    /** Ops that appear in a menu; anything else in the registry is appended so no tool is lost. */
    fun withLeftovers(registry: OpRegistry): Pair<List<MenuSection>, List<MenuSection>> {
        val listed = (adjust + effect).flatMap { it.entries }.mapNotNull { (it.action as? MenuAction.Op)?.id }.toSet()
        val extra = registry.all.filter { it.id !in listed && it.category != OpCategory.DRAW }
        if (extra.isEmpty()) return adjust to effect
        return adjust to (effect + MenuSection(R.string.menu_more, extra.map { op(it.id) }))
    }
}

/** Black full-height list of a menu with sections, icons or preview thumbnails. */
@Composable
fun MenuList(sections: List<MenuSection>, registry: OpRegistry, onAction: (MenuAction) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Classic.Menu)) {
        for (s in sections) {
            if (s.title != null) item(key = "h${s.title}") { ClassicSection(stringResource(s.title)) }
            for (e in s.entries) item(key = "e${e.label}") {
                ClassicMenuRow(stringResource(e.label), { onAction(e.action) }) {
                    when {
                        e.icon != null -> ClassicIconView(e.icon)
                        e.action is MenuAction.Op -> registry[e.action.id]?.let { OpThumb(it) }
                        e.action is MenuAction.Draw -> DrawThumb(ToolPreviews.DrawDemo.valueOf(e.action.kind.name))
                        e.action is MenuAction.InsertPicture -> InsertThumb()
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(key: String, compute: (app.bocadillo.core.imaging.PixelBuffer) -> app.bocadillo.core.imaging.PixelBuffer) {
    // narrow portrait preview of the edited photo, as in the original lists
    val k = ToolTileCache.photoKey(key)
    val bmp by produceState(ToolTileCache.cached(k), k) { if (value == null) value = ToolTileCache.get(k, compute) }
    Box(Modifier.size(ClassicDims.ThumbW, ClassicDims.ThumbH).background(Classic.Cell)) {
        bmp?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Insert Picture: the sample with a smaller copy of itself pasted on top. */
@Composable
fun InsertThumb() = Thumb("insert") { s ->
    val small = app.bocadillo.core.imaging.Resample.resize(s, s.width / 2, s.height / 2)
    val out = s.pixels.copyOf()
    val ox = s.width / 2 - 6; val oy = s.height / 2 - 6
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val edge = x < 2 || y < 2 || x >= small.width - 2 || y >= small.height - 2
        out[(oy + y) * s.width + ox + x] = if (edge) 0xFFFFFFFF.toInt() else small.pixels[y * small.width + x]
    }
    app.bocadillo.core.imaging.PixelBuffer(s.width, s.height, out)
}

@Composable
fun OpThumb(op: ImageOp) = Thumb("op:${op.id}") { ToolPreviews.forOp(op, it) }

@Composable
fun DrawThumb(demo: ToolPreviews.DrawDemo) = Thumb("draw:${demo.name}") { ToolPreviews.forDraw(demo, it) }

/**
 * Export (share) list of the original: the picture's file name at the top, Save picture to album,
 * Save as file…, then every installed app that accepts a picture (icon + name), alphabetically.
 * "Set picture as…" is an addition of this app.
 */
@Composable
fun ExportMenuList(title: String, onTarget: (ExportTarget) -> Unit, onApp: (android.content.ComponentName) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by produceState<List<Triple<String, android.content.ComponentName, androidx.compose.ui.graphics.ImageBitmap?>>>(emptyList()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/*")
            pm.queryIntentActivities(send, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { ri ->
                    val icon = runCatching { ri.loadIcon(pm).toBitmapSafe(96)?.asImageBitmap() }.getOrNull()
                    Triple(ri.loadLabel(pm).toString(), android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), icon)
                }
                .sortedBy { it.first.lowercase() }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(Classic.Menu)) {
        item {
            Box(Modifier.fillMaxWidth().background(Classic.Bar).padding(start = 52.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
                Text(
                    (title.ifBlank { "picture" }) + ".jpg", color = androidx.compose.ui.graphics.Color(0xFF333333), fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(0xFFDDDDDD)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        item { ClassicMenuRow(stringResource(R.string.export_menu_album), { onTarget(ExportTarget.GALLERY) }) { Icon(Icons.Filled.Collections, null, tint = Classic.Accent, modifier = Modifier.size(30.dp)) } }
        item { ClassicMenuRow(stringResource(R.string.export_menu_file), { onTarget(ExportTarget.DOCUMENT) }) { Icon(Icons.Filled.InsertDriveFile, null, tint = androidx.compose.ui.graphics.Color(0xFFFFC107), modifier = Modifier.size(30.dp)) } }
        item { ClassicMenuRow(stringResource(R.string.export_menu_set_as), { onTarget(ExportTarget.SET_AS) }) { Icon(Icons.Filled.Wallpaper, null, tint = Classic.Text, modifier = Modifier.size(30.dp)) } }
        items(apps.size) { i ->
            val (label, cn, icon) = apps[i]
            ClassicMenuRow(label, { onApp(cn) }) {
                if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(30.dp)) else Icon(Icons.Filled.Share, null, tint = Classic.Text, modifier = Modifier.size(30.dp))
            }
        }
    }
}

private fun android.graphics.drawable.Drawable.toBitmapSafe(px: Int): android.graphics.Bitmap? {
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    setBounds(0, 0, px, px); draw(c)
    return b
}

@Suppress("unused") private val keepRow: @Composable () -> Unit = { Row {} }
