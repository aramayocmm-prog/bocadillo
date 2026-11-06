package app.bocadillo.core.imaging

import kotlin.math.cos
import kotlin.math.sin

/**
 * 4x5 color matrix with the same convention as android.graphics.ColorMatrix:
 * R' = m0*R + m1*G + m2*B + m3*A + m4 (offset in 0..255 units), etc.
 */
class ColorMatrix(val m: FloatArray = identityArray()) {
    init { require(m.size == 20) }

    /** Returns this ∘ other: other is applied first, then this. */
    fun after(other: ColorMatrix): ColorMatrix {
        val a = m; val b = other.m
        val r = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var s = if (col == 4) a[row * 5 + 4] else 0f
                for (k in 0 until 4) s += a[row * 5 + k] * b[k * 5 + col]
                r[row * 5 + col] = s
            }
        }
        return ColorMatrix(r)
    }

    fun apply(src: PixelBuffer, ctx: RenderContext): PixelBuffer {
        val out = IntArray(src.size)
        val p = src.pixels
        val k = m
        ctx.forRows(src.height) { y0, y1 ->
            for (i in y0 * src.width until y1 * src.width) {
                val c = p[i]
                val a = alpha(c).toFloat(); val r = red(c).toFloat(); val g = green(c).toFloat(); val b = blue(c).toFloat()
                out[i] = argb(
                    clamp255(k[15] * r + k[16] * g + k[17] * b + k[18] * a + k[19]),
                    clamp255(k[0] * r + k[1] * g + k[2] * b + k[3] * a + k[4]),
                    clamp255(k[5] * r + k[6] * g + k[7] * b + k[8] * a + k[9]),
                    clamp255(k[10] * r + k[11] * g + k[12] * b + k[13] * a + k[14]),
                )
            }
        }
        return PixelBuffer(src.width, src.height, out)
    }

    companion object {
        // Rec. 709 luma weights.
        const val LR = 0.2126f
        const val LG = 0.7152f
        const val LB = 0.0722f

        fun identityArray() = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        fun identity() = ColorMatrix()

        fun scaleOffset(sr: Float, sg: Float, sb: Float, offset: Float = 0f) = ColorMatrix(floatArrayOf(
            sr, 0f, 0f, 0f, offset,
            0f, sg, 0f, 0f, offset,
            0f, 0f, sb, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))

        /** s = 0 → grayscale, 1 → unchanged, 2 → doubled saturation. */
        fun saturation(s: Float): ColorMatrix {
            val i = 1f - s
            return ColorMatrix(floatArrayOf(
                LR * i + s, LG * i, LB * i, 0f, 0f,
                LR * i, LG * i + s, LB * i, 0f, 0f,
                LR * i, LG * i, LB * i + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }

        /** Luminance-preserving hue rotation (degrees). */
        fun hueRotation(degrees: Float): ColorMatrix {
            val rad = Math.toRadians(degrees.toDouble())
            val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
            return ColorMatrix(floatArrayOf(
                LR + c * (1 - LR) + s * (-LR), LG + c * (-LG) + s * (-LG), LB + c * (-LB) + s * (1 - LB), 0f, 0f,
                LR + c * (-LR) + s * 0.143f, LG + c * (1 - LG) + s * 0.140f, LB + c * (-LB) + s * (-0.283f), 0f, 0f,
                LR + c * (-LR) + s * (-(1 - LR)), LG + c * (-LG) + s * LG, LB + c * (1 - LB) + s * LB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }

        fun invert() = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))

        fun sepia() = ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))

        /** Linear interpolation between two matrices (t = 0 → a, 1 → b). */
        fun lerp(a: ColorMatrix, b: ColorMatrix, t: Float) =
            ColorMatrix(FloatArray(20) { a.m[it] + (b.m[it] - a.m[it]) * t })
    }
}

/** Applies a per-channel lookup table (alpha untouched). */
internal fun applyLut(src: PixelBuffer, lr: IntArray, lg: IntArray, lb: IntArray, ctx: RenderContext): PixelBuffer {
    val out = IntArray(src.size)
    val p = src.pixels
    ctx.forRows(src.height) { y0, y1 ->
        for (i in y0 * src.width until y1 * src.width) {
            val c = p[i]
            out[i] = (c and -0x1000000) or (lr[red(c)] shl 16) or (lg[green(c)] shl 8) or lb[blue(c)]
        }
    }
    return PixelBuffer(src.width, src.height, out)
}

internal object Srgb {
    private val toLinearTable = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    fun toLinear(v: Int): Float = toLinearTable[v]

    fun fromLinear(l: Float): Float {
        val c = l.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else (1.055 * Math.pow(c.toDouble(), 1 / 2.4) - 0.055).toFloat()
    }
}
