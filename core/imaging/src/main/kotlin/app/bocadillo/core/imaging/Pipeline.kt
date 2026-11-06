package app.bocadillo.core.imaging

import app.bocadillo.core.imaging.ops.MaskParams
import app.bocadillo.core.imaging.ops.OpRegistry
import app.bocadillo.core.imaging.ops.StagedOp
import app.bocadillo.core.model.MaskData
import app.bocadillo.core.model.OpInstance

/** Masked blending: result = input + (filtered − input) × coverage. */
object MaskBlend {
    fun apply(input: PixelBuffer, filtered: PixelBuffer, mask: MaskData, inverted: Boolean, ctx: RenderContext): PixelBuffer {
        require(input.width == filtered.width && input.height == filtered.height) { "Masked op must keep size" }
        val m = if (mask.width == input.width && mask.height == input.height) mask else scaleMask(mask, input.width, input.height)
        val out = IntArray(input.size)
        val a = input.pixels; val b = filtered.pixels; val d = m.data
        ctx.forRows(input.height) { y0, y1 ->
            for (i in y0 * input.width until y1 * input.width) {
                var k = d[i].toInt() and 0xFF
                if (inverted) k = 255 - k
                out[i] = when (k) {
                    0 -> a[i]
                    255 -> b[i]
                    else -> {
                        val ca = a[i]; val cb = b[i]
                        fun ch(s: Int) = (((ca ushr s) and 0xFF) * (255 - k) + ((cb ushr s) and 0xFF) * k + 127) / 255
                        argb(ch(24), ch(16), ch(8), ch(0))
                    }
                }
            }
        }
        return PixelBuffer(input.width, input.height, out)
    }

    /** Bilinear rescale of a coverage mask (masks are painted at preview size). */
    fun scaleMask(mask: MaskData, w: Int, h: Int): MaskData {
        val out = ByteArray(w * h)
        val sx = mask.width.toDouble() / w; val sy = mask.height.toDouble() / h
        for (y in 0 until h) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, mask.height - 1.0)
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, mask.height - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, mask.width - 1.0)
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, mask.width - 1); val tx = fx - x0
                fun v(xx: Int, yy: Int) = (mask.data[yy * mask.width + xx].toInt() and 0xFF).toDouble()
                val top = v(x0, y0) * (1 - tx) + v(x1, y0) * tx
                val bot = v(x0, y1) * (1 - tx) + v(x1, y1) * tx
                out[y * w + x] = (top * (1 - ty) + bot * ty + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        return MaskData(w, h, out)
    }
}

/**
 * Replays a document's operations on a source buffer.
 *
 * Results of op prefixes are memoized (LRU), so while the user drags a slider of the last op
 * only that op is recomputed. Returned buffers are shared with the cache: callers must treat
 * them as read-only.
 */
class PipelineRenderer(private val registry: OpRegistry = OpRegistry.default(), private val cacheSize: Int = 4) {

    private data class Key(val sourceToken: Any, val scale: Float, val ops: List<OpInstance>)

    private val cache = object : LinkedHashMap<Key, PixelBuffer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PixelBuffer>?) = size > cacheSize
    }

    /** Op ids that were skipped because they are unknown to this version. */
    val skippedOps = mutableSetOf<String>()

    @Synchronized fun clearCache() = cache.clear()

    /**
     * @param sourceToken identity of [source] (e.g. "proxy-v1"); change it when the source changes.
     */
    fun render(
        source: PixelBuffer,
        sourceToken: Any,
        ops: List<OpInstance>,
        masks: Map<String, MaskData>,
        ctx: RenderContext,
    ): PixelBuffer {
        val active = ops.filter { op -> registry[op.opId]?.isIdentity(op.params) != true }
        var start = 0
        var current = source
        synchronized(this) {
            for (k in active.size downTo 1) {
                val hit = cache[Key(sourceToken, ctx.scale, active.subList(0, k).toList())]
                if (hit != null) { start = k; current = hit; break }
            }
        }
        for (i in start until active.size) {
            ctx.checkCancelled()
            val inst = active[i]
            val op = registry[inst.opId]
            if (op == null) { skippedOps += inst.opId; continue }
            val filtered = if (op is StagedOp) {
                // History Paint: result of the first n ops of the *document* list
                op.apply(current, inst.params, ctx) { n -> if (n in 0..ops.size) render(source, sourceToken, ops.subList(0, n).toList(), masks, ctx) else null }
            } else op.apply(current, inst.params, ctx)
            val mask = if (op.geometric) null else inst.maskId?.let { masks[it] } ?: MaskParams.build(inst.params, current.width, current.height, ctx)
            current = if (mask != null) MaskBlend.apply(current, filtered, mask, inst.maskInverted, ctx) else filtered
            // keep the last two prefixes: previous (for live tweaking of the last op) and final
            if (i >= active.size - 2) synchronized(this) {
                cache[Key(sourceToken, ctx.scale, active.subList(0, i + 1).toList())] = current
            }
        }
        return current
    }
}
