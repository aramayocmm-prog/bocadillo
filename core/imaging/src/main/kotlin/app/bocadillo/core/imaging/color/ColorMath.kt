package app.bocadillo.core.imaging.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour conversions of the unified colour picker (1.2.0, decision 5): ARGB ints ↔ HSB (hue
 * 0…360°, saturation and brightness 0…1) and HEX text. Pure Kotlin so it is tested on the JVM.
 */
object ColorMath {
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** HSB → ARGB; [alpha] 0…1. */
    fun hsb(h: Float, s: Float, v: Float, alpha: Float = 1f): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val ss = s.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = vv - c
        return argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), ((r1 + m) * 255f).roundToInt(), ((g1 + m) * 255f).roundToInt(), ((b1 + m) * 255f).roundToInt())
    }

    /** ARGB → (hue, saturation, brightness). Grey colours have hue 0. */
    fun toHsb(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    /** "#RRGGBB", or "#AARRGGBB" when the colour is not opaque (or [alwaysAlpha]). */
    fun toHex(c: Int, alwaysAlpha: Boolean = false): String =
        if (alwaysAlpha || alpha(c) != 0xFF) "#%08X".format(c.toLong() and 0xFFFFFFFFL) else "#%06X".format(c and 0xFFFFFF)

    /** Parses "#RGB", "#RRGGBB", "#AARRGGBB" (the # is optional, case and spaces ignored); null if invalid. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#").uppercase()
        if (t.isEmpty() || t.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return when (t.length) {
            3 -> { val r = t[0].digitToInt(16); val g = t[1].digitToInt(16); val b = t[2].digitToInt(16); argb(255, r * 17, g * 17, b * 17) }
            6 -> (0xFF000000.toInt()) or t.toInt(16)
            8 -> t.toLong(16).toInt()
            else -> null
        }
    }

    /** Most recent first, no duplicates, at most [max]. */
    fun pushRecent(list: List<Int>, c: Int, max: Int = 12): List<Int> = (listOf(c) + list.filter { it != c }).take(max)
}
