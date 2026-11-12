package app.bocadillo.render

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Runs the live-stroke work (1.1.0). While each update is cheap it runs at once inside the touch
 * event on the main thread, so the result is drawn in the same frame (lowest latency). When an
 * update takes longer than [budgetMs] (very thick brush, fast finger, slow phone) the next ones
 * run on a background thread, one at a time, so the main thread is never blocked; it goes back
 * to the fast path as soon as updates are cheap again.
 *
 * Each job computes into the session's buffers and returns the work to do on the main thread
 * (uploading the changed tiles). Intermediate jobs may be skipped when newer ones are waiting;
 * final jobs (finger lifted, stroke cancelled) are never skipped and keep their order.
 */
class LivePump(private val budgetMs: Float = 4f) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "live-stroke").apply { priority = Thread.MAX_PRIORITY - 1 } }
    private var busy = false
    private val queue = ArrayDeque<Pair<() -> (() -> Unit)?, Boolean>>()
    private var lastMs = 0f
    /** Time of the last job (ms) and whether it ran in the background, for the timings log. */
    var onTiming: ((Float, Boolean) -> Unit)? = null

    /** Main thread. [job] computes (any thread) and returns what to apply on the main thread. */
    fun submit(final: Boolean, job: () -> (() -> Unit)?) {
        if (busy) {
            // a newer intermediate update replaces a waiting intermediate one
            if (!final && queue.isNotEmpty() && !queue.last().second) queue.removeLast()
            queue.addLast(job to final)
            return
        }
        if (lastMs <= budgetMs) {
            val t0 = System.nanoTime()
            val apply = job()
            lastMs = (System.nanoTime() - t0) / 1e6f
            onTiming?.invoke(lastMs, false)
            apply?.invoke()
            return
        }
        busy = true
        exec.execute {
            val t0 = System.nanoTime()
            val apply = runCatching { job() }.getOrNull()
            val ms = (System.nanoTime() - t0) / 1e6f
            main.post {
                lastMs = ms
                onTiming?.invoke(ms, true)
                apply?.invoke()
                busy = false
                queue.removeFirstOrNull()?.let { (j, f) -> submit(f, j) }
            }
        }
    }

    /** True while work is waiting or running in the background. */
    val pending: Boolean get() = busy || queue.isNotEmpty()

    fun reset() { lastMs = 0f }
}
