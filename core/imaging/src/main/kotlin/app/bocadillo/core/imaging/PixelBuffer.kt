package app.bocadillo.core.imaging

/**
 * 32-bit ARGB image, non-premultiplied (same layout as Android `Bitmap.getPixels`).
 * Operations treat buffers as immutable values: they never modify their input.
 */
class PixelBuffer(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "Invalid size ${width}x$height" }
        require(pixels.size == width * height) { "Pixel array size mismatch" }
    }

    val size: Int get() = width * height

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }

    fun copy(): PixelBuffer = PixelBuffer(width, height, pixels.copyOf())

    fun hasTransparency(): Boolean = pixels.any { (it ushr 24) != 0xFF }

    companion object {
        fun filled(width: Int, height: Int, argb: Int): PixelBuffer =
            PixelBuffer(width, height, IntArray(width * height) { argb })
    }
}

internal fun alpha(c: Int) = c ushr 24
internal fun red(c: Int) = (c shr 16) and 0xFF
internal fun green(c: Int) = (c shr 8) and 0xFF
internal fun blue(c: Int) = c and 0xFF
internal fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
internal fun clamp255(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
internal fun clamp255(v: Float): Int { val i = (v + 0.5f).toInt(); return if (v < 0f) 0 else if (i > 255) 255 else i }

/** Converts to premultiplied ARGB (for correct interpolation of transparent pixels). */
internal fun premultiply(c: Int): Int {
    val a = alpha(c)
    if (a == 255) return c
    if (a == 0) return 0
    return argb(a, (red(c) * a + 127) / 255, (green(c) * a + 127) / 255, (blue(c) * a + 127) / 255)
}

internal fun unpremultiply(c: Int): Int {
    val a = alpha(c)
    if (a == 255 || a == 0) return if (a == 0) 0 else c
    return argb(a, minOf(255, (red(c) * 255 + a / 2) / a), minOf(255, (green(c) * 255 + a / 2) / a), minOf(255, (blue(c) * 255 + a / 2) / a))
}
