package app.bocadillo.core.imaging.ops

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params

/** Menu families. [ORIGINAL_*] mirror PicSay menus; [MODERN] holds added tools kept apart. */
enum class OpCategory { ADJUST, TRANSFORM, COLOR, DISTORT, ARTISTIC, PHOTO, FRAMES, MISC, MODERN }

/**
 * A raster operation. Implementations must be pure: never mutate [src] and return a new
 * buffer. Geometric ops may change the size; non-geometric ops must preserve it (required for
 * masks).
 *
 * @property id stable identifier stored in project files (never rename).
 * @property origin documents where the behaviour comes from (for the equivalence matrix).
 */
interface ImageOp {
    val id: String
    val category: OpCategory
    val params: List<ParamSpec>
    val geometric: Boolean
    val origin: Origin

    fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer

    /** Output size for a given input size, used by the UI and by mask scaling. */
    fun outputSize(w: Int, h: Int, p: Params, ctx: RenderContext): Pair<Int, Int> = w to h

    /** True when the params produce no visible change, so the op can be skipped. */
    fun isIdentity(p: Params): Boolean = false

    enum class Origin {
        /** Present in PicSay Lite and Pro (verified by static analysis). */
        PICSAY_BOTH,
        /** Present only in PicSay Pro 1.8.0.5. */
        PICSAY_PRO,
        /** New tool, not in the original app. */
        NEW,
    }
}

/** Registry of available operations. New tools are added by registering, nothing else. */
class OpRegistry(ops: List<ImageOp>) {
    private val byId: Map<String, ImageOp> = ops.associateBy { it.id }

    init { require(byId.size == ops.size) { "Duplicate op ids" } }

    val all: List<ImageOp> = ops

    operator fun get(id: String): ImageOp? = byId[id]

    fun inCategory(c: OpCategory): List<ImageOp> = all.filter { it.category == c }

    companion object {
        /** All ops implemented so far (stage 1–2: transforms + colour adjustments). */
        fun default(): OpRegistry = OpRegistry(TransformOps.all + ColorOps.all)
    }
}
