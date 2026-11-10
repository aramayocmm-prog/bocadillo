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
            op("rotate", ClassicIcon.ROTATE_FREE),
        )),
        MenuSection(R.string.cat_misc, listOf(
            op("adjust_rgb", ClassicIcon.RGB), op("brightness", ClassicIcon.BRIGHTNESS), op("blur", ClassicIcon.BLUR),
            MenuEntry(R.string.mask_title, MenuAction.Mask, ClassicIcon.MASK),
            MenuEntry(R.string.history_paint, MenuAction.Draw(DrawKind.HISTORY), ClassicIcon.HISTORY),
            MenuEntry(R.string.restore_original, MenuAction.Restore, ClassicIcon.RESTORE),
        )),
    )

    val effect = listOf(
        MenuSection(null, listOf(
            MenuEntry(R.string.brush, MenuAction.Draw(DrawKind.BRUSH)),
            MenuEntry(R.string.marker, MenuAction.Draw(DrawKind.MARKER)),
            MenuEntry(R.string.eraser_transparent, MenuAction.Draw(DrawKind.ERASER)),
        )),
        MenuSection(R.string.cat_distort, listOf("spherize", "twirl", "bulge", "circle_splash", "stretch", "stretch_v", "stretch_h", "mirror").map { op(it) }),
        MenuSection(R.string.menu_color, listOf("hue", "colorize", "grayscale", "black_white", "duotone", "rainbow", "blend_color").map { op(it) }),
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
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(key: String, compute: (app.bocadillo.core.imaging.PixelBuffer) -> app.bocadillo.core.imaging.PixelBuffer) {
    val bmp by produceState(ToolTileCache.cached(key), key) { if (value == null) value = ToolTileCache.get(key, compute) }
    Box(Modifier.size(ClassicDims.Thumb + 10.dp, ClassicDims.Thumb).clip(RoundedCornerShape(3.dp)).background(Classic.Cell)) {
        bmp?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun OpThumb(op: ImageOp) = Thumb("op:${op.id}") { ToolPreviews.forOp(op, it) }

@Composable
fun DrawThumb(demo: ToolPreviews.DrawDemo) = Thumb("draw:${demo.name}") { ToolPreviews.forDraw(demo, it) }

/** Export menu (FASE1 §3.6): album, file, share, set as. */
@Composable
fun ExportMenuList(onTarget: (ExportTarget) -> Unit) {
    Column(Modifier.fillMaxSize().background(Classic.Menu)) {
        for ((t, label, icon) in listOf(
            Triple(ExportTarget.GALLERY, R.string.export_menu_album, Icons.Filled.Save),
            Triple(ExportTarget.DOCUMENT, R.string.export_menu_file, Icons.Filled.InsertDriveFile),
            Triple(ExportTarget.SHARE, R.string.export_menu_share, Icons.Filled.Share),
            Triple(ExportTarget.SET_AS, R.string.export_menu_set_as, Icons.Filled.Wallpaper),
        )) ClassicMenuRow(stringResource(label), { onTarget(t) }) { Icon(icon, null, tint = Classic.Text, modifier = Modifier.size(30.dp)) }
    }
}

/** Grid of the 6 balloon shapes on dark grey cells (two columns). */
@Composable
fun BalloonShapeGrid(renderer: ObjectRenderer, onPick: (BalloonShape) -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize().background(Classic.Menu).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(BalloonShape.entries) { sh ->
            val sample = BalloonObject("cell", ObjectTransform(0.5f, 0.42f), sh, width = 0.8f, height = 0.62f, tailX = -0.18f, tailY = 0.46f, strokeWidth = 0.03f)
            Column(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Classic.Cell).border(1.dp, Classic.Divider, RoundedCornerShape(6.dp))
                    .clickable { onPick(sh) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.5f)) { drawIntoCanvas { renderer.draw(it.nativeCanvas, sample, size.width, size.height) } }
                Text(stringResource(balloonShapeLabel(sh)), color = Classic.TextDim, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Suppress("unused") private val keepRow: @Composable () -> Unit = { Row {} }
