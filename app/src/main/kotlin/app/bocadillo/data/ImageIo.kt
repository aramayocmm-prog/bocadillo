package app.bocadillo.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import app.bocadillo.core.imaging.PixelBuffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Picture decoding/encoding between Android bitmaps and the pure-Kotlin [PixelBuffer]. */
object ImageIo {

    /** Hard cap on imported file size (protects against absurd inputs). */
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024

    class Imported(val bytes: ByteArray, val mimeType: String, val displayName: String)

    class Decoded(val bitmap: Bitmap, val fullWidth: Int, val fullHeight: Int) {
        val scaled: Boolean get() = bitmap.width != fullWidth || bitmap.height != fullHeight
    }

    /** The name the user sees for a picked file (or the last part of the URI). */
    fun displayName(resolver: ContentResolver, uri: Uri): String {
        var name: String? = null
        runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) } }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Reads the raw bytes of a picture the user picked/shared (never re-encoded). */
    @Throws(IOException::class)
    fun read(resolver: ContentResolver, uri: Uri): Imported {
        val mime = resolver.getType(uri) ?: "image/jpeg"
        var name = "picture"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE_BYTES) throw IOException("File too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Cannot open $uri")
        return Imported(bytes, mime, name)
    }

    /**
     * Largest pixel count we can process: the working set is roughly the bitmap plus 3 int
     * buffers (≈16 bytes/pixel). Capped at 48 MP; never scales down below that unless memory
     * forces it, so normal photos keep their full resolution.
     */
    fun maxWorkingPixels(): Long {
        val budget = Runtime.getRuntime().maxMemory() / 2
        return minOf(48_000_000L, budget / 16).coerceAtLeast(4_000_000L)
    }

    /**
     * Decodes [bytes] (EXIF orientation is applied by ImageDecoder) to an sRGB ARGB_8888
     * software bitmap. If [maxSide] or [maxPixels] are given the result is scaled down to fit.
     */
    @Throws(IOException::class)
    fun decode(bytes: ByteArray, maxSide: Int? = null, maxPixels: Long? = null): Decoded {
        var fullW = 0; var fullH = 0
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            fullW = info.size.width; fullH = info.size.height
            var k = 1.0
            if (maxSide != null) k = minOf(k, maxSide.toDouble() / max(fullW, fullH))
            if (maxPixels != null) k = minOf(k, sqrt(maxPixels.toDouble() / (fullW.toLong() * fullH)))
            if (k < 1.0) decoder.setTargetSize(max(1, (fullW * k).roundToInt()), max(1, (fullH * k).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        return Decoded(argb, fullW, fullH)
    }

    /** Decodes at exactly [width]×[height] (the project's working size, see [maxWorkingPixels]). */
    @Throws(IOException::class)
    fun decodeExact(bytes: ByteArray, width: Int, height: Int): Decoded {
        var fullW = 0; var fullH = 0
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            fullW = info.size.width; fullH = info.size.height
            if (fullW != width || fullH != height) decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        return Decoded(argb, fullW, fullH)
    }

    /** Working size for a picture of [w]×[h]: full size unless it exceeds [maxWorkingPixels]. */
    /** Size of a w×h picture scaled down (never up) to at most [maxPixels], proportions kept. */
    fun fitPixels(w: Int, h: Int, maxPixels: Long): Pair<Int, Int> {
        val px = w.toLong() * h
        if (px <= maxPixels) return w to h
        val k = sqrt(maxPixels.toDouble() / px)
        return max(1, (w * k).toInt()) to max(1, (h * k).toInt())
    }

    fun workingSize(w: Int, h: Int): Pair<Int, Int> {
        val max = maxWorkingPixels()
        val px = w.toLong() * h
        if (px <= max) return w to h
        val k = sqrt(max.toDouble() / px)
        return max(1, (w * k).toInt()) to max(1, (h * k).toInt())
    }

    fun toPixelBuffer(bitmap: Bitmap): PixelBuffer {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) // non-premultiplied ARGB
        return PixelBuffer(bitmap.width, bitmap.height, px)
    }

    fun toBitmap(buffer: PixelBuffer): Bitmap =
        Bitmap.createBitmap(buffer.pixels, buffer.width, buffer.height, Bitmap.Config.ARGB_8888)

    enum class Format(val mimeType: String, val extension: String) {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png")
    }

    /**
     * Encodes [buffer]. JPEG has no alpha, so translucent pixels are composited over
     * [jpegBackground]; PNG keeps transparency.
     */
    fun encode(buffer: PixelBuffer, format: Format, quality: Int, jpegBackground: Int): ByteArray =
        encodeBitmap(toBitmap(buffer), format, quality, jpegBackground)

    /** Mutable ARGB copy of [buffer] that objects can be drawn onto. */
    fun toMutableBitmap(buffer: PixelBuffer): Bitmap =
        Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(buffer.pixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        }

    /** Encodes and recycles [src]. */
    fun encodeBitmap(src: Bitmap, format: Format, quality: Int, jpegBackground: Int): ByteArray {
        val buffer = src
        val out = ByteArrayOutputStream(buffer.width * buffer.height / 4)
        when (format) {
            Format.PNG -> src.compress(Bitmap.CompressFormat.PNG, 100, out)
            Format.JPEG -> {
                val flat = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(jpegBackground); drawBitmap(src, 0f, 0f, null) }
                flat.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
                flat.recycle()
            }
        }
        src.recycle()
        return out.toByteArray()
    }

    /** Small JPEG used for the gallery grid and stored in the project file. */
    fun thumbnail(buffer: PixelBuffer, maxSide: Int = 360): ByteArray = thumbnail(toBitmap(buffer), maxSide)

    /** Thumbnail from a bitmap (recycled afterwards). */
    fun thumbnail(bmp: Bitmap, maxSide: Int = 360): ByteArray {
        val buffer = bmp
        val k = minOf(1f, maxSide.toFloat() / max(buffer.width, buffer.height))
        val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (buffer.width * k).roundToInt()), max(1, (buffer.height * k).roundToInt()), true) else bmp
        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(0xFFFFFFFF.toInt()); drawBitmap(scaled, 0f, 0f, null) }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle(); flat.recycle()
        return out.toByteArray()
    }

    /** Solid-colour PNG used as the source of a "blank canvas" project. */
    fun blankPng(width: Int, height: Int, argb: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
