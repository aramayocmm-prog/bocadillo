package app.bocadillo.core.imaging

import java.util.concurrent.CancellationException
import java.util.stream.IntStream

/**
 * Per-render settings.
 * @param scale size of the buffer being rendered relative to the full-resolution source
 *  (1.0 = export, < 1.0 = screen preview). Ops with pixel-sized params multiply by it so the
 *  preview matches the export.
 * @param isCancelled polled between row bands; a cancelled render throws [CancellationException].
 */
class RenderContext(
    val scale: Float = 1f,
    val parallel: Boolean = true,
    val isCancelled: () -> Boolean = { false },
) {
    init { require(scale > 0f) { "scale must be > 0" } }

    fun checkCancelled() {
        if (isCancelled()) throw CancellationException("Render cancelled")
    }

    /** Runs [body] over row bands [y0, y1), in parallel when allowed. */
    fun forRows(height: Int, body: (y0: Int, y1: Int) -> Unit) {
        val cores = Runtime.getRuntime().availableProcessors()
        val bands = if (!parallel) 1 else maxOf(1, minOf(cores * 4, height / 16))
        if (bands == 1) {
            checkCancelled(); body(0, height); return
        }
        IntStream.range(0, bands).parallel().forEach { i ->
            checkCancelled()
            body(height * i / bands, height * (i + 1) / bands)
        }
    }

    companion object {
        val DEFAULT = RenderContext()
    }
}
