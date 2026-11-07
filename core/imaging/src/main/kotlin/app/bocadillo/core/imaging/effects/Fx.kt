package app.bocadillo.core.imaging.effects

import app.bocadillo.core.imaging.PixelBuffer
import app.bocadillo.core.imaging.RenderContext
import app.bocadillo.core.imaging.ops.ImageOp
import app.bocadillo.core.imaging.ops.OpCategory
import app.bocadillo.core.model.ParamSpec
import app.bocadillo.core.model.Params

/**
 * Compact definition of a size-preserving effect. The formulas are this app's own: PicSay's pixel
 * code is native and was not decompiled, so only menu placement, parameter names, ranges and
 * defaults follow the original (docs/FASE1_INFORME_TECNICO.md §B2.2).
 */
class Fx(
    override val id: String,
    override val category: OpCategory,
    override val origin: ImageOp.Origin,
    override val params: List<ParamSpec>,
    private val identity: (Params) -> Boolean = { false },
    private val body: (src: PixelBuffer, p: Params, ctx: RenderContext) -> PixelBuffer,
) : ImageOp {
    override val geometric = false
    override fun isIdentity(p: Params) = identity(p)
    override fun apply(src: PixelBuffer, p: Params, ctx: RenderContext): PixelBuffer {
        val out = body(src, p, ctx)
        check(out.width == src.width && out.height == src.height) { "$id changed the size" }
        return out
    }
    override fun toString() = "Fx($id)"
}

internal fun Params.point(key: String = "center"): Pair<Float, Float> {
    val f = floats(key)
    return if (f != null && f.size >= 2) f[0] to f[1] else 0.5f to 0.5f
}

internal fun slider(key: String, label: String, min: Float, max: Float, step: Float, def: Float, unit: ParamSpec.Unit = ParamSpec.Unit.NONE, factor: Float = 1f) =
    ParamSpec.Slider(key, label, min, max, step, def, unit, factor)

internal fun percent(key: String, label: String, def: Float, min: Float = 0f, max: Float = 1f) =
    ParamSpec.Slider(key, label, min, max, 0.01f, def, ParamSpec.Unit.PERCENT, 100f)

internal fun center() = ParamSpec.Point("center", "center")
